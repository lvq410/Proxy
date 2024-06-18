package com.lvt4j.socketproxy;

import static java.nio.channels.SelectionKey.OP_ACCEPT;
import static java.util.Collections.synchronizedList;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
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
 * @author LV on 2022年4月3日
 */
@Slf4j
@Service
public class ChannelAcceptor extends Thread implements UncaughtExceptionHandler {

    private Selector selector;
    /** 待注册队列 */
    private List<Runnable> registerQueue = synchronizedList(new LinkedList<>());
    
    @PostConstruct
    public void init() throws IOException {
        init("ChannelAcceptor");
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
            log.error("channel acceptor close err", e);
        }
    }
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if(e instanceof ClosedSelectorException) return;
        log.error("channel acceptor err", e);
    }
    
    public void accept(ServerSocketChannel channel, IOExceptionConsumer<SocketChannel> onAccept, Consumer<Exception> exHandler) {
        registerQueue.add(()->{
            AcceptMeta meta = new AcceptMeta();
            meta.channel = channel;
            meta.onAccept = onAccept;
            meta.exHandler = exHandler;
            
            try{
                meta.channel.register(selector, OP_ACCEPT, meta);
            }catch(Exception e){
                meta.exHandler.accept(e);
            }
        });
        selector.wakeup();
    }
    
    public void waitDeregister(ServerSocketChannel channel) {
        ProxyApp.waitDeregister(selector, channel);
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
                    accept(key);
                }
            }
        }catch(Throwable e){
            uncaughtException(this, e);
        }
    }
    private void accept(SelectionKey key) {
        AcceptMeta meta = (AcceptMeta) key.attachment();
        try{
            if(!key.isAcceptable()) return;
            SocketChannel channel = meta.channel.accept();
            meta.onAccept.accept(channel);
        }catch(Exception e){
            meta.exHandler.accept(e);
        }
    }
    
    private class AcceptMeta {
        private ServerSocketChannel channel;
        
        private IOExceptionConsumer<SocketChannel> onAccept;
        private Consumer<Exception> exHandler;
    }
    
}
