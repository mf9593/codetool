package com.codetool.app

import android.os.Environment
import java.io.File
import java.util.Locale

/**
 * 在线下载的构建环境包（GitHub Release 资产，只收 arm64；其它架构不放进 data/，
 * 避免 03-buildenv.sh 把 armv7/x86_64 包也装进容器）。
 * 默认仓库是用户自己的 codetool-env；仓库/镜像可在下载设置里改。
 */
data class BuildPackage(
    val id: String,
    val label: String,
    val desc: String,
    val tag: String,
    val asset: String,
    val sizeBytes: Long,
    /** 上游 nullij/sclrepo 是否也有这个资产（JDK 只在用户仓库有）。 */
    val hasUpstream: Boolean = true,
)

object BuildPackageCatalog {
    /** 用户自己的镜像仓库（已含上游 7 个 release + jdk17/jdk21）。 */
    const val DEFAULT_REPO = "mf9593/codetool-env"
    /** 上游备用仓库（旧版/兜底；不含 JDK）。 */
    const val UPSTREAM_REPO = "nullij/sclrepo"

    /** 压缩包统一放这里；03-buildenv.sh 安装时会从这里复制进容器。 */
    val dataDir: File
        get() = File(Environment.getExternalStorageDirectory(), "Download/codetool/data")

    val packages: List<BuildPackage> = listOf(
        BuildPackage(
            "android-sdk", "Android SDK 37.0.0", "platforms 36 / 36.1 / 37.0",
            "android-sdk-37.0.0", "android-sdk.tar.gz", 219_174_384L
        ),
        BuildPackage(
            "ndk-r27d", "NDK r27d", "27.3.13750724 · arm64",
            "v27.3.13750724", "r27d_arm64-v8a.tar.xz", 283_773_452L
        ),
        BuildPackage(
            "ndk-r30b", "NDK r30b", "30.0.14904198 · arm64",
            "v30.0.14904198", "r30b_arm64-v8a.tar.xz", 156_539_208L
        ),
        BuildPackage(
            "buildtools", "build-tools 36.1.0", "arm64 aapt2",
            "v36.1.0", "buildtools_36.1.0_arm64-v8a.tar.xz", 16_587_120L
        ),
        BuildPackage(
            "platformtools", "platform-tools 36.1.0", "arm64 adb",
            "v36.1.0", "platformtools_arm64-v8a.tar.xz", 3_456_912L
        ),
        BuildPackage(
            "cmake", "CMake 4.3.0", "aarch64",
            "cmake", "4.3.0-aarch64.tar.xz", 35_490_464L
        ),
        BuildPackage(
            "flutter", "Flutter SDK", "arm64",
            "flutter-sdk", "flutsdk-arm64-v8a.zip", 277_262_193L
        ),
        BuildPackage(
            "jdk17", "JDK 17", "Adoptium 17.0.13+11 · arm64",
            "jdk17", "OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.13_11.tar.gz", 190_583_488L,
            hasUpstream = false
        ),
        BuildPackage(
            "jdk21", "JDK 21", "Adoptium 21.0.10+7 · arm64",
            "jdk21", "OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.10_7.tar.gz", 205_360_862L,
            hasUpstream = false
        ),
    )

    fun assetUrl(repo: String, pkg: BuildPackage): String =
        "https://github.com/${repo.trim().trimEnd('/')}/releases/download/${pkg.tag}/${pkg.asset}"

    /** 镜像前缀只作用于 GitHub URL（用户要求仅支持 GitHub）。 */
    fun mirrorUrl(url: String, prefix: String): String {
        if (prefix.isBlank() || !url.startsWith("https://github.com/")) return url
        return prefix.trim().trimEnd('/') + "/" + url
    }

    fun localFile(pkg: BuildPackage): File = File(dataDir, pkg.asset)

    fun localFile(name: String): File = File(dataDir, name)

    fun isDownloaded(pkg: BuildPackage): Boolean =
        localFile(pkg).let { it.isFile && it.length() > 0 }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
