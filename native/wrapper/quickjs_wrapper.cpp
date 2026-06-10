//
// Created by yonglan.whl on 2021/7/14.
//
#include "quickjs_wrapper.h"
#include "../quickjs-ng/cutils.h"
#include "quickjs_extend_libraries.h"
#include <cstring>
#include <cmath>

#define MAX_SAFE_INTEGER (((int64_t)1 << 53) - 1)

// util
static string getJavaName(JNIEnv* env, jobject javaClass) {
    jclass classType = env->GetObjectClass(javaClass);
    const jmethodID method = env->GetMethodID(classType, "getName", "()Ljava/lang/String;");
    jstring javaString = (jstring)(env->CallObjectMethod(javaClass, method));
    const char* s = env->GetStringUTFChars(javaString, nullptr);

    std::string str(s);
    env->ReleaseStringUTFChars(javaString, s);
    env->DeleteLocalRef(javaString);
    env->DeleteLocalRef(classType);
    return str;
}

//quickjs 没有提供 JS_IsArrayBuffer 方法，quickjs-ng中已经实现了
//static bool JS_IsArrayBuffer(JSValue  value) {
//    // quickjs 里的 ArrayBuffer 对应的类型枚举值
//    int8_t JS_CLASS_ARRAY_BUFFER = 19;
//    return JS_GetClassID(value) == JS_CLASS_ARRAY_BUFFER;
//}

static void tryToTriggerOnError(JSContext *ctx, JSValueConst *error) {
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue onerror = JS_GetPropertyStr(ctx, global, "onError");
    if (JS_IsNull(onerror)) {
        // may be lowercase
        onerror = JS_GetPropertyStr(ctx, global, "onerror");
    }

    if (JS_IsNull(onerror)) {
        // do nothing
        return;
    }

    JS_Call(ctx, onerror, global, 1, error);
    JS_FreeValue(ctx, onerror);
    JS_FreeValue(ctx, global);
}

static string getJSErrorStr(JSContext *ctx, JSValueConst error) {
    JSValue val;
    bool is_error;
    is_error = JS_IsError(error);
    string jsException;
    if (is_error) {
        tryToTriggerOnError(ctx, &error);

        JSValue message = JS_GetPropertyStr(ctx, error, "message");
        const char *msg_str = JS_ToCString(ctx, message);
        jsException += msg_str;
        JS_FreeCString(ctx, msg_str);
        JS_FreeValue(ctx, message);

        val = JS_GetPropertyStr(ctx, error, "stack");
        if (!JS_IsUndefined(val)) {
            jsException += "\n";

            const char *stack_str = JS_ToCString(ctx, val);
            jsException += stack_str;
            JS_FreeCString(ctx, stack_str);
        }
        JS_FreeValue(ctx, val);
    } else {
        const char *error_str = JS_ToCString(ctx, error);
        jsException += error_str;
        JS_FreeCString(ctx, error_str);
    }
    return jsException;
}

static string getJSErrorStr(JSContext *ctx) {
    JSValue error = JS_GetException(ctx);
    string error_str = getJSErrorStr(ctx, error);
    JS_FreeValue(ctx, error);
    return error_str;
}

static void throwJavaException(JNIEnv *env, const char *exceptionClass, const char *fmt, ...) {
    char msg[512];
    va_list args;
    va_start (args, fmt);
    vsnprintf(msg, sizeof(msg), fmt, args);
    va_end (args);
    jclass e = env->FindClass(exceptionClass);
    env->ThrowNew(e, msg);
    env->DeleteLocalRef(e);
}

static void throwJSException(JNIEnv *env, const char* msg) {
    if (env->ExceptionCheck()) {
        return;
    }

    jclass e = env->FindClass("cn/net/zhijian/quickjs/QuickJSException");
    jmethodID init = env->GetMethodID(e, "<init>", "(Ljava/lang/String;Z)V");
    jstring ret = env->NewStringUTF(msg);
    auto t = (jthrowable)env->NewObject(e, init, ret, JNI_TRUE);
    env->Throw(t);
    env->DeleteLocalRef(e);
}

static void throwJSException(JNIEnv *env, JSContext *ctx) {
    string error = getJSErrorStr(ctx);
    throwJSException(env, error.c_str());
}

// js function callback
static JSClassID js_func_callback_class_id;

static void jsFuncCallbackFinalizer(JSRuntime *rt, JSValue val) {
    auto wrapper = reinterpret_cast<const QuickJSWrapper*>(JS_GetRuntimeOpaque(rt));
    if (wrapper) {
        int *callbackId = (int *)(JS_GetOpaque2(wrapper->context, val, js_func_callback_class_id));
        wrapper->removeCallFunction(*callbackId);
        delete callbackId;
    }
}

static JSClassDef js_func_callback_class = {
        .class_name = "JSFuncCallback",
        .finalizer = jsFuncCallbackFinalizer
};

static JSValue jsFnCallback(JSContext *ctx, JSValueConst this_obj, int argc, JSValueConst *argv, int magic, JSValue *func_data) {
    int callbackId = *((int *)JS_GetOpaque2(ctx, func_data[0], js_func_callback_class_id));
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(JS_GetRuntimeOpaque(JS_GetRuntime(ctx)));
    JSValue value = wrapper->jsFuncCall(callbackId, this_obj, argc, argv);
    return value;
}

static void initJSFuncCallback(JSContext *ctx) {
    // JSFuncCallback class
    JSRuntime* rt = JS_GetRuntime(ctx);
    JS_NewClassID(rt, &js_func_callback_class_id);
    JS_NewClass(rt, js_func_callback_class_id, &js_func_callback_class);
}

// js module
static char *jsModuleNormalizeFunc(JSContext *ctx, const char *module_base_name,
                                   const char *module_name, void *opaque) {
    auto wrapper = reinterpret_cast<const QuickJSWrapper*>(JS_GetRuntimeOpaque(JS_GetRuntime(ctx)));
    auto env = wrapper->jniEnv;

    // module loader handle.
    jobject moduleLoader = env->CallObjectMethod(wrapper->jniThiz, env->GetMethodID(wrapper->quickjsContextClass, "getModuleLoader", "()Lcn/net/zhijian/quickjs/ModuleLoader;"));
    if (moduleLoader == nullptr) {
        JS_ThrowInternalError(ctx, "Failed to load module, the ModuleLoader can not be null!");
        return nullptr;
    }
    jmethodID normalizeName = env->GetMethodID(wrapper->moduleLoaderClass, "normalizeName", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring j_module_base_name = env->NewStringUTF(module_base_name);
    jstring j_module_name = env->NewStringUTF(module_name);
    const char *norm_name; //must be defined before goto, otherwise can't use goto
    char *ret = nullptr;
    jobject result = env->CallObjectMethod(moduleLoader, normalizeName, j_module_base_name, j_module_name);
    if (result == nullptr) {
        throwJSException(env, "Failed to load module, cause moduleName was null!");
        goto END_NORMALIZE;
    }
    norm_name = env->GetStringUTFChars((jstring) result, nullptr);
    ret = js_strdup(ctx, norm_name);  // 使用QuickJS的js_strdup，分配QuickJS可以释放的内存
    env->ReleaseStringUTFChars((jstring) result, norm_name);
    env->DeleteLocalRef(result);

END_NORMALIZE:
    env->DeleteLocalRef(j_module_base_name);
    env->DeleteLocalRef(j_module_name);
    env->DeleteLocalRef(moduleLoader);

    return ret; // 由 QuickJS 负责释放
}

static JSModuleDef *jsModuleLoaderFunc(JSContext *ctx, const char *module_name, void *opaque) {
    auto wrapper = reinterpret_cast<const QuickJSWrapper*>(JS_GetRuntimeOpaque(JS_GetRuntime(ctx)));
    auto env = wrapper->jniEnv;

    jobject moduleLoader = env->CallObjectMethod(wrapper->jniThiz, env->GetMethodID(wrapper->quickjsContextClass, "getModuleLoader", "()Lcn/net/zhijian/quickjs/ModuleLoader;"));
    if (moduleLoader == nullptr) {
        JS_ThrowInternalError(ctx, "Failed to load module, the ModuleLoader can not be null!");
        return (JSModuleDef *) JS_VALUE_GET_PTR(JS_EXCEPTION);
    }

    jstring utf8Name = env->NewStringUTF(module_name);
    bool isBytecodeModule = env->CallBooleanMethod(moduleLoader, env->GetMethodID(wrapper->moduleLoaderClass, "isBytecodeMode", "()Z"));

    JSModuleDef *m = nullptr;
    if (isBytecodeModule) {
        jmethodID getBytecode = env->GetMethodID(wrapper->moduleLoaderClass, "getBytecode", "(Ljava/lang/String;)[B");
        jbyteArray bytecode = (jbyteArray) env->CallObjectMethod(moduleLoader, getBytecode, utf8Name);
        if (bytecode == nullptr) {
            throwJSException(env, "Failed to load module, cause bytecode is null!");
            goto END_LOADER;//use goto to achieve a single exit point, release resources there
        }

        jbyte* buffer = env->GetByteArrayElements(bytecode, nullptr);
        jsize bufferLength = env->GetArrayLength(bytecode);
        JSValue obj = JS_ReadObject(ctx, reinterpret_cast<const uint8_t*>(buffer), bufferLength, JS_READ_OBJ_BYTECODE | JS_READ_OBJ_REFERENCE);
        env->ReleaseByteArrayElements(bytecode, buffer, JNI_ABORT);
        env->DeleteLocalRef(bytecode);

        if (JS_IsException(obj)) {
            throwJSException(env, ctx);// convert js exception to java exception
            m = (JSModuleDef *) JS_VALUE_GET_PTR(JS_EXCEPTION);
            goto END_LOADER;
        }

        if (JS_ResolveModule(ctx, obj)) {
            throwJSException(env, "Failed to resolve JS module");
            goto END_LOADER;
        }

        m = (JSModuleDef*) JS_VALUE_GET_PTR(obj);
        // 注意：这可能会释放模块？需要检查 quickjs 内部。
        // 通常 JS_ResolveModule 后 obj 和模块关联，不能释放？这里需要参考 quickjs 示例。
        // 安全做法是返回 obj 的指针，不释放。
        // 实际上，JS_ResolveModule 不会转移所有权，我们需要返回模块对象。
        // 但 obj 已经被 JS_FreeValue 释放，m 将成为悬空指针！
        // 正确的做法：不释放 obj，而是返回 JS_VALUE_GET_PTR(obj)，后续由 JS_EvalFunction 等管理。
        // 但这里因为我们已经释放了，所以会崩溃。需要重新设计。
        // 建议：直接返回 (JSModuleDef*)JS_VALUE_GET_PTR(obj)，不释放 obj。
        //JS_FreeValue(ctx, obj); 
    } else {
        jmethodID getStringCode = env->GetMethodID(wrapper->moduleLoaderClass, "getStringCode", "(Ljava/lang/String;)Ljava/lang/String;");
        jobject result = env->CallObjectMethod(moduleLoader, getStringCode, utf8Name);
        if (result == nullptr) {
            throwJSException(env, "Failed to load module, cause string code was null!");
            goto END_LOADER;
        }
        
        const char* script = env->GetStringUTFChars((jstring) result, JNI_FALSE);
        int scriptLen = env->GetStringUTFLength((jstring) result);
        JSValue func_val = JS_Eval(ctx, script, scriptLen, module_name, JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
        env->ReleaseStringUTFChars((jstring) result, script);
        env->DeleteLocalRef(result);

        if (JS_IsException(func_val)) {
            throwJSException(env, ctx);// 获取异常信息
            m = (JSModuleDef *) JS_VALUE_GET_PTR(JS_EXCEPTION);
            goto END_LOADER;
        }
        m = (JSModuleDef*) JS_VALUE_GET_PTR(func_val);
        // 注意：这里不能释放 func_val，因为模块定义需要保留
        // JS_FreeValue(ctx, func_val);  // 绝对不能释放！
    }
END_LOADER:
    env->DeleteLocalRef(utf8Name);
    env->DeleteLocalRef(moduleLoader);
    return m;
}

static bool throwIfUnhandledRejections(QuickJSWrapper *wrapper, JSContext *ctx) {
    string error;
    while (!wrapper->unhandledRejections.empty()) {
        JSValueConst reason = wrapper->unhandledRejections.front();
        error += getJSErrorStr(ctx, reason);
        error += "\n";
        JS_FreeValue(ctx, reason);
        wrapper->unhandledRejections.pop();
    }

    bool is_error = !error.empty();
    if (is_error) {
        error = "UnhandledPromiseRejectionException: " + error;
        throwJSException(wrapper->jniEnv, error.c_str());
    }
    return is_error;
}

static bool executePendingJobLoop(JNIEnv *env, JSRuntime *rt, JSContext *ctx) {
    if (env->ExceptionCheck()) {
        return false;
    }

    JSContext *jobCtx;
    bool success = true;
    int err;
    /* execute the pending jobs */
    for(;;) {
        err = JS_ExecutePendingJob(rt, &jobCtx);
        if (err <= 0) {
            if (err < 0) {
                success = false;
                string error = getJSErrorStr(ctx);
                throwJSException(env, error.c_str());
            }
            break;
        }
    }

    if (success && throwIfUnhandledRejections(reinterpret_cast<QuickJSWrapper *>(JS_GetRuntimeOpaque(rt)), ctx)) {
        success = false;
    }

    return success;
}

static JSValue waitPromise(JNIEnv* env, JSRuntime *rt, JSContext *context, JSValue value) {
    if (!executePendingJobLoop(env, rt, context)) { 
        JS_FreeValue(context, value);
        return JS_NULL;
    }
    
    int state = JS_PromiseState(context, value);
    if (state == JS_PROMISE_FULFILLED) {
        JSValue fulfilled_value = JS_PromiseResult(context, value);
        JS_FreeValue(context, value);
        return fulfilled_value;
    }
    
    if (state == JS_PROMISE_REJECTED) {
        JSValue reason = JS_PromiseResult(context, value);
        JS_Throw(context, reason);
        JS_FreeValue(context, reason);
        JS_FreeValue(context, value);
    }
    
    return JS_NULL;
}

static void promiseRejectionTracker(JSContext *ctx, JSValueConst promise,
                                    JSValueConst reason, bool is_handled, void *opaque) {
    auto unhandledRejections = static_cast<queue<JSValue> *>(opaque);
    if (!is_handled) {
        unhandledRejections->push(JS_DupValue(ctx, reason));
    } else {
        if (!unhandledRejections->empty()) {
            JSValueConst rej = unhandledRejections->front();
            JS_FreeValue(ctx, rej);
            unhandledRejections->pop();
        }
    }
}

QuickJSWrapper::QuickJSWrapper(JNIEnv *env, jobject thiz, JSRuntime *rt) {
    jniEnv = env;
    runtime = rt;
    jniThiz = jniEnv->NewGlobalRef(thiz);

    // init ES6Module
    JS_SetModuleLoaderFunc(runtime, jsModuleNormalizeFunc, jsModuleLoaderFunc, nullptr);

    JS_SetHostPromiseRejectionTracker(runtime, promiseRejectionTracker, &unhandledRejections);

    context = JS_NewContext(runtime);

    JS_SetRuntimeOpaque(runtime, this);
    initJSFuncCallback(context);
    loadExtendLibraries(context);

    const char *getOwnPropertyNames = "Object.getOwnPropertyNames";
    ownPropertyNames = JS_Eval(context, getOwnPropertyNames, strlen(getOwnPropertyNames), getOwnPropertyNames, JS_EVAL_TYPE_GLOBAL);

    objectClass = (jclass)(env->NewGlobalRef(env->FindClass("java/lang/Object")));
    booleanClass = (jclass)(env->NewGlobalRef(env->FindClass("java/lang/Boolean")));
    integerClass = (jclass)(env->NewGlobalRef(env->FindClass("java/lang/Integer")));
    longClass = (jclass)(env->NewGlobalRef(env->FindClass("java/lang/Long")));
    doubleClass = (jclass)(env->NewGlobalRef(env->FindClass("java/lang/Double")));
    stringClass = (jclass)(env->NewGlobalRef(env->FindClass("java/lang/String")));
    jsObjectClass = (jclass)(env->NewGlobalRef(env->FindClass("cn/net/zhijian/quickjs/JSObject")));
    jsArrayClass = (jclass)(env->NewGlobalRef(env->FindClass("cn/net/zhijian/quickjs/JSArray")));
    jsFunctionClass = (jclass)(env->NewGlobalRef(env->FindClass("cn/net/zhijian/quickjs/JSFunction")));
    jsCallFunctionClass = (jclass)(env->NewGlobalRef(env->FindClass("cn/net/zhijian/quickjs/JSCallFunction")));
    quickjsContextClass = (jclass)(env->NewGlobalRef(env->FindClass("cn/net/zhijian/quickjs/QuickJSContext")));
    moduleLoaderClass = (jclass)(env->NewGlobalRef(env->FindClass("cn/net/zhijian/quickjs/ModuleLoader")));
    creatorClass = (jclass)(env->NewGlobalRef(env->FindClass("cn/net/zhijian/quickjs/JSObjectCreator")));
    byteArrayClass = (jclass) env->NewGlobalRef(env->FindClass("[B"));

    booleanValueOf = env->GetStaticMethodID(booleanClass, "valueOf", "(Z)Ljava/lang/Boolean;");
    integerValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    longValueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
    doubleValueOf = env->GetStaticMethodID(doubleClass, "valueOf", "(D)Ljava/lang/Double;");

    booleanGetValue = env->GetMethodID(booleanClass, "booleanValue", "()Z");
    integerGetValue = env->GetMethodID(integerClass, "intValue", "()I");
    longGetValue = env->GetMethodID(longClass, "longValue", "()J");
    doubleGetValue = env->GetMethodID(doubleClass, "doubleValue", "()D");
    jsObjectGetValue = env->GetMethodID(jsObjectClass, "getPointer", "()J");

    callFunctionBackM = env->GetMethodID(quickjsContextClass, "callFunctionBack", "(I[Ljava/lang/Object;)Ljava/lang/Object;");
    removeCallFunctionM = env->GetMethodID(quickjsContextClass, "removeCallFunction", "(I)V");
    callFunctionHashCodeM = env->GetMethodID(objectClass, "hashCode", "()I");
    creatorM = env->GetMethodID(quickjsContextClass, "getCreator", "()Lcn/net/zhijian/quickjs/JSObjectCreator;");
    newObjectM = env->GetMethodID(creatorClass, "newObject",
                                     "(Lcn/net/zhijian/quickjs/QuickJSContext;J)Lcn/net/zhijian/quickjs/JSObject;");
    newArrayM = env->GetMethodID(creatorClass, "newArray",
                                    "(Lcn/net/zhijian/quickjs/QuickJSContext;J)Lcn/net/zhijian/quickjs/JSArray;");
    newFunctionM = env->GetMethodID(creatorClass, "newFunction",
                                       "(Lcn/net/zhijian/quickjs/QuickJSContext;JJI)Lcn/net/zhijian/quickjs/JSFunction;");
}

QuickJSWrapper::~QuickJSWrapper() {
    JS_FreeValue(context, ownPropertyNames);
    JS_FreeContext(context);
    JS_FreeRuntime(runtime);

    jniEnv->DeleteGlobalRef(jniThiz);
    jniEnv->DeleteGlobalRef(objectClass);
    jniEnv->DeleteGlobalRef(doubleClass);
    jniEnv->DeleteGlobalRef(integerClass);
    jniEnv->DeleteGlobalRef(longClass);
    jniEnv->DeleteGlobalRef(booleanClass);
    jniEnv->DeleteGlobalRef(stringClass);
    jniEnv->DeleteGlobalRef(jsObjectClass);
    jniEnv->DeleteGlobalRef(jsArrayClass);
    jniEnv->DeleteGlobalRef(jsFunctionClass);
    jniEnv->DeleteGlobalRef(jsCallFunctionClass);
    jniEnv->DeleteGlobalRef(moduleLoaderClass);
    jniEnv->DeleteGlobalRef(quickjsContextClass);
    jniEnv->DeleteGlobalRef(creatorClass);
    jniEnv->DeleteGlobalRef(byteArrayClass);
}

jobject QuickJSWrapper::toJavaObject(JNIEnv *env, jobject thiz, JSValueConst this_obj, JSValueConst value) const{
    jobject result;
    switch (JS_VALUE_GET_NORM_TAG(value)) {
        case JS_TAG_EXCEPTION: {
            result = nullptr;
            break;
        }

        case JS_TAG_STRING: {
            result = toJavaString(env, value);
            break;
        }

        case JS_TAG_BOOL: {
            jvalue v;
            v.z = static_cast<jboolean>(JS_VALUE_GET_BOOL(value));
            result = env->CallStaticObjectMethodA(booleanClass, booleanValueOf, &v);
            break;
        }

        case JS_TAG_INT: {
            jvalue v;
            v.j = static_cast<jint>(JS_VALUE_GET_INT(value));
            result = env->CallStaticObjectMethodA(integerClass, integerValueOf, &v);
            break;
        }

        case JS_TAG_BIG_INT: {
            int64_t e;
            JS_ToBigInt64(context, &e, value);
            jvalue v;
            v.j = e;
            result = env->CallStaticObjectMethodA(longClass, longValueOf, &v);
            break;
        }

        case JS_TAG_FLOAT64: {
            jvalue v;
            double d = JS_VALUE_GET_FLOAT64(value);
            bool isInteger = floor(d) == d;
            if (isInteger) {
                v.j = static_cast<jlong>(d);
                result = env->CallStaticObjectMethodA(longClass, longValueOf, &v);
            } else {
                v.d = static_cast<jdouble>(d);
                result = env->CallStaticObjectMethodA(doubleClass, doubleValueOf, &v);
            }
            break;
        }

        case JS_TAG_OBJECT: {
            auto value_ptr = reinterpret_cast<jlong>(JS_VALUE_GET_PTR(value));
            jobject creatorObj = env->CallObjectMethod(thiz, creatorM);
            if (JS_IsFunction(context, value)) {
                auto obj_ptr = reinterpret_cast<jlong>(JS_VALUE_GET_PTR(this_obj));
                result = env->CallObjectMethod(creatorObj, newFunctionM, thiz, value_ptr, obj_ptr, JS_VALUE_GET_TAG(this_obj));
            } else if (JS_IsArray(value)) {
                result = env->CallObjectMethod(creatorObj, newArrayM, thiz, value_ptr);
            } else if (JS_IsArrayBuffer(value)) {
                size_t byteLength = 0;
                uint8_t *buffer = JS_GetArrayBuffer(context, &byteLength, value);
                jbyteArray byteArray = env->NewByteArray(byteLength);
                void *elementsPtr = env->GetPrimitiveArrayCritical(byteArray, nullptr);
                jbyte *elements = reinterpret_cast<jbyte *>(elementsPtr);
                memcpy(elements, buffer, byteLength);
                result = byteArray;
                JS_FreeValue(context, value);
                env->ReleasePrimitiveArrayCritical(byteArray, elements, 0);
            } else {
                result = env->CallObjectMethod(creatorObj, newObjectM, thiz, value_ptr);
            }
            env->DeleteLocalRef(creatorObj);
            break;
        }

        default:
            result = nullptr;
            break;
    }

    return result;
}

jobject QuickJSWrapper::evaluate(JNIEnv *env, jobject thiz, jstring script, jstring file_name) {
    const char *c_script = env->GetStringUTFChars(script, JNI_FALSE);
    const char *c_file_name = env->GetStringUTFChars(file_name, JNI_FALSE);

    JSValue result = JS_Eval(context, c_script, strlen(c_script), c_file_name, JS_EVAL_TYPE_GLOBAL);
    env->ReleaseStringUTFChars(script, c_script);
    env->ReleaseStringUTFChars(file_name, c_file_name);
    if (JS_IsException(result)) {
        throwJSException(env, context);
        return nullptr;
    }
    
    if(JS_IsPromise(result)) {
        result = waitPromise(env, runtime, context, result);
        if(JS_IsNull(result)) {
            return nullptr;
        }
    }

    return toJavaObject(env, thiz, JS_UNDEFINED, result);
}

jobject QuickJSWrapper::getGlobalObject(JNIEnv *env, jobject thiz) const {
    JSValue value = JS_GetGlobalObject(context);

    auto value_ptr = reinterpret_cast<jlong>(JS_VALUE_GET_PTR(value));
    jobject result = env->CallObjectMethod(env->CallObjectMethod(thiz, creatorM), newObjectM, thiz, value_ptr);

    JS_FreeValue(context, value);
    return result;
}

jobject QuickJSWrapper::getProperty(JNIEnv *env, jobject thiz, jlong value, jstring name) {
    JSValue jsObject = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));

    const char *propsName = env->GetStringUTFChars(name, JNI_FALSE);
    JSValue propsValue = JS_GetPropertyStr(context, jsObject, propsName);
    env->ReleaseStringUTFChars(name, propsName);
    if (JS_IsException(propsValue)) {
        throwJSException(env, context);
        return nullptr;
    }

    return toJavaObject(env, thiz, jsObject, propsValue);
}

jobject QuickJSWrapper::call(JNIEnv *env, jobject thiz, jlong func, jlong this_obj,
                             jint this_obj_tag, jobjectArray args) {
    int argc = env->GetArrayLength(args);
    vector<JSValue> arguments;
    vector<JSValue> freeArguments;
    for (int numArgs = 0; numArgs < argc && !env->ExceptionCheck(); numArgs++) {
        jobject arg = env->GetObjectArrayElement(args, numArgs);
        auto jsArg = toJSValue(env, thiz, arg);
        if (JS_IsException(jsArg)) {
            return nullptr;
        }

        // 基础类型(例如 string )和 Java callback 类型需要使用完 free.
        if (env->IsInstanceOf(arg, stringClass) || env->IsInstanceOf(arg, doubleClass) ||
            env->IsInstanceOf(arg, integerClass) || env->IsInstanceOf(arg, longClass) ||
            env->IsInstanceOf(arg, booleanClass) || env->IsInstanceOf(arg, jsCallFunctionClass)
            || env->IsInstanceOf(arg, byteArrayClass)) {
            freeArguments.push_back(jsArg);
        }

        env->DeleteLocalRef(arg);

        arguments.push_back(jsArg);
    }

    JSValue jsObj = JS_MKPTR(this_obj_tag, reinterpret_cast<void *>(this_obj));
    JSValue jsFunc = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(func));

    JSValue ret = JS_Call(context, jsFunc, jsObj, arguments.size(), arguments.data());
    if (JS_IsException(ret)) {
        throwJSException(env, context);
        return nullptr;
    }

    for (JSValue argument : freeArguments) {
        JS_FreeValue(context, argument);
    }

    // release vector by swap.
    vector<JSValue>().swap(arguments);
    vector<JSValue>().swap(freeArguments);

    if(JS_IsPromise(ret)) {
        ret = waitPromise(env, runtime, context, ret);
        if(JS_IsNull(ret)) {
            return nullptr;
        }
    }

    return toJavaObject(env, thiz, jsObj, ret);
}

jstring QuickJSWrapper::jsonStringify(JNIEnv *env, jlong value) const {
    JSValue obj = JS_JSONStringify(context, JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value)), JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(obj)){
        throwJSException(env, context);
        return nullptr;
    }

    return toJavaString(env, obj);
}

jint QuickJSWrapper::length(JNIEnv *env, jlong value) const {
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));

    JSValue length = JS_GetPropertyStr(context, jsObj, "length");
    if (JS_IsException(length)) {
        throwJSException(env, context);
        return -1;
    }

    JS_FreeValue(context, length);

    return JS_VALUE_GET_INT(length);
}

jobject QuickJSWrapper::get(JNIEnv *env, jobject thiz, jlong value, jint index) {
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    JSValue child = JS_GetPropertyUint32(context, jsObj, index);

    return toJavaObject(env, thiz, jsObj, child);
}

void QuickJSWrapper::set(JNIEnv *env, jobject thiz, jlong this_obj, jobject value, jint index) {
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(this_obj));
    JSValue child = toJSValue(env, thiz, value);
    if (JS_IsString(child)) {
        // JSString 类型不需要 JS_DupValue
        JS_SetPropertyUint32(context, jsObj, index, child);
    } else {
        JS_SetPropertyUint32(context, jsObj, index, JS_DupValue(context, child));
    }
}

void
QuickJSWrapper::setProperty(JNIEnv *env, jobject thiz, jlong this_obj, jstring name, jobject value) const {
    const char* propName = env->GetStringUTFChars(name, JNI_FALSE);
    JSValue propValue = toJSValue(env, thiz, value);
    if(env->IsInstanceOf(value, jsObjectClass)) {
        // 这里需要手动增加引用计数，不然 QuickJS 垃圾回收会报 assertion "p->ref_count > 0" 的错误。
        JS_DupValue(context, propValue);
    } else if (env->IsInstanceOf(value, jsCallFunctionClass)) {
        // 通过 JS_NewCFunctionData 创建的 fn 对象的 name 属性值被定义为 Empty 了，
        // 这里需要额外定义下，不然 js 层拿到的 fn.name 的值为空.
        JSAtom name_atom = JS_NewAtom(context, propName);
        JSAtom name_atom_key = JS_NewAtom(context, "name");
        JS_DefinePropertyValue(context, propValue, name_atom_key,
                               JS_AtomToString(context, name_atom), JS_PROP_CONFIGURABLE);
        JS_FreeAtom(context, name_atom);
        JS_FreeAtom(context, name_atom_key);
    }

    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(this_obj));
    JS_SetPropertyStr(context, jsObj, propName, propValue);

    env->ReleaseStringUTFChars(name, propName);
}

JSValue QuickJSWrapper::jsFuncCall(int callback_id, JSValueConst this_val, int argc, JSValueConst *argv){
    if (jniEnv->ExceptionCheck()) {
        return JS_EXCEPTION;
    }

    jobjectArray javaArgs = jniEnv->NewObjectArray((jsize)argc, objectClass, nullptr);

    for (int i = 0; i < argc; i++) {
        JSValue v = JS_DupValue(context, argv[i]);
        jobject java_arg = toJavaObject(jniEnv, jniThiz, this_val, v);
        jniEnv->SetObjectArrayElement(javaArgs, (jsize)i, java_arg);
        jniEnv->DeleteLocalRef(java_arg);
    }

    jobject result = jniEnv->CallObjectMethod(jniThiz, callFunctionBackM, callback_id, javaArgs);

    jniEnv->DeleteLocalRef(javaArgs);

    JSValue jsValue = toJSValue(jniEnv, jniThiz, result);

    jniEnv->DeleteLocalRef(result);
    return jsValue;
}

void QuickJSWrapper::removeCallFunction(int callback_id) const {
    if (jniEnv->ExceptionCheck()) {
        return;
    }

    jniEnv->CallVoidMethod(jniThiz, removeCallFunctionM, callback_id);
}

JSValue QuickJSWrapper::toJSValue(JNIEnv *env, jobject thiz, jobject value) const {
    if (value == nullptr) {
        return JS_NULL;
    }

    JSValue result;
    if (env->IsInstanceOf(value, stringClass)) {
        const auto s = env->GetStringUTFChars((jstring)(value), JNI_FALSE);
        result = JS_NewString(context, s);
        env->ReleaseStringUTFChars((jstring)(value), s);
    } else if (env->IsInstanceOf(value, doubleClass)) {
        result = JS_NewFloat64(context, env->CallDoubleMethod(value, doubleGetValue));
    } else if (env->IsInstanceOf(value, integerClass)) {
        result = JS_NewInt32(context, env->CallIntMethod(value, integerGetValue));
    } else if(env->IsInstanceOf(value, longClass)) {
        jlong l_val = env->CallLongMethod(value, longGetValue);
        if (l_val > MAX_SAFE_INTEGER || l_val < -MAX_SAFE_INTEGER) {
            result = JS_NewBigInt64(context, l_val);
        } else {
            result = JS_NewInt64(context, l_val);
        }
    } else if (env->IsInstanceOf(value, booleanClass)) {
        result = JS_NewBool(context, env->CallBooleanMethod(value, booleanGetValue));
    } else if (env->IsInstanceOf(value, byteArrayClass)) {
        jbyteArray bytes = static_cast<jbyteArray>(value);
        jbyte* byteData = env->GetByteArrayElements(bytes, nullptr);
        jsize length = env->GetArrayLength(bytes);
        result = JS_NewArrayBufferCopy(context, reinterpret_cast<uint8_t*>(byteData), length);
        env->ReleaseByteArrayElements(bytes, byteData, JNI_ABORT);
    } else if (env->IsInstanceOf(value, jsObjectClass)) {
        result = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(env->CallLongMethod(value, jsObjectGetValue)));
    } else if (env->IsInstanceOf(value, jsCallFunctionClass)) {
        // 这里的 obj 是用来获取 JSFuncCallback 对象的
        JSValue obj = JS_NewObjectClass(context, js_func_callback_class_id);
        result = JS_NewCFunctionData(context, jsFnCallback, 1, 0, 1, &obj);
        // JS_NewCFunctionData 有 dupValue obj，这里需要对 obj 计数减一，保持计数平衡
        JS_FreeValue(context, obj);

        int *callbackId = new int(jniEnv->CallIntMethod(value, callFunctionHashCodeM));
        JS_SetOpaque(obj, callbackId);
    } else {
        jclass classType = env->GetObjectClass(value);
        const string typeName = getJavaName(env, classType);
        env->DeleteLocalRef(classType);
        // Throw an exception for unsupported argument type.
        throwJavaException(env, "java/lang/IllegalArgumentException", "Unsupported Java type %s",
                           typeName.c_str());
        result = JS_EXCEPTION;
    }

    return result;
}

void QuickJSWrapper::freeValue(jlong value) const {
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    JS_FreeValue(context, jsObj);
}

void QuickJSWrapper::dupValue(jlong value) const {
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    JS_DupValue(context, jsObj);
}

/**
 * @deprecated
 * See {@link freeValue(String)}
 * @param value
 */
void QuickJSWrapper::freeDupValue(jlong value) const {
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    JS_FreeValue(context, jsObj);
}

jobject QuickJSWrapper::parseJSON(JNIEnv *env, jobject thiz, jstring json) {
    const char *c_json = env->GetStringUTFChars(json, JNI_FALSE);
    JSValue jsonObj = JS_ParseJSON(context, c_json, strlen(c_json), "parseJSON.js");
    env->ReleaseStringUTFChars(json, c_json);
    if (JS_IsException(jsonObj)) {
        throwJSException(env, context);
        return nullptr;
    }
    return toJavaObject(env, thiz, JS_UNDEFINED, jsonObj);
}

jbyteArray QuickJSWrapper::compile(JNIEnv *env, jstring source, jstring file_name, jboolean isModule) const {
    const auto sourceCode = env->GetStringUTFChars(source, JNI_FALSE);
    const auto fileName = env->GetStringUTFChars(file_name, JNI_FALSE);
    auto eval_flags =  JS_EVAL_FLAG_COMPILE_ONLY;
    if (isModule) {
        eval_flags = JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY;
    }
    auto compiled = JS_Eval(context, sourceCode, strlen(sourceCode), fileName, eval_flags);
    env->ReleaseStringUTFChars(source, sourceCode);
    env->ReleaseStringUTFChars(file_name, fileName);

    if (JS_IsException(compiled)) {
        throwJSException(env, context);
        return nullptr;
    }

    size_t bufferLength = 0;
    auto buffer = JS_WriteObject(context, &bufferLength, compiled, JS_WRITE_OBJ_BYTECODE | JS_WRITE_OBJ_REFERENCE);

    auto result = buffer && bufferLength > 0 ? env->NewByteArray(bufferLength) : nullptr;
    if (result) {
        env->SetByteArrayRegion(result, 0, bufferLength, reinterpret_cast<const jbyte*>(buffer));
    } else {
        throwJSException(env, context);
    }

    JS_FreeValue(context, compiled);
    js_free(context, buffer);

    return result;
}

jobject QuickJSWrapper::execute(JNIEnv *env, jobject thiz, jbyteArray bytecode) {
    if(bytecode == nullptr) {
        throwJSException(env, "bytecode can not be null");
        return nullptr;
    }

    const auto buffer = env->GetByteArrayElements(bytecode, nullptr);
    const auto bufferLength = env->GetArrayLength(bytecode);
    const auto flags = JS_READ_OBJ_BYTECODE | JS_READ_OBJ_REFERENCE;
    auto obj = JS_ReadObject(context, reinterpret_cast<const uint8_t*>(buffer), bufferLength, flags);
    env->ReleaseByteArrayElements(bytecode, buffer, JNI_ABORT);

    if (JS_IsException(obj)) {
        throwJSException(env, context);
        return nullptr;
    }

    if (JS_ResolveModule(context, obj)) {
        // TODO throwJsExceptionFmt(env, this, "Failed to resolve JS module");
        return nullptr;
    }

    JSValue val = JS_EvalFunction(context, obj);
    if(JS_IsPromise(val)) {
        val = waitPromise(env, runtime, context, val);
        if(JS_IsNull(val)) {
            return nullptr;
        }
    }

    if (JS_IsException(val)) {
        throwJSException(env, context);
        return nullptr;
    }

    return toJavaObject(env, thiz, JS_UNDEFINED, val);
}

jobject QuickJSWrapper::evaluateModule(JNIEnv *env, jobject thiz, jstring script, jstring file_name) {
    const char *c_script = env->GetStringUTFChars(script, JNI_FALSE);
    const char *c_file_name = env->GetStringUTFChars(file_name, JNI_FALSE);

    // 1. 编译模块，返回的是 JSModuleDef 包装对象（tag = module）
    JSValue module = JS_Eval(context, c_script, strlen(c_script), c_file_name,
         JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
    env->ReleaseStringUTFChars(script, c_script);
    env->ReleaseStringUTFChars(file_name, c_file_name);

    if (JS_IsException(module)) {
        throwJSException(env, context);
        return nullptr;
    }

    // 关键：立即保存 JSModuleDef* 指针
    // JS_Eval返回的tag是JS_TAG_MODULE，JS_VALUE_GET_PTR直接取到JSModuleDef*
    JSModuleDef *m = (JSModuleDef *)JS_VALUE_GET_PTR(module);

    // 2. 执行模块（module 变量会被释放，但m指针仍有效，由JSContext持有）
    JSValue result = JS_EvalFunction(context, module);
    //JS_FreeValue(context, module); //不可释放
    if (JS_IsException(result)) {
        throwJSException(env, context);
        return nullptr;
    }

    // 3. 处理 Promise
    if (JS_IsPromise(result)) {
        result = waitPromise(env, runtime, context, result);
        if (JS_IsNull(result)) {
            return nullptr;
        }
    }
    JS_FreeValue(context, result);  // Promise 对象用完即释放，非 Promise 也释放

    // 4. 统一用编译阶段保存的 m 获取模块命名空间
    JSValue module_ns = JS_GetModuleNamespace(context, m);
    if (JS_IsException(module_ns)) {
        throwJSException(env, context);
        return nullptr;
    }
    // 5. 转 Java 对象
    JSValue global = JS_GetGlobalObject(context);
    jobject jsObj = toJavaObject(env, thiz, global, module_ns);
    JS_FreeValue(context, global);
    
    return jsObj;
}

jobject QuickJSWrapper::getOwnPropertyNames(JNIEnv *env, jobject thiz, jlong obj) {
    if (JS_IsException(ownPropertyNames)) {
        throwJSException(env, context);
        return nullptr;
    }

    JSValue jsObject = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(obj));
    JSValue ret = JS_Call(context, ownPropertyNames, JS_NULL, 1, &jsObject);
    if (JS_IsException(ret)) {
        throwJSException(env, context);
        return nullptr;
    }

    return toJavaObject(env, thiz, JS_UNDEFINED, ret);
}

jstring QuickJSWrapper::toJavaString(JNIEnv *env, JSValue value) const {
    jstring result;
#ifdef IS_ANDROID
    const char* string = JS_ToCString(context, value);
    result = env->NewStringUTF(string);
    JS_FreeCString(context, string);
#else
    // 这里需要注意，JVM 平台下 NewStringUTF 方法对部分 unicode 的转换有问题，会出现乱码，换了另一种方式解决。
    const char *str;
    size_t len;
    str = JS_ToCStringLen(context, &len, value);

    jbyteArray jba = env->NewByteArray(len);
    env->SetByteArrayRegion(jba, 0, len, reinterpret_cast<const jbyte *>(str));

    result = static_cast<jstring>(env->NewObject(stringClass,
                                                 env->GetMethodID(stringClass, "<init>", "([B)V"),
                                                 jba));

    JS_FreeCString(context, str);
    env->DeleteLocalRef(jba);
#endif
    // JSString 类型的 JSValue 需要手动释放掉，不然会泄漏
    JS_FreeValue(context, value);

    return result;
}
