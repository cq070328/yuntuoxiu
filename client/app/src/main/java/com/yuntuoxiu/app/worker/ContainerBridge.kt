package com.yuntuoxiu.app.worker

import android.util.Log
import com.yuntuoxiu.app.LogStore
import com.yuntuoxiu.app.YunTuoXiuApp
import com.yuntuoxiu.app.shizuku.ShizukuShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * ContainerBridge —— APP ↔ 容器 worker 的命令桥（v1.6.2）
 *
 * 背景：
 *   Shizuku shell 只有 `sh`，**没有 bash/java/python3**。
 *   而「注入」「构建」需要 java+python3 → 必须委托给容器。
 *
 * 协议（与 termux_worker.sh 的 cmd 处理一致）：
 *   APP 写  : cmd/<name>.cmd   （首行命令，其余为参数）
 *   worker  : 执行后写 cmd/<name>.done（JSON: {ok, detail, cmd, ts}）
 *
 * 本对象提供：
 *   · isWorkerAlive()            worker 心跳是否新鲜（<30s）
 *   · execSync(script, args)     委托容器同步执行脚本，返回 stdout
 *   · execSh(cmd)                委托容器执行 shell
 */
object ContainerBridge {

    private const val TAG = "ContainerBridge"
    private val CMD_DIR = File(YunTuoXiuApp.CLOUD_ROOT, "cmd")
    private val HB = File(YunTuoXiuApp.CLOUD_ROOT, "logs/termux_heartbeat.json")

    /**
     * worker 心跳是否新鲜（v1.6.8：优先用 Shizuku 读，避开 /sdcard 权限问题）
     */
    fun isWorkerAlive(maxAgeMs: Long = 30_000): Boolean {
        return try {
            // ① 先试直接读（有 MANAGE_EXTERNAL_STORAGE 时可行）
            if (HB.exists()) {
                val age = System.currentTimeMillis() - HB.lastModified()
                val j = JSONObject(HB.readText())
                val state = j.optString("state", "")
                if (age < maxAgeMs && state != "stopped") return true
            }
            // ② 退回：用 Shizuku 读（shell 一定能读）
            val r = ShizukuShellExecutor.exec(
                "cat '$HB' 2>/dev/null")
            val out = (r.getString("stdout") ?: "").trim()
            if (out.isEmpty()) return false
            val j = JSONObject(out)
            // Shizuku 读到内容 → 用文件 mtime 判新鲜度（shell stat）
            val r2 = ShizukuShellExecutor.exec(
                "echo $(($(date +%s%3N) - $(stat -c %Y '$HB' 2>/dev/null || echo 0) * 1000))")
            val ageMs = (r2.getString("stdout") ?: "999999").trim().toLongOrNull() ?: 999999L
            val state = j.optString("state", "")
            ageMs < maxAgeMs && state != "stopped"
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * ⭐ v1.6.8 确保 worker 在跑。若不在，尝试用 Shizuku 拉起。
     *
     * 原理：Shizuku shell 虽是 /system/bin/sh（无 java/python），
     *      但**容器**里的 bash 能启动 worker。
     *      而容器进程需从「容器内部」启动 → 用 Operit 的 API 或
     *      **不行**（Shizuku 无法启动容器进程）。
     *
     * 所以真实可行的是：
     *   ① 若 worker 心跳新鲜 → 直接返回 true
     *   ② 否则 → 提示用户在 Operit 终端执行 ytx.sh start
     *   （未来：APP 与 Operit 深度集成后可自动拉起）
     */
    suspend fun ensureWorker(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isWorkerAlive()) return@withContext true to "worker 在线"

        // 尝试通过「命令桥」让已有 worker 重启（若它只是心跳停了但进程还在）
        try {
            val name = "app_ensure_" + System.currentTimeMillis()
            val cmdFile = File(CMD_DIR, "$name.cmd")
            CMD_DIR.mkdirs()
            cmdFile.writeText("ping\n")
            // 等 3s 看是否有响应
            val doneFile = File(CMD_DIR, "$name.done")
            var waited = 0L
            while (waited < 3000) {
                if (doneFile.exists()) {
                    cmdFile.delete(); doneFile.delete()
                    return@withContext true to "worker 响应 ping（进程在，心跳可能过期）"
                }
                Thread.sleep(200); waited += 200
            }
            cmdFile.delete()
        } catch (_: Throwable) {}

        false to ("worker 离线。请在 Operit 终端执行：\n" +
                  "  bash /sdcard/MT2/apks/ytx.sh start")
    }

    /** 读心跳详情（供 UI 显示） */
    fun heartbeatInfo(): String {
        return try {
            if (!HB.exists()) return "无心跳"
            val age = (System.currentTimeMillis() - HB.lastModified()) / 1000
            val j = JSONObject(HB.readText())
            "state=${j.optString("state")} age=${age}s env=${j.optString("env")}"
        } catch (t: Throwable) {
            "心跳读取失败: ${t.message}"
        }
    }

    /**
     * 委托容器同步执行脚本。
     *
     * @param script 脚本绝对路径（如 /sdcard/MT2/apks/ytx_npatch_inject.sh）
     * @param args   参数列表
     * @param timeoutMs 超时（默认 30 分钟）
     * @return Triple(ok, detail, rawJson)
     */
    suspend fun execSync(
        script: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 30 * 60 * 1000
    ): Triple<Boolean, String, String> = withContext(Dispatchers.IO) {
        if (!isWorkerAlive()) {
            return@withContext Triple(false,
                "容器 worker 未运行（命令无人处理）", "")
        }
        val name = "app_" + System.currentTimeMillis()
        val cmdFile = File(CMD_DIR, "$name.cmd")
        val doneFile = File(CMD_DIR, "$name.done")
        CMD_DIR.mkdirs()
        doneFile.delete()

        // 写命令（首行 exec_sync，其余为脚本+参数，每行一个）
        val content = buildString {
            append("exec_sync\n")
            append(script).append('\n')
            args.forEach { append(it).append('\n') }
        }
        cmdFile.writeText(content)
        LogStore.i(TAG, "已委托: $script (${args.size} 参数)")

        // 轮询等待 done
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (doneFile.exists()) {
                val text = doneFile.readText()
                cmdFile.delete(); doneFile.delete()
                return@withContext parseDone(text)
            }
            delay(500)
        }
        cmdFile.delete()
        Triple(false, "委托超时（${timeoutMs / 1000}s）", "")
    }

    /** 委托容器执行任意 shell */
    suspend fun execSh(cmd: String, timeoutMs: Long = 10 * 60 * 1000)
        : Triple<Boolean, String, String> = withContext(Dispatchers.IO) {
        if (!isWorkerAlive()) {
            return@withContext Triple(false, "容器 worker 未运行", "")
        }
        val name = "app_" + System.currentTimeMillis()
        val cmdFile = File(CMD_DIR, "$name.cmd")
        val doneFile = File(CMD_DIR, "$name.done")
        CMD_DIR.mkdirs()
        doneFile.delete()
        cmdFile.writeText("exec_sh\n$cmd\n")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (doneFile.exists()) {
                val text = doneFile.readText()
                cmdFile.delete(); doneFile.delete()
                return@withContext parseDone(text)
            }
            delay(500)
        }
        cmdFile.delete()
        Triple(false, "委托超时", "")
    }

    private fun parseDone(text: String): Triple<Boolean, String, String> {
        return try {
            val j = JSONObject(text)
            val ok = j.optString("ok") == "ok"
            val detail = j.optString("detail", "")
            Triple(ok, detail, text)
        } catch (t: Throwable) {
            Triple(false, "回执解析失败: ${t.message}\n$text", text)
        }
    }
}