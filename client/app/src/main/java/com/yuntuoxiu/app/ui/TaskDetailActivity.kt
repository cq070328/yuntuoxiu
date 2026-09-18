package com.yuntuoxiu.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yuntuoxiu.app.LogStore
import com.yuntuoxiu.app.R
import com.yuntuoxiu.app.data.SubmitResult
import com.yuntuoxiu.app.data.TaskMetaView
import com.yuntuoxiu.app.data.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 任务详情：状态机 + 壳识别 + 修复轨迹 + 日志 + 取消。
 *
 * ⚠️ 稳定性：所有字段访问都做空安全 + try/catch，
 *    任何异常只记录日志，不让 Activity 崩溃。
 */
class TaskDetailActivity : AppCompatActivity() {

    private lateinit var tvState: TextView
    private lateinit var tvShell: TextView
    private lateinit var tvTrace: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnCancel: Button
    private lateinit var btnRefresh: Button

    // ⭐ v1.6.4 产物卡片
    private var cardOutput: android.view.View? = null
    private var tvOutputPath: TextView? = null
    private var btnInstall: android.view.View? = null
    private var currentOutput: String? = null

    private var taskId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_detail)

        taskId = try {
            intent.getStringExtra(EXTRA_TASK_ID) ?: ""
        } catch (t: Throwable) { "" }

        if (taskId.isBlank()) {
            Toast.makeText(this, "缺少任务 ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        LogStore.i(TAG, "详情页打开: $taskId")

        // 绑定视图（任一失败则安全退出）
        try {
            tvState = findViewById(R.id.tvState)
            tvShell = findViewById(R.id.tvShell)
            tvTrace = findViewById(R.id.tvTrace)
            tvLog = findViewById(R.id.tvLog)
            btnCancel = findViewById(R.id.btnCancel)
            btnRefresh = findViewById(R.id.btnRefresh)
            // ⭐ v1.6.4 产物卡片
            cardOutput = findViewById(R.id.cardOutput)
            tvOutputPath = findViewById(R.id.tvOutputPath)
            btnInstall = findViewById(R.id.btnInstall)
        } catch (t: Throwable) {
            LogStore.e(TAG, "视图绑定失败: ${t.message}")
            finish()
            return
        }

        // ⭐ 长按路径 → 复制
        tvOutputPath?.setOnLongClickListener {
            val p = currentOutput
            if (!p.isNullOrBlank()) {
                try {
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("apk_path", p))
                    Toast.makeText(this, "✅ 路径已复制", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    Toast.makeText(this, "复制失败: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }

        // ⭐ 圆形图标 → 本地安装
        btnInstall?.setOnClickListener {
            val p = currentOutput
            if (p.isNullOrBlank()) {
                Toast.makeText(this, "暂无产物", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            installApk(p)
        }

        btnRefresh.setOnClickListener {
            try { load() } catch (t: Throwable) {
                LogStore.e(TAG, "刷新失败: ${t.message}")
            }
        }

        btnCancel.setOnClickListener {
            // 本地骨架不支持取消（没有后端任务）
            if (taskId.startsWith("local_")) {
                Toast.makeText(this, "本地待处理任务，暂不支持取消", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                try {
                    val r = withContext(Dispatchers.IO) { TaskRepository.submitCancel(taskId) }
                    when (r) {
                        is SubmitResult.Success ->
                            Toast.makeText(this@TaskDetailActivity,
                                "取消请求已提交", Toast.LENGTH_SHORT).show()
                        is SubmitResult.Failure ->
                            Toast.makeText(this@TaskDetailActivity,
                                "取消失败: ${r.reason}", Toast.LENGTH_LONG).show()
                    }
                } catch (t: Throwable) {
                    LogStore.e(TAG, "取消异常: ${t.message}")
                    Toast.makeText(this@TaskDetailActivity,
                        "取消异常: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        load()
        startAutoRefresh()
    }

    /**
     * 自动刷新：任务非终态时每 2 秒刷新一次（进度可见）；
     * 到达终态则停止（省电）。生命周期绑定，onDestroy 自动取消。
     */
    private fun startAutoRefresh() {
        lifecycleScope.launch {
            var terminalHits = 0
            while (true) {
                delay(2000)
                try {
                    val task = withContext(Dispatchers.IO) {
                        try { TaskRepository.loadTask(taskId) } catch (t: Throwable) { null }
                    }
                    if (task != null && !task.isTerminal) {
                        terminalHits = 0
                        load()
                    } else {
                        // 连续两次终态后停止刷新（给一个缓冲，避免刚终态漏刷）
                        if (++terminalHits >= 2) break
                        load()
                    }
                } catch (t: Throwable) {
                    LogStore.e(TAG, "自动刷新异常: ${t.message}")
                }
            }
        }
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val task = withContext(Dispatchers.IO) {
                    try { TaskRepository.loadTask(taskId) } catch (t: Throwable) {
                        LogStore.e(TAG, "读取任务失败: ${t.message}"); null
                    }
                }
                val log = withContext(Dispatchers.IO) {
                    try { TaskRepository.readLog(taskId) } catch (t: Throwable) {
                        "（日志读取失败: ${t.message}）"
                    }
                }

                // 状态
                tvState.text = task?.let {
                    "状态: ${it.stateLabel} (${it.state})" +
                            (it.failCode?.let { fc -> "\nfail_code: $fc" } ?: "") +
                            (it.artifactStatus?.let { s -> "\n产物: $s" } ?: "")
                } ?: "任务不存在（可能已被删除）"

                // 壳信息
                tvShell.text = task?.let {
                    "壳识别: ${it.shellLabel} (${it.shellTag ?: "未识别"})\n" +
                            "包名: ${it.packageName ?: "-"}\n" +
                            "ABI: ${it.clientAbi ?: "-"} | 降级: ${if (it.allowAutoDegrade) "开" else "关"}"
                } ?: ""

                // 修复轨迹（安全解析）
                tvTrace.text = buildTrace(task)

                // ⭐ v1.6.4 产物卡片
                updateOutputCard(task)

                // ⭐ v1.6.6 日志：旧→新（最新在底部）
                tvLog.text = if (log.isBlank()) "（无日志）" else log.takeLast(8000)
            } catch (t: Throwable) {
                LogStore.e(TAG, "load 异常: ${t.message}")
                try { tvState.text = "加载失败: ${t.message}" } catch (_: Throwable) {}
            }
        }
    }

    /** 安全构造修复轨迹文本（所有 map 访问都做类型保护） */
    private fun buildTrace(t: TaskMetaView?): String {
        if (t == null) return "（任务不存在）"
        return try {
            val sb = StringBuilder()

            // ⭐ v2.0：可脱性评估（提交后即知能否脱，避免白等）
            if (!t.unpackLevel.isNullOrBlank()) {
                sb.append("【可脱性】${t.unpackLabel}\n")
                if (!t.unpackAdvice.isNullOrBlank()) {
                    sb.append("  ${t.unpackAdvice}\n")
                }
                // ⭐ v2.4：重壳（需 SO 逆向/VM 还原）给出「外部 dex 导入」操作指引
                if (t.unpackLevel == "NEED_SO" || t.unpackLevel == "NEED_VM") {
                    val pkg = t.packageName ?: "<包名>"
                    sb.append("\n  📥 建议操作：用专业脱壳工具（Layout Inspect / BlackDex）\n")
                    sb.append("     脱出完整 dex 后，放入：\n")
                    sb.append("     /sdcard/MT2/apks/external_dump/$pkg/\n")
                    sb.append("     再重新一键脱修：将自动跳过设备端 dump，\n")
                    sb.append("     直接进入【修复→删壳→换入口→打包→签名】。\n")
                }
                sb.append("\n")
            }

            // ⭐ v1.6.7：本地骨架任务明确提示
            val isSkeleton = t.localSkeleton || t.taskId.startsWith("local_")
            if (isSkeleton && t.handlerTrace.isEmpty()) {
                sb.append("ℹ️ 本地待处理任务（骨架）\n")
                sb.append("   尚未被后端接管，暂无修复轨迹。\n\n")
                sb.append("可能原因：\n")
                sb.append("· 后端未运行 → 在 Operit 终端执行 ytx.sh start\n")
                sb.append("· 后端已运行但还没轮到（等几秒刷新）\n\n")
            }

            t.handlerTrace.forEachIndexed { i, h ->
                sb.append("【修复尝试 #${i + 1}】\n")
                sb.append("  壳: ${safeGet(h, "shell_verdict")}\n")
                sb.append("  处理器链: ${safeGet(h, "selected_entry")}\n")
                sb.append("  结果: ${if (h["outcome_ok"] == true) "成功" else "失败"}\n")
                sb.append("  最终处理器: ${safeGet(h, "final_handler")}\n")
                val steps = h["trace"]
                if (steps is List<*>) {
                    steps.forEach { step ->
                        val m = step as? Map<*, *>
                        if (m != null) {
                            val degrade = m["will_degrade"]
                            val arrow = if (degrade == true) " → 降级到 ${safeGetRaw(m, "next_handler")}" else ""
                            sb.append("    - ${safeGetRaw(m, "handler")} " +
                                    "ok=${safeGetRaw(m, "ok")} " +
                                    "code=${safeGetRaw(m, "fail_code")}$arrow\n")
                        }
                    }
                }
            }
            t.degradeTrace.forEachIndexed { i, d ->
                sb.append("【降级 #${i + 1}】${safeGet(d, "from")} → ${safeGet(d, "fallback")} " +
                        "原因: ${safeGet(d, "cause")} ok=${safeGet(d, "ok")}\n")
            }
            val out = sb.toString()
            // ⭐ v1.8.9：修复轨迹为空（任务尚未到 REPAIRING）时，回退展示
            //   「处理记录」——从 task.log 提炼的状态流转+指令下发，
            //   保证详情页任意阶段都有内容可看。
            if (out.isEmpty() || out == "（暂无修复轨迹）") {
                val prog = try {
                    com.yuntuoxiu.app.data.TaskRepository.buildProgressFromLog(t.taskId)
                } catch (_: Throwable) { "（暂无处理记录）" }
                if (prog.isNotBlank()) {
                    return "== 修复轨迹 ==\n（尚未进入修复阶段）\n\n" +
                            "== 处理记录 ==\n" + prog
                }
            }
            if (out.isEmpty()) "（暂无修复轨迹）" else out
        } catch (err: Throwable) {
            LogStore.e(TAG, "buildTrace 异常: ${err.message}")
            "（轨迹解析失败: ${err.message}）"
        }
    }

    private fun safeGet(m: Map<String, Any?>, k: String): String =
        m[k]?.toString() ?: "-"

    private fun safeGetRaw(m: Map<*, *>, k: String): String =
        m[k]?.toString() ?: "-"

    // ---------------- v1.6.4 产物相关 ----------------

    /** 查找任务产物 APK（多候选路径） */
    private fun findOutput(task: TaskMetaView?): String? {
        val ws = "/sdcard/MT2/apks"
        val tid = task?.taskId ?: taskId

        // 候选（按优先级）
        val cands = mutableListOf<String>()
        cands += "$ws/云脱修-$tid.apk"
        cands += "$ws/云脱修-$tid-oc.apk"
        cands += "$ws/unpackcloud/tasks/$tid/build/云脱修-$tid.apk"
        cands += "$ws/unpackcloud/tasks/$tid/build/signed.apk"
        cands += "$ws/unpackcloud/tasks/$tid/build/out.apk"

        for (p in cands) {
            try {
                val f = java.io.File(p)
                if (f.exists() && f.length() > 1024) return p
            } catch (_: Throwable) {}
        }
        return null
    }

    /** 更新产物卡片（有产物则显示，否则隐藏） */
    private fun updateOutputCard(task: TaskMetaView?) {
        try {
            val p = findOutput(task)
            currentOutput = p
            if (p.isNullOrBlank()) {
                cardOutput?.visibility = android.view.View.GONE
            } else {
                cardOutput?.visibility = android.view.View.VISIBLE
                val sz = try { java.io.File(p).length() / 1024 } catch (_: Throwable) { 0L }
                tvOutputPath?.text = "$p\n（$sz KB）"
            }
        } catch (t: Throwable) {
            LogStore.w(TAG, "更新产物卡片失败: ${t.message}")
        }
    }

    /** 本地安装 APK（⭐ v1.8.8：只用 Shizuku 静默安装，不再回退系统安装器）。
     *
     *  旧实现失败时回退「系统安装器」→ 弹出系统 UI 且提示「删除旧版」，
     *  与 APP 内一键脱修的静默安装体验不一致。
     *  现改为：Shizuku 复制到 /data/local/tmp → 先卸载旧版 → pm install，
     *  全静默、无系统弹窗；安装结果用 Toast 反馈。
     */
    private fun installApk(path: String) {
        lifecycleScope.launch {
            Toast.makeText(this@TaskDetailActivity, "安装中…", Toast.LENGTH_SHORT).show()
            val detail = withContext(Dispatchers.IO) {
                try {
                    val f = java.io.File(path)
                    if (!f.exists()) return@withContext "产物不存在: $path"
                    // 从任务 meta 取包名（用于卸载旧版）
                    val pkg = try {
                        com.yuntuoxiu.app.data.TaskRepository.loadTask(taskId)?.packageName
                    } catch (t: Throwable) { null }
                    val staged = "/data/local/tmp/ytx_ui_${System.currentTimeMillis()}.apk"
                    val uninstall = if (!pkg.isNullOrBlank())
                        "pm uninstall \"$pkg\" >/dev/null 2>&1; " else ""
                    val cmd = "cp -f '${f.absolutePath}' '$staged' && " +
                            uninstall +
                            "pm install -r -t -d '$staged' 2>&1 | tail -2; " +
                            "rm -f '$staged'"
                    val r = com.yuntuoxiu.app.shizuku.ShizukuShellExecutor.exec(cmd)
                    (r.getString("stdout") ?: "") + (r.getString("stderr") ?: "")
                } catch (t: Throwable) {
                    "安装异常: ${t.message}"
                }
            }
            val ok = detail.contains("Success", ignoreCase = true)
            Toast.makeText(this@TaskDetailActivity,
                if (ok) "✅ 已通过 Shizuku 静默安装"
                else "❌ 安装失败：${detail.trim().take(160)}",
                if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            LogStore.i(TAG, "installApk ok=$ok detail=${detail.trim().take(200)}")
            if (ok) load()
        }
    }

    companion object {
        private const val TAG = "TaskDetailActivity"
        const val EXTRA_TASK_ID = "task_id"
    }
}