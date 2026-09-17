package com.yuntuoxiu.app.data

import com.google.gson.annotations.SerializedName

/** action_payload 指令模型 */
data class ActionPayload(
    @SerializedName("seq") val seq: Int,
    @SerializedName("action") val action: String,
    @SerializedName("task_id") val taskId: String,
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("params") val params: Map<String, Any?> = emptyMap(),
    @SerializedName("expect") val expect: List<String> = listOf("ok", "detail")
)

/** 客户端回执 */
data class ActionResponse(
    val ok: Boolean,
    val detail: String = "",
    val extra: Map<String, Any?> = emptyMap()
)

/** 分片上传 manifest */
data class DexManifest(
    @SerializedName("dex_name") val dexName: String,
    @SerializedName("total_chunks") val totalChunks: Int,
    @SerializedName("file_size") val fileSize: Long,
    @SerializedName("md5") val md5: String,
    @SerializedName("chunk_size") val chunkSize: Int,
    @SerializedName("declared_at") val declaredAt: Long = 0
)

/** action 类型 */
object ActionType {
    const val PRE_CHECK = "PRE_CHECK"
    const val CLEAR_TARGET = "CLEAR_TARGET"
    const val LSPATCH_PATCH = "LSPATCH_PATCH"
    const val INSTALL_CLONE = "INSTALL_CLONE"
    const val START_TARGET = "START_TARGET"
    const val TRIGGER_PAGE = "TRIGGER_PAGE"
    const val DUMP_MEMORY = "DUMP_MEMORY"
    const val FILTER_DEX = "FILTER_DEX"
    const val UPLOAD_CHUNK = "UPLOAD_CHUNK"
    const val REPORT_STATUS = "REPORT_STATUS"
    const val CANCEL = "CANCEL"
}

/**
 * 任务状态分组（用于列表徽章）
 */
enum class TaskGroup {
    PROCESSING,   // 处理中
    SUCCESS,      // 处理成功
    FAILED,       // 处理失败
}

/**
 * 任务元数据（与后端 TaskMeta 对齐）
 */
data class TaskMetaView(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("state") val state: String = "CREATED",
    @SerializedName("idem_key") val idemKey: String = "",
    @SerializedName("source_apk") val sourceApk: String = "",
    @SerializedName("source_apk_sha256") val sourceApkSha256: String = "",
    @SerializedName("package_name") val packageName: String? = null,
    @SerializedName("version_name") val versionName: String? = null,
    @SerializedName("allow_auto_degrade") val allowAutoDegrade: Boolean = true,
    @SerializedName("client_abi") val clientAbi: String? = null,
    @SerializedName("shell_tag") val shellTag: String? = null,
    @SerializedName("fail_code") val failCode: String? = null,
    @SerializedName("artifact_status") val artifactStatus: String? = null,
    @SerializedName("handler_trace") val handlerTrace: List<Map<String, Any?>> = emptyList(),
    @SerializedName("degrade_trace") val degradeTrace: List<Map<String, Any?>> = emptyList(),
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("updated_at") val updatedAt: Long = 0
) {
    val isTerminal: Boolean
        get() = state in setOf("PRE_CHECK_FAILED", "SUCCESS", "FAILED", "CANCELLED")

    val stateLabel: String
        get() = when (state) {
            "CREATED" -> "已创建"
            "PRE_CHECKING" -> "预检中"
            "PRE_CHECK_FAILED" -> "预检失败"
            "WAIT_CLIENT" -> "等待客户端"
            "DUMPING" -> "脱壳中"
            "UPLOADING" -> "上传校验中"
            "REPAIRING" -> "修复中"
            "DEGRADING" -> "降级切换中"
            "BUILDING" -> "重打包中"
            "VERIFYING" -> "产物校验中"
            "SUCCESS" -> "成功"
            "FAILED" -> "失败"
            "CANCELLED" -> "已取消"
            else -> state
        }

    /**
     * 三状态分组（用于列表徽章）
     *  - PROCESSING：所有非终态
     *  - SUCCESS   ：SUCCESS
     *  - FAILED    ：FAILED / PRE_CHECK_FAILED / CANCELLED
     */
    val group: TaskGroup
        get() = when (state) {
            "SUCCESS" -> TaskGroup.SUCCESS
            "FAILED", "PRE_CHECK_FAILED", "CANCELLED" -> TaskGroup.FAILED
            else -> TaskGroup.PROCESSING
        }

    /** 徽章文字 */
    val groupLabel: String
        get() = when (group) {
            TaskGroup.PROCESSING -> "处理中"
            TaskGroup.SUCCESS -> "处理成功"
            TaskGroup.FAILED -> "处理失败"
        }

    /** 用于查找应用图标（优先 package_name，回退从文件名猜） */
    val lookupPackage: String?
        get() = packageName?.takeIf { it.isNotBlank() }
            ?: sourceApk.substringAfterLast('/').removeSuffix(".apk").takeIf { it.contains('.') }

    /** 显示名（优先应用名/包名，回退文件名） */
    val displayName: String
        get() = sourceApk.substringAfterLast('/').ifBlank { taskId }

    val shellLabel: String
        get() = when (shellTag) {
            "NONE" -> "未加壳"
            "OVERALL_SHELL" -> "整体壳(一代)"
            "EXTRACT_SHELL" -> "抽取壳(二代)"
            "VMP" -> "VMP(三代)"
            "DEX_VM" -> "Dex-VM"
            "DEX2C" -> "Dex2C"
            "HEADER_ERASED" -> "Header擦除"
            "NP" -> "Np加固"
            "METASEC" -> "字节metasec"
            "CLOUD_INJECT" -> "云注入"
            "UNKNOWN" -> "未知"
            else -> shellTag ?: "未识别"
        }
}

/** 任务创建请求 */
data class TaskCreateRequest(
    @SerializedName("apk_path") val apkPath: String,
    @SerializedName("package") val packageName: String = "",
    @SerializedName("allow_auto_degrade") val allowAutoDegrade: Boolean = true,
    @SerializedName("created_at") val createdAt: Long = 0
)