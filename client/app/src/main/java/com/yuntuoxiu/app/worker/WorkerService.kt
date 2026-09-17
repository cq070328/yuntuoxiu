package com.yuntuoxiu.app.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.yuntuoxiu.app.LogStore
import com.yuntuoxiu.app.data.TaskMetaView
import com.yuntuoxiu.app.data.TaskRepository
import kotlinx.coroutines.*

/**
 * WorkerService：后台轮询所有活跃任务的 action 队列并执行。
 *
 * ⚠️ 稳定性加固（防闪退）：
 *  1) 全流程 try/catch，任何异常都记录日志而不是崩溃
 *  2) Android 10+ 显式指定 foregroundServiceType（dataSync）
 *  3) 通知构建失败时用最简通知兜底
 *  4) 所有关键节点写入 LogStore（App 内可查看）
 */
class WorkerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            LogStore.i(TAG, "WorkerService.onCreate")
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate log failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先确保前台通知（失败也不能崩）
        try {
            startForegroundCompat()
            LogStore.i(TAG, "前台服务已启动")
        } catch (t: Throwable) {
            LogStore.e(TAG, "startForeground 失败（将继续后台运行）: ${t.message}")
        }

        // 启动轮询（带异常保护）
        try {
            startPolling()
            LogStore.i(TAG, "轮询已启动")
        } catch (t: Throwable) {
            LogStore.e(TAG, "启动轮询失败: ${t.message}")
        }

        return START_STICKY
    }

    /** 兼容各 Android 版本的前台服务启动 */
    private fun startForegroundCompat() {
        val notif = buildNotificationSafe()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 需显式类型；Manifest 已声明 dataSync
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** 构建通知；任何失败都回退到最简通知 */
    private fun buildNotificationSafe(): Notification {
        return try {
            buildNotification()
        } catch (t: Throwable) {
            LogStore.w(TAG, "构建通知失败，用兜底通知: ${t.message}")
            // 最简兜底：不依赖任何自定义资源
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, ensureChannel())
                    .setContentTitle("云脱修")
                    .setContentText("后台任务运行中")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("云脱修")
                    .setContentText("后台任务运行中")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .build()
            }
        }
    }

    private fun ensureChannel(): String {
        val ch = NotificationChannel(
            CHANNEL_ID, "云脱修 Worker", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            ?.createNotificationChannel(ch)
        return CHANNEL_ID
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            LogStore.i(TAG, "轮询循环开始（间隔 ${POLL_INTERVAL_MS}ms）")
            while (isActive) {
                try {
                    val activeTasks = TaskRepository.listTasks().filter { !it.isTerminal }
                    if (activeTasks.isNotEmpty()) {
                        LogStore.i(TAG, "发现 ${activeTasks.size} 个活跃任务")
                    }
                    for (task in activeTasks) {
                        processTask(task)
                    }
                } catch (t: Throwable) {
                    LogStore.e(TAG, "轮询异常: ${t.message}")
                    Log.e(TAG, "轮询异常", t)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun processTask(task: TaskMetaView) {
        try {
            val queue = ActionQueue(task.taskId)
            val executor = ActionExecutor(this, task.taskId)
            val pending = queue.pendingPayloads()
            for ((payload, respFile) in pending) {
                LogStore.i(TAG, "[${task.taskId}] 执行 ${payload.action}")
                val resp = executor.execute(payload)
                queue.writeResponse(respFile, resp)
                LogStore.i(TAG, "[${task.taskId}] ${payload.action} -> ok=${resp.ok} ${resp.detail}")
            }
        } catch (t: Throwable) {
            LogStore.e(TAG, "[${task.taskId}] 处理失败: ${t.message}")
        }
    }

    private fun buildNotification(): Notification {
        val channelId = ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("云脱修")
            .setContentText("后台脱壳任务运行中")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            LogStore.i(TAG, "WorkerService.onDestroy")
            pollingJob?.cancel()
            scope.cancel()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WorkerService"
        const val POLL_INTERVAL_MS = 3000L
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "yuntuoxiu_worker"
    }
}