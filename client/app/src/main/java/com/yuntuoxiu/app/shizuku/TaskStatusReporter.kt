package com.yuntuoxiu.app.shizuku

import android.util.Log
import com.google.gson.Gson
import com.yuntuoxiu.app.YunTuoXiuApp
import com.yuntuoxiu.app.data.TaskRepository
import java.io.File

/**
 * 任务状态上报器：把客户端侧的环境异常（Shizuku 断连 / 权限回收 /
 * Shell 致命错误）主动写入文件任务队列，供后端 watcher 感知并置任务失败。
 *
 * 落盘位置：tasks/<task_id>/work/client_status.json
 * 结构（与后端 watcher 约定）：
 *   {
 *     "event": "SHIZUKU_DISCONNECTED" | "PERMISSION_REVOKED" | "SHELL_FATAL",
 *     "fail_code": "<后端 fail_code>",
 *     "detail": "...",
 *     "ts": <ms>
 *   }
 *
 * 后端 watcher 轮询到该文件后，会调用 master_scheduler 将对应任务置为
 * FAILED（fail_code 直接采用上报值），实现「断连即失败」的闭环。
 */
object TaskStatusReporter {

    private const val TAG = "TaskStatusReporter"
    private val gson = Gson()

    const val EVENT_DISCONNECTED = "SHIZUKU_DISCONNECTED"
    const val EVENT_PERMISSION_REVOKED = "PERMISSION_REVOKED"
    const val EVENT_SHELL_FATAL = "SHELL_FATAL"

    /**
     * 对所有活跃（非终态）任务上报状态事件。
     *
     * @param event      事件类型
     * @param failCode   对应后端 fail_code（见 ShizukuErrorCodes.toBackendFailCode）
     * @param detail     附加说明
     */
    fun reportToActiveTasks(event: String, failCode: String, detail: String) {
        val active = try {
            TaskRepository.listTasks().filter { !it.isTerminal }
        } catch (e: Exception) {
            Log.w(TAG, "读取活跃任务失败", e)
            java.util.Collections.emptyList<com.yuntuoxiu.app.data.TaskMetaView>()
        }
        if (active.isEmpty()) {
            Log.i(TAG, "无活跃任务，跳过上报: $event")
            return
        }
        active.forEach { task ->
            try {
                writeStatus(task.taskId, event, failCode, detail)
            } catch (e: Exception) {
                Log.w(TAG, "上报失败 ${task.taskId}", e)
            }
        }
        Log.i(TAG, "已上报 $event 到 ${active.size} 个活跃任务")
    }

    /** 对单个任务上报 */
    fun reportToTask(taskId: String, event: String, failCode: String, detail: String) {
        try {
            writeStatus(taskId, event, failCode, detail)
        } catch (e: Exception) {
            Log.w(TAG, "上报失败 $taskId", e)
        }
    }

    private fun writeStatus(taskId: String, event: String, failCode: String, detail: String) {
        val dir = File(YunTuoXiuApp.CLOUD_ROOT, "tasks/$taskId/work")
        if (!dir.exists()) dir.mkdirs()
        val payload = mapOf(
            "event" to event,
            "fail_code" to failCode,
            "detail" to detail,
            "ts" to System.currentTimeMillis(),
            "source" to "yuntuoxiu_client"
        )
        val tmp = File(dir, "client_status.json.tmp")
        tmp.writeText(gson.toJson(payload))
        tmp.renameTo(File(dir, "client_status.json"))
        Log.i(TAG, "[$taskId] 状态上报: $event / $failCode")
    }
}
