package cn.net.zhijian.quickjs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(value = RetentionPolicy.RUNTIME)
@Target(value = {ElementType.METHOD})
//不使用JavascriptInterface，避免与android.webkit.JavascriptInterface重名
public @interface JavascriptMethod {
}