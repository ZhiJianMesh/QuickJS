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
        QuickJSLogger.setLogger(new TestLogger(buf));
        
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
    }
}
