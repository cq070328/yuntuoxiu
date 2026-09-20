package com.yuntuoxiu.app.engine

import android.content.Context
import com.yuntuoxiu.app.LogStore
import com.yuntuoxiu.app.YunTuoXiuApp
import java.io.File

/**
 * LocalUnpackEngine —— 本地脱壳引擎封装（v2.0）
 *
 * 底层：移植自 newBlackDex 的 BlackBox 虚拟化框架（top.niunaijun.blackbox.*）
 * 能力：
 *   · Cookie 模式内存 dump（主路径）
 *   · Hook 模式 dump（hook ClassLinker::LoadClass，A16 适配）
 *   · 主动调用（对抗函数抽取壳）
 *
 * 输出目录：<WORKSPACE>/unpackcloud/dump/<包名>/
 *   cookie_<size>.dex / hook_<size>.dex
 *
 * ⚠️ 与旧版（Xposed 模块 com.ytx.dump）的区别：
 *   旧版依赖外部 Xposed 模块 + NPatch；本引擎在 App 内直接运行 BlackBox 沙箱，
 *   把目标 APK 装进沙箱并启动，从 ART 内存提取 DEX，**无需外部模块/终端**。
 */
object LocalUnpackEngine {

    private const val TAG = "LocalUnpackEngine"

    /** 引擎是否已就绪（native so 是否加载成功） */
    @Volatile
    private var ready = false

    /** 引擎初始化错误信息（供 UI 展示） */
    @Volatile
    var lastError: String? = null
        private set

    /** 当前是否正在脱壳 */
    @Volatile
    var running = false
        private set

    /**
     * 检查引擎是否可用（不触发初始化，仅探测 native 库存在性）。
     * 用于环境自检。
     */
    fun checkAvailability(context: Context): CheckResult {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val isArm64 = abi.contains("arm64")

        // native 库是否随 APK 打包（ApplicationInfo.nativeLibraryDir）
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val soMain = File(nativeDir, "libblackdex.so")
        val soDump = File(nativeDir, "libblackdex_d.so")

        // assets 运行时依赖
        val assets = listOf("empty.jar", "junit.jar", "vm.jar").map { it }

        val problems = ArrayList<String>()
        if (!isArm64) problems.add("ABI 非 arm64-v8a（当前 $abi），引擎仅支持 arm64")
        if (!soMain.exists()) problems.add("缺少 libblackdex.so（native 未编入）")
        if (!soDump.exists()) problems.add("缺少 libblackdex_d.so")

        return CheckResult(
            available = problems.isEmpty(),
            abi = abi,
            nativeDir = nativeDir,
            soMain = soMain.exists(),
            soDump = soDump.exists(),
            assets = assets,
            problems = problems
        )
    }

    data class CheckResult(
        val available: Boolean,
        val abi: String,
        val nativeDir: String,
        val soMain: Boolean,
        val soDump: Boolean,
        val assets: List<String>,
        val problems: List<String>
    )

    /**
     * 标记引擎就绪（由 YunTuoXiuApp 初始化成功后调用）。
     */
    fun markReady(ok: Boolean, err: String? = null) {
        ready = ok
        lastError = err
        LogStore.i(TAG, "引擎状态: ready=$ok err=$err")
    }

    fun isReady(): Boolean = ready

    /**
     * 对已安装的包脱壳。
     *
     * @param ctx         调用上下文
     * @param packageName 目标包名
     * @param onProgress  进度回调（消息）
     * @return 产出的 dex 文件列表（失败为空列表）
     */
    fun dumpInstalled(ctx: Context, packageName: String,
                      onProgress: (String) -> Unit = {}): List<File> {
        if (running) {
            onProgress("已有脱壳任务在运行")
            return emptyList()
        }
        running = true
        try {
            onProgress("启动本地脱壳引擎: $packageName")
            val core = top.niunaijun.blackbox.BlackDexCore.get()
            val result = core.dumpDex(packageName)
            if (result == null) {
                onProgress("脱壳启动失败（沙箱安装或拉起失败）")
                return emptyList()
            }
            onProgress("沙箱已拉起，等待 DEX 落盘...")

            // 轮询等待 dump 产出（最长 120s）
            val dumpDir = File(getDumpDir(), result.packageName)
            val deadline = System.currentTimeMillis() + 120_000
            var stable = 0
            var lastCount = -1
            while (System.currentTimeMillis() < deadline) {
                val n = collectDex(dumpDir).size
                if (n > 0 && n == lastCount) {
                    stable++
                    if (stable >= 3) break
                } else {
                    stable = 0
                }
                lastCount = n
                Thread.sleep(1500)
            }

            val dexes = collectDex(dumpDir)
            onProgress("脱壳完成: ${dexes.size} 个 dex -> ${dumpDir.absolutePath}")
            return dexes
        } catch (t: Throwable) {
            LogStore.e(TAG, "脱壳失败: ${t.message}")
            onProgress("脱壳异常: ${t.message}")
            return emptyList()
        } finally {
            running = false
        }
    }

    /**
     * 对本地 APK 文件脱壳（先装进沙箱）。
     */
    fun dumpFile(ctx: Context, apkFile: File,
                 onProgress: (String) -> Unit = {}): List<File> {
        if (running) {
            onProgress("已有脱壳任务在运行")
            return emptyList()
        }
        if (!apkFile.isFile) {
            onProgress("APK 不存在: ${apkFile.absolutePath}")
            return emptyList()
        }
        running = true
        try {
            onProgress("启动本地脱壳引擎(文件): ${apkFile.name}")
            val core = top.niunaijun.blackbox.BlackDexCore.get()
            val result = core.dumpDex(apkFile)
            if (result == null) {
                onProgress("脱壳启动失败（沙箱安装或拉起失败）")
                return emptyList()
            }
            onProgress("沙箱已拉起，等待 DEX 落盘...")

            val dumpDir = File(getDumpDir(), result.packageName)
            val deadline = System.currentTimeMillis() + 120_000
            var stable = 0
            var lastCount = -1
            while (System.currentTimeMillis() < deadline) {
                val n = collectDex(dumpDir).size
                if (n > 0 && n == lastCount) {
                    stable++
                    if (stable >= 3) break
                } else stable = 0
                lastCount = n
                Thread.sleep(1500)
            }
            val dexes = collectDex(dumpDir)
            onProgress("脱壳完成: ${dexes.size} 个 dex")
            return dexes
        } catch (t: Throwable) {
            LogStore.e(TAG, "脱壳(文件)失败: ${t.message}")
            onProgress("脱壳异常: ${t.message}")
            return emptyList()
        } finally {
            running = false
        }
    }

    /** 引擎根 dump 目录 */
    fun getDumpDir(): File {
        val dir = File(YunTuoXiuApp.CLOUD_ROOT, "dump")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 收集目录下所有 *.dex（递归） */
    fun collectDex(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<File>()
        dir.walkTopDown().forEach { f ->
            if (f.isFile && f.name.endsWith(".dex") && f.length() > 0) out.add(f)
        }
        return out
    }

    /** dex 产出统计（供 UI / 回执） */
    fun summarize(dexes: List<File>): Map<String, Any?> {
        val total = dexes.sumOf { it.length() }
        return mapOf(
            "count" to dexes.size,
            "total_bytes" to total,
            "names" to dexes.map { it.name },
            "paths" to dexes.map { it.absolutePath }
        )
    }
}