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

# Compose 自动带 consumer rules，无需额外配置

