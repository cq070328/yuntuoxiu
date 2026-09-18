package com.yuntuoxiu.app.worker

import com.yuntuoxiu.app.LogStore
import com.yuntuoxiu.app.YunTuoXiuApp
import java.io.File

/**
 * AutoWatcher —— APP 侧的轻量执行器（v1.8.0 架构修正）。
 *
 * ⭐ 重大变更：不再承担「创建任务」职责。
 *
 * 背景（双真相源 bug，导致一键脱修卡死）：
 *   旧版 APP 内建了「本地 watcher」，会自己消费 uploads/create_*.req.json
 *   并在 tasks/ 下建 local_* 骨架，同时把请求 archive 到 uploads/done/。
 *   而 Operit 容器后端（termux_backend.sh / ytx.sh start 拉起的
 *   Python watcher）才是任务的**唯一权威创建者**。
 *
 *   两者并发 ⟹ 容器后端永远看不到被归档的请求 ⟹ 不建 t_* 任务 ⟹
 *   卡片停在 local_* 骨架，一键脱修永不推进。
 *
 * 现在（单一真相源）：
 *   · 任务创建：100% 交给容器后端 watcher。
 *   · APP 职责：只做「设备执行器」——扫描后端下发的
 *     work/actions 目录下的 req 指令，执行（Shizuku 安装/启动/
 *     收集 dump 等）并回写 resp。
 *
 * 保留本对象仅因为 WorkerService 仍会调用 processActions()；
 * tick() 保留签名但已废弃为 no-op（向后兼容，避免旧调用点编译失败）。
 */
object AutoWatcher {

    private const val TAG = "AutoWatcher"

    /**
     * ⛔ v1.8.0：已废弃为 no-op（任务创建交由容器后端 watcher）。
     *
     * @return 恒为 0（不再创建任何任务）
     */
    fun tick(): Int {
        LogStore.i(TAG, "tick 已禁用（任务创建交由容器后端 watcher）")
        return 0
    }

    /**
     * 执行所有活跃任务的待办 action（不依赖 WorkerService）。
     * 由 MainActivity / WorkerService 调用。
     *
     * @return 本次执行的 action 数量
     */
    fun processActions(context: android.content.Context): Int {
        var done = 0
        try {
            val active = com.yuntuoxiu.app.data.TaskRepository.listTasks()
                .filter { !it.isTerminal }
                // 跳过历史遗留的 local_ 骨架（新版本已不再产生；仅兼容老数据）
                .filter { !it.taskId.startsWith("local_") }
            for (task in active) {
                try {
                    val queue = ActionQueue(task.taskId)
                    val executor = ActionExecutor(context, task.taskId)
                    val pending = queue.pendingPayloads()
                    for ((payload, respFile) in pending) {
                        LogStore.i(TAG, "[${task.taskId}] 执行 ${payload.action}")
                        val resp = executor.execute(payload)
                        queue.writeResponse(respFile, resp)
                        LogStore.i(TAG, "[${task.taskId}] ${payload.action} -> " +
                                "ok=${resp.ok} ${resp.detail}")
                        done++
                    }
                } catch (t: Throwable) {
                    LogStore.e(TAG, "[${task.taskId}] 执行 action 失败: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            LogStore.e(TAG, "processActions 异常: ${t.message}")
        }
        return done
    }

    /** 待处理请求数（供 UI 显示：uploads/ 下尚未被后端消费的 create 请求）。 */
    fun pendingCount(): Int {
        return try {
            val uploadsRoot = File(YunTuoXiuApp.CLOUD_ROOT, "uploads")
            val files = uploadsRoot.listFiles() ?: return 0
            var n = 0
            for (f in files) {
                if (f.isFile && f.name.startsWith("create_") && f.name.endsWith(".req.json")) n++
            }
            n
        } catch (t: Throwable) { 0 }
    }
}