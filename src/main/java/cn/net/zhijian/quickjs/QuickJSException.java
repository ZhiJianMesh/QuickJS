package cn.net.zhijian.quickjs;

/**
 * Created by Harlon Wang on 2022/2/8.
 */
public class QuickJSException extends RuntimeException {
    private static final long serialVersionUID = 0x189999333333L;
    private final boolean jsError;

    public QuickJSException(String message) {
        this(message, false);
    }
    
    public QuickJSException(String message, Throwable e) {
        super(message, e);
        this.jsError = true;
    }

    public QuickJSException(String message, boolean jsError) {
        super(message);
        this.jsError = jsError;
    }

    public boolean isJSError() {
        return jsError;
    }
}
