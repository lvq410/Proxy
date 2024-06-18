package com.lvt4j.socketproxy;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.util.Collections.synchronizedList;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
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
public class ChannelReader extends Thread implements UncaughtExceptionHandler {

    private Selector selector;
    
    /** 待注册队列 */
    private List<Runnable> registerQueue = synchronizedList(new LinkedList<>());
    
    @PostConstruct
    public void init() throws IOException {
        init("ChannelReader");
    }
    public void init(String name) throws IOException {
        setName(name);
        setUncaughtExceptionHandler(this);
        selector = Selector.open();
        start();
    }
    
    @PreDestroy
    public void destory() {
        try{
            selector.close();
            join(1000);
        }catch(Exception e){
            log.error("channel reader close err", e);
        }
    }
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if(e instanceof ClosedSelectorException) return;
        log.error("channel reader err", e);
    }
    
    public void readOne(SocketChannel channel, IOExceptionConsumer<Byte> onRead, Consumer<Exception> exHandler) {
        registerQueue.add(()->{
            ReadOneMeta meta = new ReadOneMeta();
            meta.channel = channel;
            meta.buf = ByteBuffer.allocate(1);
            meta.onRead = onRead;
            meta.exHandler = exHandler;
            
            read(meta);
        });
        selector.wakeup();
    }
    public void readUntilByte(SocketChannel channel, byte specifyByte
            ,IOExceptionConsumer<byte[]> onRead, Consumer<Exception> exHandler) {
        registerQueue.add(()->{
            ReadUntitSpecifyByteMeta meta = new ReadUntitSpecifyByteMeta();
            meta.channel = channel;
            meta.specifyByte = specifyByte;
            meta.buf = ByteBuffer.allocate(1);
            meta.readed = new ByteArrayOutputStream();
            meta.onRead = onRead;
            meta.exHandler = exHandler;
            
            read(meta);
        });
        selector.wakeup();
    }
    public void readUntilLength(SocketChannel channel, int length
            ,IOExceptionConsumer<byte[]> onRead, Consumer<Exception> exHandler) {
        registerQueue.add(()->{
            ReadLengthMeta meta = new ReadLengthMeta();
            meta.channel = channel;
            meta.buf = ByteBuffer.allocate(length);
            meta.onRead = onRead;
            meta.exHandler = exHandler;
            
            read(meta);
        });
        selector.wakeup();
    }
    /**
     * 读任意长度（不能为0且最多不超过max），读到后回调onRead
     * @param channel
     * @param buf 复用的buf，使用前会被clear，因此调用前请确保数据已被使用完
     * @param onRead 该函数处理时应默认buf参数为读模式<br>
     * 　　该buf即为本方法传入的参数buf<br>
     * 　　ChannelReacher不会再使用buf，因此回调函数可以任意处理
     * @param exHandler
     */
    public void readAny(SocketChannel channel, ByteBuffer buf
            ,IOExceptionConsumer<ByteBuffer> onRead, Consumer<Exception> exHandler) {
        registerQueue.add(()->{
            ReadAnyMeta meta = new ReadAnyMeta();
            meta.channel = channel;
            buf.clear();
            meta.buf = buf;
            meta.onRead = onRead;
            meta.exHandler = exHandler;
            
            read(meta);
        });
        selector.wakeup();
    }
    /**
     * 读任意长度（不能为0且最多不超过max），读到后回调onRead
     * @param channel
     * @param bufSize 缓冲大小
     * @param onRead 该函数处理时应默认buf参数为读模式<br>
     * 　　ChannelReacher不会再使用buf，因此回调函数可以任意处理
     * @param exHandler
     * @see #readAny(SocketChannel, ByteBuffer, IOExceptionConsumer, Consumer)
     */
    public void readAny(SocketChannel channel, int bufSize
            ,IOExceptionConsumer<ByteBuffer> onRead, Consumer<Exception> exHandler) {
        readAny(channel, ByteBuffer.allocate(bufSize), onRead, exHandler);
    }
    private void read(ReadMeta meta) {
        try{
            meta.channel.register(selector, OP_READ, meta);
        }catch(Exception e){
            meta.exHandler.accept(e);
        }
    }
    
    @Override
    public void run() {
        try{
            while(selector.isOpen()){
                selector.select();
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while(!registerQueue.isEmpty()) registerQueue.remove(0).run();
                if(!selector.isOpen()) return;
                while(keys.hasNext()){
                    SelectionKey key = keys.next();
                    keys.remove();
                    read(key);
                }
            }
        }catch(Throwable e){
            uncaughtException(this, e);
        }
    }
    private void read(SelectionKey key) {
        ReadMeta meta = (ReadMeta)key.attachment();
        try{
            if(!key.isReadable()) return;
            int size = meta.channel.read(meta.buf);
            if(size==0) return;
            if(size<0){
                meta.exHandler.accept(new EOFException("end-of-stream"));
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
        private byte specifyByte;
        
        private ByteArrayOutputStream readed;
        private IOExceptionConsumer<byte[]> onRead;
        
        @Override
        protected void read(SelectionKey key) throws IOException {
            buf.flip();
            byte b = buf.get();
            readed.write(b);
            buf.clear();
            
            if(specifyByte!=b) return;
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
    
    /** 读任意长度（不能为0），则结束 */
    private class ReadAnyMeta extends ReadMeta {
        private IOExceptionConsumer<ByteBuffer> onRead;
        
        @Override
        protected void read(SelectionKey key) throws IOException {
            key.cancel();
            buf.flip();
            onRead.accept(buf);
        }
    }
    
}