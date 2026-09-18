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
        const val VERSION = 1
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
        // Android 10+ 需要指定用户；-r 覆盖安装，-t 允许测试包
        val flag = if (replace) "-r -t" else "-t"
        val r = ShizukuShellExecutor.execWithTimeout(
            "pm install $flag \"$apkPath\"", 120_000)
        return mapCommandResult(r, "install")
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