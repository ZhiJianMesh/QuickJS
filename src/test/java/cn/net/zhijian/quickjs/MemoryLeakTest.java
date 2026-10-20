package cn.net.zhijian.quickjs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * 内存 / 引用计数回归用例。
 *
 * 阈值来自 WSL(Linux x86_64 Release) 与 Windows(x86_64 dll) 上的实测：
 * 干净路径每轮增量就是 0；漏一次 release 的代价实测约 104 字节/对象（含 Java 包装），
 * 所以这里给每次 8 字节的容差吸收 GC 抖动，同时仍能抓住"按次数线性增长"的真泄漏。
 * 不要把这些阈值往小了改：负增量是正常的（runGC 顺带回收了上一轮的临时对象）。
 */
public class MemoryLeakTest {

    /** 每次操作允许的最大字节残留，超出即判为线性泄漏。 */
    private static final long MAX_BYTES_PER_OP = 8;

    /** 跑 n 次操作，返回 {内存增量, Java 侧包装对象增量}。 */
    private static long[] measure(QuickJSContext ctx, int n, Runnable op) {
        ctx.runGC();
        long memBefore = ctx.getMemoryUsedSize();
        int recBefore = ctx.getObjectRecords().size();
        for (int i = 0; i < n; i++) {
            op.run();
        }
        ctx.runGC();
        long memDelta = ctx.getMemoryUsedSize() - memBefore;
        long recDelta = ctx.getObjectRecords().size() - recBefore;
        System.out.println("    measure n=" + n + " memDelta=" + memDelta + " recDelta=" + recDelta);
        return new long[]{memDelta, recDelta};
    }

    private static void assertNoGrowth(long[] r, int n, String what) {
        assertTrue(r[0] <= n * MAX_BYTES_PER_OP,
                what + " leaked " + r[0] + " bytes over " + n + " ops (" + (r[0] / (double) n) + " B/op)");
        assertEquals(0, r[1], what + " left " + r[1] + " Java wrapper objects behind");
    }

    @Test
    public void testReleasedEvaluateResultsDoNotGrow() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            assertNoGrowth(measure(ctx, 2000, () ->
                    ((JSObject) ctx.evaluate("({a:1,b:'2',c:{d:3}})")).release()), 2000, "released evaluate");
        }
    }

    @Test
    public void testPrimitiveEvaluationsDoNotGrow() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //基本类型走栈上 JSValue，不产生任何 Java 包装
            assertNoGrowth(measure(ctx, 10000, () -> ctx.evaluate("1+1")), 10000, "int evaluate");
            assertNoGrowth(measure(ctx, 10000, () -> ctx.evaluate("'x'+'y'")), 10000, "string evaluate");
            assertNoGrowth(measure(ctx, 5000, () -> ctx.evaluate("Math.random()")), 5000, "double evaluate");
        }
    }

    @Test
    public void testAbandonedWrappersAreTracked() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //反向锁住泄漏检测：返回对象不 release，每个都必须留在 records 里被记账
            long[] abandoned = measure(ctx, 500, () -> ctx.evaluate("(function(){return {}})()"));
            assertEquals(500, abandoned[1], "abandoned wrappers must be tracked");
            assertTrue(abandoned[0] > 0, "abandoned objects still hold JS memory");

            //同一条路径 release 干净就必须是零增量（实测漏一次是 104 字节/个）
            assertNoGrowth(measure(ctx, 500, () ->
                    ((JSObject) ctx.evaluate("(function(){return {}})()")).release()),
                    500, "released returned objects");
        }
    }

    @Test
    public void testGlobalObjectWrapperIsCachedAndReusable() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //getGlobalObject() 带缓存：反复取必须只有一个包装，取一次漏一次的历史行为不能再回来
            long[] cached = measure(ctx, 2000, () -> ctx.getGlobalObject());
            assertEquals(0, cached[1], "getGlobalObject should be cached, got " + cached[1] + " wrappers");

            //release() 之后必须自愈成新的活包装，并且这条路径不能把计数减穿
            //（曾经的缺陷：native 侧提前 JS_FreeValue，Java 侧再 release 就是二次释放）
            JSObject first = ctx.getGlobalObject();
            first.release();
            JSObject second = ctx.getGlobalObject();
            assertNotSame(first, second, "released wrapper must not be handed out again");
            second.setProperty("healed", 1);
            assertEquals(1, ctx.evaluate("healed"));

            //反复 release 也不能把包装对象攒起来：第一次 release 会把测量起点那颗
            //已缓存的包装从 records 里摘走，所以增量只会是 0 或 -1，绝不能是正的。
            long[] churn = measure(ctx, 500, () -> ctx.getGlobalObject().release());
            assertTrue(churn[1] <= 0, "global release churn left " + churn[1] + " wrappers behind");
            assertTrue(churn[0] <= 500 * MAX_BYTES_PER_OP, "global release churn grew " + churn[0] + " bytes");
        }
    }

    @Test
    public void testPropertyGetterIsReleased() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            ctx.evaluate("var objProp={a:1,b:{c:2}}");
            JSObject global = ctx.getGlobalObject();
            assertNoGrowth(measure(ctx, 2000, () ->
                    ((JSObject) global.getProperty("objProp")).release()), 2000, "property getter");

            //反向锁住契约：不 release 就是每次一个包装对象
            long[] leaked = measure(ctx, 300, () -> global.getProperty("objProp"));
            assertEquals(300, leaked[1], "unreleased getters must be tracked as leaks");
        }
    }

    @Test
    public void testCallbackArgumentOwnership() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSObject global = ctx.getGlobalObject();
            //native 对每个 JS 对象入参都 dup 一次，生命周期归 Java handler
            global.setProperty("cbKeep", (JSCallFunction) args -> String.valueOf(((JSObject) args[0]).getProperty("a")));
            global.setProperty("cbFree", (JSCallFunction) args -> {
                JSObject o = (JSObject) args[0];
                Object v = o.getProperty("a");
                o.release();
                return String.valueOf(v);
            });

            long[] leaked = measure(ctx, 500, () -> ctx.evaluate("cbKeep({a:1,b:{c:2}})"));
            assertEquals(500, leaked[1], "callback arg must be released by the handler");
            assertTrue(leaked[0] > 0, "unreleased callback args should still hold memory");

            assertNoGrowth(measure(ctx, 500, () -> ctx.evaluate("cbFree({a:1,b:{c:2}})")), 500, "released callback arg");
        }
    }

    @Test
    public void testNestedCallbackArguments() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSObject global = ctx.getGlobalObject();
            global.setProperty("cbNested", (JSCallFunction) args -> {
                JSObject o = (JSObject) args[0];
                JSObject child = (JSObject) o.getProperty("b");
                String s = child.getProperty("c") + "/" + o.getProperty("a");
                child.release();
                o.release();
                return s;
            });
            assertNoGrowth(measure(ctx, 1000, () -> ctx.evaluate("cbNested({a:1,b:{c:2}})")),
                    1000, "nested callback args");
        }
    }

    @Test
    public void testByteArrayRoundTripDoesNotGrow() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            final byte[] bytes = new byte[256];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) i;
            }
            JSObject global = ctx.getGlobalObject();
            assertNoGrowth(measure(ctx, 1000, () -> {
                global.setProperty("buf", bytes);
                assertEquals(bytes.length, global.getBytes("buf").length);
            }), 1000, "byte[] round trip");
        }
    }

    @Test
    public void testToMapOnCyclicGraphReleasesGetters() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //convertToMap 命中环时取出的包装自带一次计数，早先是就地丢弃（实测每次剩约 590 字节）
            assertNoGrowth(measure(ctx, 300, () -> {
                JSObject o = (JSObject) ctx.evaluate("(function(){var a={n:1};a.self=a;a.ref=a;return a})()");
                Map<String, Object> m = o.toMap();
                assertSame(m, m.get("self"));
                assertSame(m, m.get("ref"));
                o.release();
            }), 300, "cyclic toMap");
        }
    }

    @Test
    public void testToMapOnSharedGraphReleasesGetters() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //共享（非环）子对象命中 pointer 表，走同一条丢弃路径，实测每次剩约 104 字节
            assertNoGrowth(measure(ctx, 300, () -> {
                JSObject o = (JSObject) ctx.evaluate("(function(){var s={v:1};return {p1:s,p2:s,p3:{s:s}}})()");
                assertNotNull(o.toMap().get("p1"));
                o.release();
            }), 300, "shared toMap");
        }
    }

    @Test
    public void testToMapWithFilterReleasesGetters() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            final MapFilter filter = (key, pointer, extra) -> "drop".equals(key);
            final MapCreator creator = HashMap::new;
            assertNoGrowth(measure(ctx, 300, () -> {
                JSObject o = (JSObject) ctx.evaluate("({keep:1, drop:2, nested:{keep:3, drop:4}})");
                HashMap<String, Object> skipped = o.toMap(filter);
                Map<String, Object> created = o.toMap(filter, null, creator);
                JSArray a = (JSArray) ctx.evaluate("[{keep:1, drop:2}]");
                ArrayList<Object> arr = a.toArray(filter);
                //顶层留 keep+nested 两个（drop 被过滤），数组留 1 个元素
                assertEquals(5, skipped.size() + created.size() + arr.size());
                a.release();
                o.release();
            }), 300, "filtered toMap/toArray");
        }
    }

    @Test
    public void testBytecodeExecuteDoesNotAccumulate() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            final byte[] bytecode = ctx.compile("var z='abcdefghij'.repeat(50); z.length");
            assertNoGrowth(measure(ctx, 1000, () -> assertEquals(500, ctx.execute(bytecode))),
                    1000, "bytecode execute");
            assertNoGrowth(measure(ctx, 500, () -> ctx.compile("1+1")), 500, "compile");
        }
    }

    @Test
    public void testLargeStringAcrossJni() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            assertNoGrowth(measure(ctx, 200, () ->
                    assertEquals(16384, ((String) ctx.evaluate("'q'.repeat(16384)")).length())),
                    200, "16KB string marshal");
        }
    }

    @Test
    public void testUnreachableJsGraphIsCollected() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            long before = ctx.getMemoryUsedSize();
            for (int i = 0; i < 500; i++) {
                ctx.evaluate("(function(){var o={a:{b:{c:1}}};o.x=o;return 1})()");
            }
            ctx.runGC();
            long delta = ctx.getMemoryUsedSize() - before;
            System.out.println("    unreachable graph residual=" + delta);
            assertTrue(delta < 256 * 1024, "unreachable graph held " + delta + " bytes");
        }
    }

    @Test
    public void testGcFreesDroppedJsMemory() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //JS 侧丢掉引用后，runGC 必须把这块内存还回来，否则引擎级回收就是坏的
            ctx.runGC();
            long base = ctx.getMemoryUsedSize();
            ctx.evaluate("var holdBig = new Array(200000).fill('abcdefghij')");
            assertTrue(ctx.getMemoryUsedSize() - base > 1024, "allocation should be visible in used size");
            ctx.evaluate("holdBig = null");
            ctx.runGC();
            ctx.runGC();
            long after = ctx.getMemoryUsedSize() - base;
            System.out.println("    after drop+gc delta=" + after);
            assertTrue(after < 256 * 1024, "runGC did not reclaim dropped JS memory, delta=" + after);
        }
    }

    @Test
    public void testHandledRejectionsDoNotAccumulate() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //rejection 队列按 promise 身份配对，catch 掉的那条必须从队列里摘走
            assertNoGrowth(measure(ctx, 1000, () ->
                    ctx.evaluate("Promise.reject(new Error('x')).catch(function(){return 1})")),
                    1000, "handled rejections");
        }
    }

    @Test
    public void testExceptionPathDoesNotAccumulate() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //异常路径上的中间 JSValue 最容易漏 free
            assertNoGrowth(measure(ctx, 1000, () -> {
                try {
                    ctx.evaluate("throw new TypeError('boom')");
                } catch (QuickJSException expected) {
                    assertNotNull(expected.getMessage());
                }
            }), 1000, "thrown exceptions");

            assertNoGrowth(measure(ctx, 500, () -> {
                try {
                    ctx.evaluate("(function(){ var s=new Array(1000).fill('y'); return JSON.parse('{oops') })()");
                } catch (QuickJSException expected) {
                    assertNotNull(expected.getMessage());
                }
            }), 500, "exception after allocation");
        }
    }

    @Test
    public void testRecordedObjectsReleasedOnContextClose() {
        //故意不 release 一批包装对象：close() 必须把它们全部收回，不能留在进程里
        for (int round = 0; round < 20; round++) {
            QuickJSContext ctx = QuickJSContext.create();
            int baseline = ctx.getObjectRecords().size();
            for (int i = 0; i < 50; i++) {
                ctx.evaluate("({i:" + i + ", nested:{a:1}})");
            }
            assertEquals(baseline + 50, ctx.getObjectRecords().size());
            ctx.close();
            assertTrue(ctx.closed());
        }
        //反复建/关 20 轮之后堆内存不该单调上涨
        System.gc();
        long heapBefore = usedHeap();
        for (int i = 0; i < 40; i++) {
            try (QuickJSContext ctx = QuickJSContext.create()) {
                ctx.evaluate("var a={x:1}; a");
                ctx.getGlobalObject().setProperty("k", i);
            }
        }
        System.gc();
        long heapAfter = usedHeap();
        System.out.println("    context churn heap delta=" + (heapAfter - heapBefore));
        assertTrue(heapAfter - heapBefore < 8 * 1024 * 1024,
                "create/close cycle grew Java heap by " + (heapAfter - heapBefore));
    }

    @Test
    public void testCloseIsRobustAfterGlobalWrapperAbuse() {
        //调用方 release 掉缓存的全局包装、再把内置 format 覆盖成非函数：
        //close() 不能因此中断，否则整个 runtime 就地泄漏（quickjs-ng 调试版会停在 gc_obj_list 断言）
        for (int round = 0; round < 10; round++) {
            QuickJSContext ctx = QuickJSContext.create();
            ctx.getGlobalObject().release();
            ctx.evaluate("var format = 42");
            ctx.evaluate("({leaked:1})");
            ctx.close();
            assertTrue(ctx.closed(), "round " + round);
        }
    }

    @Test
    public void testLeakListenerDoesNotAffectAccounting() {
        QuickJSContext ctx = QuickJSContext.create();
        final AtomicInteger reported = new AtomicInteger();
        try {
            ctx.setLeakDetectionListener((leak, stringValue) -> reported.incrementAndGet());
            assertNoGrowth(measure(ctx, 500, () -> ((JSObject) ctx.evaluate("({a:1})")).release()),
                    500, "with leak listener attached");
            assertEquals(0, reported.get());
        } finally {
            ctx.close();
        }
        assertEquals(0, reported.get(), "nothing was abandoned, close() must not report leaks");
    }

    @Test
    public void testHoldKeepsObjectAliveAndReportedOnClose() {
        QuickJSContext ctx = QuickJSContext.create();
        JSObject obj = (JSObject) ctx.evaluate("({held:true})");
        int base = obj.getRefCount();
        obj.hold();
        obj.hold();
        assertEquals(base + 2, obj.getRefCount());
        obj.release();
        assertEquals(base + 1, obj.getRefCount());
        assertTrue(obj.isAlive());

        final List<String> leaks = new ArrayList<>();
        ctx.setLeakDetectionListener((leak, stringValue) -> leaks.add(stringValue));
        //还挂着一笔未归还的 hold，close 时应该正好报出这一条
        ctx.close();
        assertEquals(1, leaks.size(), String.valueOf(leaks));
        assertTrue(ctx.closed());
    }

    @Test
    public void testModuleLoaderRepeatedSameModuleIsBounded() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            ctx.setModuleLoader(new QuickJSContext.DefaultModuleLoader() {
                @Override
                public String getStringCode(String moduleName) {
                    return "export const v = 1;";
                }
            });
            // 同一个模块名反复 import：loader 只真正调用一次（QuickJS 按名缓存模块 def），
            // 这里断言反复加载不会让内存持续上涨、也不会崩溃。loader 返回后那个 module
            // 类型的 JSValue 必须被释放（见 quickjs_wrapper.cpp jsModuleLoaderFunc），
            // 否则每次加载都会泄漏一个指向已缓存 def 的值。
            final int n = 2000;
            ctx.runGC();
            long before = ctx.getMemoryUsedSize();
            for (int i = 0; i < n; i++) {
                ctx.evaluate("import('same.js').then(m => m.v)");
            }
            ctx.runGC();
            long delta = ctx.getMemoryUsedSize() - before;
            System.out.println("    repeated same-module delta=" + delta);
            assertTrue(delta < 64 * 1024, "repeated same-module load grew " + delta + " bytes");
        }
    }

    @Test
    public void testSetArrayElementDoesNotLeak() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            // A 的泄漏路径：JSArray.set(index, value) 数组下标赋值。
            // 现有用例只覆盖了 setProperty（对象属性）与 byte[] 往返，没有覆盖数组元素赋值，
            // 而 set() 非 jsObjectClass 分支若对堆值多 dup 一次、原始值从不释放就会泄漏。
            // 注意：runGC() 只做 native GC、不触发 Java GC，所以这里复用同一个数组、
            // 每次覆盖下标元素——覆盖旧值会释放上一次的引用（不依赖 Java GC 回收 wrapper），
            // 才能干净地隔离“每次 set 是否泄漏”这一项。
            final int n = 5000;
            JSArray arr = ctx.createJSArray();
            ctx.runGC();
            long before = ctx.getMemoryUsedSize();
            for (int i = 0; i < n; i++) {
                arr.set(new byte[]{1, 2, 3, 4, 5}, 0);   // Uint8Array
                arr.set(3.1415926, 1);                    // Float64
                arr.set(1234567890123L, 2);               // Int64 / BigInt
                arr.set((JSCallFunction) args -> 0, 3);   // 回调（MKPTR，free 无副作用，仅验证不崩）
            }
            ctx.runGC();
            long delta = ctx.getMemoryUsedSize() - before;
            arr.release();
            ctx.runGC();
            System.out.println("    set-array-element delta=" + delta + " over " + n);
            assertTrue(delta < n * 16, "set() array element leaked " + delta + " bytes over " + n);
        }
    }

    @Test
    public void testSetJSObjectElementSurvivesGC() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            // set() 对 jsObjectClass 必须 JS_DupValue 保护 Java 持有的引用，
            // 否则调用方释放 Java 侧 JSObject 后，数组里的元素会变悬空/损坏（A 的 jsObjectClass 分支）。
            JSArray arr = ctx.createJSArray();
            JSObject inner = ctx.createJSObject();
            inner.setProperty("k", "v");
            arr.set(inner, 0);
            inner.release();   // 模拟调用方只保留数组引用
            inner = null;
            ctx.runGC();
            Object got = arr.get(0);
            assertTrue(got instanceof JSObject);
            assertEquals("v", ((JSObject) got).getString("k"));
        }
    }

    @Test
    public void testModuleLoaderBytecodeRepeatedSameModuleIsBounded() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            final String MOD = "same.js";
            byte[] bc = ctx.compileModule("export const v = 1;", MOD);
            ctx.setModuleLoader(new QuickJSContext.BytecodeModuleLoader() {
                @Override
                public byte[] getBytecode(String moduleName) {
                    return MOD.equals(moduleName) ? bc : null;
                }
            });
            // 覆盖 B 的字节码分支：jsModuleLoaderFunc 里 func_val 拿到 m 后必须被释放，
            // 否则每次加载泄漏一个指向已缓存 def 的 module 值。同名模块只真正加载一次。
            final int n = 2000;
            ctx.runGC();
            long before = ctx.getMemoryUsedSize();
            for (int i = 0; i < n; i++) {
                ctx.evaluate("import('" + MOD + "').then(m => m.v)");
            }
            ctx.runGC();
            long delta = ctx.getMemoryUsedSize() - before;
            System.out.println("    repeated same-bytecode-module delta=" + delta);
            assertTrue(delta < 64 * 1024, "repeated same-bytecode-module load grew " + delta + " bytes");
        }
    }

    @Test
    public void testNoLeftoverTempLibraryFiles() {
        // NativeLibraryLoader 提取并加载库后会清理临时文件：非 Windows 立即删除，
        // Windows 因 DLL 被 LoadLibrary 锁定只能在 JVM 退出时删（deleteOnExit 兜底）。
        // 这里断言创建若干上下文前后，系统临时目录下 libquickjs_ 前缀文件数量不增长。
        if (System.getProperty("os.name", "").toLowerCase().startsWith("win")) {
            return; // Windows 下无法在进程内删除已加载的 DLL，跳过断言
        }
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        int before = countLibTempFiles(tmp);
        for (int i = 0; i < 3; i++) {
            try (QuickJSContext ctx = QuickJSContext.create()) {
                ctx.evaluate("1");
            }
        }
        int after = countLibTempFiles(tmp);
        assertEquals(before, after, "leftover libquickjs_ temp files: before=" + before + " after=" + after);
    }

    private static int countLibTempFiles(File dir) {
        String[] names = dir.list((d, name) -> name.startsWith("libquickjs_"));
        return names == null ? 0 : names.length;
    }

    private static long usedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
}
