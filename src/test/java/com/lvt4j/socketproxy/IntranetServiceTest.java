package com.lvt4j.socketproxy;

import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.ArrayUtils.EMPTY_BYTE_ARRAY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.net.HostAndPort;
import com.lvt4j.socketproxy.Config.IntranetConfig;
import com.lvt4j.socketproxy.Config.IntranetConfig.Type;

import lombok.SneakyThrows;

/**
 *
 * @author LV on 2022年4月6日
 */
public class IntranetServiceTest extends BaseTest {

    private IntranetService service;
    
    private Config config;
    private IntranetConfig entryConfig;
    private IntranetConfig relayConfig;
    
    private ChannelAcceptor acceptor;
    private ChannelConnector connector;
    private DelayRunner delayRunner;
    
    private Socket client1;
    private Socket client2;
    
    private byte[] client1Sends,client2Sends;
    private byte[] client1Receives,client2Receives;
    
    private int targetPort;
    
    private ServerSocket targetServer;
    private Socket target1;
    private Socket target2;
    
    private byte[] target1Sends,target2Sends;
    private byte[] target1Receives,target2Receives;
    
    @Before
    public void before() throws Exception {
        targetPort = availablePort();
        targetServer = new ServerSocket(targetPort);
        
        service = new IntranetService();
        
        config = new Config();
        
        entryConfig = new IntranetConfig();
        entryConfig.type = Type.Entry;
        entryConfig.port = availablePort();
        entryConfig.relay = availablePort();
        
        relayConfig = new IntranetConfig();
        relayConfig.type = Type.Relay;
        relayConfig.entry = HostAndPort.fromParts("127.0.0.1", entryConfig.relay);
        relayConfig.target = HostAndPort.fromParts("127.0.0.1", targetPort);
        
        config.setIntranet(Arrays.asList(entryConfig, relayConfig));
        
        acceptor = new ChannelAcceptor(); invoke(acceptor, "init");
        connector = new ChannelConnector(); invoke(connector, "init");
        delayRunner = new DelayRunner(); delayRunner.init();
        
        FieldUtils.writeField(service, "config", config, true);
        FieldUtils.writeField(service, "acceptor", acceptor, true);
        FieldUtils.writeField(service, "connector", connector, true);
        FieldUtils.writeField(service, "delayRunner", delayRunner, true);
        
        invoke(service, "init");
        
        client1 = new Socket("127.0.0.1", entryConfig.port);
        client2 = new Socket("127.0.0.1", entryConfig.port);
    }
    
    @After
    public void after() throws IOException {
        if(acceptor!=null) invoke(acceptor, "destory");
        if(connector!=null) invoke(connector, "destory");
        if(delayRunner!=null) invoke(delayRunner, "destory");
        if(service!=null) invoke(service, "destory");
        
        ProxyApp.close(client1);
        ProxyApp.close(client2);
        ProxyApp.close(target1);
        ProxyApp.close(target2);
        ProxyApp.close(targetServer);
    }
    
    @Test
    public void trans() throws Throwable {
        assertCnns(2, 0);
        
        int count = 1;
        
        MutableObject<Throwable> exRef = new MutableObject<>();
        UncaughtExceptionHandler exHandler = (t,e)->exRef.setValue(e);
        
        List<byte[]> client1SendList = IntStream.range(0, count).mapToObj(i->rand()).collect(toList());
        List<byte[]> client2SendList = IntStream.range(0, count).mapToObj(i->rand()).collect(toList());
        client1Sends = client1SendList.stream().reduce(EMPTY_BYTE_ARRAY, ArrayUtils::addAll);
        client2Sends = client2SendList.stream().reduce(EMPTY_BYTE_ARRAY, ArrayUtils::addAll);
        Thread client1Sender = new Thread("client1Sender"){@SneakyThrows public void run() {
            for(byte[] data : client1SendList){
                client1.getOutputStream().write(data);
                Thread.sleep(10);
            }
        }}; client1Sender.setUncaughtExceptionHandler(exHandler);
        Thread client2Sender = new Thread("client2Sender"){@SneakyThrows public void run() {
            for(byte[] data : client2SendList){
                client2.getOutputStream().write(data);
                Thread.sleep(10);
            }
        }}; client2Sender.setUncaughtExceptionHandler(exHandler);
        client1Sender.start(); target1 = targetServer.accept();
        client2Sender.start(); target2 = targetServer.accept();
        
        assertCnns(2, 2);
        
        target1Receives = target2Receives = EMPTY_BYTE_ARRAY;
        Thread target1Receiver = new Thread("target1Receiver"){@SneakyThrows public void run() {
            while(client1Sends.length>target1Receives.length){
                target1Receives = ArrayUtils.add(target1Receives, (byte)target1.getInputStream().read());
            }
        }}; target1Receiver.setUncaughtExceptionHandler(exHandler);
        Thread target2Receiver = new Thread("target2Receiver"){@SneakyThrows public void run() {
            while(client2Sends.length>target2Receives.length){
                target2Receives = ArrayUtils.add(target2Receives, (byte)target2.getInputStream().read());
            }
        }}; target2Receiver.setUncaughtExceptionHandler(exHandler);
        target1Receiver.start(); target2Receiver.start();
        
        List<byte[]> target1SendList = IntStream.range(0, count).mapToObj(i->rand()).collect(toList());
        List<byte[]> target2SendList = IntStream.range(0, count).mapToObj(i->rand()).collect(toList());
        target1Sends = target1SendList.stream().reduce(EMPTY_BYTE_ARRAY, ArrayUtils::addAll);
        target2Sends = target2SendList.stream().reduce(EMPTY_BYTE_ARRAY, ArrayUtils::addAll);
        Thread target1Sender = new Thread("target1Sender"){@SneakyThrows public void run() {
            for(byte[] data : target1SendList){
                target1.getOutputStream().write(data);
                Thread.sleep(10);
            }
        }}; target1Sender.setUncaughtExceptionHandler(exHandler);
        Thread target2Sender = new Thread("target2Sender"){@SneakyThrows public void run() {
            for(byte[] data : target2SendList){
                target2.getOutputStream().write(data);
                Thread.sleep(10);
            }
        }}; target2Sender.setUncaughtExceptionHandler(exHandler);
        target1Sender.start(); target2Sender.start();
        
        client1Receives = client2Receives = EMPTY_BYTE_ARRAY;
        Thread client1Receiver = new Thread("client1Receiver"){@SneakyThrows public void run() {
            while(target1Sends.length>client1Receives.length){
                client1Receives = ArrayUtils.add(client1Receives, (byte)client1.getInputStream().read());
            }
        }}; client1Receiver.setUncaughtExceptionHandler(exHandler);
        Thread client2Receiver = new Thread("client2Receiver"){@SneakyThrows public void run() {
            while(target2Sends.length>client2Receives.length){
                client2Receives = ArrayUtils.add(client2Receives, (byte)client2.getInputStream().read());
            }
        }}; client2Receiver.setUncaughtExceptionHandler(exHandler);
        client1Receiver.start(); client2Receiver.start();
        
        long wait = count*20;
        client1Sender.join(wait); client2Sender.join(wait);
        target1Receiver.join(wait); target2Receiver.join(wait);
        target1Sender.join(wait); target2Receiver.join(wait);
        client1Receiver.join(wait); client2Receiver.join(wait);
        
        assertFalse(client1Sender.isAlive()); assertFalse(client2Sender.isAlive());
        assertFalse(target1Receiver.isAlive()); assertFalse(target2Receiver.isAlive());
        assertFalse(target1Sender.isAlive()); assertFalse(target2Receiver.isAlive());
        assertFalse(client1Receiver.isAlive()); assertFalse(client2Receiver.isAlive());
        
        if(exRef.getValue()!=null) throw exRef.getValue();
        
        assertArrayEquals(client1Sends, target1Receives);
        assertArrayEquals(client2Sends, target2Receives);
        assertArrayEquals(target1Sends, client1Receives);
        assertArrayEquals(target2Sends, client2Receives);
        
        client1.close();
        
        assertCnns(1, 1);
        
        target2.close();
        
        assertCnns(0, 0);
    }
    
    private void assertCnns(int entryCnns, int relayCnns) throws Exception {
        Thread.sleep(500); 
        
        Map<?, ?> servers = (Map<?, ?>) FieldUtils.readField(service, "servers", true);
        
        assertEquals(entryCnns, ((Map<?, ?>) FieldUtils.readField(servers.get(entryConfig), "connections", true)).size());
        assertEquals(relayCnns, ((Map<?, ?>) FieldUtils.readField(servers.get(relayConfig), "connections", true)).size());
    }
    
}