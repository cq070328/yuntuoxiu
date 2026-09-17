package com.yuntuoxiu.app

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LogStore —— App 内统一日志（任务工作日志 + 运行日志）。
 *
 * 用途：
 *  1) 记录 Worker 的每一步工作（任务发现 / 指令执行 / 回执结果）
 *  2) 记录关键异常（不崩溃，只记录）
 *  3) 落盘到工作区 logs/app.log，可在 App 内日志页查看
 *
 * 与后端区别：
 *  - 后端 logs/<task_id>/task.log  ：后端调度日志
 *  - 本 LogStore：客户端工作日志（App 侧能做什么、做了什么、结果如何）
 */
object LogStore {

    private const val TAG = "LogStore"
    private const val MAX_LINES = 500          // 内存/文件保留最大行数
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /** App 私有日志（总是可用） */
    private val privateFile: File
        get() = File(YunTuoXiuApp.instance.filesDir, "app_work.log")

    /** 工作区日志（有权限时，后端也能读） */
    private val workspaceFile: File
        get() = File(YunTuoXiuApp.CLOUD_ROOT, "logs/app_client.log")

    private val buffer = ArrayDeque<String>()

    @Synchronized
    fun log(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$level] $tag: $msg"
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()

        // 1) 系统 logcat
        when (level) {
            "E" -> Log.e(tag, msg)
            "W" -> Log.w(tag, msg)
            "D" -> Log.d(tag, msg)
            else -> Log.i(tag, msg)
        }

        // 2) 落盘（两个位置都尽力写，失败不影响）
        appendTo(privateFile, line)
        try {
            workspaceFile.parentFile?.mkdirs()
            appendTo(workspaceFile, line)
        } catch (_: Throwable) {
        }
    }

    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)
    fun d(tag: String, msg: String) = log("D", tag, msg)

    private fun appendTo(f: File, line: String) {
        try {
            // 简单滚动：超过 MAX_LINES 就截断重写
            if (f.exists() && f.length() > 256 * 1024) {
                val keep = f.readLines().takeLast(MAX_LINES / 2)
                f.writeText(keep.joinToString("\n") + "\n")
            }
            f.appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    /** 读取全部日志（供 UI 显示），倒序（最新在前） */
    @Synchronized
    fun readAll(): String {
        return try {
            if (privateFile.exists()) {
                privateFile.readLines().takeLast(MAX_LINES).reversed().joinToString("\n")
            } else {
                buffer.reversed().joinToString("\n")
            }
        } catch (t: Throwable) {
            "读取日志失败: ${t.message}"
        }
    }

    /** 清空日志 */
    @Synchronized
    fun clear() {
        buffer.clear()
        try { privateFile.writeText("") } catch (_: Throwable) {}
        try { workspaceFile.writeText("") } catch (_: Throwable) {}
        i(TAG, "日志已清空")
    }

    fun filePath(): String = privateFile.absolutePath
    fun workspacePath(): String = workspaceFile.absolutePath
}