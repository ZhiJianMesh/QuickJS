package cn.net.zhijian.quickjs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class MultiThreadTest {
    @Test
    public void testMultiThread() {
        int N = Runtime.getRuntime().availableProcessors();
        AtomicInteger c = new AtomicInteger(0);
        ExecutorService pool = Executors.newCachedThreadPool();
        CountDownLatch holder = new CountDownLatch(N);
        
        for(int i = 0; i < N; i++) {
            pool.execute(() -> {
                try (QuickJSContext ctx =  QuickJSContext.create()){
                    Object r = ctx.evaluate("1 + 2;");
                    if(r.equals(3)) {
                        c.incrementAndGet();
                    }
                } finally {
                    holder.countDown();
                }
            });
        }
    
        try {
            holder.await(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
        assertEquals(c.get(), N);
    }
}
