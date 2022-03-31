package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.SocketProxyApp.format;
import static org.mockito.Mockito.atMost;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Service;

import com.google.common.primitives.Longs;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author LV on 2022年3月28日
 */
@Slf4j
@Service
public class IntranetService implements InfoContributor {
    
    @Autowired
    private Config config;
    
    @Override
    public void contribute(Builder builder) {}

    private class EntryThread extends Thread {
        
        private final int port;
        private final int relayListernPort;
        
        private final ServerSocket server;
        private final ServerSocket relayServer;
        
        private RelayListener relayListener;
        private Socket relay;
        private Heartbeater relayHeartbeater;
        
        public EntryThread(int port, int relayListernPort) throws IOException {
            this.port = port;
            this.relayListernPort = relayListernPort;
            try{
                server = new ServerSocket(port);
            }catch(IOException e){
                throw new IOException(String.format("启动服务端口[%s]失败", port), e);
            }
            try{
                relayServer = new ServerSocket(relayListernPort);
            }catch(IOException e){
                throw new IOException(String.format("启动服务端口[%s]失败", relayListernPort), e);
            }
            relayListener = new RelayListener();
            setName(port+" server");
            start();
            relayHeartbeater = new Heartbeater();
            log.info("{} intranet entry start", port);
        }
        
        private void disconnectRelay() {
            if(relay==null) return;
            try{
                if(!relay.isClosed()) relay.close();
            }catch(Exception e){
                log.warn("{} relay close err", port, e);
            }
            relay = null;
        }
        private synchronized void heartbeat() {
            if(relay==null) return;
            try{
                OutputStream out = relay.getOutputStream();
                out.write(MsgType.HeartBeat);
            }catch(Exception e){
                if(e.getMessage().contains("Socket closed")) return;
                log.error("{} heartbeat err", getName() ,e);
            }
        }
        
        class RelayListener extends Thread {
            public RelayListener() {
                super(port+" relay listener");
                start();
            }
            @Override
            public void run() {
                while(!relayServer.isClosed()){
                    Socket relay;
                    try{
                        relay = relayServer.accept();
                    }catch(Exception e){
                        if((e instanceof SocketException) && StringUtils.containsAny(e.getMessage(), "Socket closed", "Socket is closed", "socket closed")){
                            return;
                        }
                        log.error("{} server relay listerner err", port, e);
                        continue;
                    }
                    disconnectRelay();
                    EntryThread.this.relay = relay;
                }
            }
        }
        class Heartbeater extends Thread {
            public Heartbeater() {
                super(port+" relay heartbeater");
                start();
            }
            @Override
            public void run() {
                while(true){
                    heartbeat();
                    try{
                        Thread.sleep(1000);
                    }catch(InterruptedException e){ //只有停服才中断
                        return;
                    }
                }
            }
        }
        
    }
    
    private class RelayThread extends Thread {
        private final InetSocketAddress entryConfig;
        private final Socket entry;
        private final InetSocketAddress targetConfig;
        
        public RelayThread(InetSocketAddress entryConfig, InetSocketAddress targetConfig) throws IOException {
            try{
                entry = new Socket(entryConfig.getAddress(), entryConfig.getPort());
            }catch(IOException e){
                throw new IOException(String.format("连接入口服务[%s]失败", format(entryConfig)), e);
            }
        }
        @Override @SneakyThrows
        public void run() {
            InputStream in = entry.getInputStream();
            int msgType = 0;
            while((msgType=in.read())!=-1){
                switch(msgType){
                case MsgType.HeartBeat:
                    break;
                case MsgType.Transmit:
                    byte[] buff = new byte[4];
                    in.read(buff);
                    int cnnId = 
                    
                default:
                    break;
                }
            }
            entry.getInputStream();
        }
        
        
    }
    
//    @RequiredArgsConstructor
//    private class HeartBeater extends Thread {
//        private final OutputStream out;
//        
//        @Override
//        public void run() {
//            while(true){
//                try{
//                    out.write(MsgType.HeartBeat);
//                }catch(Exception e){
//                    if(e.getMessage().contains("Socket closed")) return;
//                    log.error("{} heartbeat err", getName() ,e);
//                }
//                try{
//                    Thread.sleep(1000);
//                }catch(InterruptedException e){ //只有停服才中断
//                    return;
//                }
//            }
//        }
//    }
    
    /**
     * 入口服务与转发服务之间的通信协议
     *
     * @author LV on 2022年3月28日
     */
    private static class MsgType {
        /**
         * 心跳
         * 1s一次，整个消息仅一个字节
         */
        private static final int HeartBeat = 0;
        
        /**
         * 转发包
         * 整个消息由以下构成
         * 消息类型(1字节)：固定值1
         * 连接编号(4字节int)：入口服务 会为 客户端的每个请求分配一个编号
         * 内容长度(4字节int)：指示传输的内容长度
         * 内容(由内容长度确定)
         */
        private static final int Transmit = 1;
        
        /**
         * 如果消息转发失败，会响应本类型消息
         * 整个消息由以下构成
         * 消息类型(1字节)：固定值2
         * 连接编号(2字节)：入口服务 会为 客户端的每个请求分配一个编号
         */
        private static final int TransErr = 2;
        
    }
    
}
