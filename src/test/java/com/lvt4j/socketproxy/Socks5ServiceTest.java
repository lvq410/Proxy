package com.lvt4j.socketproxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static com.lvt4j.socketproxy.ProtocolService.Socks5.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.primitives.Shorts;
import com.lvt4j.socketproxy.Config.Socks5Config;

/**
 *
 * @author LV on 2022年4月2日
 */
public class Socks5ServiceTest extends BaseTest {

    private int port = availablePort();
    
    private Socks5Service service;
    
    private Config config;
    
    private ChannelReader reader;
    private ChannelWriter writer;
    private ChannelConnector connector;
    private ProtocolService protocolService;
    
    private ChannelAcceptor acceptor;
    private Socks5UdpRelay udpRelay;
    
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
        service = new Socks5Service();
        
        config = new Config();
        Socks5Config socks5Conf = new Socks5Config(port);
        config.setSocks5(Arrays.asList(socks5Conf));
        FieldUtils.writeField(service, "config", config, true);

        acceptor = new ChannelAcceptor(); invoke(acceptor, "init");
        FieldUtils.writeField(service, "acceptor", acceptor, true);

        reader = new ChannelReader(); invoke(reader, "init");
        writer = new ChannelWriter(); invoke(writer, "init");
        connector = new ChannelConnector(); invoke(connector, "init");

        protocolService = new ProtocolService();
        FieldUtils.writeField(protocolService, "reader", reader, true);
        FieldUtils.writeField(protocolService, "writer", writer, true);
        FieldUtils.writeField(protocolService, "connector", connector, true);
        FieldUtils.writeField(service, "protocolService", protocolService, true);

        FieldUtils.writeField(service, "reader", reader, true);

        udpRelay = new Socks5UdpRelay(); invoke(udpRelay, "init");
        FieldUtils.writeField(service, "udpRelay", udpRelay, true);

        invoke(service, "init");
        
        socket = new Socket("127.0.0.1", port);
        in = socket.getInputStream();
        out = socket.getOutputStream();
        
        server = new ServerSocket(serverPort);
    }
    
    @After
    public void after() throws IOException {
        if(reader!=null) invoke(reader, "destory");
        if(writer!=null) invoke(writer, "destory");
        if(acceptor!=null) invoke(acceptor, "destory");
        if(connector!=null) invoke(connector, "destory");
        if(udpRelay!=null) invoke(udpRelay, "destory");
        if(service!=null) invoke(service, "destory");
        
        if(socket!=null) socket.close();
        if(serverAccept!=null) serverAccept.close();
        if(server!=null) server.close();
    }
    
    @Test(timeout=60000)
    @SuppressWarnings("unchecked")
    public void reload() throws Exception {
        Socks5Config conf1 = new Socks5Config(port);
        Socks5Config conf2 = new Socks5Config(availablePort());
        List<Socks5Config> socks5 = Arrays.asList(conf1, conf2);
        config.setSocks5(socks5);
        invoke(service, "reloadConfig");
        Map<Integer, ?> servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        assertEquals(socks5.size(), servers.size());

        Socks5Config conf3 = new Socks5Config(availablePort());
        socks5 = Arrays.asList(conf3);
        config.setSocks5(socks5);
        invoke(service, "reloadConfig");
        servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        assertEquals(socks5.size(), servers.size());
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void cleanIdle() throws Exception {
        config.setMaxIdleTime(1);
        
        trans_domain_localhost();
        
        Map<Integer, ?> servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);
        assertEquals(1, servers.size());
        
        Object serverMeta = servers.get(port);
        List<?> connections = (List<?>) FieldUtils.readField(serverMeta, "connections", true);
        assertEquals(1, connections.size());
        
        Thread.sleep(10);
        
        service.cleanIdle();
        
        connections = (List<?>) FieldUtils.readField(serverMeta, "connections", true);
        assertEquals(0, connections.size());
    }
    
    @Test
    public void handshake_illegal_ver() throws Exception {
        assertCnns(1);
        
        out.write(0);
        
        assertBs(NoAcc, in);
        
        assertCnns(0);
    }
    @Test
    public void handshake_illegal_nmethods() throws IOException {
        out.write(5); out.write(0);
        
        assertBs(NoAcc, in);
    }
    @Test
    public void handshake_no_noAuth() throws IOException {
        out.write(5); out.write(3); out.write(new byte[]{1,2,3});
        
        assertBs(NoAcc, in);
    }
    
    @Test
    public void target_illegal_ver() throws IOException {
        out.write(new byte[]{5,3,1,2,0});
        out.write(10);
        
        assertBs(Acc, in);
        
        assertBs(Fail, in);
    }
    @Test
    public void target_illegal_cmd() throws IOException {
        out.write(new byte[]{5,3,1,2,0});
        out.write(5); out.write(5);
        
        assertBs(Acc, in);
        
        assertBs(Fail, in);
    }
    @Test
    public void target_illegal_atyp() throws IOException {
        out.write(new byte[]{5,3,1,2,0});
        out.write(5); out.write(1); out.write(0); out.write(2);
        
        assertBs(Acc, in);
        
        assertBs(Fail, in);
    }
    @Test
    public void target_illegal_domainlen() throws IOException {
        out.write(new byte[]{5,3,1,2,0});
        out.write(5); out.write(1); out.write(0); out.write(2);
        out.write(0);
        
        assertBs(Acc, in);
        
        assertBs(Fail, in);
    }
    
    @Test
    public void trans_ipv4() throws IOException {
        out.write(new byte[]{5,10,1,2,3,4,5,6,7,8,9,0});
        out.write(new byte[]{5,1,0,1, 127,0,0,1});
        out.write(Shorts.toByteArray((short)serverPort));
        
        trans();
    }
    @Test
    public void trans_domain_127_0_0_1() throws IOException {
        out.write(new byte[]{5,10,1,2,3,4,5,6,7,8,9,0});
        out.write(new byte[]{5,1,0,3});
        String domain = "127.0.0.1";
        byte[] domainBs = domain.getBytes();
        out.write(domainBs.length);
        out.write(domainBs);
        out.write(Shorts.toByteArray((short)serverPort));
        
        trans();
    }
    @Test
    public void trans_domain_localhost() throws IOException {
        out.write(new byte[]{5,10,1,2,3,4,5,6,7,8,9,0});
        out.write(new byte[]{5,1,0,3});
        String domain = "localhost";
        byte[] domainBs = domain.getBytes();
        out.write(domainBs.length);
        out.write(domainBs);
        out.write(Shorts.toByteArray((short)serverPort));
        
        trans();
    }
    private void trans() throws IOException {
        assertBs(Acc, in);
        assertBs(Suc, in);
        
        byte[] data = rand();
        out.write(data);
        
        serverAccept = server.accept();
        acceptIn = serverAccept.getInputStream();
        assertBs(data, acceptIn);
        
        acceptOut = serverAccept.getOutputStream();
        data = rand();
        acceptOut.write(data);
        assertBs(data, in);
    }
    
    @SuppressWarnings("unchecked")
    private void assertCnns(int expectedSize) throws Exception {
        Thread.sleep(100);

        Map<Integer, ?> servers = (Map<Integer, ?>) FieldUtils.readField(service, "servers", true);

        Object serverMeta = servers.get(port);
        List<?> connections = (List<?>) FieldUtils.readField(serverMeta, "connections", true);
        assertEquals(expectedSize, connections.size());
    }

    // ==================== UDP ASSOCIATE 测试 ====================

    @Test
    public void udp_associate_trans() throws Exception {
        // TCP握手
        out.write(new byte[]{5, 1, 0});
        assertBs(Acc, in);

        // 发送UDP ASSOCIATE请求 (CMD=3, ATYP=1, DST.ADDR=0.0.0.0, DST.PORT=0)
        out.write(new byte[]{5, 3, 0, 1, 0,0,0,0, 0,0});

        // 读取响应 {VER=5, REP=0, RSV=0, ATYP=1, BND.ADDR(4), BND.PORT(2)}
        byte[] resp = new byte[10];
        read(in, resp);
        assertEquals(5, resp[0]);
        assertEquals(0, resp[1]);
        assertEquals(1, resp[3]);
        int relayPort = Shorts.fromBytes(resp[8], resp[9]) & 0xFFFF;

        // 启动UDP目标服务
        int udpServerPort = availablePort();
        DatagramSocket udpServer = new DatagramSocket(udpServerPort);
        udpServer.setSoTimeout(5000);

        // 客户端通过中继发UDP（带SOCKS5 UDP头）
        DatagramSocket udpClient = new DatagramSocket();
        udpClient.setSoTimeout(5000);
        byte[] payload = rand();
        byte[] udpPacket = buildSocks5UdpPacket((byte)1, new byte[]{127,0,0,1}, udpServerPort, payload);
        udpClient.send(new DatagramPacket(udpPacket, udpPacket.length,
            InetAddress.getByName("127.0.0.1"), relayPort));

        // target收到raw payload
        DatagramPacket recvPkt = new DatagramPacket(new byte[65536], 65536);
        udpServer.receive(recvPkt);
        assertArrayEquals(payload, Arrays.copyOf(recvPkt.getData(), recvPkt.getLength()));

        // target回复
        byte[] replyData = rand();
        udpServer.send(new DatagramPacket(replyData, replyData.length,
            recvPkt.getAddress(), recvPkt.getPort()));

        // 客户端收到带SOCKS5 UDP头的回复
        DatagramPacket clientRecv = new DatagramPacket(new byte[65536], 65536);
        udpClient.receive(clientRecv);
        byte[] receivedPayload = parseSocks5UdpPayload(clientRecv.getData(), clientRecv.getLength());
        assertArrayEquals(replyData, receivedPayload);

        udpClient.close();
        udpServer.close();
    }

    @Test
    public void udp_associate_cleanup_on_tcp_close() throws Exception {
        // TCP握手 + UDP ASSOCIATE
        out.write(new byte[]{5, 1, 0});
        assertBs(Acc, in);
        out.write(new byte[]{5, 3, 0, 1, 0,0,0,0, 0,0});

        byte[] resp = new byte[10];
        read(in, resp);
        assertEquals(0, resp[1]);

        assertCnns(1);

        // 关闭TCP控制连接
        socket.close();

        // 验证连接已清理
        assertCnns(0);
    }

    private byte[] buildSocks5UdpPacket(byte atyp, byte[] addr, int port, byte[] data) {
        byte[] packet = new byte[2 + 1 + 1 + addr.length + 2 + data.length];
        int i = 0;
        packet[i++] = 0; packet[i++] = 0; // RSV
        packet[i++] = 0; // FRAG
        packet[i++] = atyp;
        System.arraycopy(addr, 0, packet, i, addr.length); i += addr.length;
        packet[i++] = (byte)(port >> 8);
        packet[i++] = (byte)(port & 0xFF);
        System.arraycopy(data, 0, packet, i, data.length);
        return packet;
    }

    private byte[] parseSocks5UdpPayload(byte[] packet, int length) {
        int offset = 3; // RSV(2) + FRAG(1)
        byte atyp = packet[offset++];
        switch(atyp){
        case 1: offset += 4; break;
        case 3: offset += 1 + (packet[offset] & 0xFF); break;
        case 4: offset += 16; break;
        }
        offset += 2; // PORT
        return Arrays.copyOfRange(packet, offset, length);
    }

}