package com.lvt4j.socketproxy;

import java.net.SocketAddress;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 *
 * @author LV on 2022年3月11日
 */
@EnableScheduling
@SpringBootApplication
public class SocketProxyApp {

    public static void main(String[] args) {
        SpringApplication.run(SocketProxyApp.class, args);
    }
    
    
    public static String format(SocketAddress addr) {
        return StringUtils.strip(addr.toString(), "/");
    }
}