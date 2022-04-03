package com.lvt4j.socketproxy;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.util.concurrent.ThreadLocalRandom;

import lombok.SneakyThrows;

/**
 *
 * @author LV on 2022年4月3日
 */
public class BaseTest {

    @SneakyThrows
    protected int availablePort() {
        ServerSocket s = new ServerSocket(0);
        int port = s.getLocalPort();
        s.close();
        return port;
    }
    
    protected void assertBs(byte[] expected, InputStream in) throws IOException {
        byte[] actual = new byte[expected.length]; in.read(actual);
        
        assertEquals(expected.length, actual.length);
        for(int i=0; i<expected.length; i++){
            assertEquals(expected[0], actual[0]);
        }
    }
    
    protected byte[] rand() {
        byte[] data = new byte[Math.abs(ThreadLocalRandom.current().nextInt(1014))+10];
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
