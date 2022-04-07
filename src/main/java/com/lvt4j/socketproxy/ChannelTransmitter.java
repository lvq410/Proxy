package com.lvt4j.socketproxy;

import static java.nio.channels.SelectionKey.OP_READ;
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

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author LV on 2022年4月2日
 */
@Slf4j
public class ChannelTransmitter extends Thread implements UncaughtExceptionHandler {

    private Selector selector;
    
    /** 待注册队列 */
    private List<Runnable> registerQueue = synchronizedList(new LinkedList<>());
    
    public ChannelTransmitter(String name) throws IOException {
        super(name);
        setDefaultUncaughtExceptionHandler(this);
        selector = Selector.open();
        start();
    }
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if(e instanceof ClosedSelectorException) return;
        log.error("channel trans err", e);
    }
    
    public void transmit(SocketChannel from, SocketChannel to, int buffSize,
            Runnable onTrans, Consumer<Exception> exHandler) {
        registerQueue.add(()->{
            Arrow arrow = new Arrow();
            arrow.from = from;
            arrow.to = to;
            arrow.buf = ByteBuffer.allocate(buffSize);
            arrow.onTrans = onTrans;
            arrow.exHandler = exHandler;
            
            try{
                from.register(selector, OP_READ, arrow);
            }catch(Exception e){
                exHandler.accept(e);
            }
        });
        selector.wakeup();
    }
    
    @Override @SneakyThrows
    public void run() {
        while(selector.isOpen()){
            selector.select();
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while(!registerQueue.isEmpty()) registerQueue.remove(0).run();
            if(!selector.isOpen()) return;
            while(keys.hasNext()){
                SelectionKey key = keys.next();
                keys.remove();
                trans(key);
            }
        }
    }
    private void trans(SelectionKey key) {
        Arrow arrow = (Arrow) key.attachment();
        try{
            if(key.isReadable()){
                int size = arrow.from.read(arrow.buf);
                if(size>0){
                    arrow.onTrans.run();
                    arrow.buf.flip();
                    key.cancel();
                    arrow.to.register(selector, OP_WRITE, arrow);
                }
            }else if(key.isWritable()){
                int size = arrow.to.write(arrow.buf);
                if(size>0){
                    arrow.onTrans.run();
                }
                if(!arrow.buf.hasRemaining()){
                    arrow.buf.clear();
                    key.cancel();
                    arrow.from.register(selector, OP_READ, arrow);
                }
            }
        }catch(Exception e){
            arrow.exHandler.accept(e);
        }
    }
    
    public void destory() {
        try{
            selector.close();
        }catch(Exception e){
            log.error("channel trans close err", e);
        }
    }
    
    private class Arrow {
        private SocketChannel from;
        private SocketChannel to;
        private ByteBuffer buf;
        private Runnable onTrans;
        private Consumer<Exception> exHandler;
    }
}