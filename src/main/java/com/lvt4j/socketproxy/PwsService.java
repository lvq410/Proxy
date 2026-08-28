package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProtocolService.Pws.Basic_Prefix;
import static com.lvt4j.socketproxy.ProtocolService.Pws.Command_Close;
import static com.lvt4j.socketproxy.ProtocolService.Pws.Header_Authorization;
import static com.lvt4j.socketproxy.ProtocolService.Pws.Header_Target;
import static com.lvt4j.socketproxy.ProxyApp.format;
import static com.lvt4j.socketproxy.ProxyApp.isCloseException;
import static com.lvt4j.socketproxy.ProxyApp.port;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import com.lvt4j.socketproxy.Config.PwsConfig;

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
        List<PwsConfig> pws = config.getPws();
        Map<Integer, PwsConfig> pwsByPort = new LinkedHashMap<>();
        for(PwsConfig c : pws) pwsByPort.put(c.getPort(), c);

        ImmutableSet.copyOf(servers.keySet()).stream().filter(p->!pwsByPort.containsKey(p)).forEach(removed->{
            ServerMeta s = servers.remove(removed);
            if(s!=null) s.destory();
        });
        for(PwsConfig c : pws){
            int port = c.getPort();
            ServerMeta exist = servers.get(port);
            if(exist!=null){
                // 端口未变但认证配置可能变了，配置一致则复用，否则重建
                if(Objects.equals(exist.pwsConfig.getAuth(), c.getAuth())) continue;
                exist.destory();
            }
            try{
                servers.put(port, new ServerMeta(c));
                log.info("{} pws代理启动{}", port, c.authEnabled()?"(已开启认证)":"");
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
        builder.withDetail("pws", config.getPws().stream().map(PwsConfig::getPort).map(servers::get).filter(Objects::nonNull)
            .map(ServerMeta::info).collect(joining("\n")));
    }

    private class ServerMeta extends WebSocketServer {

        private final int port;
        private final PwsConfig pwsConfig;
        /** 连接认证器，未开启认证时为null */
        private final PwsAuthenticator authenticator;

        private List<ConnectMeta> connections = Collections.synchronizedList(new LinkedList<>());

        public ServerMeta(PwsConfig config) {
            super(new InetSocketAddress(config.getPort()));
            this.port = config.getPort();
            this.pwsConfig = config;
            this.authenticator = config.authEnabled()? new LdapPwsAuthenticator(config.getAuth()): null;
            start();
        }

        @Override public void onStart() {}
        @Override @SneakyThrows
        public void onOpen(WebSocket client, ClientHandshake handshake) {
            if(!authenticate(handshake)){
                // 认证失败时不建立ConnectMeta，直接关闭连接
                log.warn("{} 认证失败，拒绝连接 {}", port, format(client.getRemoteSocketAddress()));
                client.close(CloseFrame.POLICY_VALIDATION, "Unauthorized");
                return;
            }
            connections.add(new ConnectMeta(client, handshake));
        }

        /**
         * 校验握手请求中的{@code Authorization: Basic}凭据
         * @return 未开启认证时恒为true
         */
        private boolean authenticate(ClientHandshake handshake) {
            if(authenticator==null) return true;
            String authorization = handshake.getFieldValue(Header_Authorization);
            if(StringUtils.isBlank(authorization)) return false;
            if(!StringUtils.startsWithIgnoreCase(authorization, Basic_Prefix)) return false;
            String userPass;
            try{
                userPass = new String(Base64.getDecoder().decode(authorization.substring(Basic_Prefix.length()).trim()), UTF_8);
            }catch(Exception e){
                return false;
            }
            // 密码中可能含有冒号，只按第一个冒号切分
            int idx = userPass.indexOf(':');
            if(idx<0) return false;
            return authenticator.authenticate(userPass.substring(0, idx), userPass.substring(idx+1));
        }

        @Override
        public void onClose(WebSocket client, int code, String reason, boolean remote) {
            ConnectMeta cnn = client.getAttachment();
            if(cnn==null) return;
            cnn.destory();
        }
        /**
         * pws协议用字符串格式传递命令
         */
        @Override
        public void onMessage(WebSocket client, String message) {
            ConnectMeta cnn = client.getAttachment();
            if(cnn==null) return;
            cnn.onCommand(message);
        }
        /**
         * pws协议用byte[]格式传递数据
         */
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
            }catch(Exception ig){}
            // 认证配置变更时会先destory旧实例再put新实例，
            // 这里只在map中仍是自己时才移除，避免误删刚建好的新实例
            servers.remove(port, this);
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
            
            private AtomicLong prepareWrite2TargetDataIder = new AtomicLong();
            private Set<Long> prepareWrite2TargetDataIds = Collections.synchronizedSet(new HashSet<>());
            private volatile boolean prepareCloseClient = false;
            
            public ConnectMeta(WebSocket client, ClientHandshake handshake) throws IOException {
                this.client = client;
                client.setAttachment(this);
                
                try{
                    targetStr = handshake.getFieldValue(Header_Target);
                    if(StringUtils.isBlank(targetStr)) throw new IOException(format("websocket miss target header : %s", Header_Target));
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
                }, e->{
                    try{
                        if(client!=null && client.isOpen()) client.send(Command_Close);
                    }catch(Exception ig){}
                    onException(e);
                });
            }
            private void dataFromTargetToClient(ByteBuffer buf) {
                client.send(buf);
                onTrans();
            }
            private void onCommand(String message) {
                log.error("pws server ({})收到命令：{}", direction, message);
                switch(message){
                case Command_Close:
                    destory();
                    break;
                default:
                    log.error("pws server ({})收到未知命令：{}", direction, message);
                    break;
                }
            }
            private void dataFromClientToTarget(ByteBuffer buf) {
                long prepareWrite2ClientDataId = prepareWrite2TargetDataIder.incrementAndGet();
                prepareWrite2TargetDataIds.add(prepareWrite2ClientDataId);
                writer.write(target, buf, ()->{
                    prepareWrite2TargetDataIds.remove(prepareWrite2ClientDataId);
                    closeIfPossible();
                }, this::onException);
                onTrans();
            }
            private void onTrans() {
                latestTouchTime = System.currentTimeMillis();
            }
            
            private synchronized void onException(Exception e) {
                if(!isCloseException(e)) log.error("connection {} err", direction, e);
                prepareWrite2TargetDataIds.clear();
                destory();
            }
            
            private void destory() {
                prepareCloseClient=true; closeIfPossible();
                ProxyApp.close(target);
                connections.remove(this);
                
                log.info("{} disconnected {}", port, direction);
            }
            public void closeIfPossible() {
                if(!prepareCloseClient) return;
                if(!prepareWrite2TargetDataIds.isEmpty()) return;
                
                if(client!=null && client.isOpen()) client.close();
            }
        }
    }
}
