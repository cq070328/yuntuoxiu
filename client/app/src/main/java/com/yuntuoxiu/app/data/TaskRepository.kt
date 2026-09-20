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
        // 显式声明 Array<File> 类型，避免 listFiles() 重载歧义
        val dirs: Array<File>? = tasksRoot.listFiles()
        if (dirs == null) return out
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val metaFile = File(dir, "meta/task_meta.json")
            if (!metaFile.exists()) continue
            try {
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

    /**
     * ⭐ v1.8.9 新增：从 task.log 提炼「处理记录」（人类可读）。
     * ⭐ v2.0：去重（相邻重复状态）+ 精简输出。
     */
    fun buildProgressFromLog(taskId: String): String {
        return try {
            val logFile = File(logsRoot, "$taskId/task.log")
            if (!logFile.exists()) return "（暂无处理记录）"
            val sb = StringBuilder()
            var lastLine = ""
            var lastState = ""
            var repeat = 0
            logFile.forEachLine { line ->
                val l = line.trim()
                if (l.isEmpty() || !l.startsWith("{")) return@forEachLine
                try {
                    val o = com.google.gson.JsonParser.parseString(l).asJsonObject
                    val event = o.get("event")?.asString ?: return@forEachLine
                    val ts = o.get("ts")?.asString?.substringAfter("T")?.take(8) ?: ""
                    val text = when (event) {
                        "task_created" -> "创建任务"
                        "package_resolved" -> "解析包名: ${o.get("package")?.asString ?: "-"}"
                        "state_transition" -> {
                            val dst = o.get("dst")?.asString ?: ""
                            // 去重：相同目标状态连续出现时跳过
                            if (dst == lastState) return@forEachLine
                            lastState = dst
                            "状态 → $dst"
                        }
                        "action_emitted" -> "下发指令: ${o.get("action")?.asString}"
                        "readonly_copy_made" -> "生成只读副本"
                        "lspatch_ok" -> "注入成功"
                        "lspatch_start" -> "容器注入开始…"
                        "dex_merged_ok" -> "DEX 合并通过"
                        "task_success" -> "✅ 任务成功"
                        "task_failed" -> "❌ 失败: ${o.get("fail_code")?.asString ?: ""}"
                        else -> return@forEachLine
                    }
                    // 去重：整行相同（连续）跳过
                    val full = "• [$ts] $text"
                    if (full == lastLine) { repeat++; return@forEachLine }
                    lastLine = full
                    repeat = 0
                    sb.append(full).append('\n')
                } catch (_: Throwable) {
                    // 单行解析失败不影响整体
                }
            }
            if (sb.isEmpty()) "（暂无处理记录）" else sb.toString().trimEnd()
        } catch (t: Throwable) {
            "（处理记录解析失败: ${t.message}）"
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
            // ⚠️ v1.7.0 修复：FUSE 下 File.exists() 不可靠（可能返回 true 但实际读不了）
            //    → 改为「实际探测可读性」
            if (!sourceApk.exists()) return SubmitResult.Failure("APK 文件不存在: ${sourceApk.absolutePath}")
            if (!sourceApk.canRead()) return SubmitResult.Failure("APK 不可读: ${sourceApk.absolutePath}")
            // 探测真实可读（open 一次）
            try {
                sourceApk.inputStream().use { it.read() }
            } catch (e: Throwable) {
                return SubmitResult.Failure(
                    "APK 无法打开（${e.message}）\n路径: ${sourceApk.absolutePath}\n" +
                    "提示：若为已安装应用，请确认 Shizuku 已授权")
            }

            uploadsRoot.mkdirs()

            // 1) 复制到 uploads（若源已在 uploads 目录内，跳过）
            //
            // ⚠️ v1.7.1 关键修复：
            //   · APP **无权限写 uploads**（目录 750，属 u0_a0:media_rw）
            //   · 但「已安装应用」流程里，调用方已用 **Shizuku** 把 APK
            //     复制到 uploads/installed_<pkg>.apk 了
            //   · 此时 sourceApk 已在 uploads 内 → **不需要再复制**
            //     （否则 APP 的 copyTo 会因权限失败：ENOENT/EACCES）
            val upPath = uploadsRoot.absolutePath
            val srcPath = try { sourceApk.canonicalPath } catch (_: Throwable) { sourceApk.absolutePath }
            val dest = File(uploadsRoot, sourceApk.name)

            if (srcPath.startsWith(upPath)) {
                // 已在 uploads → 直接用（无需复制）
                Log.i(TAG, "源已在 uploads，跳过复制: $srcPath")
            } else {
                // 不在 → 用 Shizuku 复制（APP 无权限）
                val cpCmd = "cp -f '$srcPath' '${dest.absolutePath}' 2>&1; " +
                            "echo \"SZ=\$(stat -c %s '${dest.absolutePath}' 2>/dev/null || echo 0)\""
                val cpRes = com.yuntuoxiu.app.shizuku.ShizukuShellExecutor.exec(cpCmd)
                val cpOut = (cpRes.getString("stdout") ?: "").trim()
                val cpSize = Regex("SZ=(\\d+)").find(cpOut)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (cpSize < 1024) {
                    return SubmitResult.Failure(
                        "复制到 uploads 失败（size=$cpSize）\n" +
                        "源: $srcPath\n目标: ${dest.absolutePath}\n$cpOut")
                }
            }

            // 后续用 dest（统一）
            val finalApk = if (srcPath.startsWith(upPath)) sourceApk else dest

            // 2) 解析包名（PackageManager，最可靠）
            val pkg = try {
                context.getPackageManager()
                    .getPackageArchiveInfo(finalApk.absolutePath, 0)?.packageName
            } catch (e: Exception) {
                Log.w(TAG, "解析包名失败: ${e.message}"); null
            }

            // 3) 写 create 请求（保留，兼容旧流程）
            val req = TaskCreateRequest(
                apkPath = finalApk.absolutePath,
                packageName = pkg ?: "",
                allowAutoDegrade = allowAutoDegrade,
                createdAt = System.currentTimeMillis()
            )
            val reqFile = File(uploadsRoot, "create_${UUID.randomUUID()}.req.json")
            reqFile.writeText(gson.toJson(req))

            // 4) ⭐ v2.0：全本地化 —— 直接创建任务（不再依赖容器后端）
            val taskId = "t_" + System.currentTimeMillis().toString(36) +
                    "_" + UUID.randomUUID().toString().substring(0, 6)
            val now = System.currentTimeMillis()
            val taskDir = File(tasksRoot, taskId).apply { mkdirs() }
            File(taskDir, "meta").mkdirs()
            File(taskDir, "dump").mkdirs()
            File(taskDir, "build").mkdirs()
            File(logsRoot, taskId).mkdirs()

            val meta = TaskMetaView(
                taskId = taskId,
                state = "CREATED",
                sourceApk = finalApk.absolutePath,
                packageName = pkg,
                allowAutoDegrade = allowAutoDegrade,
                clientAbi = "arm64-v8a",
                createdAt = now,
                updatedAt = now
            )
            File(taskDir, "meta/task_meta.json").writeText(gson.toJson(meta))
            File(logsRoot, "$taskId/task.log").writeText("")

            Log.i(TAG, "已本地创建任务: $taskId (${finalApk.absolutePath}, pkg=$pkg)")
            SubmitResult.Success(taskId)
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
            // 显式声明 Array<File> 类型，避免 listFiles() 重载歧义
            val dirs: Array<File>? = tasksRoot.listFiles()
            if (dirs == null) return null
            // ⚠️ 修复：不再用「字符串比较」判最新（毫秒时间戳等长时可行但脆弱，
            //    且 local_ 前缀会打乱排序）。改为解析时间戳数值 + 目录 mtime 兜底。
            var best: File? = null
            var bestTs = -1L
            for (f in dirs) {
                if (!f.isDirectory) continue
                if (!f.name.startsWith("t_")) continue
                // 目录名形如 t_<millis>_<hex>；解析 millis 数值比较
                val ts = f.name.removePrefix("t_")
                    .substringBefore('_')
                    .toLongOrNull()
                    ?: f.lastModified()
                if (ts > bestTs) {
                    bestTs = ts
                    best = f
                }
            }
            best?.name
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 删除任务（v1.6.6：连带删除「产物 APK」）
     *
     * 删除范围：
     *   ① tasks/<tid>/                     任务全部文件
     *   ② logs/<tid>/                      任务日志
     *   ③ /apks/云脱修-<tid>.apk           构建产物
     *   ④ /apks/云脱修-<tid>-oc.apk        一键脱修产物
     *   ⑤ tasks/<tid>/build 下的 apk         中间产物
     *   ⑥ uploads/installed_<pkg>.apk      上传副本（按包名，若不再被其他任务引用）
     *
     * ⚠️ 不存在则跳过（不报错）
     */
    fun deleteTask(taskId: String): SubmitResult {
        return try {
            // 防路径穿越
            if (taskId.contains("/") || taskId.contains("..")) {
                return SubmitResult.Failure("非法 task_id")
            }

            // ⭐ 先读包名（用于清理 uploads 副本）
            val pkg = try {
                val mf = File(tasksRoot, "$taskId/meta/task_meta.json")
                if (mf.exists())
                    gson.fromJson(mf.readText(), TaskMetaView::class.java)?.packageName
                else null
            } catch (_: Throwable) { null }

            var deleted = 0
            val extras = mutableListOf<String>()

            // ① 任务目录
            val td = File(tasksRoot, taskId)
            if (td.exists()) {
                if (td.deleteRecursively()) deleted++ else
                    return SubmitResult.Failure("删除任务目录失败")
            }
            // ② 日志
            val ld = File(logsRoot, taskId)
            if (ld.exists()) ld.deleteRecursively()

            // ③④ 产物 APK（工作区根 + 任务 build 目录）
            val wsRoot = YunTuoXiuApp.WORKSPACE_ROOT
            val outCandidates = listOf(
                "$wsRoot/云脱修-$taskId.apk",
                "$wsRoot/云脱修-$taskId-oc.apk",
                "$wsRoot/$taskId-out.apk",
                "$wsRoot/云脱修-$taskId.apk.idsig",
                "$wsRoot/云脱修-$taskId-oc.apk.idsig"
            )
            for (p in outCandidates) {
                try {
                    val f = File(p)
                    if (f.exists() && f.delete()) extras.add(f.name)
                } catch (_: Throwable) {}
            }

            // ⑤ 任务 build 目录下的 apk（若 tasks 目录删失败时兜底）
            try {
                val b = File(tasksRoot, "$taskId/build")
                if (b.exists()) b.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".apk") && f.delete()) extras.add(f.name)
                }
            } catch (_: Throwable) {}

            // ⑥ uploads 副本（按包名；且确认无其他任务引用同包名）
            if (!pkg.isNullOrBlank()) {
                try {
                    val stillUsed = listTasks().any {
                        it.packageName == pkg && it.taskId != taskId
                    }
                    if (!stillUsed) {
                        val up = File(uploadsRoot, "installed_${pkg}.apk")
                        if (up.exists() && up.delete()) extras.add(up.name)
                        // 通配 picked_*.apk（无法精确对应，保守不删）
                    }
                } catch (_: Throwable) {}
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

            Log.i(TAG, "已删除任务 $taskId (目录 $deleted, 附加文件 ${extras.size}: $extras)")
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
