package com.lvt4j.socketproxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.util.UUID;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.Test;

/**
 *
 * @author LV on 2022年4月7日
 */
public class BufExample {

    @Test
    public void 多轮读写() {
        ByteBuffer buf = ByteBuffer.allocate(1024); //指定空间生成时，buf为写模式
        
        byte[] data1 = UUID.randomUUID().toString().getBytes();
        
        buf.put(data1);
        
        assertEquals(1024-36, buf.remaining());
        
        buf.flip(); //flip函数为 写 转 读 模式
        
        assertEquals(36, buf.remaining());
        
        byte[] readed = new byte[72];
        readed[0] = buf.get();
        
        assertEquals(35, buf.remaining());
        
        buf.compact(); //compact函数为 读 转 写 模式
        
        assertEquals(1024-35, buf.remaining());
        
        byte[] data2 = UUID.randomUUID().toString().getBytes();
        buf.put(data2);
        
        assertEquals(1024-35-36, buf.remaining());
        
        buf.flip(); //flip函数为 写 转 读 模式
        
        buf.get(readed, 1, 35+36);
        
        assertEquals(0, buf.remaining());
        
        assertArrayEquals(ArrayUtils.addAll(data1, data2), readed);
    }
    
    @Test
    public void buf其实无读写模式概念_clear最能体现() {
        byte[] data1 = UUID.randomUUID().toString().getBytes();
        
        ByteBuffer buf = ByteBuffer.wrap(data1); //由已有数据包装出来的buf 为 读 模式
        assertEquals(0, buf.position());
        assertEquals(data1.length, buf.limit());
        assertEquals(data1.length, buf.capacity());
        assertEquals(data1.length, buf.remaining());
        
        buf.clear();
        
        assertEquals(0, buf.position());
        assertEquals(data1.length, buf.limit());
        assertEquals(data1.length, buf.capacity());
        assertEquals(data1.length, buf.remaining());
        
        //可以视为读模式重新读
        byte[] readed = new byte[data1.length];
        buf.get(readed);
        
        assertArrayEquals(data1, readed);
        
        assertEquals(data1.length, buf.position());
        assertEquals(data1.length, buf.limit());
        assertEquals(data1.length, buf.capacity());
        assertEquals(0, buf.remaining());
        
        //也可以视为写模式重新写
        buf.clear();
        byte[] data2 = UUID.randomUUID().toString().getBytes();
        buf.put(data2);
        
        assertEquals(data1.length, buf.position());
        assertEquals(data1.length, buf.limit());
        assertEquals(data1.length, buf.capacity());
        assertEquals(0, buf.remaining());
        
        assertArrayEquals(data2, buf.array());
        
    }

}