package cn.net.zhijian.quickjs;

public interface JSArray extends JSObject {
    int length();
    Object get(int index);
    void set(Object value, int index);
}
