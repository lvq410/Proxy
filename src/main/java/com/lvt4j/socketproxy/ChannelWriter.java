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

import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Service;

import com.lvt4j.socketproxy.ProxyApp.IOExceptionRunnable;

import lombok.NonNull;
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
            join(1000);
        }catch(Exception e){
            log.error("channel reader close err", e);
        }
    }
    
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if(e instanceof ClosedSelectorException) return;
        log.error("channel writer err", e);
    }
    
    /**
     * @see #write(SocketChannel, ByteBuffer, IOExceptionRunnable, Consumer)
     */
    public void write(SocketChannel channel, byte[] data, IOExceptionRunnable onWrite, Consumer<Exception> exHandler) {
        write(channel, ByteBuffer.wrap(data), onWrite, exHandler);
    }
    /**
     * @see #write(SocketChannel, ByteBuffer, IOExceptionRunnable, Consumer)
     */
    public void write(SocketChannel channel, byte[] data, Consumer<Exception> exHandler) {
        write(channel, data, null, exHandler);
    }
    /**
     * @see #write(SocketChannel, ByteBuffer, IOExceptionRunnable, Consumer)
     */
    public void write(SocketChannel channel, ByteBuffer data, Consumer<Exception> exHandler) {
        write(channel, data, null, exHandler);
    }
    /**
     * 将一份数据插入待写入队列，onWrite为其对应写入完成后的回调函数，遇到异常exHandler会被调用
     * 如果同一channel多次调用，每次调用增加的待写入数据data被写入完后，其对应的onWrite都会被调用
     * 但异常回调函数exHandler会被覆盖成最新的
     * @param channel 
     * @param data 待写入数据，不能为空
     * @param onWrite 对应写入数据的回调函数，可为null
     * @param exHandler 异常回调，不能为null
     */
    public void write(@NonNull SocketChannel channel, @NonNull ByteBuffer data, IOExceptionRunnable onWrite, @NonNull Consumer<Exception> exHandler) {
        registerQueue.add(()->{
            SelectionKey key = channel.keyFor(selector);
            WriteMeta meta;
            if(key==null){
                meta = new WriteMeta();
                meta.channel = channel;
                meta.exHandler = exHandler;
                meta.queue.add(Pair.of(data, onWrite));
            }else{
                meta = (WriteMeta) key.attachment();
                meta.exHandler = exHandler;
                
                meta.queue.add(Pair.of(data, onWrite));
            }
            try{
                meta.channel.register(selector, OP_WRITE, meta);
            }catch(Exception e){
                meta.exHandler.accept(e);
            }
        });
        selector.wakeup();
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
    
    /**
     * {@link ChannelWriter#selector}上{@link #channel}注册时绑定对象
     * 记录了待写入数据和对应的回调函数
     * 以及异常处理函数
     * @author LV on 2024年1月25日
     */
    private class WriteMeta {
        private SocketChannel channel;
        private Consumer<Exception> exHandler;
        
        /** 待写入数据及对应的回调函数 */
        private LinkedList<Pair<ByteBuffer, IOExceptionRunnable>> queue = new LinkedList<>();
        
        public void write(SelectionKey key) throws IOException {
            Iterator<Pair<ByteBuffer, IOExceptionRunnable>> it = queue.iterator();
            while(it.hasNext()){
                Pair<ByteBuffer, IOExceptionRunnable> pair = it.next();
                channel.write(pair.getLeft());
                if(pair.getLeft().hasRemaining()){
                    return;
                }else{
                    it.remove();
                    if(pair.getRight()!=null) pair.getRight().run();
                }
            }
            if(!queue.isEmpty()) return;
            
            key.cancel();
        }
    }
    
}