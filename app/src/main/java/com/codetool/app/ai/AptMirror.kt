package com.codetool.app.ai

import android.content.Context
import com.codetool.app.runtime.RootfsManager
import com.codetool.app.util.AppSettings
import java.io.File

/**
 * 容器 APT 源管理：终端设置「换源」下拉选择镜像。
 *
 * 背景（真实踩坑）：国内网络下 ports.ubuntu.com 的 CDN/DNS 解析会拿到坏 IP
 * （实测 `183.60.155.207:443` 连接挂死 4 分多钟、80 端口回 418），apt-get update
 * 直接卡死。默认改用清华镜像绕开；官方源保留可随时切回。
 */
object AptMirror {

    data class Mirror(val id: String, val label: String, val base: String)

    /** 可选镜像列表（下拉顺序即显示顺序）。 */
    val ALL = listOf(
        Mirror("official", "官方源（ports.ubuntu.com）", "http://ports.ubuntu.com/ubuntu-ports"),
        Mirror("tuna", "清华（Tsinghua）", "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"),
        Mirror("aliyun", "阿里云", "http://mirrors.aliyun.com/ubuntu-ports"),
        Mirror("ustc", "中科大", "http://mirrors.ustc.edu.cn/ubuntu-ports"),
        Mirror("huawei", "华为云", "http://repo.huaweicloud.com/ubuntu-ports"),
        Mirror("tencent", "腾讯云", "https://mirrors.cloud.tencent.com/ubuntu-ports"),
        Mirror("sjtu", "上海交大", "http://mirror.sjtu.edu.cn/ubuntu-ports"),
    )

    /** 默认镜像 id：清华（用户要求默认清华，绕开被污染的官方源）。 */
    const val DEFAULT_ID = AppSettings.DEFAULT_APT_MIRROR

    private const val KEYRING = "/usr/share/keyrings/ubuntu-archive-keyring.gpg"

    private fun byId(id: String): Mirror? = ALL.firstOrNull { it.id == id }

    fun label(id: String): String = byId(id)?.label ?: byId(DEFAULT_ID)?.label.orEmpty()

    /** 当前生效镜像 id（未选过则默认清华）。 */
    fun currentId(ctx: Context): String =
        AppSettings.aptMirror(ctx).takeIf { byId(it) != null } ?: DEFAULT_ID

    /** 生成 sources.list 内容（noble + updates + security，arm64 ports 源）。 */
    fun sourcesList(id: String): String {
        val m = byId(id) ?: byId(DEFAULT_ID)!!
        val suites = listOf("noble", "noble-updates", "noble-security")
        return suites.joinToString("\n") { suite ->
            "deb [signed-by=\"$KEYRING\"] ${m.base} $suite main universe multiverse restricted"
        } + "\n"
    }

    /**
     * 把当前镜像写进容器 sources.list（幂等：同内容不写；改前先备份 `.codetool.bak`）。
     * @param rootfs 可传入容器根目录（否则从 RootfsManager 取）
     * @return 是否写入成功（rootfs 不可用返回 false）
     */
    fun apply(ctx: Context, rootfs: File? = null): Boolean {
        val rt = rootfs ?: runCatching {
            RootfsManager.get(ctx.applicationContext).containerRoot
        }.getOrNull() ?: return false
        val f = File(rt, "etc/apt/sources.list")
        f.parentFile?.mkdirs()
        val content = sourcesList(currentId(ctx))
        return if (f.exists() && f.readText() == content) {
            true   // 同内容：幂等跳过
        } else runCatching {
            if (f.exists()) f.copyTo(File(rt, "etc/apt/sources.list.codetool.bak"), overwrite = true)
            f.writeText(content)
            true
        }.getOrDefault(false)
    }
}
