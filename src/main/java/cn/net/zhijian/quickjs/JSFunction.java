package cn.net.zhijian.quickjs;

public interface JSFunction extends JSObject {
    Object call(Object... args);
    void callVoid(Object... args);
}
