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
        } catch (t: Throwable) {
            LogStore.e(TAG, "视图绑定失败: ${t.message}")
            finish()
            return
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

                // 日志
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

    companion object {
        private const val TAG = "TaskDetailActivity"
        const val EXTRA_TASK_ID = "task_id"
    }
}