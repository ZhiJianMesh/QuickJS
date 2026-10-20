#include <jni.h>
#include <string>
#include <cerrno>
#include <cstring>
#include "quickjs_wrapper.h"
#include <vector>

#if defined(_WIN32)
#include <windows.h>
#else
#include <pthread.h>
#endif

// 当前线程栈的总大小，取不到就返回 0 表示"不知道"。
static size_t current_thread_stack_size() {
#if defined(_WIN32)
    //mingw 只前置声明了 struct _TEB，NT_TIB 是 TEB 的头一个成员，按 NT_TIB 取就够
    PNT_TIB tib = (PNT_TIB) NtCurrentTeb();
    if (tib == nullptr) {
        return 0;
    }
    return (size_t) ((char *) tib->StackBase - (char *) tib->StackLimit);
#else
    pthread_attr_t attr;
    void *base = nullptr;
    size_t size = 0;
    if (pthread_getattr_np(pthread_self(), &attr) == 0) {
        pthread_attr_getstack(&attr, &base, &size);
        pthread_attr_destroy(&attr);
    }
    return size;
#endif
}

extern "C"
JNIEXPORT void JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_destroyContext(JNIEnv *env, jobject thiz,
                                                           jlong context) {
    delete reinterpret_cast<QuickJSWrapper*>(context);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_evaluate(JNIEnv *env, jobject thiz, jlong context, jstring script,
                                                     jstring file_name) {
    if (script == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "Script cannot be null");
        return nullptr;
    }

    if (file_name == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "File name cannot be null");
        return nullptr;
    }

    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->evaluate(env, thiz, script, file_name);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_getGlobalObject(JNIEnv *env, jobject thiz,
                                                            jlong context) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->getGlobalObject(env, thiz);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_getProperty(JNIEnv *env, jobject thiz, jlong context, jlong value,
                                                 jstring name) {
    if (name == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "Property Name cannot be null");
        return nullptr;
    }

    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->getProperty(env, thiz, value, name);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_call(JNIEnv *env, jobject thiz, jlong context,
                                                 jlong func, jlong this_obj, jint this_obj_tag, jobjectArray args) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->call(env, thiz, func, this_obj, this_obj_tag, args);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_stringify(JNIEnv *env, jobject thiz, jlong context,
                                               jlong value) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->jsonStringify(env, value);
}

extern "C"
JNIEXPORT jint JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_length(JNIEnv *env, jobject thiz, jlong context,
                                               jlong value) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->length(env, value);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_get(JNIEnv *env, jobject thiz, jlong context, jlong value,
                                         jint index) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->get(env, thiz, value, index);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_createContext(JNIEnv *env, jobject thiz, jlong runtime) {
    auto *wrapper = new(std::nothrow) QuickJSWrapper(env, thiz, reinterpret_cast<JSRuntime *>(runtime));
    if (!wrapper || !wrapper->context || !wrapper->runtime) {
        delete wrapper;
        wrapper = nullptr;
    }

    return reinterpret_cast<jlong>(wrapper);
}

extern "C"
JNIEXPORT void JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_setProperty(JNIEnv *env, jobject thiz, jlong context,
                                                        jlong this_obj, jstring name,
                                                        jobject value) {
    if (name == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "Property Name cannot be null");
        return;
    }

    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    wrapper->setProperty(env, thiz, this_obj, name, value);
}

extern "C"
JNIEXPORT void JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_freeValue(JNIEnv *env, jobject thiz, jlong context,
                                                      jlong value) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    wrapper->freeValue(value);
}

extern "C"
JNIEXPORT void JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_dupValue(JNIEnv *env, jobject thiz, jlong context,
                                                     jlong value) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    wrapper->dupValue(value);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_parseJSON(JNIEnv *env, jobject thiz, jlong context,
                                                      jstring json) {
    if (json == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "JSON cannot be null");
        return nullptr;
    }

    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->parseJSON(env, thiz, json);
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_compile(JNIEnv *env, jobject thiz, jlong context,
                                                    jstring source_code, jstring file_name, jboolean isModule) {
    if (source_code == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "Source code cannot be null");
        return nullptr;
    }

    if (file_name == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "File name cannot be null");
        return nullptr;
    }

    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->compile(env, source_code, file_name, isModule);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_execute(JNIEnv *env, jobject thiz, jlong context,
                                                    jbyteArray bytecode) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->execute(env, thiz, bytecode);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_evaluateModule(JNIEnv *env, jobject thiz, jlong context,
                                                           jstring script, jstring file_name) {
    if (script == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "Script cannot be null");
        return nullptr;
    }

    if (file_name == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "File name cannot be null");
        return nullptr;
    }

    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->evaluateModule(env, thiz, script, file_name);
}

extern "C"
JNIEXPORT void JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_set(JNIEnv *env, jobject thiz, jlong context,
                                                jlong this_obj, jobject value, jint index) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    wrapper->set(env, thiz, this_obj, value, index);
}
extern "C"
JNIEXPORT void JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_setMaxStackSize(JNIEnv *env, jclass thiz,
                                                            jlong runtime, jint size) {
    auto *rt = reinterpret_cast<JSRuntime*>(runtime);
    JS_SetMaxStackSize(rt, size);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_isLiveObject(JNIEnv *env, jclass thiz, jlong runtime,
                                                         jlong value) {
    auto *rt = reinterpret_cast<JSRuntime*>(runtime);
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    if (JS_IsLiveObject(rt, jsObj)) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_runGC(JNIEnv *env, jclass thiz, jlong runtime) {
    auto *rt = reinterpret_cast<JSRuntime*>(runtime);
    JS_RunGC(rt);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_createRuntime(JNIEnv *env, jclass clazz) {
    auto *rt = JS_NewRuntime();
    if (rt == nullptr) {
        return 0;
    }

    //quickjs-ng 默认给 JS 的栈预算就是 JS_DEFAULT_STACK_SIZE(1M)，而 JVM 每个线程的栈
    //默认也只有 1M（Java 主线程同样被 -Xss 限制，ulimit 的 8M 用不上）。
    //预算等于真实栈，递归到 600 层左右会先撞上栈的 guard page：整个进程 SIGSEGV，
    //RangeError 根本来不及抛，Java 侧连 try/catch 都拦不住。
    //这里按线程实际栈大小留出自上层帧和抛异常路径的余量，宁可早点抛错也不能崩进程。
    size_t budget = JS_DEFAULT_STACK_SIZE;
    size_t total = current_thread_stack_size();
    if (total > 0) {
        const size_t headroom = 192 * 1024;
        size_t usable = total > headroom ? total - headroom : 0;
        if (usable < budget) {
            //下限 64K：再小栈的线程也得留几十层递归，否则直接不可用
            budget = usable < 64 * 1024 ? 64 * 1024 : usable;
        }
    }
    JS_SetMaxStackSize(rt, budget);
    //栈顶要在设置预算之后再取一次，顺序反了 stack_limit 会按旧值算
    JS_UpdateStackTop(rt);
    return reinterpret_cast<jlong>(rt);
}

extern "C"
JNIEXPORT void JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_setMemoryLimit(JNIEnv *env, jclass clazz, jlong runtime,
                                                           jint size) {
    auto *rt = reinterpret_cast<JSRuntime*>(runtime);
    JS_SetMemoryLimit(rt, size);
}

extern "C"
JNIEXPORT void JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_dumpMemoryUsage(JNIEnv *env, jclass clazz,
                                                            jlong runtime, jstring file_name) {
    auto *rt = reinterpret_cast<JSRuntime*>(runtime);

    if (file_name == nullptr) {
        JSMemoryUsage stats;
        JS_ComputeMemoryUsage(rt, &stats);
        JS_DumpMemoryUsage(stdout, &stats, rt);
    } else {
        const char *path = env->GetStringUTFChars(file_name, JNI_FALSE);
        errno = 0;
        auto file = fopen(path, "w");
        int openErr = errno;
        // fopen 失败是 IO 错误，不是空指针；抛项目统一的 QuickJSException 并带上文件名与
        // 系统错误原因，避免误导成 NullPointerException。path 必须在 ReleaseStringUTFChars
        // 之前拷进 std::string，否则指针已失效。
        std::string openFailMsg;
        if (!file) {
            openFailMsg = std::string("Failed to open file '") + path
                          + "' for writing: " + strerror(openErr);
        }
        env->ReleaseStringUTFChars(file_name, path);
        if (!file) {
            jclass exClass = env->FindClass("cn/net/zhijian/quickjs/QuickJSException");
            env->ThrowNew(exClass, openFailMsg.c_str());
            return;
        }

        JSMemoryUsage stats;
        JS_ComputeMemoryUsage(rt, &stats);
        JS_DumpMemoryUsage(file, &stats, rt);

        fclose(file);
    }
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_getOwnPropertyNames(JNIEnv *env, jobject thiz,
                                                                jlong context, jlong obj_value) {
    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context);
    return wrapper->getOwnPropertyNames(env, thiz, obj_value);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_getMemoryUsedSize(JNIEnv *env, jobject thiz,
                                                              jlong runtime) {
    auto *rt = reinterpret_cast<JSRuntime*>(runtime);
    JSMemoryUsage usage;
    JS_ComputeMemoryUsage(rt, &usage);
    return (jlong)usage.memory_used_size;
}

extern "C"
JNIEXPORT void JNICALL
Java_cn_net_zhijian_quickjs_QuickJSContext_setGCThreshold(JNIEnv *env, jobject thiz, jlong runtime,
                                                           jint size) {
    auto *rt = reinterpret_cast<JSRuntime*>(runtime);
    // use -1 to disable automatic GC
    if (size < 0) {
        size = -1;
    }
    JS_SetGCThreshold(rt, size);
}
