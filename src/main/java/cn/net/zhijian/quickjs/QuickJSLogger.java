package cn.net.zhijian.quickjs;

public abstract class QuickJSLogger {
    private static final String LOG_PLACEHOLDER = "{}";
    
    //print to console as default
    //setLogger 可能由启动线程调用、由其他线程读取，volatile 保证可见性
    private static volatile QuickJSLogger logger = new QuickJSLogger() {
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
    
    //Imitate logback's format, use '{}' as placeholder
    //{{}} doesn't handled, so can't print '{}'
    protected String formatInfo(String fmt, Object... args) {
        if(fmt == null || args == null || args.length == 0) {
            return fmt;
        }

        int argc = args.length;
        StringBuilder sb = new StringBuilder(fmt.length() * 2);
        int start = 0, idx = 0, end;
        //必须用 >= 0：占位符在字符串开头时 indexOf 返回 0，用 > 0 会整个跳过替换
        while(idx < argc && (end = fmt.indexOf(LOG_PLACEHOLDER, start)) >= 0) {
            sb.append(fmt, start, end).append(args[idx++]);
            start = end + LOG_PLACEHOLDER.length();
        }

        if(start < fmt.length()) { //补上尾巴
            sb.append(fmt, start, fmt.length());
        }

        if(idx < argc && args[argc - 1] instanceof Throwable) {
            Throwable trace = (Throwable)args[argc - 1];
            String sep = System.lineSeparator();
            sb.append(sep).append(trace); //异常本身（类名+message）也要打出来，不然只剩一串栈帧
            StackTraceElement[] stacks = trace.getStackTrace();
            if(stacks != null) {
                for(StackTraceElement stack : stacks) {
                    sb.append(sep).append(stack);
                }
            }
        }
        return sb.toString();
    }
}
