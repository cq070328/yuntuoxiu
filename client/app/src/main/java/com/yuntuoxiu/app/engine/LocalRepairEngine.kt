package com.yuntuoxiu.app.engine

import com.yuntuoxiu.app.LogStore
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * LocalRepairEngine —— 本地修复引擎（v2.0，脱离终端）
 *
 * 作用：把「脱壳得到的 DEX」+「原 APK」合成一个「已清壳、可运行」的新 APK。
 *
 * 流程：
 *   1) 打开原 APK（ZipFile）
 *   2) 复制全部条目，但：
 *        · 删除加固壳 so / assets（复用 ShellDetect 特征库）
 *        · 用 dump 出的 DEX 替换 classes*.dex
 *        · .so / resources.arsc 保持 STORED
 *   3) 写出新 APK
 *
 * 不再依赖 apktool / java / 终端。
 */
object LocalRepairEngine {

    private const val TAG = "LocalRepairEngine"

    /** 壳 so 精确名（与 ShellDetect.cleanShellSo 对齐） */
    private val SHELL_SO_EXACT = setOf(
        "libjiagu.so", "libjiagu_art.so", "libjiagu_x86.so", "libjiagu_a64.so",
        "libshell.so", "libshellx.so", "libtup.so", "libtxgui.so",
        "libsecexe.so", "libsecmain.so", "libdexhelper.so",
        "libexec.so", "libexecmain.so", "libijiami.so",
        "libnmmp.so", "libnmmvm.so",
        "libmobisec.so", "libmobisecx.so",
        "libchaosvmp.so", "libddog.so", "libfdog.so",
        "libshellsuper.so", "libshell-super.so",
        "libnesec.so", "libsecneo.so", "libolivesec.so"
    )

    private val SHELL_SO_PREFIX = listOf(
        "libshell-", "libshella-", "libshell-super.",
        "libnesec", "libsecneo", "libolive", "libnagain", "librsec"
    )

    private val SHELL_ASSET_KEYS = listOf(
        "fsapk", "libjiagu", "libsecex", "ijiami",
        "libsecmain", "0oo00l111l1l", "o0oooOO0ooOo.dat", "libdexhelper"
    )

    /**
     * ⭐ v2.2：厂商策略表提供的清理匹配串（运行时合并）。
     *   覆盖 ShellStrategies 的 47 厂商（so 前缀 / assets / 精确名）。
     */
    private fun strategyPatterns(): List<String> =
        try { com.yuntuoxiu.app.worker.ShellStrategies.allCleanPatterns() }
        catch (_: Throwable) { emptyList() }

    /**
     * ⭐ v2.2：壳 so 的正则（容忍包名/版本后缀），与 ShellDetect.SO_SIG 对齐。
     */
    private val SHELL_SO_REGEX = listOf(
        Regex("^libshell-super[\\.-][^/]*\\.so$"),
        Regex("^libshella-\\d[^/]*\\.so$"),
        Regex("^libshell-super\\.so$"),
        Regex("^libshellsuper\\.so$"),
        Regex("^libnesec.*\\.so$"),
        Regex("^libsecneo.*\\.so$"),
        Regex("^libolive.*\\.so$"),
        Regex("^libjiagu.*\\.so$"),
        Regex("^libmetasec.*\\.so$"),
        Regex("^libnpth[_.].*\\.so$"),
        Regex("^libDexHelper.*\\.so$"),
        Regex("^libmobisec.*\\.so$"),
        Regex("^libijiami.*\\.so$"),
        Regex("^libvenSec.*\\.so$"),
        Regex("^libvenustech.*\\.so$"),
        Regex("^libsqlen_venus.*\\.so$"),
        Regex("^venCache.*"),
        Regex("^libxloader.*\\.so$"),
        Regex("^maindata.*"),
    )

    /**
     * ⭐ v2.2：壳 assets 的正则（容忍后缀）。
     */
    private val SHELL_ASSET_REGEX = listOf(
        Regex("assets/0OO00l111l1l.*"),
        Regex("assets/o0oooOO0ooOo\\.dat.*"),
        Regex("assets/.*venCache.*"),
        Regex("assets/.*maindata.*"),
        Regex("assets/.*tosversion.*"),
        Regex("assets/.*secure.*\\.dat$"),
    )

    /**
     * 合并「dump 的 DEX」到「原 APK」，产出修复后的 APK。
     *
     * v2.0：新增「先用 DexRepairEngine 修复 dump 的 dex」，
     *       修复后再替换进 APK（magic/checksum/sha1）。
     *
     * @param srcApk    原 APK
     * @param dexFiles  脱壳得到的 dex（按 classes.dex, classes2.dex ... 顺序排列）
     * @param outApk    输出 APK
     * @param cleanShell 是否清理壳 so/assets
     * @param repairDex  是否先修复 dex（默认 true）
     * @param onProgress 进度回调
     * @return 结果统计（null 表示失败）
     */
    fun rebuild(
        srcApk: File,
        dexFiles: List<File>,
        outApk: File,
        cleanShell: Boolean = false,   // ⭐ v2.2：默认 false（整体壳的 so 是运行必需，删了会装不上）
        repairDex: Boolean = true,
        realApp: String? = null,
        onProgress: (String) -> Unit = {}
    ): Result? {
        if (!srcApk.isFile) {
            onProgress("原 APK 不存在: ${srcApk.absolutePath}")
            return null
        }
        if (dexFiles.isEmpty()) {
            onProgress("没有可用的 DEX（先脱壳）")
            return null
        }

        var removedShell = 0
        var replacedDex = 0
        var repairedDex = 0
        var manifestChanged = false
        var realAppResolved = realApp
        var totalOut = 0L

        try {
            // ⭐ v2.2：若未显式提供 realApp，则自动探测真实 Application 入口
            if (realAppResolved.isNullOrBlank()) {
                onProgress("自动探测真实 Application 入口...")
                val found = RealEntryFinder.find(dexFiles, null)
                realAppResolved = found.className
                onProgress("真实入口: ${realAppResolved ?: "未识别"}（来源=${found.source}）")
            }
            // ⭐ v2.0：先修复所有 dex（magic/checksum/sha1）
            val finalDexes: List<File> = if (repairDex) {
                onProgress("修复 DEX（${dexFiles.size} 个）...")
                val repaired = ArrayList<File>()
                for (dex in dexFiles) {
                    val outDex = File(dex.parentFile, "fixed_${dex.name}")
                    val r = DexRepairEngine.repair(dex, outDex)
                    if (r.ok) {
                        repaired.add(outDex)
                        repairedDex++
                        onProgress("  ✓ ${dex.name}: ${r.detail}")
                    } else {
                        // 修复失败 → 用原始（不阻断）
                        repaired.add(dex)
                        onProgress("  ⚠️ ${dex.name} 修复失败，用原始: ${r.detail}")
                    }
                }
                repaired
            } else dexFiles

            onProgress("打开原 APK: ${srcApk.name}")
            val zin = ZipFile(srcApk)
            val entries = zin.entries().toList()

            outApk.parentFile?.mkdirs()
            val zout = ZipOutputStream(java.io.FileOutputStream(outApk))

            // ---- 1. 复制原条目（替换 dex + 清壳）----
            for (e in entries) {
                val name = e.name
                val low = name.lowercase()
                val bn = low.substringAfterLast('/')

                // 清壳判定（内置 + 策略表）
                if (cleanShell && isShellEntry(bn, low)) {
                    removedShell++
                    continue
                }

                // 跳过原 dex（稍后写 dump 的）
                if (Regex("^classes\\d*\\.dex$").matches(File(name).name)) {
                    continue
                }

                var data = zin.getInputStream(e).readBytes()

                // ⭐ v2.2：替换 Manifest 里的壳入口为真实 Application
                if (!realAppResolved.isNullOrBlank() && name == "AndroidManifest.xml" && data.isNotEmpty()) {
                    val patched = LocalSmaliPatcher.patchManifestEntry(data, realAppResolved)
                    if (patched != null && !patched.contentEquals(data)) {
                        data = patched
                        manifestChanged = true
                        onProgress("Manifest: 入口已替换为 $realAppResolved")
                    }
                }

                val outEntry = if (low.endsWith(".so") || low.endsWith("resources.arsc"))
                    StoredEntry(name, data) else ZipEntry(name).apply { time = e.time }
                zout.putNextEntry(outEntry)
                zout.write(data)
                zout.closeEntry()
                totalOut += data.size
            }

            // ---- 2. 写入（已修复的）DEX ----
            var idx = 1
            for (dex in finalDexes) {
                val dexName = if (idx == 1) "classes.dex" else "classes$idx.dex"
                idx++
                val data = dex.readBytes()
                if (!looksLikeDex(data)) {
                    onProgress("跳过非法 dex: ${dex.name}")
                    continue
                }
                val ne = ZipEntry(dexName)
                ne.time = dex.lastModified()
                zout.putNextEntry(ne)
                zout.write(data)
                zout.closeEntry()
                replacedDex++
                totalOut += data.size
            }

            zout.close()
            zin.close()

            onProgress("重建完成: dex=$replacedDex(修复$repairedDex) 清壳=$removedShell 入口=$realAppResolved 大小=${totalOut / 1024}KB")
            return Result(outApk, replacedDex, removedShell, totalOut, manifestChanged, realAppResolved)
        } catch (t: Throwable) {
            LogStore.e(TAG, "重建失败: ${t.javaClass.simpleName}: ${t.message}")
            onProgress("重建失败: ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
    }

    data class Result(
        val outApk: File,
        val dexCount: Int,
        val removedShell: Int,
        val totalBytes: Long,
        val manifestChanged: Boolean = false,
        val realApp: String? = null,
    )

    /** 是否为壳相关条目 */
    private fun isShellEntry(bn: String, low: String): Boolean {
        if (bn.endsWith(".so")) {
            if (SHELL_SO_EXACT.contains(bn)) return true
            if (SHELL_SO_PREFIX.any { bn.startsWith(it) }) return true
            // ⭐ v2.2：正则（容忍包名/版本后缀，如 libshell-super.<pkg>.so）
            if (SHELL_SO_REGEX.any { it.matches(bn) }) return true
            // ⭐ v2.2：厂商策略表（47 厂商）
            if (strategyPatterns().any { p -> p.isNotBlank() && bn.contains(p.lowercase()) }) return true
        }
        if (low.contains("assets/")) {
            if (SHELL_ASSET_KEYS.any { low.contains(it) }) return true
            // ⭐ v2.2：assets 正则
            if (SHELL_ASSET_REGEX.any { it.containsMatchIn(low) }) return true
            // ⭐ v2.2：厂商策略表 assets
            if (strategyPatterns().any { p -> p.isNotBlank() && low.contains(p.lowercase()) }) return true
        }
        return false
    }

    /** dex 魔数校验（dex\n 或 cdex） */
    private fun looksLikeDex(data: ByteArray): Boolean {
        if (data.size < 8) return false
        // "dex\n" 或 "cdex"
        val isDex = data[0] == 'd'.code.toByte() && data[1] == 'e'.code.toByte() &&
                data[2] == 'x'.code.toByte() && data[3] == '\n'.code.toByte()
        val isCdex = data[0] == 'c'.code.toByte() && data[1] == 'd'.code.toByte() &&
                data[2] == 'e'.code.toByte() && data[3] == 'x'.code.toByte()
        return isDex || isCdex
    }

    /**
     * 读取 dex 的类数量（用于诊断/修复判断）。
     * ⚠️ 纯字节解析（不依赖 dexlib2 API），更稳：
     *    dex_header.class_defs_size 位于偏移 0x60（96）。
     */
    fun dexClassCount(dexFile: File): Int {
        return try {
            val head = ByteArray(112)
            dexFile.inputStream().use { ins ->
                if (ins.read(head) < 112) return -1
            }
            // 校验 magic
            val magic = String(head, 0, 4, Charsets.US_ASCII)
            if (magic != "dex\n" && magic != "cdex") return -1
            // class_defs_size: offset 96 (0x60), 小端 u4
            readU4LE(head, 96)
        } catch (t: Throwable) {
            -1
        }
    }

    /** 读取小端 u4 */
    private fun readU4LE(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    /** 合并/去重多个 dex 的名称统计 */
    fun describeDexes(dexes: List<File>): String {
        return dexes.joinToString("\n") { f ->
            "· ${f.name}  ${f.length() / 1024}KB  类数=${dexClassCount(f)}"
        }
    }
}

/**
 * ZIP STORED 条目（不压缩），用于 .so / resources.arsc。
 */
private class StoredEntry(name: String, private val raw: ByteArray) : ZipEntry(name) {
    init {
        method = STORED
        size = raw.size.toLong()
        val crc = java.util.zip.CRC32()
        crc.update(raw)
        this.crc = crc.value
    }
}