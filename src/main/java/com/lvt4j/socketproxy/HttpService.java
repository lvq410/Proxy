package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProxyApp.format;
import static com.lvt4j.socketproxy.ProxyApp.port;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.common.collect.ImmutableSet;

import lombok.extern.slf4j.Slf4j;

/**
 * http协议代理
 * @author LV on 2022年3月22日
 * @see <a href="https://imququ.com/post/web-proxy.html">参考1</a>
 * @see <a href="https://github.com/stefano-lupo/Java-Proxy-Server/blob/master/src/RequestHandler.java">参考2</a>
 * @author LV on 2022年4月3日
 */
@Slf4j
@Service
public class HttpService implements InfoContributor {

    @Autowired
    private Config config;
    @Autowired
    private ChannelAcceptor acceptor;
    @Autowired
    private ProtocolService protocolService;
    
    private Map<Integer, ServerMeta> servers = new HashMap<>();
    
    @PostConstruct
    private void init() throws IOException {
        Config.changeCallback_http = this::reloadConfig;
        
        reloadConfig();
    }
    private synchronized void reloadConfig() {
        List<Integer> http = config.getHttp();
        ImmutableSet.copyOf(servers.keySet()).stream().filter(p->!http.contains(p)).forEach(removed->{
            ServerMeta s = servers.remove(removed);
            if(s!=null) s.destory();
        });
        for(int port : http){
            if(servers.containsKey(port)) continue;
            try{
                servers.put(port, new ServerMeta(port));
                log.info("{} http代理启动", port);
            }catch(Exception e){
                log.error("{} http代理启动失败", port, e);
            }
        }
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
        if(servers.isEmpty()) return;
        builder.withDetail("http", config.getHttp().stream().map(servers::get).filter(Objects::nonNull)
            .map(ServerMeta::info).collect(joining("\n")));
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
                serverSocketChannel = ProxyApp.server(null, port);
                
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
            acceptor.waitDeregister(serverSocketChannel);
            servers.remove(port);
            log.info("{} http5代理停止", port);
        }
        
        public void cleanIdle() {
            synchronized (connections) {
                for(ConnectMeta connect : ImmutableSet.copyOf(connections)){
                    if(System.currentTimeMillis()-connect.latestTouchTime<config.getMaxIdleTime()) continue;
                    connect.destory();
                }
            }
        }

        public String info() {
            List<Object> infos = new LinkedList<>();
            infos.add(port);
            connections.stream().collect(groupingBy(c->c.targetStr, TreeMap::new, mapping(c->c.direction, toList())))
            .forEach((t,cs)->{
                infos.add("  "+t);
                cs.forEach(c->infos.add("  - "+c));
            });
            return StringUtils.join(infos, "\n");
        }
        
        private class ConnectMeta {
            private final SocketChannel src;
            
            private String targetStr;
            private SocketChannel target;
            
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
                    
                    protocolService.http_server_connect(src, (targetStr, target)->{
                        ConnectMeta.this.targetStr = targetStr;
                        ConnectMeta.this.target = target;
                        
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
                if(!ProxyApp.isCloseException(e)) log.error("connection {} err", direction, e);
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