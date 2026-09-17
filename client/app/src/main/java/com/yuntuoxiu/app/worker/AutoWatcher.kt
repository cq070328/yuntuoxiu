package com.yuntuoxiu.app.worker

import android.util.Log
import com.google.gson.Gson
import com.yuntuoxiu.app.LogStore
import com.yuntuoxiu.app.YunTuoXiuApp
import com.yuntuoxiu.app.data.TaskCreateRequest
import java.io.File
import java.security.MessageDigest

/**
 * AutoWatcher —— APP 内置的「轻量 watcher」。
 *
 * 背景：
 *   原本任务由容器里的 backend/watcher.py 创建，但 APP 无法调用容器
 *   （Shizuku shell uid 进不去 proot）。因此把 watcher 的核心逻辑
 *   用 Kotlin 在 APP 内实现，做到「提交后秒级创建任务」。
 *
 * 职责（与后端 watcher 对齐）：
 *   1. 扫描 uploads 目录下的 req 文件（客户端提交的创建请求）
 *   2. 在 tasks/<tid>/ 下创建隔离目录 + meta/task_meta.json
 *   3. 下发 PRE_CHECK 指令到 work/actions/0001_PRE_CHECK.req.json
 *   4. 把请求文件移到 uploads/done/
 *
 * 与后端的协作：
 *   - 生成的任务格式与 backend/Core/task_store.py 一致
 *   - 后端 watcher 后续接管时，会识别已有任务（幂等）
 *   - 真正的 dump/修复仍走容器（APP 只做调度骨架）
 */
object AutoWatcher {

    private const val TAG = "AutoWatcher"
    private val gson = Gson()

    private val cloudRoot: File get() = File(YunTuoXiuApp.CLOUD_ROOT)
    private val tasksRoot: File get() = File(cloudRoot, "tasks")
    private val uploadsRoot: File get() = File(cloudRoot, "uploads")
    private val doneRoot: File get() = File(uploadsRoot, "done")

    private val TASK_SUBDIRS = listOf(
        "original", "chunks", "dump", "repaired", "build", "meta", "work"
    )

    /** 单次 tick：消费所有待处理请求 + 执行待办 action。返回本次创建的任务数。 */
    fun tick(): Int {
        var created = 0
        try {
            if (!uploadsRoot.exists()) uploadsRoot.mkdirs()
            // 用 list() 避免 listFiles() 重载歧义
            val allFiles = uploadsRoot.listFiles()
            if (allFiles != null) {
                for (reqFile in allFiles) {
                    if (!reqFile.isFile) continue
                    if (!reqFile.name.startsWith("create_")) continue
                    if (!reqFile.name.endsWith(".req.json")) continue
                    try {
                        if (handleCreate(reqFile)) created++
                    } catch (t: Throwable) {
                        LogStore.e(TAG, "处理请求失败 ${reqFile.name}: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            LogStore.e(TAG, "tick 异常: ${t.message}")
        }
        return created
    }

    /**
     * 【新增】执行所有活跃任务的待办 action（不依赖 WorkerService）。
     * 由 MainActivity / WorkerService 调用。
     */
    fun processActions(context: android.content.Context): Int {
        var done = 0
        try {
            val active = com.yuntuoxiu.app.data.TaskRepository.listTasks()
                .filter { !it.isTerminal }
                // ⚠️ 跳过本地骨架：local_ 任务只是「待后端接管」的占位，
                //    其 work/actions 下没有真实指令，避免和正式任务重复处理。
                .filter { !it.taskId.startsWith("local_") }
            for (task in active) {
                try {
                    val queue = ActionQueue(task.taskId)
                    val executor = ActionExecutor(context, task.taskId)
                    val pending = queue.pendingPayloads()
                    for ((payload, respFile) in pending) {
                        LogStore.i(TAG, "[${task.taskId}] 执行 ${payload.action}")
                        val resp = executor.execute(payload)
                        queue.writeResponse(respFile, resp)
                        LogStore.i(TAG, "[${task.taskId}] ${payload.action} -> " +
                                "ok=${resp.ok} ${resp.detail}")
                        done++
                    }
                } catch (t: Throwable) {
                    LogStore.e(TAG, "[${task.taskId}] 执行 action 失败: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            LogStore.e(TAG, "processActions 异常: ${t.message}")
        }
        return done
    }

    // ---------------- 核心：处理创建请求 ----------------

    private fun handleCreate(reqFile: File): Boolean {
        val req = try {
            gson.fromJson(reqFile.readText(), TaskCreateRequest::class.java)
        } catch (t: Throwable) {
            LogStore.w(TAG, "解析请求失败: ${reqFile.name}"); null
        } ?: run { archive(reqFile); return false }

        val apkPath = req.apkPath
        if (apkPath.isBlank() || !File(apkPath).exists()) {
            LogStore.w(TAG, "APK 不存在，归档请求: $apkPath")
            archive(reqFile); return false
        }

        // 幂等：同 APK 已有非终态任务则不重复创建
        if (hasActiveTaskFor(apkPath)) {
            LogStore.i(TAG, "已有同 APK 活跃任务，跳过: $apkPath")
            archive(reqFile); return false
        }

        // ⚠️ 修复：APP 创建的本地骨架必须用 "local_" 前缀 + local_skeleton=true，
        //    否则后端 watcher._cleanup_local_skeleton 永远无法清理，导致任务列表重复。
        val tid = "local_${System.currentTimeMillis()}_${randomHex(4)}"
        val taskDir = File(tasksRoot, tid)

        // 1) 创建隔离目录
        try {
            for (sub in TASK_SUBDIRS) {
                File(taskDir, sub).mkdirs()
            }
        } catch (t: Throwable) {
            LogStore.e(TAG, "创建任务目录失败: ${t.message}"); return false
        }

        // 2) 复制 APK 到任务 original/（只读隔离）
        val srcApk = File(apkPath)
        val origApk = File(taskDir, "original/${srcApk.name}")
        try {
            srcApk.copyTo(origApk, overwrite = true)
        } catch (t: Throwable) {
            LogStore.e(TAG, "复制 APK 失败: ${t.message}")
        }

        // 3) 写 task_meta.json（对齐后端 TaskMeta 格式）
        val now = System.currentTimeMillis() / 1000
        val sha = try { sha256(origApk) } catch (t: Throwable) { "" }

        // 【新增】APP 内置壳识别（秒级，不依赖容器）
        val verdict = try {
            ShellDetect.detect(origApk.absolutePath)
        } catch (t: Throwable) {
            LogStore.e(TAG, "壳识别失败: ${t.message}"); null
        }

        val meta = mapOf(
            "task_id" to tid,
            "state" to "CREATED",
            "idem_key" to tid,
            "source_apk" to origApk.absolutePath,
            "source_apk_sha256" to sha,
            "package_name" to req.packageName,
            "version_name" to "",
            "allow_auto_degrade" to req.allowAutoDegrade,
            "client_abi" to "arm64-v8a",
            "shell_tag" to verdict?.tag,           // 【新增】壳标签
            "shell_confidence" to verdict?.confidence,
            "shell_reasons" to (verdict?.reasons ?: emptyList<String>()),
            "fail_code" to null,
            "artifact_status" to null,
            "handler_trace" to emptyList<Any>(),
            "degrade_trace" to emptyList<Any>(),
            // ⚠️ 修复：字段名必须与后端一致（后端读 local_skeleton），
            //    同时保留 local_watcher 兼容旧逻辑。
            "local_skeleton" to true,
            "local_watcher" to true,
            "created_at" to now,
            "updated_at" to now
        )
        File(taskDir, "meta/task_meta.json").writeText(gson.toJson(meta))
        LogStore.i(TAG, "壳识别结果: ${verdict?.tag} (${verdict?.confidence})")

        // 4) 下发 PRE_CHECK 指令（供 WorkerService 执行）
        emitPreCheck(taskDir, tid, req)

        // 5) 归档请求
        archive(reqFile)

        LogStore.i(TAG, "已创建任务: $tid (pkg=${req.packageName})")
        return true
    }

    /** 下发 PRE_CHECK 指令（格式对齐后端 shizuku_dump_scheduler） */
    private fun emitPreCheck(taskDir: File, tid: String, req: TaskCreateRequest) {
        try {
            val actionsDir = File(taskDir, "work/actions")
            actionsDir.mkdirs()
            val payload = mapOf(
                "seq" to 1,
                "action" to "PRE_CHECK",
                "task_id" to tid,
                "created_at" to System.currentTimeMillis() / 1000,
                "params" to mapOf(
                    "abi" to "arm64-v8a",
                    "package" to req.packageName,
                    "no_dump" to true
                ),
                "expect" to listOf("ok", "detail")
            )
            File(actionsDir, "0001_PRE_CHECK.req.json")
                .writeText(gson.toJson(payload))
            LogStore.i(TAG, "已下发 PRE_CHECK: $tid")
        } catch (t: Throwable) {
            LogStore.e(TAG, "下发 PRE_CHECK 失败: ${t.message}")
        }
    }

    // ---------------- 工具函数 ----------------

    /** 是否已有同 APK 的活跃任务（幂等） */
    private fun hasActiveTaskFor(apkPath: String): Boolean {
        val apkName = File(apkPath).name
        val terminal = setOf("SUCCESS", "FAILED", "CANCELLED", "PRE_CHECK_FAILED")
        return try {
            val dirs = tasksRoot.listFiles() ?: return false
            for (d in dirs) {
                if (!d.isDirectory) continue
                val mf = File(d, "meta/task_meta.json")
                if (!mf.exists()) continue
                try {
                    @Suppress("UNCHECKED_CAST")
                    val m = gson.fromJson(mf.readText(), Map::class.java) as? Map<String, Any?>
                    val st = m?.get("state")?.toString() ?: ""
                    val src = m?.get("source_apk")?.toString() ?: ""
                    // 同 APK 且非终态 → 已有活跃任务
                    if (File(src).name == apkName && st !in terminal) {
                        return true
                    }
                } catch (t: Throwable) {
                    // 单个任务解析失败不影响整体
                }
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    private fun archive(reqFile: File) {
        try {
            doneRoot.mkdirs()
            reqFile.renameTo(File(doneRoot, reqFile.name))
        } catch (t: Throwable) {
            try { reqFile.delete() } catch (_: Throwable) {}
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(len: Int): String {
        val chars = "0123456789abcdef"
        return (1..len).map { chars.random() }.joinToString("")
    }

    /** 待处理请求数（供 UI 显示） */
    fun pendingCount(): Int {
        return try {
            val files = uploadsRoot.listFiles()
            if (files == null) return 0
            var n = 0
            for (f in files) {
                if (f.isFile && f.name.startsWith("create_") && f.name.endsWith(".req.json")) n++
            }
            n
        } catch (t: Throwable) { 0 }
    }
}