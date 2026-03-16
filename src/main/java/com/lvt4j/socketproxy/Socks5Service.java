package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProxyApp.format;
import static com.lvt4j.socketproxy.ProxyApp.isCloseException;
import static com.lvt4j.socketproxy.ProxyApp.port;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.net.InetSocketAddress;
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
import com.lvt4j.socketproxy.Config.Socks5Config;

import lombok.extern.slf4j.Slf4j;

/**
 * socks5协议代理
 * @author LV on 2022年3月22日
 * @see <a href="https://wiyi.org/socks5-protocol-in-deep.html">参考</a>
 * @see <a href="https://wiyi.org/socks5-implementation.html">参考</a>
 * @author LV on 2022年4月2日
 */
@Slf4j
@Service
public class Socks5Service implements InfoContributor {

    @Autowired
    private Config config;
    @Autowired
    private ChannelAcceptor acceptor;
    @Autowired
    private ProtocolService protocolService;
    @Autowired
    private ChannelReader reader;
    @Autowired
    private Socks5UdpRelay udpRelay;
    
    private Map<Integer, ServerMeta> servers = new HashMap<>();
    
    @PostConstruct
    private void init() throws IOException {
        Config.changeCallback_socks5 = this::reloadConfig;
        
        reloadConfig();
    }
    private synchronized void reloadConfig() {
        List<Socks5Config> socks5 = config.getSocks5();
        ImmutableSet.copyOf(servers.keySet()).stream().filter(p->socks5.stream().noneMatch(c->c.getPort().equals(p))).forEach(removed->{
            ServerMeta s = servers.remove(removed);
            if(s!=null) s.destory();
        });
        for(Socks5Config conf : socks5){
            if(servers.containsKey(conf.getPort())) continue;
            try{
                servers.put(conf.getPort(), new ServerMeta(conf));
                log.info("{} socks5代理启动", conf.getPort());
            }catch(Exception e){
                log.error("{} socks5代理启动失败", conf.getPort(), e);
            }
        }
    }
    @PreDestroy
    private synchronized void destory() throws IOException {
        Config.changeCallback_socks5 = null;
        ImmutableSet.copyOf(servers.values()).forEach(ServerMeta::destory);
    }
    
    @Scheduled(cron="0/10 * * * * ?")
    public synchronized void cleanIdle() {
        servers.values().forEach(ServerMeta::cleanIdle);
    }
    
    @Override
    public void contribute(Builder builder) {
        if(servers.isEmpty()) return;
        builder.withDetail("socks5", config.getSocks5().stream().map(c->servers.get(c.getPort())).filter(Objects::nonNull)
            .map(ServerMeta::info).collect(joining("\n")));
    }
    
    
    private class ServerMeta {

        private Socks5Config conf;
        private int port;
        private ServerSocketChannel serverSocketChannel;

        private ChannelTransmitter client2target;
        private ChannelTransmitter target2client;

        private List<ConnectMeta> connections = Collections.synchronizedList(new LinkedList<>());

        public ServerMeta(Socks5Config conf) throws IOException {
            this.conf = conf;
            this.port = conf.getPort();
            
            try{
                serverSocketChannel = ProxyApp.server(null, port);
                
                client2target = new ChannelTransmitter(port+" c->t");
                target2client = new ChannelTransmitter(port+" t->c");
                
                acceptor.accept(serverSocketChannel, this::accept, e->log.error("establish connection err", e));
            }catch(Exception e){
                destory();
                throw e;
            }
        }
        private void accept(SocketChannel client) throws IOException {
            connections.add(new ConnectMeta(client));
        }
        
        public void destory() {
            ImmutableSet.copyOf(connections).forEach(ConnectMeta::destory);
            if(client2target!=null) client2target.destory();
            if(target2client!=null) target2client.destory();
            ProxyApp.close(serverSocketChannel);
            acceptor.waitDeregister(serverSocketChannel);
            servers.remove(port);
            log.info("{} socks5代理停止", port);
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
            .forEach((t, cs)->{
                infos.add("  "+t);
                cs.forEach(cnn->infos.add("  - "+cnn));
            });
            return StringUtils.join(infos, "\n");
        }
        
        private class ConnectMeta {
            private final SocketChannel client;

            private SocketChannel target;
            private Socks5UdpRelay.Session udpSession;
            private String targetStr;

            private String direction;

            private long latestTouchTime = System.currentTimeMillis();

            public ConnectMeta(SocketChannel client) throws IOException {
                this.client = client;

                try{
                    client.configureBlocking(false);

                    targetStr = "initializing";
                    direction = String.format("%s->%s->%s->%s"
                            ,format(client.getRemoteAddress()), port(client.getLocalAddress())
                            ,"initializing" ,"initializing");

                    if(log.isTraceEnabled()) log.trace("{} connecting {}", port, direction);

                    protocolService.socks5_server_connect(client, target->{
                        ConnectMeta.this.target = target;
                        targetStr = format(target.getRemoteAddress());

                        direction = String.format("%s->%s->%s->%s"
                            ,format(client.getRemoteAddress()), port(client.getLocalAddress())
                            ,port(target.getLocalAddress()), format(target.getRemoteAddress()));

                        client2target.transmit(client, target, 1024, this::onTrans, this::onException);
                        target2client.transmit(target, client, 1024, this::onTrans, this::onException);

                        log.info("{} connected {}", port, direction);
                    },
                    conf.getUdpAdvertiseHost(), conf.getUdpPortMin(), conf.getUdpPortMax(),
                    udpChannel->{
                        InetSocketAddress clientAddr = (InetSocketAddress) client.getRemoteAddress();
                        udpSession = udpRelay.new Session(udpChannel, clientAddr.getAddress(), this::onTrans, this::onException);
                        udpRelay.bindSession(udpSession);
                        InetSocketAddress udpAddr = (InetSocketAddress) udpChannel.getLocalAddress();
                        targetStr = "udp-relay:"+udpAddr.getPort();
                        direction = String.format("%s->%s->udp:%s"
                            ,format(client.getRemoteAddress()), port(client.getLocalAddress())
                            ,udpAddr.getPort());
                        log.info("{} udp associated {}", port, direction);
                        monitorTcpControl();
                    }, this::onException);
                }catch(IOException e){
                    destory();
                    throw e;
                }

            }

            private void monitorTcpControl() {
                reader.readAny(client, 1, buf->monitorTcpControl(), this::onException);
            }

            private void onTrans() {
                latestTouchTime = System.currentTimeMillis();
            }

            private synchronized void onException(Exception e) {
                if(!isCloseException(e)) log.error("connection {} err", direction, e);
                destory();
            }

            private void destory() {
                ProxyApp.close(client);
                ProxyApp.close(target);
                if(udpSession!=null){
                    udpRelay.unbindSession(udpSession);
                    udpSession.destroy();
                }
                connections.remove(this);

                log.info("{} disconnected {}", port, direction);
            }
        }
    }
}
