package com.yuntuoxiu.app.engine

import com.yuntuoxiu.app.LogStore
import java.io.File

/**
 * PipelineRunner —— 一键流水线编排器（v2.0）
 *
 * 与「单独工具」的区别：
 *   · 一键脱壳：跑完所有步骤，产出 **独立的 oneclick.apk**
 *   · 工具面板：单步操作 work.apk，产出 **工具产物**
 *   · 两者互不干扰；工具产物可作为一键的输入（补足）
 *
 * 一键流水线步骤（顺序）：
 *   ① 去壳清理   →  ② 规则修补  →  ③ 本地脱壳  →  ④ DEX 修复
 *   → ⑤ DEX 替换  →  ⑥ 去除签名校验  →  ⑦ 签名
 */
object PipelineRunner {

    private const val TAG = "PipelineRunner"

    /** 一键产物 */
    fun oneClickApk(taskId: String): File =
        File(TaskPipeline.workDir(taskId), "build/oneclick.apk")

    data class Progress(
        val stepIndex: Int,
        val totalSteps: Int,
        val stepLabel: String,
        val message: String,
        val ok: Boolean,
    )

    /**
     * 执行一键流水线。
     *
     * @param taskId     任务 ID
     * @param sourceApk  原始 APK
     * @param pkg        包名（可选）
     * @param onProgress 进度回调
     * @return 最终 APK（失败为 null）
     */
    fun runOneClick(
        taskId: String,
        sourceApk: File,
        pkg: String?,
        onProgress: (Progress) -> Unit = {},
    ): File? {
        if (!sourceApk.isFile) {
            onProgress(Progress(0, 7, "初始化", "源 APK 不存在", false))
            return null
        }

        val wd = TaskPipeline.workDir(taskId)
        val dexDir = File(wd, "dump").apply { mkdirs() }
        // 一键流水线用独立 work 文件，避免与工具面板互相干扰
        val work = File(wd, "oneclick_work.apk")
        sourceApk.copyTo(work, overwrite = true)
        LogStore.i(TAG, "[$taskId] 一键流水线开始，work=${work.length()}B")

        var cur = work

        // ① 去壳清理
        onProgress(Progress(1, 7, "去壳清理", "开始…", true))
        runCatching {
            val out = File(wd, "oneclick_clean.apk")
            val n = ShellDetectBridge.cleanShell(cur, out)
            if (out.isFile && out.length() > 1024) {
                cur = out
                onProgress(Progress(1, 7, "去壳清理", "已清理 $n 个壳条目", true))
            } else {
                onProgress(Progress(1, 7, "去壳清理", "无壳条目，跳过", true))
            }
        }.onFailure {
            onProgress(Progress(1, 7, "去壳清理", "失败(跳过): ${it.message}", false))
        }

        // ② 规则修补
        onProgress(Progress(2, 7, "规则修补", "开始…", true))
        runCatching {
            val out = File(wd, "oneclick_patched.apk")
            val r = LocalSmaliPatcher.patch(cur, out, null, true, true)
            if (r.ok && out.isFile && out.length() > 1024) {
                cur = out
                onProgress(Progress(2, 7, "规则修补", r.detail, true))
            } else {
                onProgress(Progress(2, 7, "规则修补", "无变更，跳过", true))
            }
        }.onFailure {
            onProgress(Progress(2, 7, "规则修补", "失败(跳过): ${it.message}", false))
        }

        // ③ 本地脱壳
        onProgress(Progress(3, 7, "本地脱壳", "启动引擎…", true))
        val dexes = runCatching {
            LocalUnpackEngine.dumpFile(
                com.yuntuoxiu.app.YunTuoXiuApp.instance, cur
            ) { msg -> onProgress(Progress(3, 7, "本地脱壳", msg, true)) }
        }.getOrDefault(emptyList())

        if (dexes.isEmpty()) {
            onProgress(Progress(3, 7, "本地脱壳", "未产出 DEX（目标可能未加壳/对抗）", false))
            // ⚠️ 无 dump 也继续（可能应用本身未加壳）
        } else {
            onProgress(Progress(3, 7, "本地脱壳", "产出 ${dexes.size} 个 dex", true))
            dexes.forEach { runCatching { it.copyTo(File(dexDir, it.name), true) } }
        }

        // ④ DEX 修复
        if (dexes.isNotEmpty()) {
            onProgress(Progress(4, 7, "DEX修复", "开始…", true))
            runCatching {
                val fixDir = File(wd, "dump_fixed").apply { mkdirs() }
                val (ok, detail) = DexRepairEngine.repairAll(dexDir, fixDir) { }
                onProgress(Progress(4, 7, "DEX修复", "修复 $ok 个: $detail", ok > 0))
            }.onFailure {
                onProgress(Progress(4, 7, "DEX修复", "失败(跳过): ${it.message}", false))
            }
        } else {
            onProgress(Progress(4, 7, "DEX修复", "无 dex，跳过", true))
        }

        // ⑤ DEX 替换
        if (dexes.isNotEmpty()) {
            onProgress(Progress(5, 7, "DEX替换", "开始…", true))
            runCatching {
                val fixDir = File(wd, "dump_fixed")
                val useDir = if (fixDir.isDirectory && fixDir.listFiles()?.isNotEmpty() == true)
                    fixDir else dexDir
                val out = File(wd, "oneclick_replaced.apk")
                val r = LocalRepairEngine.rebuild(cur, LocalUnpackEngine.collectDex(useDir), out, repairDex = false)
                if (r != null && out.isFile) {
                    cur = out
                    onProgress(Progress(5, 7, "DEX替换", "替换 ${r.dexCount} 个 dex", true))
                } else {
                    onProgress(Progress(5, 7, "DEX替换", "替换失败，跳过", false))
                }
            }.onFailure {
                onProgress(Progress(5, 7, "DEX替换", "失败(跳过): ${it.message}", false))
            }
        } else {
            onProgress(Progress(5, 7, "DEX替换", "无 dex，跳过", true))
        }

        // ⑥ 去除签名校验（记录原始签名）
        onProgress(Progress(6, 7, "去除签名校验", "开始…", true))
        runCatching {
            val rec = SigBypassEngine.recordAndApply(sourceApk, pkg)
            onProgress(Progress(6, 7, "去除签名校验", rec.detail, rec.ok))
        }.onFailure {
            onProgress(Progress(6, 7, "去除签名校验", "失败(跳过): ${it.message}", false))
        }

        // ⑦ 签名
        onProgress(Progress(7, 7, "签名", "开始…", true))
        val finalApk = oneClickApk(taskId)
        val signRes = runCatching {
            LocalApkSigner.sign(cur, finalApk, null, com.yuntuoxiu.app.YunTuoXiuApp.instance)
        }.getOrNull()

        if (signRes?.ok == true) {
            onProgress(Progress(7, 7, "签名", "完成: ${finalApk.absolutePath}", true))
            LogStore.i(TAG, "[$taskId] 一键流水线完成: ${finalApk.absolutePath}")
            return finalApk
        } else {
            // 签名失败 → 返回未签名产物
            cur.copyTo(finalApk, overwrite = true)
            onProgress(Progress(7, 7, "签名", "签名失败，输出未签名产物: ${signRes?.detail}", false))
            return finalApk
        }
    }
}

/**
 * ShellDetect 桥接（避免循环依赖）
 */
private object ShellDetectBridge {
    fun cleanShell(src: File, dst: File): Int {
        return com.yuntuoxiu.app.worker.ShellDetect.cleanShellSo(src.absolutePath, dst.absolutePath)
    }
}