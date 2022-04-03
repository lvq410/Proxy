package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProxyApp.format;
import static com.lvt4j.socketproxy.ProxyApp.port;
import static java.nio.channels.SelectionKey.OP_ACCEPT;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * tcp直连代理（反向代理）
 * @author LV on 2022年3月31日
 */
@Slf4j
@Service
public class TcpService extends Thread implements InfoContributor {

    @Autowired
    private Config config;
    @Autowired
    private ChannelConnector connector;
    
    private Selector acceptor;
    
    private Map<Integer, ServerMeta> servers = new HashMap<>();
    
    @PostConstruct
    private void init() throws IOException {
        setName("TcpService");
        acceptor = Selector.open();
        
        Config.changeCallback_tcp = this::reloadConfig;
        
        reloadConfig();
        start();
    }
    
    private synchronized void reloadConfig() {
        Map<Integer, HostAndPort> tcp = config.getTcp();
        tcp.forEach((port,target)->{
            ServerMeta meta = servers.get(port);
            if(meta==null){
                meta = new ServerMeta();
                
                ServerSocketChannel serverSocketChannel = null;
                try{
                    serverSocketChannel = ServerSocketChannel.open();
                    serverSocketChannel.bind(new InetSocketAddress(port));
                    serverSocketChannel.configureBlocking(false);
                    
                    meta.port = port;
                    meta.serverSocketChannel = serverSocketChannel;
                    meta.target = target;
                    meta.src2target = new ChannelTransmitter(port+" s->t");
                    meta.target2src = new ChannelTransmitter(port+" t->s");
                    
                    synchronized(this){
                        acceptor.wakeup();
                        serverSocketChannel.register(acceptor, OP_ACCEPT, meta);
                    }
                    
                    servers.put(port, meta);
                    
                    log.info("{} tcp代理启动,目标 {}", port, target);
                }catch(Exception e){
                    log.error("{} tcp代理启动失败", port, e);
                    ProxyApp.close(serverSocketChannel);
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
        acceptor.close();
    }
    
    @Scheduled(cron="0/10 * * * * ?")
    public synchronized void cleanIdle() {
        servers.values().forEach(ServerMeta::cleanIdle);
    }
    
    @Override
    public void contribute(Builder builder) {
        builder.withDetail("tcp", servers.values().stream().collect(toMap(s->s.port+"->"+s.target, s->s.info())));
    }
    
    @Override
    public void run() {
        while(acceptor.isOpen()){
            try{
                acceptor.select();
            }catch(Exception e){
                log.error("acceptor select err", e);
                return;
            }
            if(!acceptor.isOpen()) return;
            synchronized(this){
                //等待可能的注册
            }
            Iterator<SelectionKey> keys = acceptor.selectedKeys().iterator();
            while(keys.hasNext()){
                SelectionKey key = keys.next();
                keys.remove();
                ServerMeta serverMeta = (ServerMeta) key.attachment();
                try{
                    accept(key, serverMeta);
                }catch(Exception e){
                    log.error("establish connection err", e);
                }
            }
        }
    }
    private void accept(SelectionKey key, ServerMeta serverMeta) throws IOException {
        SocketChannel src = ((ServerSocketChannel)key.channel()).accept();
        src.configureBlocking(false);
        
        HostAndPort targetConfig = serverMeta.target;
        SocketChannel target = SocketChannel.open();
        target.configureBlocking(false);
        target.connect(new InetSocketAddress(targetConfig.getHostText(), targetConfig.getPort()));
        
        ServerMeta.ConnectMeta connect = serverMeta.new ConnectMeta(src, targetConfig, target);
        
        connect.direction = String.format("%s->%s->%s->%s"
            ,format(src.getRemoteAddress()), port(src.getLocalAddress())
            ,"initializing" , format(target.getRemoteAddress()));
        
        connector.connectListen(target, ()->{
            connect.direction = String.format("%s->%s->%s->%s"
                ,format(src.getRemoteAddress()), port(src.getLocalAddress())
                ,port(target.getLocalAddress()), format(target.getRemoteAddress()));
            
            serverMeta.connections.add(connect);
            
            serverMeta.src2target.transmit(src, target, 1024, connect::onTrans, connect::onException);
            serverMeta.target2src.transmit(target, src, 1024, connect::onTrans, connect::onException);
            
            log.info("{} connected {}", serverMeta.port, connect.direction);
        }, connect::onException);
    }
    
    private class ServerMeta {
        private int port;
        private ServerSocketChannel serverSocketChannel;
        private HostAndPort target;
        
        private ChannelTransmitter src2target;
        private ChannelTransmitter target2src;
        
        private List<ConnectMeta> connections = Collections.synchronizedList(new LinkedList<>());
        
        public void destory() {
            ImmutableSet.copyOf(connections).forEach(ConnectMeta::destory);
            src2target.destory(); target2src.destory();
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
        
        @RequiredArgsConstructor
        private class ConnectMeta {
            private final SocketChannel src;
            
            private final HostAndPort targetConfig;
            private final SocketChannel target;
            
            private String direction;
            
            private long latestTouchTime = System.currentTimeMillis();
            
            private void onTrans() {
                latestTouchTime = System.currentTimeMillis();
            }
            
            private synchronized void onException(Exception e) {
                log.error("connection {} err", direction, e);
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