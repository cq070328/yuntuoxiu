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
     * 检查 RUN_COMMAND 权限。
     *
     * ⚠️ 重要修正（v1.5.4）：
     *   `com.termux.permission.RUN_COMMAND` 是 Termux 的**自定义权限**，
     *   在绝大多数 ROM 上 `checkSelfPermission()` 会返回 DENIED ——
     *   但**这不代表命令发不出去**！Termux 的 RunCommandService 对
     *   「已在 Manifest 声明该权限」的调用方实际是放行的。
     *
     *   原实现用它做**硬拦截**，导致真机上「始终报缺少权限」、命令根本
     *   没发出去。现在改为**宽松判断**：
     *     - 只要 Manifest 声明了（编译期即固定），就认为「权限声明就绪」
     *     - 真正的失败会在 startService / 探针阶段暴露
     *
     * @return Pair(声明是否就绪, 描述)
     */
    fun runCommandPermissionHint(context: Context): Pair<Boolean, String> {
        if (!isTermuxInstalled(context)) {
            return false to "Termux 未安装"
        }
        // 宽松：不因 checkSelfPermission==DENIED 就拦截。
        // 只做「安装 + 声明」检查；能否执行交给实际发送验证。
        return true to "已就绪（若命令无效，请确认 Termux 已开启 allow-external-apps）"
    }

    /**
     * 【诊断用】返回 RUN_COMMAND 权限的详细状态（不用于拦截）。
     */
    fun describeRunCommandPermission(context: Context): String {
        return try {
            val granted = context.checkSelfPermission(PERM_RUN_COMMAND) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            "declared=true, checkSelfPermission=${if (granted) "GRANTED" else "DENIED(正常,不影响执行)"}"
        } catch (t: Throwable) {
            "检查异常: ${t.message}"
        }
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
        // ① 先试 RUN_COMMAND（标准方式）
        if (tryRunCommand(context, command, args, background, workdir)) {
            return true
        }
        // ② RUN_COMMAND 被系统拦（SecurityException）-> 回退到「文件触发桥」
        //    原理：worker 轮询 $CLOUD/cmd/*.cmd，执行后写 *.done
        LogStore.w(TAG, "RUN_COMMAND 不可用，改用文件触发桥（cmd 队列）")
        return writeCmdFile(command, args)
    }

    /** 尝试标准 RUN_COMMAND 方式；失败返回 false（不抛异常）。 */
    private fun tryRunCommand(
        context: Context,
        command: String,
        args: List<String>,
        background: Boolean,
        workdir: String
    ): Boolean {
        return try {
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
                putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "云脱修")
                putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "云脱修任务")
                putExtra("com.termux.RUN_COMMAND_STDOUT", outFile)
                putExtra("com.termux.RUN_COMMAND_STDERR", errFile)
                putExtra("com.termux.RUN_COMMAND_COMMAND_RESULT_STDOUT", outFile)
                putExtra("com.termux.RUN_COMMAND_COMMAND_RESULT_STDERR", errFile)
            }
            context.startService(intent)
            LogStore.i(TAG, "RUN_COMMAND 已发送: $command ${args.joinToString(" ")}")
            true
        } catch (t: Throwable) {
            LogStore.w(TAG, "RUN_COMMAND 失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * ⭐ 文件触发桥：把命令写到 $CLOUD/cmd/*.cmd，由 Termux worker 轮询执行。
     * 完全绕开 RUN_COMMAND 权限限制（/sdcard 双方可读写）。
     *
     * 命令文件格式：第一行=命令，其余行=参数。
     */
    private fun writeCmdFile(command: String, args: List<String>): Boolean {
        return try {
            val cmdDir = File(WORKSPACE, "unpackcloud/cmd").apply { mkdirs() }
            val name = "c_${System.currentTimeMillis()}_${(1000..9999).random()}.cmd"

            // 把「命令 + 参数」翻译成 worker 能识别的 cmd 协议：
            //   - 若 command 是 bash 脚本路径 -> run_script <路径> <参数...>
            //   - 否则 -> exec <完整命令>
            val content = when {
                command.endsWith("/bash") || command.endsWith("bash") -> {
                    // command 是 bash，args = [脚本, 参数...]
                    if (args.isNotEmpty()) {
                        "run_script\n" + args.joinToString("\n")
                    } else {
                        "ping"
                    }
                }
                command.startsWith("/") && File(command).exists() -> {
                    "run_script\n$command\n" + args.joinToString("\n")
                }
                else -> {
                    // exec 模式：拼接命令
                    "exec\n$command " + args.joinToString(" ")
                }
            }
            File(cmdDir, name).writeText(content)
            LogStore.i(TAG, "已写入命令桥: $name ($content)")

            // 等待 worker 消费（最多 5 秒），成功则返回 true
            val done = File(cmdDir, name.replace(".cmd", ".done"))
            repeat(25) {
                if (done.exists()) {
                    try {
                        LogStore.i(TAG, "命令桥回执: ${done.readText().trim()}")
                        done.delete()
                    } catch (_: Throwable) {}
                    return true
                }
                try { Thread.sleep(200) } catch (_: Throwable) {}
            }
            LogStore.w(TAG, "命令桥等待超时（worker 可能未运行）")
            false
        } catch (t: Throwable) {
            LogStore.e(TAG, "写命令桥失败: ${t.message}")
            false
        }
    }

    /**
     * 【探针】验证「APP -> Termux」通道是否真的可用。
     * 写一个标记文件，2 秒内出现即代表 RUN_COMMAND 生效。
     * 这是比 checkSelfPermission 可靠得多的真实检测。
     */
    fun probeRunCommandChannel(context: Context): Boolean {
        val marker = File(RESULT_DIR, ".chan_ok")
        try { if (marker.exists()) marker.delete() } catch (_: Throwable) {}
        File(RESULT_DIR).mkdirs()
        val ok = runInTermux(
            context,
            TERMUX_BASH,
            listOf("-c", "echo ok > '$RESULT_DIR/.chan_ok'"),
            background = true
        )
        if (!ok) return false
        repeat(20) {
            if (marker.exists()) return true
            try { Thread.sleep(150) } catch (_: Throwable) {}
        }
        return false
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