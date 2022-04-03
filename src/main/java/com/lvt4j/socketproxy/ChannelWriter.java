package com.lvt4j.socketproxy;

import static java.nio.channels.SelectionKey.OP_WRITE;

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

import com.lvt4j.socketproxy.ProxyApp.IOExceptionRunnable;

import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author LV on 2022年4月2日
 */
@Slf4j
@Service
public class ChannelWriter extends Thread {

    private Selector selector;
    
    @PostConstruct
    private void init() throws IOException {
        setName("ChannelWriter");
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
    
    public synchronized void write(SocketChannel channel, byte[] data, IOExceptionRunnable onWrite, Consumer<Exception> exHandler) {
        WriteMeta meta = new WriteMeta();
        meta.channel = channel;
        meta.buf = ByteBuffer.wrap(data);
        meta.onWrite = onWrite;
        meta.exHandler = exHandler;
        
        selector.wakeup();
        
        try{
            meta.channel.register(selector, OP_WRITE, meta);
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
                write(key);
            }
        }
    }
    private void write(SelectionKey key) {
        WriteMeta meta = (WriteMeta) key.attachment();
        try{
            if(key.isWritable()){
                meta.channel.write(meta.buf);
                if(!meta.buf.hasRemaining()){
                    key.cancel();
                    meta.onWrite.run();
                }
            }
        }catch(Exception e){
            meta.exHandler.accept(e);
        }
    }
    
    private class WriteMeta {
        private SocketChannel channel;
        private ByteBuffer buf;
        private IOExceptionRunnable onWrite;
        private Consumer<Exception> exHandler;
    }
    
}
