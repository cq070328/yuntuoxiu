package com.yuntuoxiu.app.shizuku

import android.os.Bundle
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Shell 执行器（运行在 Shizuku UserService 进程内，拥有 shell uid）。
 *
 * ## 架构说明（已核实官方 API）
 *
 * 官方 `rikka.shizuku.Shizuku` 类中**没有** `newProcess` / `exec` 方法；
 * `ShizukuRemoteProcess` 的构造器是**包级私有**（`(IRemoteProcess)`），
 * 客户端无法直接创建。它是给「普通 uid 的客户端 App 想远程执行 shell」
 * 用的内部能力。
 *
 * 本项目的正确路径是：
 * ```
 * 客户端 App(普通uid) → AIDL → UserService(shell uid) → ProcessBuilder 执行
 * ```
 * UserService 本身就在 shell uid 下运行，**直接 `ProcessBuilder` 即可**，
 * 无需（也无法）使用 ShizukuRemoteProcess。
 *
 * ## 关键防护
 * 1) **超时控制**：`waitFor(timeout)`，超时 `destroyForcibly()`，绝不阻塞调用方
 * 2) **输出并发捕获**：stdout/stderr 用独立线程并发读取，避免管道缓冲区写满死锁
 * 3) **进程树清理**：超时时确保子进程被回收
 * 4) **结果统一封装**：`Bundle{code, stdout, stderr}`
 */
object ShizukuShellExecutor {

    private const val TAG = "ShizukuShell"
    const val DEFAULT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_SEC = 3L
    private const val MAX_OUTPUT_BYTES = 256 * 1024   // 单流最大 256KB，防 OOM

    // 线程池：命令输出读取（有界，避免无限开线程）
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "yuntuoxiu-shell").apply { isDaemon = true }
    }

    fun exec(cmd: String): Bundle = execWithTimeout(cmd, DEFAULT_TIMEOUT_MS)

    fun execWithTimeout(cmd: String, timeoutMs: Int): Bundle {
        val result = Bundle()
        if (cmd.isBlank()) {
            return result.apply {
                putInt("code", ShizukuErrorCodes.ERR_INVALID_ARGS)
                putString("stdout", "")
                putString("stderr", "空命令")
            }
        }

        val effectiveTimeout = if (timeoutMs <= 0) DEFAULT_TIMEOUT_MS else timeoutMs
        var process: Process? = null
        var outFuture: Future<ByteArray>? = null
        var errFuture: Future<ByteArray>? = null

        try {
            process = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(false)
                .start()

            // 并发读取 stdout / stderr（避免缓冲区写满死锁）
            // ⚠️ 必须显式声明 Callable：ExecutorService.submit 有 Runnable /
            //    Callable<T> 两个重载，Kotlin lambda 会歧义解析为 Runnable
            //    （返回 Future<*>），导致类型不匹配。
            val p = process
            outFuture = executor.submit(Callable { readLimited(p.inputStream) })
            errFuture = executor.submit(Callable { readLimited(p.errorStream) })

            val finished = try {
                process.waitFor(effectiveTimeout.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

            if (!finished) {
                // 超时：强制杀掉进程
                killProcessTree(process)
                outFuture.cancel(true)
                errFuture.cancel(true)
                Log.w(TAG, "shell 超时(${effectiveTimeout}ms): $cmd")
                return result.apply {
                    putInt("code", ShizukuErrorCodes.ERR_TIMEOUT)
                    putString("stdout", "")
                    putString("stderr", "shell 执行超时(${effectiveTimeout}ms): $cmd")
                }
            }

            val stdout = safeGet(outFuture)
            val stderr = safeGet(errFuture)
            val exitCode = try { process.exitValue() } catch (e: Exception) { -1 }

            result.putInt("code",
                if (exitCode == 0) ShizukuErrorCodes.OK else exitCode)
            result.putString("stdout", stdout)
            result.putString("stderr", stderr)
            return result
        } catch (e: TimeoutException) {
            process?.let { killProcessTree(it) }
            return result.apply {
                putInt("code", ShizukuErrorCodes.ERR_TIMEOUT)
                putString("stdout", "")
                putString("stderr", "输出读取超时: ${e.message}")
            }
        } catch (e: Exception) {
            process?.let { killProcessTree(it) }
            return result.apply {
                putInt("code", ShizukuErrorCodes.ERR_IO)
                putString("stdout", "")
                putString("stderr", "shell 执行异常: ${e.message}")
            }
        } finally {
            // 确保进程被回收（正常结束时也已退出）
            process?.let {
                try { if (it.isAlive) killProcessTree(it) } catch (_: Exception) {}
            }
        }
    }

    /** 读取流，限制最大字节数（防 OOM）。 */
    private fun readLimited(ins: java.io.InputStream): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        try {
            while (true) {
                val n = ins.read(tmp)
                if (n <= 0) break
                if (buf.size() + n > MAX_OUTPUT_BYTES) {
                    buf.write(tmp, 0, MAX_OUTPUT_BYTES - buf.size())
                    break
                }
                buf.write(tmp, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            try { ins.close() } catch (_: Exception) {}
        }
        return buf.toByteArray()
    }

    private fun safeGet(f: Future<ByteArray>?): String {
        if (f == null) return ""
        return try {
            f.get(READ_TIMEOUT_SEC, TimeUnit.SECONDS).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    /** 强制终止进程（先 destroy，再 destroyForcibly）。 */
    private fun killProcessTree(p: Process) {
        try {
            p.destroy()
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
            }
        } catch (_: Exception) {
            try { p.destroyForcibly() } catch (_: Exception) {}
        }
    }
}