package com.lvt4j.socketproxy;

import static com.lvt4j.socketproxy.ProxyApp.format;
import static java.util.Collections.emptyList;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
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
    public static Runnable changeCallback_pws;
    
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
    @Getter@Setter
    private List<Integer> pws = emptyList();
    
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
            if(changeCallback_pws!=null) changeCallback_pws.run();
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
        
        public URI proxy;
        
        public void setTarget(String target) {
            this.target = ProxyApp.validHostPort(target);
            Validate.notNull(this.target, "非法的地址:%s", target);
        }
        
        public void setProxy(String proxy) {
            if(proxy==null) return;
            URI url;
            try{
                url = new URI(proxy);
            }catch(Exception e){
                throw new IllegalArgumentException(String.format("非法的代理格式：%s", proxy));
            }
            Protocol protocol = Protocol.parse(url.getScheme());
            Validate.notNull(protocol, "代理配置中协议不支持：%s", url.getScheme());
            Validate.isTrue(url.getPort()>0, "代理配置中端口号异常：%s", url.getPort());
            switch(protocol){
            case Pws: case Pwss: break;
            default:
                Validate.isTrue(StringUtils.isBlank(url.getPath()), "非法的代理格式：%s", proxy);
                Validate.isTrue(StringUtils.isBlank(url.getQuery()), "非法的代理格式：%s", proxy);
                break;
            }
            this.proxy = url;
        }
        
        public String shortDirection() {
            String direction = String.valueOf(port);
            if(host!=null) direction = format(host)+":"+direction;
            return direction;
        }
        
        public String direction() {
            String direction = String.format("%s->%s", port, target);
            if(host!=null) direction = format(host)+":"+direction;
            if(proxy!=null) direction += " via "+proxy;
            return direction;
        }
    }
    
    /**
     * 内网穿透配置
     * @author LV on 2022年3月28日
     */
    @Data
    public static class IntranetConfig {
        
        public Type type;
        
        /** 入口服务配置，端口默认绑定的地址 */
        public String host;
        /** 入口服务配置，通过本端口接收客户端请求 */
        public Integer port;
        /** 入口服务配置，{@link #port}绑定的地址，为空时取{@link #host} */
        public String entryHost;
        /** 入口服务配置，转发服务通过本端口与入口服务建立连接 */
        public Integer relay;
        /** 入口服务配置，{@link #relay}绑定的地址，为空时取{@link #host} */
        public String relayHost;
        
        /** 转发服务配置，入口服务地址 */
        public HostAndPort entry;
        /** 转发服务配置，目标服务地址 */
        public HostAndPort target;
        
        /** 心跳间隔 */
        public Long heartbeatInterval = TimeUnit.SECONDS.toMillis(10);
        /** 多久没收到心跳时断开连接 */
        public Long heartbeatMissTimeout = TimeUnit.MINUTES.toMillis(1);
        
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