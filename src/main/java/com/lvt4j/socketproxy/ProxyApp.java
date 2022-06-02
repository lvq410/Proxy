package com.lvt4j.socketproxy;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.ApplicationPidFileWriter;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.google.common.net.HostAndPort;

import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author LV on 2022年3月11日
 */
@Slf4j
@EnableScheduling
@SpringBootApplication
public class ProxyApp {

    public static void main(String[] args) throws Throwable {
        SpringApplication app = new SpringApplication(ProxyApp.class);
        app.addListeners(new ApplicationPidFileWriter());
        app.run(args);
    }
    
    public static ServerSocketChannel server(InetAddress host, int port) throws IOException {
        try{
            ServerSocketChannel channel = ServerSocketChannel.open();
            channel.bind(new InetSocketAddress(host, port));
            channel.configureBlocking(false);
            return channel;
        }catch(IOException e){
            throw new IOException(String.format("启动服务端口[%s]失败", port), e);
        }
    }
    public static HostAndPort validHostPort(String hostAndPort) {
        HostAndPort hp;
        try{
            if(StringUtils.isBlank(hostAndPort)) return null;
            hp = HostAndPort.fromString(hostAndPort);
            Validate.isTrue(hp.hasPort(), "缺少端口");
            return hp;
        }catch(Exception e){
            log.error("非法的地址:{}", hostAndPort, e);
            return null;
        }
    }
    public static String format(SocketAddress addr) {
        return StringUtils.strip(addr.toString(), "/");
    }
    public static String port(SocketAddress addr) {
        if(!(addr instanceof InetSocketAddress)) return "unknown";
        return String.valueOf(((InetSocketAddress)addr).getPort());
    }
    public static String format(InetAddress addr) {
        return StringUtils.strip(addr.toString(), "/");
    }
    
    public static void close(Closeable channel){
        if(channel==null) return;
        try{
            channel.close();
        }catch(Exception ig){}
    }
    public static boolean isCloseException(Exception e) {
        if(e instanceof EOFException) return true;
        if(e instanceof ClosedChannelException) return true;
        if(e instanceof AsynchronousCloseException) return true;
        if(e instanceof IOException){
            if(StringUtils.containsAny(e.getMessage(),
                    "Connection reset by peer", "中止了一个已建立的连接")){
                return true;
            }
        }
        return false;
    }
    public static void waitDeregister(Selector selector, AbstractSelectableChannel channel) {
        if(channel==null || selector==null) return;
        if(!channel.isRegistered()) return;
        SelectionKey key = channel.keyFor(selector);
        if(key==null) return;
        selector.wakeup();
        long timeout = 1000;
        long waited = 0;
        long gap = 10;
        while((key = channel.keyFor(selector))!=null && waited<timeout){
            try{
                Thread.sleep(gap);
                waited += gap;
            }catch(InterruptedException e){
                break;
            }
        }
    }
    
    public static interface IOExceptionConsumer<T> {
        void accept(T t) throws IOException;
    }
    public static interface IoExceptionBiConsumer<T,U> {
        void accept(T t, U u) throws IOException;
    }
    public static interface IOExceptionRunnable {
        void run() throws IOException;
    }
}