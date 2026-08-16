# For more information about using CMake with Android NDK, see https://d.android.com/studio/projects/add-native-code.html
# 虚拟显示器捕获 Native 层构建脚本
cmake_minimum_required(VERSION 3.22.1)

project(autobot_native)

# C++ 标准设置（与 build.gradle.kts 中的 cppFlags 一致）
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 源码文件清单
add_library(autobot_native SHARED
        native_capturer.cpp
)

# 系统库依赖（按需求文档要求链接）
find_library(log-lib          log)
find_library(android-lib      android)
find_library(jnigraphics-lib  jnigraphics)
find_library(mediandk-lib     mediandk)
find_library(egl-lib          EGL)
find_library(glesv2-lib       GLESv2)

# 链接到目标库
target_link_libraries(autobot_native
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
        ${mediandk-lib}
        ${egl-lib}
        ${glesv2-lib}
)

# 包含路径（NDK 自带 mediandk 头文件在 $NDK/sysroot/usr/include/android）
target_include_directories(autobot_native PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)
