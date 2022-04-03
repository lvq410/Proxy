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
import java.net.URL;
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
import com.google.common.net.HostAndPort;

import lombok.RequiredArgsConstructor;
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
public class HttpNioService extends Thread implements InfoContributor {

    private static final byte LineFeed = '\n';
    private static final byte RetChar = '\r';
    
    private static final byte[] EstablishedHeaders = "HTTP/1.0 200 Connection established\r\nProxy-Agent: lvt4j-SocketProxy/1.0\r\n\r\n".getBytes();
    
    @Autowired
    private Config config;
    @Autowired
    private ChannelReader reader;
    @Autowired
    private ChannelWriter writer;
    @Autowired
    private ChannelConnector connector;
    
    private Selector acceptor;
    
    private Map<Integer, ServerMeta> servers = new HashMap<>();
    
    @PostConstruct
    private void init() throws IOException {
        setName("HttpService");
        acceptor = Selector.open();
        
        Config.changeCallback_http = this::reloadConfig;
        
        reloadConfig();
        start();
    }
    private synchronized void reloadConfig() {
        Set<Integer> http = config.getHttp();
        for(int port : http){
            ServerMeta meta = servers.get(port);
            if(meta!=null) continue;
            
            meta = new ServerMeta();
            
            ServerSocketChannel serverSocketChannel = null;
            try{
                serverSocketChannel = ServerSocketChannel.open();
                serverSocketChannel.bind(new InetSocketAddress(port));
                serverSocketChannel.configureBlocking(false);
                
                meta.port = port;
                meta.serverSocketChannel = serverSocketChannel;
                meta.src2target = new ChannelTransmitter(port+" s->t");
                meta.target2src = new ChannelTransmitter(port+" t->s");
                
                synchronized(this){
                    acceptor.wakeup();
                    serverSocketChannel.register(acceptor, OP_ACCEPT, meta);
                }
                
                servers.put(port, meta);
                
                log.info("{} http代理启动", port);
            }catch(Exception e){
                log.error("{} http代理启动失败", port, e);
                ProxyApp.close(serverSocketChannel);
            }
        }
        ImmutableSet.copyOf(servers.keySet()).stream().filter(p->!http.contains(p)).forEach(removed->{
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
        builder.withDetail("http", servers.values().stream().collect(toMap(s->s.port, s->s.info())));
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
        
        ServerMeta.ConnectMeta connect = serverMeta.new ConnectMeta(src);
        
        connect.targetStr = "initializing";
        connect.direction = String.format("%s->%s->%s->%s"
            ,format(src.getRemoteAddress()), port(src.getLocalAddress())
            ,"initializing" ,"initializing");
        
        if(log.isTraceEnabled()) log.trace("{} connecting {}", serverMeta.port, connect.direction);
        
        serverMeta.connections.add(connect);
        
        connect.connect_begin();
    }
    
    private class ServerMeta {
        
        private int port;
        private ServerSocketChannel serverSocketChannel;
        
        private ChannelTransmitter src2target;
        private ChannelTransmitter target2src;
        
        private List<ConnectMeta> connections = Collections.synchronizedList(new LinkedList<>());
        
        public void destory() {
            ImmutableSet.copyOf(connections).forEach(ConnectMeta::destory);
            src2target.destory(); target2src.destory();
            ProxyApp.close(serverSocketChannel);
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

        public Object info() {
            return connections.stream().collect(groupingBy(c->c.targetStr, mapping(c->c.direction, toList())));
        }
        
        @RequiredArgsConstructor
        private class ConnectMeta {
            private final SocketChannel src;
            
            private String targetStr;
            private SocketChannel target;
            
            private String direction;
            
            private long latestTouchTime = System.currentTimeMillis();
            
            
            private void connect_begin() {
                reader.readUntilByte(src, LineFeed, data->{
                    String statusLine = new String(data, 0, data.length-1);
                    String[] split = statusLine.split(" ");
                    if(split.length!=3){
                        log.error("非法的http请求状态行：%s", statusLine);
                        destory();
                        return;
                    }
                    if("CONNECT".equals(split[0])){
                        connect_https_begin(split);
                    }else{
                        connect_http(split, data);
                    }
                    
                }, this::onException);
            }
            private void connect_http(String[] statusLine, byte[] statusLineRaw) throws IOException {
                URL url = new URL(statusLine[1]);
                int port = url.getPort();
                if(port==-1) port = url.getDefaultPort();
                targetStr = url.getHost()+":"+port;
                
                target = SocketChannel.open();
                target.configureBlocking(false);
                target.connect(new InetSocketAddress(url.getHost(), port));
                
                connector.connectListen(target, ()->{
                    writer.write(target, statusLineRaw, this::connect_end, this::onException);
                }, e->{
                    log.error("连接目标失败 : {}", targetStr, e);
                    destory();
                });
            }
            private void connect_https_begin(String[] statusLine) throws IOException {
                targetStr = statusLine[1];
                HostAndPort hp = ProxyApp.validHostPort(targetStr);
                if(hp==null){
                    log.error("请求头中非法的目标地址 : %s", targetStr);
                    destory();
                }else{
                    target = SocketChannel.open();
                    target.configureBlocking(false);
                    target.connect(new InetSocketAddress(hp.getHostText(), hp.getPort()));
                    
                    connector.connectListen(target, this::connect_https_exhaust_headers, e->{
                        log.error("连接目标失败 : {}", targetStr, e);
                        destory();
                    });
                }
            }
            /** 读掉https建立连接请求中的全部的请求头 */
            private void connect_https_exhaust_headers() {
                reader.readUntilByte(src, LineFeed, data->{
                    data = ArrayUtils.removeElement(data, RetChar); //如win类操作系统，换行同时会携带'\r'，去掉它
                    if(data.length==1){//请求头结束，返回连接建立成功消息
                        writer.write(src, EstablishedHeaders, this::connect_end, this::onException);
                    }else{
                        connect_https_exhaust_headers();
                    }
                }, this::onException);
            }
            
            private void connect_end() throws IOException {
                direction = String.format("%s->%s->%s->%s"
                    ,format(src.getRemoteAddress()), port(src.getLocalAddress())
                    ,port(target.getLocalAddress()), format(target.getRemoteAddress()));
                
                src2target.transmit(src, target, 1024, this::onTrans, this::onException);
                target2src.transmit(target, src, 1024, this::onTrans, this::onException);
                
                log.info("{} connected {}", port, direction);
            }
            
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