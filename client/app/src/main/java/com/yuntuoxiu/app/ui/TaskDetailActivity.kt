package com.yuntuoxiu.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yuntuoxiu.app.R
import com.yuntuoxiu.app.data.SubmitResult
import com.yuntuoxiu.app.data.TaskMetaView
import com.yuntuoxiu.app.data.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 任务详情：状态机信息 + 壳识别 + 修复轨迹 + 日志 + 取消。
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

        taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: run {
            finish()
            return
        }

        tvState = findViewById(R.id.tvState)
        tvShell = findViewById(R.id.tvShell)
        tvTrace = findViewById(R.id.tvTrace)
        tvLog = findViewById(R.id.tvLog)
        btnCancel = findViewById(R.id.btnCancel)
        btnRefresh = findViewById(R.id.btnRefresh)

        btnRefresh.setOnClickListener { load() }

        btnCancel.setOnClickListener {
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { TaskRepository.submitCancel(taskId) }
                when (r) {
                    is SubmitResult.Success ->
                        Toast.makeText(this@TaskDetailActivity, "取消请求已提交", Toast.LENGTH_SHORT).show()
                    is SubmitResult.Failure ->
                        Toast.makeText(this@TaskDetailActivity, "取消失败: ${r.reason}", Toast.LENGTH_LONG).show()
                }
            }
        }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val task = withContext(Dispatchers.IO) { TaskRepository.loadTask(taskId) }
            val log = withContext(Dispatchers.IO) { TaskRepository.readLog(taskId) }

            tvState.text = task?.let {
                "状态: ${it.stateLabel} (${it.state})" +
                        (it.failCode?.let { fc -> "\nfail_code: $fc" } ?: "") +
                        (it.artifactStatus?.let { s -> "\n产物: $s" } ?: "")
            } ?: "任务不存在"

            tvShell.text = task?.let {
                "壳识别: ${it.shellLabel} (${it.shellTag ?: "未识别"})\n" +
                        "ABI: ${it.clientAbi ?: "-"} | 降级: ${if (it.allowAutoDegrade) "开" else "关"}"
            } ?: ""

            tvTrace.text = task?.let { buildTrace(it) } ?: ""

            tvLog.text = log.takeLast(8000) // 只显示尾部日志
            tvLog.post { tvLog.scrollTo(0, 0) }
        }
    }

    private fun buildTrace(t: TaskMetaView): String {
        val sb = StringBuilder()
        t.handlerTrace.forEachIndexed { i, h ->
            sb.append("【修复尝试 #${i + 1}】\n")
            sb.append("  壳: ${h["shell_verdict"]}\n")
            sb.append("  选中处理器链: ${h["selected_entry"]}\n")
            sb.append("  结果: ${if (h["outcome_ok"] == true) "成功" else "失败"}\n")
            sb.append("  最终处理器: ${h["final_handler"]}\n")
            (h["trace"] as? List<*>)?.forEach { step ->
                @Suppress("UNCHECKED_CAST")
                val m = step as? Map<String, Any?>
                if (m != null) {
                    sb.append("    - ${m["handler"]} ok=${m["ok"]} code=${m["fail_code"]} " +
                            "${m["will_degrade"]?.let { if (it == true) "→降级到 ${m["next_handler"]}" else "" } ?: ""}\n")
                }
            }
        }
        t.degradeTrace.forEachIndexed { i, d ->
            sb.append("【降级 #${i + 1}】${d["from"]}->${d["fallback"]} 原因:${d["cause"]} ok=${d["ok"]}\n")
        }
        return sb.ifEmpty { "（暂无修复轨迹）" }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }
}