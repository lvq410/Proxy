package com.lvt4j.socketproxy;

import static java.lang.String.format;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.function.Consumer;

import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import com.google.common.primitives.Shorts;
import com.lvt4j.socketproxy.ProxyApp.IOExceptionConsumer;
import com.lvt4j.socketproxy.ProxyApp.IoExceptionBiConsumer;

import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author LV on 2022年4月15日
 */
@Slf4j
@Service
public class ProtocolService {

    public static class Socks5 {
        public static final byte NoAuth = 0;
        
        public static final byte[] NoAcc = {5, -1};
        public static final byte[] Acc = {5, 0};
        
        public static final byte[] Fail = {5,1,0,1, 0,0,0,0, 0,0};
        public static final byte[] Suc = {5,0,0,1, 0,0,0,0, 0,0};
    }
    public static class Http {
        public static final byte LineFeed = '\n';
        public static final byte RetChar = '\r';
        
        public static final byte[] EstablishedHeaders = "HTTP/1.0 200 Connection established\r\nProxy-Agent: lvt4j-SocketProxy/1.0\r\n\r\n".getBytes();
    }
    
    @Autowired
    private ChannelReader reader;
    @Autowired
    private ChannelWriter writer;
    @Autowired
    private ChannelConnector connector;
    
    public void client_connect(Protocol protocol, HostAndPort serverConfig, HostAndPort targetConfig,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler) {
        switch(protocol){
        case Socks5:
            socks5_client_connect(serverConfig, targetConfig, onConnect, exHandler);
            break;
        case Http:
            http_client_connect(serverConfig, targetConfig, onConnect, exHandler);
            break;
        default:
            break;
        }
    }
    
    public void socks5_client_connect(HostAndPort serverConfig, HostAndPort targetConfig,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler) {
        SocketChannel server;
        try{
            server = SocketChannel.open();
        }catch(Exception e){
            exHandler.accept(new IOException(format("打开服务套接字失败 : %s", serverConfig), e));
            return;
        }
        Consumer<Exception> closeExHandler = e->{
            ProxyApp.close(server);
            exHandler.accept(e);
        };
        try{
            server.configureBlocking(false);
            server.connect(new InetSocketAddress(serverConfig.getHostText(), serverConfig.getPort()));
        }catch(Exception e){
            closeExHandler.accept(new IOException(format("连接服务套接字失败 : %s", serverConfig), e));
            return;
        }
        connector.connect(server, ()->socks5_client_handshake(server, targetConfig, onConnect, closeExHandler), closeExHandler);
    }
    private void socks5_client_handshake(SocketChannel server, HostAndPort targetConfig,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler){
        byte[] handshake = {5, 1, 0};
        writer.write(server, handshake, ()->{
            reader.readOne(server, ver->{
                if(ver!=5){ //仅支持socket版本5
                    exHandler.accept(new IOException(format("server no acceptable socks5 ver : %s", ver)));
                    return;
                }
                reader.readOne(server, method->{
                    if(method!=0){ //仅支持无认证模式
                        exHandler.accept(new IOException(format("we only accept no auth but : %s", method)));
                        return;
                    }
                    socks5_client_target(server, targetConfig, onConnect, exHandler);
                }, exHandler);
            }, exHandler);
        }, exHandler);
    }
    private void socks5_client_target(SocketChannel server, HostAndPort targetConfig,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler) {
        byte[] packet = {5, 1, 0};
        if(InetAddresses.isInetAddress(targetConfig.getHostText())){
            InetAddress targetAddr = InetAddresses.forUriString(targetConfig.getHostText());
            if(targetAddr instanceof Inet4Address){
                packet = ArrayUtils.add(packet, (byte)1);
            }else{
                packet = ArrayUtils.add(packet, (byte)4);
            }
            packet = ArrayUtils.addAll(packet, targetAddr.getAddress());
        }else{
            packet = ArrayUtils.add(packet, (byte)3);
            packet = ArrayUtils.add(packet, (byte)targetConfig.getHostText().getBytes().length);
            packet = ArrayUtils.addAll(packet, targetConfig.getHostText().getBytes());
        }
        packet = ArrayUtils.addAll(packet, Shorts.toByteArray((short)targetConfig.getPort()));
        writer.write(server, packet, ()->{
            reader.readOne(server, ver->{
                if(ver!=5){ //仅支持socket版本5
                    exHandler.accept(new IOException(format("server no acceptable socks5 ver : %s", ver)));
                    return;
                }
                reader.readOne(server, rep->{
                    if(rep!=0){
                        exHandler.accept(new IOException(format("server response not success : %s", rep)));
                        return;
                    }
                    reader.readOne(server, rst->{
                        reader.readOne(server, atyp->{
                            switch(atyp){
                            case 1: //ipv4
                                reader.readUntilLength(server, 4, addr->{
                                    reader.readUntilLength(server, 2, port->onConnect.accept(server), exHandler);
                                }, exHandler);
                                break;
                            case 3: //域名
                                reader.readOne(server, len->{
                                    reader.readUntilLength(server, Byte.toUnsignedInt(len), addr->{
                                        reader.readUntilLength(server, 2, port->onConnect.accept(server), exHandler);
                                    }, exHandler);
                                }, exHandler);
                                break;
                            case 4: //ipv6
                                reader.readUntilLength(server, 16, addr->{
                                    reader.readUntilLength(server, 2, port->onConnect.accept(server), exHandler);
                                }, exHandler);
                                break;
                            default:
                                exHandler.accept(new IOException(format("no acceptable server addr type : %s", atyp)));
                                return;
                            }
                        }, exHandler);
                    }, exHandler);
                }, exHandler);
            }, exHandler);
        }, exHandler);
    }
    
    /**
     * 代理服务器接收客户端连接
     * @param client accept的客户端
     * @param onConnect 协议通讯成功，并与目标服务建立完连接后的回调，参数为目标服务
     * @param exHandler 异常处理
     */
    public void socks5_server_connect(SocketChannel client,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler) {
        socks5_server_handshake(client, onConnect, exHandler);
    }
    /**
     * 握手阶段：获取认证方法 并 响应是否进入认证阶段，或无需认证跳过认证阶段
     * <pre>
     * 接受的三个参数分别是
     * ver(1字节):         socket版本
     * nmethods(1字节):     认证方法字段的字节数
     * methods(1至255字节): 认证方法，数组格式，一个字节为表示一个客户端支持的方法，nmethods的值为多少，methods就有多少个字节。可能有的值：
     *  0x00 ： NO AUTHENTICATION REQUIRED                       无身份验证
     *  0x01 ： GSSAPI                                         未知
     *  0x02 ： USERNAME/PASSWORD                               用户名/密码
     *  0x03 ： to X’7F’ IANA ASSIGNED
     *  0x80 ： to X’FE’ RESERVED FOR PRIVATE METHODS
     *  0xFF ： NO ACCEPTABLE METHODS                           无可用方法
     *  
     * 响应为选中一个METHOD返回给客户端，格式如下
     *  ver(1字节):         socket版本
     *  method(1字节):      选择使用哪种认证方法
     *  当客户端收到0x00时，会跳过认证阶段直接进入请求阶段
     *  当收到0xFF时，直接断开连接
     *  其他的值进入到对应的认证阶段
     *  </pre>
     * @param client
     * @param onConnect
     * @param exHandler
     */
    private void socks5_server_handshake(SocketChannel client,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler) {
        reader.readOne(client, ver->{
            if(ver!=5){ //仅支持socket版本5
                writer.write(client, Socks5.NoAcc, ()->exHandler.accept(new IOException(format("no acceptable socket ver : %s", ver))), exHandler);
                return;
            }
            reader.readOne(client, nmethods->{
                if(nmethods==0){ //客户端不支持任何认证方法
                    writer.write(client, Socks5.NoAcc, ()->exHandler.accept(new IOException(format("no acceptable nmethods : %s", nmethods))), exHandler);
                    return;
                }
                reader.readUntilLength(client, Byte.toUnsignedInt(nmethods), methods->{
                    if(!ArrayUtils.contains(methods, Socks5.NoAuth)){ //目前仅支持无身份验证，但客户端不支持无身份验证
                        writer.write(client, Socks5.NoAcc, ()->exHandler.accept(new IOException(format("only accept no auth but : %s", Arrays.toString(methods)))), exHandler);
                        return;
                    }
                    writer.write(client, Socks5.Acc, ()->socks5_server_read_target(client, onConnect, exHandler), exHandler);
                }, exHandler);
            }, exHandler);
        }, exHandler);
    }
    /**
     * 请求阶段：获取要建立连接的目标的地址&协议&端口
     * <pre>
     * 客户端传输过来的参数
     * ver(1字节):         socket版本
     * cmd(1字节):         请求类型，可选值
     *  0x01 ： CONNECT请求
     *  0x02 ： BIND请求
     *  0x03 ： UDP转发
     * rsv(1字节):         保留字段，固定值为0x00
     * ATYP(1字节):        目标地址类型，可选值
     *  0x01 ： IPv4地址，DST.ADDR为4个字节
     *  0x03 ： 域名，DST.ADDR是一个可变长度的域名
     *  0x04 ： IPv6地址，DST.ADDR为16个字节长度
     * DST.ADDR(不定):      目标地址
     * DST.PORT(2字节):     目标端口
     * 
     * 服务端响应
     * ver(1字节):         socket版本
     * rep(1字节):         响应码，可选值
     *  0x00 ： succeeded
     *  0x01 ： general SOCKS server failure
     *  0x02 ： connection not allowed by ruleset
     *  0x03 ： Network unreachable
     *  0x04 ： Host unreachable
     *  0x05 ： Connection refused
     *  0x06 ： TTL expired
     *  0x07 ： Command not supported
     *  0x08 ： Address type not supported
     *  0x09 ： to X’FF’ unassigned
     * RSV(1字节):         保留字段，固定值为0x00
     * ATYPE(1字节):       服务绑定的地址类型，可选值同请求的ATYP
     * BND.ADDR 服务绑定的地址
     * BND.PORT 服务绑定的端口DST.PORT
     * </pre>
     */
    private void socks5_server_read_target(SocketChannel client,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler) {
        reader.readOne(client, ver->{
            if(ver!=5){ //仅支持socket版本5
                writer.write(client, Socks5.Fail, ()->exHandler.accept(new IOException(format("no acceptable socket ver : %s", ver))), exHandler);
                return;
            }
            reader.readOne(client, cmd->{
                if(cmd!=1){ //仅支持CONNECT请求
                    writer.write(client, Socks5.Fail, ()->exHandler.accept(new IOException(format("no acceptable cmd : %s", cmd))), exHandler);
                    return;
                }
                reader.readOne(client, rsv->{
                    reader.readOne(client, atyp->{
                        switch(atyp){
                        case 1: //ipv4
                            reader.readUntilLength(client, 4, ipv4->{
                                InetAddress host = Inet4Address.getByAddress(ipv4);
                                socks5_server_read_target_port(client, host, onConnect, exHandler);
                            }, exHandler);
                            break;
                        case 3: //域名
                            reader.readOne(client, len->{
                                if(len==0){
                                    writer.write(client, Socks5.Fail, ()->exHandler.accept(new IOException(format("no acceptable domain len : %s", len))), exHandler);
                                    return;
                                }
                                reader.readUntilLength(client, Byte.toUnsignedInt(len), domainBs->{
                                    String domain = new String(domainBs);
                                    InetAddress host = InetAddress.getByName(domain);
                                    socks5_server_read_target_port(client, host, onConnect, exHandler);
                                }, exHandler);
                            }, exHandler);
                            break;
                        case 4: //ipv6
                            reader.readUntilLength(client, 16, ipv6->{
                                InetAddress host = Inet6Address.getByAddress(ipv6);
                                socks5_server_read_target_port(client, host, onConnect, exHandler);
                            }, exHandler);
                            break;
                        default:
                            writer.write(client, Socks5.Fail, ()->exHandler.accept(new IOException(format("no acceptable addr type : %s", atyp))), exHandler);
                            return;
                        }
                    }, exHandler);
                }, exHandler);
            }, exHandler);
        }, exHandler);
    }
    private void socks5_server_read_target_port(SocketChannel client, InetAddress host,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler) {
        reader.readUntilLength(client, 2, portBs->{
            int port = Short.toUnsignedInt(Shorts.fromByteArray(portBs));
            socks5_server_target_connect(client, host, port, onConnect, exHandler);
        }, exHandler);
    }
    private void socks5_server_target_connect(SocketChannel client, InetAddress host, int port,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler) {
        Consumer<Exception> responseFail2ClientThenExHandler = e->{
            writer.write(client, Socks5.Fail, ()->{
                exHandler.accept(e);
            }, we->{
                log.error("write socks5 fail msg to client error", we);
                exHandler.accept(e);
            });
        };
        
        SocketChannel target;
        try{
            target = SocketChannel.open();
        }catch(Exception e){
            responseFail2ClientThenExHandler.accept(new IOException(format("打开目标套接字失败 : %s:%s", host, port), e));
            return;
        }
        Consumer<Exception> responseFail2ClientThenCloseThenExHandler = e->{
            writer.write(client, Socks5.Fail, ()->{
                ProxyApp.close(target);
                exHandler.accept(e);
            }, we->{
                ProxyApp.close(target);
                log.error("write socks5 fail msg to client error", we);
                exHandler.accept(e);
            });
        };
        try{
            target.configureBlocking(false);
            target.connect(new InetSocketAddress(host, port));
        }catch(Exception e){
            responseFail2ClientThenCloseThenExHandler.accept(new IOException(format("连接目标套接字失败 : %s:%s", host, port), e));
            return;
        }
        connector.connect(target, ()->{
            writer.write(client, Socks5.Suc, ()->{
                onConnect.accept(target);
            }, responseFail2ClientThenCloseThenExHandler);
        }, responseFail2ClientThenCloseThenExHandler);
    }
    
    public void http_client_connect(HostAndPort serverConfig, HostAndPort targetConfig,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler){
        SocketChannel server;
        try{
            server = SocketChannel.open();
        }catch(Exception e){
            exHandler.accept(new IOException(format("打开服务套接字失败 : %s", serverConfig), e));
            return;
        }
        Consumer<Exception> closeExHandler = e->{
            ProxyApp.close(server);
            exHandler.accept(e);
        };
        try{
            server.configureBlocking(false);
            server.connect(new InetSocketAddress(serverConfig.getHostText(), serverConfig.getPort()));
        }catch(Exception e){
            closeExHandler.accept(new IOException(format("连接服务套接字失败 : %s", serverConfig), e));
            return;
        }
        connector.connect(server, ()->http_client_handshake(server, serverConfig, targetConfig, onConnect, closeExHandler), closeExHandler);
    }
    private void http_client_handshake(SocketChannel server, HostAndPort serverConfig, HostAndPort targetConfig,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler) {
        String statusLine = "CONNECT "+targetConfig+" HTTP/1.0\r\n";
        String hostHeader = "Host: "+serverConfig+"\r\n";
        
        byte[] handshake = (statusLine+hostHeader+"\r\n").getBytes();
        writer.write(server, handshake, ()->{
            reader.readUntilByte(server, Http.LineFeed, data->{
                String responseStatusLine = new String(data);
                String[] split = responseStatusLine.split(" ", 3);
                if(split.length!=3){
                    exHandler.accept(new IOException(format("非法的http响应状态行 : %s", responseStatusLine)));
                    return;
                }
                if(!"200".equals(split[1])){ //响应状态码不是200
                    exHandler.accept(new IOException(format("http响应状态码不是200 : %s", responseStatusLine)));
                    return;
                }
                http_client_exhaust_headers(server, onConnect, exHandler);
            }, exHandler);
        }, exHandler);
    }
    private void http_client_exhaust_headers(SocketChannel server,
            IOExceptionConsumer<SocketChannel> onConnect, Consumer<Exception> exHandler) {
        reader.readUntilByte(server, Http.LineFeed, data->{
            data = ArrayUtils.removeElement(data, Http.RetChar); //如win类操作系统，换行同时会携带'\r'，去掉它
            if(data.length==1){ //响应头结束，连接建立成功
                onConnect.accept(server);
            }else{
                http_client_exhaust_headers(server, onConnect, exHandler);
            }
        }, exHandler);
    }
    
    public void http_server_connect(SocketChannel client,
            IoExceptionBiConsumer<String, SocketChannel> onConnect, Consumer<Exception> exHandler) {
        reader.readUntilByte(client, Http.LineFeed, data->{
            String statusLine = new String(data, 0, data.length-1);
            String[] split = statusLine.split(" ", 3);
            if(split.length!=3){
                exHandler.accept(new IOException(format("非法的http请求状态行：%s", statusLine)));
                return;
            }
            if("CONNECT".equals(split[0])){
                http_server_jump(split, client, onConnect, exHandler);
            }else{
                http_server_direct(split, data, onConnect, exHandler);
            }
        }, exHandler);
    }
    private void http_server_direct(String[] statusLine, byte[] statusLineRaw,
            IoExceptionBiConsumer<String, SocketChannel> onConnect, Consumer<Exception> exHandler) {
        URL url;
        try{
            url=new URL(statusLine[1]);
        }catch(MalformedURLException e){
            exHandler.accept(new IOException(format("请求头中的目标地址非url : %s", statusLine[1])));
            return;
        }
        int port = url.getPort();
        if(port==-1) port = url.getDefaultPort();
        String targetStr = url.getHost()+":"+port;
        http_server_target_connect(targetStr, (target, closeExHandler)->{
            writer.write(target, statusLineRaw, ()->{
                onConnect.accept(targetStr, target);
            }, closeExHandler);
        }, exHandler);
    }
    private void http_server_jump(String[] statusLine,
            SocketChannel client,
            IoExceptionBiConsumer<String, SocketChannel> onConnect, Consumer<Exception> exHandler) {
        String targetStr = statusLine[1];
        http_server_target_connect(targetStr, (target, closeExHandler)->{
            http_server_jump_exhaust_headers(client, targetStr, target, onConnect, exHandler);
        }, exHandler);
    }
    private void http_server_jump_exhaust_headers(SocketChannel client,
            String targetStr, SocketChannel target,
            IoExceptionBiConsumer<String, SocketChannel> onConnect, Consumer<Exception> exHandler) {
        reader.readUntilByte(client, Http.LineFeed, data->{
            data = ArrayUtils.removeElement(data, Http.RetChar); //如win类操作系统，换行同时会携带'\r'，去掉它
            if(data.length==1){//请求头结束，返回连接建立成功消息
                writer.write(client, Http.EstablishedHeaders, ()->onConnect.accept(targetStr, target), exHandler);
            }else{
                http_server_jump_exhaust_headers(client, targetStr, target, onConnect, exHandler);
            }
        }, exHandler);
    }
    private void http_server_target_connect(String targetStr,
            IoExceptionBiConsumer<SocketChannel, Consumer<Exception>> onConnect, Consumer<Exception> exHandler) {
        HostAndPort targetConfig = ProxyApp.validHostPort(targetStr);
        if(targetConfig==null){
            exHandler.accept(new IOException(format("请求头中的目标地址非法 : %s", targetStr)));
            return;
        }
        
        SocketChannel target;
        try{
            target = SocketChannel.open();
        }catch(Exception e){
            exHandler.accept(new IOException(format("打开目标套接字失败 : %s", targetConfig), e));
            return;
        }
        Consumer<Exception> closeExHandler = e->{
            ProxyApp.close(target);
            exHandler.accept(e);
        };
        try{
            target.configureBlocking(false);
            target.connect(new InetSocketAddress(targetConfig.getHostText(), targetConfig.getPort()));
        }catch(Exception e){
            closeExHandler.accept(new IOException(format("连接目标套接字失败 : %s", targetConfig), e));
            return;
        }
        
        connector.connect(target, ()->{
            onConnect.accept(target, closeExHandler);
        }, e->{
            closeExHandler.accept(new IOException(format("连接目标失败 : %s", targetConfig), e));
        });
    }
    
}