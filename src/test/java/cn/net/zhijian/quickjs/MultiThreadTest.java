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
                //如果在线程池中使用，如果线程池是固定大小的，可以使用ThreadLocal保存ctx；
                //如果线程池是变动的，空闲时会销毁线程，则建议使用一个池子管理ctx，避免线程销毁了，ctx没有close；
                //本例因为在当前线程执行结束就释放，所以不涉及ctx缓存问题
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
