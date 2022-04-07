package com.lvt4j.socketproxy;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author LV on 2022年4月6日
 */
public class DelayRunnerTest extends BaseTest {

    private DelayRunner runner;
    
    private Exception ex;
    
    @Before
    public void before() throws Exception {
        runner = new DelayRunner(); runner.init();
        ex = null;
    }
    
    @After
    public void after() {
        runner.destory();
    }
    
    @Test
    public void test() throws Exception {
        List<Integer> msgs = Collections.synchronizedList(new LinkedList<>());
        
        CountDownLatch latch = new CountDownLatch(2);
        
        long now = System.currentTimeMillis();
        
        runner.run(1000, ()->{
            msgs.add(2);
            assertEquals(now+1000, System.currentTimeMillis(), 10);
            latch.countDown();
        }, e->ex=e);
        runner.run(2000, ()->{
            msgs.add(1);
            assertEquals(now+2000, System.currentTimeMillis(), 10);
            latch.countDown();
        }, e->ex=e);
        
        latch.await();
        
        if(ex!=null) throw ex;
        
        assertEquals(2, (int)msgs.get(0));
        assertEquals(1, (int)msgs.get(1));
    }
    
}
