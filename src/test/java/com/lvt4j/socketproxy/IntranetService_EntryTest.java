package com.lvt4j.socketproxy;

import static org.apache.commons.lang3.ArrayUtils.EMPTY_BYTE_ARRAY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.Socket;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.primitives.Ints;
import com.lvt4j.socketproxy.Config.IntranetConfig;
import com.lvt4j.socketproxy.Config.IntranetConfig.Type;
import com.lvt4j.socketproxy.IntranetService.MsgType;

import lombok.SneakyThrows;

/**
 *
 * @author LV on 2022年4月3日
 */
public class IntranetService_EntryTest extends BaseTest {

    private IntranetService service;
    
    private Config config;
    private IntranetConfig entryConfig;
    
    private ChannelAcceptor acceptor;
    private ChannelConnector connector;
    
    private int port;
    private int relay;
    
    private Socket client1;
    private List<Integer> client1SendLengths;
    private byte[] client1SendData;
    private Socket client2;
    private List<Integer> client2SendLengths;
    private byte[] client2SendData;
    
    private Socket relay1;
    private Socket relay2;
    
    private byte[] relaySendData1;
    private byte[] relayReceiveData1;
    private List<Integer> relayReceiveLengths1;
    private byte[] relaySendData2;
    private byte[] relayReceiveData2;
    private List<Integer> relayReceiveLengths2;
    
    
    @Before
    public void before() throws Exception {
        port = availablePort();
        relay = availablePort();
        service = new IntranetService();
        
        config = new Config();
        entryConfig = new IntranetConfig();
        entryConfig.type = Type.Entry;
        entryConfig.port = port;
        entryConfig.relay = relay;
        config.setIntranet(Arrays.asList(entryConfig));
        
        acceptor = new ChannelAcceptor(); invoke(acceptor, "init");
        connector = new ChannelConnector(); invoke(connector, "init");
        
        FieldUtils.writeField(service, "config", config, true);
        FieldUtils.writeField(service, "acceptor", acceptor, true);
        FieldUtils.writeField(service, "connector", connector, true);
        
        invoke(service, "init");
    }
    private void initClient() throws Exception {
        client1 = new Socket("127.0.0.1", port);
        client2 = new Socket("127.0.0.1", port);
    }
    private void initRelayer() throws Exception {
        relay1 = new Socket("127.0.0.1", relay);
        relaySendData1 = relayReceiveData1 = EMPTY_BYTE_ARRAY;
        relaySendData2 = relayReceiveData2 = EMPTY_BYTE_ARRAY;
        relayReceiveLengths1 = new LinkedList<>();
        relayReceiveLengths2 = new LinkedList<>();
    }
    private void initRelayer2() throws Exception {
        relay2 = new Socket("127.0.0.1", relay);
        relaySendData1 = relayReceiveData1 = EMPTY_BYTE_ARRAY;
        relaySendData2 = relayReceiveData2 = EMPTY_BYTE_ARRAY;
        relayReceiveLengths1 = new LinkedList<>();
        relayReceiveLengths2 = new LinkedList<>();
    }
    
    @After
    public void after() throws IOException {
        if(acceptor!=null) invoke(acceptor, "destory");
        if(connector!=null) invoke(connector, "destory");
        if(service!=null) invoke(service, "destory");
        
        ProxyApp.close(client1);
        ProxyApp.close(client2);
        ProxyApp.close(relay1);
        ProxyApp.close(relay2);
    }
    
    @Test(timeout=10000)
    public void reload() throws Throwable {
        Map<?, ?> servers = (Map<?, ?>) FieldUtils.readField(service, "servers", true);
        assertEquals(1, servers.size());
        
        IntranetConfig entryConfig2 = new IntranetConfig();
        entryConfig2.type = Type.Entry;
        entryConfig2.port = availablePort();
        entryConfig2.relay = availablePort();
        config.setIntranet(Arrays.asList(entryConfig, entryConfig2));
        
        invoke(service, "reloadConfig");
        
        assertEquals(2, servers.size());
        
        config.setIntranet(Arrays.asList());
        
        invoke(service, "reloadConfig");
        assertEquals(0, servers.size());
    }
    
    @Test(timeout=10000)
    public void cleanIdle() throws Throwable {
        config.setMaxIdleTime(1);
        
        initRelayer(); initClient();
        
        assertCnns(2);
        
        service.cleanIdle();
        
        assertCnns(0);
    }
    
    @Test(timeout=10000)
    public void connect_no_relay() throws Exception {
        client1 = new Socket("127.0.0.1", port);
        assertCnns(0);
    }
    
    @Test(timeout=60000)
    public void trans() throws Throwable {
        initRelayer(); initClient();
        
        assertCnns(2);
        
        trans(relay1, 0);
    }
    @Test(timeout=60000)
    public void relayer_reconnect() throws Throwable {
        trans();
        
        initRelayer2();
        
        assertCnns(0);
        
        initClient();
        
        assertCnns(2);
        
        trans(relay2, 2);
    }
    @Test(timeout=60000)
    public void client_close_or_relayer_reconnect() throws Throwable {
        initRelayer(); initClient();
        
        assertCnns(2);
        
        client1.close();
        
        byte type = (byte) relay1.getInputStream().read();
        assertEquals(MsgType.ConnectClose.Type, type);
        
        byte[] intBs = new byte[4];
        read(relay1.getInputStream(), intBs);
        assertEquals(1, Ints.fromByteArray(intBs));
        
        assertCnns(1);
        
        client2.close();
        
        type = (byte) relay1.getInputStream().read();
        assertEquals(MsgType.ConnectClose.Type, type);
        
        read(relay1.getInputStream(), intBs);
        assertEquals(2, Ints.fromByteArray(intBs));
        
        initRelayer2();
        
        assertCnns(0);
    }
    
    
    private void trans(Socket relay, int idBase) throws Throwable {
        int count = 100;
        
        MutableObject<Throwable> exRef = new MutableObject<>();
        UncaughtExceptionHandler exHandler = (t,e)->exRef.setValue(e);
        
        client1SendData = EMPTY_BYTE_ARRAY; client1SendLengths = new LinkedList<>();
        Thread client1Sender = new Thread("client1Sender"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                byte[] data = rand();
                client1.getOutputStream().write(data);
                client1SendData = ArrayUtils.addAll(client1SendData, data);
                client1SendLengths.add(data.length);
                Thread.sleep(10);
            }
        }}; client1Sender.setUncaughtExceptionHandler(exHandler);
        client2SendData = EMPTY_BYTE_ARRAY; client2SendLengths = new LinkedList<>();
        Thread client2Sender = new Thread("client2Sender"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                byte[] data = rand();
                client2.getOutputStream().write(data);
                client2SendData = ArrayUtils.addAll(client2SendData, data);
                client2SendLengths.add(data.length);
                Thread.sleep(10);
            }
        }}; client2Sender.setUncaughtExceptionHandler(exHandler);
        Thread entryHeartbeatSender = new Thread("entryHeartbeatSender"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                service.heartbeat();
                Thread.sleep(10);
            }
        }}; entryHeartbeatSender.setUncaughtExceptionHandler(exHandler);
        
        relaySendData1 = EMPTY_BYTE_ARRAY;
        Thread relay1Sender = new Thread("relay1Sender"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                byte[] data = rand();
                synchronized(config){ //relay 互斥
                    relay.getOutputStream().write(MsgType.Transmit.packet(Ints.toByteArray(idBase+1), data)); //id 1
                }
                relaySendData1 = ArrayUtils.addAll(relaySendData1, data);
                Thread.sleep(10);
            }
        }}; relay1Sender.setUncaughtExceptionHandler(exHandler);
        relaySendData2 = EMPTY_BYTE_ARRAY;
        Thread relay2Sender = new Thread("relay2Sender"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                byte[] data = rand();
                synchronized(config){ //relay 互斥
                    relay.getOutputStream().write(MsgType.Transmit.packet(Ints.toByteArray(idBase+2), data)); //id 2
                }
                relaySendData2 = ArrayUtils.addAll(relaySendData2, data);
                Thread.sleep(10);
            }
        }}; relay2Sender.setUncaughtExceptionHandler(exHandler);
        Thread relayHeartbeatSender = new Thread("relayHeartbeatSender"){@SneakyThrows public void run() {
            for(int i=0; i<count; i++){
                synchronized(config){ //relay 互斥
                    relay.getOutputStream().write(MsgType.HeartBeat.Packet);
                }
                Thread.sleep(10);
            }
        }}; relayHeartbeatSender.setUncaughtExceptionHandler(exHandler);
        
        AtomicInteger relayReceiveHeartbeatNum = new AtomicInteger();
        relayReceiveData1 = EMPTY_BYTE_ARRAY; relayReceiveLengths1 = new LinkedList<>();
        relayReceiveData2 = EMPTY_BYTE_ARRAY; relayReceiveLengths2 = new LinkedList<>();
        Thread relayReceiver = new Thread("relayReceiver"){@SneakyThrows public void run() {
            for(int i=0; i<count*3; i++){
                InputStream in = relay.getInputStream();
                int type = in.read();
                switch(type){
                case MsgType.HeartBeat.Type:
                    relayReceiveHeartbeatNum.incrementAndGet();
                    break;
                case MsgType.Transmit.Type:
                    byte[] intBs = new byte[4];
                    read(in, intBs);
                    int id = Ints.fromByteArray(intBs);
                    read(in, intBs);
                    int len = Ints.fromByteArray(intBs);
                    byte[] data = new byte[len];
                    read(in, data);
                    id -= idBase;
                    switch(id){
                    case 1:
                        relayReceiveLengths1.add(data.length);
                        relayReceiveData1 = ArrayUtils.addAll(relayReceiveData1, data);
                        break;
                    case 2:
                        relayReceiveLengths2.add(data.length);
                        relayReceiveData2 = ArrayUtils.addAll(relayReceiveData2, data);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown id:"+id);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("unknown msg type:"+type);
                }
            }
        }}; relayReceiver.setUncaughtExceptionHandler(exHandler);
        
        client1Sender.start(); client2Sender.start(); entryHeartbeatSender.start();
        relay1Sender.start(); relay2Sender.start(); relayHeartbeatSender.start();
        relayReceiver.start();
        
        client1Sender.join(); client2Sender.join(); entryHeartbeatSender.join();
        relay1Sender.join(); relay2Sender.join(); relayHeartbeatSender.join();
        relayReceiver.join();
        
        if(exRef.getValue()!=null) throw exRef.getValue();
        
        assertArrayEquals(client1SendData, relayReceiveData1);
        assertArrayEquals(client2SendData, relayReceiveData2);
        assertEquals(count, relayReceiveHeartbeatNum.get());
        
        assertBs(relaySendData1, client1.getInputStream());
        assertBs(relaySendData2, client2.getInputStream());
    }
    private void assertCnns(int expectedSize) throws Exception {
        Thread.sleep(500); 
        
        Map<?, ?> servers = (Map<?, ?>) FieldUtils.readField(service, "servers", true);
        
        Object serverMeta = servers.get(entryConfig);
        Map<?, ?> connections = (Map<?, ?>) FieldUtils.readField(serverMeta, "connections", true);
        assertEquals(expectedSize, connections.size());
    }
}