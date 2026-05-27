package cn.net.zhijian.quickjs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertTrue(useTime2 < useTime1);
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
}
