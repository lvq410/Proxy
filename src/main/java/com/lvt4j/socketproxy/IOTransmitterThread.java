package com.lvt4j.socketproxy;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;

import org.slf4j.Logger;

import lombok.SneakyThrows;

/**
 *
 * @author LV on 2022年3月22日
 */
class IOTransmitterThread extends Thread {
    private final Logger log;
    private final InputStream in;
    private final OutputStream out;
    
    public long latestTouchTime = System.currentTimeMillis();
    
    public IOTransmitterThread(String name, Logger log, InputStream in, OutputStream out) {
        setName(name);
        this.log = log;
        this.in = in;
        this.out = out;
        setDaemon(true);
        start();
    }
    
    @Override @SneakyThrows
    public void run() {
        int n = 0; byte[] buffer = new byte[1024];
        try{
            while (-1 != (n = in.read(buffer))) {
                out.write(buffer, 0, n);
                out.flush();
                latestTouchTime = System.currentTimeMillis();
            }
        }catch(SocketException e){
            if(e.getMessage().contains("Socket closed")) return;
            log.error("{} trans data err", getName() ,e);
        }
    }

}