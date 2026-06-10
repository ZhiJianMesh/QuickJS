# QuickJS For JVM&Android
QuickJS wrapper for JVM & Android. 
It is based on [HarlonWang's QuickJS](https://github.com/HarlonWang/quickjs-wrapper/tree/main).

## Enhancements:
1) Replace QuickJS with QuickJS-NG;
2) Correct some bugs.
3) Add NativeLibraryLoader;
4) Add Logger support;
5) Support ESMoudle;
6) Cross compile in Linux|Windows|Termux|Android;
7) Add junit test cases;

## Features:
- Java types are supported with JavaScript
- Support promise execute
- JavaScript exception handler
- Compile bytecode
- Supports converting JS object types to Java HashMap.
- ESModule (import, export)
- Support 16KB page size

Experimental Features Stability not guaranteed.
- Supports ArrayBuffer to a byte array type.

## Usage

### Create QuickJSContext

```Java
QuickJSContext context = QuickJSContext.create();

// evaluating JavaScript
context.evaluate("1 + 2;");

// destroy QuickJSContext
context.close();
```
Or try-with-resource

```Java
try(QuickJSContext context = QuickJSContext.create()) {
    // evaluating JavaScript
    context.evaluate("1 + 2;");
}
```

### Console Support
```Java
context.setConsole(your console implementation);
DefaultConsole will be used if not set. DefaultConsole print information to logger if set.
```

### Supported Types

#### Java and JavaScript can directly convert to each other for the following basic types
| JavaScript  | Java              |
|-------------|-------------------|
| null        | null              |
| undefined   | null              |
| boolean     | Boolean           |
| Number      | Long/Int/Double   |
| string      | String            |
| Array       | JSArray           |
| object      | JSObject          |
| Function    | JSFunction        |
| ArrayBuffer | byte[](Deep copy) |

Since JavaScript doesn't have a `long` type, additional information about `long`:

Java --> JavaScript
  - The Long value <= Number.MAX_SAFE_INTEGER, will be convert to Number type.
  - The Long value > Number.MAX_SAFE_INTEGER, will be convert to BigInt type.
  - Number.MIN_SAFE_INTEGER is the same to above.

JavaScript --> Java
  - Number(Int64) or BigInt --> Long type


### Set Property
Java

```java
QuickJSContext context = QuickJSContext.create();
JSObject globalObj = context.getGlobalObject();
JSObject repository = context.createNewJSObject();
obj1.setProperty("name", "QuickJS Wrapper");
obj1.setProperty("created", 2022);
obj1.setProperty("version", 1.1);
obj1.setProperty("signing_enabled", true);
obj1.setProperty("getUrl", (JSCallFunction) args -> {
    return "https://github.com/ZhiJianMesh/QuickJS";
});
globalObj.setProperty("repository", repository);
repository.release();
```

JavaScript

```javascript
repository.name; // QuickJS Wrapper
repository.created; // 2022
repository.version; // 1.1
repository.signing_enabled; // true
repository.getUrl(); // https://github.com/ZhiJianMesh/QuickJS
```                

### Get Property
JavaScript

```JavaScript
var repository = {
    name: 'QuickJS Wrapper',
    created: 2022,
    version: 1.1,
    signing_enabled: true,
    getUrl: (name) => { return 'https://github.com/ZhiJianMesh/QuickJS'; }
}
```
Java

```Java
QuickJSContext context = QuickJSContext.create();
JSObject globalObject = context.getGlobalObject();
JSObject repository = globalObject.getJSObject("repository");
repository.getString("name"); // QuickJS Wrapper
repository.getInteger("created"); // 2022
repository.getDouble("version"); // 1.1
repository.getBoolean("signing_enabled"); // true
JSFunction fn = repository.getJSFunction("getUrl");
String url = fn.call(); // https://github.com/ZhiJianMesh/QuickJS
fn.release();
repository.release();
```

### Create JSObject in Java
```Java
QuickJSContext context = QuickJSContext.create();
JSObject obj = context.createNewJSObject();
// When not in use, it needs to be released, otherwise it will cause a memory leak.
obj.release();
```

### Create JSArray in Java
```Java
QuickJSContext context = QuickJSContext.create();
JSArray array = context.createNewJSArray();
array.release();
```

### How to return Function to JavaScript in Java
```Java
QuickJSContext context = createContext();
context.getGlobalObject().setProperty("test", args -> (JSCallFunction) args1 -> "123");
context.evaluate("console.log(test());");
```

Also, you can view it in `JavaFeaturesTest.testJavaFunctionBinding`


### Compile ByteCode

```Java
byte[] code = context.compile("'hello, world!'.toUpperCase();");
context.execute(code); //caution:not evaluate()
```

### ESModule
Java
```Java
// 1. load with string code mode
String js =  "export var name = 'Jack';\n"
          + "export var age = 18;\n"
          + "export function report() { return name + ':' + age};"

context.setModuleLoader(new QuickJSContext.DefaultModuleLoader() {
    @Override
    public String getModuleStringCode(String moduleName) {
       if (moduleName.equals("a.js")) {
           return js;
       }
       return null;
    }
});

// 2. load with bytecode mode
context.setModuleLoader(new QuickJSContext.BytecodeModuleLoader() {
    @Override
    public byte[] getModuleBytecode(String moduleName) {
        if (moduleName.equals("a.js")) {
            return context.compileModule(js, moduleName);
        }
        return null;
    }
});

// 3. use module script with 'evaluate'
Object msg = context.evaluate("import('a.js').then(m => m.name+':'+m.age)"); //Jack:18

// 4. load module with 'evaluateModule' directly
Object o = context.evaluateModule(
        "export var name = 'Jack';\n" +
        "export var age = 18;\n" +
        "export function report() { return name + ':' + age};", 
        "a.js");
JSObject module = (JSObject)o;
String name = module.getString("name"); //Jack
int age = module.getInteger("age"); //18
JSFunction f = module.getJSFunction("report");
String result = (String) f.call(); //Jack:18
//===be sure to release them after using===
f.release();
module.release();

// 5. load module to global module
context.evaluateModuleToGlobal(
        "export var name = 'Jack';\n" +
        "export var age = 18;\n" +
        "export function report() { return name + ':' + age};");
context.evaluate("name"); //Jack
context.evaluate("age"); //18
context.evaluate("report()");//Jack:18
```

### Used in multi-thread
QuickJSContext can only be created,used,closed in the same thread.
So in a ThreadPool, the optional ways are:
1. Create a fixed number thread by newFixedThreadPool;
Create context in each thread and never close it. It will be closed when java progress over;
2. Create a context at the begin of the thread and close it at the end of the thread.
Save the context into ThreadLocal, and get it from ThreadLocal when using.
Following code is a example of way 2.

```Java
ThreadLocal<QuickJSContext> threadContext = new ThreadLocal<>();
ExecutorService pool = new ThreadPoolExecutor(1, 100, 10 * 1000L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new ThreadFactory() {
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    @Override
    public Thread newThread(Runnable r) {
        String name = "TestMulti-" + threadNumber.getAndIncrement();
        Runnable wrapped = () -> {
            QuickJSContext ctx =  QuickJSContext.create();
            //do some initialization here
            threadContext.set(ctx);
            try {
                r.run();
            } finally {
                threadContext.set(null);
                ctx.close(); //close it when the thread destroyed
            }
        };
        return new Thread(wrapped, name);
    }            
});
pool.execute(() -> {
    QuickJSContext ctx = threadContext.get(); //get it from ThreadLocal
    ctx.evaluate(...);
});
...
pool.close();
``` 

### Object release
We typically recommend releasing reference relationships actively after using Java objects to avoid memory leaks. Additionally, the engine will release unreleased objects when destroy, but it may be a bit later.
```java
JSFunction func = xxx.getJSFunction("test");
func.call();
func.release();

JSObject obj = xxx.getJSObject("test");
int a = obj.getString("123");
obj.release();

// If the return value is an object, it also needs to be released, 
JSObject ret = jsFunction.call();
ret.release();

// If you don't need to handle the return value, it is recommended to call the following method.
jsFunction.callVoid(xxx);
```

It's important that if the result is being used in JavaScript, needn't release.
```java
context.getGlobalObject().setProperty("test", new JSCallFunction() {
  @Override
  public Object call(Object... args) {
    JSObject ret = context.createNewJSObject();
    // There is no need to call the release method here.
    // ret.release();
    return ret;
  }
});
```

## R8 / ProGuard
If you are using R8 the shrinking and obfuscation rules are included automatically.

ProGuard users must manually add the options from [consumer-rules.pro](/consumer-rules.pro).

## Concurrency
JavaScript runtime context must be used in a single thread at the same time.
All execution in JavaScript runtime is guaranteed thread safe. 
You should isolate contexts within different threads.
For example save contexts in a resource pool. When any thread need to use, just apply from the pool. 

## Find this repository useful?
Support it by joining __[stargazers](https://github.com/ZhiJianMesh/QuickJS/stargazers)__ for this repository. <br>

## Reference
- [HarlonWang's QuickJS](https://github.com/HarlonWang/quickjs-wrapper/tree/main)
- [quickjs-java](https://github.com/cashapp/quickjs-java)
- [quack](https://github.com/koush/quack)
- [quickjs-android](https://github.com/taoweiji/quickjs-android)
