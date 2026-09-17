package com.yuntuoxiu.app.data

import android.util.Log
import com.google.gson.Gson
import com.yuntuoxiu.app.YunTuoXiuApp
import java.io.File
import java.util.UUID

/**
 * 任务仓库：与后端工作区直接交互（文件协议）。
 *
 * 职责：
 *  - 扫描 tasks/ 下的所有任务
 *  - 读取任务元数据 / 日志
 *  - 提交新任务（复制 APK -> uploads/ -> 写 create 请求，由后端 watcher 消费）
 *  - 提交取消请求（写 cancel 请求，由后端 watcher 处理）
 */
object TaskRepository {

    private const val TAG = "TaskRepository"
    private val gson = Gson()

    private val tasksRoot: File get() = File(YunTuoXiuApp.CLOUD_ROOT, "tasks")
    private val logsRoot: File get() = File(YunTuoXiuApp.CLOUD_ROOT, "logs")
    private val uploadsRoot: File get() = File(YunTuoXiuApp.UPLOADS_ROOT)

    // ---------------- 任务列表 ----------------

    /** 扫描全部任务（按更新时间倒序） */
    fun listTasks(): List<TaskMetaView> {
        if (!tasksRoot.exists()) return emptyList()
        val out = mutableListOf<TaskMetaView>()
        tasksRoot.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val metaFile = File(dir, "meta/task_meta.json")
            if (!metaFile.exists()) return@forEach
            try {
                // Gson.fromJson 可能返回 null（JSON 非法/类型不符），必须判空
                val parsed = gson.fromJson(metaFile.readText(), TaskMetaView::class.java)
                if (parsed != null) out.add(parsed)
            } catch (e: Exception) {
                Log.w(TAG, "解析任务元数据失败: ${dir.name}", e)
            }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun loadTask(taskId: String): TaskMetaView? {
        val metaFile = File(tasksRoot, "$taskId/meta/task_meta.json")
        if (!metaFile.exists()) return null
        return try {
            gson.fromJson(metaFile.readText(), TaskMetaView::class.java)
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 日志 ----------------

    fun readLog(taskId: String): String {
        val logFile = File(logsRoot, "$taskId/task.log")
        return if (logFile.exists()) logFile.readText() else "（无日志）"
    }

    // ---------------- 提交新任务 ----------------

    /**
     * 提交新任务：
     * 1) 把用户选中的 APK 复制到 uploads/（原始文件不动）
     * 2) 【提速】本地立即创建任务骨架 → 任务列表秒级显示
     * 3) 同时写 create 请求，后端稍后接管（补全包名等）
     */
    fun submitTask(context: android.content.Context, sourceApk: File,
                   allowAutoDegrade: Boolean): SubmitResult {
        return try {
            if (!sourceApk.exists()) return SubmitResult.Failure("APK 文件不存在")
            uploadsRoot.mkdirs()

            // 1) 复制（原始文件只读约束）
            val dest = File(uploadsRoot, sourceApk.name)
            sourceApk.copyTo(dest, overwrite = true)

            // 2) 解析包名（PackageManager，最可靠）
            val pkg = try {
                context.getPackageManager()
                    .getPackageArchiveInfo(dest.absolutePath, 0)?.packageName
            } catch (e: Exception) {
                Log.w(TAG, "解析包名失败: ${e.message}"); null
            }
            val verName = try {
                context.getPackageManager()
                    .getPackageArchiveInfo(dest.absolutePath, 0)?.versionName
            } catch (e: Exception) { null }

            // 3) 【提速】本地创建任务骨架（立即出现在列表）
            val localTid = createLocalTaskSkeleton(dest, pkg, verName)

            // 4) 写 create 请求（后端接管，会用 idem_key 幂等对齐）
            val req = TaskCreateRequest(
                apkPath = dest.absolutePath,
                packageName = pkg ?: "",
                allowAutoDegrade = allowAutoDegrade,
                createdAt = System.currentTimeMillis()
            )
            val reqFile = File(uploadsRoot, "create_${UUID.randomUUID()}.req.json")
            reqFile.writeText(gson.toJson(req))

            Log.i(TAG, "已提交: ${dest.absolutePath} (pkg=$pkg, local_tid=$localTid)")
            SubmitResult.Success(dest.absolutePath)
        } catch (e: Exception) {
            SubmitResult.Failure(e.message ?: "提交失败")
        }
    }

    /**
     * 本地创建任务骨架，让任务列表立即显示。
     *
     * 结构对齐后端：tasks/<tid>/meta/task_meta.json
     * 状态用 PENDING_LOCAL（前端展示为"等待处理"），
     * 后端 watcher 创建正式任务后，前端会读到后端的任务。
     *
     * 为避免和后端任务重复，用 APK 路径+时间戳做本地 tid，
     * 且骨架里标记 "local_skeleton": true，前端可去重。
     */
    private fun createLocalTaskSkeleton(apk: File, pkg: String?, ver: String?): String {
        val tid = "local_${System.currentTimeMillis()}"
        val dir = File(tasksRoot, "$tid/meta")
        dir.mkdirs()

        val meta = mapOf(
            "task_id" to tid,
            "state" to "PENDING_LOCAL",          // 本地待处理
            "idem_key" to "",
            "source_apk" to apk.absolutePath,
            "source_apk_sha256" to "",
            "package_name" to (pkg ?: ""),
            "version_name" to (ver ?: ""),
            "allow_auto_degrade" to true,
            "local_skeleton" to true,             // 标记：本地骨架
            "created_at" to System.currentTimeMillis() / 1000,
            "updated_at" to System.currentTimeMillis() / 1000
        )
        File(dir, "task_meta.json").writeText(gson.toJson(meta))
        Log.i(TAG, "本地任务骨架已创建: $tid")
        return tid
    }

    // ---------------- 取消任务 ----------------

    /**
     * 写取消请求（后端 watcher 校验后处理）。
     * APP 侧没有 token（token 由后端生成、经后端 API 返回），
     * 因此取消走「请求文件 + APP 端设备指纹」契约，由 watcher 决定是否受理。
     */
    fun submitCancel(taskId: String): SubmitResult {
        return try {
            val cancelDir = File(uploadsRoot, "cancels")
            cancelDir.mkdirs()
            val req = mapOf(
                "task_id" to taskId,
                "created_at" to System.currentTimeMillis(),
                "source" to "yuntuoxiu_app"
            )
            File(cancelDir, "cancel_${taskId}_${System.currentTimeMillis()}.req.json")
                .writeText(gson.toJson(req))
            SubmitResult.Success(taskId)
        } catch (e: Exception) {
            SubmitResult.Failure(e.message ?: "取消失败")
        }
    }

    // ---------------- 任务产物 ----------------

    fun listArtifacts(taskId: String): List<File> {
        val repaired = File(tasksRoot, "$taskId/repaired")
        val build = File(tasksRoot, "$taskId/build")
        val out = mutableListOf<File>()
        for (dir in listOf(repaired, build)) {
            if (dir.exists()) {
                dir.listFiles()?.forEach { out.add(it) }
            }
        }
        return out
    }

    /**
     * 删除任务（递归删 tasks/<tid> 与 logs/<tid>）
     * 注意：仅删除该任务自己的目录，不影响其他任务。
     */
    fun deleteTask(taskId: String): SubmitResult {
        return try {
            // 防路径穿越
            if (taskId.contains("/") || taskId.contains("..")) {
                return SubmitResult.Failure("非法 task_id")
            }
            var deleted = 0
            val td = File(tasksRoot, taskId)
            if (td.exists()) {
                if (td.deleteRecursively()) deleted++ else
                    return SubmitResult.Failure("删除任务目录失败")
            }
            val ld = File(logsRoot, taskId)
            if (ld.exists()) {
                ld.deleteRecursively()
            }
            // 从幂等索引里移除（若有）
            try {
                val idem = File(YunTuoXiuApp.CLOUD_ROOT, "idem_index.json")
                if (idem.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val m = gson.fromJson(idem.readText(), Map::class.java) as? MutableMap<String, Any?>
                    if (m != null) {
                        val toRemove = m.entries.filter {
                            val v = it.value as? Map<*, *>
                            v?.get("task_id") == taskId
                        }.map { it.key }
                        toRemove.forEach { m.remove(it) }
                        idem.writeText(gson.toJson(m))
                    }
                }
            } catch (_: Throwable) {}
            Log.i(TAG, "已删除任务 $taskId (删了 $deleted 个目录)")
            SubmitResult.Success(taskId)
        } catch (t: Throwable) {
            SubmitResult.Failure("删除异常: ${t.message}")
        }
    }
}

/**
 * 提交结果（替代 kotlin.Result —— 它的返回类型在 Kotlin 中受限，
 * 且 runCatching 在部分版本会引发类型推断问题）。
 */
sealed class SubmitResult {
    data class Success(val path: String) : SubmitResult()
    data class Failure(val reason: String) : SubmitResult()
}
