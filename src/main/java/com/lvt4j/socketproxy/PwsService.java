package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProtocolService.Pws.TargetHeader;
import static com.lvt4j.socketproxy.ProxyApp.format;
import static com.lvt4j.socketproxy.ProxyApp.isCloseException;
import static com.lvt4j.socketproxy.ProxyApp.port;
import static java.lang.String.format;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
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
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于WebSocket的私有协议
 * @author LV on 2022年6月2日
 * @see https://zhuanlan.zhihu.com/p/407711596
 */
@Slf4j
@Service
public class PwsService implements InfoContributor {

    @Autowired
    private Config config;
    
    @Autowired
    private ChannelConnector connector;
    @Autowired
    private ChannelReader reader;
    @Autowired
    private ChannelWriter writer;
    
    private Map<Integer, ServerMeta> servers = new HashMap<>();
    
    @PostConstruct
    private void init() {
        Config.changeCallback_pws = this::reloadConfig;
        reloadConfig();
    }
    private synchronized void reloadConfig() {
        List<Integer> pws = config.getPws();
        ImmutableSet.copyOf(servers.keySet()).stream().filter(p->!pws.contains(p)).forEach(removed->{
            ServerMeta s = servers.remove(removed);
            if(s!=null) s.destory();
        });
        for(int port : pws){
            if(servers.containsKey(port)) continue;
            try{
                servers.put(port, new ServerMeta(port));
                log.info("{} pws代理启动", port);
            }catch(Exception e){
                log.error("{} pws代理启动失败", port, e);
            }
        }
    }
    @PreDestroy
    private synchronized void destory() throws IOException {
        Config.changeCallback_pws = null;
        ImmutableSet.copyOf(servers.values()).forEach(ServerMeta::destory);
    }
    
    @Scheduled(cron="0/10 * * * * ?")
    public synchronized void cleanIdle() {
        servers.values().forEach(ServerMeta::cleanIdle);
    }
    
    @Override
    public void contribute(Builder builder) {
        if(servers.isEmpty()) return;
        builder.withDetail("pws", config.getPws().stream().map(servers::get).filter(Objects::nonNull)
            .map(ServerMeta::info).collect(joining("\n")));
    }
    
    private class ServerMeta extends WebSocketServer {
        
        private final int port;
        
        private List<ConnectMeta> connections = Collections.synchronizedList(new LinkedList<>());
        
        public ServerMeta(int port) {
            super(new InetSocketAddress(port));
            this.port = port;
            start();
        }
        
        @Override public void onStart() {}
        @Override @SneakyThrows
        public void onOpen(WebSocket client, ClientHandshake handshake) {
            connections.add(new ConnectMeta(client, handshake));
        }
        @Override
        public void onClose(WebSocket client, int code, String reason, boolean remote) {
            ConnectMeta cnn = client.getAttachment();
            if(cnn==null) return;
            cnn.destory();
        }
        @Override
        public void onMessage(WebSocket client, String message) {
            ConnectMeta cnn = client.getAttachment();
            if(cnn==null) return;
            log.warn("{} {} receive illegal string msg : {}", port, cnn.direction, message);
        }
        @Override
        public void onMessage(WebSocket client, ByteBuffer message) {
            ConnectMeta cnn = client.getAttachment();
            if(cnn==null) return;
            cnn.dataFromClientToTarget(message);
        }
        @Override
        public void onError(WebSocket client, Exception ex) {
            ConnectMeta cnn = client.getAttachment();
            if(cnn==null) return;
            cnn.onException(ex);
        }
        
        public void destory() {
            ImmutableSet.copyOf(connections).forEach(ConnectMeta::destory);
            try{
                stop();
            }catch(Exception ig){
            }
            servers.remove(port);
            log.info("{} pws代理停止", port);
        }
        
        public void cleanIdle() {
            synchronized (connections) {
                for(ConnectMeta cnn : ImmutableSet.copyOf(connections)){
                    if(System.currentTimeMillis()-cnn.latestTouchTime<config.getMaxIdleTime()) continue;
                    cnn.destory();
                }
            }
        }
        
        public String info() {
            List<Object> infos = new LinkedList<>();
            infos.add(port);
            connections.stream().collect(groupingBy(c->c.targetStr, TreeMap::new, mapping(c->c.direction, toList())))
            .forEach((t, cs)->{
                infos.add("  "+t);
                cs.forEach(cnn->infos.add("  - "+cnn));
            });
            return StringUtils.join(infos, "\n");
        }
        
        private class ConnectMeta {
            private final WebSocket client;
            
            private SocketChannel target;
            private String targetStr;
            
            private String direction;
            
            private long latestTouchTime = System.currentTimeMillis();
            
            public ConnectMeta(WebSocket client, ClientHandshake handshake) throws IOException {
                this.client = client;
                client.setAttachment(this);
                
                try{
                    targetStr = handshake.getFieldValue(TargetHeader);
                    if(StringUtils.isBlank(targetStr)) throw new IOException(format("websocket miss target header : %s", TargetHeader));
                    HostAndPort targetConfig = ProxyApp.validHostPort(targetStr);
                    if(targetConfig==null) throw new IOException(format("websocket illegal target header : %s", targetStr));
                    
                    direction = format("%s->%s->%s->%s"
                        ,format(client.getRemoteSocketAddress()), port(client.getLocalSocketAddress())
                        ,"initializing" ,"initializing");
                    
                    if(log.isTraceEnabled()) log.trace("{} connecting {}", port, direction);
                    
                    target = SocketChannel.open();
                    target.configureBlocking(false);
                    target.connect(new InetSocketAddress(targetConfig.getHostText(), targetConfig.getPort()));
                    
                    connector.connect(target, ()->onConnect(target), this::onException);
                }catch(Exception e){
                    destory();
                    throw e;
                }
            }
            private void onConnect(SocketChannel target) throws IOException {
                direction = String.format("%s->%s->%s->%s"
                    ,format(client.getRemoteSocketAddress()), port(client.getLocalSocketAddress())
                    ,port(target.getLocalAddress()), format(target.getRemoteAddress()));
                
                targetRead(null);
                
                log.info("{} connected {}", port, direction);
            }
            private void targetRead(ByteBuffer buf) {
                if(buf==null) buf = ByteBuffer.allocate(1024);
                reader.readAny(target, buf, data->{
                    dataFromTargetToClient(data);
                    targetRead(data);
                }, this::onException);
            }
            private void dataFromTargetToClient(ByteBuffer buf) {
                client.send(buf);
                onTrans();
            }
            private void dataFromClientToTarget(ByteBuffer buf) {
                writer.write(target, buf, this::onException);
                onTrans();
            }
            private void onTrans() {
                latestTouchTime = System.currentTimeMillis();
            }
            
            private synchronized void onException(Exception e) {
                if(!isCloseException(e)) log.error("connection {} err", direction, e);
                destory();
            }
            
            private void destory() {
                if(client!=null && client.isOpen()) client.close();
                ProxyApp.close(target);
                connections.remove(this);
                
                log.info("{} disconnected {}", port, direction);
            }
        }
    }
}
