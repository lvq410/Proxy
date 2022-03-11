package com.lvt4j.socketproxy;

import static java.util.stream.Collectors.toMap;

import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

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

    public static Runnable changeCallback;
    
    @Setter@Getter
    private long maxIdleTime;
    
    @Getter
    private Map<Integer, String> proxy;
    
    public void setProxy(Map<Integer, String> proxy) {
        this.proxy = proxy.entrySet().stream()
            .filter(e->isValidTarget(e.getValue())).collect(toMap(Entry::getKey, Entry::getValue));
    }
    
    private boolean isValidTarget(String target) {
        if(StringUtils.isBlank(target)) return false;
        String[] splits = target.split("[:]",2);
        if(splits.length!=2) return false;
        if(StringUtils.isBlank(splits[0])) return false;
        if(!NumberUtils.isDigits(splits[1])) return false;
        return true;
    }
    
    @PreDestroy
    private void destory() {
        new Thread(()->{
            try{
                Thread.sleep(1000);
            }catch(Exception ig){}
            if(changeCallback!=null) changeCallback.run();
        }).start();
    }
    
}