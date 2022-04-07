package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProxyApp.format;
import static com.lvt4j.socketproxy.ProxyApp.isCloseException;
import static com.lvt4j.socketproxy.ProxyApp.port;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;

import lombok.extern.slf4j.Slf4j;

/**
 * tcp直连代理（反向代理）
 * @author LV on 2022年3月31日
 */
@Slf4j
@Service
public class TcpService implements InfoContributor {

    @Autowired
    private Config config;
    @Autowired
    private ChannelAcceptor acceptor;
    @Autowired
    private ChannelConnector connector;
    
    private Map<Integer, ServerMeta> servers = new HashMap<>();
    
    @PostConstruct
    private void init() throws IOException {
        Config.changeCallback_tcp = this::reloadConfig;
        
        reloadConfig();
    }
    
    private synchronized void reloadConfig() {
        Map<Integer, HostAndPort> tcp = config.getTcp();
        tcp.forEach((port,target)->{
            ServerMeta meta = servers.get(port);
            if(meta==null){
                try{
                    servers.put(port, new ServerMeta(port, target));
                    log.info("{} tcp代理启动,目标 {}", port, target);
                }catch(Exception e){
                    log.error("{} tcp代理启动失败", port, e);
                }
            }else{
                meta.target = target;
            }
        });
        ImmutableSet.copyOf(servers.keySet()).stream().filter(k->!tcp.containsKey(k)).forEach(removed->{
            ServerMeta s = servers.remove(removed);
            if(s!=null) s.destory();
        });
    }
    
    @PreDestroy
    private synchronized void destory() throws IOException {
        Config.changeCallback_tcp = null;
        ImmutableSet.copyOf(servers.values()).forEach(ServerMeta::destory);
    }
    
    @Scheduled(cron="0/10 * * * * ?")
    public synchronized void cleanIdle() {
        servers.values().forEach(ServerMeta::cleanIdle);
    }
    
    @Override
    public void contribute(Builder builder) {
        builder.withDetail("tcp", servers.values().stream().collect(toMap(s->s.port+"->"+s.target, s->s.info())));
    }
    
    private class ServerMeta {
        private int port;
        private ServerSocketChannel serverSocketChannel;
        private HostAndPort target;
        
        private ChannelTransmitter src2target;
        private ChannelTransmitter target2src;
        
        private List<ConnectMeta> connections = Collections.synchronizedList(new LinkedList<>());
        
        public ServerMeta(int port, HostAndPort target) throws IOException {
            this.port = port;
            
            try{
                serverSocketChannel = ProxyApp.server(port);
                
                this.target = target;
                src2target = new ChannelTransmitter(port+" s->t");
                target2src = new ChannelTransmitter(port+" t->s");
                
                acceptor.accept(serverSocketChannel, this::accept, e->log.error("establish connection err", e));
            }catch(IOException e){
                destory();
                throw e;
            }
        }
        private void accept(SocketChannel src) throws IOException {
            connections.add(new ConnectMeta(src, target));
        }
        
        public void destory() {
            ImmutableSet.copyOf(connections).forEach(ConnectMeta::destory);
            if(src2target!=null) src2target.destory();
            if(target2src!=null) target2src.destory();
            ProxyApp.close(serverSocketChannel);
            servers.remove(port);
            log.info("{} tcp代理停止", port);
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
            return connections.stream().collect(groupingBy(c->c.targetConfig, mapping(c->c.direction, toList())));
        }
        
        private class ConnectMeta {
            private final SocketChannel src;
            
            private final HostAndPort targetConfig;
            private final SocketChannel target;
            
            private String direction;
            
            private long latestTouchTime = System.currentTimeMillis();
            
            public ConnectMeta(SocketChannel src, HostAndPort targetConfig) throws IOException {
                this.src = src;
                try{
                    src.configureBlocking(false);
                    
                    this.targetConfig = targetConfig;
                    target = SocketChannel.open();
                    target.configureBlocking(false);
                    target.connect(new InetSocketAddress(targetConfig.getHostText(), targetConfig.getPort()));
                    
                    direction = String.format("%s->%s->%s->%s"
                        ,format(src.getRemoteAddress()), port(src.getLocalAddress())
                        ,"initializing" , format(target.getRemoteAddress()));
                    
                    connector.connect(target, ()->{
                        direction = String.format("%s->%s->%s->%s"
                            ,format(src.getRemoteAddress()), port(src.getLocalAddress())
                            ,port(target.getLocalAddress()), format(target.getRemoteAddress()));
                        
                        src2target.transmit(src, target, 1024, this::onTrans, this::onException);
                        target2src.transmit(target, src, 1024, this::onTrans, this::onException);
                        
                        log.info("{} connected {}", port, direction);
                    }, this::onException);
                    
                }catch(IOException e){
                    destory();
                    throw e;
                }
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