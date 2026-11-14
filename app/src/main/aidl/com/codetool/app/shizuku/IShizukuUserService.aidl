package com.codetool.app.shizuku;

/**
 * Shizuku user-service 接口：让 App 以 shell（adb，uid 2000）身份执行 Android 命令。
 *
 * 13.1.5 的 Shizuku 门面类没有 newProcess，执行命令需走 user-service：
 * App 通过 Shizuku.bindUserService 绑到这个 AIDL，拿到 IShizukuUserService
 * 后调 exec() 在 shell 身份下跑命令并回收输出。
 *
 * 由 ShizukuBridge.kt 里的 ShizukuUserService 实现（同包）。
 */
interface IShizukuUserService {
    /** 执行 cmd（经 sh -c），返回「exit=<code>\n<stdout+stderr>」。 */
    String exec(String cmd);
}
