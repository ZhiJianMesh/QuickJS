package cn.net.zhijian.quickjs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

public class JSFeaturesTest extends UnitTestBase {
    @Test
    public void testBase() {
        String js = "var a='a',b='b';a+b;";
        Object s = getContext().evaluate(js);
        assertTrue(s.equals("ab"));

        js = "var a=1,b=2;a+b;";
        Object i = getContext().evaluate(js);
        assertTrue(i.equals(3));

        js = "var a='a',b=`b\nc`;a+b;";
        s = getContext().evaluate(js);
        assertTrue(s.equals("ab\nc"));
    }
    
    @Test
    public void testBranch() {
        String js = "1>0?'test1':'test2'";
        Object s = getContext().evaluate(js);
        assertTrue(s.equals("test1"));
        js = "1<0?'test1':'test2'";
        s = getContext().evaluate(js);
        assertTrue(s.equals("test2"));
    }

    @Test
    public void testPushJoin() {
        String js = "var sql=['a'];sql.push('b','c','de');sql.join('')";
        Object s = getContext().evaluate(js);
        assertTrue(s.equals("abcde"));
    }

    @Test
    public void testFunction() {
        String js = "(function(){return `select * from t where a='a' and b=1`})()";
        Object s = getContext().evaluate(js);
        assertTrue(s.equals("select * from t where a='a' and b=1"));
    }
    
    @Test
    public void testInnerFunction() {
        String js = "function testFunc(){return true;}"
                + "if(testFunc()) 'a';"
                + "else 'b'";
        Object hr = getContext().evaluate(js);
        assertEquals(hr, "a");
        js = "function testFunc(){return false;}"
            + "if(testFunc()) 1;"
            + "else 2";
        hr = getContext().evaluate(js);
        assertEquals(hr, 2);
    }
    
    @Test
    public void testArrayAndObjectOperations() {
        Object length = getContext().evaluate("var arr = [1, 2, 3]; arr.push(4);arr.length");
        assertEquals(length, 4);
        Object name = getContext().evaluate("var obj = { name: 'test', version: 1 };obj.name");
        assertEquals(name, "test");
    }

    @Test
    public void testAutoRunJsFunc() {
        String js = "(function() {\n"
                + "var dbs=[{\"name\":\"192.168.1.117:8523\",\"ut\":1763982064163,\"val\":\"[{\\\"no\\\":1,\\\"slaves\\\":\\\"192.168.1.6:8523\\\",\\\"level\\\":1,\\\"shardEnd\\\":16382,\\\"shardStart\\\":0,\\\"type\\\":\\\"SQLITE\\\"},{\\\"no\\\":1,\\\"slaves\\\":\\\"192.168.1.6:8523\\\",\\\"level\\\":1,\\\"shardEnd\\\":32767,\\\"shardStart\\\":16383,\\\"type\\\":\\\"SQLITE\\\"}]\"},{\"name\":\"192.168.1.6:8523\",\"ut\":1763982064163,\"val\":\"[{\\\"no\\\":0,\\\"slaves\\\":\\\"\\\",\\\"level\\\":0,\\\"shardEnd\\\":32767,\\\"shardStart\\\":0,\\\"type\\\":\\\"SQLITE\\\"}]\"}];\n"
                + "var nodes,s;\n"
                + "var shardings={};\n"
                + "for(var d of dbs) { //addr(name)->cfg(val)\n"
                + "  nodes=JSON.parse(d.val);\n"
                + "  for(var n of nodes) {\n"
                + "     if(!(s = shardings[n.no])){\n"
                + "         s=new Array(32768).fill(0);\n"
                + "         shardings[n.no]=s;\n"
                + "     }\n"
                + "     for(var i=n.shardStart;i<=n.shardEnd;i++) {\n"
                + "         if(s[i]!=0) {//重叠分片\n"
                + "             return {code:1, info:'duplicate sharding, db '+d.name+','+n.no+'('+n.shardStart+'-'+n.shardEnd+')'};\n"
                + "         }\n"
                + "         s[i]=1;\n"
                + "     }\n"
                + "  }\n"
                + "}\n"
                + "\n"
                + "for(var no in shardings) {//dbNo->sharding\n"
                + "   for(var s of shardings[no]) {\n"
                + "       if(s==0) { //未覆盖分片\n"
                + "           return {code:1, info:'empty sharding,db '+no+'('+s+')'};\n"
                + "       }\n"
                + "   }\n"
                + "}\n"
                + "return {code:0,info:'Success'};\n"
                + "})()";
        Object hr = getContext().evaluate(js);
        assertTrue(hr != null && hr instanceof JSObject);
        JSObject m = (JSObject)hr;
        int code = m.getInteger("code");
        String info = m.getString("info");
        System.out.println("info:" + info);
        assertEquals(code, 0);
    }
    
    @Test
    public void testComplexAutoRunWithJavaObj() {
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
        Object hr = getContext().evaluate(js);
        assertEquals("{\"code\":0,\"info\":\"Success\",\"data:\":{}}", hr);
    }
    
    private static int parseInt(Object o) {
        if(o == null) return Integer.MIN_VALUE;
        if(o instanceof Number) return ((Number)o).intValue();
        return Integer.parseInt(o.toString());
    }
    
    private static String parseString(Object o) {
        if(o == null) return null;
        if(o instanceof String) return (String)o;
        return o.toString();
    }
    
    @Test
    public void testNewFunc() {
        String js = "var f=new Function('s',`if(s<=100)return 0; if(s>100)return s*0.1;`);"
                + "f(90)+'/'+f(500)";
        Object s = getContext().evaluate(js);
        System.out.println("new Funnction():" + s);
        assertEquals(s, "0/50");
    }
    
    @Test
    public void testGlobalConstDef() {
        Object r = getContext().evaluate("RetCode.OK");
        assertEquals(r, 0);
    }
    
    @Test
    public void testSimpleJsSpeed() {
        String js;
        int N=1000;
        Object hr = null;
        long start = System.currentTimeMillis();
        for(int i = 0; i < N; i++) {
            js = "(function(){return {code:RetCode.OK,info:'Success'}})()";
            hr = getContext().evaluate(js);
        }
        long end = System.currentTimeMillis();
        long interval = end > start ? end - start : 1;
        
        System.out.println("speed1(auto executed):" + (1000L*N/interval));
        
        assertTrue(hr != null && hr instanceof QuickJSObject);
        Map<String, Object> m = ((QuickJSObject)hr).toMap();
        int code = parseInt(m.get("code"));
        String info = parseString(m.get("info"));
        System.out.println("info:" + info);
        assertEquals(code, 0);
    }
    
    @Test
    public void testJsRTCSpeed() { //运行时编译
        String js = "var dt=new Date();"
                + " dt.getFullYear().toString().padStart(4, '0')"
                + " +dt.getMonth().toString().padStart(2, '0')"
                + " +dt.getDate().toString().padStart(2, '0')";
        byte[] bJs = getContext().compile(js);
        assertNotNull(bJs);
        
        String dt = parseString(getContext().execute(bJs));
        assertTrue(dt.length() == 8);
        
        int N=1000;
        long start = System.currentTimeMillis();
        for(int i = 0; i < N; i++) {
            getContext().evaluate(js);
        }
        long end = System.currentTimeMillis();
        long useTime1 = end > start ? end - start : 1;
        System.out.println("speed1(auto executed):" + (1000L*N/useTime1));
        
        start = System.currentTimeMillis();
        for(int i = 0; i < N; i++) {
            getContext().execute(bJs);
        }
        end = System.currentTimeMillis();
        long useTime2 = end > start ? end - start : 1;
        System.out.println("speed2(auto executed):" + (1000L*N/useTime2));
        //字节码和源码两条路径都要给出8位日期；谁快谁慢看机器和JVM预热，不做断言
        assertEquals(8, parseString(getContext().evaluate(js)).length());
    }
    
    @Test
    public void testJsDateFmt() {
        String js = "var dt=new Date();"
                + " dt.getFullYear().toString().padStart(4, '0')"
                + " +dt.getMonth().toString().padStart(2, '0')"
                + " +dt.getDate().toString().padStart(2, '0')";
        String dt = parseString(getContext().evaluate(js));
        System.out.println("dt:" + dt);
        assertTrue(dt.length() == 8);
        js = "var dt=new Date();var t=dt.getTime()+86400000;dt.setTime(t);"
                + " dt.getFullYear().toString().padStart(4, '0')"
                + " +dt.getMonth().toString().padStart(2, '0')"
                + " +dt.getDate().toString().padStart(2, '0')";
        String dt1 = parseString(getContext().evaluate(js));
        System.out.println("dt1:" + dt1);
        assertTrue(dt1.compareTo(dt) > 0);
    }
    
    @Test
    public void testJsDateTimeZone() {
        Calendar cal = Calendar.getInstance();
        String js = "var dt=new Date();dt.getHours();";
        int hour = parseInt(getContext().evaluate(js));
        System.out.println("js hour:" + hour + ", java hour:" + cal.get(Calendar.HOUR_OF_DAY)
            + ",java tz:" + cal.getTimeZone().getRawOffset() + "," + TimeZone.getDefault().getID());
        assertEquals(hour, cal.get(Calendar.HOUR_OF_DAY));
        
        js = "var dt=new Date();"
            + "var hour=dt.getHours();"
            + "var t=dt.getTime()+dt.getTimezoneOffset()*60000;"//0区
            + "dt.setTime(t);"
            + "hour - dt.getHours()";
        hour = parseInt(getContext().evaluate(js));
        assertEquals(hour, cal.getTimeZone().getRawOffset() / 3600000);
    }
    
    @Test
    public void testJsonStrJoinAndParse() {
        String js = "var json=['{\"salaries\":['];\n"
                + "  var tasktimes={\"1\":0.4,\"2\":0.6}; //pid->ratio\n"
                + "  var salary=10000;\n"
                + "  var i=0;\n"
                + "  for(var t in tasktimes) {\n"
                + "      if(i>0) json.push(',');\n"
                + "      json.push('{\"pid\\\":',t,',\"val\":', (tasktimes[t]*salary).toFixed(2), '}')\n"
                + "      i++;"
                + "  }\n"
                + "  json.push(']}');\n"
                + "  json.join('');";
        String json = parseString(getContext().evaluate(js));
        System.out.println("js json:" + json);
        Object o = getContext().parse(json);
        assertTrue(o != null && o instanceof QuickJSObject);
        QuickJSObject m = (QuickJSObject)o;
        assertTrue(m.containsProperty("salaries"));
        m.release(); //JSObject should be released as soon as possible
    }
    
    @Test
    public void testJsonParse() {
        String js = "var json=`{a:1,b:2}`;"
                + "  json";
        Object s = getContext().evaluate(js);
        assertEquals(s , "{a:1,b:2}");
        
        js = "var json=`{\"a\":1,\"b\":2}`;"
                + "  var map=JSON.parse(json);"
                + "  map.a";
        Object v = getContext().evaluate(js);
        assertEquals(v, 1L);
        
        js = "var json=`{a:1,\"b\":2}`;" //字段名称必须加双引号
            + "var map=JSON.parse(json);"
            + "map.a";
        try {
            v = getContext().evaluate(js);
            fail("can't parse an invalid json string");
        } catch(Exception e) {
        }
        
        js = "var json=`[{\"a\":\"abc\",\"b\":2},{\"a\":3,\"b\":5}]`;"
                + "  var arr=JSON.parse(json);"
                + "  arr[0].a";
        v = getContext().evaluate(js);
        assertEquals(v, "abc");

        js = "var json=`[{\"no\":1,\"slaves\":\"192.168.1.6:8523\",\"level\":1,\"shardEnd\":16382,\"shardStart\":0,\"type\":\"SQLITE\"},"
                +"{\"no\":2,\"slaves\":\"192.168.1.6:8523\",\"level\":1,\"shardEnd\":32767,\"shardStart\":16383,\"type\":\"SQLITE\"}]`;"
                + "  var arr=JSON.parse(json);"
                + "  arr[0].no+arr[1].no";
        v = getContext().evaluate(js);
        assertEquals(v, 3L);
    }
    
    @Test
    public void testPromise() {
        String js = "var v = new Promise((resolve) => {resolve(1)}); v";
        Object o = getContext().evaluate(js);
        assertEquals(1, o);
        
        js = "var v = new Promise((resolve,reject) => {reject('err1')}); v";
        try {
            o = getContext().evaluate(js);
        } catch(Exception e) {
            assertTrue(e.getMessage().indexOf("err1") >= 0);
        }
    }
    
    @Test
    public void testEvaluateJsModule() {
        Object o = getContext().evaluateModule(
                "export var name = 'Jack';\n" +
                "export var age = 18;\n" +
                "export function report() { return name + ':' + age};", 
                "a.js");

        assertTrue(o instanceof JSObject);
        JSObject module = (JSObject) o;
        assertEquals("Jack", module.getProperty("name"));
        assertEquals(18, ((Number)module.getProperty("age")).intValue());
        JSFunction f = module.getJSFunction("report");
        assertTrue(f != null);
        String result = (String) f.call(); // 调用函数
        f.release();
        assertEquals(result, "Jack:18");
        module.release();
    }
    
    @Test
    public void testEvaluateJsModuleToGlobal() {
        getContext().evaluateModuleToGlobal(
                "export var name = 'Jack';\n" +
                "export var student = {name:'Jack',age:18};\n" +
                "export var age = 18;\n" +
                "export function report() { return name + ':' + age};");
        
        Object o = getContext().evaluate("name");
        assertEquals("Jack", o);
        o = getContext().evaluate("age");
        assertEquals(18, o);
        o = getContext().evaluate("report()");
        assertEquals("Jack:18", o);

        o = getContext().evaluate("student.name");
        assertEquals("Jack", o);
        o = getContext().evaluate("student.age");
        assertEquals(18, o);
    }

    @Test
    public void testContainsPropertyDoesNotLeak() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            ctx.evaluate("globalThis.o = {b:1};");
            JSObject g = ctx.getGlobalObject();
            int before = ctx.getObjectRecords().size();
            for (int i = 0; i < 500; i++) {
                assertTrue(g.containsProperty("o"));
            }
            assertEquals(before, ctx.getObjectRecords().size());
            assertFalse(g.containsProperty("not_exists"));
        } finally {
            ctx.close();
        }
    }

    @Test
    public void testTypedGettersDoNotLeak() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            ctx.evaluate("globalThis.obj = {child:{a:1}, arr:[1,2]};");
            JSObject obj = ctx.getGlobalObject().getJSObject("obj");
            int before = ctx.getObjectRecords().size();
            //属性值是 JS 对象时，这些取值方法只是判断类型/转换，用完必须释放
            assertNotNull(obj.getString("child"));   //JS 对象走 toString()，用完同样要释放
            assertNull(obj.getInteger("child"));
            assertFalse(obj.getBoolean("child"));
            assertNull(obj.getDouble("arr"));
            assertNull(obj.getLong("arr"));
            assertNull(obj.getBytes("child"));
            assertNull(obj.getJSArray("child"));
            assertNull(obj.getJSFunction("child"));
            assertEquals(before, ctx.getObjectRecords().size());
            obj.release();
        } finally {
            ctx.close();
        }
    }

    @Test
    public void testConsoleWithAnyArguments() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            final StringBuilder out = new StringBuilder();
            ctx.setConsole(new QuickJSContext.Console() {
                @Override public void debug(String info) { out.append("D:").append(info).append('|'); }
                @Override public void info(String info) { out.append("I:").append(info).append('|'); }
                @Override public void warn(String info) { out.append("W:").append(info).append('|'); }
                @Override public void error(String info) { out.append("E:").append(info).append('|'); }
            });

            ctx.evaluate("console.log();console.log(12345);console.log('txt');console.info('i');console.warn('w');console.error('e');");
            String s = out.toString();
            assertTrue(s.contains("D:|"), s);        //无参调用不能抛异常
            assertTrue(s.contains("D:12345|"), s);   //非字符串参数不能被砍掉首尾字符
            assertTrue(s.contains("D:txt|"), s);
            assertTrue(s.contains("I:i|"), s);
            assertTrue(s.contains("W:w|"), s);
            assertTrue(s.contains("E:e|"), s);
        } finally {
            ctx.close();
        }
    }

    @Test
    public void testThrowJSExceptionKeepsMessage() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            final String msg = "cannot read property \"data\" of undefined";
            //旧实现把消息拼进 throw "..."，消息里的引号先变成 JS 语法错误，真实消息被吞
            QuickJSException ex = assertThrows(QuickJSException.class, () -> ctx.throwJSException(msg));
            assertTrue(String.valueOf(ex.getMessage()).contains(msg), String.valueOf(ex.getMessage()));

            //从 Java 回调里抛给 JS 时同样要保住消息
            ctx.getGlobalObject().setProperty("boom", (JSCallFunction) args -> {
                ctx.throwJSException(msg);
                return null;
            });
            QuickJSException ex2 = assertThrows(QuickJSException.class, () -> ctx.evaluate("boom()"));
            assertTrue(String.valueOf(ex2.getMessage()).contains(msg), String.valueOf(ex2.getMessage()));

            //抛完不能在全局留下临时变量
            assertEquals("undefined", ctx.evaluate("typeof __quickjsThrowMessage"));
        } finally {
            ctx.close();
        }
    }

    @Test
    public void testRopeStringIsConverted() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            //quickjs-ng 0.17 起，超过 JS_STRING_ROPE_SHORT_LEN(512) 的拼接结果是 rope，
            //和 JS_TAG_STRING 是不同的 tag；漏掉它长字符串会直接变成 null
            Object longConcat = ctx.evaluate("'a'.repeat(1000) + 'b'.repeat(1000)");
            assertTrue(longConcat instanceof String, String.valueOf(longConcat));
            assertEquals(2000, ((String) longConcat).length());
            assertEquals("aabb", ((String) longConcat).substring(998, 1002));

            //短的仍然是普通 string，行为不能变
            assertEquals("aaaaaaaaaabbbbbbbbbb", ctx.evaluate("'a'.repeat(10) + 'b'.repeat(10)"));
        } finally {
            ctx.close();
        }
    }

    @Test
    public void testShortBigIntIsConverted() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            //int32 范围内的 BigInt 是 JS_TAG_SHORT_BIG_INT 立即数，不处理会落到 default 变成 null
            assertEquals(5L, ctx.evaluate("5n"));
            assertEquals(0L, ctx.evaluate("0n"));
            assertEquals(-7L, ctx.evaluate("-7n"));
            assertEquals(7L, ctx.evaluate("BigInt(3)+BigInt(4)"));
            //超出 int32 的走堆上 JSBigInt
            assertEquals(4611686018427387904L, ctx.evaluate("(2n ** 62n)"));
        } finally {
            ctx.close();
        }
    }

    @Test
    public void testInfinityStaysDouble() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            //Infinity 强转 long 是有符号溢出 UB，实测会变成 Long.MIN_VALUE
            assertEquals(Double.POSITIVE_INFINITY, ctx.evaluate("1/0"));
            assertEquals(Double.NEGATIVE_INFINITY, ctx.evaluate("-1/0"));
            assertEquals(1.0E19, ctx.evaluate("1e19"));
            //范围内的整数仍按 Long 返回，这条是既有行为
            assertEquals(1000000000000000000L, ctx.evaluate("1e18"));
            assertEquals(1.5D, ctx.evaluate("1.5"));
        } finally {
            ctx.close();
        }
    }

    @Test
    public void testSymbolDoesNotLeak() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            for (int i = 0; i < 1000; i++) {
                assertNull(ctx.evaluate("Symbol(Math.random())")); //symbol 转不出 Java 值
            }
            ctx.runGC();
            long before = ctx.getMemoryUsedSize();
            for (int i = 0; i < 10000; i++) {
                ctx.evaluate("Symbol(Math.random())");
            }
            ctx.runGC();
            long delta = ctx.getMemoryUsedSize() - before;
            //修复前 2 万次约泄漏 113 万字节，这里给足余量
            assertTrue(delta < 65536, "leaked " + delta + " bytes");
        } finally {
            ctx.close();
        }
    }

    @Test
    public void testJavaExceptionIsCatchableInJs() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            ctx.getGlobalObject().setProperty("javaBooms", (JSCallFunction) args -> {
                throw new IllegalStateException("java-side-boom");
            });
            //挂起的 Java 异常必须先换成 JS 异常，否则 JS 侧 try/catch 抓不到，
            //而且异常会漂到后面某次无关的 JNI 调用上
            assertEquals("caught-in-js", ctx.evaluate("try { javaBooms(); 'not-caught'; } catch (e) { 'caught-in-js'; }"));

            QuickJSException ex = assertThrows(QuickJSException.class, () -> ctx.evaluate("javaBooms()"));
            assertTrue(String.valueOf(ex.getMessage()).contains("java-side-boom"), String.valueOf(ex.getMessage()));
        } finally {
            ctx.close();
        }
    }

    @Test
    public void testHandledRejectionIsNotReported() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            //已经 catch 掉的 rejection 不该当成未处理异常抛出来
            assertEquals(1, ctx.evaluate("Promise.reject(new Error('SECOND')).catch(function(){ return 1; });"));

            //两个 rejection 只处理了一个：上报的必须是漏掉的那个，而不是已处理的那个
            QuickJSException ex = assertThrows(QuickJSException.class, () -> ctx.evaluate(
                    "var p1 = Promise.reject(new Error('FIRST'));var p2 = Promise.reject(new Error('SECOND'));p2.catch(function(){});"));
            String msg = String.valueOf(ex.getMessage());
            assertTrue(msg.contains("FIRST"), msg);
            assertFalse(msg.contains("SECOND"), msg);

            //取一个已被处理、但自身被拒绝的 promise：不能静默返回 null
            QuickJSException ex2 = assertThrows(QuickJSException.class, () -> ctx.evaluate(
                    "var q = Promise.reject(new Error('boom'));q.catch(function(){});q;"));
            assertTrue(String.valueOf(ex2.getMessage()).contains("boom"), String.valueOf(ex2.getMessage()));
        } finally {
            ctx.close();
        }
    }

    //值语义类（record / 重写了 hashCode 的数据类）的 hashCode 由字段决定，两个不同回调很容易撞成同一个值。
    //旧实现拿 hashCode 当回调标识，撞了就把 f1() 静默派到 f2 的 Java 对象上。
    private static class FixedHashCodeCallback implements JSCallFunction {
        private final String name;

        FixedHashCodeCallback(String name) {
            this.name = name;
        }

        @Override
        public int hashCode() {
            return 42;
        }

        @Override
        public Object call(Object...args) {
            return name;
        }
    }

    @Test
    public void testCallbacksSharingHashCodeAreNotMixedUp() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            JSObject global = ctx.getGlobalObject();
            global.setProperty("f1", new FixedHashCodeCallback("FIRST"));
            global.setProperty("f2", new FixedHashCodeCallback("SECOND"));
            assertEquals("FIRST", ctx.evaluate("f1()"));
            assertEquals("SECOND", ctx.evaluate("f2()"));

            //作为参数传进 JS 的回调同样要按对象区分
            JSFunction caller = (JSFunction) ctx.evaluate("(function(cb){ return cb(); })");
            assertEquals("THIRD", caller.call(new FixedHashCodeCallback("THIRD")));

            //GC 之后依然要能正确派发（两个回调都被 globalObject 强引用着）
            ctx.runGC();
            assertEquals("FIRST", ctx.evaluate("f1()"));
        } finally {
            ctx.close();
        }
    }

    @Test
    public void testNonAsciiStringSurvivesConversion() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            //JS 侧固定是 UTF-8，转 Java 时不能跟着 file.encoding 走，
            //否则在 -Dfile.encoding=GBK 的 JVM 上（zh-CN Windows + JDK<=17）非 ASCII 全变乱码
            String js = "\"中文测试\" + \"，éè\" + \"🎉\"";
            String s = (String) ctx.evaluate(js);
            assertEquals("中文测试，éè🎉", s);

            //反方向：Java 字符串进 JS 也要保持长度和内容
            JSObject global = ctx.getGlobalObject();
            global.setProperty("cn", s);
            assertEquals(9, ctx.evaluate("cn.length")); //5 + 1 + 2 + 1，🎉 是代理对
            assertEquals("中文测试，éè🎉", ctx.evaluate("cn"));
        } finally {
            ctx.close();
        }
    }

    @Test
    public void testDetachedArrayBufferThrows() {
        QuickJSContext ctx = QuickJSContext.create();
        try {
            //v0.17 起 JS 可以 ab.transfer() 把 buffer 变成 detached，
            //旧实现会静默返回空数组并把 JS 异常留在那儿，漂到后面某次无关调用上
            assertThrows(QuickJSException.class, () -> ctx.evaluate(
                    "(function(){ var ab = new ArrayBuffer(8); ab.transfer(); return ab; })()"));

            //异常转成 Java 异常后，context 必须还能继续正常使用
            assertEquals(2, ctx.evaluate("1+1"));

            Object buf = ctx.evaluate("new ArrayBuffer(4)");
            assertTrue(buf instanceof byte[]);
            assertEquals(4, ((byte[]) buf).length);
        } finally {
            ctx.close();
        }
    }
}
