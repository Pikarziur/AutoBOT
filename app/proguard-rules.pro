# 添加项目专用的 ProGuard 规则。
# 你可以在这里添加混淆规则，通常为保持类不被混淆添加规则。
# 对于 Shizuku，需要保持一些类不被混淆
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# NativeCapturer：JNI RegisterNatives 通过类名+方法名查找，必须保持不被混淆
-keep class com.autobot.app.nativelib.NativeCapturer { *; }

# CompositionService / ViewModel 中被 Native 反射或序列化的类保持
-keep class com.autobot.app.service.CompositionService { *; }

# ==================== server 进程架构（Shizuku.newProcess）====================
# 这些类通过 -Djava.class.path=/data/local/tmp/autobot-server.apk 被 app_process
# 在独立 shell uid 进程中加载，类名/方法名必须保持原样，否则反射查找会失败。

# ServerMain: app_process 入口（必须保留 public static void main(String[])）
-keep class com.autobot.app.server.ServerMain {
    public static void main(java.lang.String[]);
}
-keep class com.autobot.app.server.VDProtocol { *; }
-keep class com.autobot.app.server.VDRequest { *; }
-keep class com.autobot.app.server.VDResponse { *; }

# Workarounds / FakeContext / DisplayManagerHelper: 在 server 进程内通过反射调用
-keep class com.autobot.app.third.Workarounds { *; }
-keep class com.autobot.app.third.FakeContext { *; }
-keep class com.autobot.app.third.DisplayManagerHelper { *; }

# ShizukuProcessManager: 反射 Shizuku.newProcess 启动 server
-keep class com.autobot.app.manager.ShizukuProcessManager { *; }

# ==================== 三方库（R8 默认会剥离未使用类，但反射入口需显式 keep）====================

# OpenCV：Utils.bitmapToMat 内部通过 JNI 反射访问 Bitmap 像素，保留 org.opencv.** 入口
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ML Kit Text Recognition：模型加载与识别走反射，保留 com.google.mlkit.** / com.google.android.gms.vision.**
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.vision.**

# kotlinx.coroutines：R8 已带 consumer rules，但 ensureNotNull/Continuation 入口保险性保留
-dontwarn kotlinx.coroutines.**

# ==================== 数据类（Parcel 序列化 + JSON 解析）====================
# TaskFile / ProgramTask / TaskAction / RecognitionTask 等模型走 JSON 反射（Gson/Moshi 风格），
# 字段名需保留，否则反序列化字段为 null
-keep class com.autobot.app.model.** { *; }
-keepclassmembers class com.autobot.app.model.** { *; }

# Compose 自动带 consumer rules，无需额外配置

