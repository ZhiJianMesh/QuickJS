package cn.net.zhijian.quickjs;

/**
 * Created by Harlon Wang on 2023/8/26.
 * 该类仅提供给 Native 层调用
 */
public abstract class ModuleLoader {
    /**
     * 模块加载模式：
     * True 会调用 {@link #getBytecode(String)}
     * False 会调用 {@link #getStringCode(String)}
     * @return 是否字节码模式
     */
    public abstract boolean isBytecodeMode();

    /**
     * 获取字节码代码内容
     * @param moduleName 模块路径名，例如 "xxx.js"
     * @return 代码内容
     */
    public abstract byte[] getBytecode(String moduleName);

    /**
     * 获取字符串代码内容
     * @param moduleName 模块路径名，例如 "xxx.js"
     * @return 代码内容
     */
    public abstract String getStringCode(String moduleName);

    /**
     * 该方法返回结果会作为 moduleName 参数给到 {@link #getBytecode(String)}
     * 或者 {@link #getStringCode(String)} 中使用，默认返回 moduleName。
     * 一般可以在这里对模块名称进行转换处理。
     * @param baseModuleName 使用 Import 的所在模块名称
     * @param moduleName 需要加载的模块名称
     * @return 模块名称
     */
    public String normalizeName(String baseModuleName, String moduleName) {
        return moduleName;
    }
}
