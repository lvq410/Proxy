package com.lvt4j.socketproxy;

import static java.nio.channels.SelectionKey.OP_CONNECT;

import java.io.IOException;
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
public class ChannelConnector extends Thread {

    private Selector selector;
    
    @PostConstruct
    private void init() throws IOException {
        setName("ChannelConnector");
        selector = Selector.open();
        start();
    }
    
    public synchronized void connectListen(SocketChannel channel, IOExceptionRunnable onConnect, Consumer<Exception> exHandler) {
        ConnectMeta cnn = new ConnectMeta();
        cnn.onConnect = onConnect;
        cnn.exHandler = exHandler;
        
        selector.wakeup();
        
        try{
            channel.register(selector, OP_CONNECT, cnn);
        }catch(Exception e){
            exHandler.accept(e);
        }
    }
    
    @Override
    public void run() {
        while(selector.isOpen()){
            try{
                selector.select();
            }catch(Exception e){
                log.error("channel connector select err", e);
                return;
            }
            if(!selector.isOpen()) return;
            synchronized(this) {
                //等待可能的注册
            }
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while(keys.hasNext()){
                SelectionKey key = keys.next();
                keys.remove();
                connect(key);
                key.cancel();
            }
        }
    }
    private void connect(SelectionKey key) {
        ConnectMeta cnn = (ConnectMeta) key.attachment();
        try{
            if(key.isConnectable()){
                SocketChannel channel = (SocketChannel)key.channel();
                channel.finishConnect();
                cnn.onConnect.run();
            }
        }catch(Exception e){
            cnn.exHandler.accept(e);
        }
    }
    
    @PreDestroy
    private void destory() {
        try{
            selector.close();
        }catch(Exception e){
            log.error("channel connector close err", e);
        }
    }
    
    private class ConnectMeta {
        private IOExceptionRunnable onConnect;
        private Consumer<Exception> exHandler;
    }
    
}