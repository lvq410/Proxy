package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.SocketProxyApp.format;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * http协议代理
 * @author LV on 2022年3月22日
 * @see <a href="https://imququ.com/post/web-proxy.html">参考1</a>
 * @see <a href="https://github.com/stefano-lupo/Java-Proxy-Server/blob/master/src/RequestHandler.java">参考2</a>
 */
@Slf4j
@Service
public class HttpService implements InfoContributor {

    @Autowired
    private Config config;
    
    private Map<Integer, ServerThread> instances = new HashMap<>();
    
    @PostConstruct
    private synchronized void init() {
        Config.changeCallback_http = this::init;
        for(Integer port : config.getHttp()){
            if(instances.containsKey(port)) continue;
            try{
                instances.put(port, new ServerThread(port));
            }catch(Exception e){
                log.error("启动服务线程[{}]失败", port, e);
            }
        }
        Set<Integer> removeds = instances.keySet().stream().filter(k->!config.getHttp().contains(k)).collect(toSet());
        for(Integer removed : removeds){
            ServerThread s = instances.remove(removed);
            if(s!=null) s.destory();
        }
    }
    
    @PreDestroy
    private synchronized void destory() {
        Config.changeCallback_http = null;
        instances.values().forEach(ServerThread::destory);
    }
    
    @Scheduled(cron="* * * * * ?")
    public synchronized void cleanIdle() {
        instances.values().forEach(ServerThread::cleanIdle);
    }
    
    @Override
    public void contribute(Builder builder) {
        builder.withDetail("http", instances.values().stream().collect(toMap(s->s.port, s->s.info())));
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
            log.info("{} http proxy start", port);
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
            
            String statusLine = readline(srcIn);
            String[] split = statusLine.split(" ",3);
            Validate.isTrue(split.length==3, "非法的http请求状态行：%s", statusLine);
            if("CONNECT".equals(split[0])){
                initHttps(split);
            }else{
                initHttp(split, statusLine);
            }
            
            direction = String.format("%s->%s->%s->%s", format(src.getRemoteSocketAddress()), format(src.getLocalSocketAddress())
                ,target.getLocalPort(), format(target.getRemoteSocketAddress()));
            
            src2Target = new IOTransmitterThread(serverPort+"->"+targetStr, log, srcIn, target.getOutputStream());
            target2Src = new IOTransmitterThread(serverPort+"<-"+targetStr, log, target.getInputStream(), srcOut);
            
            log.info("{} connected {}", serverPort, direction);
        }

        private void initHttp(String[] statusLine, String statusLineRaw) throws IOException {
            URL url = new URL(statusLine[1]);
            int port = url.getPort();
            if(port==-1) port = url.getDefaultPort();
            targetStr = url.getHost()+":"+port;
            try{
                this.target = new Socket(url.getHost(), port);
            }catch(IOException e){
                throw new IOException(String.format("连接目标失败[%s]", targetStr), e);
            }
            
            OutputStream targetOut = target.getOutputStream();
            targetOut.write(statusLineRaw.getBytes());
            targetOut.write('\n');
            targetOut.flush();
        }
        private void initHttps(String[] statusLine) throws IOException {
            while(!"".equals(readline(srcIn))){}
            targetStr = statusLine[1];
            String[] split = targetStr.split("[:]");
            try{
                this.target = new Socket(split[0], Integer.valueOf(split[1]));
            }catch(IOException e){
                throw new IOException(String.format("连接目标失败[%s]", targetStr), e);
            }
            
            srcOut.write("HTTP/1.0 200 Connection established\r\nProxy-Agent: lvt4j-SocketProxy/1.0\r\n\r\n".getBytes());
            srcOut.flush();
        }
        private String readline(InputStream in) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int b;
            while(true){
                b = in.read();
                if(b==-1) break;
                if(b=='\n') break;
                if(b=='\r') continue;
                baos.write(b);
            }
            return baos.toString();
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
