package cn.net.zhijian.quickjs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 性能回归用例：给每条热路径一个"实测值 x 宽松倍数"的时间预算。
 *
 * 基准来自 WSL(Linux x86_64 Release) 实测：1e6 次累加约 44ms、1e5 push+join 约 10ms、
 * fib(25) 约 16ms、getMemoryUsedSize 约 0.003ms/次、runGC 约 0.009ms/次。
 * 预算一律放大一个数量级，CI 机器再慢也够；它们抓的是"数量级劣化"（比如某次改动让
 * 每次 JNI 调用重新分配一个大 buffer、或者 toMap 变成深拷贝），不是微抖动。
 * 每条用例都会打印实测值，红了先看打印再改代码。
 */
public class PerformanceTest {

    /** 先热一下 JIT 再计时，避免解释执行的首跑把结果拖偏。 */
    private static long timed(String name, int warmupOps, int ops, Runnable body) {
        for (int i = 0; i < warmupOps; i++) {
            body.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            body.run();
        }
        long elapsed = System.nanoTime() - start;
        long ms = elapsed / 1_000_000L;
        System.out.println("    perf " + name + ": ops=" + ops + " ms=" + ms
                + " us/op=" + ((double) elapsed / 1000.0 / ops));
        return ms;
    }

    private static void assertWithin(String name, long actualMs, long budgetMs) {
        assertTrue(actualMs <= budgetMs, name + " took " + actualMs + "ms, budget " + budgetMs + "ms");
    }

    @Test
    public void testPureJsBudgets() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            Object sum = ctx.evaluate("(function(){var s=0;for(var i=0;i<1000000;i++)s+=i;return s})()");
            assertEquals(499999500000L, ((Number) sum).longValue());

            long loop = timed("1e6 add", 0, 1, () ->
                    ctx.evaluate("(function(){var s=0;for(var i=0;i<1000000;i++)s+=i;return s})()"));
            assertWithin("1e6 add", loop, 1200);

            long join = timed("1e5 push+join", 0, 1, () ->
                    ctx.evaluate("(function(){var a=[];for(var i=0;i<100000;i++)a.push(i);return a.join('').length})()"));
            assertWithin("1e5 push+join", join, 300);

            long fib = timed("fib(25)", 0, 3, () ->
                    ctx.evaluate("(function f(n){return n<2?n:f(n-1)+f(n-2)})(25)"));
            assertWithin("fib(25) x3", fib, 900);

            long json = timed("JSON round trip", 20, 1000, () ->
                    ctx.evaluate("JSON.parse(JSON.stringify({a:[1,2,3],s:'abcdefghij'.repeat(200)}))"));
            assertWithin("JSON round trip x1000", json, 4000);
        }
    }

    @Test
    public void testEvaluateCallOverhead() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            long ms = timed("trivial evaluate", 2000, 20000, () -> ctx.evaluate("1+1"));
            assertWithin("20000 trivial evaluates", ms, 4000);
            //单次 JNI 往返不该超过 0.2ms，超了说明参数编解码里多了分配
            double usPerOp = (double) ms * 1000.0 / 20000;
            assertTrue(usPerOp < 200, "evaluate overhead " + usPerOp + " us/op");
        }
    }

    @Test
    public void testPropertyAccessOverhead() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSObject global = ctx.getGlobalObject();
            long ms = timed("get/set property", 1000, 20000, () -> {
                global.setProperty("p", 1);
                assertEquals(1, global.getProperty("p"));
            });
            assertWithin("20000 property get+set", ms, 4000);
        }
    }

    @Test
    public void testCallbackOverhead() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSObject global = ctx.getGlobalObject();
            global.setProperty("cb", (JSCallFunction) args -> {
                JSObject o = (JSObject) args[0];
                String r = String.valueOf(o.getProperty("v"));
                o.release();
                return r;
            });
            long ms = timed("js->java callback", 500, 5000, () -> ctx.evaluate("cb({v:1})"));
            assertWithin("5000 callbacks", ms, 4000);
        }
    }

    @Test
    public void testToMapThroughput() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSObject wide = (JSObject) ctx.evaluate(
                    "(function(){var o={};for(var i=0;i<200;i++)o['k'+i]={v:i,s:'x'.repeat(8)};return o})()");
            Map<String, Object> first = wide.toMap();
            assertEquals(200, first.size());
            long ms = timed("toMap(200 keys)", 10, 200, wide::toMap);
            assertWithin("200 toMap of 200-key object", ms, 6000);
            wide.release();
        }
    }

    @Test
    public void testBytecodeIsNotSlowerThanSource() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            final String src = "(function(){var s=0;for(var i=0;i<20000;i++)s+=i;return s})()";
            final byte[] bc = ctx.compile(src);
            assertEquals(199990000, ctx.execute(bc));

            long bcMs = timed("execute bytecode", 100, 2000, () -> ctx.execute(bc));
            long evalMs = timed("evaluate source", 100, 2000, () -> ctx.evaluate(src));
            //字节码省掉解析，正常应快于源码；这里只锁"不要反向劣化"
            System.out.println("    bytecode/evaluate ratio=" + (evalMs == 0 ? "n/a" : (double) bcMs / evalMs));
            assertTrue(bcMs <= evalMs * 3 + 500, "bytecode " + bcMs + "ms vs evaluate " + evalMs + "ms");
        }
    }

    @Test
    public void testAccountingApiIsCheap() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //这两个调用是内存/泄漏用例的度量手段，本身必须足够便宜
            //实测：Windows 42ms、Linux Release ~60ms、Linux ASan 474ms，
            //所以预算按"最慢的插桩构建也够用"来定，抓的是量级劣化
            long memMs = timed("getMemoryUsedSize", 2000, 20000, ctx::getMemoryUsedSize);
            assertWithin("20000 getMemoryUsedSize", memMs, 2000);

            long gcMs = timed("runGC", 200, 2000, ctx::runGC);
            assertWithin("2000 runGC", gcMs, 2000);
        }
    }

    @Test
    public void testContextCreateCloseCost() {
        long ms = timed("create+close", 5, 100, () -> {
            try (QuickJSContext ctx = QuickJSContext.create()) {
                ctx.evaluate("({a:1})");
            }
        });
        assertWithin("100 create+close", ms, 8000);
    }

    @Test
    public void testDeepRecursionIsRejectedCheaply() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //命中栈预算必须"抛得出"而不是把进程打死，顺带确认拒绝成本不高
            long ms = timed("stack overflow", 10, 200, () -> {
                try {
                    ctx.evaluate("(function r(n){return n<=0?0:1+r(n-1)})(50000)");
                } catch (QuickJSException expected) {
                    assertTrue(String.valueOf(expected.getMessage()).contains("stack"), expected.getMessage());
                }
            });
            assertWithin("200 rejected deep recursions", ms, 8000);
            assertEquals(4, ctx.evaluate("2+2"));
        }
    }
}
