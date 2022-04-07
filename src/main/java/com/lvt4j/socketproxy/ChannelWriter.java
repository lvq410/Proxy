package com.lvt4j.socketproxy;

import static java.nio.channels.SelectionKey.OP_WRITE;
import static java.util.Collections.synchronizedList;

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

import com.lvt4j.socketproxy.ProxyApp.IOExceptionRunnable;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author LV on 2022年4月2日
 */
@Slf4j
@Service
public class ChannelWriter extends Thread implements UncaughtExceptionHandler {

    private Selector selector;
    
    /** 待注册队列 */
    private List<Runnable> registerQueue = synchronizedList(new LinkedList<>());
    
    @PostConstruct
    public void init() throws IOException {
        init("ChannelWriter");
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
        }catch(Exception e){
            log.error("channel reader close err", e);
        }
    }
    
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if(e instanceof ClosedSelectorException) return;
        log.error("channel writer err", e);
    }
    
    public void write(SocketChannel channel, byte[] data, IOExceptionRunnable onWrite, Consumer<Exception> exHandler) {
        registerQueue.add(()->{
            WriteOnceMeta meta = new WriteOnceMeta();
            meta.channel = channel;
            meta.buf = ByteBuffer.wrap(data);
            meta.onWrite = onWrite;
            meta.exHandler = exHandler;
            
            write(meta);
        });
        selector.wakeup();
    }
    public void write(SocketChannel channel, byte[] data, Consumer<Exception> exHandler) {
        registerQueue.add(()->{
            SelectionKey key = channel.keyFor(selector);
            WriteContinuousMeta meta;
            if(key==null){
                meta = new WriteContinuousMeta();
                meta.channel = channel;
                meta.exHandler = exHandler;
                meta.queue.add(buf(data));
            }else{
                meta = (WriteContinuousMeta) key.attachment();
                
                ByteBuffer buf = meta.queue.peekLast();
                if(buf.capacity()-buf.remaining()>=data.length){ //最后一个buf的剩余空间足够，则复用buf
                    buf.compact();
                    buf.put(data);
                    buf.flip();
                }else{
                    meta.queue.add(buf(data));
                }
            }
            write(meta);
        });
        selector.wakeup();
    }
    private void write(WriteMeta meta) {
        try{
            meta.channel.register(selector, OP_WRITE, meta);
        }catch(Exception e){
            meta.exHandler.accept(e);
        }
    }
    
    @Override @SneakyThrows
    public void run() {
        while(selector.isOpen()){
            selector.select();
            while(!registerQueue.isEmpty()) registerQueue.remove(0).run();
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            if(!selector.isOpen()) return;
            while(keys.hasNext()){
                SelectionKey key = keys.next();
                keys.remove();
                write(key);
            }
        }
    }
    private void write(SelectionKey key) {
        WriteMeta meta = (WriteMeta) key.attachment();
        try{
            if(!key.isWritable()) return;
            meta.write(key);
        }catch(Exception e){
            meta.exHandler.accept(e);
        }
    }
    
    private ByteBuffer buf(byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(Math.max(1024, data.length));
        buf.put(data);
        buf.flip();
        return buf;
    }
    
    private abstract class WriteMeta {
        protected SocketChannel channel;
        
        protected Consumer<Exception> exHandler;
        
        protected abstract void write(SelectionKey key) throws IOException;
    }
    /** 只写一份数据，写完后执行回调函数 */
    private class WriteOnceMeta extends WriteMeta {
        private ByteBuffer buf;
        private IOExceptionRunnable onWrite;
        
        @Override
        protected void write(SelectionKey key) throws IOException {
            channel.write(buf);
            if(buf.hasRemaining()) return;
            key.cancel();
            onWrite.run();
        }
    }
    /** 连续写，无需回调函数 */
    private class WriteContinuousMeta extends WriteMeta {
        private LinkedList<ByteBuffer> queue = new LinkedList<>();
        
        @Override
        protected void write(SelectionKey key) throws IOException {
            Iterator<ByteBuffer> it = queue.iterator();
            while(it.hasNext()){
                ByteBuffer buf = it.next();
                channel.write(buf);
                if(buf.hasRemaining()){
                    return;
                }else{
                    it.remove();
                }
            }
            if(!queue.isEmpty()) return;
            
            key.cancel();
        }
    }
    
}