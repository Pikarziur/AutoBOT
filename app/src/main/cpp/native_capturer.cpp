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
#include <condition_variable>
#include <memory>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#define LOG_TAG "AutoBOT-Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// GPU P2-4 编译开关：预览通道是否使用 RGB_565（16bpp，带宽/显存减半）
// OCR/模板匹配仍使用 ARGB_8888 frameBuffer（识别对颜色略敏感），只改预览通道。
// 如果预览画面有可见色带或精度损失，改 0 回退到 ARGB_8888。
#ifndef AUTOBOT_PREVIEW_RGB565
#define AUTOBOT_PREVIEW_RGB565 1
#endif

// GPU P1-1 编译开关：预览是否使用 EGL/GLES GPU blit
// 当 EGL 初始化失败或设备不支持时，自动回退到 CPU memcpy 路径（blitFrameToPreviewWindow）。
#ifndef AUTOBOT_EGL_PREVIEW
#define AUTOBOT_EGL_PREVIEW 1
#endif

namespace {

// ============================================================================
// GPU P1-1：EGL/GLES 2.0 渲染器（预览 GPU blit）
//
// 设计：
//   - 每个 CapturerContext 持有一个 EglRenderer（previewMutex 下使用，线程安全由调用方保证）
//   - setWindow()：创建 EGLContext + EGLSurface(window) + 纹理/着色器
//   - renderFrame()：glTexImage2D 上传 RGBA8888 frameBuffer → 四边形绘制 → eglSwapBuffers
//   - ~EglRenderer()：释放 GL 资源 + EGL
//   - 任何步骤失败都返回 false，调用方回退 CPU memcpy 路径
// ============================================================================
class EglRenderer {
public:
    EglRenderer() = default;
    ~EglRenderer() { release(); }

    // 禁止拷贝
    EglRenderer(const EglRenderer&) = delete;
    EglRenderer& operator=(const EglRenderer&) = delete;

    /**
     * 绑定 ANativeWindow 作为渲染目标。
     * 重复调用时：若 window 相同则复用 EGLSurface；若不同则销毁旧的再创建新的。
     * width/height 仅用于 EGL 最小尺寸选择（实际上由 Surface 方决定）。
     */
    bool setWindow(ANativeWindow* window, int /*width*/, int /*height*/) {
        if (window == nullptr) {
            release();
            return true;
        }

        if (window_ == window && eglSurface_ != EGL_NO_SURFACE) {
            // 窗口没变，复用
            return true;
        }

        // 释放旧的 surface（如果切换了 preview window）
        if (eglSurface_ != EGL_NO_SURFACE) {
            eglDestroySurface(eglDisplay_, eglSurface_);
            eglSurface_ = EGL_NO_SURFACE;
        }
        window_ = window;

        // 首次创建 Display + Context
        if (!initialized_) {
            if (!initOnce()) {
                release();
                return false;
            }
        }

        // 创建 EGLSurface（绑定 window）
        EGLint surfAttrs[] = {EGL_NONE};
        eglSurface_ = eglCreateWindowSurface(eglDisplay_, eglConfig_, window_, surfAttrs);
        if (eglSurface_ == EGL_NO_SURFACE) {
            LOGW("EglRenderer: eglCreateWindowSurface failed, err=0x%x", eglGetError());
            release();
            return false;
        }
        // 让当前 context 当前此 window surface
        if (!makeCurrent()) {
            release();
            return false;
        }
        // 第一次绑定时初始化 GL 资源（shader/texture）
        if (!glInitialized_ && !initGlResources()) {
            release();
            return false;
        }
        return true;
    }

    /**
     * 上传一帧 RGBA8888 frameBuffer 并渲染到绑定的 window。
     * 调用方应保证 setWindow() 已成功返回 true。
     */
    bool renderFrame(const uint8_t* rgba, int w, int h) {
        if (rgba == nullptr || w <= 0 || h <= 0) return false;
        if (!initialized_ || !glInitialized_ || eglSurface_ == EGL_NO_SURFACE) return false;

        if (!makeCurrent()) return false;

        // 视口
        glViewport(0, 0, w, h);

        // 上传纹理（仅在 w/h 变化时重新分配；否则用 glTexSubImage2D 减少 DRAM 分配）
        if (texW_ != w || texH_ != h) {
            glBindTexture(GL_TEXTURE_2D, texId_);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
            texW_ = w; texH_ = h;
        } else {
            glBindTexture(GL_TEXTURE_2D, texId_);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        }

        // 用 program 画两个三角形 (全屏四边形)
        glUseProgram(prog_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texId_);
        glUniform1i(uTex_, 0);

        // 顶点 + UV（interleaved）：屏幕为一个四边形覆盖 [-1,1]，UV [0,1]
        //   (vX, vY, u, v)  x4 顶点 (TL, BL, TR, BR)
        //   注意 UV 上下翻转：GPU 纹理 (0,0) 在左下，而 Android (0,0) 左上
        const float verts[] = {
            // x   y   u   v
            -1.0f,  1.0f, 0.0f, 1.0f,  // TL
            -1.0f, -1.0f, 0.0f, 0.0f,  // BL
             1.0f,  1.0f, 1.0f, 1.0f,  // TR
             1.0f, -1.0f, 1.0f, 0.0f,  // BR
        };

        GLuint aPos = (GLuint)aPos_;
        GLuint aUv  = (GLuint)aUv_;
        glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), verts);
        glVertexAttribPointer(aUv,  2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), verts + 2);
        glEnableVertexAttribArray(aPos);
        glEnableVertexAttribArray(aUv);

        // clear + draw
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisableVertexAttribArray(aPos);
        glDisableVertexAttribArray(aUv);

        GLenum err = glGetError();
        if (err != GL_NO_ERROR) {
            LOGW("EglRenderer: gl error after draw: 0x%x", err);
        }

        // swap（提交到 Surface）
        EGLBoolean ok = eglSwapBuffers(eglDisplay_, eglSurface_);
        if (ok != EGL_TRUE) {
            LOGW("EglRenderer: eglSwapBuffers failed, err=0x%x", eglGetError());
            return false;
        }
        return true;
    }

    /**
     * 显式释放资源（析构也会调）。
     */
    void release() {
        if (eglDisplay_ != EGL_NO_DISPLAY) {
            // 必须先 make current 才能释放 GL 资源
            if (eglContext_ != EGL_NO_CONTEXT && eglSurface_ != EGL_NO_SURFACE) {
                eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_);
            }
            if (glInitialized_) {
                if (prog_ != 0)  glDeleteProgram(prog_);
                if (texId_ != 0) glDeleteTextures(1, &texId_);
                prog_ = 0; texId_ = 0;
                glInitialized_ = false;
            }
            if (eglSurface_ != EGL_NO_SURFACE) {
                eglDestroySurface(eglDisplay_, eglSurface_);
                eglSurface_ = EGL_NO_SURFACE;
            }
            if (eglContext_ != EGL_NO_CONTEXT) {
                eglDestroyContext(eglDisplay_, eglContext_);
                eglContext_ = EGL_NO_CONTEXT;
            }
            eglTerminate(eglDisplay_);
            eglDisplay_ = EGL_NO_DISPLAY;
        }
        window_        = nullptr;
        eglConfig_     = nullptr;
        texW_ = texH_  = 0;
        uTex_ = aPos_ = aUv_ = -1;
        prog_ = 0; texId_ = 0;
        initialized_  = false;
    }

private:
    bool initOnce() {
        if (initialized_) return true;

        eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (eglDisplay_ == EGL_NO_DISPLAY) return false;
        EGLint major, minor;
        if (eglInitialize(eglDisplay_, &major, &minor) != EGL_TRUE) {
            eglDisplay_ = EGL_NO_DISPLAY;
            return false;
        }

        // 选择 RGBA8888 + GLES2 的 config
        const EGLint cfgAttrs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
            EGL_RED_SIZE,        8,
            EGL_GREEN_SIZE,      8,
            EGL_BLUE_SIZE,       8,
            EGL_ALPHA_SIZE,      8,
            EGL_NONE,
        };
        EGLint numCfgs = 0;
        if (eglChooseConfig(eglDisplay_, cfgAttrs, &eglConfig_, 1, &numCfgs) != EGL_TRUE ||
            numCfgs < 1) {
            LOGW("EglRenderer: eglChooseConfig failed, err=0x%x", eglGetError());
            return false;
        }

        // 创建 GLES 2 context
        const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, ctxAttrs);
        if (eglContext_ == EGL_NO_CONTEXT) {
            LOGW("EglRenderer: eglCreateContext failed, err=0x%x", eglGetError());
            return false;
        }
        initialized_ = true;
        LOGI("EglRenderer init success: EGL %d.%d", major, minor);
        return true;
    }

    bool makeCurrent() {
        if (eglDisplay_ == EGL_NO_DISPLAY || eglSurface_ == EGL_NO_SURFACE ||
            eglContext_ == EGL_NO_CONTEXT) {
            return false;
        }
        if (eglGetCurrentContext() == eglContext_ &&
            eglGetCurrentSurface(EGL_DRAW) == eglSurface_) {
            return true;  // 已经是当前
        }
        if (eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_) != EGL_TRUE) {
            LOGW("EglRenderer: eglMakeCurrent failed, err=0x%x", eglGetError());
            return false;
        }
        return true;
    }

    bool initGlResources() {
        // 顶点 + 片元 shader：简单采样器
        static const char* kVS =
            "attribute vec4 aPos;\n"
            "attribute vec2 aUv;\n"
            "varying vec2 vUv;\n"
            "void main() {\n"
            "  gl_Position = aPos;\n"
            "  vUv = aUv;\n"
            "}\n";
        static const char* kFS =
            "precision mediump float;\n"
            "varying vec2 vUv;\n"
            "uniform sampler2D uTex;\n"
            "void main() {\n"
            "  gl_FragColor = texture2D(uTex, vUv);\n"
            "}\n";

        auto compile = [](GLenum type, const char* src) -> GLuint {
            GLuint s = glCreateShader(type);
            if (!s) return 0;
            glShaderSource(s, 1, &src, nullptr);
            glCompileShader(s);
            GLint ok = 0;
            glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
            if (!ok) {
                char log[512] = {0};
                glGetShaderInfoLog(s, sizeof(log), nullptr, log);
                LOGW("EglRenderer: shader compile failed: %s", log);
                glDeleteShader(s);
                return 0;
            }
            return s;
        };

        GLuint vs = compile(GL_VERTEX_SHADER, kVS);
        GLuint fs = compile(GL_FRAGMENT_SHADER, kFS);
        if (!vs || !fs) { if (vs) glDeleteShader(vs); if (fs) glDeleteShader(fs); return false; }

        prog_ = glCreateProgram();
        glAttachShader(prog_, vs);
        glAttachShader(prog_, fs);
        glLinkProgram(prog_);
        GLint linked = 0;
        glGetProgramiv(prog_, GL_LINK_STATUS, &linked);
        glDeleteShader(vs); glDeleteShader(fs);
        if (!linked) {
            char log[512] = {0};
            glGetProgramInfoLog(prog_, sizeof(log), nullptr, log);
            LOGW("EglRenderer: program link failed: %s", log);
            glDeleteProgram(prog_); prog_ = 0;
            return false;
        }

        aPos_ = glGetAttribLocation(prog_, "aPos");
        aUv_  = glGetAttribLocation(prog_, "aUv");
        uTex_ = glGetUniformLocation(prog_, "uTex");

        // 纹理
        glGenTextures(1, &texId_);
        glBindTexture(GL_TEXTURE_2D, texId_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glInitialized_ = true;
        return true;
    }

    // EGL
    EGLDisplay  eglDisplay_ = EGL_NO_DISPLAY;
    EGLConfig   eglConfig_  = nullptr;
    EGLContext  eglContext_ = EGL_NO_CONTEXT;
    EGLSurface  eglSurface_ = EGL_NO_SURFACE;
    bool        initialized_  = false;
    ANativeWindow* window_    = nullptr;

    // GL
    bool        glInitialized_ = false;
    GLuint      prog_  = 0;
    GLuint      texId_ = 0;
    int         texW_  = 0;
    int         texH_  = 0;
    GLint       uTex_  = -1;
    GLint       aPos_  = -1;
    GLint       aUv_   = -1;
};

// ============================================================================
// GPU P2-4：RGBA → RGB565 行转换（用于预览 blit 时省带宽/显存）
//
// 每像素 RGBA(4B) → RGB565(2B)，1080P 单帧从 8MB 降到 4MB，ANativeWindow lock 带宽减半。
// 转换走定点整数运算（6-bit mask），无浮点开销，单帧 ~0.3ms @ 1080P。
// ============================================================================
static inline uint16_t rgbaToRgb565(uint8_t r, uint8_t g, uint8_t b) {
    return (uint16_t)(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

/**
 * RGBA8888 src (rowBytes_src = srcW * 4) → RGB565 dst (dstStride 可能 != width)。
 *
 * 只在 AUTOBOT_PREVIEW_RGB565 = 1 时使用。
 * width/height 以屏幕像素计；dstStride 由 ANativeWindow_Buffer.stride 提供（单位：像素）。
 */
static void convertRgba8888ToRgb565(const uint8_t* src, uint16_t* dst,
                                    int width, int height, int dstStridePixels) {
    if (dstStridePixels == width) {
        // 行对齐完美：紧凑排布
        for (int i = 0; i < width * height; i++) {
            const uint8_t* p = src + i * 4;
            dst[i] = rgbaToRgb565(p[0], p[1], p[2]);
        }
    } else {
        // stride != width：按行写，保留 padding 区
        for (int y = 0; y < height; y++) {
            const uint8_t* srcRow = src + y * width * 4;
            uint16_t*      dstRow = dst + y * dstStridePixels;
            for (int x = 0; x < width; x++) {
                const uint8_t* p = srcRow + x * 4;
                dstRow[x] = rgbaToRgb565(p[0], p[1], p[2]);
            }
        }
    }
}

// ============================================================================
// 预览窗口格式与 format id：由 AUTOBOT_PREVIEW_RGB565 编译开关决定
// ============================================================================
#if AUTOBOT_PREVIEW_RGB565
static constexpr int32_t kPreviewFormat = AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM;
static constexpr size_t  kPreviewBytesPerPixel = 2;
#else
static constexpr int32_t kPreviewFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
static constexpr size_t  kPreviewBytesPerPixel = 4;
#endif

// ============================================================================
// 全局捕获器上下文
// ============================================================================
struct CapturerContext {
    AImageReader*       imageReader    = nullptr;
    ANativeWindow*      readerWindow   = nullptr;   // AImageReader 的窗口
    jobject             readerSurface  = nullptr;   // 由 readerWindow 转出的 Java Surface

    // 预览 Surface（Compose 层 SurfaceView 提供）
    ANativeWindow*      previewWindow  = nullptr;
    // GPU P2-3：缓存预览窗口已经设置的几何尺寸，setBuffersGeometry 内部 binder 往返只在变化时调用
    int                 previewBufW    = 0;
    int                 previewBufH    = 0;
    // GPU P1-1：EGL/GLES 渲染器 + 运行时启用标志（初始化失败时置 false，回退 CPU blit）
#if AUTOBOT_EGL_PREVIEW
    std::unique_ptr<EglRenderer> eglRenderer;
    std::atomic<bool>            eglEnabled {false};
#endif
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

// ============================================================================
// GPU P1-1 / P2-4：统一的帧缓冲 → previewWindow blit 辅助函数
//
// 调用方必须保证：
//   - 已持有 ctx->previewMutex（ctx 可为 nullptr 表示使用纯 CPU blit）
//   - fbBuffer 指针有效
//
// 优先级：
//   1. GPU P1-1：若 ctx && ctx->eglEnabled 为 true，先尝试 EglRenderer::renderFrame
//      - ctx->eglEnabled 还为 false 时尝试 eglRenderer->setWindow()，失败则置 false
//        避免每帧都重试
//   2. 回退到 P2-4 CPU blit：
//      - RGB565 模式：RGBA8888 → RGB565 转换后写（带宽/显存减半）
//      - ARGB8888 模式：直接 memcpy
// ============================================================================
static inline void blitFrameToPreviewWindow(CapturerContext* ctx,
                                            const uint8_t* fbBuffer,
                                            int width, int height,
                                            ANativeWindow* pw) {
    if (pw == nullptr || fbBuffer == nullptr) return;

#if AUTOBOT_EGL_PREVIEW
    // GPU P1-1：EGL 快路径（需要 ctx）
    if (ctx != nullptr) {
        if (ctx->eglEnabled.load(std::memory_order_acquire)) {
            // 已启用 EGL：直接渲染（内部 makeCurrent + TexSubImage + Swap）
            if (ctx->eglRenderer &&
                ctx->eglRenderer->renderFrame(fbBuffer, width, height)) {
                return;  // GPU 渲染成功
            }
            // EGL 运行时失败：disable，后续帧走 CPU blit（避免每帧反复尝试）
            ctx->eglEnabled.store(false, std::memory_order_release);
            ctx->eglRenderer.reset();
            LOGW("blitFrameToPreviewWindow: EGL render failed at runtime, fall back to CPU blit");
        } else {
            // 还未启用 EGL：尝试初始化（只在 previewWindow set 后做一次）
            if (!ctx->eglRenderer) {
                ctx->eglRenderer = std::make_unique<EglRenderer>();
            }
            if (ctx->eglRenderer->setWindow(pw, width, height)) {
                ctx->eglEnabled.store(true, std::memory_order_release);
                // 首次：先渲一帧
                if (ctx->eglRenderer->renderFrame(fbBuffer, width, height)) {
                    return;  // GPU 渲染成功
                }
                // 初始化成功但首帧渲染失败：fallback CPU
                ctx->eglEnabled.store(false, std::memory_order_release);
                ctx->eglRenderer.reset();
                LOGW("blitFrameToPreviewWindow: EGL init ok but first render fall back");
            } else {
                // 初始化失败：禁用 EGL，后续帧直接走 CPU
                ctx->eglEnabled.store(false, std::memory_order_release);
                ctx->eglRenderer.reset();
                LOGW("blitFrameToPreviewWindow: EGL init disabled, use CPU blit");
            }
        }
    }
#else
    (void)ctx;  // EGL 开关关闭时静默未使用参数
#endif

    // ======= 回退路径（或 EGL 关闭）：CPU blit =======
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(pw, &buf, nullptr) != 0) {
        LOGW("blitFrameToPreviewWindow: ANativeWindow_lock failed");
        return;
    }
    if (buf.bits == nullptr) {
        ANativeWindow_unlockAndPost(pw);
        return;
    }

#if AUTOBOT_PREVIEW_RGB565
    // GPU P2-4：RGBA8888 → RGB565 转换后写预览
    convertRgba8888ToRgb565(fbBuffer,
                            reinterpret_cast<uint16_t*>(buf.bits),
                            width, height, buf.stride);
#else
    // ARGB_8888 路径：直接 memcpy + padding 行对齐
    uint8_t*       dst      = reinterpret_cast<uint8_t*>(buf.bits);
    const int      rowBytes = width * 4;
    if (buf.stride == width) {
        memcpy(dst, fbBuffer, (size_t)rowBytes * height);
    } else {
        for (int y = 0; y < height; y++) {
            memcpy(dst + y * buf.stride * 4,
                   fbBuffer + y * rowBytes,
                   (size_t)rowBytes);
        }
    }
#endif

    ANativeWindow_unlockAndPost(pw);
}

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
                // GPU P2-3：尺寸不变时跳过 setBuffersGeometry（内部 binder 往返昂贵）
                ANativeWindow* pw = g_ctx->previewWindow;
                if (g_ctx->previewBufW != g_ctx->width ||
                    g_ctx->previewBufH != g_ctx->height) {
                    ANativeWindow_setBuffersGeometry(pw, g_ctx->width, g_ctx->height,
                                                     kPreviewFormat);
                    g_ctx->previewBufW = g_ctx->width;
                    g_ctx->previewBufH = g_ctx->height;
                }
                // 快照 fbBuffer 指针+宽高（在 fbMutex 内），blit 时不嵌套持锁
                int w, h;
                const uint8_t* src;
                {
                    std::lock_guard<std::mutex> lk2(g_ctx->fbMutex);
                    w   = g_ctx->width;
                    h   = g_ctx->height;
                    src = g_ctx->frameBuffer.data();
                }
                blitFrameToPreviewWindow(g_ctx, src, w, h, pw);
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
    //    GPU P2-2：CPU_READ_OFTEN 与 GPU_SAMPLED_IMAGE 互斥（前者强制 linear layout，
    //    后者走 tiling，两个 flag 同设会互相抵消，两个优化都不生效）。
    //    本项目 AImageReader 的内容最终必须 CPU 读取（拷贝到 frameBuffer 供识图引擎使用），
    //    预览实际上走 ANativeWindow_lock 的 CPU memcpy 路径（不走 GPU 采样），
    //    所以只保留 CPU_READ_OFTEN。后续 GPU P1-1（EGL 预览）若启用，可独立创建 EGLImage 上传，
    //    不需要 AImageReader buffer 自身带 GPU_SAMPLED。
    uint64_t usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
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

    // 1. 先释放 EGL 渲染器 + 预览窗口引用
    {
        std::lock_guard<std::mutex> lk2(g_ctx->previewMutex);
#if AUTOBOT_EGL_PREVIEW
        g_ctx->eglEnabled.store(false, std::memory_order_release);
        g_ctx->eglRenderer.reset();  // 析构会 release EGLDisplay/Context/Surface
#endif
        if (g_ctx->previewWindow != nullptr) {
            ANativeWindow_release(g_ctx->previewWindow);
            g_ctx->previewWindow = nullptr;
        }
        g_ctx->previewBufW = 0;
        g_ctx->previewBufH = 0;
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

    // GPU P1-1：切窗/关窗时先释放 EGL（EGLSurface 绑的是旧 window 引用，不释放会泄漏或在新窗上黑屏）
#if AUTOBOT_EGL_PREVIEW
    g_ctx->eglEnabled.store(false, std::memory_order_release);
    g_ctx->eglRenderer.reset();
#endif

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
            // GPU P2-3 / P2-4：新挂 previewSurface 时设置初始几何 + 记录缓存
            //   RGB565 模式下使用 kPreviewFormat (R5G6B5)，带宽/显存减半
            ANativeWindow_setBuffersGeometry(g_ctx->previewWindow,
                                             g_ctx->width, g_ctx->height,
                                             kPreviewFormat);
            g_ctx->previewBufW = g_ctx->width;
            g_ctx->previewBufH = g_ctx->height;
            LOGI("Preview surface attached");
        } else {
            LOGE("ANativeWindow_fromSurface returned null");
        }
    } else {
        // 脱离预览 Surface 时清缓存（下次 attach 再重设）
        g_ctx->previewBufW = 0;
        g_ctx->previewBufH = 0;
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
/**
 * 内部释放 g_ctx（与 native_releaseCapturer 逻辑一致，但无锁 g_ctxMutex，
 * 因为调用方已持有 g_ctxMutex）。
 */
static void releaseCtxInline(JNIEnv* env, CapturerContext* ctx) {
    ctx->released = true;

    {
        std::lock_guard<std::mutex> lk(ctx->previewMutex);
        // GPU P1-1：先释放 EGL（它需要引用 previewWindow 里的 EGLSurface，release 时会 detach）
#if AUTOBOT_EGL_PREVIEW
        ctx->eglEnabled.store(false, std::memory_order_release);
        ctx->eglRenderer.reset();  // 析构会 release EGLDisplay/Context/Surface
#endif
        if (ctx->previewWindow != nullptr) {
            ANativeWindow_release(ctx->previewWindow);
            ctx->previewWindow = nullptr;
        }
        ctx->previewBufW = 0;
        ctx->previewBufH = 0;
    }

    if (ctx->readerSurface != nullptr) {
        env->DeleteGlobalRef(ctx->readerSurface);
        ctx->readerSurface = nullptr;
    }

    {
        std::lock_guard<std::mutex> lk(ctx->fbMutex);
        ctx->frameBuffer.clear();
        ctx->frameBuffer.shrink_to_fit();
        ctx->fbWidth = 0;
        ctx->fbHeight = 0;
    }

    if (ctx->imageReader != nullptr) {
        AImageReader_delete(ctx->imageReader);
        ctx->imageReader = nullptr;
        ctx->readerWindow = nullptr;
    }

    if (ctx->javaRef != nullptr) {
        env->DeleteGlobalRef(ctx->javaRef);
        ctx->javaRef = nullptr;
    }

    LOGI("Old capturer released (inline)");
}

static jboolean JNICALL native_prepareFrameBuffer(JNIEnv* env, jobject thiz,
                                                  jint width, jint height) {
    LOGI("prepareFrameBuffer: %dx%d", width, height);

    std::lock_guard<std::mutex> lk(g_ctxMutex);
    if (g_ctx != nullptr) {
        LOGW("Capturer already exists, releasing old one first");
        releaseCtxInline(env, g_ctx);
        delete g_ctx;
        g_ctx = nullptr;
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
            // GPU P2-3：尺寸不变时跳过 setBuffersGeometry；P2-4：RGB565 格式
            if (g_ctx->previewBufW != w || g_ctx->previewBufH != h) {
                ANativeWindow_setBuffersGeometry(pw, w, h, kPreviewFormat);
                g_ctx->previewBufW = w;
                g_ctx->previewBufH = h;
            }
            // 快照 fbBuffer（fbMutex 内读），blit 期间不嵌套持 fbMutex + previewMutex
            const uint8_t* src;
            {
                std::lock_guard<std::mutex> lk2(g_ctx->fbMutex);
                src = g_ctx->frameBuffer.data();
            }
            blitFrameToPreviewWindow(g_ctx, src, w, h, pw);
        }
    }

    // ---- 3. 自增帧计数 ----
    g_ctx->frameCount.fetch_add(1);
}

// ============================================================================
// CPU 优化路径：跳过 JPEG 解码 + Bitmap 创建，直接把 server 端 RGBA 字节写入 frameBuffer
// ============================================================================
/**
 * JNI 签名对应 Kotlin: fun injectExternalFrameRaw(bytes: ByteArray, width: Int, height: Int)
 *
 * 与 native_injectExternalFrame 等价，但少了一次 BitmapFactory.decodeByteArray（JPEG 解码）
 * 和 AndroidBitmap_lockPixels（Bitmap 像素锁定），单帧 CPU 节省 ~3-5ms。
 * 用 GetByteArrayRegion 直接拷贝到目标 buffer，避免 GetByteArrayElements 的额外 pin/unpin 开销。
 */
static void JNICALL native_injectExternalFrameRaw(JNIEnv* env, jobject /*thiz*/,
                                                  jbyteArray bytes, jint width, jint height) {
    if (g_ctx == nullptr || g_ctx->released.load() || bytes == nullptr) return;

    jsize len = env->GetArrayLength(bytes);
    size_t need = (size_t)width * height * 4;
    if ((size_t)len < need) {
        LOGW("injectExternalFrameRaw: bytes too short, len=%d, need=%zu", len, need);
        return;
    }
    if (width <= 0 || height <= 0) {
        LOGE("injectExternalFrameRaw: invalid dims %dx%d", width, height);
        return;
    }

    // ---- 1. 写入帧缓冲 ----
    {
        std::lock_guard<std::mutex> lk(g_ctx->fbMutex);
        if (g_ctx->frameBuffer.size() < need) {
            try {
                g_ctx->frameBuffer.resize(need, 0);
            } catch (const std::bad_alloc& e) {
                LOGE("injectExternalFrameRaw resize failed: %s", e.what());
                return;
            }
        }
        g_ctx->fbWidth  = width;
        g_ctx->fbHeight = height;
        // 同步 ctx 宽高（预览 blit 用 g_ctx->width/height）
        g_ctx->width  = width;
        g_ctx->height = height;

        // GetByteArrayRegion：直接拷贝到目标 buffer，无额外分配
        // 比 GetByteArrayElements 更高效（后者需要 pin 内存或返回拷贝）
        env->GetByteArrayRegion(bytes, 0, (jsize)need,
                                 reinterpret_cast<jbyte*>(g_ctx->frameBuffer.data()));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("injectExternalFrameRaw: GetByteArrayRegion threw");
            return;
        }
    }

    // ---- 2. 分发预览（若 previewWindow 已设置）----
    {
        std::lock_guard<std::mutex> lk(g_ctx->previewMutex);
        if (g_ctx->previewWindow != nullptr) {
            ANativeWindow* pw = g_ctx->previewWindow;
            // GPU P2-3：尺寸不变时跳过 setBuffersGeometry；P2-4：RGB565 格式
            if (g_ctx->previewBufW != width || g_ctx->previewBufH != height) {
                ANativeWindow_setBuffersGeometry(pw, width, height, kPreviewFormat);
                g_ctx->previewBufW = width;
                g_ctx->previewBufH = height;
            }
            // 快照 fbBuffer（fbMutex 内读），blit 期间不嵌套持 fbMutex + previewMutex
            const uint8_t* src;
            {
                std::lock_guard<std::mutex> lk2(g_ctx->fbMutex);
                src = g_ctx->frameBuffer.data();
            }
            blitFrameToPreviewWindow(g_ctx, src, width, height, pw);
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
        {"injectExternalFrameRaw", "([BII)V",
         (void*)native_injectExternalFrameRaw},
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
