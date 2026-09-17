package com.yuntuoxiu.app.worker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yuntuoxiu.app.LogStore

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

    /** Termux 是否已安装 */
    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PKG, 0)
            true
        } catch (t: Throwable) {
            false
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
        workdir: String = WORKSPACE,
    ): Boolean {
        if (!isTermuxInstalled(context)) {
            LogStore.w(TAG, "Termux 未安装，无法执行")
            return false
        }
        return try {
            val intent = Intent().apply {
                setClassName(TERMUX_PKG, TERMUX_SERVICE)
                action = ACTION_RUN_COMMAND
                putExtra("com.termux.RUN_COMMAND_PATH", command)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args.toTypedArray())
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
                // 允许把结果写到文件
                putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "云脱修")
                putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "云脱修任务")
            }
            context.startService(intent)
            LogStore.i(TAG, "已向 Termux 发送命令: $command ${args.joinToString(" ")}")
            true
        } catch (t: Throwable) {
            LogStore.e(TAG, "Termux 执行失败: ${t.message}")
            false
        }
    }

    /** 便捷：让 Termux 处理某个任务（跑全流程脚本） */
    fun runTaskPipeline(context: Context, taskId: String, background: Boolean = true): Boolean {
        val script = "$WORKSPACE/ytx_all.sh"
        return runInTermux(
            context,
            "/data/data/com.termux/files/usr/bin/bash",
            listOf(script, taskId),
            background = background,
        )
    }

    /** 便捷：让 Termux 启动守护进程 */
    fun startDaemon(context: Context): Boolean {
        val script = "$WORKSPACE/termux_daemon.sh"
        return runInTermux(
            context,
            "/data/data/com.termux/files/usr/bin/bash",
            listOf(script, "start"),
            background = true,
        )
    }

    /** 打开 Termux 并进入工作区目录（便于用户查看） */
    fun openTermuxAtWorkspace(context: Context): Boolean {
        // RUN_COMMAND 前台执行：打开 Termux 并 cd 到工作区
        return runInTermux(
            context,
            "/data/data/com.termux/files/usr/bin/bash",
            listOf("-c", "cd $WORKSPACE && exec bash"),
            background = false,
        )
    }
}