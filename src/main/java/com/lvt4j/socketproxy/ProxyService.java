package com.lvt4j.socketproxy;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author LV on 2022年3月11日
 */
@Slf4j
@Service
public class ProxyService implements InfoContributor {

    @Autowired
    private Config config;
    
    private Map<Integer, ServerThread> instances = new HashMap<>();
    
    @PostConstruct
    private void init() throws Throwable {
        Config.changeCallback = ()->{
            try{
                this.init();
            }catch(Throwable e){
                log.error("reinitialize all proxy err", e);
            }
        };
        
        for(Entry<Integer, String> entry : config.getProxy().entrySet()){
            int port = entry.getKey();
            String target = entry.getValue();
            
            ServerThread s = instances.get(port);
            if(s==null){
                s = new ServerThread(port, target);
                instances.put(port, s);
            }else{
                s.setTarget(target);
            }
        }
        Set<Integer> removeds = instances.keySet().stream().filter(k->!config.getProxy().containsKey(k)).collect(Collectors.toSet());
        for(Integer removed : removeds){
            ServerThread s = instances.remove(removed);
            if(s!=null) s.destory();
        }
    }
    
    @PreDestroy
    private synchronized void destory() {
        Config.changeCallback = null;
        instances.values().forEach(ServerThread::destory);
    }
    
    @Scheduled(cron="* * * * * ?")
    public synchronized void cleanIdle() {
        instances.values().forEach(ServerThread::cleanIdle);
    }
    
    @Override
    public void contribute(Builder builder) {
        builder.withDetail("connects", instances.values().stream().collect(toMap(s->s.port, s->s.info())));
    }
    
    class ServerThread extends Thread {
        
        private final int port;
        @Setter
        private String target;
        
        private final ServerSocket server;
        
        private List<Connect> connects = Collections.synchronizedList(new LinkedList<>());
        
        public ServerThread(int port, String target) throws IOException {
            this.port = port;
            this.target = target;
            server = new ServerSocket(port);
            log.info("{} ServerSocket start, target {}", port, target);
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
                    connects.add(new Connect(port, src, target));
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
            return connects.stream().collect(groupingBy(c->c.targetConfig, mapping(c->c.direction, toList())));
        }
        
        @Override
        public String toString() {
            return String.format("%s %s cnns: %s", port, target, connects.size());
        }
    }
    class Connect implements Closeable {
        
        private final int serverPort;
        private final Socket src;
        private final String targetConfig;
        private final Socket target;
        
        private final String direction;
        
        private TransferThread src2Target;
        private TransferThread target2Src;
        
        private long latestTouchTime = System.currentTimeMillis();
        
        public Connect(int serverPort, Socket src, String targetConfig) throws IOException {
            this.serverPort = serverPort;
            this.src = src;
            this.targetConfig = targetConfig;
            String[] splits = targetConfig.split("[:]", 2);
            this.target = new Socket(splits[0], Integer.valueOf(splits[1]));
            
            direction = String.format("%s->%s->%s->%s", format(src.getRemoteSocketAddress()), format(src.getLocalSocketAddress())
                ,target.getLocalPort(), format(target.getRemoteSocketAddress()));
            
            src2Target = new TransferThread(src.getInputStream(), target.getOutputStream());
            src2Target.setName(serverPort+" "+targetConfig+" s->t"); src2Target.setDaemon(true);
            src2Target.start();
            target2Src = new TransferThread(target.getInputStream(), src.getOutputStream());
            target2Src.setName(serverPort+" "+targetConfig+" t->s"); target2Src.setDaemon(true);
            target2Src.start();
            
            log.info("{} connected {}", serverPort, direction);
        }
        private String format(SocketAddress addr) {
            return StringUtils.strip(addr.toString(), "/");
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
        public synchronized void close() throws IOException {
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
