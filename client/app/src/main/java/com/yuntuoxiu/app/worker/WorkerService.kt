package com.yuntuoxiu.app.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.yuntuoxiu.app.LogStore
import com.yuntuoxiu.app.data.TaskMetaView
import com.yuntuoxiu.app.data.TaskRepository
import kotlinx.coroutines.*

/**
 * WorkerService：后台轮询任务队列
 *
 * 稳定性 + 可观测性：
 *  1) 全流程 try/catch，不崩溃
 *  2) 定期心跳日志（每 10 次轮询一次），避免「看起来没动静」
 *  3) 通知常驻，显示运行状态
 */
class WorkerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var heartbeat = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try { LogStore.i(TAG, "WorkerService.onCreate") } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat()
            LogStore.i(TAG, "前台服务已启动（通知栏可见）")
        } catch (t: Throwable) {
            LogStore.e(TAG, "startForeground 失败: ${t.message}")
        }
        try {
            startPolling()
        } catch (t: Throwable) {
            LogStore.e(TAG, "启动轮询失败: ${t.message}")
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notif = buildNotificationSafe()
        // Android 14：用 2 参避免 FOREGROUND_SERVICE_<TYPE> 权限校验
        startForeground(NOTIF_ID, notif)
    }

    private fun buildNotificationSafe(): Notification {
        return try {
            buildNotification()
        } catch (t: Throwable) {
            LogStore.w(TAG, "通知构建失败，用兜底: ${t.message}")
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
        try {
            val ch = NotificationChannel(
                CHANNEL_ID, "云脱修 Worker", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(ch)
        } catch (_: Throwable) {}
        return CHANNEL_ID
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) {
            LogStore.i(TAG, "轮询已在运行，忽略重复启动")
            return
        }
        pollingJob = scope.launch {
            LogStore.i(TAG, "轮询循环开始（间隔 ${POLL_INTERVAL_MS}ms）")
            while (isActive) {
                try {
                    // ⭐ v1.8.0 架构修正：不再调用 AutoWatcher.tick()。
                    //
                    //   原因：任务创建是「容器后端 watcher」的唯一职责。
                    //   APP 内建 watcher 会造成【双真相源】——
                    //     · APP 建 local_* 骨架，并把 create 请求归档到
                    //       uploads/done/ → 容器后端再也看不到请求 → 不建 t_*。
                    //     · 结果：任务永远停在 local_* 骨架，永不推进。
                    //
                    //   现在 APP 只做「设备执行器」：
                    //     · 扫描 tasks/<t_*>/work/actions/ 下由后端下发的指令
                    //     · 执行（Shizuku 安装/启动/收集 dump 等）并回执
                    //   任务创建 100% 交给容器后端。

                    // 执行活跃任务的 action 指令（仅 t_* 正式任务；local_ 已不再产生）
                    val tasks = TaskRepository.listTasks()
                    val active = tasks.filter {
                        !it.isTerminal && !it.taskId.startsWith("local_")
                    }

                    heartbeat++
                    if (heartbeat % 100 == 0) {   // 每 100 次(≈5分钟)一次心跳
                        LogStore.i(TAG, "心跳 #$heartbeat | 任务=${tasks.size} 活跃=${active.size}")
                    }

                    if (active.isNotEmpty()) {
                        for (task in active) processTask(task)
                    }
                } catch (t: Throwable) {
                    LogStore.e(TAG, "轮询异常: ${t.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
            LogStore.i(TAG, "轮询循环结束")
        }
    }

    private fun processTask(task: TaskMetaView) {
        try {
            val queue = ActionQueue(task.taskId)
            val executor = ActionExecutor(this, task.taskId)
            val pending = queue.pendingPayloads()
            if (pending.isEmpty()) return
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
            .setContentText("后台任务运行中（轮询中）")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            LogStore.i(TAG, "WorkerService.onDestroy")
            pollingJob?.cancel()
            scope.cancel()
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WorkerService"
        const val POLL_INTERVAL_MS = 3000L
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "yuntuoxiu_worker"
    }
}