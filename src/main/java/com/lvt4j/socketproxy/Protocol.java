package com.lvt4j.socketproxy;

import java.net.URI;

import lombok.SneakyThrows;

/**
 *
 * @author LV on 2022年4月15日
 */
public enum Protocol {

    Socks5
    ,Http
    ,Pws //private web socket
    ,Pwss //private web socket
    ;
    
    public static Protocol parse(String protocol) {
        for(Protocol p : values()){
            if(p.toString().equalsIgnoreCase(protocol)) return p;
        }
        return null;
    }
    
    @SneakyThrows
    public static URI pws2ws(URI uri) {
        return new URI(uri.getScheme().replace("p", ""), null, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
    }
}