# QuickJS For JVM
QuickJS wrapper for JVM. Based on [HarlonWang's QuickJS](https://github.com/HarlonWang/quickjs-wrapper/tree/main)
Changes:
1) Replace QuickJS with QuickJS-NG;
2) Add NativeLibraryLoader;
3) Add Logger support;
4) Cross compile in Linux|Windows|Termux;
5) Add unit tests.

## Feature
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
context.destroy();
```

### Console Support
```Java
context.setConsole(your console implementation.);
DefaultConsole will be used if not set. DefaultConsole print infomation to logger if set.
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
    return "https://github.com/HarlonWang/quickjs-wrapper";
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
repository.getUrl(); // https://github.com/HarlonWang/quickjs-wrapper
```                

### Get Property
JavaScript

```JavaScript
var repository = {
	name: 'QuickJS Wrapper',
	created: 2022,
	version: 1.1,
	signing_enabled: true,
	getUrl: (name) => { return 'https://github.com/HarlonWang/quickjs-wrapper'; }
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
String url = fn.call(); // https://github.com/HarlonWang/quickjs-wrapper
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
context.evaluate("console.log(test()());");
```

Also, you can view it in `QuickJSTest.testReturnJSCallback` code


### Compile ByteCode

```Java
byte[] code = context.compile("'hello, world!'.toUpperCase();");
context.execute(code);
```

### ESModule
Java
```Java
// 1. string code mode
context.setModuleLoader(new QuickJSContext.DefaultModuleLoader() {
    @Override
    public String getModuleStringCode(String moduleName) {
       if (moduleName.equals("a.js")) {
           return "export var name = 'Jack';\n" +
                   "export var age = 18;";
       }
       return null;
    }
});

// 2. bytecode mode
context.setModuleLoader(new QuickJSContext.BytecodeModuleLoader() {
    @Override
    public byte[] getModuleBytecode(String moduleName) {
        return context.compileModule("export var name = 'Jack';export var age = 18;", moduleName);
    }
});

// 3. use `evaluateModule` for module script
Object msg = context.evaluate("import {name, age} from './a.js'; name + ':' + age"); //Jack:18
```


### Object release
We typically recommend releasing reference relationships actively after using Java objects to avoid memory leaks. Additionally, the engine will release unreleased objects when destroy, but this timing may be a bit later.
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

It's important to note that if the result is being returned for use in JavaScript, there is no need to release it.
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

ProGuard users must manually add the options from [consumer-rules.pro](/wrapper-android/consumer-rules.pro).

## Concurrency
JavaScript runtimes are single threaded. All execution in the JavaScript runtime is guaranteed thread safe, by way of Java synchronization.

## Find this repository useful?
Support it by joining __[stargazers](https://github.com/HarlonWang/quickjs-wrapper/stargazers)__ for this repository. <br>

## Reference

- [quickjs-java](https://github.com/cashapp/quickjs-java)
- [quack](https://github.com/koush/quack)
- [quickjs-android](https://github.com/taoweiji/quickjs-android)
- [HarlonWang's QuickJS](https://github.com/HarlonWang/quickjs-wrapper/tree/main)
