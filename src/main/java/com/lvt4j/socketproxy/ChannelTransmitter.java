package com.lvt4j.socketproxy;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author LV on 2022年4月2日
 */
@Slf4j
public class ChannelTransmitter extends Thread {

    private Selector selector;
    
    public ChannelTransmitter(String name) throws IOException {
        super(name);
        selector = Selector.open();
        start();
    }
    
    public synchronized void transmit(SocketChannel from, SocketChannel to, int buffSize,
            Runnable onTrans, Consumer<Exception> exHandler) {
        Arrow arrow = new Arrow();
        arrow.from = from;
        arrow.to = to;
        arrow.buf = ByteBuffer.allocate(buffSize);
        arrow.onTrans = onTrans;
        arrow.exHandler = exHandler;
        
        selector.wakeup();
        
        try{
            selector.selectNow(); //防止连续写时报CancelledKey异常
            from.register(selector, OP_READ, arrow);
        }catch(Exception e){
            exHandler.accept(e);
        }
    }
    
    public void run() {
        while(selector.isOpen()){
            try{
                selector.select();
            }catch(Exception e){
                log.error("channel trans select err", e);
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
                    arrow.buf.flip();
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
