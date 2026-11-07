# Agent-VScreen: shrink libraries (icons, compose) but keep every app class as is — many are reached by name
# (Shizuku UserService, app_process entry point, reflection on framework classes, the JNI side of ONNX Runtime).
-dontobfuscate
-keep class com.kilon.agentvscreen.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn org.slf4j.**
