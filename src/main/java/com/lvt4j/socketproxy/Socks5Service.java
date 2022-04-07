package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProxyApp.format;
import static com.lvt4j.socketproxy.ProxyApp.isCloseException;
import static com.lvt4j.socketproxy.ProxyApp.port;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Shorts;

import lombok.extern.slf4j.Slf4j;

/**
 * socks5协议代理
 * @author LV on 2022年3月22日
 * @see <a href="https://wiyi.org/socks5-protocol-in-deep.html">参考</a>
 * @see <a href="https://wiyi.org/socks5-implementation.html">参考</a>
 * @author LV on 2022年4月2日
 */
@Slf4j
@Service
public class Socks5Service implements InfoContributor {

    private static class Msg {
        private static final byte[] NoAcc = {5, -1};
        private static final byte[] Acc = {5, 0};
        
        private static final byte[] Fail = {5,1,0,1, 0,0,0,0, 0,0};
        private static final byte[] Suc = {5,0,0,1, 0,0,0,0, 0,0};
    }
    
    private static final byte NoAuth = 0;
    
    @Autowired
    private Config config;
    @Autowired
    private ChannelReader reader;
    @Autowired
    private ChannelWriter writer;
    @Autowired
    private ChannelAcceptor acceptor;
    @Autowired
    private ChannelConnector connector;
    
    private Map<Integer, ServerMeta> servers = new HashMap<>();
    
    @PostConstruct
    private void init() throws IOException {
        Config.changeCallback_socks5 = this::reloadConfig;
        
        reloadConfig();
    }
    private synchronized void reloadConfig() {
        Set<Integer> socks5 = config.getSocks5();
        for(int port : socks5){
            if(servers.containsKey(port)) continue;
            try{
                servers.put(port, new ServerMeta(port));
                log.info("{} socks5代理启动", port);
            }catch(Exception e){
                log.error("{} socks5代理启动失败", port, e);
            }
        }
        ImmutableSet.copyOf(servers.keySet()).stream().filter(p->!socks5.contains(p)).forEach(removed->{
            ServerMeta s = servers.remove(removed);
            if(s!=null) s.destory();
        });
    }
    @PreDestroy
    private synchronized void destory() throws IOException {
        Config.changeCallback_socks5 = null;
        ImmutableSet.copyOf(servers.values()).forEach(ServerMeta::destory);
    }
    
    @Scheduled(cron="0/10 * * * * ?")
    public synchronized void cleanIdle() {
        servers.values().forEach(ServerMeta::cleanIdle);
    }
    
    @Override
    public void contribute(Builder builder) {
        builder.withDetail("socks5", servers.values().stream().collect(toMap(s->s.port, s->s.info())));
    }
    
    
    private class ServerMeta {
        
        private int port;
        private ServerSocketChannel serverSocketChannel;
        
        private ChannelTransmitter src2target;
        private ChannelTransmitter target2src;
        
        private List<ConnectMeta> connections = Collections.synchronizedList(new LinkedList<>());
        
        public ServerMeta(int port) throws IOException {
            this.port = port;
            
            try{
                serverSocketChannel = ProxyApp.server(port);
                
                src2target = new ChannelTransmitter(port+" s->t");
                target2src = new ChannelTransmitter(port+" t->s");
                
                acceptor.accept(serverSocketChannel, this::accept, e->log.error("establish connection err", e));
            }catch(Exception e){
                destory();
                throw e;
            }
        }
        private void accept(SocketChannel src) throws IOException {
            connections.add(new ConnectMeta(src));
        }
        
        public void destory() {
            ImmutableSet.copyOf(connections).forEach(ConnectMeta::destory);
            if(src2target!=null) src2target.destory();
            if(target2src!=null) target2src.destory();
            ProxyApp.close(serverSocketChannel);
            servers.remove(port);
            log.info("{} socks5代理停止", port);
        }
        
        public void cleanIdle() {
            synchronized (connections) {
                for(ConnectMeta connect : ImmutableSet.copyOf(connections)){
                    if(System.currentTimeMillis()-connect.latestTouchTime<config.getMaxIdleTime()) continue;
                    connect.destory();
                }
            }
        }

        public Object info() {
            return connections.stream().collect(groupingBy(c->c.targetStr, mapping(c->c.direction, toList())));
        }
        
        private class ConnectMeta {
            private final SocketChannel src;
            
            private InetAddress targetHost;
            private int targetPort;
            private SocketChannel target;
            private String targetStr;
            
            private String direction;
            
            private long latestTouchTime = System.currentTimeMillis();
            
            public ConnectMeta(SocketChannel src) throws IOException {
                this.src = src;
                
                try{
                    src.configureBlocking(false);
                    
                    targetStr = "initializing";
                    direction = String.format("%s->%s->%s->%s"
                            ,format(src.getRemoteAddress()), port(src.getLocalAddress())
                            ,"initializing" ,"initializing");
                    
                    if(log.isTraceEnabled()) log.trace("{} connecting {}", port, direction);
                    
                    handshake();
                }catch(IOException e){
                    destory();
                    throw e;
                }
                
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
            private void handshake() {
                reader.readOne(src, ver->{
                    if(ver!=5){ //仅支持socket版本5
                        log.error("no acceptable socket ver : {}", ver);
                        writer.write(src, Msg.NoAcc, this::destory, this::onException);
                        return;
                    }
                    reader.readOne(src, nmethods->{
                        if(nmethods==0){ //客户端不支持任何认证方法
                            log.error("no acceptable nmethods : {}", nmethods);
                            writer.write(src, Msg.NoAcc, this::destory, this::onException);
                            return;
                        }
                        reader.readUntilLength(src, Byte.toUnsignedInt(nmethods), methods->{
                            if(!ArrayUtils.contains(methods, NoAuth)){ //目前仅支持无身份验证，但客户端不支持无身份验证
                                log.error("only accept no auth but : {}", Arrays.toString(methods));
                                writer.write(src, Msg.NoAcc, this::destory, this::onException);
                                return;
                            }
                            
                            writer.write(src, Msg.Acc, this::target, this::onException);
                        }, this::onException);
                    }, this::onException);
                    
                }, this::onException);
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
             * ATYPE(1字节):       服务绑定的地址类型，可选值同请求的ATYP
             * BND.ADDR 服务绑定的地址
             * BND.PORT 服务绑定的端口DST.PORT
             * </pre>
             */
            private void target() {
                reader.readOne(src, ver->{
                    if(ver!=5){ //仅支持socket版本5
                        log.error("no acceptable socket ver : {}", ver);
                        writer.write(src, Msg.Fail, this::destory, this::onException);
                        return;
                    }
                    reader.readOne(src, cmd->{
                        if(cmd!=1){ //仅支持CONNECT请求
                            log.error("no acceptable cmd : {}", cmd);
                            writer.write(src, Msg.Fail, this::destory, this::onException);
                            return;
                        }
                        reader.readOne(src, rsv->{
                            reader.readOne(src, atyp->{
                                switch(atyp){
                                case 1: //ipv4
                                    reader.readUntilLength(src, 4, ipv4->{
                                        targetHost = Inet4Address.getByAddress(ipv4);
                                        target_port();
                                    }, this::onException);
                                    break;
                                case 3: //域名
                                    reader.readOne(src, len->{
                                        if(len==0){
                                            log.error("no acceptable domain len : {}", len);
                                            writer.write(src, Msg.Fail, this::destory, this::onException);
                                        }else{
                                            reader.readUntilLength(src, Byte.toUnsignedInt(len), domainBs->{
                                                String domain = new String(domainBs);
                                                targetHost = InetAddress.getByName(domain);
                                                target_port();
                                            }, this::onException);
                                        }
                                    }, this::onException);
                                    break;
                                case 4: //ipv6
                                    reader.readUntilLength(src, 16, ipv6->{
                                        targetHost = Inet6Address.getByAddress(ipv6);
                                        target_port();
                                    }, this::onException);
                                    break;
                                default:
                                    log.error("no acceptable addr type : {}", atyp);
                                    writer.write(src, Msg.Fail, this::destory, this::onException);
                                    return;
                                }
                            }, this::onException);
                            
                        }, this::onException);
                    }, this::onException);
                    
                }, this::onException);
            }
            private void target_port() {
                reader.readUntilLength(src, 2, portBs->{
                    targetPort = Short.toUnsignedInt(Shorts.fromByteArray(portBs));
                    targetStr = format(targetHost)+":"+targetPort;
                    target = SocketChannel.open();
                    target.configureBlocking(false);
                    target.connect(new InetSocketAddress(targetHost, targetPort));
                    
                    connector.connect(target, this::target_finish, e->{
                        writer.write(src, Msg.Fail, ()->onException(e), this::onException);
                    });
                }, this::onException);
            }
            private void target_finish() {
                writer.write(src, Msg.Suc, ()->{
                    direction = String.format("%s->%s->%s->%s"
                        ,format(src.getRemoteAddress()), port(src.getLocalAddress())
                        ,port(target.getLocalAddress()), format(target.getRemoteAddress()));
                    
                    src2target.transmit(src, target, 1024, this::onTrans, this::onException);
                    target2src.transmit(target, src, 1024, this::onTrans, this::onException);
                    
                    log.info("{} connected {}", port, direction);
                }, this::onException);
            }
            
            private void onTrans() {
                latestTouchTime = System.currentTimeMillis();
            }
            
            private synchronized void onException(Exception e) {
                if(!isCloseException(e)) log.error("connection {} err", direction, e);
                destory();
            }
            
            private void destory() {
                ProxyApp.close(src);
                ProxyApp.close(target);
                connections.remove(this);
                
                log.info("{} disconnected {}", port, direction);
            }
        }
    }
}
