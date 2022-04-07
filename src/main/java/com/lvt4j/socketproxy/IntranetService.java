package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProxyApp.format;
import static com.lvt4j.socketproxy.ProxyApp.isCloseException;
import static com.lvt4j.socketproxy.ProxyApp.port;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import com.google.common.primitives.Ints;
import com.lvt4j.socketproxy.Config.IntranetConfig;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author LV on 2022年3月28日
 */
@Slf4j
@Service
public class IntranetService implements InfoContributor {
    
    private static final long EntryConnectRetryGap = 5000;
    
    @Autowired
    private Config config;
    @Autowired
    private ChannelAcceptor acceptor;
    @Autowired
    private ChannelConnector connector;
    @Autowired
    private DelayRunner delayRunner;
    
    private Map<IntranetConfig, Server> servers = new HashMap<>();
    
    @PostConstruct
    private void init() {
        Config.changeCallback_intranet = this::reloadConfig;
        
        reloadConfig();
    }
    private synchronized void reloadConfig() {
        List<IntranetConfig> intranet = config.getIntranet();
        for(IntranetConfig c : intranet){
            if(servers.containsKey(c)) continue;
            switch(c.getType()){
            case Entry:
                try{
                    servers.put(c, new EntryServer(c));
                    log.info("{} intranet entry启动", c.getPort());
                }catch(Exception e){
                    log.info("{} intranet entry启动失败", c.getPort(), e);
                }
                break;
            case Relay:
                try{
                    servers.put(c, new RelayServer(c));
                    log.info("{} intranet relay启动", c.getTarget());
                }catch(Exception e){
                    log.info("{} intranet relay启动失败", c.getTarget(), e);
                }
                break;
            }
        }
        ImmutableSet.copyOf(servers.keySet()).stream().filter(c->!intranet.contains(c)).forEach(removed->{
            Server s = servers.remove(removed);
            if(s!=null) s.destory();
        });
    }
    @PreDestroy
    private synchronized void destory() {
        Config.changeCallback_intranet = null;
        ImmutableSet.copyOf(servers.values()).forEach(Server::destory);
    }
    
    @Scheduled(cron="0/10 * * * * ?")
    public synchronized void heartbeat() {
        servers.values().forEach(Server::heartbeat);
    }
    @Scheduled(cron="0/10 * * * * ?")
    public synchronized void cleanIdle() {
        servers.values().forEach(Server::cleanIdle);
    }
    
    @Override
    public void contribute(Builder builder) {
        builder.withDetail("intranet", ImmutableMap.of(
            "entry", servers.values().stream()
                .filter(s->s instanceof EntryServer).map(EntryServer.class::cast)
                .collect(toMap(s->s.direction, s->s.info()))
            ,"relay", servers.values().stream()
                .filter(s->s instanceof RelayServer).map(RelayServer.class::cast)
                .collect(toMap(s->s.direction, s->s.info()))
            ));
    }

    private class EntryServer implements Server {
        private final IntranetConfig config;
        
        private final int relay;
        private final ServerSocketChannel relayServer;
        private SocketChannel relayer;
        
        private final int port;
        private final ServerSocketChannel server;
        private final AtomicInteger idx = new AtomicInteger();
        
        private final ChannelReader reader;
        private final ChannelWriter writer;
        
        private String direction;
        
        private Map<Integer, ConnectMeta> connections = new ConcurrentHashMap<>();
        
        public EntryServer(IntranetConfig config) throws IOException {
            this.config = config;
            this.port = config.port;
            this.relay = config.relay;
            
            try{
                server = ProxyApp.server(port);
                relayServer = ProxyApp.server(relay);
                
                reader = new ChannelReader(); reader.init(port+" entry reader");
                writer = new ChannelWriter(); writer.init(port+" entry writer");
                
                acceptor.accept(relayServer, this::relayAccept, e->log.error("establish relay connection err", e));
                acceptor.accept(server, this::serverAccept, e->log.error("establish client connection err", e));
                
                direction = String.format("%s->%s", port, relay);
            }catch(IOException e){
                destory();
                throw e;
            }
        }
        public synchronized void destory() {
            ImmutableSet.copyOf(connections.values()).forEach(ConnectMeta::destory);
            ProxyApp.close(server);
            ProxyApp.close(relayer);
            ProxyApp.close(relayServer);
            if(reader!=null) reader.destory();
            if(writer!=null) writer.destory();
            servers.remove(config);
            log.info("{} intranet entry停止", port);
        }
        
        private synchronized void relayAccept(SocketChannel relayer) throws IOException {
            try{
                log.info("{} intranet relay {} 接入", port, format(relayer.getRemoteAddress()));
            }catch(Exception ig){}
            relayDestory();
            relayer.configureBlocking(false);
            this.relayer = relayer;
            direction = String.format("%s->%s->%s", port, relay, format(relayer.getRemoteAddress()));
            relayRead();
        }
        private void relayRead() {
            reader.readOne(relayer, type->{
                switch(type){
                case MsgType.HeartBeat.Type:
                    relayRead();
                    break;
                case MsgType.Transmit.Type:
                    reader.readUntilLength(relayer, 4, idBs->{
                        int id = Ints.fromByteArray(idBs);
                        reader.readUntilLength(relayer, 4, lenBs->{
                            int len = Ints.fromByteArray(lenBs);
                            reader.readUntilLength(relayer, len, data->{
                                relayRead();
                                ConnectMeta connect = connections.get(id);
                                if(connect==null) return;
                                connect.dataFromRelayToSrc(data);
                            }, this::relayException);
                        }, this::relayException);
                    }, this::relayException);
                    break;
                case MsgType.ConnectClose.Type:
                    reader.readUntilLength(relayer, 4, idBs->{
                        relayRead();
                        int id = Ints.fromByteArray(idBs);
                        ConnectMeta connect = connections.get(id);
                        if(connect==null) return;
                        connect.destory(false);
                    }, this::relayException);
                    break;
                default:
                    log.warn("unknown msg type : {}", type);
                    relayRead();
                    break;
                }
            }, this::relayException);
        }
        private synchronized void relayException(Exception e) {
            if(!isCloseException(e)) log.error("relay err", e);
            relayDestory();
        }
        private synchronized void relayDestory() {
            if(relayer==null) return;
            try{
                log.info("{} intranet relay {} 断开", port, format(relayer.getRemoteAddress()));
            }catch(Exception ig){}
            ImmutableSet.copyOf(connections.values()).forEach(ConnectMeta::destory);
            ProxyApp.close(relayer);
            relayer = null;
        }
        private void relayerCheck() throws IOException {
            if(relayer==null) throw new IOException("转发服务未连接");
        }
        private synchronized void relayWriteTransmit(byte[] body) throws IOException {
            relayerCheck();
            writer.write(relayer, body, this::relayException);
        }
        private synchronized void relayWriteConnectClose(byte[] body) {
            if(relayer==null) return;
            writer.write(relayer, body, e->{});
        }
        
        private synchronized void serverAccept(SocketChannel client) throws IOException {
            int id;
            do{ id = idx.incrementAndGet(); }while(connections.containsKey(id));
            connections.put(id, new ConnectMeta(id, client));
        }
        
        public synchronized void heartbeat() {
            if(relayer==null) return;
            writer.write(relayer, MsgType.HeartBeat.Packet, this::relayException);
        }
        @Override
        public synchronized void cleanIdle() {
            for(ConnectMeta connect : ImmutableSet.copyOf(connections.values())){
                if(System.currentTimeMillis()-connect.latestTouchTime<IntranetService.this.config.getMaxIdleTime()) continue;
                connect.destory();
            }
        }
        
        public Object info() {
            return connections.values().stream().map(c->c.id+": "+c.direction).collect(toList());
        }
        
        @RequiredArgsConstructor
        private class ConnectMeta {
            private final int id;
            private final byte[] idBs;
            
            private final SocketChannel client;
            
            private final String direction;
            
            private long latestTouchTime = System.currentTimeMillis();
            
            public ConnectMeta(int id, SocketChannel client) throws IOException {
                this.id = id; this.idBs = Ints.toByteArray(id);
                this.client = client;
                
                try{
                    relayerCheck();
                    
                    client.configureBlocking(false);
                    
                    direction = String.format("%s->%s" ,format(client.getRemoteAddress()), port(client.getLocalAddress()));
                    
                    reader.readAny(client, 1024, this::dataFromClientToRelay, this::onException);
                    
                    log.info("{} connected {}", port, direction);
                }catch(Exception e){
                    destory();
                    throw e;
                }
            }
            private void dataFromRelayToSrc(byte[] data) {
                writer.write(client, data, this::onException);
                onTrans();
            }
            private void dataFromClientToRelay(ByteBuffer data) throws IOException {
                EntryServer.this.relayWriteTransmit(MsgType.Transmit.packet(idBs, data));
                onTrans();
                
                reader.readAny(client, data, this::dataFromClientToRelay, this::onException);
            }
            
            private void onTrans() {
                latestTouchTime = System.currentTimeMillis();
            }
            
            private synchronized void onException(Exception e) {
                if(!isCloseException(e)) log.error("connection {} err", direction, e);
                destory();
            }
            
            private void destory() {
                destory(true);
            }
            private void destory(boolean sendCloseMsgToRelay) {
                ProxyApp.close(client);
                connections.remove(id);
                if(sendCloseMsgToRelay) relayWriteConnectClose(MsgType.ConnectClose.packet(idBs));
                
                log.info("{} disconnected {}", port, direction);
            }
        }
    }
    
    private class RelayServer implements Server {
        private final IntranetConfig config;
        
        private final HostAndPort entryConfig;
        private final HostAndPort targetConfig;
        
        private final ChannelReader reader;
        private final ChannelWriter writer;
        
        private Delayed entryConnectRetryDelay;
        
        private SocketChannel entry;
        
        private String direction;
        
        private Map<Integer, ConnectMeta> connections = new ConcurrentHashMap<>();
        
        public RelayServer(IntranetConfig config) throws IOException {
            this.config = config;
            this.entryConfig = config.getEntry();
            this.targetConfig = config.getTarget();
            
            try{
                reader = new ChannelReader(); reader.init(targetConfig+" relay reader");
                writer = new ChannelWriter(); writer.init(targetConfig+" relay writer");
                
                direction = String.format("%s->%s->port->%s", entryConfig, "connecting", targetConfig);
                
                entryConnectBegin();
            }catch(Exception e){
                destory();
                throw e;
            }
        }
        private synchronized void entryConnectBegin() {
            try{
                SocketChannel entry = SocketChannel.open();
                entry.configureBlocking(false);
                entry.connect(new InetSocketAddress(entryConfig.getHostText(), entryConfig.getPort()));
                connector.connect(entry, ()->entryConnected(entry), this::entryOnConnectException);
            }catch(Exception e){
                log.error("尝试打开与entry({})服务连接的端口失败", entryConfig, e);
                entryConnectRetry();
            }
        }
        private void entryConnectRetry() {
            direction = String.format("%s->%s->port->%s", entryConfig, "connecting", targetConfig);
            entryConnectRetryDelay = delayRunner.run(EntryConnectRetryGap,
                this::entryConnectBegin, this::entryOnConnectException);
        }
        private synchronized void entryOnConnectException(Exception e) {
            log.error("尝试与entry({})服务建立连接失败，{}s后重试", entryConfig, EntryConnectRetryGap/1000, e);
            entryConnectRetry();
        }
        private synchronized void entryConnected(SocketChannel entry) throws IOException {
            direction = String.format("%s->%s->port->%s", entryConfig, port(entry.getLocalAddress()), targetConfig);
            this.entry = entry;
            log.info("与entry({}<->{})服务连接建立", entryConfig, port(entry.getLocalAddress()));
            entryRead();
        }
        private void entryRead() {
            reader.readOne(entry, type->{
                switch(type){
                case MsgType.HeartBeat.Type:
                    entryRead();
                    break;
                case MsgType.Transmit.Type:
                    reader.readUntilLength(entry, 4, idBs->{
                        int id = Ints.fromByteArray(idBs);
                        reader.readUntilLength(entry, 4, lenBs->{
                            int len = Ints.fromByteArray(lenBs);
                            reader.readUntilLength(entry, len, data->{
                                entryRead();
                                ConnectMeta connect = connections.get(id);
                                if(connect==null){
                                    try{
                                        connections.put(id, connect = new ConnectMeta(id));
                                    }catch(Exception e){
                                        log.error("initial connection err", e);
                                        return;
                                    }
                                }
                                connect.dataFromEntryToTarget(data);
                            }, this::entryOnException);
                        }, this::entryOnException);
                    }, this::entryOnException);
                    break;
                case MsgType.ConnectClose.Type:
                    reader.readUntilLength(entry, 4, idBs->{
                        entryRead();
                        int id = Ints.fromByteArray(idBs);
                        ConnectMeta connect = connections.get(id);
                        if(connect==null) return;
                        connect.destory(false);
                    }, this::entryOnException);
                    break;
                default:
                    log.warn("unknown msg type : {}", type);
                    entryRead();
                    break;
                }
            }, this::entryOnException);
            
        }
        private synchronized void entryOnException(Exception e) {
            if(!isCloseException(e)) log.error("与entry({})服务传输数据失败，重建连接", entryConfig, e);
            log.info("与entry({})服务连接断开", entryConfig);
            ImmutableSet.copyOf(connections.values()).forEach(ConnectMeta::destory);
            ProxyApp.close(entry);
            entry = null;
            entryConnectBegin();
        }
        private synchronized void entryWriteTransmit(byte[] body) throws IOException {
            entryCheck();
            writer.write(entry, body, this::entryOnException);
        }
        private synchronized void entryWriteConnectClose(byte[] packet) {
            if(entry==null) return;
            writer.write(entry, packet, e->{});
        }
        private void entryCheck() throws IOException {
            if(entry==null) throw new IOException("入口服务未连接");
        }
        
        public synchronized void heartbeat() {
            if(entry==null) return;
            writer.write(entry, MsgType.HeartBeat.Packet, this::entryOnException);
        }
        
        public synchronized void destory() {
            delayRunner.cancel(entryConnectRetryDelay);
            ImmutableSet.copyOf(connections.values()).forEach(ConnectMeta::destory);
            ProxyApp.close(entry); entry = null;
            if(reader!=null) reader.destory();
            if(writer!=null) writer.destory();
            servers.remove(config);
            log.info("{} intranet relay停止", targetConfig);
        }
        
        @Override
        public synchronized void cleanIdle() {
            for(ConnectMeta connect : ImmutableSet.copyOf(connections.values())){
                if(System.currentTimeMillis()-connect.latestTouchTime<IntranetService.this.config.getMaxIdleTime()) continue;
                connect.destory();
            }
        }
        
        @SneakyThrows
        public Object info() {
            return connections.values().stream().map(c->c.id+": "+c.direction).collect(toList());
        }
        
        private class ConnectMeta {
            private final int id;
            private final byte[] idBs;
            private final SocketChannel target;
            
            private String direction;
            
            private long latestTouchTime = System.currentTimeMillis();
            
            public ConnectMeta(int id) throws IOException {
                this.id = id;
                idBs = Ints.toByteArray(id);
                
                try{
                    target = SocketChannel.open();
                    target.configureBlocking(false);
                    target.connect(new InetSocketAddress(targetConfig.getHostText(), targetConfig.getPort()));
                    
                    direction = String.format("%s", "initializing");
                    connector.connect(target, ()->{
                        direction = String.format("%s", port(target.getLocalAddress()));
                        
                        targetRead(null);
                    }, this::onException);
                }catch(IOException e){
                    destory();
                    throw e;
                }
            }
            private void targetRead(ByteBuffer buf) {
                if(buf==null) buf = ByteBuffer.allocate(1024);
                reader.readAny(target, buf, data->{
                    dataFromTargetToEntry(data);
                    targetRead(data);
                }, this::onException);
            }
            private void dataFromEntryToTarget(byte[] data) {
                writer.write(target, data, this::onException);
                
                onTrans();
            }
            private void dataFromTargetToEntry(ByteBuffer data) throws IOException {
                RelayServer.this.entryWriteTransmit(MsgType.Transmit.packet(idBs, data));
                
                onTrans();
            }
            
            private void onTrans() {
                latestTouchTime = System.currentTimeMillis();
            }
            
            private synchronized void onException(Exception e) {
                if(!isCloseException(e)) log.error("connection {} err", direction, e);
                destory();
            }
            private synchronized void destory() {
                destory(true);
            }
            private synchronized void destory(boolean sendCloseMsgToEntry) {
                ProxyApp.close(target);
                connections.remove(id);
                entryWriteConnectClose(MsgType.ConnectClose.packet(idBs));
                
                log.info("{} disconnected {}", targetConfig, direction);
            }
        }
    }
    
    private interface Server {
        public void heartbeat();
        public void cleanIdle();
        public Object info();
        public void destory();
    }
    
    /**
     * 入口服务与转发服务之间的通信协议
     *
     * @author LV on 2022年3月28日
     */
    static class MsgType {
        
        /**
         * 心跳
         * 1s一次，整个消息仅一个字节
         */
        static class HeartBeat {
            static final byte Type = 0;
            static final byte[] Packet = {Type};
        }
        
        /**
         * 转发包
         * 整个消息由以下构成
         * 消息类型(1字节)：固定值1
         * 连接编号(4字节int)：入口服务 会为 客户端的每个请求分配一个编号
         * 内容长度(4字节int)：指示传输的内容长度
         * 内容(由内容长度确定)
         */
        static class Transmit {
            static final byte Type = 1;
            
            /**
             * 转发消息包装
             * @param idBs
             * @param data 默认其为读模式
             * @return
             */
            static byte[] packet(byte[] id, ByteBuffer data) {
                int bodyLen = 1+4+4+data.remaining();
                byte[] packet = new byte[bodyLen];
                packet[0] = Type;
                System.arraycopy(id, 0, packet, 1, 4);
                System.arraycopy(Ints.toByteArray(data.remaining()), 0, packet, 5, 4);
                System.arraycopy(data.array(), 0, packet, 9, data.remaining());
                return packet;
            }
            /**
             * 转发消息包装
             * @param idBs
             * @param data 默认其为读模式
             * @return
             */
            static byte[] packet(byte[] id, byte[] data) {
                int bodyLen = 1+4+4+data.length;
                byte[] packet = new byte[bodyLen];
                packet[0] = Type;
                System.arraycopy(id, 0, packet, 1, 4);
                System.arraycopy(Ints.toByteArray(data.length), 0, packet, 5, 4);
                System.arraycopy(data, 0, packet, 9, data.length);
                return packet;
            }
        }
        
        /**
         * 如果消息传输失败（client<->entry或relay<->target），会互相发送本消息
         * 整个消息由以下构成
         * 消息类型(1字节)：固定值2
         * 连接编号(2字节)：入口服务 会为 客户端的每个请求分配一个编号
         */
        static class ConnectClose {
            static final byte Type = 2;
            
            static byte[] packet(byte[] id){
                byte[] packet = new byte[1+id.length];
                packet[0] = Type;
                System.arraycopy(id, 0, packet, 1, 4);
                return packet;
            }
        }
        
    }
    
}