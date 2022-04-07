package com.lvt4j.socketproxy;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import com.lvt4j.socketproxy.Config.TcpConfig;

/**
 *
 * @author LV on 2022年4月3日
 */
public class TcpServiceTest extends BaseTest {

    private int port = availablePort();
    
    private TcpService service;
    
    private Config config;
    private TcpConfig tcpConfig;
    private ChannelAcceptor acceptor;
    private ChannelConnector connector;
    
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    
    private int serverPort = availablePort();
    private ServerSocket server;
    private Socket serverAccept;
    private InputStream acceptIn;
    private OutputStream acceptOut;
    
    @Before
    public void before() throws Exception {
        service = new TcpService();
        
        config = new Config();
        tcpConfig = new TcpConfig();
        tcpConfig.port = port;
        tcpConfig.target = HostAndPort.fromParts("127.0.0.1", serverPort);
        config.setTcp(Arrays.asList(tcpConfig));
        
        acceptor = new ChannelAcceptor(); invoke(acceptor, "init");
        connector = new ChannelConnector(); invoke(connector, "init");
        
        FieldUtils.writeField(service, "config", config, true);
        FieldUtils.writeField(service, "acceptor", acceptor, true);
        FieldUtils.writeField(service, "connector", connector, true);
        
        invoke(service, "init");
        
        socket = new Socket("127.0.0.1", port);
        in = socket.getInputStream();
        out = socket.getOutputStream();
        
        server = new ServerSocket(serverPort);
    }
    
    @After
    public void after() throws IOException {
        if(acceptor!=null) invoke(acceptor, "destory");
        if(connector!=null) invoke(connector, "destory");
        if(service!=null) invoke(service, "destory");
        
        if(socket!=null) socket.close();
        if(serverAccept!=null) serverAccept.close();
        if(server!=null) server.close();
    }
    
    @Test(timeout=60000)
    @SuppressWarnings("unchecked")
    public void reload() throws Exception {
        TcpConfig tcpConfig2 = new TcpConfig();
        tcpConfig2.port = availablePort();
        tcpConfig2.target = HostAndPort.fromParts("127.0.0.1", serverPort);
        
        config.setTcp(Arrays.asList(tcpConfig, tcpConfig2));
        invoke(service, "reloadConfig");
        Map<Integer, ?> servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        assertEquals(2, servers.size());
        assertEquals(ImmutableSet.of(tcpConfig, tcpConfig2), servers.keySet());
        
        config.setTcp(Arrays.asList(tcpConfig2));
        invoke(service, "reloadConfig");
        servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        assertEquals(1, servers.size());
        assertEquals(ImmutableSet.of(tcpConfig2), servers.keySet());
    }
    
    @Test(timeout=60000)
    public void cleanIdle() throws Exception {
        config.setMaxIdleTime(1);
        
        trans();
        
        Map<?, ?> servers = (Map<?, ?>) FieldUtils.readField(service, "servers", true);
        assertEquals(1, servers.size());
        
        Object serverMeta = servers.get(tcpConfig);
        List<?> connections = (List<?>) FieldUtils.readField(serverMeta, "connections", true);
        assertEquals(1, connections.size());
        
        Thread.sleep(10);
        
        service.cleanIdle();
        
        connections = (List<?>) FieldUtils.readField(serverMeta, "connections", true);
        assertEquals(0, connections.size());
    }
    
    @Test(timeout=60000)
    public void trans() throws Exception {
        byte[] data = rand();
        out.write(data);
        
        serverAccept = server.accept();
        acceptIn = serverAccept.getInputStream();
        assertBs(data, acceptIn);
        
        acceptOut = serverAccept.getOutputStream();
        data = rand();
        acceptOut.write(data);
        assertBs(data, in);
        
        assertCnns(1);
    }
    
    private void assertCnns(int expectedSize) throws Exception {
        Thread.sleep(100); 
        
        Map<?, ?> servers = (Map<?, ?>) FieldUtils.readField(service, "servers", true);
        
        Object serverMeta = servers.get(tcpConfig);
        List<?> connections = (List<?>) FieldUtils.readField(serverMeta, "connections", true);
        assertEquals(expectedSize, connections.size());
    }
}
