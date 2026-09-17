package com.yuntuoxiu.app.worker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yuntuoxiu.app.LogStore
import java.io.File

/**
 * TermuxBridge —— 云脱修 APP 与 Termux 的联动桥（B 方案）。
 *
 * 两个能力：
 *  1. isTermuxInstalled()   检查 Termux 是否安装
 *  2. runInTermux(cmd...)   通过 RUN_COMMAND Intent 在 Termux 执行命令
 *  3. openTermux()          打开 Termux（用户手动查看）
 *
 * 前置（用户一次性配置）：
 *  - 安装 Termux
 *  - Termux 里执行 termux-setup-storage（授权存储）
 *  - 若要静默执行，需在 Termux 设置里开启「允许外部程序执行命令」
 */
object TermuxBridge {

    private const val TAG = "TermuxBridge"
    const val TERMUX_PKG = "com.termux"
    const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    private const val WORKSPACE = "/sdcard/MT2/apks"

    /** Termux 私有目录（APP 无权直接访问，仅用于生成 shell 命令串） */
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /** 结果回读目录（Termux 把执行结果写到这里，APP 轮询读取） */
    private const val RESULT_DIR = "$WORKSPACE/unpackcloud/logs/termux_cmd"

    /** Termux 是否已安装 */
    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PKG, 0)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 检查是否已授予 RUN_COMMAND 权限。
     * 未授权时 startService 会被系统静默拒绝（SecurityException 只进 logcat），
     * 因此必须先检查，给出明确提示。
     */
    fun hasRunCommandPermission(context: Context): Boolean {
        return try {
            context.checkSelfPermission(PERM_RUN_COMMAND) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 检查 Termux 是否开启了 allow-external-apps。
     * ⚠️ APP 无法读取 Termux 私有目录的 termux.properties，
     *    只能通过一个「探针命令 + 结果文件」间接判断（见 probeExternalApps）。
     *
     * @return Pair(是否已授权, 提示文案)
     */
    fun runCommandPermissionHint(context: Context): Pair<Boolean, String> {
        if (!isTermuxInstalled(context)) {
            return false to "Termux 未安装"
        }
        if (!hasRunCommandPermission(context)) {
            return false to "未授予 RUN_COMMAND 权限（请在系统设置中允许「云脱修」运行命令）"
        }
        return true to "RUN_COMMAND 权限已就绪"
    }

    /** 打开 Termux（仅切到前台，不执行命令） */
    fun openTermux(context: Context): Boolean {
        return try {
            val i = context.packageManager.getLaunchIntentForPackage(TERMUX_PKG)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                LogStore.i(TAG, "已打开 Termux")
                true
            } else {
                LogStore.w(TAG, "Termux 启动 Intent 不可用")
                false
            }
        } catch (t: Throwable) {
            LogStore.e(TAG, "打开 Termux 失败: ${t.message}")
            false
        }
    }

    /**
     * 在 Termux 执行命令（通过 RUN_COMMAND Service）。
     *
     * @param command 要执行的命令（绝对路径的脚本或命令）
     * @param args    参数
     * @param background true=后台执行（不切换前台）
     * @return 是否成功发出请求
     */
    fun runInTermux(
        context: Context,
        command: String,
        args: List<String> = emptyList(),
        background: Boolean = true,
        workdir: String = WORKSPACE
    ): Boolean {
        if (!isTermuxInstalled(context)) {
            LogStore.w(TAG, "Termux 未安装，无法执行")
            return false
        }
        if (!hasRunCommandPermission(context)) {
            LogStore.w(TAG, "缺少 RUN_COMMAND 权限，Termux 不会收到命令（startService 会被静默拒绝）")
            return false
        }
        return try {
            // 让 Termux 把 stdout/stderr 落到工作区，APP 可轮询回读（沙箱隔离的绕行方案）
            val resultDir = File(RESULT_DIR).apply { mkdirs() }
            val stamp = "cmd_${System.currentTimeMillis()}"
            val outFile = File(resultDir, "$stamp.out").absolutePath
            val errFile = File(resultDir, "$stamp.err").absolutePath

            val intent = Intent().apply {
                setClassName(TERMUX_PKG, TERMUX_SERVICE)
                action = ACTION_RUN_COMMAND
                putExtra("com.termux.RUN_COMMAND_PATH", command)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args.toTypedArray())
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
                // 标准 RUN_COMMAND 结果回传（需 Termux 侧配合）
                putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "云脱修")
                putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "云脱修任务")
                putExtra("com.termux.RUN_COMMAND_STDOUT", outFile)
                putExtra("com.termux.RUN_COMMAND_STDERR", errFile)
                putExtra("com.termux.RUN_COMMAND_COMMAND_RESULT_STDOUT", outFile)
                putExtra("com.termux.RUN_COMMAND_COMMAND_RESULT_STDERR", errFile)
            }
            context.startService(intent)
            LogStore.i(TAG, "已向 Termux 发送命令: $command ${args.joinToString(" ")}")
            true
        } catch (t: Throwable) {
            LogStore.e(TAG, "Termux 执行失败: ${t.message}")
            false
        }
    }

    /**
     * 便捷：让 Termux **启动常驻守护**（推荐方式）。
     *
     * ⚠️ 修复：原来直接调 ytx_all.sh 跑全流程，会绕过后端状态机、
     *    且和 APP 的 WorkerService 抢 action 队列。
     *
     * ⭐ v1.5.3 起：启动**完整后端**（termux_start_all.sh）——
     *    同时拉起「Python 后端 watcher + 构建 worker」两个常驻进程，
     *    使 APP + Termux 即可独立完成整条流水线，无需 Operit 容器/手动脚本。
     */
    fun runTaskPipeline(context: Context, taskId: String, background: Boolean = true): Boolean {
        // 直接启动完整后端（幂等，已在跑则跳过）
        return startDaemon(context)
    }

    /**
     * 让 Termux 启动**完整后端**（后端调度器 + 构建 worker）。
     *
     * 优先用 termux_start_all.sh；若不存在（旧部署）则回退 termux_daemon.sh。
     */
    fun startDaemon(context: Context): Boolean {
        val allScript = "$WORKSPACE/termux_start_all.sh"
        val legacy = "$WORKSPACE/termux_daemon.sh"
        val script = if (File(allScript).exists()) allScript else legacy
        val scriptName = if (script == allScript) "完整后端" else "守护(legacy)"
        LogStore.i(TAG, "启动 Termux $scriptName: $script")
        return runInTermux(
            context,
            TERMUX_BASH,
            listOf(script, "start"),
            background = true
        )
    }

    /** 停止 Termux 完整后端（供 UI 使用）。 */
    fun stopDaemon(context: Context): Boolean {
        val allScript = "$WORKSPACE/termux_start_all.sh"
        val legacy = "$WORKSPACE/termux_daemon.sh"
        val script = if (File(allScript).exists()) allScript else legacy
        return runInTermux(
            context,
            TERMUX_BASH,
            listOf(script, "stop"),
            background = true
        )
    }

    /**
     * 探针：间接判断 Termux 是否开启了 allow-external-apps。
     * 原理：发一条写标记文件的命令，2 秒后检查文件是否出现。
     * 注意：若权限/配置都没问题，命令在后台执行，这里只做「尽力探测」。
     */
    fun probeExternalApps(context: Context): Boolean {
        val marker = File(RESULT_DIR, ".probe_ok")
        try { if (marker.exists()) marker.delete() } catch (_: Throwable) {}
        File(RESULT_DIR).mkdirs()
        val ok = runInTermux(
            context,
            TERMUX_BASH,
            listOf("-c", "echo ok > '$RESULT_DIR/.probe_ok'"),
            background = true
        )
        if (!ok) return false
        // 轮询等待最多 3 秒
        repeat(15) {
            if (marker.exists()) return true
            try { Thread.sleep(200) } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * 读取 Termux 守护心跳（由 termux_worker.sh 定期写）。
     * @return Pair(是否在线, 描述)
     */
    fun readDaemonStatus(): Pair<Boolean, String> {
        // worker 心跳 + 后端心跳，两者都在才算「完整后端在线」
        val worker = readHeartbeat("termux_heartbeat.json")
        val backend = readHeartbeat("termux_backend_heartbeat.json")
        return when {
            worker.first && backend.first ->
                true to "完整后端在线（backend+worker）"
            worker.first && !backend.first ->
                false to "仅 worker 在线，后端调度器未启动"
            !worker.first && backend.first ->
                false to "仅后端在线，构建 worker 未启动"
            else -> false to (backend.second)
        }
    }

    /** 读取某个心跳文件（统一逻辑）。 */
    private fun readHeartbeat(name: String): Pair<Boolean, String> {
        val f = File(WORKSPACE, "unpackcloud/logs/$name")
        if (!f.exists()) return false to "未启动（无 $name）"
        return try {
            val obj = com.google.gson.JsonParser.parseString(f.readText()).asJsonObject
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

    /** 读取最近一次命令的输出（供 UI 展示，尽力而为） */
    fun readLatestOutput(): String {
        return try {
            val dir = File(RESULT_DIR)
            val f = dir.listFiles { x -> x.name.endsWith(".out") }
                ?.maxByOrNull { it.name } ?: return "（无输出）"
            if (f.length() > 64 * 1024) f.readText().takeLast(64 * 1024) else f.readText()
        } catch (t: Throwable) {
            "（读取失败: ${t.message}）"
        }
    }

    /**
     * 【环境自检 + 自动搭建】
     * 检查 Termux 依赖是否就绪；缺失则自动跑 bootstrap 脚本安装。
     */
    fun ensureEnvironment(context: Context): Boolean {
        if (!isTermuxInstalled(context)) {
            LogStore.w(TAG, "Termux 未安装，无法搭建环境")
            return false
        }
        val boot = "$WORKSPACE/termux_bootstrap.sh"
        if (!java.io.File(boot).exists()) {
            LogStore.w(TAG, "bootstrap 脚本不存在: $boot")
            return false
        }
        // 让 Termux 执行 bootstrap（幂等，已装的会跳过）
        val ok = runInTermux(
            context,
            "/data/data/com.termux/files/usr/bin/bash",
            listOf(boot),
            background = true
        )
        LogStore.i(TAG, "已请求 Termux 自检/搭建环境: $ok")
        return ok
    }

    /**
     * 检查是否已搭建完成（读取 stamp 文件）
     * 注意：APP 无法直接读 Termux 私有目录，这里仅做提示用。
     */
    fun isBootstrappedHint(): Boolean {
        // APP 无法访问 /data/data/com.termux，只能提示用户
        return false
    }

    /** 打开 Termux 并进入工作区目录（便于用户查看） */
    fun openTermuxAtWorkspace(context: Context): Boolean {
        // RUN_COMMAND 前台执行：打开 Termux 并 cd 到工作区
        return runInTermux(
            context,
            "/data/data/com.termux/files/usr/bin/bash",
            listOf("-c", "cd $WORKSPACE && exec bash"),
            background = false
        )
    }
}