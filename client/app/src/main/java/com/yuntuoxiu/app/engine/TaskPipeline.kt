package com.yuntuoxiu.app.engine

import com.yuntuoxiu.app.LogStore
import java.io.File

/**
 * TaskPipeline —— 任务级单一 APK 流水线（v2.0）
 *
 * 核心思想：
 *   一个任务只有一个「工作 APK」（work.apk），所有功能**就地修改它**，
 *   最终产出一个 signed.apk。避免「每个功能各生成一个 APK」的碎片化。
 *
 * 流水线步骤（可任意顺序，幂等）：
 *   UNCLEAN(去壳) → PATCH(规则修补) → UNPACK(脱壳) → DEX_REPAIR(dex修复)
 *   → REPLACE(dex替换) → SIG_BYPASS(去签名校验) → SIGN(签名)
 *
 * 状态记录在 tasks/<id>/pipeline.json，重复执行同一步骤会跳过（幂等）。
 */
object TaskPipeline {

    private const val TAG = "TaskPipeline"

    /** 流水线步骤 */
    enum class Step(val label: String) {
        UNCLEAN("去壳清理"),
        PATCH("规则修补"),
        UNPACK("本地脱壳"),
        DEX_REPAIR("DEX修复"),
        REPLACE("DEX替换"),
        SIG_BYPASS("去除签名校验"),
        SIGN("签名"),
    }

    /** ⭐ v2.2：正则类步骤（可选，UI 可提示用户是否启用） */
    val REGEX_STEPS = setOf(Step.PATCH, Step.SIG_BYPASS)

    /** 步骤状态 */
    data class StepState(
        val step: String,
        val done: Boolean,
        val output: String?,   // 该步骤产物路径（通常都是 work.apk）
        val timestamp: Long,
        val detail: String = "",
    )

    /** 流水线状态文件（每任务一个） */
    private fun stateFile(taskId: String): File =
        File(com.yuntuoxiu.app.YunTuoXiuApp.CLOUD_ROOT, "tasks/$taskId/pipeline.json")

    /** 工作目录 */
    fun workDir(taskId: String): File {
        val d = File(com.yuntuoxiu.app.YunTuoXiuApp.CLOUD_ROOT, "tasks/$taskId")
        d.mkdirs()
        return d
    }

    /** 当前工作 APK（所有步骤的输入/输出） */
    fun workApk(taskId: String): File = File(workDir(taskId), "work.apk")

    /** 最终签名产物 */
    fun signedApk(taskId: String): File = File(workDir(taskId), "build/signed.apk")

    /** 读取状态 */
    fun load(taskId: String): MutableMap<String, StepState> {
        return try {
            val f = stateFile(taskId)
            if (!f.isFile) return mutableMapOf()
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, StepState>>() {}.type
            gson.fromJson<MutableMap<String, StepState>>(f.readText(), type) ?: mutableMapOf()
        } catch (t: Throwable) {
            mutableMapOf()
        }
    }

    /** 保存状态 */
    private fun save(taskId: String, map: Map<String, StepState>) {
        try {
            val f = stateFile(taskId)
            f.parentFile?.mkdirs()
            f.writeText(com.google.gson.Gson().toJson(map))
        } catch (t: Throwable) {
            LogStore.e(TAG, "保存流水线状态失败: ${t.message}")
        }
    }

    /** 标记步骤完成 */
    fun markDone(taskId: String, step: Step, output: File?, detail: String = "") {
        val map = load(taskId)
        map[step.name] = StepState(step.name, true, output?.absolutePath,
            System.currentTimeMillis(), detail)
        save(taskId, map)
        LogStore.i(TAG, "[$taskId] 步骤完成: ${step.label}")
    }

    /** 该步骤是否已完成（幂等判断） */
    fun isDone(taskId: String, step: Step): Boolean {
        val st = load(taskId)[step.name] ?: return false
        if (!st.done) return false
        // 产物存在才认为真的完成
        val out = st.output?.let { File(it) }
        return out == null || out.exists()
    }

    /**
     * 确保「工作 APK」存在：
     *   · 若 work.apk 不存在，从任务的原 APK 复制
     *   · 返回 work.apk
     */
    fun ensureWorkApk(taskId: String, sourceApk: File): File {
        val w = workApk(taskId)
        if (!w.isFile || w.length() < 1024) {
            w.parentFile?.mkdirs()
            sourceApk.copyTo(w, overwrite = true)
            LogStore.i(TAG, "[$taskId] 初始化 work.apk (${w.length()}B)")
        }
        return w
    }

    /**
     * 替换 work.apk（某步骤产出新 APK 后调用，让后续步骤基于它）。
     */
    fun replaceWorkApk(taskId: String, newApk: File) {
        val w = workApk(taskId)
        if (newApk.absolutePath == w.absolutePath) return
        try {
            newApk.copyTo(w, overwrite = true)
            LogStore.i(TAG, "[$taskId] work.apk 已更新 (${w.length()}B)")
        } catch (t: Throwable) {
            LogStore.e(TAG, "更新 work.apk 失败: ${t.message}")
        }
    }

    /** 重置流水线（重新开始） */
    fun reset(taskId: String) {
        try {
            stateFile(taskId).delete()
            workApk(taskId).delete()
            LogStore.i(TAG, "[$taskId] 流水线已重置")
        } catch (_: Throwable) {}
    }

    /** 生成人类可读的状态摘要 */
    fun summary(taskId: String): String {
        val map = load(taskId)
        val sb = StringBuilder()
        sb.append("流水线状态 ($taskId):\n")
        for (s in Step.values()) {
            val st = map[s.name]
            val mark = if (st?.done == true) "✅" else "⬜"
            sb.append("  $mark ${s.label}")
            if (st?.done == true && st.output != null) {
                sb.append("  → ${File(st.output).name}")
            }
            sb.append('\n')
        }
        sb.append("\nwork.apk: ${if (workApk(taskId).exists()) "${workApk(taskId).length() / 1024}KB" else "不存在"}")
        return sb.toString()
    }
}