package cn.net.zhijian.quickjs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class MultiThreadTest {
    @Test
    public void testMultiThreadWithExclusiveContext() {
        int N = Runtime.getRuntime().availableProcessors();
        int T = 20000;
        AtomicInteger c = new AtomicInteger(0);
        ExecutorService pool = Executors.newCachedThreadPool();
        CountDownLatch holder = new CountDownLatch(N);
        long start = System.currentTimeMillis();
        
        for(int i = 0; i < N; i++) {
            pool.execute(() -> {
                int n = 0;
                //如果在线程池中使用，如果线程池是固定大小的，可以使用ThreadLocal保存ctx；
                //如果线程池是变动的，空闲时会销毁线程，此时ctx没有close，
                //这种情况需要关注，目前无解决方法，只能等程序结束时才能释放
                //本例因为在当前线程执行结束就释放，所以不涉及ctx缓存问题
                try (QuickJSContext ctx =  QuickJSContext.create()) {
                    //ctx.setMaxStackSize(1024 * 1024 * 4);
                    for(int j = 0; j < T; j++) {
                        Object r = ctx.evaluate("1 + 2;");
                        if(r.equals(3)) {
                            n++;
                        }
                    }
                } finally {
                    holder.countDown();
                    c.addAndGet(n);
                }
            });
        }
        long end = System.currentTimeMillis();
        long interval = end - start;
        
        System.out.println("testMultiThreadWithExclusiveContext,use time:" + interval + ",speed:" + (1000L * T * N) / interval);
    
        try {
            holder.await();
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
        assertEquals(N * T, c.get());
    }
    
    @Test
    public void testUsedInDifferentThread() {
        String js = "(function() {"
                + "var dbs=[{\"name\":\"192.168.1.6:8523\",\"ut\":1775875156723,\"val\":\"[{\\\"no\\\":0,\\\"level\\\":0,\\\"type\\\":\\\"SQLITE\\\",\\\"shardStart\\\":0,\\\"shardEnd\\\":32768,\\\"mode\\\":\\\"master\\\",\\\"readConn\\\":2,\\\"writeConn\\\":1,\\\"slaves\\\":\\\"192.168.1.6:8525\\\"}]\"}];\n"
                + "  var nodes,s;\n"
                + "  var shardings={};\n"
                + "  for(var d of dbs) { //addr(name)->cfg(val)\n"
                + "    nodes=JSON.parse(d.val); //[{no:xx,level:xx,shardStart:xx,shardEnd:..}..]\n"
                + "    for(var n of nodes) {\n"
                + "      if(!(s = shardings[n.no])){\n"
                + "       s=new Array(32768).fill(0);\n"
                + "       shardings[n.no]=s;\n"
                + "      }\n"
                + "      for(var i=n.shardStart;i<n.shardEnd;i++) {\n"
                + "        if(s[i]!=0) {//重叠分片\n"
                + "          return Mesh.error(RetCode.DATA_WRONG, 'duplicated sharding,('+n.shardStart+'-'+n.shardEnd+')@'+d.name+'#'+n.no);\n"
                + "        }\n"
                + "        s[i]=1;\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  for(var no in shardings) {//dbNo->sharding\n"
                + "    s=shardings[no];\n"
                + "    for(var i in s) {\n"
                + "      if(s[i]==0) { //未覆盖的分片\n"
                + "        return Mesh.error(RetCode.DATA_WRONG, 'empty sharding,('+i+')#'+no);\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  return Mesh.success({});\n"
                + "})()";

        QuickJSContext ctx =  QuickJSContext.create();
        CountDownLatch holder = new CountDownLatch(1);
        AtomicInteger c = new AtomicInteger(0);
        new Thread() {
            public void run() {
                try {
                    ctx.evaluate(js);
                } catch(Exception e) {
                    c.incrementAndGet();
                } finally {
                    holder.countDown();
                }
            }
        }.start();
    
        try {
            holder.await();
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
        assertEquals(1, c.get());
    }
}
