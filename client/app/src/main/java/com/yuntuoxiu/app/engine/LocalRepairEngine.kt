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
     * 合并「dump 的 DEX」到「原 APK」，产出修复后的 APK。
     *
     * @param srcApk    原 APK
     * @param dexFiles  脱壳得到的 dex（按 classes.dex, classes2.dex ... 顺序排列）
     * @param outApk    输出 APK
     * @param cleanShell 是否清理壳 so/assets
     * @param onProgress 进度回调
     * @return 结果统计（null 表示失败）
     */
    fun rebuild(
        srcApk: File,
        dexFiles: List<File>,
        outApk: File,
        cleanShell: Boolean = true,
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
        var totalOut = 0L

        try {
            onProgress("打开原 APK: ${srcApk.name}")
            val zin = ZipFile(srcApk)
            val entries = zin.entries().toList()

            // 原 APK 里已有的 classes*.dex 名（用于替换判断）
            val existingDexNames = entries
                .map { it.name }
                .filter { Regex("^classes\\d*\\.dex$").matches(File(it).name) }
                .toMutableSet()

            outApk.parentFile?.mkdirs()
            val zout = ZipOutputStream(FileOutputStream(outApk))

            // ---- 1. 复制原条目（替换 dex + 清壳）----
            for (e in entries) {
                val name = e.name
                val low = name.lowercase()
                val bn = low.substringAfterLast('/')

                // 清壳判定
                if (cleanShell && isShellEntry(bn, low)) {
                    removedShell++
                    continue
                }

                // 跳过原 dex（稍后写 dump 的）
                if (Regex("^classes\\d*\\.dex$").matches(File(name).name)) {
                    continue
                }

                // 复制（保持压缩方式；so/arsc 必须 STORED）
                val data = zin.getInputStream(e).readBytes()
                val ne = ZipEntry(name)
                ne.time = e.time
                // ZipEntry 无法直接指定 STORED，需用 size/crc 让 ZipOutputStream 自动选择；
                // 这里用 setMethod 需反射/子类。简化：用自定义 StoredEntry。
                val outEntry = if (low.endsWith(".so") || low.endsWith("resources.arsc"))
                    StoredEntry(name, data) else ne
                zout.putNextEntry(outEntry)
                zout.write(data)
                zout.closeEntry()
                totalOut += data.size
            }

            // ---- 2. 写入 dump 的 DEX（重命名为 classes.dex / classesN.dex）----
            var idx = 1
            for (dex in dexFiles) {
                val dexName = if (idx == 1) "classes.dex" else "classes$idx.dex"
                idx++
                val data = dex.readBytes()
                // 校验 dex magic
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

            onProgress("重建完成: dex=$replacedDex 清壳=$removedShell 大小=${totalOut / 1024}KB")
            return Result(outApk, replacedDex, removedShell, totalOut)
        } catch (t: Throwable) {
            LogStore.e(TAG, "重建失败: ${t.message}")
            onProgress("重建失败: ${t.message}")
            return null
        }
    }

    data class Result(
        val outApk: File,
        val dexCount: Int,
        val removedShell: Int,
        val totalBytes: Long
    )

    /** 是否为壳相关条目 */
    private fun isShellEntry(bn: String, low: String): Boolean {
        if (bn.endsWith(".so")) {
            if (SHELL_SO_EXACT.contains(bn)) return true
            if (SHELL_SO_PREFIX.any { bn.startsWith(it) }) return true
        }
        if (low.contains("assets/")) {
            if (SHELL_ASSET_KEYS.any { low.contains(it) }) return true
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