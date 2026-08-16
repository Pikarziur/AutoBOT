# 添加项目专用的 ProGuard 规则。
# 你可以在这里添加混淆规则，通常为保持类不被混淆添加规则。
# 对于 Shizuku，需要保持一些类不被混淆
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# NativeCapturer：JNI RegisterNatives 通过类名+方法名查找，必须保持不被混淆
-keep class com.autobot.app.nativelib.NativeCapturer { *; }

# CompositionService / ViewModel 中被 Native 反射或序列化的类保持
-keep class com.autobot.app.service.CompositionService { *; }

# Compose 自动带 consumer rules，无需额外配置

