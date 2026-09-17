package com.yuntuoxiu.app.worker

import android.content.Context
import android.util.Log
import com.yuntuoxiu.app.data.ActionPayload
import com.yuntuoxiu.app.data.ActionResponse
import com.yuntuoxiu.app.data.ActionType
import com.yuntuoxiu.app.shizuku.ShizukuClient
import com.yuntuoxiu.app.shizuku.ShizukuErrorCodes
import java.io.File

/**
 * ActionExecutor：把后端下发的 action_payload 翻译成真机操作。
 *
 * 约束（PRD 定稿）：
 *  - PRE_CHECK 只上报环境状态，禁止触发任何 dump 流水线。
 *  - 动态 dump 通过 LSPatch + Frida-Gadget 完成（无需 root）。
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

    /** 执行内存 dump：Frida-Gadget 已在目标进程内，dump 内存中的 dex */
    private fun onDump(payload: ActionPayload): ActionResponse {
        val pkg = payload.params["package"] as? String ?: return ActionResponse(false, "缺 package")
        // Frida-Gadget 由 LSPatch 注入后随 App 加载，脚本在 App 内 dump 内存 dex。
        val dumpDir = File(context.getExternalFilesDir(null), "dump/$pkg")
        if (!dumpDir.exists()) dumpDir.mkdirs()

        val dexCount = dumpDir.listFiles { f -> f.name.endsWith(".dex") }?.size ?: 0
        return if (dexCount > 0) {
            // dump 完成后立刻过滤 + 分片写入 chunks/
            val uploaded = DexDumper.filterAndUpload(dumpDir, taskId)
            ActionResponse(true, "dump 完成，产出 $dexCount 个 dex，已分片 ${uploaded.size} 个",
                mapOf("dump_dir" to dumpDir.absolutePath, "dex_names" to uploaded))
        } else {
            ActionResponse(false, "dump 无 dex 产出", mapOf("dump_dir" to dumpDir.absolutePath))
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
