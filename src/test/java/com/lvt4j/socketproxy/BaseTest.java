package com.lvt4j.socketproxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import lombok.SneakyThrows;

/**
 *
 * @author LV on 2022年4月3日
 */
public class BaseTest {

    private List<Integer> ports = new LinkedList<>();
    
    @SneakyThrows
    protected int availablePort() {
        List<ServerSocket> tmps = new LinkedList<>();
        try{
            int port;
            do{
                ServerSocket s = new ServerSocket(0);
                tmps.add(s);
                port = s.getLocalPort();
            }while(ports.contains(port));
            ports.add(port);
            return port;
        }finally{
            for(ServerSocket tmp : tmps){ tmp.close(); }
        }
    }
    
    protected void assertBs(byte[] expecteds, InputStream in) throws IOException {
        byte[] actuals = new byte[expecteds.length]; read(in, actuals);
        assertArrayEquals(expecteds, actuals);
    }
    protected void read(InputStream in, byte[] data) throws IOException {
        for(int i=0; i<data.length; i++){
            int d = in.read();
            assertNotEquals(-1, d);
            data[i] = (byte) d;
        }
    }
    
    protected byte[] rand() {
        byte[] data = new byte[Math.abs(ThreadLocalRandom.current().nextInt(1000))+10];
        for(int i=0; i<data.length; i++){
            data[i] = (byte) ThreadLocalRandom.current().nextInt();
        }
        return data;
    }
    
    @SneakyThrows
    protected void invoke(Object bean, String method) {
        Method m = bean.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(bean);
    }
    
}
