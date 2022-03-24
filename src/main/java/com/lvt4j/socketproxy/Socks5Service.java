package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.SocketProxyApp.format;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.common.primitives.Shorts;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * socks5协议代理
 * @author LV on 2022年3月22日
 * @see <a href="https://wiyi.org/socks5-protocol-in-deep.html">参考</a>
 * @see <a href="https://wiyi.org/socks5-implementation.html">参考</a>
 */
@Slf4j
@Service
public class Socks5Service implements InfoContributor {

    @Autowired
    private Config config;
    
    private Map<Integer, ServerThread> instances = new HashMap<>();
    
    @PostConstruct
    private synchronized void init() {
        Config.changeCallback_socks5 = this::init;
        for(Integer port : config.getSocks5()){
            if(instances.containsKey(port)) continue;
            try{
                instances.put(port, new ServerThread(port));
            }catch(Exception e){
                log.error("启动服务线程[{}]失败", port, e);
            }
        }
        Set<Integer> removeds = instances.keySet().stream().filter(k->!config.getSocks5().contains(k)).collect(toSet());
        for(Integer removed : removeds){
            ServerThread s = instances.remove(removed);
            if(s!=null) s.destory();
        }
    }
    
    @PreDestroy
    private synchronized void destory() {
        Config.changeCallback_socks5 = null;
        instances.values().forEach(ServerThread::destory);
    }
    
    @Scheduled(cron="* * * * * ?")
    public synchronized void cleanIdle() {
        instances.values().forEach(ServerThread::cleanIdle);
    }
    
    @Override
    public void contribute(Builder builder) {
        builder.withDetail("socks5", instances.values().stream().collect(toMap(s->s.port, s->s.info())));
    }
    
    private class ServerThread extends Thread {
        private final int port;
        
        private final ServerSocket server;

        private List<Connect> connects = Collections.synchronizedList(new LinkedList<>());
        
        public ServerThread(int port) throws IOException {
            this.port = port;
            try{
                server = new ServerSocket(port);
            }catch(IOException e){
                throw new IOException(String.format("启动服务端口[%s]失败", port), e);
            }
            log.info("{} socket5 proxy start", port);
            setName(port+" server");
            start();
        }
        @Override
        public void run() {
            while(!server.isClosed()){
                Socket src;
                try{
                    src = server.accept();
                }catch(Exception e) {
                    if((e instanceof SocketException) && StringUtils.containsAny(e.getMessage(), "Socket closed", "Socket is closed", "socket closed")){
                        return;
                    }
                    log.error("{} server socket accept err", port, e);
                    continue;
                }
                try{
                    connects.add(new Connect(port, src));
                }catch(Exception e){
                    log.error("{} server socket initial connect error", port, e);
                }
            }
        }
        public void destory() {
            synchronized (connects) {
                for(Connect connect : connects){
                    connect.destory();
                }
                connects.clear();
                try{
                    server.close();
                }catch(Exception e){
                    log.warn("{} server socket close err", port, e);
                }
                try{
                    join(1000);
                }catch(Exception e){
                    log.warn("{} server socket wait accept thread destory timeout", port, e);
                }
                log.info("{} ServerSocket destoried", port);
            }
        }
        public void cleanIdle() {
            synchronized (connects) {
                List<Connect> cleaned = new LinkedList<>();
                for(Connect connect : connects){
                    if(System.currentTimeMillis()-connect.latestTouchTime()<config.getMaxIdleTime()) continue;
                    connect.destory();
                    cleaned.add(connect);
                }
                connects.removeAll(cleaned);
            }
        }
        public Map<String, List<String>> info() {
            return connects.stream().collect(groupingBy(c->c.targetStr, mapping(c->c.direction, toList())));
        }
        
        @Override
        public String toString() {
            return String.format("%s cnns: %s", port, connects.size());
        }
        
    }
    private class Connect extends Thread implements Closeable {
        private final int serverPort;
        private final Socket src;
        private InputStream srcIn;
        private OutputStream srcOut;
        
        private Socket target;
        private String targetStr = "uninitialized";
        
        private String direction;
        
        private IOTransmitterThread src2Target;
        private IOTransmitterThread target2Src;
        
        private final long createTime = System.currentTimeMillis();
        
        public Connect(int serverPort, Socket src) throws IOException {
            this.serverPort = serverPort;
            this.src = src;
            direction = String.format("%s->%s->%s->%s", format(src.getRemoteSocketAddress()), format(src.getLocalSocketAddress()), "unknown", "unknown");
            setName(String.format("%s connect from %s", serverPort, format(src.getRemoteSocketAddress())));
            start();
        }
        @Override @SneakyThrows
        public void run() {
            this.srcIn = src.getInputStream();
            this.srcOut = src.getOutputStream();
            
            handshake();
            //(allmost never)TODO 认证阶段
            target();
            
            direction = String.format("%s->%s->%s->%s", format(src.getRemoteSocketAddress()), format(src.getLocalSocketAddress())
                ,target.getLocalPort(), format(target.getRemoteSocketAddress()));
            
            src2Target = new IOTransmitterThread(serverPort+"->"+targetStr, log, srcIn, target.getOutputStream());
            target2Src = new IOTransmitterThread(serverPort+"<-"+targetStr, log, target.getInputStream(), srcOut);
            
            log.info("{} connected {}", serverPort, direction);
        }
        /**
         * 握手阶段：获取认证方法 并 响应是否进入认证阶段，或无需认证跳过认证阶段
         * <pre>
         * 接受的三个参数分别是
         * ver(1字节):         socket版本
         * nmethods(1字节):     认证方法字段的字节数
         * methods(1至255字节): 认证方法，数组格式，一个字节为表示一个客户端支持的方法，nmethods的值为多少，methods就有多少个字节。可能有的值：
         *  0x00 ： NO AUTHENTICATION REQUIRED                       无身份验证
         *  0x01 ： GSSAPI                                         未知
         *  0x02 ： USERNAME/PASSWORD                               用户名/密码
         *  0x03 ： to X’7F’ IANA ASSIGNED
         *  0x80 ： to X’FE’ RESERVED FOR PRIVATE METHODS
         *  0xFF ： NO ACCEPTABLE METHODS                           无可用方法
         *  
         * 响应为选中一个METHOD返回给客户端，格式如下
         *  ver(1字节):         socket版本
         *  method(1字节):      选择使用哪种认证方法
         *  当客户端收到0x00时，会跳过认证阶段直接进入请求阶段
         *  当收到0xFF时，直接断开连接
         *  其他的值进入到对应的认证阶段
         *  </pre>
         */
        private void handshake() throws IOException{
            int ver = srcIn.read();
            if(ver!=5){ //仅支持socket版本5
                srcOut.write(new byte[] {5, -1});
                throw new IOException(String.format("no acceptable socket ver : %s", ver));
            }
            int nmethods = srcIn.read();
            if(nmethods==0){
                srcOut.write(new byte[] {5,-1});
                throw new IOException(String.format("no acceptable nmethods : %s", nmethods));
            }
            byte[] methods = new byte[nmethods];
            srcIn.read(methods);
            if(!ArrayUtils.contains(methods, (byte)0)){ //仅支持无身份验证
                srcOut.write(new byte[] {5,-1});
                throw new IOException(String.format("only accept no auth but : %s", Arrays.toString(methods)));
            }
            
            srcOut.write(new byte[] {5,0});
        }
        /**
         * 请求阶段：获取要建立连接的目标的地址&协议&端口
         * <pre>
         * 客户端传输过来的参数
         * ver(1字节):         socket版本
         * cmd(1字节):         请求类型，可选值
         *  0x01 ： CONNECT请求
         *  0x02 ： BIND请求
         *  0x03 ： UDP转发
         * rsv(1字节):         保留字段，固定值为0x00
         * ATYP(1字节):        目标地址类型，可选值
         *  0x01 ： IPv4地址，DST.ADDR为4个字节
         *  0x03 ： 域名，DST.ADDR是一个可变长度的域名
         *  0x04 ： IPv6地址，DST.ADDR为16个字节长度
         * DST.ADDR(不定):      目标地址
         * DST.PORT(2字节):     目标端口
         * 
         * 服务端响应
         * ver(1字节):         socket版本
         * rep(1字节):         响应码，可选值
         *  0x00 ： succeeded
         *  0x01 ： general SOCKS server failure
         *  0x02 ： connection not allowed by ruleset
         *  0x03 ： Network unreachable
         *  0x04 ： Host unreachable
         *  0x05 ： Connection refused
         *  0x06 ： TTL expired
         *  0x07 ： Command not supported
         *  0x08 ： Address type not supported
         *  0x09 ： to X’FF’ unassigned
         * RSV(1字节):         保留字段，固定值为0x00
         * ATYPE(1字节):       同请求的ATYPE
         * BND.ADDR 服务绑定的地址
         * BND.PORT 服务绑定的端口DST.PORT
         * </pre>
         */
        private void target() throws IOException {
            int ver = srcIn.read();
            if(ver!=5){ //仅支持socket版本5
                srcOut.write(new byte[] {5,1,0,1, 0,0,0,0, 0,0});
                throw new IOException(String.format("no acceptable socket ver : %s", ver));
            }
            int cmd = srcIn.read();
            if(cmd!=1){ //仅支持CONNECT请求
                srcOut.write(new byte[] {5,1,0,1, 0,0,0,0, 0,0});
                throw new IOException(String.format("no acceptable cmd : %s", cmd));
            }
            @SuppressWarnings("unused")
            int rsv = srcIn.read();
            int atyp = srcIn.read();
            InetAddress addr;
            switch(atyp){
            case 1: //ipv4
                byte[] ipv4 = new byte[4];
                srcIn.read(ipv4);
                addr = InetAddress.getByAddress(ipv4);
                break;
            case 3: //域名
                int domainLen = srcIn.read();
                byte[] domainBs = new byte[domainLen];
                srcIn.read(domainBs);
                String domain = new String(domainBs);
                addr = InetAddress.getByName(domain);
                break;
            case 4: //ipv6
                byte[] ipv6 = new byte[16];
                srcIn.read(ipv6);
                addr = Inet6Address.getByAddress(ipv6);
                break;
            default:
                srcOut.write(new byte[] {5,1,0,1, 0,0,0,0, 0,0});
                throw new IOException(String.format("no acceptable addr type : %s", atyp));
            }
            byte[] portBs = new byte[2];
            srcIn.read(portBs);
            short port = Shorts.fromByteArray(portBs);
            
            this.targetStr = format(addr)+":"+port;
            try{
                this.target = new Socket(addr, port);
            }catch(IOException e){
                srcOut.write(new byte[] {5,1,0,1, 0,0,0,0, 0,0});
                throw new IOException(String.format("连接目标失败[%s]", targetStr), e);
            }
            srcOut.write(new byte[]{5,0,0,1, 0,0,0,0, 0,0});
        }
        
        private long latestTouchTime() {
            return ObjectUtils.max(createTime,
                Optional.ofNullable(src2Target).map(t->t.latestTouchTime).orElse(null),
                Optional.ofNullable(target2Src).map(t->t.latestTouchTime).orElse(null));
        }
        
        @Override
        public void close() throws IOException {
            destory();
        }
        private synchronized void destory() {
            try{
                if(src!=null && !src.isClosed()) src.close();
            }catch(Exception e){
                log.warn("{} connection {} close src err", serverPort, direction, e);
            }
            try{
                if(target!=null && !target.isClosed()) target.close();
            }catch(Exception e){
                log.warn("{} connection {} close err", serverPort, direction, e);
            }
            try{
                if(this.isAlive()) this.join(1000);
            }catch(Exception e){
                log.warn("{} connection {} wait connect thread destory timeout", serverPort, direction, e);
            }
            try{
                if(src2Target!=null && src2Target.isAlive()) src2Target.join(1000);
            }catch(Exception e){
                log.warn("{} connection {} wait src to target data transfer thread destory timeout", serverPort, direction, e);
            }
            try{
                if(target2Src!=null && target2Src.isAlive()) target2Src.join(1000);
            }catch(Exception e){
                log.warn("{} connection {} wait target to src data transfer thread destory timeout", serverPort, direction, e);
            }
            log.info("{} connection {} closed", serverPort, direction);
        }
    }
    
}