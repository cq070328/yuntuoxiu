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

        // ⭐ v2.0 关键：区分进程！
        //   · 主进程：完整初始化（引擎/Shizuku/Worker）
        //   · 子进程(:black/:p0...)：**必须** setContext（否则 BlackBox 静态初始化 NPE），
        //     但**不能**重复 loadLibrary/doCreate/HookManager.init（会崩）
        val proc = currentProcessName()
        if (proc != null && proc.contains(":")) {
            LogStore.i("YunTuoXiuApp", "子进程($proc) onCreate：仅设置 BlackBox 上下文")
            try {
                initBlackBoxContextOnly()
            } catch (t: Throwable) {
                LogStore.e("YunTuoXiuApp", "子进程上下文初始化失败: ${t.message}")
            }
            return
        }

        // ⚡ 主进程：启动优化，重活全部丢后台线程
        Thread(::initInBackground, "ytx-init").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }.start()
    }

    /**
     * 子进程用：只设置 BlackBox 的 Context/ClientConfiguration。
     *   ⚠️ 不加载 native、不 doCreate、不 HookManager.init
     *      （这些会与 :black 的 BlackBoxSystem.startup() 冲突）
     */
    private fun initBlackBoxContextOnly() {
        val cfg = object : top.niunaijun.blackbox.app.configuration.ClientConfiguration() {
            override fun getHostPackageName(): String = packageName
            override fun getDexDumpDir(): String {
                val dir = java.io.File(WORKSPACE_ROOT, "unpackcloud/dump")
                dir.mkdirs()
                return dir.absolutePath
            }
            override fun isFixCodeItem(): Boolean = false
            override fun isEnableHookDump(): Boolean = true
            override fun isAutoCallMethod(): Boolean = true
            override fun isVerifyDex(): Boolean = true
        }
        // 只设置上下文（内部会 mClientConfiguration.init()）
        top.niunaijun.blackbox.BlackDexCore.get().doAttachBaseContext(this, cfg)
    }

    /**
     * ⭐ v2.0 兜底：把 uploads/ 里遗留的 create_*.req.json 转成本地任务。
     *    （旧版只写请求不建任务；新版本地直接建任务）
     */
    private fun migratePendingRequests() {
        try {
            val uploads = java.io.File(CLOUD_ROOT, "uploads")
            if (!uploads.isDirectory) return
            val tasks = java.io.File(CLOUD_ROOT, "tasks")
            val logs = java.io.File(CLOUD_ROOT, "logs")
            val reqs = uploads.listFiles { f -> f.name.startsWith("create_") && f.name.endsWith(".req.json") }
                ?: return
            if (reqs.isEmpty()) return

            val gson = com.google.gson.Gson()
            for (req in reqs) {
                try {
                    val o = com.google.gson.JsonParser.parseString(req.readText()).asJsonObject
                    val apkPath = o.get("apk_path")?.asString ?: continue
                    val pkg = o.get("package")?.asString
                    val apk = java.io.File(apkPath)
                    if (!apk.isFile) continue

                    val taskId = "t_" + System.currentTimeMillis().toString(36) +
                            "_" + java.util.UUID.randomUUID().toString().substring(0, 6)
                    val now = System.currentTimeMillis()
                    val taskDir = java.io.File(tasks, taskId); taskDir.mkdirs()
                    java.io.File(taskDir, "meta").mkdirs()
                    java.io.File(taskDir, "dump").mkdirs()
                    java.io.File(taskDir, "build").mkdirs()
                    java.io.File(logs, taskId).mkdirs()

                    val meta = com.yuntuoxiu.app.data.TaskMetaView(
                        taskId = taskId, state = "CREATED", sourceApk = apkPath,
                        packageName = pkg, createdAt = now, updatedAt = now
                    )
                    java.io.File(taskDir, "meta/task_meta.json").writeText(gson.toJson(meta))
                    java.io.File(logs, "$taskId/task.log").writeText("")
                    LogStore.i("YunTuoXiuApp", "已迁移遗留请求 → 任务 $taskId ($apkPath)")
                    req.delete()
                } catch (t: Throwable) {
                    LogStore.w("YunTuoXiuApp", "迁移请求失败: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            LogStore.w("YunTuoXiuApp", "migratePendingRequests 异常: ${t.message}")
        }
    }

    /** 后台初始化（不阻塞 UI） */
    private fun initInBackground() {
        try {
            // 0. 迁移遗留的 create 请求 → 本地任务（兜底）
            migratePendingRequests()
        } catch (t: Throwable) {
            LogStore.w("YunTuoXiuApp", "迁移失败: ${t.message}")
        }
        try {
            // ⭐ v2.0：确保工作区/云脱修目录有 .nomedia（阻止相册扫描到解包资源图片）
            ensureNoMediaDirs()
        } catch (t: Throwable) {
            LogStore.w("YunTuoXiuApp", ".nomedia 创建失败: ${t.message}")
        }
        try {
            // 1. 本地脱壳引擎（loadLibrary + BlackBox 初始化，最耗时）
            initUnpackEngine()
        } catch (t: Throwable) {
            LogStore.e("YunTuoXiuApp", "脱壳引擎初始化失败: ${t.message}")
        }

        try {
            // 2. Shizuku 客户端
            ShizukuClient.init(this)
        } catch (t: Throwable) {
            LogStore.e("YunTuoXiuApp", "ShizukuClient.init 失败: ${t.message}")
        }

        try {
            // 3. 启动 Worker 前台服务
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
            // ⚠️ 只在主进程初始化（:black / :p0 等子进程跳过）
            val procName = currentProcessName()
            if (procName != null && procName.contains(":")) {
                LogStore.i("YunTuoXiuApp", "非主进程($procName)，跳过引擎初始化")
                return
            }
            LogStore.i("YunTuoXiuApp", "主进程($procName)，开始初始化引擎...")

            // 先做可用性自检（native so / ABI）
            val chk = com.yuntuoxiu.app.engine.LocalUnpackEngine.checkAvailability(this)
            LogStore.i("YunTuoXiuApp", "引擎自检: available=${chk.available} abi=${chk.abi} " +
                    "soMain=${chk.soMain} problems=${chk.problems}")
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
            LogStore.i("YunTuoXiuApp", "调用 doAttachBaseContext...")
            // ⚠️ doAttachBaseContext/doCreate 在部分 ROM 可能抛异常（如无法启动 :black 进程）
            //    但它们不是「引擎可用」的硬前提 —— 脱壳时 dumpDex 会重新走完整流程。
            //    因此这里**分别容错**：任一失败仍标记引擎可用（native 已可加载）。
            var attachOk = false
            var createOk = false
            try {
                top.niunaijun.blackbox.BlackDexCore.get().doAttachBaseContext(this, clientConfig)
                attachOk = true
                LogStore.i("YunTuoXiuApp", "doAttachBaseContext OK")
            } catch (t: Throwable) {
                LogStore.w("YunTuoXiuApp", "doAttachBaseContext 失败(可忽略): ${t.message}")
            }
            try {
                top.niunaijun.blackbox.BlackDexCore.get().doCreate()
                createOk = true
                LogStore.i("YunTuoXiuApp", "doCreate OK")
            } catch (t: Throwable) {
                LogStore.w("YunTuoXiuApp",
                    "doCreate 失败: ${t.javaClass.name}: ${t.message}\n" +
                    t.stackTraceToString().take(1500))
            }

            // native 可加载 = 引擎可用；attach/create 失败仅告警
            com.yuntuoxiu.app.engine.LocalUnpackEngine.markReady(true)
            LogStore.i("YunTuoXiuApp",
                "✅ 本地脱壳引擎就绪 (${chk.abi}) attach=$attachOk create=$createOk")
        } catch (t: Throwable) {
            com.yuntuoxiu.app.engine.LocalUnpackEngine.markReady(false, t.message)
            LogStore.e("YunTuoXiuApp",
                "本地脱壳引擎初始化异常: ${t.message}\n${t.stackTraceToString().take(800)}")
        }
    }

    /** ⭐ v2.0：确保工作区相关目录都有 .nomedia，阻止系统相册/媒体库扫描 */
    private fun ensureNoMediaDirs() {
        val dirs = listOf(
            WORKSPACE_ROOT,              // /storage/emulated/0/MT2/apks （整体）
            CLOUD_ROOT,                  // /unpackcloud
            "$CLOUD_ROOT/dump",
            "$CLOUD_ROOT/tasks",
            "$CLOUD_ROOT/uploads",
            TOOLS_DIR,                   // /ytx-tools
        )
        for (p in dirs) {
            try {
                val d = java.io.File(p)
                if (!d.exists()) d.mkdirs()
                val nm = java.io.File(d, ".nomedia")
                if (!nm.exists()) nm.createNewFile()
            } catch (_: Throwable) {}
        }
    }

    /** 获取当前进程名（全版本安全：直接读 /proc/self/cmdline） */
    private fun currentProcessName(): String? {
        return try {
            java.io.File("/proc/self/cmdline").readText().trim().trimEnd('\u0000')
        } catch (t: Throwable) {
            // 回退：API 28+ 的 getProcessName（反射调用，避免低版本编译错误）
            try {
                val m = android.app.Application::class.java.getMethod("getProcessName")
                m.invoke(this) as? String
            } catch (_: Throwable) {
                null
            }
        }
    }
}
