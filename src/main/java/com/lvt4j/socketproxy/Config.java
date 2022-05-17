package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProxyApp.format;
import static java.util.Collections.emptyList;

import java.net.InetAddress;
import java.util.List;

import javax.annotation.PreDestroy;

import org.apache.commons.lang3.Validate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

import com.google.common.net.HostAndPort;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 *
 * @author LV on 2022年3月11日
 */
@RefreshScope
@Configuration
@ConfigurationProperties
public class Config {

    public static Runnable changeCallback_tcp;
    public static Runnable changeCallback_socks5;
    public static Runnable changeCallback_http;
    public static Runnable changeCallback_intranet;
    
    @Setter@Getter
    private long maxIdleTime;
    
    @Getter
    private List<TcpConfig> tcp = emptyList();
    @Getter@Setter
    private List<Integer> socks5 = emptyList();
    @Getter@Setter
    private List<Integer> http = emptyList();
    @Getter
    private List<IntranetConfig> intranet = emptyList();
    
    public void setTcp(List<TcpConfig> tcp) {
        for(TcpConfig c : tcp){
            Validate.notNull(c.target, "tcp反向代理，target配置不能为空");
            Validate.notNull(0<c.port, "tcp反向代理，port配置不能为空");
            Validate.isTrue(0<c.getPort() && c.getPort()<65535, "tcp反向代理，port配置必须在(0~65535]内");
        }
        this.tcp = tcp;
    }
    
    public void setIntranet(List<IntranetConfig> intranet) {
        for(IntranetConfig c : intranet){
            switch(c.getType()){
            case Entry:
                Validate.notNull(c.port, "intranet entry 服务 port配置不能为空");
                Validate.notNull(c.relay, "intranet entry 服务 relay配置不能为空");
                break;
            case Relay:
                Validate.notNull(c.entry, "intranet entry 服务 entry配置不能为空");
                Validate.notNull(c.target, "intranet entry 服务 entry配置不能为空");
                break;
            }
        }
        this.intranet = intranet;
    }
    
    @PreDestroy
    private void destory() {
        new Thread(()->{
            try{
                Thread.sleep(1000);
            }catch(Exception ig){}
            if(changeCallback_tcp!=null) changeCallback_tcp.run();
            if(changeCallback_socks5!=null) changeCallback_socks5.run();
            if(changeCallback_http!=null) changeCallback_http.run();
            if(changeCallback_intranet!=null) changeCallback_intranet.run();
        }).start();
    }
    
    /**
     * tcp反向代理配置
     * @author LV on 2022年4月7日
     */
    @Data
    public static class TcpConfig {
        /** 绑定的host，可以为空 */
        public InetAddress host;
        public Integer port;
        public HostAndPort target;
        
        public ProxyConfig proxy;
        
        public void setTarget(String target) {
            this.target = ProxyApp.validHostPort(target);
            Validate.notNull(this.target, "非法的地址:%s", target);
        }
        
        public void setProxy(ProxyConfig proxy) {
            Validate.notNull(proxy.protocol, "代理配置协议缺失");
            Validate.notNull(proxy.server, "代理配置服务缺失");
            this.proxy = proxy;
        }
        
        public String shortDirection() {
            String direction = String.valueOf(port);
            if(host!=null) direction = format(host)+":"+direction;
            return direction;
        }
        
        public String direction() {
            String direction = String.format("%s->%s", port, target);
            if(host!=null) direction = format(host)+":"+direction;
            if(proxy!=null) direction += " via "+proxy.direction();
            return direction;
        }
    }
    
    @Data
    public static class ProxyConfig {
        public Protocol protocol;
        public HostAndPort server;
        
        public void setServer(String server) {
            this.server = ProxyApp.validHostPort(server);
            Validate.notNull(this.server, "非法的地址:%s", server);
        }
        
        public String direction() {
            return protocol.toString().toLowerCase()+"://"+server;
        }
    }
    
    /**
     * 内网穿透配置
     * @author LV on 2022年3月28日
     */
    @Data
    public static class IntranetConfig {
        
        public Type type;
        
        /** 入口服务配置，通过本端口接收客户端请求 */
        public Integer port;
        /** 入口服务配置，转发服务通过本端口与入口服务建立连接 */
        public Integer relay;
        
        /** 转发服务配置，入口服务地址 */
        public HostAndPort entry;
        /** 转发服务配置，目标服务地址 */
        public HostAndPort target;
        
        public void setEntry(String entry) {
            this.entry = ProxyApp.validHostPort(entry);
            Validate.notNull(this.entry, "非法的地址:%s", entry);
        }
        public void setTarget(String target) {
            this.target = ProxyApp.validHostPort(target);
            Validate.notNull(this.target, "非法的地址:%s", target);
        }
        
        public enum Type {
            /** 入口服务 */
            Entry
            /** 转发服务 */
            ,Relay
            ;
        }
    }
    
}