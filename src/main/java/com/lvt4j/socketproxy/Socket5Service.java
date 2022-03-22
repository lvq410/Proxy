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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.common.primitives.Shorts;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * socket5协议代理
 * @author LV on 2022年3月22日
 */
@Slf4j
@Service
public class Socket5Service implements InfoContributor {

    @Autowired
    private Config config;
    
    private Map<Integer, ServerThread> instances = new HashMap<>();
    
    @PostConstruct
    private synchronized void init() {
        Config.changeCallback_socket5 = this::init;
        for(Integer port : config.getSocket5s()){
            if(instances.containsKey(port)) continue;
            try{
                instances.put(port, new ServerThread(port));
            }catch(Exception e){
                log.error("启动服务线程[{}]失败", port, e);
            }
        }
        Set<Integer> removeds = instances.keySet().stream().filter(k->!config.getSocket5s().contains(k)).collect(toSet());
        for(Integer removed : removeds){
            ServerThread s = instances.remove(removed);
            if(s!=null) s.destory();
        }
    }
    
    @PreDestroy
    private synchronized void destory() {
        Config.changeCallback_socket5 = null;
        instances.values().forEach(ServerThread::destory);
    }
    
    @Scheduled(cron="* * * * * ?")
    public synchronized void cleanIdle() {
        instances.values().forEach(ServerThread::cleanIdle);
    }
    
    @Override
    public void contribute(Builder builder) {
        builder.withDetail("socket5s", instances.values().stream().collect(toMap(s->s.port, s->s.info())));
    }
    
    class ServerThread extends Thread {
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
            log.info("{} ServerSocket start", port);
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
                    if(System.currentTimeMillis()-connect.latestTouchTime<config.getMaxIdleTime()) continue;
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
    class Connect implements Closeable {
        private final int serverPort;
        private final Socket src;
        private final InputStream srcIn;
        private final OutputStream srcOut;
        
        private final Socket target;
        private final String targetStr;
        
        private final String direction;
        
        private TransferThread src2Target;
        private TransferThread target2Src;
        
        private long latestTouchTime = System.currentTimeMillis();
        
        public Connect(int serverPort, Socket src) throws IOException {
            this.serverPort = serverPort;
            this.src = src;
            
            this.srcIn = src.getInputStream();
            this.srcOut = src.getOutputStream();
            
            acceptAuth();
            String targetDomain = targetDomain();
            short targetPort = targetPort();
            targetStr = targetDomain+":"+targetPort;
            try{
                this.target = new Socket(targetDomain, targetPort);
            }catch(IOException e){
                throw new IOException(String.format("连接目标失败[%s]", targetStr), e);
            }
            responseStatus();
            
            direction = String.format("%s->%s->%s->%s", format(src.getRemoteSocketAddress()), format(src.getLocalSocketAddress())
                ,target.getLocalPort(), format(target.getRemoteSocketAddress()));
            
            src2Target = new TransferThread(srcIn, target.getOutputStream());
            src2Target.setName(serverPort+" "+targetStr+" s->t"); src2Target.setDaemon(true);
            src2Target.start();
            target2Src = new TransferThread(target.getInputStream(), srcOut);
            target2Src.setName(serverPort+" "+targetStr+" t->s"); target2Src.setDaemon(true);
            target2Src.start();
            
            log.info("{} connected {}", serverPort, direction);
        }
        /**
         * 获取认证方法并通过认证 默认为无身份认证
         * 接受的三个参数分别是
         * ver:socket版本(5)--1字节
         * nmethods:在下一个参数的方法数 --1字节
         * methods:方法 --1至255字节
         *  X’00’ NO AUTHENTICATION REQUIRED                            无身份验证
         *  X’01’ GSSAPI                                                                    未知
         *  X’02’ USERNAME/PASSWORD                                                 用户名/密码
         *  X’03’ to X’7F’ IANA ASSIGNED                                    保留位
         *  X’80’ to X’FE’ RESERVED FOR PRIVATE METHODS             私有位
         *  X’FF’ NO ACCEPTABLE METHODS                     没有可用方法
         */
        private void acceptAuth() throws IOException{
            byte[] b = new byte[3];
            srcIn.read(b);
            if(b[0]==5&&b[1]==1&&b[2]==0) {
                srcOut.write(new byte[] {5,0});
            }
        }
        /**
         * 获取域名
         * 域名前一位为域名字符串的长度 每个字符为一个字节
         * 长度前四位分别是 
         * ver:socket版本(5) 
         * cmd:sock命令码(1 tcp,2 bind,3 udp) 
         * rsv:保留字段
         * atyp:地址类型(ipv4 1,域名 3,ipv6 4)
         * @return
         */
        
        private String targetDomain() throws IOException {
            byte[] c = new byte[4];
            srcIn.read(c);
            int domainLen = srcIn.read();
            byte[] domainarr = new byte[domainLen];
            srcIn.read(domainarr);
            return new String(domainarr);
        }
        
        /**
         * 获取端口号
         * 一般在域名或地址之后的两个字符 所以用short类型返回
         */
        private short targetPort() throws IOException {  
            byte[] portarr=new byte[2];
            srcIn.read(portarr);
            return Shorts.fromByteArray(portarr);
        }
        /**
         * 向源主机发送此次请求的状态 是否成功等
         * 一定要在源主机向服务器发送完域名和端口信息的下一条发送
         */
        public void responseStatus() throws IOException {
            byte[] status = {0x05,0x00,0x00,0x01,0x00,0x00,0x00,0x00,0x00,0x00};
            srcOut.write(status);
        }
        
        @AllArgsConstructor
        class TransferThread extends Thread {
            private InputStream is;
            private OutputStream os;
            
            @Override @SneakyThrows
            public void run() {
                int n = 0; byte[] buffer = new byte[1024];
                try{
                    while (-1 != (n = is.read(buffer))) {
                        os.write(buffer, 0, n);
                        latestTouchTime = System.currentTimeMillis();
                    }
                }catch(SocketException e){
                    if(e.getMessage().contains("Socket closed")) return;
                    log.error("{} connection {} trans data err", serverPort, direction ,e);
                }
            }
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