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
                else -> ActionResponse(false, "未知 action: ${payload.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行 action ${payload.action} 失败", e)
            ActionResponse(false, e.message ?: "执行异常")
        }
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
            ActionResponse(false, "安装失败: ${ShizukuErrorCodes.describe(code)}",
                mapOf("code" to code,
                    "fail_code" to (r.getString("fail_code") ?: "DUMP_INSTALL_FAIL"),
                    "stderr" to (r.getString("stderr") ?: "")))
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
     * 构建：把任务 dump/ 里的 DEX 装回原 APK（替换+对齐+签名）。
     *
     * v1.6 新增。调容器/Termux 的 ytx_build_from_dump.sh。
     *
     * ⚠️ 注意：构建是重活（apktool/java），需容器或 Termux。
     *   本动作会通过命令桥（cmd/*.cmd）触发，由 worker 执行。
     */
    private fun onBuild(payload: ActionPayload): ActionResponse {
        val origApk = payload.params["original_apk"] as? String
            ?: return ActionResponse(false, "缺 original_apk")
        val taskDir = File(YunTuoXiuApp.CLOUD_ROOT, "tasks/$taskId")
        val dumpDir = File(taskDir, "dump")
        if (!dumpDir.isDirectory || (dumpDir.listFiles()?.isEmpty() != false)) {
            return ActionResponse(false, "任务 dump 目录为空（先收集 DEX）",
                mapOf("dump_dir" to dumpDir.absolutePath))
        }
        val outApk = File(taskDir, "build/out.apk")

        // 通过命令桥触发（worker 执行真正的构建）
        val cmdDir = File(YunTuoXiuApp.CLOUD_ROOT, "cmd").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val cmdFile = File(cmdDir, "build_${stamp}.cmd")
        cmdFile.writeText(
            "run_script\n" +
            "/storage/emulated/0/MT2/apks/ytx_build_from_dump.sh\n" +
            "$origApk\n${dumpDir.absolutePath}\n${outApk.absolutePath}\n"
        )
        Log.i(TAG, "已下发构建请求: ${cmdFile.name}")

        return ActionResponse(
            true,
            "已下发构建请求（worker 执行中）",
            mapOf("cmd" to cmdFile.name, "out" to outApk.absolutePath)
        )
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
