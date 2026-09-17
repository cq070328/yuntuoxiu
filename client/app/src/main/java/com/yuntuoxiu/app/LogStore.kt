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

    /** 读取全部日志（供 UI 显示）—— v1.6.6 修正：旧→新（最新在底部） */
    @Synchronized
    fun readAll(): String {
        return try {
            if (privateFile.exists()) {
                // ⚠️ 不要 .reversed()（那是「新在顶」）；
                //    要「旧→新」，最新一行在文件末尾，直接 join
                privateFile.readLines().takeLast(MAX_LINES).joinToString("\n")
            } else {
                buffer.joinToString("\n")
            }
        } catch (t: Throwable) {
            "读取日志失败: ${t.message}"
        }
    }

    /**
     * ⭐ v1.6.5 增量读取（供 UI 追加，不整段重载）
     *
     * @param sinceSeq 上次读取到的「序号」（首次传 -1）
     * @return Triple(新行列表-已去重, 最新序号, 去重统计)
     *
     * 去重规则：连续相同的行合并为 1 条 + " (xN)"
     */
    @Synchronized
    fun readSince(sinceSeq: Int): Triple<List<String>, Int, Int> {
        val all = try {
            if (privateFile.exists()) privateFile.readLines().takeLast(MAX_LINES)
            else buffer.toList()
        } catch (t: Throwable) {
            return Triple(emptyList(), sinceSeq, 0)
        }

        if (all.isEmpty()) return Triple(emptyList(), 0, 0)
        val start = (sinceSeq + 1).coerceIn(0, all.size)
        if (start >= all.size) return Triple(emptyList(), all.size - 1, 0)

        val fresh = all.subList(start, all.size)

        // ⭐ 去重：连续相同（去掉时间戳前缀后比较）合并
        val out = ArrayList<String>()
        var dup = 0
        var i = 0
        while (i < fresh.size) {
            val cur = fresh[i]
            val body = cur.substringAfter("] ", cur)   // 去时间+级别前缀
            var n = 1
            var j = i + 1
            while (j < fresh.size) {
                val nxt = fresh[j].substringAfter("] ", fresh[j])
                if (nxt == body) { n++; j++ } else break
            }
            if (n > 1) {
                out.add("$cur   (x$n)")
                dup += n - 1
            } else {
                out.add(cur)
            }
            i = j
        }
        return Triple(out, all.size - 1, dup)
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