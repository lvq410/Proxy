package com.lvt4j.socketproxy;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.util.Collections.synchronizedList;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import com.google.common.primitives.Shorts;

import lombok.extern.slf4j.Slf4j;

/**
 * SOCKS5 UDP ASSOCIATE 中继服务
 * <p>
 * 管理所有 UDP 中继会话，单线程 + Selector 模式（与 ChannelReader 一致）。
 * 每个 UDP ASSOCIATE 会话对应一个 {@link Session}，持有独立的 {@link DatagramChannel}。
 * </p>
 * @author LV
 */
@Slf4j
@Service
public class Socks5UdpRelay extends Thread implements UncaughtExceptionHandler {

    private Selector selector;

    private List<Runnable> registerQueue = synchronizedList(new LinkedList<>());

    @PostConstruct
    public void init() throws IOException {
        init("Socks5UdpRelay");
    }
    public void init(String name) throws IOException {
        setName(name);
        setUncaughtExceptionHandler(this);
        selector = Selector.open();
        start();
    }

    @PreDestroy
    public void destory() {
        try{
            selector.close();
            join(1000);
        }catch(Exception e){
            log.error("socks5 udp relay close err", e);
        }
    }
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if(e instanceof ClosedSelectorException) return;
        log.error("socks5 udp relay err", e);
    }

    public void bindSession(Session session) {
        registerQueue.add(()->{
            try{
                session.channel.register(selector, OP_READ, session);
            }catch(Exception e){
                session.exHandler.accept(e);
            }
        });
        selector.wakeup();
    }

    public void unbindSession(Session session) {
        registerQueue.add(()->{
            SelectionKey key = session.channel.keyFor(selector);
            if(key!=null) key.cancel();
        });
        selector.wakeup();
    }

    @Override
    public void run() {
        try{
            while(selector.isOpen()){
                selector.select();
                while(!registerQueue.isEmpty()) registerQueue.remove(0).run();
                if(!selector.isOpen()) return;
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while(keys.hasNext()){
                    SelectionKey key = keys.next();
                    keys.remove();
                    if(!key.isValid() || !key.isReadable()) continue;
                    Session session = (Session) key.attachment();
                    try{
                        session.onReceive();
                    }catch(Exception e){
                        session.exHandler.accept(e);
                    }
                }
            }
        }catch(Throwable e){
            uncaughtException(this, e);
        }
    }

    /**
     * 一个 UDP ASSOCIATE 会话
     */
    public class Session {
        final DatagramChannel channel;
        final InetAddress clientIp;
        final Runnable onTrans;
        final Consumer<Exception> exHandler;

        private InetSocketAddress clientUdpAddr;
        private final ByteBuffer buf = ByteBuffer.allocate(65536);

        public Session(DatagramChannel channel, InetAddress clientIp,
                Runnable onTrans, Consumer<Exception> exHandler) {
            this.channel = channel;
            this.clientIp = clientIp;
            this.onTrans = onTrans;
            this.exHandler = exHandler;
        }

        void onReceive() throws IOException {
            buf.clear();
            InetSocketAddress sender = (InetSocketAddress) channel.receive(buf);
            if(sender==null) return;
            buf.flip();

            if(clientUdpAddr!=null ? sender.equals(clientUdpAddr) : sender.getAddress().equals(clientIp)){
                clientUdpAddr = sender;
                relayClientToTarget();
            }else{
                relayTargetToClient(sender);
            }
            onTrans.run();
        }

        /**
         * 客户端 → 中继 → 目标
         * 解析 SOCKS5 UDP 头，提取目标地址和数据，转发原始数据给目标
         */
        private void relayClientToTarget() throws IOException {
            if(buf.remaining()<4) return; // RSV(2) + FRAG(1) + ATYP(1) 至少4字节

            buf.getShort(); // RSV
            byte frag = buf.get();
            if(frag!=0) return; // 丢弃分片包

            byte atyp = buf.get();
            InetAddress targetAddr;
            switch(atyp){
            case 1: // IPv4
                if(buf.remaining()<6) return; // 4+2
                byte[] ipv4 = new byte[4];
                buf.get(ipv4);
                targetAddr = Inet4Address.getByAddress(ipv4);
                break;
            case 3: // 域名
                if(buf.remaining()<1) return;
                int domainLen = buf.get() & 0xFF;
                if(buf.remaining()<domainLen+2) return;
                byte[] domainBs = new byte[domainLen];
                buf.get(domainBs);
                targetAddr = InetAddress.getByName(new String(domainBs));
                break;
            case 4: // IPv6
                if(buf.remaining()<18) return; // 16+2
                byte[] ipv6 = new byte[16];
                buf.get(ipv6);
                targetAddr = Inet6Address.getByAddress(ipv6);
                break;
            default:
                return;
            }
            int targetPort = Shorts.fromBytes(buf.get(), buf.get()) & 0xFFFF;

            // 剩余部分即为实际数据
            channel.send(buf, new InetSocketAddress(targetAddr, targetPort));
        }

        /**
         * 目标 → 中继 → 客户端
         * 将原始数据封装 SOCKS5 UDP 头后转发给客户端
         */
        private void relayTargetToClient(InetSocketAddress sender) throws IOException {
            if(clientUdpAddr==null) return; // 尚未收到客户端的首包，无法转发

            byte[] addr = sender.getAddress().getAddress();
            byte atyp = (addr.length==4) ? (byte)1 : (byte)4;
            int port = sender.getPort();

            // 构造 SOCKS5 UDP 头 + 数据
            int headerLen = 2 + 1 + 1 + addr.length + 2; // RSV + FRAG + ATYP + ADDR + PORT
            ByteBuffer wrapped = ByteBuffer.allocate(headerLen + buf.remaining());
            wrapped.putShort((short)0);  // RSV
            wrapped.put((byte)0);        // FRAG
            wrapped.put(atyp);           // ATYP
            wrapped.put(addr);           // ADDR
            wrapped.putShort((short)port); // PORT
            wrapped.put(buf);            // DATA
            wrapped.flip();

            channel.send(wrapped, clientUdpAddr);
        }

        public void destroy() {
            ProxyApp.close(channel);
        }
    }

}
