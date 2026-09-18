package com.yuntuoxiu.app.shizuku

import android.content.Context
import android.os.Bundle
import android.os.RemoteCallbackList
import android.util.Log

/**
 * 云脱修 Shizuku UserService 实现（v1.8.5 关键修正）。
 *
 * ⚠️ 重大修正 —— 官方规则（Shizuku-API README）：
 *     "Unlike Bound service, the service class must implement IBinder interface.
 *      The usual usage is `public class YourService extends IYouAidlInterface.Stub`."
 *
 *   即：UserService 类**必须直接继承 AIDL 的 Stub**（实现 IBinder），
 *   **不是**继承 android.app.Service、也不是在 onBind 里返回 binder！
 *
 *   旧实现 `class YunTuoXiuUserService : Service()` 是**普通 Bound Service** 的写法，
 *   而 Shizuku 的 UserService 由 shell uid 在**独立进程**里实例化（不走 onBind），
 *   因此旧实现会导致 `bindUserService` 永远不回调 `onServiceConnected`
 *   → 所有 Shizuku 操作报 ERR_SERVICE_NOT_BOUND(-1003)。
 *
 *   构造函数（官方说明）：
 *     · 可提供「默认构造函数」与「带 Context 参数的构造函数」
 *     · Shizuku v13 会**优先尝试带 Context 的构造函数**；旧版用默认构造函数
 *     · 注意：此处的 Context 与普通 Android App 的 Context 行为不同
 *       （不能用于 registerReceiver / getContentResolver 等）
 *
 *   能力封装：
 *     installApk / uninstallApp / startActivity / clearAppData / getAppInfo /
 *     exec / execWithTimeout
 *   每个能力都做「命令输出 -> 错误码」二次映射，返回码对齐后端 fail_code。
 */
class YunTuoXiuUserService : IYunTuoXiuService.Stub {

    companion object {
        private const val TAG = "YunTuoXiuUserService"
        // ⚠️ 与 ShizukuClient.userServiceArgs().version(N) 保持一致
        const val VERSION = 2
    }

    /** 回调列表（线程安全） */
    private val callbacks = RemoteCallbackList<IYunTuoXiuCallback>()

    /** 默认构造函数（旧版 Shizuku 使用） */
    constructor() : super()

    /** 带 Context 的构造函数（Shizuku v13 优先使用） */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun getVersion(): Int = VERSION

    override fun exec(cmd: String): Bundle =
        ShizukuShellExecutor.execWithTimeout(cmd, ShizukuShellExecutor.DEFAULT_TIMEOUT_MS)

    override fun execWithTimeout(cmd: String, timeoutMs: Int): Bundle =
        ShizukuShellExecutor.execWithTimeout(cmd, timeoutMs)

    // ---------------- 安装 ----------------
    override fun installApk(apkPath: String, replace: Boolean): Bundle {
        if (apkPath.isBlank()) return err(ShizukuErrorCodes.ERR_INVALID_ARGS)
        val flag = if (replace) "-r -t" else "-t"

        // ⭐ v1.8.6 修复 1：Android 14+（实测 Android 16）SELinux 限制
        //   system_server 无法读取 /sdcard（fuse）下的文件：
        //     avc: denied { read } ... tcontext=u:object_r:fuse:s0
        //   因此先复制到 /data/local/tmp/ 再安装。
        val stagedPath = stageToLocalTmp(apkPath) ?: apkPath

        // 首次安装
        var r = ShizukuShellExecutor.execWithTimeout(
            "pm install $flag \"$stagedPath\"", 120_000)
        var mapped = mapInstallCode(r)

        // ⭐ v1.8.6 修复 2：签名冲突（INSTALL_FAILED_INCOMPATIBLE）自动重试
        //   场景：设备上已装同名 app（原始签名），lspatch 注入版签名不同，
        //        `pm install -r` 覆盖安装报「签名不兼容」。
        //   处理：提取 APK 包名 -> 卸载旧版 -> 重新安装。
        if (mapped == ShizukuErrorCodes.INSTALL_FAILED_INCOMPATIBLE) {
            val pkg = extractPackageName(stagedPath)
            if (!pkg.isNullOrBlank()) {
                ShizukuShellExecutor.execWithTimeout(
                    "pm uninstall \"$pkg\"", 60_000)
                // 卸载后重装
                r = ShizukuShellExecutor.execWithTimeout(
                    "pm install $flag \"$stagedPath\"", 120_000)
                mapped = mapInstallCode(r)
            }
        }

        // 清理中转文件（尽力）
        if (stagedPath != apkPath) {
            ShizukuShellExecutor.execWithTimeout("rm -f \"$stagedPath\"", 10_000)
        }
        return mapCommandResult(r, "install")
    }

    /** 从 APK 提取包名（用 aapt/dumpsys 兜底的方式）。 */
    private fun extractPackageName(apkPath: String): String? {
        return try {
            // pm dump 可解析包的 manifest（无需安装）
            val r = ShizukuShellExecutor.execWithTimeout(
                "pm dump \"$apkPath\" 2>/dev/null | grep -m1 'packageName=' | head -1", 30_000)
            val out = r.getString("stdout") ?: ""
            Regex("packageName=([\\w\\.]+)").find(out)?.groupValues?.get(1)
        } catch (t: Throwable) {
            null
        }
    }

    /** 只做 install 的错误码映射（用于判断是否需要重试）。 */
    private fun mapInstallCode(r: Bundle): Int {
        val mapped = mapCommandResult(r, "install")
        return mapped.getInt("code")
    }

    /**
     * 把 APK 复制到 /data/local/tmp/ 下的中转路径。
     *
     * 目的：绕过 Android 14+ 的 SELinux 限制（system_server 读不了 /sdcard/fuse）。
     * @return 中转路径；失败返回 null。
     */
    private fun stageToLocalTmp(apkPath: String): String? {
        return try {
            val dst = "/data/local/tmp/ytx_stage_${System.currentTimeMillis()}.apk"
            val r = ShizukuShellExecutor.execWithTimeout(
                "cp -f \"$apkPath\" \"$dst\" && [ -s \"$dst\" ] && echo OK", 120_000)
            val out = (r.getString("stdout") ?: "")
            if (out.contains("OK")) dst else null
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------- 卸载 ----------------
    override fun uninstallApp(pkg: String): Bundle {
        if (pkg.isBlank()) return err(ShizukuErrorCodes.ERR_INVALID_ARGS)
        val r = ShizukuShellExecutor.execWithTimeout("pm uninstall \"$pkg\"", 60_000)
        return mapCommandResult(r, "uninstall")
    }

    // ---------------- 启动 ----------------
    override fun startActivity(pkg: String, cls: String): Bundle {
        if (pkg.isBlank()) return err(ShizukuErrorCodes.ERR_INVALID_ARGS)
        val cmd = if (cls.isBlank()) {
            // 用 monkey 启动 launcher 入口
            "monkey -p \"$pkg\" -c android.intent.category.LAUNCHER 1"
        } else {
            "am start -n \"$pkg/$cls\""
        }
        val r = ShizukuShellExecutor.execWithTimeout(cmd, 30_000)
        return mapCommandResult(r, "start")
    }

    // ---------------- 清数据 ----------------
    override fun clearAppData(pkg: String): Bundle {
        if (pkg.isBlank()) return err(ShizukuErrorCodes.ERR_INVALID_ARGS)
        val r = ShizukuShellExecutor.execWithTimeout("pm clear \"$pkg\"", 30_000)
        return mapCommandResult(r, "clear")
    }

    // ---------------- 应用信息 ----------------
    override fun getAppInfo(pkg: String): Bundle {
        if (pkg.isBlank()) return err(ShizukuErrorCodes.ERR_INVALID_ARGS)
        val r = ShizukuShellExecutor.execWithTimeout("dumpsys package \"$pkg\"", 30_000)
        if (r.getInt("code") != ShizukuErrorCodes.OK) {
            return mapCommandResult(r, "getinfo")
        }
        val out = r.getString("stdout") ?: ""
        val info = Bundle()
        info.putInt("code", ShizukuErrorCodes.OK)
        info.putString("versionName", regexValue(out, "versionName=([^\\s]+)"))
        info.putString("versionCode", regexValue(out, "versionCode=(\\d+)"))
        info.putString("firstInstallTime", regexValue(out, "firstInstallTime=(.+(?=\\n))"))
        info.putString("raw", out.take(4000))
        return info
    }

    // ---------------- 回调 ----------------
    override fun registerCallback(cb: IYunTuoXiuCallback?) {
        if (cb != null) callbacks.register(cb)
    }

    override fun unregisterCallback(cb: IYunTuoXiuCallback?) {
        if (cb != null) callbacks.unregister(cb)
    }

    // ---------------- 辅助 ----------------

    private fun err(code: Int): Bundle = Bundle().apply {
        putInt("code", code)
        putString("stdout", "")
        putString("stderr", ShizukuErrorCodes.describe(code))
    }

    /**
     * 把任意命令的原始结果按「能力类型」二次映射到统一错误码。
     *
     * pm/am 的失败信息在 stdout/stderr 中，且退出码常为 0，
     * 因此必须解析文本关键词来判断成败。
     */
    private fun mapCommandResult(r: Bundle, kind: String): Bundle {
        val rawCode = r.getInt("code")
        if (rawCode == ShizukuErrorCodes.ERR_TIMEOUT ||
            rawCode == ShizukuErrorCodes.ERR_IO) {
            return r
        }
        val out = (r.getString("stdout") ?: "") + "\n" + (r.getString("stderr") ?: "")
        val lower = out.toLowerCase()

        val mapped: Int = when (kind) {
            "install" -> when {
                lower.contains("success") -> ShizukuErrorCodes.OK
                lower.contains("incompatible") || lower.contains("signatures do not match") ->
                    ShizukuErrorCodes.INSTALL_FAILED_INCOMPATIBLE
                lower.contains("invalid apk") || lower.contains("parse") ->
                    ShizukuErrorCodes.INSTALL_FAILED_INVALID_APK
                lower.contains("failure") || lower.contains("exception") ->
                    ShizukuErrorCodes.INSTALL_FAILED
                else -> ShizukuErrorCodes.INSTALL_FAILED
            }
            "uninstall" -> if (lower.contains("success")) ShizukuErrorCodes.OK
            else ShizukuErrorCodes.UNINSTALL_FAILED

            "start" -> when {
                lower.contains("error") || lower.contains("exception") ||
                        lower.contains("unable") || lower.contains("does not exist") ->
                    ShizukuErrorCodes.START_FAILED
                else -> ShizukuErrorCodes.OK
            }
            "clear" -> if (lower.contains("success")) ShizukuErrorCodes.OK
            else ShizukuErrorCodes.CLEAR_FAILED

            "getinfo" -> ShizukuErrorCodes.OK
            else -> ShizukuErrorCodes.OK
        }

        val result = Bundle()
        result.putInt("code", mapped)
        result.putString("stdout", r.getString("stdout") ?: "")
        result.putString("stderr", r.getString("stderr") ?: "")
        result.putString("fail_code", ShizukuErrorCodes.toBackendFailCode(mapped))
        // ⭐ v1.8.6：把失败原因的**关键摘要**放进 detail 友好位（stderr 首行），
        //   避免上层只看到「安装失败: 安装失败」这类无信息提示。
        if (mapped != ShizukuErrorCodes.OK) {
            val firstErr = r.getString("stderr")?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }?.take(300) ?: ""
            result.putString("detail", firstErr)
        }
        return result
    }

    private fun regexValue(text: String, pattern: String): String {
        return try {
            Regex(pattern).find(text)?.groupValues?.get(1)?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}