package cn.net.zhijian.quickjs;

public abstract class QuickJSLogger {
    private static final String LOG_PLACEHOLDER = "{}";
    
    //print to console as default
    private static QuickJSLogger logger = new QuickJSLogger() {
        @Override
        public void debug(String fmt, Object... args) {
            System.out.println(formatInfo(fmt, args));
        }

        @Override
        public void info(String fmt, Object... args) {
            System.out.println(formatInfo(fmt, args));
        }

        @Override
        public void warn(String fmt, Object... args) {
            System.err.println(formatInfo(fmt, args));
        }

        @Override
        public void error(String fmt, Object... args) {
            System.err.println(formatInfo(fmt, args));
        }
    };
    
    public abstract void debug(String fmt, Object...args);
    public abstract void info(String fmt, Object...args);
    public abstract void warn(String fmt, Object...args);
    public abstract void error(String fmt, Object...args);

    public static void setLogger(QuickJSLogger logger) {
        QuickJSLogger.logger = logger;
    }
    
    public static QuickJSLogger instance() {
        return logger;
    }
    
    //模仿logback格式化的特征实现
    protected String formatInfo(String fmt, Object... args) {
        if(args == null || args.length == 0) {
            return fmt;
        }
        int start = 0;
        int end = fmt.indexOf(LOG_PLACEHOLDER); //split会占用两倍内存，释放慢
        int idx = 0;
        int argc = args.length;
        StringBuilder sb = new StringBuilder(fmt.length() * 2);

        do {
            sb.append(fmt.substring(start, end));
            if(idx < argc) {
                sb.append(args[idx++]);
            } else {
                sb.append(LOG_PLACEHOLDER);
            }
            start = end + LOG_PLACEHOLDER.length();
            end = fmt.indexOf(LOG_PLACEHOLDER, start);
        } while(end > 0);

        if(start < fmt.length()) { //补上尾巴
            sb.append(fmt.substring(start));
        }

        if(idx < args.length && args[argc - 1] instanceof Throwable) {
            Throwable e = (Throwable)args[argc - 1];
            StackTraceElement[] stacks = e.getStackTrace();
            for(StackTraceElement stack : stacks) {
                sb.append('\n').append(stack.toString());
            }
        }
        return sb.toString();
    }
}
