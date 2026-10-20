package cn.net.zhijian.quickjs;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Harlon Wang on 2024/2/12.
 */
public class QuickJSObject implements JSObject {
    private final QuickJSContext context;
    private final long pointer;
    private int refCount;
    private Throwable stackTrace;

    public QuickJSObject(QuickJSContext context, long pointer) {
        this.context = context;
        this.pointer = pointer;
        refCount++;
    }

    @Override
    public long getPointer() {
        return pointer;
    }

    @Override
    public QuickJSContext getContext() {
        return context;
    }

    @Override
    public Object getProperty(String name) {
        checkRefCountIsZero();
        return context.getProperty(this, name);
    }
    
    @Override
    public boolean containsProperty(String name) {
        checkRefCountIsZero();
        Object value = context.getProperty(this, name);
        freeIfNotReturned(value); //属性值只是用来判断存在性，不释放会一直滞留在 context 的 objectRecords 里
        return value != null;
    }

    /**
     * 取到的属性值不返回给调用方时（只判断类型、只做一次转换），需要释放它。
     * 不释放的话 Java 侧包装对象会留在 context 的 objectRecords 中，
     * JS 侧引用计数也降不下来，长期持有 context 的场景会一直增长。
     */
    private void freeIfNotReturned(Object value) {
        if (value instanceof JSObject) {
            ((JSObject) value).release();
        }
    }

    @Override
    public void setStackTrace(Throwable stackTrace) {
        this.stackTrace = stackTrace;
    }

    @Override
    public Throwable getStackTrace() {
        return stackTrace;
    }

    @Override
    public void setProperty(String name, String value) {
        setPropertyObject(name, value);
    }

    @Override
    public void setProperty(String name, int value) {
        setPropertyObject(name, value);
    }

    @Override
    public void setProperty(String name, long value) {
        setPropertyObject(name, value);
    }

    @Override
    public void setProperty(String name, JSObject value) {
        setPropertyObject(name, value);
    }

    @Override
    public void setProperty(String name, boolean value) {
        setPropertyObject(name, value);
    }

    @Override
    public void setProperty(String name, double value) {
        setPropertyObject(name, value);
    }

    @Override
    public void setProperty(String name, byte[] value) {
        setPropertyObject(name, value);
    }

    @Override
    public void setProperty(String name, JSCallFunction value) {
        setPropertyObject(name, value);
    }

    @Override
    public void setPropertyObject(String name, Object o) {
        checkRefCountIsZero();
        context.setProperty(this, name, o);
    }

    @Override
    public void setProperty(String name, Class<?> clazz) {
        Object javaObj = null;
        try {
            javaObj = clazz.getConstructor().newInstance();
        } catch (Exception e) {
            QuickJSLogger.instance().error("Fail to call {}()", clazz.getName(), e);
        }

        if (javaObj == null) {
            throw new NullPointerException("Fail to create " + clazz.getName());
        }

        setJavaObject(name, javaObj);
    }
    
    @Override
    public void setJavaObject(String name, Object javaObj) {
        JSObject jsObj = context.createJSObject();
        Class<?> clazz = javaObj.getClass();
        Method[] methods = clazz.getMethods();
        for (Method method : methods) {
            if (method.isAnnotationPresent(JavascriptMethod.class)) {
                jsObj.setProperty(method.getName(), args -> {
                    try {
                        return method.invoke(javaObj, args);
                    } catch (Exception e) {
                        QuickJSLogger.instance().error("Fail to get method {}.{}", clazz.getName(), method.getName(), e);
                    }
                    return null;
                });
            }
        }

        setProperty(name, jsObj);
        jsObj.release();
    }

    @Override
    public String getString(String name) {
        Object value = getProperty(name);
        if(value == null) {
            return null;
        }
        if(value instanceof String) {
            return (String) value;
        }
        String s = value.toString();
        freeIfNotReturned(value);
        return s;
    }

    @Override
    public Integer getInteger(String name) {
        Object value = getProperty(name);
        Integer ret = null;
        if (value instanceof Number) {
            ret = ((Number) value).intValue();
        } else if (value instanceof String) {
            try {
                ret = Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
            }
        }
        freeIfNotReturned(value);
        return ret;
    }

    @Override
    public boolean getBoolean(String name) {
        Object value = getProperty(name);
        boolean ret = false;
        if(value instanceof Boolean) {
            ret = (Boolean) value;
        } else if(value instanceof String) {
            String s = ((String)value).toLowerCase();
            ret = s.equals("true") || s.equals("1");
        } else if(value instanceof Number) {
            ret = (((Number) value).intValue() != 0);
        }
        freeIfNotReturned(value);
        return ret;
    }

    @Override
    public Double getDouble(String name) {
        Object value = getProperty(name);
        Double ret = null;
        if (value instanceof Number) {
            ret = ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                ret = Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
            }
        }
        freeIfNotReturned(value);
        return ret;
    }

    @Override
    public Long getLong(String name) {
        Object value = getProperty(name);
        Long ret = null;
        if (value instanceof Number) {
            ret = ((Number) value).longValue();
        } else if (value instanceof String) {
            try {
                ret = Long.parseLong((String) value);
            } catch (NumberFormatException e) {
            }
        }
        freeIfNotReturned(value);
        return ret;
    }

    @Override
    public byte[] getBytes(String name) {
        Object value = getProperty(name);
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        freeIfNotReturned(value);
        return null;
    }

    @Override
    public JSObject getJSObject(String name) {
        Object value = getProperty(name);
        if (value instanceof JSObject) {
            return (JSObject) value;
        }
        freeIfNotReturned(value);
        return null;
    }

    @Override
    public JSFunction getJSFunction(String name) {
        Object value = getProperty(name);
        if (value instanceof JSFunction) {
            return (JSFunction) value;
        }
        freeIfNotReturned(value);
        return null;
    }

    @Override
    public JSArray getJSArray(String name) {
        Object value = getProperty(name);
        if (value instanceof JSArray) {
            return (JSArray) value;
        }
        freeIfNotReturned(value);
        return null;
    }

    @Override
    public JSArray getNames() {
        checkRefCountIsZero();
        return (JSArray) context.getOwnPropertyNames(this);
    }

    @Override
    public void release() {
        if (isRefCountZero()) {
            return;
        }
        refCount--;
        context.freeValue(this);
    }

    @Override
    public void hold() {
        checkRefCountIsZero();
        refCount++;
        context.hold(this);
    }

    @Override
    public void decrementRefCount() {
        checkRefCountIsZero();
        refCount--;
    }

    @Override
    public HashMap<String, Object> toMap() {
        return toMap(null);
    }

    @Override
    public ArrayList<Object> toArray() {
        return toArray(null);
    }

    @Override
    public HashMap<String, Object> toMap(MapFilter filter) {
        return (HashMap<String, Object>) toMap(filter, null, HashMap::new);
    }

    @Override
    public Map<String, Object> toMap(MapFilter filter, Object extra, MapCreator mapCreator) {
        Map<String, Object> objectMap = mapCreator.get();
        HashMap<Long, Object> circulars = new HashMap<>();
        convertToMap(this, objectMap, circulars, filter, extra, mapCreator);
        circulars.clear();
        return objectMap;
    }

    @Override
    public ArrayList<Object> toArray(MapFilter filter, Object extra, MapCreator mapCreator) {
        throw new UnsupportedOperationException("Object types are not yet supported for conversion to array. You should use toMap.");
    }

    @Override
    public ArrayList<Object> toArray(MapFilter filter) {
        return toArray(filter, null, null);
    }

    @SuppressWarnings("unchecked")
    protected void convertToMap(Object target, Object map, HashMap<Long, Object> circulars, MapFilter filter, Object extra, MapCreator mapCreator) {
        circulars.put(((JSObject) target).getPointer(), map);

        boolean isArray = target instanceof JSArray;
        JSArray array = isArray ? (JSArray) target : ((JSObject) target).getNames();
        int length = array.length();
        for (int i = 0; i < length; i++) {
            String key = null;
            Object value;
            if (isArray) {
                value = array.get(i);
            } else {
                key = (String) array.get(i);
                if (filter != null && filter.shouldSkipKey(key, pointer, extra)) {
                    continue;
                }
                value = ((JSObject) target).getProperty(key);
            }

            if (value instanceof JSObject) {
                long pointer = ((JSObject) value).getPointer();
                if (circulars.containsKey(pointer)) {
                    // Circular reference objects.
                    Object refValue = circulars.get(pointer);
                    if (map instanceof Map) {
                        ((Map<String, Object>) map).put(key, refValue);
                    } else if (map instanceof ArrayList){
                        ((ArrayList<Object>) map).add(refValue);
                    }
                    //getProperty/get 返回的是自带一次计数的包装，这里不还不是引用泄漏：
                    //命中环或共享对象时 map 里放的是已建好的 Java map，跟这个包装对象再无关系。
                    ((JSObject) value).release();
                    continue;
                }
            }

            if (value instanceof JSFunction) {
                // Unsupported type.
                ((JSFunction) value).release();
                continue;
            }

            if (value instanceof JSArray) {
                ArrayList<Object> list = new ArrayList<>(((JSArray) value).length());
                convertToMap(value, list, circulars, filter, extra, mapCreator);
                if (map instanceof Map) {
                    ((Map<String, Object>) map).put(key, list);
                } else if (map instanceof ArrayList){
                    ((ArrayList<Object>) map).add(list);
                }
                ((JSArray) value).release();
                continue;
            }

            if (value instanceof JSObject) {
                Map<String, Object> valueMap = mapCreator.get();
                convertToMap(value, valueMap, circulars, filter, extra, mapCreator);
                if (map instanceof Map) {
                    ((Map<String, Object>) map).put(key, valueMap);
                } else if (map instanceof ArrayList){
                    ((ArrayList<Object>) map).add(valueMap);
                }
                ((JSObject) value).release();
                continue;
            }

            // Primitive types.
            if (map instanceof Map) {
                ((Map<String, Object>) map).put(key, value);
            } else if (map instanceof ArrayList){
                ((ArrayList<Object>) map).add(value);
            }
        }
        if (!isArray) {
            array.release();
        }
    }

    public int getRefCount() {
        return refCount;
    }

    @Override
    public String stringify() {
        checkRefCountIsZero();
        return context.stringify(this);
    }

    @Override
    public boolean isAlive() {
        return !isRefCountZero();
    }

    final void checkRefCountIsZero() {
        if (isRefCountZero()) {
            throw new QuickJSException("The call threw an exception, the reference count of current object reached zero.");
        }
    }

    public boolean isRefCountZero() {
        return refCount == 0;
    }

    @Override
    public String toString() {
        checkRefCountIsZero();
        //别直接 toString.call()：ES 模块命名空间对象、Object.create(null) 造出来的对象
        //[[Prototype]] 为 null，取不到 toString，getJSFunction 返回 null 就是 NPE，
        //调用方只是想打印一下就崩了。另外 call 抛异常时原来的 release() 会被跳过，
        //包装对象就地泄漏，这里用 finally 兜住。
        JSFunction toString = getJSFunction("toString");
        if (toString == null) {
            return getClass().getSimpleName() + "@" + Long.toHexString(pointer);
        }
        try {
            Object ret = toString.call();
            return ret == null ? "null" : String.valueOf(ret);
        } finally {
            toString.release();
        }
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new long[]{pointer});
    }

}
