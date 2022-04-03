package com.lvt4j.socketproxy;

import static java.nio.channels.SelectionKey.OP_READ;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.function.Consumer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import com.lvt4j.socketproxy.ProxyApp.IOExceptionConsumer;

import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author LV on 2022年4月2日
 */
@Slf4j
@Service
public class ChannelReader extends Thread {

    private Selector selector;
    
    @PostConstruct
    private void init() throws IOException {
        setName("ChannelReader");
        selector = Selector.open();
        start();
    }
    
    @PreDestroy
    private void destory() {
        try{
            selector.close();
        }catch(Exception e){
            log.error("channel reader close err", e);
        }
    }
    
    public synchronized void readOne(SocketChannel channel, IOExceptionConsumer<Byte> onRead, Consumer<Exception> exHandler) {
        ReadOneMeta meta = new ReadOneMeta();
        meta.channel = channel;
        meta.buf = ByteBuffer.allocate(1);
        meta.onRead = onRead;
        meta.exHandler = exHandler;
        
        read(meta);
    }
    public synchronized void readUntilByte(SocketChannel channel, byte specifyByte
            ,IOExceptionConsumer<byte[]> onRead, Consumer<Exception> exHandler) {
        ReadUntitSpecifyByteMeta meta = new ReadUntitSpecifyByteMeta();
        meta.channel = channel;
        meta.specifyByte = specifyByte;
        meta.buf = ByteBuffer.allocate(1);
        meta.readed = new ByteArrayOutputStream();
        meta.onRead = onRead;
        meta.exHandler = exHandler;
        
        read(meta);
    }
    public synchronized void readUntilLength(SocketChannel channel, int length
            ,IOExceptionConsumer<byte[]> onRead, Consumer<Exception> exHandler) {
        ReadLengthMeta meta = new ReadLengthMeta();
        meta.channel = channel;
        meta.buf = ByteBuffer.allocate(length);
        meta.onRead = onRead;
        meta.exHandler = exHandler;
        
        read(meta);
    }
    private void read(ReadMeta meta) {
        selector.wakeup();
        
        try{
            selector.selectNow(); //连续读时，key.cancel和register会发生在同一线程，不重新执行下select清理下会报cancelledKey异常
            meta.channel.register(selector, OP_READ, meta);
        }catch(Exception e){
            meta.exHandler.accept(e);
        }
    }
    
    @Override
    public void run() {
        while(selector.isOpen()){
            try{
                selector.select();
            }catch(Exception e){
                log.error("channel reader select err", e);
                return;
            }
            if(!selector.isOpen()) return;
            synchronized(this) {
                //等待可能的transmit 注册
            }
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while(keys.hasNext()){
                SelectionKey key = keys.next();
                keys.remove();
                read(key);
            }
        }
    }
    private void read(SelectionKey key) {
        ReadMeta meta = (ReadMeta)key.attachment();
        try{
            if(!key.isReadable()) return;
            int size = meta.channel.read(meta.buf);
            if(size==0) return;
            if(size<0){
                meta.exHandler.accept(new IOException("end-of-stream"));
                return;
            }
            meta.read(key);
        }catch(Exception e){
            meta.exHandler.accept(e);
        }
    }
    
    private abstract class ReadMeta {
        
        protected SocketChannel channel;
        
        protected ByteBuffer buf;
        
        protected Consumer<Exception> exHandler;
        
        protected abstract void read(SelectionKey key) throws IOException;
    }
    
    /** 只读一个字节则结束 */
    private class ReadOneMeta extends ReadMeta {
        private IOExceptionConsumer<Byte> onRead;
        
        @Override
        protected void read(SelectionKey key) throws IOException {
            buf.flip();
            byte b = buf.get();
            key.cancel();
            onRead.accept(b);
        }
    }
    
    /** 读到特定字节则结束 */
    private class ReadUntitSpecifyByteMeta extends ReadMeta {
        protected byte specifyByte;
        
        private ByteArrayOutputStream readed;
        private IOExceptionConsumer<byte[]> onRead;
        
        @Override
        protected void read(SelectionKey key) throws IOException {
            //buf2readed and get last byte
            buf.flip();
            readed.write(buf.array(), 0, buf.limit());
            byte lastByte = buf.array()[buf.limit()-1];
            buf.flip();
            buf.clear();
            
            if(specifyByte!=lastByte) return;
            key.cancel();
            onRead.accept(readed.toByteArray());
        }
    }
    
    /** 读够特定长度，则结束 */
    private class ReadLengthMeta extends ReadMeta {
        private IOExceptionConsumer<byte[]> onRead;
        
        @Override
        protected void read(SelectionKey key) throws IOException {
            if(buf.hasRemaining()) return;
            key.cancel();
            buf.flip();
            onRead.accept(buf.array());
        }
    }
    
}
