package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProtocolService.Pws.Command_Close;
import static com.lvt4j.socketproxy.ProtocolService.Pws.Header_Target;
import static com.lvt4j.socketproxy.ProxyApp.format;
import static com.lvt4j.socketproxy.ProxyApp.isCloseException;
import static com.lvt4j.socketproxy.ProxyApp.port;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.security.sasl.AuthenticationException;

import org.apache.commons.lang3.StringUtils;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import com.lvt4j.socketproxy.Config.TcpConfig;

import lombok.SneakyThrows;
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
    @Autowired
    private ChannelReader reader;
    @Autowired
    private ChannelWriter writer;
    @Autowired
    private ProtocolService protocol;
    
    private Map<TcpConfig, ServerMeta> servers = new HashMap<>();
    
    @PostConstruct
    private void init() throws IOException {
        Config.changeCallback_tcp = this::reloadConfig;
        
        reloadConfig();
    }
    
    private synchronized void reloadConfig() {
        List<TcpConfig> tcp = config.getTcp();
        ImmutableSet.copyOf(servers.keySet()).stream().filter(k->!tcp.contains(k)).forEach(removed->{
            ServerMeta s = servers.remove(removed);
            if(s!=null) s.destory();
        });
        for(TcpConfig c : tcp){
            if(servers.containsKey(c)) continue;
            try{
                servers.put(c, new ServerMeta(c));
                log.info("{} tcp代理启动,目标 {}", c.shortDirection(), c.getTarget());
            }catch(Exception e){
                log.error("{} tcp代理启动失败", c.shortDirection(), e);
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
        builder.withDetail("tcp", config.getTcp().stream().map(servers::get).filter(Objects::nonNull)
            .map(ServerMeta::info).collect(joining("\n")));
    }
    
    private class ServerMeta {
        private final TcpConfig config;
        private final InetAddress host;
        private final int port;
        private final HostAndPort target;
        private final URI proxy;
        
        private final String shortDirection;
        private final String direction;
        
        private final ServerSocketChannel serverSocketChannel;
        
        private final ChannelTransmitter src2target;
        private final ChannelTransmitter target2src;
        
        private final List<ConnectMeta> connections = Collections.synchronizedList(new LinkedList<>());
        
        public ServerMeta(TcpConfig config) throws IOException {
            this.config = config;
            this.host = config.getHost();
            this.port = config.getPort();
            this.target = config.getTarget();
            this.proxy = config.getProxy();
            
            this.shortDirection = config.shortDirection();
            this.direction = config.direction();
            
            try{
                serverSocketChannel = ProxyApp.server(host, port);
                
                src2target = new ChannelTransmitter(port+" s->t");
                target2src = new ChannelTransmitter(port+" t->s");
                
                acceptor.accept(serverSocketChannel, this::accept, e->log.error("establish connection err", e));
            }catch(IOException e){
                destory();
                throw e;
            }
        }
        private void accept(SocketChannel src) throws IOException {
            connections.add(new ConnectMeta(src, target, proxy));
        }
        
        public void destory() {
            ImmutableSet.copyOf(connections).forEach(ConnectMeta::destory);
            if(src2target!=null) src2target.destory();
            if(target2src!=null) target2src.destory();
            ProxyApp.close(serverSocketChannel);
            acceptor.waitDeregister(serverSocketChannel);
            servers.remove(config);
            log.info("{} tcp代理停止", shortDirection);
        }
        
        public void cleanIdle() {
            synchronized (connections) {
                for(ConnectMeta connect : ImmutableSet.copyOf(connections)){
                    if(System.currentTimeMillis()-connect.latestTouchTime<TcpService.this.config.getMaxIdleTime()) continue;
                    connect.destory();
                }
            }
        }
        
        public String info() {
            List<Object> infos = new LinkedList<>();
            infos.add(direction);
            connections.stream().collect(groupingBy(c->c.targetConfig.toString(), TreeMap::new, mapping(c->c.direction, toList())))
            .forEach((t, cs)->{
                infos.add("  "+t);
                cs.forEach(cnn->infos.add("  - "+cnn));
            });
            return StringUtils.join(infos, "\n");
        }
        
        private class ConnectMeta {
            private final SocketChannel src;
            
            private final HostAndPort targetConfig;
            private final URI proxyConfig;
            private PwsClient pwsProxy;
            private SocketChannel target;
            
            private String direction;
            
            private long latestTouchTime = System.currentTimeMillis();
            
            public ConnectMeta(SocketChannel src, HostAndPort targetConfig, URI proxyConfig) throws IOException {
                this.src = src;
                this.proxyConfig = proxyConfig;
                try{
                    src.configureBlocking(false);
                    
                    this.targetConfig = targetConfig;
                    direction = String.format("%s->%s->%s->%s"
                        ,format(src.getRemoteAddress()), port(src.getLocalAddress())
                        ,"initializing" , targetConfig);
                    
                    if(proxy==null){
                        target = SocketChannel.open();
                        target.configureBlocking(false);
                        target.connect(new InetSocketAddress(targetConfig.getHostText(), targetConfig.getPort()));
                        
                        connector.connect(target, ()->onDirectConnect(target), this::onException);
                    }else{
                        Protocol protocol = Protocol.parse(proxyConfig.getScheme());
                        switch(protocol){
                        case Pws: case Pwss:
                            pwsProxy = new PwsClient(proxyConfig);
                            break;
                        default:
                            TcpService.this.protocol.client_connect(proxyConfig, targetConfig, this::onProxyConnect, this::onException);
                            break;
                        }
                    }
                }catch(IOException e){
                    destory();
                    throw e;
                }
            }
            private void onDirectConnect(SocketChannel target) throws IOException {
                this.target = target;
                direction = String.format("%s->%s->%s->%s"
                    ,format(src.getRemoteAddress()), port(src.getLocalAddress())
                    ,port(target.getLocalAddress()), format(target.getRemoteAddress()));
                
                src2target.transmit(src, target, 1024, this::onTrans, this::onException);
                target2src.transmit(target, src, 1024, this::onTrans, this::onException);
                
                log.info("{} connected {}", shortDirection, direction);
            }
            private void onProxyConnect(SocketChannel proxy) throws IOException {
                this.target = proxy;
                direction = String.format("%s->%s->%s->%s->%s"
                        ,format(src.getRemoteAddress()), port(src.getLocalAddress())
                        ,port(proxy.getLocalAddress()), proxyConfig, targetConfig);
                
                src2target.transmit(src, target, 1024, this::onTrans, this::onException);
                target2src.transmit(target, src, 1024, this::onTrans, this::onException);
                
                log.info("{} connected {}", shortDirection, direction);
            }
            
            private void onTrans() {
                latestTouchTime = System.currentTimeMillis();
            }
            
            private synchronized void onException(Exception e) {
                if(!isCloseException(e)) log.error("connection {} err", direction, e);
                destory();
            }
            
            private void destory() {
                if(pwsProxy==null) ProxyApp.close(src);
                else{
                    pwsProxy.prepareCloseClient = true;
                    pwsProxy.closeIfPossible();
                }
                ProxyApp.close(target);
                connections.remove(this);
                
                log.info("{} disconnected {}", shortDirection, direction);
            }
            private Map<String, String> pws_headers(URI pwsServer){
                Map<String, String> headers = new HashMap<>();
                headers.put(Header_Target, targetConfig.toString());
                if(pwsServer.getUserInfo()!=null) headers.put(AUTHORIZATION, "Basic "+Base64.getEncoder().encodeToString(pwsServer.getUserInfo().getBytes()));
                return headers;
            }
            class PwsClient extends WebSocketClient {

                private AtomicLong prepareWrite2SrcDataIder = new AtomicLong();
                private Set<Long> prepareWrite2SrcDataIds = Collections.synchronizedSet(new HashSet<>());
                private volatile boolean prepareCloseClient = false;
                
                public PwsClient(URI pwsServer) {
                    super(Protocol.pws2ws(pwsServer), pws_headers(pwsServer));
                    connect();
                }
                
                @Override @SneakyThrows
                public void onOpen(ServerHandshake handshakedata) {
                    direction = String.format("%s->%s->%s->%s"
                        ,format(src.getRemoteAddress()), port(src.getLocalAddress())
                        ,port(getLocalSocketAddress()), format(getRemoteSocketAddress()));
                    
                    srcRead(null);
                    
                    log.info("{} connected {}", shortDirection, direction);
                }
                /**
                 * pws协议用字符串格式传递命令
                 */
                @Override
                public void onMessage(String message) {
                    log.error("pws client ({})收到命令：{}", direction, message);
                    switch(message){
                    case Command_Close:
                        destory();
                        break;
                    default:
                        log.error("pws client ({})收到未知命令：{}", direction, message);
                        break;
                    }
                }
                /**
                 * pws协议用byte[]格式传递数据
                 */
                @Override
                public void onMessage(ByteBuffer bytes) {
                    dataFromTargetToSrc(bytes);
                }
                private void srcRead(ByteBuffer buf) {
                    if(buf==null) buf = ByteBuffer.allocate(1024);
                    reader.readAny(src, buf, data->{
                        dataFromSrcToTarget(data);
                        srcRead(data);
                    }, e->{
                        try{
                            if(isOpen()) send(Command_Close);
                        }catch(Exception ig){}
                        onError(e);
                    });
                }
                private void dataFromSrcToTarget(ByteBuffer buf) {
                    send(buf);
                    onTrans();
                }
                private void dataFromTargetToSrc(ByteBuffer buf) {
                    long prepareWrite2ClientDataId = prepareWrite2SrcDataIder.incrementAndGet();
                    prepareWrite2SrcDataIds.add(prepareWrite2ClientDataId);
                    writer.write(src, buf, ()->{
                        prepareWrite2SrcDataIds.remove(prepareWrite2ClientDataId);
                        closeIfPossible();
                    }, this::onError);
                    onTrans();
                }
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if(CloseFrame.NORMAL==code) return;
                    onError(new AuthenticationException("pws链接非正常关闭：code="+code+",reason="+reason+",remote="+remote));
                }
                @Override
                public void onError(Exception ex) {
                    prepareWrite2SrcDataIds.clear();
                    onException(ex);
                }
                public void closeIfPossible() {
                    if(!prepareCloseClient) return;
                    if(!prepareWrite2SrcDataIds.isEmpty()) return;
                    
                    close();
                    ProxyApp.close(src);
                }
                
            }
        }
    }
    
}