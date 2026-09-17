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
        return try {
            val metaFile = File(tasksRoot, "$taskId/meta/task_meta.json")
            if (!metaFile.exists()) return null
            gson.fromJson(metaFile.readText(), TaskMetaView::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "解析任务失败: $taskId", e)
            null
        }
    }

    // ---------------- 日志 ----------------

    fun readLog(taskId: String): String {
        return try {
            val logFile = File(logsRoot, "$taskId/task.log")
            if (logFile.exists()) logFile.readText() else "（无日志）"
        } catch (t: Throwable) {
            "（日志读取失败: ${t.message}）"
        }
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

            // 3) 写 create 请求
            val req = TaskCreateRequest(
                apkPath = dest.absolutePath,
                packageName = pkg ?: "",
                allowAutoDegrade = allowAutoDegrade,
                createdAt = System.currentTimeMillis()
            )
            val reqFile = File(uploadsRoot, "create_${UUID.randomUUID()}.req.json")
            reqFile.writeText(gson.toJson(req))

            // 4) 【核心提速】立即触发内置 AutoWatcher → 秒级创建正式任务
            val created = try {
                com.yuntuoxiu.app.worker.AutoWatcher.tick()
            } catch (t: Throwable) {
                Log.w(TAG, "AutoWatcher 触发失败: ${t.message}"); 0
            }

            // 5) 【B 方案】自动拉起 Termux 执行 dump/修复/打包（若有 Termux）
            try {
                if (com.yuntuoxiu.app.worker.TermuxBridge.isTermuxInstalled(context)) {
                    // 找到刚创建的任务 id（最新的 t_ 开头）
                    val tid = findLatestTaskId()
                    if (tid != null) {
                        com.yuntuoxiu.app.worker.TermuxBridge.runTaskPipeline(
                            context, tid, background = true)
                        Log.i(TAG, "已自动派发任务给 Termux: $tid")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "派发 Termux 失败: ${t.message}")
            }

            if (created == 0) {
                Log.i(TAG, "AutoWatcher 未创建新任务（可能已存在）")
            }

            Log.i(TAG, "已提交: ${dest.absolutePath} (pkg=$pkg, created=$created)")
            SubmitResult.Success(dest.absolutePath)
        } catch (e: Exception) {
            SubmitResult.Failure(e.message ?: "提交失败")
        }
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
     * 找最新创建的任务 ID（t_ 开头，按目录名时间戳排序）
     */
    fun findLatestTaskId(): String? {
        return try {
            val all = File(tasksRoot).listFiles() ?: return null
            var best: File? = null
            for (f in all) {
                if (!f.isDirectory) continue
                if (!f.name.startsWith("t_")) continue
                if (best == null || f.name > best.name) best = f
            }
            best?.name
        } catch (t: Throwable) {
            null
        }
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
