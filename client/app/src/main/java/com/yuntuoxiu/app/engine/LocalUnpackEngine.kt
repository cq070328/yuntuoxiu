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

    /** 本次脱壳开始时间（用于判断是否卡死） */
    @Volatile
    private var runningStartMs = 0L

    /**
     * ⭐ v2.2：深度脱壳开关（真实原 dex）。
     *
     * false（默认）：只做 cookie dump（快，但 VMP/抽取壳拿不到真实方法体）
     * true：额外开启 fixCodeItem + Hook dump，dump ART 已加载 CodeItem，
     *       还原真实方法体（慢，对 VMP/抽取壳更有效）
     */
    @Volatile
    private var deepUnpack = false

    fun setDeepUnpack(v: Boolean) {
        deepUnpack = v
        LogStore.i(TAG, "setDeepUnpack: $v")
    }

    fun isDeepUnpack(): Boolean = deepUnpack

    private fun runningSince(): String =
        if (runningStartMs <= 0) "?" else java.text.SimpleDateFormat(
            "HH:mm:ss", java.util.Locale.US).format(java.util.Date(runningStartMs))

    private fun runningElapsedSec(): Long =
        if (runningStartMs <= 0) 0 else (System.currentTimeMillis() - runningStartMs) / 1000

    /**
     * 检查引擎是否可用。
     *
     * ⚠️ v2.0 修复：Android 10+ 若 extractNativeLibs=false，
     *    so 不会解压到 nativeLibraryDir（那里为空），
     *    因此不能用「文件是否存在」判断，必须**实际尝试加载**。
     */
    fun checkAvailability(context: Context): CheckResult {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val isArm64 = abi.contains("arm64")
        val nativeDir = context.applicationInfo.nativeLibraryDir

        val problems = ArrayList<String>()
        if (!isArm64) problems.add("ABI 非 arm64-v8a（当前 $abi），引擎仅支持 arm64")

        // 方式1：nativeLibraryDir 里是否有解压的 so（旧 ROM / extractNativeLibs=true）
        val dirSoMain = File(nativeDir, "libblackdex.so").exists()
        val dirSoDump = File(nativeDir, "libblackdex_d.so").exists()

        // 方式2（权威）：尝试 System.loadLibrary，能加载即说明 so 可用（无论是否解压）
        var loadOk = false
        var loadErr: String? = null
        try {
            System.loadLibrary("blackdex")
            loadOk = true
        } catch (t: Throwable) {
            loadErr = t.message
        }

        val soMain = dirSoMain || loadOk
        val soDump = dirSoDump || loadOk // 主库能加载即视为 native 可用

        if (!soMain) {
            problems.add("libblackdex.so 加载失败" + (loadErr?.let { ": $it" } ?: "（未编入 APK）"))
        }

        return CheckResult(
            available = problems.isEmpty(),
            abi = abi,
            nativeDir = nativeDir,
            soMain = soMain,
            soDump = soDump,
            assets = listOf("empty.jar", "junit.jar", "vm.jar"),
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
            onProgress("已有脱壳任务在运行（开始于 ${runningSince()}，已 ${runningElapsedSec()}s）")
            LogStore.w(TAG, "dumpInstalled 被拒: running=true since=${runningSince()}")
            return emptyList()
        }
        running = true
        runningStartMs = System.currentTimeMillis()
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
            // ⭐ v2.2：明确记录被拒原因（runningSince 可判断是否卡死）
            onProgress("已有脱壳任务在运行（开始于 ${runningSince()}，已 ${runningElapsedSec()}s）")
            LogStore.w(TAG, "dumpFile 被拒: running=true, runningSince=${runningSince()}")
            return emptyList()
        }
        if (!apkFile.isFile) {
            onProgress("APK 不存在: ${apkFile.absolutePath}")
            LogStore.w(TAG, "dumpFile 被拒: APK 不存在 ${apkFile.absolutePath}")
            return emptyList()
        }
        running = true
        runningStartMs = System.currentTimeMillis()
        try {
            onProgress("启动本地脱壳引擎(文件): ${apkFile.name}")
            LogStore.i(TAG, "dumpFile: 开始, apk=${apkFile.absolutePath} size=${apkFile.length()} deep=$deepUnpack")
            val core = top.niunaijun.blackbox.BlackDexCore.get()

            // ⭐ v2.2：按「深度脱壳」开关动态配置引擎
            //   · deep=true  → fixCodeItem=true（dump CodeItem 真实方法体）+ hook 兜底
            //   · deep=false → 仅 cookie dump（快）
            try {
                core.setDumpOptions(deepUnpack)
                onProgress("脱壳模式: " + (if (deepUnpack) "深度（CodeItem dump）" else "标准（cookie dump）"))
            } catch (t: Throwable) {
                LogStore.w(TAG, "setDumpOptions 失败: ${t.message}")
            }

            LogStore.i(TAG, "dumpFile: 调用 dumpDex...")
            val result = core.dumpDex(apkFile)
            LogStore.i(TAG, "dumpFile: dumpDex 返回 ${if (result == null) "null" else "ok pkg=${result.packageName}"}")
            if (result == null) {
                onProgress("脱壳启动失败（沙箱安装或拉起失败）")
                return emptyList()
            }
            onProgress("沙箱已拉起，等待 DEX 落盘...")

            // ⭐ v2.0.1：沙箱启动较慢，等待时间延长到 180s，并每 10s 打印进度
            val dumpDir = File(getDumpDir(), result.packageName)
            val deadline = System.currentTimeMillis() + 180_000
            // ⭐⭐⭐ v2.2 关键修复：深度脱壳必须「等到扫描真正结束」！
            //   时序：handleDumpDex 先等 6s（壳初始化）→ memScanMultiRound 扫 6 轮×800ms≈5s
            //   总计 ≈ 11s。原「稳定 3 次(4.5s)就 break」会在扫描前退出 → 拿不到 scan_*.dex。
            //   因此深度模式下**强制最少等待 25s**。
            val minWaitMs = if (deepUnpack) 25_000L else 6_000L
            val minDeadline = System.currentTimeMillis() + minWaitMs
            var stable = 0
            var lastCount = -1
            var tick = 0
            var deadTicks = 0          // ⭐ v2.5：连续「进程已死且无产物」计数
            while (System.currentTimeMillis() < deadline) {
                val n = collectDex(dumpDir).size
                if (n > 0 && n == lastCount && System.currentTimeMillis() >= minDeadline) {
                    stable++
                    if (stable >= 3) break
                } else stable = 0
                lastCount = n
                tick++
                if (tick % 7 == 0) {
                    LogStore.i(TAG, "dumpFile: 等待中... 已有 $n 个 dex (dir=${dumpDir.absolutePath})")
                }
                // ⭐⭐⭐ v2.5 修复：:p0 进程崩溃检测 —— 避免产物恒 0 时白等 180s。
                //   实测：:p0 因 vm.apk 可写 dex 崩溃后，dump 目录永远为空，
                //   原逻辑会死等到 180s 超时（用户看到「等待中... 已有 0 个 dex」刷屏）。
                //   现：进入稳定等待期后，若连续 6 次（≈9s）既无 dex 又无 :p 进程 → 提前结束。
                if (System.currentTimeMillis() >= minDeadline && n == 0) {
                    val alive = try {
                        top.niunaijun.blackbox.BlackDexCore.get().isRunning()
                    } catch (_: Throwable) { true }
                    if (!alive) {
                        deadTicks++
                        if (deadTicks >= 6) {
                            LogStore.w(TAG, "dumpFile: :p 进程已消失且无产物 → 提前结束等待")
                            onProgress("目标进程已退出且未产出 DEX（可查看 blackbox.log 定位崩溃原因）")
                            break
                        }
                    } else deadTicks = 0
                } else deadTicks = 0
                Thread.sleep(1500)
            }
            val dexes = collectDex(dumpDir)
            LogStore.i(TAG, "dumpFile: 完成, 产出 ${dexes.size} 个 dex")
            // ⭐ v2.4：读取沙箱写入的「运行时真实入口」entry.txt（Layout Inspect 思路）
            try {
                val ef = File(dumpDir, "entry.txt")
                if (ef.isFile) {
                    val rt = ef.readText().trim()
                    if (rt.isNotBlank()) {
                        LogStore.i(TAG, "dumpFile: 运行时真实入口 = $rt")
                        onProgress("运行时真实入口: $rt（将用于替换 Manifest）")
                    }
                }
            } catch (_: Throwable) {}
            // ⭐ v2.1 P0：dump 为空时打印真实引擎目录与目录内容，便于定位（权限/写失败/无产出）
            if (dexes.isEmpty()) {
                val exists = dumpDir.exists()
                val list = dumpDir.listFiles()?.joinToString { it.name } ?: "(读取失败)"
                LogStore.e(TAG, "dumpFile: 未产出 DEX！引擎目录=${dumpDir.absolutePath} " +
                        "exists=$exists canWrite=${dumpDir.canWrite()} 内容=[$list]")
                // ⭐ v2.2：把黑盒日志尾部（含 :black startup 崩溃、ProviderCall 失败、
                //   NoSuchFieldError 等）一并回传到 UI，用户可据此定位真实失败原因，
                //   而不是只看到“未产出 DEX”。
                val diag = buildDumpFailureDiagnosis(dumpDir, result.packageName)
                onProgress("未产出 DEX：$diag")
            } else {
                // ⭐⭐⭐ v2.3：产物来源判定 —— 若全是「宿主/壳 dex」，明确提示失败原因，
                //   避免把「云脱修自己的 dex」当成脱壳成功产物（这是本版本要解决的核心问题）。
                val cls = classifyDump(dexes)
                LogStore.i(TAG, "dumpFile: 产物来源判定 = $cls")
                if (cls.realCount == 0) {
                    val diag = buildHostOnlyDiagnosis(result.packageName, cls)
                    onProgress("⚠️ 只拿到宿主/壳 dex，未拿到目标真实 DEX。$diag")
                    LogStore.e(TAG, "dumpFile: 只拿到宿主/壳 dex！$diag")
                } else {
                    onProgress("产物判定: 真实目标 dex=${cls.realCount} 个，"
                            + "宿主/壳 dex=${cls.hostCount} 个")
                }
            }
            // ⭐ v2.0：把 BlackBox 日志尾部追加到 App 日志，便于直接查看失败原因
            try {
                val bbxLog = File("/storage/emulated/0/MT2/apks/unpackcloud/logs/blackbox.log")
                if (bbxLog.isFile) {
                    val tail = bbxLog.readLines().takeLast(30).joinToString("\n")
                    LogStore.i(TAG, "=== BlackBox 日志尾部 ===\n$tail")
                }
            } catch (_: Throwable) {}
            onProgress("脱壳完成: ${dexes.size} 个 dex")
            return dexes
        } catch (t: Throwable) {
            LogStore.e(TAG, "脱壳(文件)失败: ${t.javaClass.name}: ${t.message}\n" +
                    t.stackTraceToString().take(1500))
            onProgress("脱壳异常: ${t.javaClass.simpleName}: ${t.message}")
            return emptyList()
        } finally {
            running = false
        }
    }

    /** 引擎根 dump 目录（同时写入 .nomedia 阻止媒体扫描） */
    fun getDumpDir(): File {
        // ⭐ v2.1 P0：直接采用引擎（ClientConfiguration）的权威目录，
        //   杜绝「引擎写 A、UI 读 B」导致永远数不到 dex 的问题。
        val dir = try {
            File(top.niunaijun.blackbox.BlackBoxCore.get().getDexDumpDir())
        } catch (_: Throwable) {
            // 引擎未初始化时的兜底：与 YunTuoXiuApp.dexDumpRoot() 保持一致
            YunTuoXiuApp.dexDumpRoot()
        }
        if (!dir.exists()) dir.mkdirs()
        // ⭐ v2.0：阻止媒体库扫描（dump 的 dex/资源不会被相册收录）
        ensureNoMedia(dir)
        return dir
    }

    /**
     * ⭐ v2.2：构造脱壳失败的可读诊断信息。
     *   读取 blackbox.log 尾部，提取关键失败行（startup 崩溃 / ProviderCall / NoSuchFieldError /
     *   进程未拉起 等），同时汇总引擎目录状态。
     */
    fun buildDumpFailureDiagnosis(dumpDir: File, pkg: String): String {
        val sb = StringBuilder()
        sb.append("目录=").append(dumpDir.absolutePath)
        sb.append(" exists=").append(dumpDir.exists())
        sb.append(" canWrite=").append(dumpDir.canWrite())
        try {
            val bbxLog = File("/storage/emulated/0/MT2/apks/unpackcloud/logs/blackbox.log")
            if (bbxLog.isFile) {
                val lines = bbxLog.readLines().takeLast(120)
                // 提取与本次失败强相关的关键行
                val keys = listOf(
                    "NoSuchFieldError", "ProviderCall", "initAppProcessL",
                    "startApply", "startup", "崩溃", "crash", "Exception",
                    "未拉起", "返回 null", "bad Package", "com.huawei.hwid"
                )
                val hits = lines.filter { l -> keys.any { l.contains(it) } }.takeLast(12)
                if (hits.isNotEmpty()) {
                    sb.append("\n关键日志:\n").append(hits.joinToString("\n"))
                } else {
                    sb.append("\n关键日志: (blackbox.log 无匹配行，末 5 行：")
                    sb.append(lines.takeLast(5).joinToString(" | "))
                    sb.append(")")
                }
            } else {
                sb.append("\nblackbox.log 不存在")
            }
        } catch (t: Throwable) {
            sb.append("\n读取 blackbox.log 失败: ${t.message}")
        }
        return sb.toString()
    }

    /**
     * ⭐ v2.3：产物分类结果。
     * @param realCount 疑似「目标真实业务 dex」数量（非宿主/壳 且 class 数达标）
     * @param hostCount 疑似「宿主（云脱修）/壳 dex」数量
     * @param names     全部产物文件名
     */
    data class DumpClassify(
        val realCount: Int,
        val hostCount: Int,
        val names: List<String>,
    )

    /** 宿主 / 壳 特征串（类描述符形式最精确） */
    private val HOST_OR_SHELL_MARKERS = listOf(
        "Lcom/yuntuoxiu/app", "com/yuntuoxiu/app",
        "Ltop/niunaijun/blackbox", "top/niunaijun/blackbox",
        "Lcom/ai/assistance/operit", "com/ai/assistance/operit",
        "Lcom/stub/StubApp", "Lcom/tencent/StubShell",
        "Lcom/secneo/apkwrapper", "Lcom/qihoo/util",
    )

    /**
     * ⭐ v2.3：判定一组 dump 产物中，「真实目标 dex」与「宿主/壳 dex」各有多少。
     *
     * 判定规则：
     *   · 命中宿主/壳特征（≥1 个类描述符，或 ≥2 个裸包名）→ 宿主/壳
     *   · 否则若 class 数 ≥ 300 → 真实目标 dex
     *   · 其余（碎片、stub）→ 归为宿主/壳 类（保守：不计入 real）
     */
    fun classifyDump(dexes: List<File>): DumpClassify {
        var real = 0
        var host = 0
        for (f in dexes) {
            if (!f.isFile || f.length() < 112) { host++; continue }
            val info = scanDex(f)
            if (info == null) { host++; continue }
            val (classCount, markerHits, strongHit) = info
            if (strongHit || markerHits >= 2) host++
            else if (classCount >= 500) real++             // ⭐ v2.5：与 DexPostProcessor.minClasses 对齐
            else host++   // 小碎片 / stub → 保守归为 host（不计入 real）
        }
        return DumpClassify(real, host, dexes.map { it.name })
    }

    /** 扫描 dex：返回 (class 数, 特征命中数, 是否命中强特征) */
    private fun scanDex(f: File): Triple<Int, Int, Boolean>? {
        return try {
            val bytes = f.readBytes()
            if (bytes.size < 0x64) return null
            val classCount = (bytes[0x60].toInt() and 0xFF) or
                    ((bytes[0x61].toInt() and 0xFF) shl 8) or
                    ((bytes[0x62].toInt() and 0xFF) shl 16) or
                    ((bytes[0x63].toInt() and 0xFF) shl 24)
            val scanLen = minOf(bytes.size, 8 * 1024 * 1024)
            val text = String(bytes, 0, scanLen, Charsets.ISO_8859_1)
            var hits = 0
            var strong = false
            for (m in HOST_OR_SHELL_MARKERS) {
                if (text.contains(m)) {
                    hits++
                    if (m.startsWith("L")) strong = true
                }
            }
            Triple(classCount, hits, strong)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * ⭐ v2.3：构造「只拿到宿主/壳 dex」的诊断信息。
     *
     * 核心结论：目标真实 dex 未解密（通常原因）：
     *   · 壳 Application 未构造（目标 ClassLoader 建立失败）
     *   · 壳反调试 / 签名校验触发 → 拒绝解密
     */
    fun buildHostOnlyDiagnosis(pkg: String, cls: DumpClassify): String {
        val sb = StringBuilder()
        sb.append("pkg=").append(pkg)
        sb.append(" real=").append(cls.realCount)
        sb.append(" host/shell=").append(cls.hostCount)
        sb.append(" 产物=[").append(cls.names.joinToString(",")).append("]")
        try {
            val bbxLog = File("/storage/emulated/0/MT2/apks/unpackcloud/logs/blackbox.log")
            if (bbxLog.isFile) {
                val lines = bbxLog.readLines()
                // 提取与「目标 loader 建立 / 壳解密」相关的行
                val keys = listOf(
                    "PathClassLoader", "Writable dex", "loader=",
                    "方案X", "onCreate", "application 构造完成",
                    "discard", "丢弃宿主", "壳"
                )
                val hits = lines.filter { l -> keys.any { l.contains(it) } }.takeLast(8)
                if (hits.isNotEmpty()) sb.append("\n关键日志:\n").append(hits.joinToString("\n"))
            }
        } catch (_: Throwable) {
        }
        sb.append("\n提示：多为壳反调试/签名校验未过，或目标 ClassLoader 未能建立（可检查黑盒日志中的 "
                + "Writable dex / 方案X 行）。")
        return sb.toString()
    }

    /** 在目录（及其父链）放置 .nomedia，阻止媒体扫描 */
    fun ensureNoMedia(dir: File) {
        try {
            var d: File? = dir
            var depth = 0
            while (d != null && depth < 5) {
                val nm = File(d, ".nomedia")
                if (!nm.exists()) nm.createNewFile()
                d = d.parentFile
                depth++
            }
        } catch (_: Throwable) {}
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