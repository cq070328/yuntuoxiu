package com.yuntuoxiu.app.shizuku

/**
 * Shizuku UserService 返回码定义 + 与后端 fail_code 的映射。
 *
 * 约定：
 *  - 0 = OK
 *  - 负数 = 错误（按能力域分段）
 *  - 每个错误码都映射到后端 fail_codes.py 中已登记的 fail_code，
 *    客户端回执可直接带上，后端调度器无需转换。
 *
 * 分段：
 *    -1xxx  通用 / 环境
 *    -2xxx  安装
 *    -3xxx  启动
 *    -4xxx  卸载
 *    -5xxx  清数据 / 应用信息
 */
object ShizukuErrorCodes {

    // ---------------- 通用 ----------------
    const val OK = 0
    const val ERR_UNKNOWN = -1000
    const val ERR_SHIZUKU_NOT_RUNNING = -1001   // shizuku server 未运行
    const val ERR_PERMISSION_DENIED = -1002     // 未授权
    const val ERR_SERVICE_NOT_BOUND = -1003     // UserService 未绑定
    const val ERR_TIMEOUT = -1004               // shell 执行超时
    const val ERR_IO = -1005                    // 输入输出异常
    const val ERR_INVALID_ARGS = -1006          // 参数非法
    const val ERR_BINDER_DEAD = -1007           // binder 已死

    // ---------------- 安装 ----------------
    const val INSTALL_FAILED = -2001
    const val INSTALL_FAILED_INCOMPATIBLE = -2002   // 签名不兼容等
    const val INSTALL_FAILED_INVALID_APK = -2003    // APK 损坏

    // ---------------- 启动 ----------------
    const val START_FAILED = -3001

    // ---------------- 卸载 ----------------
    const val UNINSTALL_FAILED = -4001

    // ---------------- 清数据 / 应用信息 ----------------
    const val CLEAR_FAILED = -5001
    const val GET_INFO_FAILED = -5002

    /**
     * 客户端错误码 -> 后端 fail_code（与 backend/Core/fail_codes.py 枚举值精确对齐）。
     *
     * 映射原则：
     *  - 环境/权限问题 -> 对应鉴权与 dump 环境类 fail_code
     *  - 安装失败 -> DUMP_INSTALL_FAIL
     *  - 启动失败/服务断开 -> DUMP_CLIENT_CRASH
     *  - 超时 -> TIMEOUT_DUMP_EXEC
     *  - 其他 -> SYS_INTERNAL_ERROR
     */
    fun toBackendFailCode(code: Int): String {
        return when (code) {
            OK -> "OK"
            ERR_TIMEOUT -> "TIMEOUT_DUMP_EXEC"
            ERR_SHIZUKU_NOT_RUNNING -> "DUMP_GADGET_LOAD_FAIL"
            ERR_PERMISSION_DENIED -> "AUTH_TOKEN_INVALID"
            ERR_SERVICE_NOT_BOUND, ERR_BINDER_DEAD -> "DUMP_CLIENT_CRASH"
            ERR_IO -> "SYS_INTERNAL_ERROR"
            ERR_INVALID_ARGS -> "SYS_INTERNAL_ERROR"
            INSTALL_FAILED, INSTALL_FAILED_INCOMPATIBLE, INSTALL_FAILED_INVALID_APK ->
                "DUMP_INSTALL_FAIL"
            START_FAILED -> "DUMP_CLIENT_CRASH"
            UNINSTALL_FAILED -> "DUMP_CLIENT_CRASH"
            CLEAR_FAILED -> "DUMP_CLIENT_CRASH"
            GET_INFO_FAILED -> "SYS_INTERNAL_ERROR"
            else -> "SYS_INTERNAL_ERROR"
        }
    }

    /** 是否可重试（对应后端 fail_codes 的 retryable 语义） */
    fun isRetryable(code: Int): Boolean {
        return code in setOf(
            ERR_TIMEOUT, ERR_IO, ERR_BINDER_DEAD,
            INSTALL_FAILED, INSTALL_FAILED_INCOMPATIBLE
        )
    }

    fun describe(code: Int): String = when (code) {
        OK -> "成功"
        ERR_UNKNOWN -> "未知错误"
        ERR_SHIZUKU_NOT_RUNNING -> "Shizuku 未运行"
        ERR_PERMISSION_DENIED -> "Shizuku 未授权"
        ERR_SERVICE_NOT_BOUND -> "UserService 未绑定"
        ERR_TIMEOUT -> "Shell 执行超时"
        ERR_IO -> "IO 异常"
        ERR_INVALID_ARGS -> "参数非法"
        ERR_BINDER_DEAD -> "Binder 已断开"
        INSTALL_FAILED -> "安装失败"
        INSTALL_FAILED_INCOMPATIBLE -> "安装失败(签名不兼容)"
        INSTALL_FAILED_INVALID_APK -> "安装失败(APK 损坏)"
        START_FAILED -> "启动失败"
        UNINSTALL_FAILED -> "卸载失败"
        CLEAR_FAILED -> "清数据失败"
        GET_INFO_FAILED -> "获取应用信息失败"
        else -> "未知错误码 $code"
    }
}
