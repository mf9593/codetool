# CodeTool release 混淆 keep 规则（预备：当前 release 仍 isMinifyEnabled=false，
# 开启 R8 前需先实机回归以下 keep 类，避免反射/JNI 被裁剪后崩溃）。

# JNI：compositor NativeBridge（符号 Java_me_phie_tawc_compositor_NativeBridge_* 依赖类名）
-keepclasseswithmembernames class me.phie.tawc.compositor.NativeBridge {
    native <methods>;
}
-keep class me.phie.tawc.compositor.** { *; }

# Shizuku：Binder AIDL 接口经反射/序列化创建代理
-keep class moe.shizuku.** { *; }
-keep class com.codetool.app.shizuku.** { *; }

# FileProvider（androidx 自带规则之外显式保留）
-keep class androidx.core.content.FileProvider { *; }

# 配置/模型数据类：Jsonc 解析、org.json 反射构建
-keep class com.codetool.app.ai.** { *; }
-keep class com.codetool.app.util.Jsonc** { *; }

# Kotlin 协程
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
