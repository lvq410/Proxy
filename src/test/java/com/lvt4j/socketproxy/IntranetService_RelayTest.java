package com.lvt4j.socketproxy;

import static org.apache.commons.lang3.ArrayUtils.EMPTY_BYTE_ARRAY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.net.HostAndPort;
import com.google.common.primitives.Ints;
import com.lvt4j.socketproxy.Config.IntranetConfig;
import com.lvt4j.socketproxy.Config.IntranetConfig.Type;
import com.lvt4j.socketproxy.IntranetService.MsgType;
import com.lvt4j.socketproxy.IntranetService.Server;

import lombok.SneakyThrows;

/**
 *
 * @author LV on 2022年4月4日
 */
public class IntranetService_RelayTest extends BaseTest {

    private IntranetService service;
    
    private Config config;
    private IntranetConfig relayConfig;
    
    private ChannelAcceptor acceptor;
    private ChannelConnector connector;
    private DelayRunner delayRunner;
    
    private HostAndPort entryConfig;
    private HostAndPort targetConfig;
    
    private Map<?, Server> servers;
    
    private int targetPort;
    private ServerSocket targetServer;
    private Socket target1;
    private Socket target2;
    
    private byte[] target1SendData;
    private byte[] target2SendData;
    
    private int entryPort;
    private ServerSocket entryServer;
    private Socket entry1;
    private Socket entry2;
    
    private byte[] entrySendData1;
    private byte[] entryReceiveData1;
    private List<Integer> entryReceiveLengths1;
    
    private byte[] entrySendData2;
    private byte[] entryReceiveData2;
    private List<Integer> entryReceiveLengths2;
    
    @Before
    @SuppressWarnings("unchecked")
    public void before() throws Exception {
        targetPort = availablePort();
        entryPort = availablePort();
        service = new IntranetService();
        
        targetServer = new ServerSocket(targetPort);
        entryServer = new ServerSocket(entryPort);
        
        entryConfig = HostAndPort.fromParts("127.0.0.1", entryPort);
        targetConfig = HostAndPort.fromParts("127.0.0.1", targetPort);
        
        config = new Config();
        relayConfig = new IntranetConfig();
        relayConfig.type = Type.Relay;
        relayConfig.entry = entryConfig;
        relayConfig.target = targetConfig;
        relayConfig.heartbeatInterval = TimeUnit.MINUTES.toMillis(5);
        relayConfig.heartbeatMissTimeout = TimeUnit.MINUTES.toMillis(5);
        config.setIntranet(Arrays.asList(relayConfig));
        
        acceptor = new ChannelAcceptor(); invoke(acceptor, "init");
        connector = new ChannelConnector(); invoke(connector, "init");
        delayRunner = new DelayRunner(); delayRunner.init();
        
        FieldUtils.writeField(service, "config", config, true);
        FieldUtils.writeField(service, "acceptor", acceptor, true);
        FieldUtils.writeField(service, "connector", connector, true);
        FieldUtils.writeField(service, "delayRunner", delayRunner, true);
        
        invoke(service, "init");
        
        servers = (Map<?, Server>) FieldUtils.readField(service, "servers", true);
    }
    
    @After
    public void after() throws Throwable {
        if(acceptor!=null) invoke(acceptor, "destory");
        if(connector!=null) invoke(connector, "destory");
        if(delayRunner!=null) invoke(delayRunner, "destory");
        if(service!=null) invoke(service, "destory");
        
        ProxyApp.close(target1);
        ProxyApp.close(target2);
        ProxyApp.close(targetServer);
        ProxyApp.close(entry1);
        ProxyApp.close(entry2);
        ProxyApp.close(entryServer);
    }
    
    @Test(timeout=10000)
    public void reload() throws Throwable {
        Map<?, ?> servers = (Map<?, ?>) FieldUtils.readField(service, "servers", true);
        assertEquals(1, servers.size());
        
        IntranetConfig relayConfig2 = new IntranetConfig();
        relayConfig2.type = Type.Relay;
        relayConfig2.entry = HostAndPort.fromParts("127.0.0.1", availablePort());
        relayConfig2.target = HostAndPort.fromParts("127.0.0.1", availablePort());
        config.setIntranet(Arrays.asList(relayConfig, relayConfig2));
        
        invoke(service, "reloadConfig");
        
        assertEquals(2, servers.size());
        
        config.setIntranet(Arrays.asList());
        
        invoke(service, "reloadConfig");
        assertEquals(0, servers.size());
    }
    
    @Test(timeout=10000)
    public void cleanIdle() throws Throwable {
        config.setMaxIdleTime(1);
        
        entry1 = entryServer.accept();
        trans(entry1);
        
        assertCnns(2);
        
        service.cleanIdle();
        
        assertCnns(0);
    }
    
    @Test(timeout=10000)
    public void trans() throws Throwable {
        assertCnns(0);
        
        entry1 = entryServer.accept();
        trans(entry1);
        
        assertCnns(2);
    }
    @Test(timeout=10000)
    public void entry_reboot() throws Throwable {
        trans();
        
        ProxyApp.close(entry1);
        
        entry2 = entryServer.accept();
        
        trans(entry2);
        
        assertCnns(2);
    }
    @Test(timeout=10000)
    public void target_close_or_entry_reboot() throws Throwable {
        trans();
        
        assertCnns(2);
        
        target1.close();
        
        byte type = (byte) entry1.getInputStream().read();
        assertEquals(MsgType.ConnectClose.Type, type);
        
        byte[] intBs = new byte[4];
        read(entry1.getInputStream(), intBs);
        assertEquals(1, Ints.fromByteArray(intBs));
        
        assertCnns(1);
        
        target2.close();
        
        type = (byte) entry1.getInputStream().read();
        assertEquals(MsgType.ConnectClose.Type, type);
        
        intBs = new byte[4];
        read(entry1.getInputStream(), intBs);
        assertEquals(2, Ints.fromByteArray(intBs));
        
        assertCnns(0);
        
        entry1.close();
        
        entry2 = entryServer.accept();
        
        trans(entry2);
        
        assertCnns(2);
    }
    
    /**
     * 长时间未收到entry心跳，relay server应当重试连接entry
     * @throws Throwable
     */
    @Test(timeout=60000)
    public void close_on_entry_heartbeat_timeout() throws Throwable {
        config.setIntranet(Arrays.asList());
        invoke(service, "init");
        
        relayConfig.heartbeatInterval = TimeUnit.SECONDS.toMillis(1);
        relayConfig.heartbeatMissTimeout = TimeUnit.SECONDS.toMillis(5);
        config.setIntranet(Arrays.asList(relayConfig));
        invoke(service, "init");
        
        entry1 = entryServer.accept();
        entry1.getOutputStream().write(MsgType.HeartBeat.Packet);
        Thread.sleep(6000);
        try{
            entry1.getOutputStream().write(MsgType.HeartBeat.Packet);
            assertTrue("entry1应当已被关闭", false);
        }catch(SocketException e){}
    }
    
    private void trans(Socket entry) throws Throwable {
        int count = 10;
        
        MutableObject<Throwable> exRef = new MutableObject<>();
        UncaughtExceptionHandler exHandler = (t,e)->exRef.setValue(e);
        
        entrySendData1 = entrySendData2 = EMPTY_BYTE_ARRAY;
        Thread entrySender1 = new Thread("entrySender1"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                byte[] data = rand();
                byte[] body = MsgType.Transmit.packet(Ints.toByteArray(1), data);
                synchronized(config){
                    entry.getOutputStream().write(body);
                }
                entrySendData1 = ArrayUtils.addAll(entrySendData1, data);
                Thread.sleep(10);
            }
            entry.getOutputStream().flush();
        }}; entrySender1.setUncaughtExceptionHandler(exHandler);
        Thread entrySender2 = new Thread("entrySender2"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                byte[] data = rand();
                byte[] body = MsgType.Transmit.packet(Ints.toByteArray(2), data);
                synchronized(config){
                    entry.getOutputStream().write(body);
                }
                entrySendData2 = ArrayUtils.addAll(entrySendData2, data);
                Thread.sleep(10);
            }
            entry.getOutputStream().flush();
        }}; entrySender2.setUncaughtExceptionHandler(exHandler);
        Thread entryHeartbeatSender = new Thread("entryHeartbeatSender"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                synchronized(config){
                    entry.getOutputStream().write(MsgType.HeartBeat.Packet);
                }
                Thread.sleep(10);
            }
            entry.getOutputStream().flush();
        }}; entryHeartbeatSender.setUncaughtExceptionHandler(exHandler);
        
        target1SendData = target2SendData = EMPTY_BYTE_ARRAY;
        Thread target1Sender = new Thread("target1Sender"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                byte[] data = rand();
                target1.getOutputStream().write(data);
                target1SendData = ArrayUtils.addAll(target1SendData, data);
                Thread.sleep(10);
            }
            target1.getOutputStream().flush();
        }}; target1Sender.setUncaughtExceptionHandler(exHandler);
        Thread target2Sender = new Thread("target2Sender"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                byte[] data = rand();
                target2.getOutputStream().write(data);
                target2SendData = ArrayUtils.addAll(target2SendData, data);
                Thread.sleep(10);
            }
            target2.getOutputStream().flush();
        }}; target2Sender.setUncaughtExceptionHandler(exHandler);
        Thread relayHeartbeatSender = new Thread("relayHeartbeatSender"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                servers.values().forEach(s->s.heartbeat());
                Thread.sleep(10);
            }
        }}; relayHeartbeatSender.setUncaughtExceptionHandler(exHandler);
        
        AtomicInteger entryReceiveHeartbeatNum = new AtomicInteger();
        entryReceiveLengths1=new LinkedList<>(); entryReceiveLengths2=new LinkedList<>();
        entryReceiveData1 = entryReceiveData2 = EMPTY_BYTE_ARRAY;
        Thread entryReceiver = new Thread("entryReceiver"){@SneakyThrows public void run() {
            for(int i=0; i<count*3; i++){
                InputStream in = entry.getInputStream();
                int type = in.read();
                switch(type){
                case MsgType.HeartBeat.Type:
                    entryReceiveHeartbeatNum.incrementAndGet();
                    break;
                case MsgType.Transmit.Type:
                    byte[] intBs = new byte[4];
                    read(in, intBs);
                    int id = Ints.fromByteArray(intBs);
                    read(in, intBs);
                    int len = Ints.fromByteArray(intBs);
                    byte[] data = new byte[len];
                    read(in, data);
                    switch(id){
                    case 1:
                        entryReceiveLengths1.add(data.length);
                        entryReceiveData1 = ArrayUtils.addAll(entryReceiveData1, data);
                        break;
                    case 2:
                        entryReceiveLengths2.add(data.length);
                        entryReceiveData2 = ArrayUtils.addAll(entryReceiveData2, data);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown id:"+id);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("unknown msg type:"+type);
                }
            }
        }}; entryReceiver.setUncaughtExceptionHandler(exHandler);
        
        entrySender1.start(); target1 = targetServer.accept();
        entrySender2.start(); target2 = targetServer.accept();
        entryHeartbeatSender.start();
        target1Sender.start(); target2Sender.start(); relayHeartbeatSender.start();
        entryReceiver.start();
        
        entrySender1.join(); entrySender2.join(); entryHeartbeatSender.join();
        target1Sender.join(); target2Sender.join(); relayHeartbeatSender.join();
        entryReceiver.join();
        
        if(exRef.getValue()!=null) throw exRef.getValue();
        
        assertArrayEquals(target1SendData, entryReceiveData1);
        assertArrayEquals(target2SendData, entryReceiveData2);
        assertEquals(count, entryReceiveHeartbeatNum.get());
        
        assertBs(entrySendData1, target1.getInputStream());
        assertBs(entrySendData2, target2.getInputStream());
    }
    
    
    private void assertCnns(int expectedSize) throws Exception {
        Thread.sleep(500); 
        
        Map<?, ?> servers = (Map<?, ?>) FieldUtils.readField(service, "servers", true);
        
        Object serverMeta = servers.get(relayConfig);
        Map<?, ?> connections = (Map<?, ?>) FieldUtils.readField(serverMeta, "connections", true);
        assertEquals(expectedSize, connections.size());
    }
}