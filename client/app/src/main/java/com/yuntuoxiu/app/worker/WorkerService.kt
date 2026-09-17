package com.yuntuoxiu.app.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.yuntuoxiu.app.YunTuoXiuApp
import com.yuntuoxiu.app.data.TaskMetaView
import com.yuntuoxiu.app.data.TaskRepository
import kotlinx.coroutines.*

/**
 * WorkerService：后台轮询所有活跃任务的 action 队列并执行。
 *
 * - 自动发现新任务（扫描 tasks/ 下非终态任务）
 * - 对每个任务轮询 work/actions/ 下未回执的 .req.json
 * - 执行后回写 .resp.json（幂等：已回执跳过）
 */
class WorkerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        startPolling()
        return START_STICKY
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    // 扫描活跃任务（非终态）
                    val activeTasks = TaskRepository.listTasks()
                        .filter { !it.isTerminal }
                    for (task in activeTasks) {
                        processTask(task)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "轮询异常", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun processTask(task: TaskMetaView) {
        val queue = ActionQueue(task.taskId)
        val executor = ActionExecutor(this, task.taskId)
        val pending = queue.pendingPayloads()
        for ((payload, respFile) in pending) {
            val resp = executor.execute(payload)
            queue.writeResponse(respFile, resp)
            Log.i(TAG, "[${task.taskId}] 执行 ${payload.action} -> ok=${resp.ok} detail=${resp.detail}")
        }
    }

    private fun buildNotification(): Notification {
        val ch = NotificationChannel(CHANNEL_ID, "云脱修 Worker", NotificationManager.IMPORTANCE_LOW)
        // getSystemService 返回可空，必须安全调用
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("云脱修")
            .setContentText("后台脱壳任务运行中")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WorkerService"
        const val POLL_INTERVAL_MS = 2000L
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "yuntuoxiu_worker"
    }
}
