package com.lvt4j.socketproxy;

import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertArrayEquals;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.mutable.MutableObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author LV on 2022年4月6日
 */
public class ChannelReaderTest extends BaseTest {

    private ChannelReader reader;
    
    private int port;
    
    private ServerSocketChannel serverSocket;
    
    private Socket client;
    private byte[] sends;
    
    private SocketChannel server;
    private byte[] receives;

    private Exception ex;
    
    private CountDownLatch latch;
    
    @Before
    public void before() throws Exception {
        port = availablePort();
        
        serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(port));
        
        client = new Socket("127.0.0.1", port);

        server = serverSocket.accept();
        server.configureBlocking(false);
        
        reader = new ChannelReader(); reader.init();
        
        receives = null;
        sends = null;
        ex = null;
    }
    
    @After
    public void after() {
        if(reader!=null) reader.destory();
        
        ProxyApp.close(client);
        ProxyApp.close(server);
        ProxyApp.close(serverSocket);
    }
    
    @Test(timeout=10000)
    public void read_one() throws Exception {
        int count = 100;
        sends = new byte[count];
        ThreadLocalRandom.current().nextBytes(sends);
        client.getOutputStream().write(sends);
        
        AtomicInteger receiveIdx = new AtomicInteger();
        
        receives = new byte[count];
        latch = new CountDownLatch(count);
        
        MutableObject<Runnable> readRef = new MutableObject<>();
        Runnable read = ()->{
            reader.readOne(server, d->{
                receives[receiveIdx.getAndIncrement()]=d;
                latch.countDown();
                readRef.getValue().run();
            }, e->ex=e);
        };
        readRef.setValue(read);
        
        read.run();
        
        latch.await(10, TimeUnit.SECONDS); if(ex!=null) throw ex;
        
        assertArrayEquals(sends, receives);
    }
    @Test(timeout=10000)
    public void read_until_byte() throws Exception {
        int count = 100;
        String rawData = IntStream.range(0, count).mapToObj(i->UUID.randomUUID().toString()).collect(joining("\n"));
        rawData += '\n';
        sends = rawData.getBytes();
        client.getOutputStream().write(sends);
        
        receives = new byte[0];
        latch = new CountDownLatch(count);
        
        MutableObject<Runnable> readRef = new MutableObject<>();
        Runnable read = ()->{
            reader.readUntilByte(server, (byte)'\n', d->{
                receives = ArrayUtils.addAll(receives, d);
                latch.countDown();
                readRef.getValue().run();
            }, e->ex=e);
        };
        readRef.setValue(read);
        
        read.run();
        
        latch.await(10, TimeUnit.SECONDS); if(ex!=null) throw ex;
        
        assertArrayEquals(sends, receives);
    }
    @Test(timeout=10000)
    public void read_length() throws Exception {
        int count = 100;
        List<Integer> sendLengths = new LinkedList<>();
        for(int i=0; i<count; i++){
            byte[] data = rand();
            sendLengths.add(data.length);
            sends = ArrayUtils.addAll(sends, data);
            client.getOutputStream().write(data);
        }
        
        List<Integer> shouldReceiveLengths = new LinkedList<>(sendLengths);
        
        latch = new CountDownLatch(count);
        
        MutableObject<Runnable> readRef = new MutableObject<>();
        Runnable read = ()->{
            reader.readUntilLength(server, shouldReceiveLengths.remove(0), d->{
                receives = ArrayUtils.addAll(receives, d);
                latch.countDown();
                readRef.getValue().run();
            }, e->ex=e);
        };
        readRef.setValue(read);
        
        read.run();
        
        latch.await(10, TimeUnit.SECONDS); if(ex!=null) throw ex;
        
        assertArrayEquals(sends, receives);
    }
    @Test(timeout=10000)
    public void read_any() throws Exception {
        int count = 100;
        for(int i=0; i<count; i++){
            byte[] data = rand();
            sends = ArrayUtils.addAll(sends, data);
            client.getOutputStream().write(data);
        }
        
        latch = new CountDownLatch(1);
        
        MutableObject<Runnable> readRef = new MutableObject<>();
        Runnable read = ()->{
            reader.readAny(server, 1024, d->{
                byte[] data = new byte[d.remaining()];
                System.arraycopy(d.array(), 0, data, 0, d.remaining());
                receives = ArrayUtils.addAll(receives, data);
                if(receives.length==sends.length){
                    latch.countDown();
                }else{
                    readRef.getValue().run();
                }
            }, e->ex=e);
        };
        readRef.setValue(read);
        
        read.run();
        
        latch.await(10, TimeUnit.SECONDS); if(ex!=null) throw ex;
        
        assertArrayEquals(sends, receives);
    }
    
}
