package com.lvt4j.socketproxy;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import com.lvt4j.socketproxy.ProxyApp.IOExceptionRunnable;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author LV on 2022年4月6日
 */
@Slf4j
@Service
public class DelayRunner extends Thread implements UncaughtExceptionHandler {

    private DelayQueue<DelayMeta> queue = new DelayQueue<>();
    
    private volatile boolean destory;
    
    @PostConstruct
    public void init() {
        init("DelayRunner");
    }
    public void init(String name) {
        setName(name);
        setUncaughtExceptionHandler(this);
        start();
    }
    @PreDestroy
    @SneakyThrows
    public void destory() {
        destory = true;
        interrupt();
        join();
    }
    
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if(e instanceof InterruptedException) return;
        log.error("delay runner err", e);
    }
    
    public Delayed run(long delayed, IOExceptionRunnable run, Consumer<Exception> exHandler) {
        DelayMeta meta = new DelayMeta(delayed, run, exHandler);
        queue.put(meta);
        return meta;
    }
    
    public boolean cancel(Delayed delayed) {
        if(delayed==null) return false;
        return queue.remove(delayed);
    }
    
    @Override
    public void run() {
        while(!destory){
            DelayMeta meta;
            try{
                meta = queue.take();
            }catch(InterruptedException e){
                continue;
            }
            try{
                meta.run.run();
            }catch(Exception e){
                meta.exHandler.accept(e);
            }
        }
    }
    
    @RequiredArgsConstructor
    private class DelayMeta implements Delayed {

        private final long createTime = System.currentTimeMillis();
        private final long delayMs;
        private final IOExceptionRunnable run;
        private final Consumer<Exception> exHandler;

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(getDelay(MILLISECONDS), o.getDelay(MILLISECONDS));
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long delay = createTime+delayMs-System.currentTimeMillis();
            return unit.convert(delay, MILLISECONDS);
        }

    }

}