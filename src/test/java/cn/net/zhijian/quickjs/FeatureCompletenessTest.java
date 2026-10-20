package cn.net.zhijian.quickjs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * 功能完备性用例：把 wrapper 暴露出来的每条 API 路径都真跑一遍，
 * 断言的是实测锁定下来的行为（WSL Release/ASan + Windows dll 三轮跑出来的结果），
 * 不是"应该是这样"。有几处是已知缺陷，注释里写明了"锁定现状"，改实现时记得同步改断言。
 */
public class FeatureCompletenessTest {

    public static class Mesh {
        @JavascriptMethod
        public String ok(String name) {
            return "ok:" + name;
        }

        @JavascriptMethod
        public int add(int a, int b) {
            return a + b;
        }

        /** 没加注解，JS 侧不应该看得见。 */
        public String hidden() {
            return "should-not-be-visible";
        }
    }

    @Test
    public void testSetPropertyValueOverloads() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSObject g = ctx.getGlobalObject();
            g.setProperty("s", "str");
            g.setProperty("i", 7);
            g.setProperty("l", 9000000000L);
            g.setProperty("b", true);
            g.setProperty("d", 1.5);
            g.setProperty("bytes", new byte[]{1, 2, 3});
            g.setProperty("fn", (JSCallFunction) args -> "called");
            JSObject child = ctx.createJSObject();
            child.setProperty("inner", 1);
            g.setProperty("o", child);

            assertEquals("str", ctx.evaluate("s"));
            assertEquals(7, ctx.evaluate("i"));
            assertEquals(9000000000L, ctx.evaluate("l"));
            assertEquals(true, ctx.evaluate("b"));
            assertEquals(1.5, ctx.evaluate("d"));
            assertEquals("function", ctx.evaluate("typeof fn"));
            assertEquals("called", ctx.evaluate("fn()"));
            assertEquals(1, ctx.evaluate("o.inner"));
            //byte[] 过 JNI 是 ArrayBuffer，不是 Uint8Array：没有 length，只有 byteLength
            assertEquals("object", ctx.evaluate("typeof bytes"));
            assertEquals("ArrayBuffer", ctx.evaluate("bytes.constructor.name"));
            assertEquals(Boolean.TRUE, ctx.evaluate("bytes instanceof ArrayBuffer"));
            assertEquals(3, ctx.evaluate("bytes.byteLength"));
            assertEquals(2, ctx.evaluate("new Uint8Array(bytes)[1]"));
            assertEquals("[1, 2, 3]", Arrays.toString(g.getBytes("bytes")));

            assertEquals("str", g.getString("s"));
            assertEquals(Integer.valueOf(7), g.getInteger("i"));
            assertEquals(Boolean.TRUE, g.getBoolean("b"));
            assertEquals(Double.valueOf(1.5), g.getDouble("d"));
            assertEquals(Long.valueOf(9000000000L), g.getLong("l"));
            assertTrue(g.containsProperty("s"));
            assertFalse(g.containsProperty("nope"));

            JSArray names = g.getNames();
            assertTrue(names.toArray().contains("s"));
            names.release();
            child.release();
        }
    }

    @Test
    public void testSetPropertyObjectAndUnsupportedType() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSObject g = ctx.getGlobalObject();
            g.setPropertyObject("po", Integer.valueOf(42));
            assertEquals(42, ctx.evaluate("po"));

            g.setPropertyObject("pnull", null);
            assertEquals("object", ctx.evaluate("typeof pnull"));

            //非白名单类型直接拒，不能悄悄塞成 undefined
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> g.setPropertyObject("bad", new java.util.Date()));
            assertTrue(e.getMessage().contains("Unsupported Java type"), e.getMessage());

            //Map 也不能作为回调返回值，同一条转换路径
            g.setProperty("mapCb", (JSCallFunction) args -> new HashMap<String, Object>());
            assertThrows(IllegalArgumentException.class, () -> ctx.evaluate("mapCb()"));
        }
    }

    @Test
    public void testSetPropertyFromClass() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSObject g = ctx.getGlobalObject();
            //setProperty(String, Class) 会反射建实例，等价于 setJavaObject
            g.setProperty("mesh", Mesh.class);
            assertEquals("object", ctx.evaluate("typeof mesh"));
            assertEquals("ok:js", ctx.evaluate("mesh.ok('js')"));
            assertEquals(3, ctx.evaluate("mesh.add(1,2)"));
            assertEquals("undefined", ctx.evaluate("typeof mesh.hidden"));

            //没有无参构造的类：建不出来就是抛，不会静默设成 undefined
            assertThrows(NullPointerException.class, () -> g.setProperty("noctor", int.class));
        }
    }

    @Test
    public void testSetJavaObjectArgumentCoercion() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSObject g = ctx.getGlobalObject();
            g.setJavaObject("mesh", new Mesh());
            assertEquals("ok:x", ctx.evaluate("mesh.ok('x')"));
            assertEquals(5, ctx.evaluate("mesh.add(2,3)"));
            assertEquals("undefined", ctx.evaluate("typeof mesh.hidden"));
            //注册出来的胶水函数固定声明 1 个形参，JS 侧看到的 length 就是 1
            assertEquals(1, ctx.evaluate("mesh.add.length"));

            //已知缺陷（锁定现状）：反射按 (args[0], args[1]) 逐个取值再强转，
            //少传/多传/类型不符都会被 catch 掉并静默返回 null，而不是抛错。
            //宿主类还不是 public 时同理（IllegalAccessException 也被吞掉），只是这次类是 public 的。
            assertNull(ctx.evaluate("mesh.add(1)"));
            assertNull(ctx.evaluate("mesh.add(1,2,3)"));
            assertNull(ctx.evaluate("mesh.add('x','y')"));
            assertNull(ctx.evaluate("mesh.ok(123)"));
        }
    }

    @Test
    public void testJsonAndParse() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            Object parsed = ctx.parse("{\"a\":1,\"b\":[1,2],\"c\":{\"d\":\"e\"}}");
            assertTrue(parsed instanceof JSObject, String.valueOf(parsed));
            JSObject o = (JSObject) parsed;
            //parse 出来的数字统一是 Long，跟 evaluate 的 Integer 不一样（同一份 JSON 走 JS 求值也一样）
            assertEquals(Long.valueOf(1), o.getProperty("a"));
            assertEquals("e", ((JSObject) o.getProperty("c")).getString("d"));
            ((JSObject) o.getProperty("c")).release();
            o.release();

            JSObject src = (JSObject) ctx.evaluate("({a:1,b:[1,2]})");
            String json = src.stringify();
            assertEquals("{\"a\":1,\"b\":[1,2]}", json);
            assertEquals(1L, ctx.evaluate("JSON.parse(jsonStr).a".replace("jsonStr", "'" + json + "'")));
            src.release();

            //非法 JSON 走的是 JS 异常路径，消息可读
            QuickJSException e = assertThrows(QuickJSException.class, () -> ctx.parse("{oops"));
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testArrayAccessors() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSArray a = (JSArray) ctx.evaluate("[10,'x',{q:1},[1,2]]");
            assertEquals(4, ctx.length(a));
            assertEquals(4, a.length());
            assertEquals(10, a.get(0));
            assertEquals("x", a.get(1));
            assertEquals(1, ((JSObject) a.get(2)).getProperty("q"));
            ((JSObject) a.get(2)).release();
            ArrayList<Object> plain = a.toArray();
            assertEquals(4, plain.size());

            a.set("y", 1);
            assertEquals("y", a.get(1));
            JSArray nested = (JSArray) a.get(3);
            assertEquals(2, ctx.length(nested));
            nested.release();
            a.release();

            JSArray made = ctx.createJSArray();
            made.set("head", 0);
            assertEquals("head", made.get(0));
            made.release();
        }
    }

    @Test
    public void testErrorMessageCarriesFileName() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            QuickJSException e = assertThrows(QuickJSException.class,
                    () -> ctx.evaluate("throw new Error('named')", "my/file.js"));
            assertTrue(e.getMessage().contains("named"), e.getMessage());
            assertTrue(e.getMessage().contains("my/file.js"), e.getMessage());

            //语法错同样带文件名和位置（quickjs 的措辞是 "xxx expected"，不会写 "syntax"）
            QuickJSException syn = assertThrows(QuickJSException.class, () -> ctx.evaluate("var ="));
            assertTrue(String.valueOf(syn.getMessage()).contains("expected"), syn.getMessage());
            assertTrue(String.valueOf(syn.getMessage()).contains("unknown.js:1:5"), syn.getMessage());

            //TypeError 归一化成 QuickJSException，Java 侧 catch 得住
            assertThrows(QuickJSException.class, () -> ctx.evaluate("null.x"));
            assertEquals("TypeError", ctx.evaluate("(function(){try{null.x}catch(e){return e.constructor.name}})()"));
        }
    }

    @Test
    public void testMemoryLimitIsEnforcedAndRecoverable() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            ctx.runGC();
            //配额是"整个 runtime 的 malloc 上限"，不是单次分配上限，
            //所以必须按当前基线来算：Windows/Linux 上新建上下文的基线差别不小。
            final long base = ctx.getMemoryUsedSize();
            ctx.setMemoryLimit((int) (base + 512 * 1024));
            assertEquals(65536, ctx.evaluate("'x'.repeat(64*1024).length"));

            QuickJSException e = assertThrows(QuickJSException.class,
                    () -> ctx.evaluate("'x'.repeat(4*1024*1024).length"));
            assertTrue(String.valueOf(e.getMessage()).contains("out of memory"), e.getMessage());

            //超配额之后上下文必须还能用，不能一路烂到底
            assertEquals(2, ctx.evaluate("1+1"));
            assertEquals(65536, ctx.evaluate("'y'.repeat(64*1024).length"));
        }
    }

    @Test
    public void testDeepRecursionThrowsInsteadOfKillingJvm() {
        //这是 P0 回归：quickjs-ng 默认给 JS 的栈预算是 1M，而 JVM 每个线程的栈默认也只有 1M
        //（Java 主线程同样受 -Xss 限制，ulimit 的 8M 用不上）。预算等于真实栈时，
        //递归到 600 层左右会先撞上 guard page：整个进程 SIGSEGV，RangeError 根本来不及抛。
        //createRuntime 现在按当前线程栈大小收敛预算，深递归必须变成可 catch 的异常。
        //注意别在这里断言"200 层一定过"：能跑多深取决于构建产物每帧占多少栈，
        //同一份代码 Linux Release 能到 400 层、ASan 版 200 层就拒、Windows dll 更低。
        try (QuickJSContext ctx = QuickJSContext.create()) {
            assertEquals(20, ctx.evaluate("(function r(n){return n<=0?0:1+r(n-1)})(20)"));

            QuickJSException deep = assertThrows(QuickJSException.class, () ->
                    ctx.evaluate("(function r(n){return n<=0?0:1+r(n-1)})(500000)"));
            assertTrue(String.valueOf(deep.getMessage()).contains("stack"), deep.getMessage());

            //无界递归同样得抛得住
            QuickJSException unbounded = assertThrows(QuickJSException.class,
                    () -> ctx.evaluate("function ff(){return ff()+1}; ff()"));
            assertTrue(String.valueOf(unbounded.getMessage()).contains("stack"), unbounded.getMessage());

            //爆栈之后上下文仍然可用
            assertEquals(4, ctx.evaluate("2+2"));
        }
    }

    @Test
    public void testSetMaxStackSizeOverridesBudget() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //显式调小预算会立刻生效。别在这里断言"100 层一定过"：能跑多深完全取决于
            //每帧占多少栈，同一份代码 Linux Release 400 层没问题、ASan 版 200 层就拒。
            ctx.setMaxStackSize(64 * 1024);
            assertEquals(3, ctx.evaluate("(function r(n){return n<=0?0:1+r(n-1)})(3)"));
            assertThrows(QuickJSException.class, () ->
                    ctx.evaluate("(function r(n){return n<=0?0:1+r(n-1)})(200000)"));
            //注意：0 = 不设限，JS 递归会直接吃满真实线程栈并把进程打死，这里只用极浅的递归验证 API 可用
            ctx.setMaxStackSize(0);
            assertEquals(3, ctx.evaluate("(function r(n){return n<=0?0:1+r(n-1)})(3)"));
        }
    }

    @Test
    public void testNestedModuleImports() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            final AtomicReference<String> requested = new AtomicReference<>();
            ctx.setModuleLoader(new QuickJSContext.DefaultModuleLoader() {
                @Override
                public String normalizeName(String baseName, String moduleName) {
                    return moduleName;
                }

                @Override
                public String getStringCode(String name) {
                    requested.set(name);
                    if (name.equals("a.js")) {
                        return "export const A=1; import * as b from 'b.js'; export const SUM=b.B+1;";
                    }
                    if (name.equals("b.js")) {
                        return "export const B=41;";
                    }
                    return null;
                }
            });

            JSObject ns = (JSObject) ctx.evaluateModule("import {A,SUM} from 'a.js'; export const R=A+':'+SUM;", "main.js");
            assertEquals("1:42", ns.getProperty("R"));
            assertEquals("b.js", requested.get());
            assertEquals("{\"R\":\"1:42\"}", ns.stringify());
            //模块命名空间对象 [[Prototype]] 为 null，取不到 toString：早先这里是 NPE
            assertNotNull(ns.toString());
            ns.release();

            //loader 返回 null 必须报成清晰的 JS 异常，且上下文不受影响
            QuickJSException e = assertThrows(QuickJSException.class,
                    () -> ctx.evaluateModule("import {X} from 'q.js'; X", "m3.js"));
            assertTrue(String.valueOf(e.getMessage()).contains("string code was null"), e.getMessage());
            assertEquals(2, ctx.evaluate("1+1"));
        }
    }

    @Test
    public void testModuleErrorsAreReported() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            ctx.setModuleLoader(new QuickJSContext.DefaultModuleLoader() {
                @Override
                public String normalizeName(String baseName, String moduleName) {
                    return moduleName;
                }

                @Override
                public String getStringCode(String name) {
                    if (name.equals("ok.js")) {
                        return "export const V=7;";
                    }
                    return "export const W=" + name.length() + ";";
                }
            });

            JSObject ns = (JSObject) ctx.evaluateModule("import {V} from 'ok.js'; export const OUT=V;", "e1.js");
            assertEquals(7, ns.getProperty("OUT"));
            ns.release();

            //导出名对不上
            QuickJSException missing = assertThrows(QuickJSException.class,
                    () -> ctx.evaluateModule("import {X} from 'ok.js';", "m2.js"));
            assertTrue(String.valueOf(missing.getMessage()).contains("Could not find export"), missing.getMessage());

            //模块内抛错
            QuickJSException thrown = assertThrows(QuickJSException.class, () -> ctx.evaluateModule(
                    "throw new Error('in module');", "boom.js"));
            assertTrue(String.valueOf(thrown.getMessage()).contains("in module"), thrown.getMessage());

            //模块语法错，带文件名
            QuickJSException syn = assertThrows(QuickJSException.class, () -> ctx.evaluateModule("import {", "bad.js"));
            assertTrue(String.valueOf(syn.getMessage()).contains("bad.js"), syn.getMessage());
        }
    }

    @Test
    public void testModuleWithoutLoaderIsRejected() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //没有 loader 的上下文里 import 必须报成异常；setModuleLoader(null) 也有参数校验
            QuickJSException e = assertThrows(QuickJSException.class,
                    () -> ctx.evaluateModule("import {Z} from 'x.js';", "noload.js"));
            assertTrue(String.valueOf(e.getMessage()).contains("ModuleLoader"), e.getMessage());
            assertThrows(QuickJSException.class, () -> ctx.setModuleLoader(null));
            assertEquals(2, ctx.evaluate("1+1"));
        }
    }

    @Test
    public void testBytecodeRoundTripAndBadInput() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            byte[] bc = ctx.compile("1+1");
            assertNotNull(bc);
            assertTrue(bc.length > 8);
            assertEquals(2, ctx.execute(bc));

            //坏字节码全部归成可 catch 的异常，且之后上下文照常可用
            assertThrows(NullPointerException.class, () -> ctx.execute(null));
            assertThrows(QuickJSException.class, () -> ctx.execute(new byte[0]));
            assertThrows(QuickJSException.class, () -> ctx.execute(Arrays.copyOf(bc, bc.length / 2)));
            assertThrows(QuickJSException.class, () -> ctx.execute("not bytecode at all".getBytes()));
            assertEquals(6, ctx.evaluate("3+3"));

            //模块字节码
            byte[] mbc = ctx.compileModule("export const M=1;", "mm.js");
            assertNotNull(mbc);
        }
    }

    @Test
    public void testNullPrototypeObjects() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //Object.create(null) 与模块命名空间一样没有 toString，早先 toString() 直接 NPE
            JSObject bare = (JSObject) ctx.evaluate("Object.create(null)");
            assertNotNull(bare.toString());
            bare.setProperty("a", 1);
            assertEquals(1, bare.getProperty("a"));
            assertEquals("{\"a\":1}", bare.stringify());
            bare.release();

            JSObject withProto = (JSObject) ctx.evaluate("({a:1})");
            assertTrue(withProto.toString().contains("[object Object]"), withProto.toString());
            withProto.release();

            JSObject fn = (JSObject) ctx.evaluate("(function foo(){})");
            assertTrue(fn.toString().contains("function foo"), fn.toString());
            fn.release();
        }
    }

    @Test
    public void testPromiseRejectionSurfaces() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            //没人 catch 的 rejection 在同一次 evaluate 结束时就要冒到 Java 侧，不能静默吞掉
            QuickJSException e = assertThrows(QuickJSException.class,
                    () -> ctx.evaluate("Promise.reject(new Error('dangling'))"));
            assertTrue(String.valueOf(e.getMessage()).contains("UnhandledPromiseRejectionException"), e.getMessage());
            assertTrue(String.valueOf(e.getMessage()).contains("dangling"), e.getMessage());

            //报过一次就该清掉，不能每次都重复报
            assertEquals(2, ctx.evaluate("1+1"));
            assertEquals(4, ctx.evaluate("2+2"));

            //catch 过的不算未处理 rejection
            ctx.evaluate("Promise.reject(new Error('handled')).catch(function(){return 1})");
            assertEquals(2, ctx.evaluate("1+1"));
        }
    }

    @Test
    public void testConsoleRedirect() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            final Map<String, String> seen = new HashMap<>();
            //Console 只有 debug/info/warn/error 四档，JS 侧的 'log' 归到 debug
            ctx.setConsole(new QuickJSContext.Console() {
                @Override
                public void debug(String info) {
                    seen.put("debug", info);
                }

                @Override
                public void info(String info) {
                    seen.put("info", info);
                }

                @Override
                public void warn(String info) {
                    seen.put("warn", info);
                }

                @Override
                public void error(String info) {
                    seen.put("error", info);
                }
            });
            ctx.evaluate("console.print('log','hello')");
            assertEquals("hello", seen.get("debug"));
            ctx.evaluate("console.print('info','note')");
            ctx.evaluate("console.print('warn','care')");
            ctx.evaluate("console.print('error','bad')");
            assertEquals("note", seen.get("info"));
            assertEquals("care", seen.get("warn"));
            assertEquals("bad", seen.get("error"));
            //stdout 只被 console 用到，脚本自己也能直接写
            ctx.evaluate("console.print('log', 42)");
            assertEquals("42", seen.get("debug"));
        }
    }

    @Test
    public void testLifecycleGuards() {
        QuickJSContext ctx = QuickJSContext.create();
        JSObject obj = (JSObject) ctx.evaluate("({a:1})");
        assertTrue(ctx.isLiveObject(obj));
        //getCurrentThreadId 返回的就是创建者线程的 id（本模块兼容 11，不用 Thread.threadId()）
        assertTrue(ctx.getCurrentThreadId() > 0);
        obj.release();
        //release 之后再问存活必须返回 false：早先这里会把可能已被回收的 pointer 直接交给
        //native 的 JS_IsLiveObject（它读 p->free_mark，quickjs.c:7499），ASan 实测 heap-use-after-free
        assertFalse(ctx.isLiveObject(obj));
        assertFalse(obj.isAlive());

        assertFalse(ctx.closed());
        ctx.close();
        assertTrue(ctx.closed());

        //关掉的上下文上任何调用都必须明确拒绝，不能用野指针
        QuickJSException e = assertThrows(QuickJSException.class, () -> ctx.evaluate("1"));
        assertTrue(String.valueOf(e.getMessage()).contains("destroyed"), e.getMessage());
        assertThrows(QuickJSException.class, ctx::getGlobalObject);
        assertThrows(QuickJSException.class, ctx::close);
        //runtime 级入口以前是静默拿野指针（ASan: runGC 命中 JS_FreeRuntime 释放的内存），
        //现在必须和 evaluate 一样明确拒绝
        assertThrows(QuickJSException.class, ctx::runGC);
        assertThrows(QuickJSException.class, ctx::getMemoryUsedSize);
        assertThrows(QuickJSException.class, () -> ctx.setMaxStackSize(1 << 20));
        assertThrows(QuickJSException.class, () -> ctx.setMemoryLimit(1 << 20));
        assertThrows(QuickJSException.class, () -> ctx.setGCThreshold(1 << 20));
        assertThrows(QuickJSException.class, ctx::dumpMemoryUsage);
    }

    @Test
    public void testDumpMemoryUsageReportsOpenFailureClearly() throws Exception {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            // 传一个“存在但打不开”的目标（目录），让 native 走 fopen 失败分支：
            // 修复前这里抛 NullPointerException("fail to open file")，类型错且无文件名；
            // 修复后应抛 QuickJSException 且消息带上文件名与系统原因。
            Path dir = Files.createTempDirectory("qjs_dump");
            QuickJSException ex = assertThrows(QuickJSException.class,
                    () -> ctx.dumpMemoryUsage(dir.toFile()));
            String msg = String.valueOf(ex.getMessage());
            assertTrue(msg.contains(dir.toAbsolutePath().toString()), msg);
            assertTrue(msg.contains("Failed to open file"), msg);
        }
    }

    @Test
    public void testRefCountObservables() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSObject o = (JSObject) ctx.evaluate("({a:1})");
            assertEquals(1, o.getRefCount());
            o.hold();
            assertEquals(2, o.getRefCount());
            o.release();
            assertEquals(1, o.getRefCount());
            assertTrue(o.isAlive());
            o.release();
            assertTrue(o.isRefCountZero());
            assertFalse(o.isAlive());
            //计数归零之后再访问必须明确报错，而不是操作野指针
            assertThrows(QuickJSException.class, () -> o.getProperty("a"));
            assertThrows(QuickJSException.class, () -> o.setProperty("a", 1));
        }
    }

    @Test
    public void testKnownBigIntTruncation() {
        //已知缺陷（锁定现状）：BigInt 只按 int64 取，超出范围会按 2^64 截断而不是报错。
        //真要修的话得改成抛错或返回 String，届时把这几条断言一起换掉。
        try (QuickJSContext ctx = QuickJSContext.create()) {
            assertEquals(Long.MIN_VALUE, ctx.evaluate("2n**63n"));
            assertEquals(0L, ctx.evaluate("2n**64n"));
            assertEquals(7L, ctx.evaluate("BigInt(3)+BigInt(4)"));
            assertEquals(-1L, ctx.evaluate("0n-1n"));
        }
    }

    @Test
    public void testToArrayAndToMapFilterSemantics() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            final MapFilter drop = (key, pointer, extra) -> key.equals(String.valueOf(extra));
            JSArray a = (JSArray) ctx.evaluate("[{keep:1,drop:2},{keep:3,drop:4}]");
            //MapCreator 不能传 null（convertToMap 里直接 .get()，传 null 就是 NPE）
            ArrayList<Object> filtered = a.toArray(drop, "drop", HashMap::new);
            assertEquals(2, filtered.size());
            assertTrue(filtered.get(0) instanceof Map);
            a.release();

            JSObject o = (JSObject) ctx.evaluate("({a:{x:1,y:2},b:[1,{x:3,y:4}]})");
            HashMap<String, Object> all = o.toMap();
            Map<?, ?> nested = (Map<?, ?>) all.get("a");
            assertEquals(1, nested.get("x"));
            assertEquals(4, ((Map<?, ?>) ((ArrayList<?>) all.get("b")).get(1)).get("y"));

            //自定义 MapCreator：允许换成 LinkedHashMap 之类的实现
            Map<String, Object> custom = o.toMap((key, pointer, extra) -> false, null, HashMap::new);
            assertEquals(2, custom.size());
            o.release();
        }
    }

    @Test
    public void testFunctionCallAndVoid() {
        try (QuickJSContext ctx = QuickJSContext.create()) {
            JSObject g = ctx.getGlobalObject();
            g.setProperty("add3", (JSCallFunction) args -> {
                int sum = 0;
                for (Object a : args) {
                    sum += ((Number) a).intValue();
                }
                return sum;
            });
            JSFunction add3 = g.getJSFunction("add3");
            assertEquals(6, add3.call(1, 2, 3));
            add3.callVoid();
            add3.release();

            JSFunction jsFn = (JSFunction) ctx.evaluate("(function mul(a,b){return a*b})");
            assertEquals(12, jsFn.call(3, 4));
            jsFn.release();

            //函数对象上的 this 语义：JS 函数被 Java 直接 call 时 this 是 undefined
            JSFunction method = (JSFunction) ctx.evaluate("({v:9, get(){return this.v}}).get");
            assertEquals(9, ctx.evaluate("({v:9, get(){return this.v}}).get()"));
            method.release();
        }
    }
}
