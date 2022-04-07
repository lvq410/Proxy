package com.lvt4j.socketproxy;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.lang3.mutable.MutableObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.primitives.Ints;

/**
 *
 * @author LV on 2022年4月3日
 */
public class ChannelWriterTest extends BaseTest {

    private ChannelWriter writer;
    
    private int serverPort;
    private ServerSocket server;
    private Socket serverAccept;
    private InputStream acceptIn;

    private SocketChannel socket;
    
    @Before
    public void before() throws Exception {
        writer = new ChannelWriter(); writer.init();
        
        serverPort = availablePort();
        server = new ServerSocket(serverPort);
        
        socket = SocketChannel.open(new InetSocketAddress("127.0.0.1", serverPort));
        socket.configureBlocking(false);
    }
    
    @After
    public void after() throws IOException {
        if(writer!=null) writer.destory();
        
        if(socket!=null) socket.close();
        if(serverAccept!=null) serverAccept.close();
        if(server!=null) server.close();
    }
    
    @Test(timeout=60000)
    public void write_once() throws Exception {
        byte[] data = rand();
        
        MutableObject<Exception> exRef = new MutableObject<>();
        CountDownLatch latch = new CountDownLatch(1);
        
        writer.write(socket, data, ()->{
            latch.countDown();
        }, e->{
            exRef.setValue(e);
            latch.countDown();
        });
        
        latch.await();
        
        serverAccept = server.accept();
        acceptIn = serverAccept.getInputStream();
        assertBs(data, acceptIn);
    }
    @Test(timeout=60000)
    public void write_twice_by_once() throws Exception {
        byte[] data1 = rand();
        byte[] data2 = rand();
        
        MutableObject<Exception> exRef = new MutableObject<>();
        CountDownLatch latch = new CountDownLatch(2);
        
        writer.write(socket, data1, ()->{
            latch.countDown();
            writer.write(socket, data2, ()->{
                latch.countDown();
            },e->{
                exRef.setValue(e);
                latch.countDown();
            });
        }, e->{
            exRef.setValue(e);
            latch.countDown();latch.countDown();
        });
        
        latch.await();
        
        serverAccept = server.accept();
        acceptIn = serverAccept.getInputStream();
        assertBs(data1, acceptIn);
        assertBs(data2, acceptIn);
    }
    @Test(timeout=60000)
    public void write_continous() throws Exception {
        int count = 100;
        
        List<Thread> writeThreads = new ArrayList<>(count);
        List<byte[]> datas = new ArrayList<>(count);
        List<Integer> lengths = new ArrayList<>(count);
        MutableObject<Exception> exRef = new MutableObject<>();
        for(int i=0; i<count; i++){
            byte[] data = rand();
            byte[] intBs = Ints.toByteArray(data.length);
            byte[] body = new byte[4+data.length];
            System.arraycopy(intBs, 0, body, 0, 4);
            System.arraycopy(data, 0, body, 4, data.length);
            
            lengths.add(data.length);
            datas.add(data);
            
            Thread thread = new Thread(()->{
                writer.write(socket, body, exRef::setValue);
            });
            writeThreads.add(thread);
        }
        Collections.shuffle(writeThreads);
        for(Thread t : writeThreads) t.start();
        for(Thread t : writeThreads) t.join();
        
        serverAccept = server.accept();
        acceptIn = serverAccept.getInputStream();
        
        List<Integer> resLengths = new ArrayList<>(count);
        for(int i=0; i<count; i++){
            byte[] intBs = new byte[4];
            read(acceptIn, intBs);
            int len = Ints.fromByteArray(intBs);
            resLengths.add(len);
            byte[] data = new byte[len];
            read(acceptIn, data);
            
            byte[] srcData = datas.stream().filter(ds->Arrays.equals(ds, data)).findAny().orElse(null);
            assertNotNull(srcData);
            datas.remove(srcData);
        }
        assertNotEquals(lengths, resLengths);
        assertTrue(datas.isEmpty());
    }
}