package cn.net.zhijian.quickjs;

import java.io.*;
import java.nio.file.*;

class NativeLibraryLoader {
    public static void load() {
        String osName = osName();
        String libName = libName(osName);
        String archName = archName();
        String resourcePath = String.format("native/%s/%s/%s", osName, archName, libName);

        try {
            if(isAndroid()) { 
                //安卓的原生库需要放在jniLibs下，打包成aar后，默认从`jni/架构名/`目录中加载
                //同样的loadLibrary(name)，windows中加载name.dll
                //linux/android加载libname.so，macOS加载libname.dylib
                //所以，在不同系统中，使用独立的动态库，要注意名称
                QuickJSLogger.instance().info("load {} from android system", libName);
                int idx = libName.lastIndexOf('.'); //不要末尾的.so
                System.loadLibrary(libName.substring(0, idx));
                return;
            }
            
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
            if (in == null) {
                QuickJSLogger.instance().warn("{} not exists, try to load {} from system", resourcePath, libName);
                // 如果在JAR中找不到对应的资源，回退到系统库路径
                int idx = libName.lastIndexOf('.');
                System.loadLibrary(libName.substring(0, idx));
                return;
            }

            QuickJSLogger.instance().info("Load {} from jar", resourcePath);
            // 将库文件从 JAR 提取到临时目录
            Path tempFile = Files.createTempFile("libquickjs_", "_" + libName);
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            tempFile.toFile().deleteOnExit(); //退出时删除临时文件

            // 加载提取出来的动态库
            System.load(tempFile.toAbsolutePath().toString());
        } catch (Exception e) {
            throw new QuickJSException("Failed to load native library from " + resourcePath, e);
        }
    }
    
    private static boolean isTermux() {
        String termuxVersion = System.getenv("TERMUX_VERSION");
        if (termuxVersion != null && !termuxVersion.isEmpty()) {
            return true;
        }
        // 备用检查
        String home = System.getProperty("user.home");
        return home != null && home.contains("/data/data/com.termux");
    }
    
    private static boolean isAndroid() {
        String s = System.getProperty("java.vendor");
        // 注意: "Android" 和 "Dalvik/ART" 的大小写准确
        if(s.contains("Android")) {
            return true;
        }
        
        s = System.getProperty("java.vm.name");
        if("Dalvik".equals(s) || "ART".equals(s)) {
            return true;
        }
        return false;
    }
    
    private static String osName() {
        String osName = System.getProperty("os.name");
        if (osName == null) return "unknown";
        if(isTermux()) {
            return "Linux-Android";
        }
        if(isAndroid()) {
            return "Android";
        }
        String os = osName.toLowerCase();
        if (os.startsWith("win")) {
            return "Windows";
        }
        
        return "Linux";
    }

    private static String archName() {
        String arch = System.getProperty("os.arch");
        if (arch == null) return "unknown";
        if (arch.equals("amd64") || arch.equals("x86_64")) return "x86_64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
        return arch;
    }

    private static String libName(String os) {
        return os.equals("Windows") ? "quickjs-jni-wrapper.dll" : "quickjs-jni-wrapper.so";
    }
}
