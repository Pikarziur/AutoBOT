/*
 * native_capturer.cpp
 *
 * 虚拟显示器帧捕获 Native 实现
 *
 * 实现要点：
 * 1. 使用 AImageReader_newWithUsage 创建指定分辨率 + RGBA_8888 格式的图像读取器
 *    usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
 *    maxImages = 5
 * 2. 注册 onImageAvailable 回调：
 *    - 从 AImage 中取出 AHardwareBuffer 写入帧缓冲供识图引擎使用
 *    - 若预览开启则通过 DispatchPreview 分发到预览 Surface
 * 3. AImageReader_getWindow 获取 ANativeWindow，再用 ANativeWindow_toSurface 转换为 Java Surface
 *    该 Surface 即虚拟显示器的画面输出目标
 * 4. JNI_OnLoad 中通过 RegisterNatives 注册：
 *    setupNativeCapturer / releaseNativeCapturer / setPreviewSurface
 *    getFrameBufferBitmap / getFrameCount
 * 5. 资源释放：surfaceDestroyed / release 时严格调用
 *    AImage_delete / AImageReader_delete / ReleaseFrameBuffers 避免内存泄漏
 */

#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/bitmap.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <unistd.h>
#include <pthread.h>
#include <cstring>
#include <vector>
#include <atomic>
#include <mutex>

#define LOG_TAG "AutoBOT-Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ============================================================================
// 全局捕获器上下文
// ============================================================================
struct CapturerContext {
    AImageReader*       imageReader    = nullptr;
    ANativeWindow*      readerWindow   = nullptr;   // AImageReader 的窗口
    jobject             readerSurface  = nullptr;   // 由 readerWindow 转出的 Java Surface

    // 预览 Surface（Compose 层 SurfaceView 提供）
    ANativeWindow*      previewWindow  = nullptr;
    std::mutex          previewMutex;

    // 帧缓冲：保存最近一帧 RGBA 数据供识图引擎 / getFrameBufferBitmap 使用
    std::vector<uint8_t> frameBuffer;
    int                 fbWidth       = 0;
    int                 fbHeight      = 0;
    std::mutex          fbMutex;

    // 配置
    int                 width         = 0;
    int                 height        = 0;

    // 统计
    std::atomic<int64_t> frameCount   {0};
    std::atomic<bool>    released     {false};

    // JVM 与回调引用
    JavaVM*             jvm           = nullptr;
    jobject             javaRef       = nullptr;  // GlobalRef 弱引用 NativeCapturer.kt 实例
};

// 进程内全局唯一捕获器（满足 RegisterNatives 的简单实现；多实例需求可改为 map）
CapturerContext* g_ctx = nullptr;
std::mutex g_ctxMutex;

// ============================================================================
// 将 AImage 一帧数据 copy 到帧缓冲 + (可选) 分发预览
// ============================================================================
void onImageAvailable(void* /*context*/, AImageReader* reader) {
    if (g_ctx == nullptr || g_ctx->released.load()) return;

    AImage* image = nullptr;
    media_status_t st = AImageReader_acquireLatestImage(reader, &image);
    if (st != AMEDIA_OK || image == nullptr) {
        // 队列空或瞬时不可用，跳过
        return;
    }

    // 取出 AHardwareBuffer 句柄
    AHardwareBuffer* hwBuf = nullptr;
    st = AImage_getHardwareBuffer(image, &hwBuf);
    if (st == AMEDIA_OK && hwBuf != nullptr) {
        // ---- 写入帧缓冲（供识图引擎 / getFrameBufferBitmap 使用）----
        // 取出 plane 数据：RGBA_8888 通常只有 1 个 plane
        int32_t numPlanes = 0;
        AImage_getNumberOfPlanes(image, &numPlanes);

        if (numPlanes > 0) {
            uint8_t* data = nullptr;
            int      dataLength = 0;
            int      rowStride  = 0;
            AImage_getPlaneData(image, 0, &data, &dataLength);
            AImage_getPlaneRowStride(image, 0, &rowStride);

            int w = g_ctx->width;
            int h = g_ctx->height;

            std::lock_guard<std::mutex> lk(g_ctx->fbMutex);
            if ((int)g_ctx->frameBuffer.size() < (size_t)w * h * 4) {
                g_ctx->frameBuffer.resize((size_t)w * h * 4);
            }
            // 拷贝数据（考虑 rowStride 可能 > width*4）
            uint8_t* dst = g_ctx->frameBuffer.data();
            if (rowStride == w * 4) {
                memcpy(dst, data, (size_t)w * h * 4);
            } else {
                for (int y = 0; y < h; y++) {
                    memcpy(dst + y * w * 4, data + y * rowStride, (size_t)w * 4);
                }
            }
            g_ctx->fbWidth  = w;
            g_ctx->fbHeight = h;
        }

        // ---- 分发预览（若开启）----
        {
            std::lock_guard<std::mutex> lk(g_ctx->previewMutex);
            if (g_ctx->previewWindow != nullptr) {
                // 预览 Surface 复制：直接把帧缓冲内容 blit 到预览 ANativeWindow
                ANativeWindow* pw = g_ctx->previewWindow;
                ANativeWindow_setBuffersGeometry(pw, g_ctx->width, g_ctx->height,
                                                 AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);

                ANativeWindow_Buffer buf;
                if (ANativeWindow_lock(pw, &buf, nullptr) == 0) {
                    std::lock_guard<std::mutex> lk2(g_ctx->fbMutex);
                    uint8_t* src = g_ctx->frameBuffer.data();
                    if (src != nullptr && buf.bits != nullptr) {
                        uint8_t* dst = (uint8_t*)buf.bits;
                        int rowBytes = g_ctx->width * 4;
                        if (buf.stride == g_ctx->width) {
                            memcpy(dst, src, (size_t)rowBytes * g_ctx->height);
                        } else {
                            for (int y = 0; y < g_ctx->height; y++) {
                                memcpy(dst + y * buf.stride * 4,
                                       src + y * rowBytes,
                                       (size_t)rowBytes);
                            }
                        }
                    }
                    ANativeWindow_unlockAndPost(pw);
                }
            }
        }
    }

    g_ctx->frameCount.fetch_add(1);

    // ---- 释放当前 AImage（关键：避免缓冲队列耗尽）----
    AImage_delete(image);
}

// ============================================================================
// 工具：将 ANativeWindow 转换为 Java Surface 对象
// ============================================================================
jobject nativeWindowToSurface(JNIEnv* env, ANativeWindow* window) {
    if (window == nullptr) return nullptr;

    // Android Surface(SurfaceControl) 构造在 NDK 中没有公开 API
    // 通用做法：调用 ANativeWindow_toSurface（API 26+ 提供）
    jobject surface = ANativeWindow_toSurface(env, window);
    return surface;  // 已是 LocalRef，由调用方 DeleteLocalRef
}

} // namespace

// ============================================================================
// Native 方法实现
// ============================================================================

// JNI 签名对应 Kotlin: fun setupNativeCapturer(width: Int, height: Int): Surface?
static jobject JNICALL native_setupCapturer(JNIEnv* env, jobject thiz, jint width, jint height) {
    LOGI("setupNativeCapturer: %dx%d", width, height);

    std::lock_guard<std::mutex> lk(g_ctxMutex);
    if (g_ctx != nullptr) {
        LOGW("Capturer already exists, release first");
        return nullptr;
    }

    if (width <= 0 || height <= 0) {
        LOGE("Invalid dimensions");
        return nullptr;
    }

    g_ctx = new CapturerContext();
    g_ctx->width  = width;
    g_ctx->height = height;
    g_ctx->released = false;
    env->GetJavaVM(&g_ctx->jvm);
    g_ctx->javaRef = env->NewGlobalRef(thiz);

    // 1. 创建 AImageReader
    //    usage = CPU_READ_OFTEN (供识图引擎 CPU 读取) | GPU_SAMPLED_IMAGE (可供预览 GPU 采样)
    //    API 26 提供的 AImageReader_newWithUsage 为 6 参数版本
    uint64_t usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                     AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    media_status_t st = AImageReader_newWithUsage(
            width, height,
            AIMAGE_FORMAT_RGBA_8888,
            usage,
            /*maxImages*/ 5,
            &g_ctx->imageReader);

    if (st != AMEDIA_OK || g_ctx->imageReader == nullptr) {
        LOGE("AImageReader_newWithUsage failed: %d", st);
        delete g_ctx; g_ctx = nullptr;
        return nullptr;
    }

    // 2. 注册 onImageAvailable 回调
    AImageReader_ImageListener listener{};
    listener.context = g_ctx;
    listener.onImageAvailable = ::onImageAvailable;
    st = AImageReader_setImageListener(g_ctx->imageReader, &listener);
    if (st != AMEDIA_OK) {
        LOGW("setImageListener failed: %d (preview will not update)", st);
    }

    // 3. 取出 Window 并转 Surface
    st = AImageReader_getWindow(g_ctx->imageReader, &g_ctx->readerWindow);
    if (st != AMEDIA_OK || g_ctx->readerWindow == nullptr) {
        LOGE("AImageReader_getWindow failed: %d", st);
        AImageReader_delete(g_ctx->imageReader);
        g_ctx->imageReader = nullptr;
        delete g_ctx; g_ctx = nullptr;
        return nullptr;
    }
    // readerWindow 生命周期由 AImageReader 管理，不需要再 acquire

    jobject surface = nativeWindowToSurface(env, g_ctx->readerWindow);
    if (surface == nullptr) {
        LOGE("ANativeWindow_toSurface returned null");
        AImageReader_delete(g_ctx->imageReader);
        g_ctx->imageReader = nullptr;
        g_ctx->readerWindow = nullptr;
        delete g_ctx; g_ctx = nullptr;
        return nullptr;
    }

    // 保存为 GlobalRef
    g_ctx->readerSurface = env->NewGlobalRef(surface);

    LOGI("setupNativeCapturer success, surface=%p", surface);
    return surface;  // 返回给 Java 层
}

// JNI 签名对应 Kotlin: fun releaseNativeCapturer()
static void JNICALL native_releaseCapturer(JNIEnv* env, jobject /*thiz*/) {
    LOGI("releaseNativeCapturer");

    std::lock_guard<std::mutex> lk(g_ctxMutex);
    if (g_ctx == nullptr) return;

    g_ctx->released = true;

    // 1. 先释放预览窗口引用
    {
        std::lock_guard<std::mutex> lk2(g_ctx->previewMutex);
        if (g_ctx->previewWindow != nullptr) {
            ANativeWindow_release(g_ctx->previewWindow);
            g_ctx->previewWindow = nullptr;
        }
    }

    // 2. 释放 reader surface 全局引用
    if (g_ctx->readerSurface != nullptr) {
        env->DeleteGlobalRef(g_ctx->readerSurface);
        g_ctx->readerSurface = nullptr;
    }

    // 3. readerWindow 由 AImageReader 持有，不需要单独 release

    // 4. 释放帧缓冲
    {
        std::lock_guard<std::mutex> lk2(g_ctx->fbMutex);
        g_ctx->frameBuffer.clear();
        g_ctx->frameBuffer.shrink_to_fit();
        g_ctx->fbWidth = 0;
        g_ctx->fbHeight = 0;
    }

    // 5. 删除 AImageReader（内部会清理 readerWindow）
    if (g_ctx->imageReader != nullptr) {
        AImageReader_delete(g_ctx->imageReader);
        g_ctx->imageReader = nullptr;
        g_ctx->readerWindow = nullptr;
    }

    // 6. 释放 Java 引用
    if (g_ctx->javaRef != nullptr) {
        env->DeleteGlobalRef(g_ctx->javaRef);
        g_ctx->javaRef = nullptr;
    }

    LOGI("Capturer released. Total frames captured: %lld",
         (long long)g_ctx->frameCount.load());

    delete g_ctx;
    g_ctx = nullptr;
}

// JNI 签名对应 Kotlin: fun setPreviewSurface(surface: Surface?)
static void JNICALL native_setPreviewSurface(JNIEnv* env, jobject /*thiz*/, jobject surface) {
    LOGI("setPreviewSurface: %p", surface);

    if (g_ctx == nullptr) {
        LOGW("setPreviewSurface: capturer not initialized");
        return;
    }

    std::lock_guard<std::mutex> lk(g_ctx->previewMutex);

    // 释放旧的预览窗口
    if (g_ctx->previewWindow != nullptr) {
        ANativeWindow_release(g_ctx->previewWindow);
        g_ctx->previewWindow = nullptr;
    }

    if (surface != nullptr) {
        // 从 Java Surface 取 ANativeWindow
        ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
        if (win != nullptr) {
            g_ctx->previewWindow = win;
            // 设置预览窗口缓冲区几何
            ANativeWindow_setBuffersGeometry(g_ctx->previewWindow,
                                             g_ctx->width, g_ctx->height,
                                             AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
            LOGI("Preview surface attached");
        } else {
            LOGE("ANativeWindow_fromSurface returned null");
        }
    } else {
        LOGI("Preview surface detached");
    }
}

// JNI 签名对应 Kotlin: fun getFrameBufferBitmap(): Bitmap?
static jobject JNICALL native_getFrameBufferBitmap(JNIEnv* env, jobject /*thiz*/) {
    if (g_ctx == nullptr) return nullptr;

    std::lock_guard<std::mutex> lk(g_ctx->fbMutex);
    if (g_ctx->fbWidth <= 0 || g_ctx->fbHeight <= 0 ||
        g_ctx->frameBuffer.empty()) {
        return nullptr;
    }

    int w = g_ctx->fbWidth;
    int h = g_ctx->fbHeight;

    // 创建 Android Bitmap（ARGB_8888 与 RGBA_8888 内存布局一致）
    jclass    bitmapCls = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmapMid = env->GetStaticMethodID(
            bitmapCls, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");

    jclass    configCls = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID  argb8888Fid = env->GetStaticFieldID(configCls, "ARGB_8888",
                                                  "Landroid/graphics/Bitmap$Config;");
    jobject   configObj = env->GetStaticObjectField(configCls, argb8888Fid);

    jobject bitmap = env->CallStaticObjectMethod(bitmapCls, createBitmapMid,
                                                 w, h, configObj);
    if (bitmap == nullptr) {
        LOGE("createBitmap failed");
        return nullptr;
    }

    // 锁定像素缓冲并写入
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    void* pixels = nullptr;
    int ret = AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (ret < 0) {
        LOGE("AndroidBitmap_lockPixels failed: %d", ret);
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    // RGBA -> ARGB：Android Bitmap ARGB_8888 在内存中实际是 RGBA 字节序
    // 直接 memcpy 即可（小端序下 ARGB_8888 与 RGBA_8888 字节布局相同）
    uint32_t stride = info.stride;
    uint8_t* src = g_ctx->frameBuffer.data();
    uint8_t* dst = (uint8_t*)pixels;
    if (stride == (uint32_t)(w * 4)) {
        memcpy(dst, src, (size_t)w * h * 4);
    } else {
        for (int y = 0; y < h; y++) {
            memcpy(dst + y * stride, src + y * w * 4, (size_t)w * 4);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    env->DeleteLocalRef(configObj);
    env->DeleteLocalRef(configCls);
    env->DeleteLocalRef(bitmapCls);
    return bitmap;
}

// JNI 签名对应 Kotlin: fun getFrameCount(): Long
static jlong JNICALL native_getFrameCount(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_ctx == nullptr) return 0;
    return (jlong)g_ctx->frameCount.load();
}

// ============================================================================
// 新架构（Server 进程传 JPEG 帧到 App 注入）所需方法
// ============================================================================

/**
 * 仅分配 frameBuffer，不创建 AImageReader（Server 端负责画面采集）。
 * JNI 签名对应 Kotlin: fun prepareFrameBuffer(width: Int, height: Int): Boolean
 */
static jboolean JNICALL native_prepareFrameBuffer(JNIEnv* env, jobject thiz,
                                                  jint width, jint height) {
    LOGI("prepareFrameBuffer: %dx%d", width, height);

    std::lock_guard<std::mutex> lk(g_ctxMutex);
    if (g_ctx != nullptr) {
        LOGW("Capturer already exists, releasing old one first");
        // 此处不调用 release（需要 JNIEnv 清理 GlobalRef），返回失败让调用方 stop 再 start
        return JNI_FALSE;
    }

    if (width <= 0 || height <= 0) {
        LOGE("Invalid dimensions: %dx%d", width, height);
        return JNI_FALSE;
    }

    g_ctx = new CapturerContext();
    g_ctx->width    = width;
    g_ctx->height   = height;
    g_ctx->released = false;
    g_ctx->fbWidth  = width;
    g_ctx->fbHeight = height;
    env->GetJavaVM(&g_ctx->jvm);
    g_ctx->javaRef  = env->NewGlobalRef(thiz);

    // 预分配 frameBuffer（RGBA_8888 = w*h*4 bytes）
    try {
        std::lock_guard<std::mutex> lk2(g_ctx->fbMutex);
        g_ctx->frameBuffer.resize((size_t)width * height * 4, 0);
    } catch (const std::bad_alloc& e) {
        LOGE("frameBuffer allocation failed: %s", e.what());
        env->DeleteGlobalRef(g_ctx->javaRef);
        delete g_ctx; g_ctx = nullptr;
        return JNI_FALSE;
    }

    LOGI("prepareFrameBuffer success: frameBuffer=%zu bytes", g_ctx->frameBuffer.size());
    return JNI_TRUE;
}

/**
 * 从 Bitmap（JPEG 解码后的 ARGB_8888）写入 frameBuffer + 可选分发预览 + 自增 frameCount。
 * JNI 签名对应 Kotlin: fun injectExternalFrame(bitmap: Bitmap)
 */
static void JNICALL native_injectExternalFrame(JNIEnv* env, jobject /*thiz*/, jobject bitmap) {
    if (g_ctx == nullptr || g_ctx->released.load() || bitmap == nullptr) return;

    AndroidBitmapInfo info;
    int ret = AndroidBitmap_getInfo(env, bitmap, &info);
    if (ret < 0) {
        LOGE("AndroidBitmap_getInfo failed: %d", ret);
        return;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        // 仅支持 ARGB_8888（JPEG 经 BitmapFactory.decodeByteArray 默认产出）
        LOGW("injectExternalFrame: unsupported format=%d, expect RGBA_8888(%d)",
             info.format, ANDROID_BITMAP_FORMAT_RGBA_8888);
        return;
    }

    void* pixels = nullptr;
    ret = AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (ret < 0 || pixels == nullptr) {
        LOGE("AndroidBitmap_lockPixels failed: %d", ret);
        return;
    }

    const int w = (int)info.width;
    const int h = (int)info.height;

    // ---- 1. 写入帧缓冲 ----
    {
        std::lock_guard<std::mutex> lk(g_ctx->fbMutex);
        size_t need = (size_t)w * h * 4;
        if (g_ctx->frameBuffer.size() < need) {
            try {
                g_ctx->frameBuffer.resize(need, 0);
            } catch (const std::bad_alloc& e) {
                LOGE("injectExternalFrame resize failed: %s", e.what());
                AndroidBitmap_unlockPixels(env, bitmap);
                return;
            }
        }
        g_ctx->fbWidth  = w;
        g_ctx->fbHeight = h;
        // 同步 ctx 宽高（预览 blit 用 g_ctx->width/height）
        g_ctx->width  = w;
        g_ctx->height = h;

        uint8_t*       src = (uint8_t*)pixels;
        uint8_t*       dst = g_ctx->frameBuffer.data();
        const uint32_t srcStride = info.stride;

        if (srcStride == (uint32_t)(w * 4)) {
            // 行对齐完美，直接整块拷贝
            // 注意：ARGB_8888 Bitmap 的内存字节序与 frameBuffer 的 RGBA 约定一致
            // （小端 ARM 下 0xAARRGGBB 在内存中按 byte[0]=R byte[1]=G byte[2]=B byte[3]=A 排布，
            //  与 AImageReader RGBA plane 完全相同 → getFrameBufferBitmap 里也直接 memcpy）
            memcpy(dst, src, need);
        } else {
            // stride 不一致：按行拷贝去掉 padding
            const int rowBytes = w * 4;
            for (int y = 0; y < h; y++) {
                memcpy(dst + y * rowBytes,
                       src + y * srcStride,
                       (size_t)rowBytes);
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    // ---- 2. 分发预览（若 previewWindow 已设置）----
    {
        std::lock_guard<std::mutex> lk(g_ctx->previewMutex);
        if (g_ctx->previewWindow != nullptr) {
            ANativeWindow* pw = g_ctx->previewWindow;
            ANativeWindow_setBuffersGeometry(pw, w, h,
                                             AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
            ANativeWindow_Buffer buf;
            if (ANativeWindow_lock(pw, &buf, nullptr) == 0) {
                std::lock_guard<std::mutex> lk2(g_ctx->fbMutex);
                uint8_t* src = g_ctx->frameBuffer.data();
                if (src != nullptr && buf.bits != nullptr) {
                    uint8_t* dst = (uint8_t*)buf.bits;
                    const int rowBytes = w * 4;
                    if (buf.stride == w) {
                        memcpy(dst, src, (size_t)rowBytes * h);
                    } else {
                        for (int y = 0; y < h; y++) {
                            memcpy(dst + y * buf.stride * 4,
                                   src + y * rowBytes,
                                   (size_t)rowBytes);
                        }
                    }
                }
                ANativeWindow_unlockAndPost(pw);
            }
        }
    }

    // ---- 3. 自增帧计数 ----
    g_ctx->frameCount.fetch_add(1);
}

// ============================================================================
// RegisterNatives 表
// ============================================================================
static const JNINativeMethod kNativeMethods[] = {
        {"setupNativeCapturer",   "(II)Landroid/view/Surface;",
         (void*)native_setupCapturer},
        {"prepareFrameBuffer",    "(II)Z",
         (void*)native_prepareFrameBuffer},
        {"injectExternalFrame",   "(Landroid/graphics/Bitmap;)V",
         (void*)native_injectExternalFrame},
        {"releaseNativeCapturer", "()V",
         (void*)native_releaseCapturer},
        {"setPreviewSurface",     "(Landroid/view/Surface;)V",
         (void*)native_setPreviewSurface},
        {"getFrameBufferBitmap",  "()Landroid/graphics/Bitmap;",
         (void*)native_getFrameBufferBitmap},
        {"getFrameCount",         "()J",
         (void*)native_getFrameCount},
};

// ============================================================================
// JNI_OnLoad：注册 Native 方法
// ============================================================================
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // 找到 NativeCapturer Kotlin 类
    const char* kClassName = "com/autobot/app/nativelib/NativeCapturer";
    jclass clazz = env->FindClass(kClassName);
    if (clazz == nullptr) {
        LOGE("FindClass(%s) failed", kClassName);
        return JNI_ERR;
    }

    jint rc = env->RegisterNatives(clazz, kNativeMethods,
                                   sizeof(kNativeMethods) / sizeof(JNINativeMethod));
    if (rc != JNI_OK) {
        LOGE("RegisterNatives failed: %d", rc);
        return JNI_ERR;
    }

    env->DeleteLocalRef(clazz);
    LOGI("Native methods registered successfully");
    return JNI_VERSION_1_6;
}
