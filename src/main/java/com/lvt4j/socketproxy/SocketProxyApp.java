package com.lvt4j.socketproxy;

import java.net.InetAddress;
import java.net.SocketAddress;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

/**
 *
 * @author LV on 2022年3月11日
 */
@EnableScheduling
@SpringBootApplication
public class SocketProxyApp {

    public static void main(String[] args) {
//        SpringApplication.run(SocketProxyApp.class, args);
        System.out.println(Ints.toByteArray(Integer.MAX_VALUE).length);
    }
    
    
    public static String format(SocketAddress addr) {
        return StringUtils.strip(addr.toString(), "/");
    }
    public static String format(InetAddress addr) {
        return StringUtils.strip(addr.toString(), "/");
    }
}