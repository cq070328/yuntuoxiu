package com.yuntuoxiu.app.engine

import com.yuntuoxiu.app.LogStore
import com.yuntuoxiu.app.shizuku.ShizukuShellExecutor
import java.io.File

/**
 * LogCapture —— 本地日志/崩溃捕获引擎（v2.0）
 *
 * 借鉴「崩溃日志抓包神器」（LogFox / Logcat Reader）的能力：
 *   · 抓取 logcat（通过 Shizuku shell，无需 READ_LOGS 权限）
 *   · 过滤指定包名/标签
 *   · 提取崩溃堆栈（FATAL EXCEPTION / AndroidRuntime）
 *   · 落盘到任务目录，供诊断
 *
 * 为什么需要：
 *   云脱修在「脱壳/修复」时，目标 App 可能崩溃或异常退出。
 *   抓取其 logcat 能快速定位原因（壳对抗、签名不符、SO 缺失等）。
 *
 * 依赖：Shizuku（shell 有读 logcat 的权限）。
 */
object LogCapture {

    private const val TAG = "LogCapture"

    data class CaptureResult(
        val ok: Boolean,
        val file: File?,
        val lineCount: Int,
        val crashFound: Boolean,
        val crashSnippet: String,
        val detail: String,
    )

    /**
     * 抓取 logcat 并落盘。
     *
     * @param taskId    任务 ID（决定落盘路径）
     * @param packageFilter 目标包名（过滤，可空）
     * @param durationMs    抓取时长（默认 5 秒）
     * @param clearFirst    抓取前是否清空缓冲区
     * @return CaptureResult
     */
    fun capture(
        taskId: String,
        packageFilter: String? = null,
        durationMs: Long = 5000,
        clearFirst: Boolean = false,
    ): CaptureResult {
        return try {
            val outDir = File(com.yuntuoxiu.app.YunTuoXiuApp.CLOUD_ROOT, "logs/$taskId")
            outDir.mkdirs()
            val logFile = File(outDir, "logcat_${System.currentTimeMillis()}.txt")

            val clear = if (clearFirst) "logcat -c; " else ""
            // Shizuku shell 有 logcat 权限；用 timeout 控制时长
            val cmd = buildString {
                append(clear)
                append("timeout ${durationMs / 1000} logcat -v threadtime -d 2>/dev/null || ")
                append("timeout ${durationMs / 1000} logcat -v threadtime 2>/dev/null | head -5000")
            }

            LogStore.i(TAG, "抓取 logcat (pkg=$packageFilter, ${durationMs}ms)")
            val r = ShizukuShellExecutor.execWithTimeout(cmd, (durationMs + 5000).toInt())
            val out = r.getString("stdout") ?: ""

            if (out.isBlank()) {
                return CaptureResult(false, null, 0, false, "",
                    "logcat 为空（Shizuku 未授权？目标未运行？）")
            }

            // 过滤（若指定包名）
            val filtered = if (packageFilter.isNullOrBlank()) out
            else out.lineSequence().filter {
                it.contains(packageFilter) || it.contains("AndroidRuntime") ||
                        it.contains("FATAL") || it.contains("System.err")
            }.joinToString("\n")

            logFile.writeText(filtered)
            val lines = filtered.count { it == '\n' } + 1

            // 提取崩溃堆栈
            val crash = extractCrash(filtered)

            LogStore.i(TAG, "logcat 已保存: ${logFile.absolutePath} ($lines 行, crash=${crash.first})")
            CaptureResult(true, logFile, lines, crash.first, crash.second,
                "已抓取 $lines 行" + if (crash.first) "（含崩溃）" else "")
        } catch (t: Throwable) {
            LogStore.e(TAG, "抓取失败: ${t.message}")
            CaptureResult(false, null, 0, false, "", "抓取失败: ${t.message}")
        }
    }

    /**
     * 提取崩溃堆栈（FATAL EXCEPTION / AndroidRuntime）。
     * @return (是否有崩溃, 崩溃片段)
     */
    private fun extractCrash(text: String): Pair<Boolean, String> {
        val lines = text.split('\n')
        var start = -1
        for (i in lines.indices) {
            val l = lines[i]
            if (l.contains("FATAL EXCEPTION") || l.contains("AndroidRuntime") ||
                l.contains("FATAL:") || l.contains("SIGSEGV") || l.contains("signal 11")) {
                start = i
                break
            }
        }
        if (start < 0) return false to ""

        // 取崩溃点后 40 行
        val end = minOf(start + 40, lines.size)
        val snippet = lines.subList(start, end).joinToString("\n")
        return true to snippet
    }

    /**
     * 仅在 logcat 中搜崩溃（不落盘），用于快速判断目标是否崩溃。
     */
    fun quickCrashCheck(packageFilter: String, durationMs: Long = 3000): Pair<Boolean, String> {
        val r = capture("_quick", packageFilter, durationMs, clearFirst = true)
        return r.crashFound to r.crashSnippet
    }
}