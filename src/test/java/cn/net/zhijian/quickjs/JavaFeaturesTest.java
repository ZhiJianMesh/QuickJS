package cn.net.zhijian.quickjs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import cn.net.zhijian.quickjs.QuickJSContext.Console;

public class JavaFeaturesTest extends UnitTestBase {
    @Test
    public void testTryResourceLoad() {
        try(QuickJSContext context = QuickJSContext.create()) {
            if(context == null) {
                fail("Fail to get context");
                return;
            }
            // evaluating JavaScript
            Object r = context.evaluate("1 + 2;");
            assertTrue(r.equals(3));
        }
    }
    
    @Test
    void testMultipleContextIsolation() {
        QuickJSContext ctx2 = QuickJSContext.create();
        getContext().evaluate("var shared = 'context1';");
        ctx2.evaluate("var shared = 'context2';");
        
        Object r = getContext().evaluate("shared");
        assertEquals(r, "context1");
        r = ctx2.evaluate("shared");
        assertEquals(r, "context2");
        
        ctx2.destroy();
    }
    
    @Test
    void testJavaToJSPrimitiveConversion() {
        getContext().getGlobalObject().setProperty("longNum", Long.MAX_VALUE);
        Object r = getContext().evaluate("typeof longNum");
        assertEquals(r, "bigint");

        getContext().getGlobalObject().setProperty("smallNum", 100L);
        r = getContext().evaluate("typeof smallNum");
        assertEquals(r, "number");
    }
    
    @Test
    void testJavaFunctionBinding() {
        getContext().getGlobalObject().setProperty("javaAdd", (JSCallFunction) args -> {
            int a = ((Number) args[0]).intValue();
            int b = ((Number) args[1]).intValue();
            return a + b;
        });
        Object result = getContext().evaluate("javaAdd(5, 3)");
        assertEquals(result, 8);
    }
    
    @Test
    void testJsFunctionBinding() {
        getContext().evaluate("function test_aaa(a) {return 'aaa_'+a;}");
        JSFunction jf = getContext().getGlobalObject().getJSFunction("test_aaa");
        Object r = jf.call("test");
        jf.release();
        assertEquals(r, "aaa_test");
    }

    @Test
    void testJSArrayToJavaConversion() {
        Object arr = getContext().evaluate("[1, 'two', false]");
        assertTrue(arr instanceof JSArray);
        JSArray ja = (JSArray)arr;
        assertEquals(ja.get(0), 1);
        assertEquals(ja.get(1), "two");
        assertEquals(ja.get(2), false);
    }

    @Test
    void testVariableSetAndGet() {
        getContext().evaluate("var message = 'Hello, QuickJS!';");
        Object result = getContext().evaluate("message");
        assertEquals(result, "Hello, QuickJS!");
    }

    @Test
    void testGlobalObjectProperty() {
        JSObject global = getContext().getGlobalObject();
        global.setProperty("global_a", 100);
        Object result = getContext().evaluate("global_a + 1");
        assertEquals(result, 101);
        
        //不可以release，否则context.close时会异常，因为从global中无法获取format
        //global.release();
    }
  
    public static class FunctionCls {
        private int base = 0;
        public FunctionCls() {}
        public FunctionCls(int base) {this.base = base;} 
        
        @JavascriptMethod
        public int add(int a, int b) {
            return a + b + base;
        }
    }
    
    @Test
    void testExcuteJavaClass() {
        //getContext().setLogger(QuickJSContext.SystemConsole);
        JSObject global = getContext().getGlobalObject();
        
        global.setProperty("funcCls", FunctionCls.class);
        Object result = getContext().evaluate("funcCls.add(1,99)");
        assertEquals(result, 100);
        
        FunctionCls base = new FunctionCls(10);
        global.setJavaObject("funcCls1", base);
        result = getContext().evaluate("funcCls1.add(1,99)");
        assertEquals(result, 110);        
    }

    @Test
    void testEvaluateMathOpr() {
        Object result = getContext().evaluate("1 + 2 * 3");
        assertEquals(result, 7);
    }
    
    @Test
    void testJavaScriptException() {
        try {
            getContext().evaluate("abcd_ed+1");
            //assertTrue(ex instanceof RuntimeException);
            fail("There should be an exception");
        } catch(Exception e) {
            //e.printStackTrace();
        }
        
        //js中除0并不会发生异常，只会返回一个代表无限的值
        Object r = getContext().evaluate("var a=1.0/0.0;isFinite(a)");
        assertEquals(r, false);
        
        try {
            getContext().evaluate("throw new Error('test error');");
            fail("There should be an exception");
        } catch(Exception e) {
            //e.printStackTrace();
        }
    }
    
    @Test
    public void testConsoleOutput() {
        StringBuilder consoleMock = new StringBuilder();
        getContext().setConsole(new Console() {
            @Override
            public void debug(String info) {
                consoleMock.append("log:").append(info).append('\n');
            }

            @Override
            public void info(String info) {
                consoleMock.append("info:").append(info).append('\n');
            }

            @Override
            public void warn(String info) {
                consoleMock.append("warn:").append(info).append('\n');
            }

            @Override
            public void error(String info) {
                consoleMock.append("error:").append(info).append('\n');
            }            
        });
        getContext().evaluate("console.log(\"aB\");console.warn('b');"
                + "new Promise((resolve) => {console.error('c')})");
        assertEquals("log:aB\nwarn:b\nerror:c\n", consoleMock.toString());
    }    
    
    @Test
    void testReleaseJSObject() {
        Object obj = getContext().evaluate("({ name: 'temp' })");
        assertTrue(obj instanceof JSObject);
        
        JSObject jsObj = (JSObject) obj;
        
        jsObj.release();
        assertTrue(!jsObj.isAlive());
    }
    
    @Test
    public void testJsObject() {
        JSObject globalObj = getContext().getGlobalObject();
        JSObject obj = getContext().createJSObject();
        obj.setProperty("name", "QuickJS Wrapper");
        obj.setProperty("created", 2022);
        obj.setProperty("version", 1.1);
        obj.setProperty("signing_enabled", true);
        obj.setProperty("getUrl", (JSCallFunction) args -> {
            return "https://github.com";
        });
        globalObj.setProperty("testObj", obj);

        Object o = obj.getProperty("name");
        assertEquals(o, "QuickJS Wrapper");

        JSFunction fn = obj.getJSFunction("getUrl");
        Object url = fn.call();
        fn.release();
        assertEquals(url, "https://github.com");
        
        o = getContext().evaluate("testObj.name + ':' +  testObj.getUrl()");
        assertEquals(o, "QuickJS Wrapper:https://github.com");
        obj.release();
    }
    
    @Test
    public void testJsArray() {
        JSObject globalObj = getContext().getGlobalObject();
        JSArray array = getContext().createJSArray();
        array.set("a", 0);
        array.set(1, 1);
        array.set(2, 2);
        globalObj.setProperty("testArr", array);
        Object o = array.get(0);
        assertEquals(o, "a");
        o = array.get(1);
        assertEquals(o, 1);
        o = getContext().evaluate("testArr[1]+testArr[2];");
        assertEquals(o, 3);
        o = getContext().evaluate("testArr[0]+'bc';");
        assertEquals(o, "abc");
        
        array.release();
    }
    
    @Test
    public void testESModuleSupport() throws Exception {
        final String TEST_MODULE = "a.js";
        getContext().setConsole(QuickJSContext.DefaultConsole);
        getContext().setModuleLoader(new QuickJSContext.DefaultModuleLoader() {
            @Override
            public String getStringCode(String moduleName) {
                if(moduleName.indexOf(TEST_MODULE) >= 0) {
                    return "export var name = 'Jack';\n"
                           + "export var age = 18;"
                           + "export function report() { return name + ':' + age};";
                }
                return null;
            }
        });
        Object o = getContext().evaluate("import('a.js').then(m => m.name+'|'+m.age+';'+m.report())", "b.js");
        assertEquals("Jack|18;Jack:18", o);
    }
}
