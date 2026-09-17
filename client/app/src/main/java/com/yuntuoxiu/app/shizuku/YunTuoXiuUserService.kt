package com.yuntuoxiu.app.shizuku

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log

/**
 * 云脱修 Shizuku UserService 实现。
 *
 * 运行环境：由 Shizuku server（shell uid）启动本服务，因此本进程内
 * 可直接调用 pm / am / dumpsys 等系统命令，等效 adb shell 能力（无需 root）。
 *
 * ⚠️ 基类对齐（已按官方 API 13.1.5 核实）：
 *   `dev.rikka.shizuku:api` 中**不存在** `rikka.shizuku.ShizukuService` 类
 *   （经 AAR 反编译核实类清单）。Shizuku 的 UserService 就是一个**普通
 *   Android Service**，通过 `Shizuku.bindUserService(UserServiceArgs, conn)`
 *   绑定；Shizuku 的 server 会用 shell uid 拉起它。
 *   因此这里继承标准 `android.app.Service`，在 onBind 里返回 AIDL binder。
 *
 * 能力封装：
 *  - installApk / uninstallApp / startActivity / clearAppData / getAppInfo
 *  - exec / execWithTimeout（任意 shell）
 * 每个能力都做「命令输出 -> 错误码」二次映射，返回码可直接对齐后端 fail_code。
 */
class YunTuoXiuUserService : Service() {

    companion object {
        private const val TAG = "YunTuoXiuUserService"
        const val VERSION = 1
    }

    // 回调列表（线程安全）
    private val callbacks = RemoteCallbackList<IYunTuoXiuCallback>()

    private val binder: IYunTuoXiuService.Stub = object : IYunTuoXiuService.Stub() {

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
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // 通知所有客户端：服务即将断开
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            try {
                callbacks.getBroadcastItem(i).onServiceDisconnected(ShizukuErrorCodes.ERR_BINDER_DEAD)
            } catch (e: Exception) {
                Log.w(TAG, "回调通知失败", e)
            }
        }
        callbacks.finishBroadcast()
        callbacks.kill()
        super.onDestroy()
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
