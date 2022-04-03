package com.lvt4j.socketproxy;

import static java.util.Collections.emptySet;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.PreDestroy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

import com.google.common.net.HostAndPort;

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
    
    @Setter@Getter
    private long maxIdleTime;
    
    @Getter
    private Map<Integer, HostAndPort> tcp;
    
    @Getter@Setter
    private Set<Integer> socks5 = emptySet();
    @Getter@Setter
    private Set<Integer> http = emptySet();
    
    public void setTcp(Map<Integer, String> proxy) {
        Map<Integer, HostAndPort> tcp = new HashMap<>();
        
        proxy.forEach((p,a)->{
            HostAndPort hp = ProxyApp.validHostPort(a);
            if(hp==null) return;
            tcp.put(p, hp);
        });
        this.tcp = tcp;
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
        }).start();
    }
    
}