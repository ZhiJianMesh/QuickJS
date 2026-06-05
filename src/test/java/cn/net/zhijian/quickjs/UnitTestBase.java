package cn.net.zhijian.quickjs;

import java.util.Calendar;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public class UnitTestBase {
    //protected static QuickJSContext context;
    private static final ThreadLocal<QuickJSContext> threadContext = new ThreadLocal<>();
    
    public static class JsMesh {
        @JavascriptMethod
        public String success(JSObject data) {
            return "{\"code\":0,\"info\":\"Success\",\"data:\":" + data.toMap() + "}";
        }

        @JavascriptMethod
        public String error(int code, String info) {
            return "{\"code\":"+code+",\"info\":\""+info+"\"}";
        }
    }
    
    @BeforeAll
    public synchronized static void loadContext() {
        QuickJSLogger.instance().info("loadContext {}", Calendar.getInstance().getTime());
        QuickJSContext context = QuickJSContext.create();
        context.evaluate("const RetCode={OK:0,ERROR:1,WARN:2};");
        JSObject jsObj = context.getGlobalObject();
        jsObj.setJavaObject("Mesh", new JsMesh());
        threadContext.set(context);
    }

    @AfterAll
    public static synchronized void destroyContext() {
        QuickJSLogger.instance().info("destroyContext {}", Calendar.getInstance().getTime());
        QuickJSContext context = threadContext.get();
        context.close();
        threadContext.set(null);
    }
    
    protected QuickJSContext getContext() {
        return threadContext.get();
    }
}
