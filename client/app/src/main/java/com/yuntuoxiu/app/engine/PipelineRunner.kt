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
     * @param taskId      任务 ID
     * @param sourceApk   原始 APK
     * @param pkg         包名（可选）
     * @param useRegex    是否启用「正则类步骤」（规则修补 / 去签名校验）。
     *                    默认 true；false 时跳过这两步（用户可自行选择）。
     * @param onProgress  进度回调
     * @return 最终 APK（失败为 null）
     */
    fun runOneClick(
        taskId: String,
        sourceApk: File,
        pkg: String?,
        useRegex: Boolean = true,
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
        LogStore.i(TAG, "[$taskId] 一键流水线开始，work=${work.length()}B useRegex=$useRegex")

        var cur = work

        // ① 去壳清理
        //
        // ⭐⭐ v2.2 关键修复：默认**跳过**「去壳清理」！
        //   实测：先删壳 so/assets 会导致目标 APK 无法安装/运行
        //   （壳 so 是 App 启动必需的），进而 dump 必然失败
        //   （日志：parserApk 异常 InvocationTargetException → parser apk error）。
        //
        //   正解：脱壳必须对「原始完整 APK」进行（壳完整才能跑起来 dump）。
        //   去壳清理应放在【dump 之后】。这里改为仅记录，不在脱壳前执行。
        onProgress(Progress(1, 7, "去壳清理", "跳过（脱壳需完整壳，清理移至脱壳后）", true))

        // ② 规则修补（正则类步骤，可选）
        if (useRegex) {
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
        } else {
            onProgress(Progress(2, 7, "规则修补", "已禁用（未启用正则），跳过", true))
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
        } else {
            onProgress(Progress(3, 7, "本地脱壳", "产出 ${dexes.size} 个 dex", true))
        }

        // ⭐⭐⭐ v2.4【Layout Inspect 思路】：读取沙箱运行时解析的「真实 Application 入口」。
        //   沙箱（BActivityThread）在构造目标 Application 后，会把真实入口类名写入
        //   原始 dump 目录的 entry.txt。这里先抓取，避免后续 dump_post/dump_fixed
        //   （入口不在这些目录）导致丢失。
        val runtimeEntry: String? = try {
            val ef = File(dexes.firstOrNull()?.parentFile ?: dexDir, "entry.txt")
            if (ef.isFile) ef.readText().trim().ifBlank { null } else null
        } catch (_: Throwable) { null }
        runtimeEntry?.let {
            onProgress(Progress(3, 7, "真实入口", "运行时解析到真实 Application: $it", true))
            LogStore.i(TAG, "[$taskId] runtimeEntry=$it")
        }

        // ③b ⭐ v2.2：DEX 后处理（过滤壳 stub / 去重 / 按 class 数排序 / 命名 classesN）
        var processedDexes: List<File> = emptyList()
        if (dexes.isNotEmpty()) {
            onProgress(Progress(3, 7, "DEX后处理", "过滤壳 stub + 去重 + 排序…", true))
            runCatching {
                val postDir = File(wd, "dump_post").apply { mkdirs() }
                val pr = DexPostProcessor.process(dexes, postDir) { m ->
                    onProgress(Progress(3, 7, "DEX后处理", m, true))
                }
                processedDexes = pr.kept
                // 同时把原始 dex 归拢到任务 dump 目录（保留原始产物供查看）
                dexes.forEach { runCatching { it.copyTo(File(dexDir, it.name), true) } }
                onProgress(Progress(3, 7, "DEX后处理", pr.detail, pr.kept.isNotEmpty()))
            }.onFailure {
                onProgress(Progress(3, 7, "DEX后处理", "失败(用原始 dex): ${it.message}", false))
                processedDexes = dexes
            }
        }

        // ④ DEX 修复
        var fixedDir: File? = null
        if (processedDexes.isNotEmpty()) {
            onProgress(Progress(4, 7, "DEX修复", "开始…", true))
            runCatching {
                val fixDir = File(wd, "dump_fixed").apply { mkdirs() }
                val srcDir = File(wd, "dump_post")
                val (ok, detail) = DexRepairEngine.repairAll(srcDir, fixDir) { }
                fixedDir = fixDir
                onProgress(Progress(4, 7, "DEX修复", "修复 $ok 个", ok > 0))
            }.onFailure {
                onProgress(Progress(4, 7, "DEX修复", "失败(跳过): ${it.message}", false))
            }
        } else {
            onProgress(Progress(4, 7, "DEX修复", "无 dex，跳过", true))
        }

        // ⑤ DEX 替换（含真实入口替换）
        if (processedDexes.isNotEmpty()) {
            onProgress(Progress(5, 7, "DEX替换", "开始…", true))
            runCatching {
                val useDir = fixedDir?.takeIf { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
                    ?: File(wd, "dump_post")
                val out = File(wd, "oneclick_replaced.apk")
                val r = LocalRepairEngine.rebuild(
                    cur, LocalUnpackEngine.collectDex(useDir), out,
                    // ⭐ v2.2：替换 dex 时**保留**壳 so/assets（脱壳阶段需要），
                    //   真正的清壳在下一步 ⑤b 执行
                    cleanShell = false, repairDex = false,
                    // ⭐ v2.4：把沙箱运行时解析的真实入口传入 → rebuild 优先采用它替换 Manifest
                    runtimeEntry = runtimeEntry
                )
                if (r != null && out.isFile) {
                    cur = out
                    onProgress(Progress(5, 7, "DEX替换",
                        "替换 ${r.dexCount} 个 dex，入口=${r.realApp ?: "未变"}", true))
                } else {
                    onProgress(Progress(5, 7, "DEX替换", "替换失败，跳过", false))
                }
            }.onFailure {
                onProgress(Progress(5, 7, "DEX替换", "失败(跳过): ${it.message}", false))
            }
        } else {
            onProgress(Progress(5, 7, "DEX替换", "无 dex，跳过", true))
        }

        // ⑤b ⭐ v2.2：清壳（在 dex 替换【之后】执行）
        //   顺序很重要：脱壳阶段必须保留完整壳（否则 App 跑不起来无法 dump）；
        //   拿到真实 dex 并替换完后，才可安全清理壳文件（so + assets）。
        onProgress(Progress(5, 7, "清壳清理", "清理壳 so/assets…", true))
        runCatching {
            val out = File(wd, "oneclick_decleaned.apk")
            val n = ShellDetectBridge.cleanShell(cur, out)
            if (out.isFile && out.length() > 1024) {
                cur = out
                onProgress(Progress(5, 7, "清壳清理", "已清理 $n 个壳条目", true))
            } else {
                onProgress(Progress(5, 7, "清壳清理", "无壳条目，跳过", true))
            }
        }.onFailure {
            onProgress(Progress(5, 7, "清壳清理", "失败(跳过): ${it.message}", false))
        }

        // ⑥ 去除签名校验（正则类步骤，可选）
        if (useRegex) {
            onProgress(Progress(6, 7, "去除签名校验", "开始…", true))
            runCatching {
                val rec = SigBypassEngine.recordAndApply(sourceApk, pkg)
                onProgress(Progress(6, 7, "去除签名校验", rec.detail, rec.ok))
            }.onFailure {
                onProgress(Progress(6, 7, "去除签名校验", "失败(跳过): ${it.message}", false))
            }
        } else {
            onProgress(Progress(6, 7, "去除签名校验", "已禁用（未启用正则），跳过", true))
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