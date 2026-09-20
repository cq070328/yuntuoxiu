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

        /** 启动命令（v2.0 已废弃——全本地化） */
        const val START_CMD = "(v2.0 无需启动后端)"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ⭐ v2.0：初始化本地脱壳引擎（BlackBox/BlackDex）
        try {
            initUnpackEngine()
        } catch (t: Throwable) {
            LogStore.e("YunTuoXiuApp", "脱壳引擎初始化失败: ${t.message}")
        }

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

        // 【v2.0】本地引擎自检（在后台线程，不阻塞启动）
        // ⚠️ 已移除对 NPatch/脱壳模块/注入器 的检查（v2.0 全本地化，不再需要）
        Thread {
            try {
                val chk = com.yuntuoxiu.app.engine.LocalUnpackEngine.checkAvailability(this)
                if (chk.available) {
                    LogStore.i("YunTuoXiuApp", "✅ 本地引擎可用（${chk.abi}）")
                } else {
                    LogStore.w("YunTuoXiuApp",
                        "本地引擎问题: ${chk.problems.joinToString("; ")}")
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

    /**
     * ⭐ v2.0：初始化本地脱壳引擎（BlackBox / newBlackDex 移植）。
     *
     * BlackBox 需要以「宿主 App」身份初始化：
     *   - doAttachBaseContext(context, ClientConfiguration)：记录宿主包名、dump 目录等
     *   - doCreate()：启动 :black 系统服务进程、安装 IO hook 等
     */
    private fun initUnpackEngine() {
        try {
            // 先做可用性自检（native so / ABI）
            val chk = com.yuntuoxiu.app.engine.LocalUnpackEngine.checkAvailability(this)
            if (!chk.available) {
                com.yuntuoxiu.app.engine.LocalUnpackEngine.markReady(
                    false, chk.problems.joinToString("; "))
                LogStore.w("YunTuoXiuApp", "本地脱壳引擎不可用: ${chk.problems.joinToString("; ")}")
                return
            }

            val clientConfig = object : top.niunaijun.blackbox.app.configuration.ClientConfiguration() {
                override fun getHostPackageName(): String = packageName

                override fun getDexDumpDir(): String {
                    // dump 输出到公共 dump 目录，便于后续本地修复引擎读取
                    val dir = java.io.File(WORKSPACE_ROOT, "unpackcloud/dump")
                    dir.mkdirs()
                    return dir.absolutePath
                }

                // 默认开启 Hook dump + 主动调用（对抗抽取壳）；深度脱壳 A13+ 已失效，关闭
                override fun isFixCodeItem(): Boolean = false
                override fun isEnableHookDump(): Boolean = true
                override fun isAutoCallMethod(): Boolean = true
                override fun isVerifyDex(): Boolean = true
            }
            top.niunaijun.blackbox.BlackDexCore.get().doAttachBaseContext(this, clientConfig)
            top.niunaijun.blackbox.BlackDexCore.get().doCreate()

            com.yuntuoxiu.app.engine.LocalUnpackEngine.markReady(true)
            LogStore.i("YunTuoXiuApp", "✅ 本地脱壳引擎初始化完成 (${chk.abi})")
        } catch (t: Throwable) {
            com.yuntuoxiu.app.engine.LocalUnpackEngine.markReady(false, t.message)
            LogStore.w("YunTuoXiuApp", "本地脱壳引擎初始化异常（可能非主进程）: ${t.message}")
        }
    }
}
