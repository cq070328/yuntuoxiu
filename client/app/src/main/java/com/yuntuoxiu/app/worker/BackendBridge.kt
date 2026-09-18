package com.yuntuoxiu.app.worker

import com.google.gson.JsonParser
import com.yuntuoxiu.app.LogStore
import java.io.File

/**
 * BackendBridge —— 云脱修 APP 与「容器后端」的状态桥接（v1.8.4）。
 *
 * 背景（架构演进）：
 *   云脱修已**放弃 Termux**，后端统一运行在 Operit 容器里
 *   （termux_backend.sh 调度 + termux_worker.sh 构建，由 ytx.sh start 拉起）。
 *   原先的 TermuxBridge 里大量「Termux 执行/探针/bootstrap」逻辑已成死代码。
 *
 * 本对象只保留**真正需要**的能力：
 *   1) readDaemonStatus()  —— 读后端心跳，判断「完整后端是否在线」
 *   2) ensureRequest()     —— 提交任务后，把「需要后端消费」的意图落盘
 *                             （请求已由 TaskRepository 写入 uploads/，此处仅做日志）
 *
 * 注：命令桥（cmd 目录下的 .cmd 文件）在容器侧由 termux_worker.sh 消费——
 *     APP 若需委托容器执行重活，走 ContainerBridge.execSync，而不是本对象。
 */
object BackendBridge {

    private const val TAG = "BackendBridge"
    private const val WORKSPACE = "/sdcard/MT2/apks"

    /**
     * 读取容器后端状态。
     *
     * 判定「完整后端在线」= 后端调度器心跳 + 构建 worker 心跳**都新鲜**。
     * 心跳由容器侧进程写：
     *   - termux_backend_heartbeat.json  ← termux_backend.sh（Python watcher）
     *   - termux_heartbeat.json          ← termux_worker.sh（构建 worker，含独立心跳守护）
     *
     * @return Pair(是否完整在线, 人类可读描述)
     */
    fun readDaemonStatus(): Pair<Boolean, String> {
        val backend = readHeartbeat("termux_backend_heartbeat.json")
        val worker = readHeartbeat("termux_heartbeat.json")
        return when {
            backend.first && worker.first ->
                true to "完整后端在线（调度器+worker）"
            worker.first && !backend.first ->
                false to "仅 worker 在线，后端调度器未启动"
            !worker.first && backend.first ->
                false to "仅后端在线，构建 worker 未启动"
            else -> false to worker.second
        }
    }

    /** 后端调度器是否在线（供 UI 徽章）。 */
    fun isBackendAlive(): Boolean = readHeartbeat("termux_backend_heartbeat.json").first

    /** 构建 worker 是否在线（供 UI 徽章）。 */
    fun isWorkerAlive(): Boolean = readHeartbeat("termux_heartbeat.json").first

    /** 读取某个心跳文件（统一逻辑）。 */
    private fun readHeartbeat(name: String): Pair<Boolean, String> {
        val f = File(WORKSPACE, "unpackcloud/logs/$name")
        if (!f.exists()) return false to "未启动（无 $name）"
        return try {
            val obj = JsonParser.parseString(f.readText()).asJsonObject
            val state = obj.get("state")?.asString ?: "?"
            val tsMs = obj.get("ts_ms")?.asLong ?: 0L
            val ageSec = (System.currentTimeMillis() - tsMs) / 1000
            when {
                state == "stopped" -> false to "已停止"
                ageSec > 60 -> false to "心跳超时（${ageSec}s 前）"
                else -> true to "在线（${ageSec}s 前）"
            }
        } catch (t: Throwable) {
            false to "心跳解析失败: ${t.message}"
        }
    }

    /**
     * 提交任务后调用：确认后端会消费 uploads/ 下的 create 请求。
     *
     * ⚠️ 架构约定（单一真相源）：
     *   任务创建 100% 由容器后端 watcher 负责。APP **不**自建任务。
     *   本方法只做「后端在线性」检查 + 日志，不触发任何 Termux 行为。
     *
     * @return 后端是否在线（在线则请求将被及时消费；离线则请求驻留等待）
     */
    fun ensureRequest(): Boolean {
        val (alive, desc) = readDaemonStatus()
        LogStore.i(TAG, "后端状态: $desc（online=$alive）")
        return alive
    }
}