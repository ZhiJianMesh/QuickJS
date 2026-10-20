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
        //包装对象的计数已经归零，说明它自己那一次 JS_FreeValue 已经还过了：
        //此时 pointer 有没有效完全取决于 JS 侧还留没留引用，从外部无从判断，
        //而 native 的 JS_IsLiveObject 是直接读 p->free_mark 的（quickjs.c:7499），
        //对象已被回收就是 heap-use-after-free（ASan 实测命中）。宁可返回 false 也不递野指针。
        if (jsObj == null || !jsObj.isAlive()) {
            return false;
        }
        checkDestroyed();
        return isLiveObject(runtime, jsObj.getPointer());
    }
    
    public void setConsole(Console console) {
        if (console == null) {
            return;
        }

        JSObject consoleObj = getGlobalObject().getJSObject("console");
        consoleObj.setProperty("stdout", args -> {
            String level = "log";
            String info = "";
            //native 侧 console.print 固定以 (level, msg) 调用 stdout，参数个数并不保证
            if (args != null) {
                if (args.length > 0 && args[0] != null) {
                    level = String.valueOf(args[0]).toLowerCase();
                }
                if (args.length > 1 && args[1] != null) {
                    Object msg = args[1];
                    info = unquote(msg instanceof String ? (String) msg : String.valueOf(msg));
                }
            }
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
            return null;
        });
        consoleObj.release();
    }

    /**
     * format() 只给字符串参数包一对单引号，数字/布尔/对象不包，
     * 所以只有两端确实都是单引号时才去掉，否则会吃掉内容的首尾字符。
     */
    private static String unquote(String msg) {
        if (msg.length() > 1 && msg.charAt(0) == '\'' && msg.charAt(msg.length() - 1) == '\'') {
            return msg.substring(1, msg.length() - 1);
        }
        return msg;
    }

    //下面这几个是 runtime 级入口。close() 里 destroyContext 会走到 JS_FreeRuntime，
    //runtime 结构体当场被 free；这些方法早先不检查 destroyed，关完之后调用等于
    //拿野指针去 JS_RunGC/JS_SetXxx —— ASan 实测 runGC 报 heap-use-after-free
    //（写在被 JS_FreeRuntime 释放的 1496 字节块里），Release 版就是静默改堆。
    public void setMaxStackSize(int maxStackSize) {
        checkDestroyed();
        setMaxStackSize(runtime, maxStackSize);
    }

    public void setGCThreshold(int thresholdSize) {
        checkDestroyed();
        setGCThreshold(runtime, thresholdSize);
    }

    public void runGC() {
        checkDestroyed();
        runGC(runtime);
    }

    public void setMemoryLimit(int memoryLimitSize) {
        checkDestroyed();
        setMemoryLimit(runtime, memoryLimitSize);
    }

    // Return the byte size.
    public long getMemoryUsedSize() {
        checkDestroyed();
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

    public void dumpMemoryUsage() {
        dumpMemoryUsage(null); //print to stdout
    }

    //used in native
    public JSObjectCreator getCreator() {
        return creator;
    }

    private final long runtime;
    private final long context;
    private final long currentThreadId;
    private boolean destroyed = false;
    private final HashMap<Integer, JSCallFunction> callFunctionMap = new HashMap<>();
    //注册在 map 里的标识由这里单调分配，map 只在引擎线程读写，不需要并发容器
    private int nextCallFunctionId = 1;

    private ModuleLoader moduleLoader;
    private JSObject globalObject;
    private final JSObjectCreator creator;
    //QuickJSContext can only be used in one thread, so needn't use a concurrent array
    private final List<JSObject> objectRecords = new ArrayList<>();
    private LeakDetectionListener leakDetectionListener;
    private boolean enableStackTrace = false;

    @SuppressWarnings("deprecation")
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
            //createContext 在 native 侧分配失败时会返回 0，后续任何 JNI 调用都会拿 0 当 wrapper 指针解引用，
            //直接 JVM 段错误而不是异常，所以这里必须当场拦住
            if (context == 0) {
                throw new QuickJSException("Failed to create QuickJS context, out of memory");
            }
        } catch (UnsatisfiedLinkError e) {
            throw new QuickJSException("The so library must be initialized before createContext! QuickJSLoader.init should be called on the Android platform. In the JVM, you need to manually call System.loadLibrary");
        }
        currentThreadId = Thread.currentThread().getId(); //threadId() 是 JDK 19 才有的方法，本模块声明兼容 11
    }

    public void setEnableStackTrace(boolean enableStackTrace) {
        this.enableStackTrace = enableStackTrace;
    }
    
    @SuppressWarnings("deprecation")
    private void checkSameThread() {
        if (currentThreadId != Thread.currentThread().getId()) {
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

        if (globalObject == null || globalObject.isRefCountZero()) {
            //调用方 release() 过缓存包装之后不能把死壳继续发出去：包装现在确实持有
            //JS_GetGlobalObject 的那次引用，重新取一次才能让后续调用和计数都对
            globalObject = getGlobalObject(context);
        }

        return globalObject;
    }

    public void setLeakDetectionListener(LeakDetectionListener leakDetectionListener) {
        this.leakDetectionListener = leakDetectionListener;
    }

    @Override
    public void close() {
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

    private void releaseObjectRecords(boolean needRelease) {
        //这里任何一次抛出都会让 close() 走不到 destroyContext，整个 runtime 就地泄漏，两种真实触发方式：
        //1) 调用方 release() 了 getGlobalObject() 返回的缓存包装，拿它再取 format 会抛"计数已为零"；
        //2) 脚本把内置的 globalThis.format 覆盖成非函数，getJSFunction 返回 null，末尾 format.release() 就是 NPE。
        if (globalObject == null || globalObject.isRefCountZero()) {
            globalObject = getGlobalObject(context);
        }
        JSFunction format = ((JSObject) globalObject).getJSFunction("format");

        // 检测是否有未被释放引用的对象，如果有的话，根据计数释放一下
        Iterator<JSObject> objectIterator = objectRecords.iterator();
        while (objectIterator.hasNext()) {
            JSObject object = objectIterator.next();

            // 这里需要过滤掉 getGlobalObject 和 format
            // 1. getGlobalObject 全局对象不会主动释放，引擎销毁会回收
            // 2. format 用来格式化内容，会在迭代完释放掉，这里过滤掉
            if (!object.isRefCountZero() && object != globalObject && object != format) {
                int refCount = object.getRefCount();
                if (leakDetectionListener != null && format != null) {
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

        if (format != null) {
            format.release();
        }

        //全局对象包装现在真的持有 JS_GetGlobalObject 的那次引用，必须在 destroyContext 之前对称还掉，
        //否则引擎带着非零计数被销毁，quickjs-ng 调试版直接停在 JS_FreeRuntime 的 gc_obj_list 断言上
        if (globalObject != null && !globalObject.isRefCountZero()) {
            globalObject.release();
        }
        globalObject = null;
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

        setProperty(context, jsObj.getPointer(), name, value);
    }

    /**
     * 该方法只提供给 Native 层调用.
     * 回调标识必须由引擎分配，不能用 hashCode：两个回调 hashCode 相同时后注册的会覆盖前一个，
     * JS 侧调用先注册的那个就会静默跑到后注册的对象上。
     */
    public int registerCallback(JSCallFunction callFunction) {
        int callFunctionId = nextCallFunctionId++;
        callFunctionMap.put(callFunctionId, callFunction);
        return callFunctionId;
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

        return call(context, func.getPointer(), objPointer, thisPointerTag, args);
    }

    /**
     * Automatically manage the release of objects: hold() is equivalent to calling
     * dupValue() and registering a NativeCleaner that releases the extra reference
     * when this wrapper is garbage-collected.
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
    
    /**
     * Load module to global module
     * After execution, you can use all properties exported
     * from the module in the global module, include functions
     * @param script module script,must include 'export'
     */
    public void evaluateModuleToGlobal(String script) {
        if (script == null) {
            throw new NullPointerException("Script cannot be null");
        }

        checkSameThread();
        checkDestroyed();
        JSObject globalModule = this.getGlobalObject();
        JSObject module = (JSObject)evaluateModule(script);
        
        JSArray array = module.getNames();
        int length = array.length();
        for (int i = 0; i < length; i++) {
            String key = (String) array.get(i);
            Object value = module.getProperty(key);
            globalModule.setPropertyObject(key, value);
            if(value instanceof JSObject) {
                ((JSObject)value).release();
            }
        }
        module.release();
        array.release();
    }

    public Object evaluateModule(String script) {
        return evaluateModule(script, UNKNOWN_FILE);
    }

    //throwJSException 用来把消息递给 JS 侧的全局临时变量，避免把消息拼进源码
    private static final String THROW_VALUE = "__quickjsThrowMessage";

    public void throwJSException(String error) {
        // throw $error;
        QuickJSLogger.instance().error(error);
        //不能拼成 throw "..."，消息里有引号/换行会变成 JS 语法错误，真实错误反而被吞掉
        JSObject global = getGlobalObject();
        global.setProperty(THROW_VALUE, error == null ? "" : error);
        try {
            evaluate("throw " + THROW_VALUE + ";");
        } finally {
            try {
                evaluate("delete " + THROW_VALUE + ";");
            } catch (Exception cleanupFailed) { //清理失败不能盖掉真正要抛出的 JS 异常
            }
        }
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
    private native Object parseJSON(long context, String json);
    private native byte[] compile(long context, String sourceCode, String fileName, boolean isModule); // Bytecode compile
    private native Object execute(long context, byte[] bytecode); // Bytecode execute
    private native Object getOwnPropertyNames(long context, long objValue);

    // destroy context and runtime
    private native void destroyContext(long context);
}
