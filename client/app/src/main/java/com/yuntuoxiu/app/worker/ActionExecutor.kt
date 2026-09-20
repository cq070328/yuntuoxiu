package com.yuntuoxiu.app.worker
import android.content.Context
import android.util.Log
import com.yuntuoxiu.app.YunTuoXiuApp
import com.yuntuoxiu.app.data.ActionPayload
import com.yuntuoxiu.app.data.ActionResponse
import com.yuntuoxiu.app.data.ActionType
import com.yuntuoxiu.app.shizuku.ShizukuClient
import com.yuntuoxiu.app.shizuku.ShizukuErrorCodes
import com.yuntuoxiu.app.shizuku.ShizukuShellExecutor
import java.io.File
/**
 * ActionExecutor：把后端下发的 action_payload 翻译成真机操作。
 *
 * 约束（PRD 定稿）：
 *  - PRE_CHECK 只上报环境状态，禁止触发任何 dump 流水线。
 *  - 动态 dump 通过 Xposed 模块（com.ytx.dump）完成（无需 root）。
 *    （v1.6：Frida-Gadget 方案因 Android 10+ SELinux 限制已废弃）
 *  - dump 产出做 0 字节过滤 + MD5 + 分片写入 chunks/。
 */
class ActionExecutor(private val context: Context, private val taskId: String) {

    companion object {
        private const val TAG = "ActionExecutor"

        /** 客户端 ABI 固定 arm64-v8a（PRD 定稿） */
        const val CLIENT_ABI = "arm64-v8a"

        /** Frida-Gadget 锁定版本 + 路径 */
        const val GADGET_VERSION = "16.5.9"
        const val GADGET_PATH = "/data/local/tmp/frida-gadget-$GADGET_VERSION-arm64.so"
    }

    fun execute(payload: ActionPayload): ActionResponse {
        return try {
            when (payload.action) {
                ActionType.PRE_CHECK -> onPreCheck(payload)
                ActionType.CLEAR_TARGET -> onClearTarget(payload)
                ActionType.LSPATCH_PATCH -> onPatch(payload)
                ActionType.INSTALL_CLONE -> onInstall(payload)
                ActionType.START_TARGET -> onStart(payload)
                ActionType.TRIGGER_PAGE -> onTriggerPage(payload)
                ActionType.DUMP_MEMORY -> onDump(payload)
                ActionType.FILTER_DEX -> onFilter(payload)
                ActionType.UPLOAD_CHUNK -> onUpload(payload)
                ActionType.BUILD_APK -> onBuild(payload)
                ActionType.CANCEL -> ActionResponse(true, "cancelled")
                // ⭐ v2.0 本地引擎动作
                ActionType.LOCAL_UNPACK -> onLocalUnpack(payload)
                ActionType.LOCAL_REPAIR -> onLocalRepair(payload)
                ActionType.LOCAL_SIGN -> onLocalSign(payload)
                ActionType.LOCAL_ALL -> onLocalAll(payload)
                ActionType.LOCAL_ENGINE_CHECK -> onLocalEngineCheck(payload)
                ActionType.LOCAL_PATCH -> onLocalPatch(payload)
                ActionType.LOCAL_LOG_CAPTURE -> onLocalLogCapture(payload)
                else -> ActionResponse(false, "未知 action: ${payload.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行 action ${payload.action} 失败", e)
            ActionResponse(false, e.message ?: "执行异常")
        }
    }

    // ============================================================
    // ⭐ v2.0 本地引擎动作（脱离终端 / 容器）
    // ============================================================

    /**
     * 本地引擎自检：native so / assets / ABI / 初始化状态。
     */
    private fun onLocalEngineCheck(payload: ActionPayload): ActionResponse {
        val chk = com.yuntuoxiu.app.engine.LocalUnpackEngine.checkAvailability(context)
        val ready = com.yuntuoxiu.app.engine.LocalUnpackEngine.isReady()
        val ok = chk.available
        val detail = buildString {
            append("本地脱壳引擎: ")
            append(if (ok) "可用" else "不可用")
            append(" (初始化=${if (ready) "已就绪" else "未就绪"})")
            if (chk.problems.isNotEmpty()) append("\n问题: " + chk.problems.joinToString("; "))
        }
        return ActionResponse(ok, detail, mapOf(
            "abi" to chk.abi,
            "so_main" to chk.soMain,
            "so_dump" to chk.soDump,
            "native_dir" to chk.nativeDir,
            "engine_ready" to ready,
            "problems" to chk.problems
        ))
    }

    /**
     * 本地规则化修补（Manifest 入口替换 / 反调试串清理 / 壳 so+assets 清理）。
     * 对应原版「正则替换」能力，v2.0 纯 Kotlin 本地化。
     *
     * params:
     *   in_apk        源 APK
     *   out_apk?      输出（默认 tasks/<id>/build/patched.apk）
     *   real_app?     真实 Application 类名（不传则自动推断）
     *   clean_manifest? 默认 true
     *   clean_anti_debug? 默认 true
     */
    private fun onLocalPatch(payload: ActionPayload): ActionResponse {
        val inApk = payload.params["in_apk"] as? String
            ?: return ActionResponse(false, "缺 in_apk")
        val taskDir = java.io.File(YunTuoXiuApp.CLOUD_ROOT, "tasks/$taskId")
        val outApk = (payload.params["out_apk"] as? String)?.let { java.io.File(it) }
            ?: java.io.File(taskDir, "build/patched.apk")
        val realApp = payload.params["real_app"] as? String
        val cleanManifest = (payload.params["clean_manifest"] as? Boolean) ?: true
        val cleanAnti = (payload.params["clean_anti_debug"] as? Boolean) ?: true

        val res = com.yuntuoxiu.app.engine.LocalSmaliPatcher.patch(
            java.io.File(inApk), outApk, realApp, cleanManifest, cleanAnti
        ) { Log.i(TAG, "  $it") }

        return if (res.ok) {
            ActionResponse(true, "本地修补完成: ${res.detail}",
                mapOf("out_apk" to res.outApk?.absolutePath,
                      "manifest_changed" to res.manifestChanged,
                      "removed_so" to res.removedShellSo,
                      "removed_assets" to res.removedShellAssets,
                      "anti_debug_cleaned" to res.antiDebugCleaned))
        } else {
            ActionResponse(false, res.detail, mapOf("fail_code" to "PATCH_FAIL"))
        }
    }

    /**
     * 本地日志/崩溃捕获（logcat）。
     * params: package?（过滤）、duration_ms?（默认5000）、clear?（默认false）
     */
    private fun onLocalLogCapture(payload: ActionPayload): ActionResponse {
        val pkg = payload.params["package"] as? String
        val dur = (payload.params["duration_ms"] as? Number)?.toLong() ?: 5000L
        val clear = (payload.params["clear"] as? Boolean) ?: false

        val res = com.yuntuoxiu.app.engine.LogCapture.capture(taskId, pkg, dur, clear)
        return if (res.ok) {
            ActionResponse(true, res.detail,
                mapOf("file" to res.file?.absolutePath,
                      "lines" to res.lineCount,
                      "crash_found" to res.crashFound,
                      "crash" to res.crashSnippet.take(2000)))
        } else {
            ActionResponse(false, res.detail, mapOf("fail_code" to "LOG_CAPTURE_FAIL"))
        }
    }

    /**
     * 本地脱壳（App 内 BlackBox 引擎，无需 Xposed/终端）。
     * params: package（已安装包名） 或 apk（本地 APK 路径）
     */
    private fun onLocalUnpack(payload: ActionPayload): ActionResponse {
        val pkg = payload.params["package"] as? String
        val apkPath = payload.params["apk"] as? String

        val dexes: List<java.io.File> = when {
            !apkPath.isNullOrBlank() -> {
                Log.i(TAG, "本地脱壳(文件): $apkPath")
                com.yuntuoxiu.app.engine.LocalUnpackEngine.dumpFile(
                    context, java.io.File(apkPath)) { Log.i(TAG, "  $it") }
            }
            !pkg.isNullOrBlank() -> {
                Log.i(TAG, "本地脱壳(包名): $pkg")
                com.yuntuoxiu.app.engine.LocalUnpackEngine.dumpInstalled(
                    context, pkg) { Log.i(TAG, "  $it") }
            }
            else -> return ActionResponse(false, "缺 package 或 apk 参数")
        }

        if (dexes.isEmpty()) {
            return ActionResponse(false,
                "本地脱壳未产出 DEX（目标可能未启动/壳对抗/引擎异常）",
                mapOf("fail_code" to "DUMP_LOCAL_EMPTY"))
        }
        val sum = com.yuntuoxiu.app.engine.LocalUnpackEngine.summarize(dexes)
        return ActionResponse(true,
            "本地脱壳完成: ${dexes.size} 个 dex",
            sum + mapOf("source" to "local_engine"))
    }

    /**
     * 本地修复：dump dex + 原 APK → 清壳重组。
     * params: original_apk（原 APK）、dex_dir（dump 目录，可选，默认任务 dump/）
     */
    private fun onLocalRepair(payload: ActionPayload): ActionResponse {
        val origApk = payload.params["original_apk"] as? String
            ?: return ActionResponse(false, "缺 original_apk")
        val taskDir = java.io.File(YunTuoXiuApp.CLOUD_ROOT, "tasks/$taskId")
        val dexDir = (payload.params["dex_dir"] as? String)?.let { java.io.File(it) }
            ?: java.io.File(taskDir, "dump")
        val outApk = java.io.File(taskDir, "build/repaired.apk")

        val dexes = com.yuntuoxiu.app.engine.LocalUnpackEngine.collectDex(dexDir)
        if (dexes.isEmpty()) {
            return ActionResponse(false, "未找到可用 DEX: ${dexDir.absolutePath}",
                mapOf("fail_code" to "REPAIR_NO_DEX"))
        }
        val res = com.yuntuoxiu.app.engine.LocalRepairEngine.rebuild(
            java.io.File(origApk), dexes, outApk, cleanShell = true
        ) { Log.i(TAG, "  $it") }
            ?: return ActionResponse(false, "本地重组失败", mapOf("fail_code" to "REPAIR_FAIL"))

        return ActionResponse(true,
            "本地修复完成: dex=${res.dexCount} 清壳=${res.removedShell}",
            mapOf("out_apk" to res.outApk.absolutePath,
                  "dex_count" to res.dexCount,
                  "removed_shell" to res.removedShell))
    }

    /**
     * 本地签名（apksig）。
     * params: in_apk（待签名）、out_apk（可选）
     */
    private fun onLocalSign(payload: ActionPayload): ActionResponse {
        val inApk = payload.params["in_apk"] as? String
            ?: return ActionResponse(false, "缺 in_apk")
        val taskDir = java.io.File(YunTuoXiuApp.CLOUD_ROOT, "tasks/$taskId")
        val outApk = (payload.params["out_apk"] as? String)?.let { java.io.File(it) }
            ?: java.io.File(taskDir, "build/signed.apk")

        val res = com.yuntuoxiu.app.engine.LocalApkSigner.sign(
            java.io.File(inApk), outApk, null
        ) { Log.i(TAG, "  $it") }
        return if (res.ok) {
            ActionResponse(true, "本地签名完成", mapOf("out_apk" to res.outApk?.absolutePath))
        } else {
            ActionResponse(false, res.detail, mapOf("fail_code" to "SIGN_FAIL"))
        }
    }

    /**
     * 本地一键：脱壳 → 修复 → 签名。
     * params: package / apk、original_apk
     */
    private fun onLocalAll(payload: ActionPayload): ActionResponse {
        val pre = onLocalUnpack(payload)
        if (!pre.ok) return pre
        // 把 dump 的 dex 收集到任务目录供修复使用（若在同一目录则无需动）
        val origApk = payload.params["original_apk"] as? String
        if (origApk.isNullOrBlank()) {
            return ActionResponse(true, "脱壳完成（未提供 original_apk，跳过修复）",
                pre.extra)
        }
        val p2 = HashMap<String, Any?>(payload.params)
        p2["original_apk"] = origApk
        val rep = onLocalRepair(ActionPayload(payload.seq, ActionType.LOCAL_REPAIR,
            payload.taskId, payload.createdAt, p2))
        if (!rep.ok) return rep
        val outApk = rep.extra["out_apk"] as? String
            ?: return ActionResponse(false, "修复产物缺失")
        val p3 = HashMap<String, Any?>(payload.params)
        p3["in_apk"] = outApk
        val sign = onLocalSign(ActionPayload(payload.seq, ActionType.LOCAL_SIGN,
            payload.taskId, payload.createdAt, p3))
        return if (sign.ok) {
            ActionResponse(true, "本地一键完成（脱壳+修复+签名）",
                sign.extra + mapOf("repaired_apk" to outApk))
        } else sign
    }

    /** 纯环境预检：只上报状态，禁触发 dump */
    private fun onPreCheck(payload: ActionPayload): ActionResponse {
        val shizukuOk = ShizukuClient.isGranted()
        return ActionResponse(
            ok = true,
            detail = "环境预检完成",
            extra = mapOf(
                "abi" to CLIENT_ABI,
                "shizuku_granted" to shizukuOk,
                "shizuku_available" to ShizukuClient.isAvailable(),
                "device_ready" to shizukuOk,
                "no_dump" to true  // 强制约束：预检禁触发 dump
            )
        )
    }

    /** 清除目标 App 旧缓存/残留（避免加固留存对抗标记） */
    private fun onClearTarget(payload: ActionPayload): ActionResponse {
        val pkg = payload.params["package"] as? String ?: return ActionResponse(false, "缺 package")
        val r = ShizukuClient.clearAppData(pkg)
        val code = r.getInt("code")
        return if (code == ShizukuErrorCodes.OK) {
            ActionResponse(true, "已清除 $pkg 缓存/残留")
        } else {
            ActionResponse(false, "清缓存失败: ${ShizukuErrorCodes.describe(code)}",
                mapOf("code" to code, "fail_code" to (r.getString("fail_code") ?: "")))
        }
    }

    /** 校验容器下发的 LSPatch 产物（打补丁在容器侧完成，App 只校验+安装） */
    private fun onPatch(payload: ActionPayload): ActionResponse {
        val pkg = payload.params["package"] as? String ?: return ActionResponse(false, "缺 package")
        val patched = payload.params["patched_apk"] as? String
            ?: return ActionResponse(false, "缺 patched_apk（应由容器侧 lspatch_runner 产出）",
                mapOf("fail_code" to "DUMP_LSPATCH_FAIL"))

        Log.i(TAG, "校验 LSPatch 产物: $patched (pkg=$pkg)")
        val (ok, detail) = LspatchHelper.validatePatchedApk(patched)
        return if (ok) {
            ActionResponse(true, "LSPatch 产物校验通过",
                mapOf("patched_apk" to patched))
        } else {
            ActionResponse(false, "LSPatch 产物不可用: $detail",
                mapOf("fail_code" to "DUMP_LSPATCH_FAIL"))
        }
    }

    private fun onInstall(payload: ActionPayload): ActionResponse {
        val apk = payload.params["patched_apk"] as? String
            ?: return ActionResponse(false, "缺 patched_apk")
        val r = ShizukuClient.installApk(apk, replace = true)
        val code = r.getInt("code")
        return if (code == ShizukuErrorCodes.OK) {
            ActionResponse(true, "副本已安装")
        } else {
            // ⭐ v1.8.9：失败时优先展示 UserService 回传的真实 detail/stderr 首行，
            //   而非泛化的 describe(code)，便于定位（如签名冲突 / SELinux）。
            val stderr = r.getString("stderr") ?: ""
            val realDetail = r.getString("detail")?.takeIf { it.isNotBlank() }
                ?: stderr.lineSequence().firstOrNull { it.isNotBlank() }?.take(300)
                ?: ShizukuErrorCodes.describe(code)
            ActionResponse(false, "安装失败: $realDetail",
                mapOf("code" to code,
                    "fail_code" to (r.getString("fail_code") ?: "DUMP_INSTALL_FAIL"),
                    "stderr" to stderr))
        }
    }

    private fun onStart(payload: ActionPayload): ActionResponse {
        val pkg = payload.params["package"] as? String ?: return ActionResponse(false, "缺 package")
        val cls = (payload.params["activity"] as? String) ?: ""
        val r = ShizukuClient.startActivity(pkg, cls)
        val code = r.getInt("code")
        return if (code == ShizukuErrorCodes.OK) {
            ActionResponse(true, "已启动 $pkg")
        } else {
            ActionResponse(false, "启动失败: ${ShizukuErrorCodes.describe(code)}",
                mapOf("code" to code, "fail_code" to (r.getString("fail_code") ?: "DUMP_CLIENT_CRASH")))
        }
    }

    private fun onTriggerPage(payload: ActionPayload): ActionResponse {
        val page = payload.params["trigger_page"] as? String
        val pkg = payload.params["package"] as? String
        if (page == null) return ActionResponse(true, "无指定页面，跳过")
        val r = ShizukuClient.startActivity(pkg ?: "", page)
        val code = r.getInt("code")
        return if (code == ShizukuErrorCodes.OK) {
            ActionResponse(true, "已触发 $page")
        } else {
            ActionResponse(false, "触发失败: ${ShizukuErrorCodes.describe(code)}",
                mapOf("code" to code, "fail_code" to (r.getString("fail_code") ?: "DUMP_CLIENT_CRASH")))
        }
    }

    /**
     * 执行内存 dump（v1.6：收集 Xposed 模块产物）
     *
     * ⭐ 技术变更：
     *   原方案假设 Frida-Gadget 已在目标进程内 dump 到本地目录 —— 已废弃
     *   （Android 10+ SELinux 限制）。
     *
     *   新方案：Xposed 模块（com.ytx.dump）在目标 App 内运行，
     *   把真实 DEX dump 到 /sdcard/Android/data/<pkg>/files/ytx_dump/。
     *
     *   ⚠️ 关键：这个目录是 App 私有的：
     *     · 容器/Termux 读不了
     *     · **只有 Shizuku（shell, ext_data_rw 组）能读**
     *   所以必须用 ShizukuShellExecutor 来「收集」。
     */
    private fun onDump(payload: ActionPayload): ActionResponse {
        val pkg = payload.params["package"] as? String
            ?: return ActionResponse(false, "缺 package")

        val modDump = "/sdcard/Android/data/$pkg/files/ytx_dump"
        val taskDump = java.io.File(YunTuoXiuApp.CLOUD_ROOT, "tasks/$taskId/dump")
        taskDump.mkdirs()

        Log.i(TAG, "收集 Xposed 模块 dump: $modDump")

        // 用 Shizuku 执行（shell 有权限读 App 私有目录）
        val cmd = buildString {
            append("mkdir -p '${taskDump.absolutePath}' && ")
            append("if [ -d '$modDump' ]; then ")
            append("  cp -f '$modDump'/dex_*.dex '${taskDump.absolutePath}/' 2>/dev/null; ")
            append("  ls '${taskDump.absolutePath}'/dex_*.dex 2>/dev/null | wc -l; ")
            append("else echo NOT_FOUND; fi")
        }

        val r = ShizukuShellExecutor.exec(cmd)
        val out = (r.getString("stdout") ?: "").trim()
        val code = r.getInt("code")

        Log.i(TAG, "Shizuku 收集结果: code=$code out=${out.take(200)}")

        if (out == "NOT_FOUND") {
            return ActionResponse(
                false,
                "未找到 Xposed 模块 dump 目录（$modDump）。\n" +
                "请先：① 安装 com.ytx.dump 模块 ② NPatch 处理目标 APK 并启用模块 ③ 启动目标 App",
                mapOf("reason" to "need_npatch", "expected" to modDump)
            )
        }

        val n = out.toIntOrNull() ?: 0
        return if (n > 0) {
            // 收集到 dex -> 交给分片上传
            val uploaded = DexDumper.filterAndUpload(taskDump, taskId)
            ActionResponse(
                true,
                "已从 Xposed 模块收集 $n 个 dex，分片 ${uploaded.size} 个",
                mapOf("dex_count" to n, "dex_names" to uploaded,
                      "source" to "xposed_module")
            )
        } else {
            ActionResponse(false, "dump 目录存在但无有效 dex（模块是否已跑？）",
                mapOf("reason" to "empty_dump", "dump_dir" to modDump))
        }
    }

    /** 过滤 0 字节 + MD5（客户端侧，把 dump 结果分片写入 chunks/） */
    private fun onFilter(payload: ActionPayload): ActionResponse {
        val dumpDir = (payload.params["dex_paths"] as? List<*>)
            ?.firstOrNull()?.toString()?.let { File(it) }
        val dir = dumpDir ?: File(context.getExternalFilesDir(null), "dump")
        val uploaded = DexDumper.filterAndUpload(dir, taskId)
        return if (uploaded.isNotEmpty())
            ActionResponse(true, "过滤+分片完成", mapOf("dex_names" to uploaded))
        else
            ActionResponse(false, "过滤后无合法 dex")
    }

/**
     * 构建：把任务 dump/ 里的 DEX 装回原 APK（替换+清壳+签名）。
     *
     * ⭐ v2.0：完全本地化 —— 不再走命令桥/容器，直接在 App 内完成。
     */
    private fun onBuild(payload: ActionPayload): ActionResponse {
        val origApk = payload.params["original_apk"] as? String
            ?: return ActionResponse(false, "缺 original_apk")
        val taskDir = File(YunTuoXiuApp.CLOUD_ROOT, "tasks/$taskId")
        val dumpDir = File(taskDir, "dump")
        if (!dumpDir.isDirectory || (dumpDir.listFiles()?.isEmpty() != false)) {
            return ActionResponse(false, "任务 dump 目录为空（先本地脱壳）",
                mapOf("dump_dir" to dumpDir.absolutePath))
        }
        val dexes = com.yuntuoxiu.app.engine.LocalUnpackEngine.collectDex(dumpDir)
        if (dexes.isEmpty()) {
            return ActionResponse(false, "无可用 DEX", mapOf("fail_code" to "BUILD_NO_DEX"))
        }
        val repaired = File(taskDir, "build/repaired.apk")
        val res = com.yuntuoxiu.app.engine.LocalRepairEngine.rebuild(
            File(origApk), dexes, repaired, cleanShell = true
        ) ?: return ActionResponse(false, "本地重组失败", mapOf("fail_code" to "BUILD_FAIL"))

        val signed = File(taskDir, "build/signed.apk")
        val sr = com.yuntuoxiu.app.engine.LocalApkSigner.sign(repaired, signed, null)
        return if (sr.ok) {
            ActionResponse(true, "本地构建完成（修复+签名）",
                mapOf("out_apk" to signed.absolutePath,
                      "dex_count" to res.dexCount,
                      "removed_shell" to res.removedShell,
                      "source" to "local_engine"))
        } else {
            ActionResponse(true, "构建完成但签名失败: ${sr.detail}",
                mapOf("repaired_apk" to repaired.absolutePath,
                      "sign_error" to sr.detail))
        }
    }

    private fun onUpload(payload: ActionPayload): ActionResponse {
        val dexName = payload.params["dex_name"] as? String
        return ActionResponse(true, "分片已写入", mapOf("dex_name" to dexName))
    }
}

/**
 * LSPatch 相关适配（架构已修正）。
 *
 * ⚠️ 重要：LSPatch **没有可调用的 Android SDK**（无 Maven 依赖）。
 *   官方用法是在 PC/容器执行 `java -jar lspatch.jar`。
 *   因此 App 侧**不做打补丁**，而是：
 *     1. 容器侧（backend/Skills/lspatch_runner.py）用 lspatch.jar 生成 patched.apk
 *     2. 通过 action_payload 把 patched_apk 路径下发给 App
 *     3. App 只负责 `pm install`（见 onInstall）
 *
 * 本对象保留，用于**校验**容器下发的产物是否可用（存在性 + 基本完整性）。
 */
object LspatchHelper {

    /**
     * 校验容器下发的 patched APK 是否可用于安装。
     * （不再承担"打补丁"职责 —— 那在容器侧完成）
     */
    fun validatePatchedApk(path: String): Pair<Boolean, String> {
        return try {
            val f = java.io.File(path)
            if (!f.exists()) return false to "patched APK 不存在: $path"
            if (f.length() < 1024) return false to "patched APK 过小(可能损坏): ${f.length()}"
            // 校验 zip 魔数（APK 是 zip）
            val magic = ByteArray(4)
            f.inputStream().use { it.read(magic) }
            if (!(magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte())) {
                return false to "patched APK 非合法 zip"
            }
            true to "OK (${f.length()} bytes)"
        } catch (e: Exception) {
            false to "校验异常: ${e.message}"
        }
    }
}
