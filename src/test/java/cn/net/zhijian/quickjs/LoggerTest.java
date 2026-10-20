package cn.net.zhijian.quickjs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class LoggerTest {
    private static class TestLogger extends QuickJSLogger {
        private final StringBuilder buf;
        
        public TestLogger(StringBuilder buf) {
            this.buf = buf;
        }

        @Override
        public void debug(String fmt, Object... args) {
            buf.append(formatInfo(fmt, args));
        }

        @Override
        public void info(String fmt, Object... args) {
            buf.append(formatInfo(fmt, args));
        }

        @Override
        public void warn(String fmt, Object... args) {
            buf.append(formatInfo(fmt, args));
        }

        @Override
        public void error(String fmt, Object... args) {
            buf.append(formatInfo(fmt, args));
        }        
    }

    @Test
    public void testLoggerFormat() {
        StringBuilder buf = new StringBuilder(1000);
        QuickJSLogger previous = QuickJSLogger.instance();
        QuickJSLogger.setLogger(new TestLogger(buf));
        try {
            testCases(buf);
        } finally {
            QuickJSLogger.setLogger(previous); //全局 logger 不能留給其他测试
        }
    }

    private void testCases(StringBuilder buf) {
        QuickJSLogger.instance().debug("test logger"); //没有参数
        String s = buf.toString();
        assertEquals(s, "test logger");
        buf.setLength(0);
        
        QuickJSLogger.instance().debug("{} logger", "test"); //{}开头
        s = buf.toString();
        assertEquals(s, "test logger");
        buf.setLength(0);
        
        QuickJSLogger.instance().debug("logger {}", "test"); //{}结尾
        s = buf.toString();
        assertEquals(s, "logger test");
        buf.setLength(0);
        
        QuickJSLogger.instance().debug("a {} b {} c", "+", '=');
        s = buf.toString();
        assertEquals(s, "a + b = c");
        buf.setLength(0);
        
        QuickJSLogger.instance().debug("a {} b {}", "+"); //参数不够
        s = buf.toString();
        assertEquals(s, "a + b {}");
        buf.setLength(0);

        QuickJSLogger.instance().error("a {} b,bool:{},int:{}", '&', false, 89, new Exception("test"));
        s = buf.toString();
        assertTrue(s.startsWith("a & b,bool:false,int:89"));
        buf.setLength(0);

        //没有占位符但带参数：不能抛异常，也不能把尾部异常丢掉
        QuickJSLogger.instance().error("no placeholder", new Exception("boom"));
        s = buf.toString();
        assertTrue(s.startsWith("no placeholder"), s);
        assertTrue(s.contains("boom"), s);
        assertTrue(s.contains("LoggerTest"), s); //堆栈要打得出来
        buf.setLength(0);

        //占位符数量刚好等于参数数量时，末尾异常按 SLF4J 约定不再填占位符以外还要打堆栈
        QuickJSLogger.instance().error("{} failed", "step", new IllegalStateException("why"));
        s = buf.toString();
        assertTrue(s.startsWith("step failed"), s);
        assertTrue(s.contains("why"), s);
        buf.setLength(0);

        //多余参数没有异常时按原样保留占位符
        QuickJSLogger.instance().debug("{} and {}", 1);
        s = buf.toString();
        assertEquals("1 and {}", s);
        buf.setLength(0);
    }
}
