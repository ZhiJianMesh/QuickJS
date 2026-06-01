package cn.net.zhijian.quickjs;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class QuickJSContext implements AutoCloseable {
    public interface Console {
        void debug(String info);
        void info(String info);
        void warn(String info);
        void error(String info);
    }
    
    static {
        // 静态加载方法，在类被引用时自动触发，确保原生库被加载
        NativeLibraryLoader.load();
    }

    public static Console DefaultConsole = new Console() { //默认打印到logger中
        @Override
        public void debug(String info) {
            QuickJSLogger.instance().debug(info);
        }

        @Override
        public void info(String info) {
            QuickJSLogger.instance().info(info);
        }

        @Override
        public void warn(String info) {
            QuickJSLogger.instance().warn(info);
        }

        @Override
        public void error(String info) {
            QuickJSLogger.instance().error(info);
        }
    };    

    public interface LeakDetectionListener {
        void notifyLeakDetected(JSObject leak, String stringValue);
    }

    public static abstract class DefaultModuleLoader extends ModuleLoader {
        @Override
        public boolean isBytecodeMode() {
            return false;
        }

        @Override
        public byte[] getBytecode(String moduleName) {
            return null;
        }
    }

    public static abstract class BytecodeModuleLoader extends ModuleLoader {
        @Override
        public boolean isBytecodeMode() {
            return true;
        }

        @Override
        public String getStringCode(String moduleName) {
            return null;
        }
    }

    private static final String UNKNOWN_FILE = "unknown.js";

    public static QuickJSContext create() {
        QuickJSContext context = new QuickJSContext(new JSObjectCreator() {
            @Override
            public JSObject newObject(QuickJSContext context, long pointer) {
                return new QuickJSObject(context, pointer);
            }

            @Override
            public JSArray newArray(QuickJSContext context, long pointer) {
                return new QuickJSArray(context, pointer);
            }

            @Override
            public JSFunction newFunction(QuickJSContext context, long pointer, long thisPointer, int thisPointerTag) {
                return new QuickJSFunction(context, pointer, thisPointer, thisPointerTag);
            }
        });
        context.setConsole(DefaultConsole);
        return context;
    }

    public static QuickJSContext create(JSObjectCreator creator) {
        return new QuickJSContext(creator);
    }

    public boolean isLiveObject(JSObject jsObj) {
        return isLiveObject(runtime, jsObj.getPointer());
    }
    
    public void setConsole(Console console) {
        if (console == null) {
            return;
        }

        JSObject consoleObj = getGlobalObject().getJSObject("console");
        consoleObj.setProperty("stdout", args -> {
            if (args.length == 2) {
                String level = ((String) args[0]).toLowerCase();
                String s = (String) args[1];
                String info = s.substring(1, s.length() - 1);//前后多加了一个单引号
                switch (level) {
                    case "info":
                        console.info(info);
                        break;
                    case "warn":
                        console.warn(info);
                        break;
                    case "error":
                        console.error(info);
                        break;
                    case "log":
                    case "debug":
                    default:
                        console.debug(info);
                        break;
                }
            }

            return null;
        });
        consoleObj.release();
    }

    public void setMaxStackSize(int maxStackSize) {
        setMaxStackSize(runtime, maxStackSize);
    }

    public void setGCThreshold(int thresholdSize) {
        setGCThreshold(runtime, thresholdSize);
    }

    public void runGC() {
        runGC(runtime);
    }

    public void setMemoryLimit(int memoryLimitSize) {
        setMemoryLimit(runtime, memoryLimitSize);
    }

    // Return the byte size.
    public long getMemoryUsedSize() {
        return getMemoryUsedSize(runtime);
    }

    public void dumpMemoryUsage(File target) {
        checkSameThread();
        checkDestroyed();
        String fileName = null;
        if (target != null && target.exists()) {
            fileName = target.getAbsolutePath();
        }

        dumpMemoryUsage(runtime, fileName);
    }

    // will use stdout to print.
    public void dumpMemoryUsage() {
        dumpMemoryUsage(null);
    }

    public void dumpObjects(File target) {
        checkSameThread();
        checkDestroyed();
        String fileName = null;
        if (target != null && target.exists()) {
            fileName = target.getAbsolutePath();
        }

        dumpObjects(runtime, fileName);
    }

    //used in native
    public JSObjectCreator getCreator() {
        return creator;
    }

    // will use stdout to print.
    public void dumpObjects() {
        dumpObjects(null);
    }

    private final long runtime;
    private final long context;
    private final long currentThreadId;
    private boolean destroyed = false;
    private final HashMap<Integer, JSCallFunction> callFunctionMap = new HashMap<>();

    private ModuleLoader moduleLoader;
    private JSObject globalObject;
    private final JSObjectCreator creator;
    private final List<JSObject> objectRecords = new ArrayList<>();
    private LeakDetectionListener leakDetectionListener;
    private boolean enableStackTrace = false;

    private QuickJSContext(JSObjectCreator creator) {
        try {
            // 这里代理一层 creator，用来记录 js 对象.
            this.creator = new JSObjectCreator() {
                @Override
                public JSObject newObject(QuickJSContext c, long pointer) {
                    JSObject o = creator.newObject(c, pointer);
                    if (enableStackTrace) {
                        o.setStackTrace(new Throwable());
                    }
                    objectRecords.add(o);
                    return o;
                }

                @Override
                public JSArray newArray(QuickJSContext c, long pointer) {
                    JSArray o = creator.newArray(c, pointer);
                    if (enableStackTrace) {
                        o.setStackTrace(new Throwable());
                    }
                    objectRecords.add(o);
                    return o;
                }

                @Override
                public JSFunction newFunction(QuickJSContext c, long pointer, long thisPointer, int thisPointerTag) {
                    JSFunction o = creator.newFunction(c, pointer, thisPointer, thisPointerTag);
                    if (enableStackTrace) {
                        o.setStackTrace(new Throwable());
                    }
                    objectRecords.add(o);
                    return o;
                }
            };
            runtime = createRuntime();
            context = createContext(runtime);
        } catch (UnsatisfiedLinkError e) {
            throw new QuickJSException("The so library must be initialized before createContext! QuickJSLoader.init should be called on the Android platform. In the JVM, you need to manually call System.loadLibrary");
        }
        currentThreadId = Thread.currentThread().threadId();
    }

    public void setEnableStackTrace(boolean enableStackTrace) {
        this.enableStackTrace = enableStackTrace;
    }

    private void checkSameThread() {
        boolean isSameThread = currentThreadId == Thread.currentThread().threadId();
        if (!isSameThread) {
            throw new QuickJSException("Must be called in the same thread");
        }
    }

    public long getCurrentThreadId() {
        return currentThreadId;
    }

    public void setModuleLoader(ModuleLoader moduleLoader) {
        checkSameThread();
        checkDestroyed();

        if (moduleLoader == null) {
            throw new QuickJSException("Argument moduleLoader can not be null");
        }

        this.moduleLoader = moduleLoader;
    }

    public ModuleLoader getModuleLoader() {
        return moduleLoader;
    }

    private void checkDestroyed() {
        if (destroyed) {
            throw new QuickJSException("Can not call this after QuickJSContext was destroyed!");
        }
    }

    public Object evaluate(String script) {
        return evaluate(script, UNKNOWN_FILE);
    }

    public Object evaluate(String script, String fileName) {
        if (script == null) {
            throw new QuickJSException("Argument script cannot be null");
        }

        checkSameThread();
        checkDestroyed();
        return evaluate(context, script, fileName);
    }

    public JSObject getGlobalObject() {
        checkSameThread();
        checkDestroyed();

        if (globalObject == null) {
            globalObject = getGlobalObject(context);
        }

        return globalObject;
    }

    public void setLeakDetectionListener(LeakDetectionListener leakDetectionListener) {
        this.leakDetectionListener = leakDetectionListener;
    }

    @Override
    public void close() {
        QuickJSLogger.instance().debug("Close JS context");
        checkSameThread();
        checkDestroyed();

        callFunctionMap.clear();
        releaseObjectRecords(true);
        objectRecords.clear();
        destroyContext(context);
        destroyed = true;
    }
    
    public boolean closed() {
        return destroyed;
    }

    public void releaseObjectRecords(boolean needRelease) {
        JSFunction format = getGlobalObject().getJSFunction("format");

        // 检测是否有未被释放引用的对象，如果有的话，根据计数释放一下
        Iterator<JSObject> objectIterator = objectRecords.iterator();
        while (objectIterator.hasNext()) {
            JSObject object = objectIterator.next();

            // 这里需要过滤掉 getGlobalObject 和 format
            // 1. getGlobalObject 全局对象不会主动释放，引擎销毁会回收
            // 2. format 用来格式化内容，会在迭代完释放掉，这里过滤掉
            if (!object.isRefCountZero() && object != getGlobalObject() && object != format) {
                int refCount = object.getRefCount();
                if (leakDetectionListener != null) {
                    String value = (String) format.call(object);
                    leakDetectionListener.notifyLeakDetected(object, value);
                }

                if (needRelease) {
                    for (int j = 0; j < refCount; j++) {
                        // 这里不能直接调用 object.release 方法，因为 release 里会调用 list.remove 导致并发修改异常
                        object.decrementRefCount();
                        freeValue(context, object.getPointer());
                    }

                    if (object.getRefCount() == 0) {
                        objectIterator.remove();
                    }
                }
            }
        }

        format.release();
    }

    public List<JSObject> getObjectRecords() {
        return objectRecords;
    }

    public String stringify(JSObject jsObj) {
        checkSameThread();
        checkDestroyed();
        return stringify(context, jsObj.getPointer());
    }

    public Object getProperty(JSObject jsObj, String name) {
        checkSameThread();
        checkDestroyed();
        return getProperty(context, jsObj.getPointer(), name);
    }

    public void setProperty(JSObject jsObj, String name, Object value) {
        checkSameThread();
        checkDestroyed();

        if (value instanceof JSCallFunction) {
            // Todo 优化：可以只传 callFunctionId 给到 JNI.
            putCallFunction((JSCallFunction) value);
        }

        setProperty(context, jsObj.getPointer(), name, value);
    }

    private void putCallFunction(JSCallFunction callFunction) {
        int callFunctionId = callFunction.hashCode();
        callFunctionMap.put(callFunctionId, callFunction);
    }

    /**
     * 该方法只提供给 Native 层回调.
     * @param callFunctionId JSCallFunction 对象标识
     */
    public void removeCallFunction(int callFunctionId) {
        callFunctionMap.remove(callFunctionId);
    }

    /**
     * 该方法只提供给 Native 层回调.
     * @param callFunctionId JSCallFunction 对象标识
     * @param args JS 到 Java 的参数映射
     */
    public Object callFunctionBack(int callFunctionId, Object... args) {
        checkSameThread();
        checkDestroyed();

        JSCallFunction callFunction = callFunctionMap.get(callFunctionId);
        Object ret = callFunction.call(args);
        if (ret instanceof JSCallFunction) {
            putCallFunction((JSCallFunction) ret);
        }

        if (ret instanceof JSObject) {
            // 注意：JSObject 对象作为参数返回到️ JavaScript 中，不需要调用 release 方法，
            // JS 引擎会进行 free，但是这里需要手动对 JSObject 对象的计数减一。
            ((JSObject) ret).decrementRefCount();

            if (((JSObject) ret).getRefCount() == 0) {
                objectRecords.remove(((JSObject) ret));
            }
        }

        return ret;
    }

    /**
     * JS 引擎层的对象计数减一。
     */
    public void freeValue(JSObject jsObj) {
        checkSameThread();
        checkDestroyed();

        freeValue(context, jsObj.getPointer());

        // todo 如果计数为 0，从 objectRecords 里移除掉
        if (jsObj.isRefCountZero()) {
            objectRecords.remove(jsObj);
        }
    }

    /**
     * @VisibleForTesting
     * 该方法仅供单元测试使用
     */
    int getCallFunctionMapSize() {
        return callFunctionMap.size();
    }

    /**
     * JS 引擎层的对象计数加一。
     */
    private void dupValue(JSObject jsObj) {
        checkSameThread();
        checkDestroyed();

        dupValue(context, jsObj.getPointer());
    }

    public int length(JSArray jsArray) {
        checkSameThread();
        checkDestroyed();

        // todo 待优化
        if (!isLiveObject(jsArray)) {
            return 0;
        }

        return length(context, jsArray.getPointer());
    }

    public Object get(JSArray jsArray, int index) {
        checkSameThread();
        checkDestroyed();

        return get(context, jsArray.getPointer(), index);
    }

    public void set(JSArray jsArray, Object value, int index) {
        checkSameThread();
        checkDestroyed();

        set(context, jsArray.getPointer(), value, index);
    }

    Object call(JSObject func, long objPointer, int thisPointerTag, Object... args) {
        checkSameThread();
        checkDestroyed();

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof JSCallFunction) {
                putCallFunction((JSCallFunction) arg);
            }
        }

        return call(context, func.getPointer(), objPointer, thisPointerTag, args);
    }

    /**
     * Automatically manage the release of objects，
     * the hold method is equivalent to call the
     * dupValue and freeDupValue methods with NativeCleaner.
     */
    public void hold(JSObject jsObj) {
        checkSameThread();
        checkDestroyed();

        dupValue(jsObj);
    }

    public JSObject createJSObject() {
        Object obj = parse("{}");
        if (!(obj instanceof JSObject)) {
            throw new QuickJSException("Object must start with '{'.");
        }
        return (JSObject) obj;
    }

    public JSArray createJSArray() {
        Object obj = parse("[]");
        if (!(obj instanceof JSArray)) {
            throw new QuickJSException("Array must start with '['");
        }
        return (JSArray) obj;
    }

    public Object parse(String json) {
        checkSameThread();
        checkDestroyed();
        return parseJSON(context, json);
    }

    public byte[] compile(String script) {
        return compile(script, UNKNOWN_FILE);
    }

    public byte[] compile(String script, String fileName) {
        if (script == null) {
            throw new NullPointerException("Script cannot be null with " + fileName);
        }

        checkSameThread();
        checkDestroyed();

        return compile(context, script, fileName, false);
    }

    public byte[] compileModule(String script) {
        return compileModule(script, UNKNOWN_FILE);
    }

    public byte[] compileModule(String script, String fileName) {
        if (script == null) {
            throw new NullPointerException("Script cannot be null with " + fileName);
        }

        checkSameThread();
        checkDestroyed();

        return compile(context, script, fileName, true);
    }

    public Object execute(byte[] code) {
        if (code == null) {
            throw new NullPointerException("Bytecode cannot be null");
        }

        checkSameThread();
        checkDestroyed();

        return execute(context, code);
    }

    public Object evaluateModule(String script, String moduleName) {
        if (script == null) {
            throw new NullPointerException("Script cannot be null with " + moduleName);
        }

        checkSameThread();
        checkDestroyed();
        return evaluateModule(context, script, moduleName);
    }

    public Object evaluateModule(String script) {
        return evaluateModule(script, UNKNOWN_FILE);
    }

    public void throwJSException(String error) {
        // throw $error;
        String errorScript = "throw " + "\"" + error + "\"" + ";";
        QuickJSLogger.instance().error(error);
        evaluate(errorScript);
    }

    public Object getOwnPropertyNames(JSObject object) {
        return getOwnPropertyNames(context, object.getPointer());
    }

    // runtime
    private native long createRuntime();
    private native void setMaxStackSize(long runtime, int size); // The default is 1024 * 256, and 0 means unlimited.
    private native boolean isLiveObject(long runtime, long objValue);
    private native void runGC(long runtime);
    private native void setMemoryLimit(long runtime, int size);
    private native void dumpMemoryUsage(long runtime, String fileName);
    private native void dumpObjects(long runtime, String fileName);
    private native long getMemoryUsedSize(long runtime);
    private native void setGCThreshold(long runtime, int size);

    // context
    private native long createContext(long runtime);
    private native Object evaluate(long context, String script, String fileName);
    private native Object evaluateModule(long context, String script, String fileName);
    private native JSObject getGlobalObject(long context);
    private native Object call(long context, long func, long thisObj, int thisObjTag, Object[] args);
    private native Object getProperty(long context, long objValue, String name);
    private native void setProperty(long context, long objValue, String name, Object value);
    private native String stringify(long context, long objValue);
    private native int length(long context, long objValue);
    private native Object get(long context, long objValue, int index);
    private native void set(long context, long objValue, Object value, int index);
    private native void freeValue(long context, long objValue);
    private native void dupValue(long context, long objValue);
    private native void freeDupValue(long context, long objValue);
    private native Object parseJSON(long context, String json);
    private native byte[] compile(long context, String sourceCode, String fileName, boolean isModule); // Bytecode compile
    private native Object execute(long context, byte[] bytecode); // Bytecode execute
    private native Object getOwnPropertyNames(long context, long objValue);

    // destroy context and runtime
    private native void destroyContext(long context);
}
