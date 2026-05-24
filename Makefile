# ============================================================================
# Makefile – 为 HarlonWang/quickjs-wrapper 构建多平台动态库
# 项目结构：
#   src/main/java/                      - Java 源码
#   src/main/native/quickjs-ng/         - quickjs-ng 源码 (自动克隆)
#   src/main/native/wrapper/            - JNI 包装器 C 源码
#   src/main/resources/native/           - 生成的动态库 (按 OS/ARCH 存放)
# wsl+ubuntu环境交叉编译，生成linux、windows系统的64位原生库，需安装：
#    git
#    make cmake gcc g++
#    aarch64-linux-gnu-gcc aarch64-linux-gnu-g++
#    x86_64-w64-mingw32-gcc x86_64-w64-mingw32-g++
# termux环境除git、make、cmake外，还需安装：
#    clang clang++
# ============================================================================

# ----- 用户配置 -------------------------------------------------------------
JDK_HOME            ?= /usr/lib/jvm/java-21-openjdk-amd64
QUICKJS_NG_VERSION  ?= v0.14.0

# ----- 路径定义 -------------------------------------------------------------
PROJECT_ROOT        := $(realpath $(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
NATIVE_SRC_DIR      := $(PROJECT_ROOT)/native
QUICKJS_SRC_DIR     := $(NATIVE_SRC_DIR)/quickjs-ng
JNI_WRAPPER_SRC_DIR := $(NATIVE_SRC_DIR)/wrapper
RESOURCE_NATIVE_DIR := $(PROJECT_ROOT)/src/main/resources/native
BUILD_DIR           := $(PROJECT_ROOT)/build

# ----- 获取 quickjs-ng 源码 -------------------------------------------------
.PHONY: get-quickjs
get-quickjs:
	@echo ">>> 获取 quickjs-ng $(QUICKJS_NG_VERSION) ..."
	@if [ ! -d "$(QUICKJS_SRC_DIR)" ]; then \
		mkdir -p $(NATIVE_SRC_DIR); \
		git clone --depth 1 --branch $(QUICKJS_NG_VERSION) https://github.com/quickjs-ng/quickjs.git $(QUICKJS_SRC_DIR); \
	else \
		echo "quickjs-ng 已存在: $(QUICKJS_SRC_DIR)"; \
	fi

# ----- 通用构建函数 ---------------------------------------------------------
# 参数: $(1)构建目录, $(2)系统名, $(3)架构, $(4)C编译器, $(5)CXX编译器,
#       $(6)CMake额外选项, $(7)输出后缀, $(8)JAVA_INCLUDE_PATH2 (JNI 第二个头文件路径)
# cmake会使用CMakeList.txt中的配置，这里-D的参数会传入其中
define build_platform
	@mkdir -p $(PROJECT_ROOT)/$(1)
	@echo ">>> 构建 $(2)/$(3) ..."
	# 1. 编译 quickjs-ng 动态库（注意：这里使用 $(QUICKJS_SRC_DIR)）
	cd $(PROJECT_ROOT)/$(1) && \
		cmake $(QUICKJS_SRC_DIR) \
			-DCMAKE_SYSTEM_NAME=$(2) \
			-DCMAKE_SYSTEM_PROCESSOR=$(3) \
			-DCMAKE_C_COMPILER=$(4) \
			-DCMAKE_CXX_COMPILER=$(5) \
			-DCMAKE_BUILD_TYPE=Release \
			-DBUILD_SHARED_LIBS=ON \
			-DQJS_BUILD_EXAMPLES=OFF \
			-DJAVA_INCLUDE_PATH="$(JDK_HOME)/include" \
			-DJAVA_INCLUDE_PATH2="$(JDK_HOME)/include/$(6)" \
			$(8) \
			-G "Unix Makefiles"
	cd $(PROJECT_ROOT)/$(1) && cmake --build . --target qjs
	# 2. 编译 JNI wrapper 并链接
	mkdir -p $(PROJECT_ROOT)/$(1)/jni_build
	cd $(PROJECT_ROOT)/$(1)/jni_build && \
		cmake $(PROJECT_ROOT) \
			-DQUICKJS_SOURCE_DIR=$(QUICKJS_SRC_DIR) \
			-DJNI_WRAPPER_SOURCE_DIR=$(JNI_WRAPPER_SRC_DIR) \
			-DCMAKE_SYSTEM_NAME=$(2) \
			-DCMAKE_SYSTEM_PROCESSOR=$(3) \
			-DCMAKE_C_COMPILER=$(4) \
			-DCMAKE_CXX_COMPILER=$(5) \
			-DCMAKE_BUILD_TYPE=Release \
			-DJAVA_INCLUDE_PATH="$(JDK_HOME)/include" \
			-DJAVA_INCLUDE_PATH2="$(JDK_HOME)/include/$(6)" \
			$(8) \
			-G "Unix Makefiles"
	cd $(PROJECT_ROOT)/$(1)/jni_build && cmake --build . --target quickjs-jni-wrapper
	# 3. 复制产物到资源目录 (注意 CMakeLists.txt 输出路径)
	mkdir -p $(RESOURCE_NATIVE_DIR)/$(2)/$(3)

    @echo "$(PROJECT_ROOT)/$(1)/jni_build/native/$(2)/$(3)/quickjs-jni-wrapper$(7)"
    @if [ "$(2)" = "Windows" ]; then \
	    cp $(PROJECT_ROOT)/$(1)/jni_build/quickjs-jni-wrapper$(7) $(RESOURCE_NATIVE_DIR)/$(2)/$(3)/; \
	else \
	    cp $(PROJECT_ROOT)/$(1)/jni_build/native/$(2)/$(3)/quickjs-jni-wrapper$(7) $(RESOURCE_NATIVE_DIR)/$(2)/$(3)/; \
	fi
	@echo ">>> 完成: $(RESOURCE_NATIVE_DIR)/$(2)/$(3)/quickjs-jni-wrapper$(7)"
endef

# ============================================================================
# 平台目标
# ============================================================================

# ----- Linux x86_64 (本地或交叉编译，使用系统 gcc) ----------------------------
linux-x86_64: get-quickjs
	$(call build_platform,build_linux_x86_64,Linux,x86_64,gcc,g++,linux,.so,"")

# ----- Linux aarch64 (需要安装 gcc-aarch64-linux-gnu) -----------------------
linux-aarch64: get-quickjs
	$(call build_platform,build_linux_aarch64,Linux,aarch64,aarch64-linux-gnu-gcc,aarch64-linux-gnu-g++,linux,.so,"")

# ----- Windows x86_64 (需要安装 gcc-mingw-w64-x86-64) -----------------------
win-x86_64: get-quickjs
	$(call build_platform,build_win_x86_64,Windows,x86_64,x86_64-w64-mingw32-gcc,x86_64-w64-mingw32-g++,win32,.dll,-DWIN32=ON)

# ----- Termux aarch64 (直接在 Termux 中编译，使用 clang) --------------------
# 注意：系统名设为 Android，以便 CMake 正确识别（Termux 本质是 Android 环境）
termux-aarch64: get-quickjs
	$(call build_platform,build_termux_aarch64,Linux-Android,aarch64,clang,clang++,linux,.so,\
	-DCMAKE_C_FLAGS='-D__ANDROID__ -rtlib=compiler-rt' -DCMAKE_CXX_FLAGS='-D__ANDROID__ -stdlib=libc++ -rtlib=compiler-rt')

# ============================================================================
# 打包 JAR (将 Java 类和资源打包)
# ============================================================================
jar:
	@echo ">>> 编译 Java 源码 ..."
	@mkdir -p $(BUILD_DIR)/classes
	@javac -d $(BUILD_DIR)/classes $(shell find src/main/java -name "*.java")
	@echo ">>> 创建 JAR 文件 ..."
	@jar cvf $(PROJECT_ROOT)/quickjs-wrapper-multi-platform.jar -C $(BUILD_DIR)/classes .
	@if [ -d $(RESOURCE_NATIVE_DIR) ]; then \
		jar uvf $(PROJECT_ROOT)/quickjs-wrapper-multi-platform.jar -C src/main/resources .; \
	fi
	@echo ">>> JAR 已生成: $(PROJECT_ROOT)/quickjs-wrapper-multi-platform.jar"

# ============================================================================
# 构建所有默认平台 (可自定义)
# ============================================================================
all: linux-x86_64 linux-aarch64 win-x86_64

# ============================================================================
# 清理
# ============================================================================
clean:
	rm -rf $(BUILD_DIR)
	rm -rf $(PROJECT_ROOT)/build_*

# ============================================================================
# 帮助
# ============================================================================
help:
	@echo "可用构建目标:"
	@echo "  linux-x86_64, linux-aarch64"
	@echo "  win-x86_64"
	@echo "  termux-aarch64"
	@echo "  all, jar, clean"

.PHONY: all clean help jar get-quickjs \
	linux-x86_64 linux-aarch64 win-x86_64 termux-aarch64
