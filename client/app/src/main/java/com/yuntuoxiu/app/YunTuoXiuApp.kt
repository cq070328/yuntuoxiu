package com.yuntuoxiu.app
import android.app.Application
import com.yuntuoxiu.app.shizuku.ShizukuClient

/**
 * 云脱修 —— APK 云脱壳 + 云修复 客户端。
 *
 * 全局常量：与后端 config.py 对齐的工作区路径。
 */
class YunTuoXiuApp : Application() {

    companion object {
        lateinit var instance: YunTuoXiuApp
            private set

        /** Operit 公共工作区根路径（与后端 WORKSPACE_ROOT 一致） */
        const val WORKSPACE_ROOT = "/storage/emulated/0/MT2/apks"

        /** 本系统根目录（与后端 CLOUD_ROOT 一致） */
        const val CLOUD_ROOT = "$WORKSPACE_ROOT/unpackcloud"

        /** 上传目录（APP 把用户选中的 APK 复制到这里并提交创建请求） */
        const val UPLOADS_ROOT = "$CLOUD_ROOT/uploads"

        // ---- v1.6.9 补充常量（消除散落的硬编码路径）----
        /** 工具目录（apktool/apksigner/NPatch素材/脱壳模块） */
        const val TOOLS_DIR = "$WORKSPACE_ROOT/ytx-tools"

        /** 日志目录 */
        const val LOGS_DIR = "$CLOUD_ROOT/logs"

        /** 命令桥目录（APP → worker） */
        const val CMD_DIR = "$CLOUD_ROOT/cmd"

        /** 任务目录 */
        const val TASKS_DIR = "$CLOUD_ROOT/tasks"

        /** 运行日志文件（APP 侧） */
        const val APP_LOG = "$LOGS_DIR/app_client.log"

        /** worker 心跳 */
        const val WORKER_HEARTBEAT = "$LOGS_DIR/termux_heartbeat.json"

        /** 后端心跳 */
        const val BACKEND_HEARTBEAT = "$LOGS_DIR/termux_backend_heartbeat.json"

        /** token 文件（云端构建） */
        const val TOKEN_FILE = "$WORKSPACE_ROOT/yuntuoxiu-dev/token.txt"

        /** 脱壳模块 APK */
        const val DUMP_MODULE_APK = "$TOOLS_DIR/ytxdump-module.apk"

        /** NPatch 素材（metaloader 作为存在性标志） */
        const val NPATCH_ASSETS = "$TOOLS_DIR/npatch_assets/assets/lspatch/metaloader.dex"

        /** 启动命令（给用户的提示） */
        const val START_CMD = "bash $WORKSPACE_ROOT/ytx.sh start"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化 Shizuku 客户端：注册生命周期监听（断连/权限回收自动上报）
        try {
            ShizukuClient.init(this)
        } catch (t: Throwable) {
            LogStore.e("YunTuoXiuApp", "ShizukuClient.init 失败: ${t.message}")
        }
        // 【新增】App 启动即自动启动 Worker（无需手动点）
        try {
            val intent = android.content.Intent(this,
                com.yuntuoxiu.app.worker.WorkerService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            LogStore.i("YunTuoXiuApp", "已自动启动 Worker")
        } catch (t: Throwable) {
            LogStore.e("YunTuoXiuApp", "自动启动 Worker 失败: ${t.message}")
        }

        // 【v1.6.1】本地引擎自检（在后台线程，不阻塞启动）
        // ⚠️ 已移除 Termux 自动探测（Termux 现为「可选后端」，
        //    不再在启动时触发，避免无用日志 + 延迟）
        Thread {
            try {
                val ws = WORKSPACE_ROOT
                val checks = listOf(
                    "NPatch素材" to "$ws/ytx-tools/npatch_assets/assets/lspatch/metaloader.dex",
                    "脱壳模块" to "$ws/ytx-tools/ytxdump-module.apk",
                    "注入器" to "$ws/ytx_npatch_inject.sh"
                )
                val missing = checks.filter { !java.io.File(it.second).exists() }
                if (missing.isEmpty()) {
                    LogStore.i("YunTuoXiuApp", "✅ 本地引擎就绪（可全自动脱壳）")
                } else {
                    LogStore.w("YunTuoXiuApp",
                        "本地引擎缺: ${missing.joinToString("/") { it.first }}")
                }
            } catch (t: Throwable) {
                LogStore.e("YunTuoXiuApp", "本地引擎自检失败: ${t.message}")
            }
        }.start()
    }

    override fun onTerminate() {
        ShizukuClient.release()
        super.onTerminate()
    }
}
