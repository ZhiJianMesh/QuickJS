cmake_minimum_required(VERSION 3.15)
project(quickjs CXX C)  # 启用 C++ 和 C

# 强制使用 C++14 以避免 std::byte 与 MinGW 冲突
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================================
# 配置：通过外部传入变量
# ============================================================================
# QUICKJS_SOURCE_DIR: quickjs-ng源码路径
# JNI_WRAPPER_SOURCE_DIR: JNI包装器源码路径
# JAVA_INCLUDE_PATH: JDK 的 include 目录（包含 jni.h）
# JAVA_INCLUDE_PATH2: JDK 的平台相关头文件目录（如 linux/win32/darwin）
# CMAKE_SYSTEM_NAME: 目标操作系统 (Linux, Windows, Linux-Android)
# CMAKE_SYSTEM_PROCESSOR: 目标架构 (x86_64, aarch64)
# ============================================================================

if(NOT QUICKJS_SOURCE_DIR)
    set(QUICKJS_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/native/quickjs-ng")
endif()
if(NOT JNI_WRAPPER_SOURCE_DIR)
    set(JNI_WRAPPER_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/native/wrapper")
endif()

message(STATUS "QuickJS source dir: ${QUICKJS_SOURCE_DIR}")
message(STATUS "JNI wrapper source dir: ${JNI_WRAPPER_SOURCE_DIR}")

## 查找 JNI (只需要头文件，不强制 AWT)
#find_package(JNI COMPONENTS JVM REQUIRED)
#message(STATUS "JNI include dirs: ${JNI_INCLUDE_DIRS}")

# 检查 JNI 头文件路径是否已传入
if(NOT JAVA_INCLUDE_PATH OR NOT JAVA_INCLUDE_PATH2)
    message(FATAL_ERROR "JAVA_INCLUDE_PATH and JAVA_INCLUDE_PATH2 must be provided via -D")
endif()
message(STATUS "JAVA_INCLUDE_PATH = ${JAVA_INCLUDE_PATH}")
message(STATUS "JAVA_INCLUDE_PATH2 = ${JAVA_INCLUDE_PATH2}")
set(JNI_INCLUDE_DIRS ${JAVA_INCLUDE_PATH} ${JAVA_INCLUDE_PATH2})

message(STATUS "CMAKE_SYSTEM_NAME = ${CMAKE_SYSTEM_NAME}")

# 从 QuickJS 源文件编译静态库
file(GLOB QUICKJS_SOURCES "${QUICKJS_SOURCE_DIR}/*.c")
# 排除不需要的源文件
list(REMOVE_ITEM QUICKJS_SOURCES
    "${QUICKJS_SOURCE_DIR}/qjs.c" #用于命令行工具
    "${QUICKJS_SOURCE_DIR}/qjsc.c"
    "${QUICKJS_SOURCE_DIR}/qjs-wasi-reactor.c" #用于WASI(WebAssembly System Interface)
    "${QUICKJS_SOURCE_DIR}/ctest.c"
    "${QUICKJS_SOURCE_DIR}/api-test.c"
    "${QUICKJS_SOURCE_DIR}/lre-test.c"
    "${QUICKJS_SOURCE_DIR}/run-test262.c"
    "${QUICKJS_SOURCE_DIR}/unicode_gen.c"
)
# 编译 QuickJS 为静态库，并强制启用 -fPIC
add_library(quickjs STATIC ${QUICKJS_SOURCES})
#add_compile_definitions(_GNU_SOURCE)
target_compile_options(quickjs PRIVATE -fPIC)
target_compile_definitions(quickjs PRIVATE _GNU_SOURCE CONFIG_BIGNUM
    CONFIG_DLOPEN=0        # 关键：禁用 dlopen
    CONFIG_DL=0            # 关键：禁用 dl
)
target_include_directories(quickjs PRIVATE ${QUICKJS_SOURCE_DIR})

# ----------------------------------------------------------------------------
# JNI 包装器 (C++)
# ----------------------------------------------------------------------------
file(GLOB JNI_WRAPPER_SOURCES
    "${JNI_WRAPPER_SOURCE_DIR}/*.cpp"
)
if(NOT JNI_WRAPPER_SOURCES)
    message(FATAL_ERROR "No JNI wrapper source found in ${JNI_WRAPPER_SOURCE_DIR}")
endif()
message(STATUS "JNI wrapper sources: ${JNI_WRAPPER_SOURCES}")

add_library(quickjs-jni-wrapper SHARED ${JNI_WRAPPER_SOURCES})
target_include_directories(quickjs-jni-wrapper PRIVATE
    ${QUICKJS_SOURCE_DIR}
    ${JNI_INCLUDE_DIRS}
)

# 条件化链接：Windows 不需要 m 和 dl
if(WIN32)
    target_link_libraries(quickjs-jni-wrapper PRIVATE quickjs)
    # 解决 byte 歧义问题：禁用 std::byte
    target_compile_definitions(quickjs-jni-wrapper PRIVATE _HAS_STD_BYTE=0)
    set_target_properties(quickjs-jni-wrapper PROPERTIES LINK_FLAGS "-static-libgcc -static-libstdc++ -static")
elseif(CMAKE_SYSTEM_NAME MATCHES "Android")
    # Termux / Android: 链接 C++ 共享库，不需要 m 和 dl (Bionic 内置)
    target_link_libraries(quickjs-jni-wrapper PRIVATE quickjs c++_shared)
    target_compile_definitions(quickjs-jni-wrapper PRIVATE __ANDROID__)
    target_link_options(quickjs-jni-wrapper PRIVATE -Wl,--as-needed -rtlib=compiler-rt)
    target_link_options(quickjs-jni-wrapper PRIVATE -rtlib=compiler-rt)
    # 关键：禁用 C++ 异常和 RTTI，通常能彻底消除 libgcc_s.so.1 依赖
    target_compile_options(quickjs-jni-wrapper PRIVATE -fno-exceptions -fno-rtti -rtlib=compiler-rt)
else()
    target_link_libraries(quickjs-jni-wrapper PRIVATE quickjs m dl)
endif()

# 允许 C++ 编译器中 void* → uint8_t* 的隐式转换（与 C 行为一致）
target_compile_options(quickjs-jni-wrapper PRIVATE -fpermissive)

# 设置动态库输出名称和路径
# 注意：最终产物放在 build 目录下的 native/${CMAKE_SYSTEM_NAME}/${CMAKE_SYSTEM_PROCESSOR}/
set_target_properties(quickjs-jni-wrapper PROPERTIES
    PREFIX ""
    OUTPUT_NAME "quickjs-jni-wrapper"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/native/${CMAKE_SYSTEM_NAME}/${CMAKE_SYSTEM_PROCESSOR}"
)

# 平台特定编译选项
if(WIN32)
    target_compile_definitions(quickjs PRIVATE CONFIG_WIN32)
endif()
if(APPLE)
    target_compile_definitions(quickjs PRIVATE CONFIG_DARWIN)
endif()
# Termux 不需要额外宏
