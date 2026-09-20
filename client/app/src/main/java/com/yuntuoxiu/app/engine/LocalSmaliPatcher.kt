package com.yuntuoxiu.app.engine

import com.yuntuoxiu.app.LogStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * LocalSmaliPatcher —— 本地「正则替换/壳桩修复」引擎（v2.0，纯 Kotlin）
 *
 * 移植自 去360_v5.py 的核心修复逻辑，**不依赖 java/apktool/终端**。
 *
 * 在 APK 层面提供：
 *   1) Manifest 修复：把加固入口（com.stub.StubApp 等）换成真实 Application
 *   2) 壳 so / assets 清理
 *   3) dex 文本级规则替换（壳类引用串替换 / 反调试常量串清理）
 *
 * 与 LocalRepairEngine 的分工：
 *   · LocalRepairEngine = 用 dump 的 dex 替换 + 清壳（基础重组）
 *   · LocalSmaliPatcher = 额外的「规则化修补」（Manifest / 反调试 / 壳串）
 */
object LocalSmaliPatcher {

    private const val TAG = "LocalSmaliPatcher"

    // ==================== 规则常量（对齐 去360_v5.py）====================

    /** 加固入口类（需替换为真实 Application） */
    private val STUB_APP_CLASSES = listOf(
        "com.stub.StubApp",
        "com.qihoo.util.StubApp",
        "com.secneo.apkwrapper.ApplicationWrapper",
        "com.tencent.StubShell.TxAppEntry",
    )

    /** 壳 so 特征（清理） */
    private val SHELL_SO = listOf(
        "libjiagu.so", "libjiagu_art.so", "libjiagu_x86.so", "libjiagu_a64.so",
        "libshell.so", "libshellx.so", "libshellsuper.so", "libshell-super.so",
        "libsecexe.so", "libsecmain.so", "libDexHelper.so",
        "libexec.so", "libexecmain.so", "libijiami.so",
        "libnmmp.so", "libnmmvm.so",
        "libmobisec.so", "libmobisecx.so",
        "libchaosvmp.so", "libddog.so", "libfdog.so",
        "libnesec.so", "libsecneo.so", "libolivesec.so",
        "libprotectClass.so", "libjgdtc.so", "libjgdtc_x86.so", "libjgdtc_a64.so",
    )

    /** 壳 so 前缀（容忍后缀） */
    private val SHELL_SO_PREFIX = listOf(
        "libshell-", "libshella-", "libshell-super.", "libjiagu",
        "libnesec", "libsecneo", "libolive", "libnagain", "librsec",
        "libjgdtc", "libprotectClass",
    )

    /** ⭐ v2.0：厂商策略表提供的清理匹配串（运行时合并） */
    private fun strategyCleanPatterns(): List<String> =
        com.yuntuoxiu.app.worker.ShellStrategies.allCleanPatterns()

    /** 壳 assets 特征 */
    private val SHELL_ASSETS = listOf(
        "0OO00l111l1l", "o0oooOO0ooOo.dat", "jiagu",
        "libjiagu", "libsecex", "ijiami", "libsecmain", "libDexHelper",
        "protected_by_np",
    )

    /** 反调试常量串（dex 内清理） */
    private val ANTI_DEBUG_STRINGS = listOf(
        "/proc/self/status", "TracerPid", "isDebuggerConnected",
        "isEmulator", "isRooted", "checkRoot", "checkEmulator",
    )

    // ==================== 结果模型 ====================

    data class PatchResult(
        val ok: Boolean,
        val outApk: File?,
        val manifestChanged: Boolean,
        val removedShellSo: Int,
        val removedShellAssets: Int,
        val antiDebugCleaned: Int,
        val detail: String,
    )

    // ==================== 主入口 ====================

    /**
     * 对 APK 执行规则化修补（Manifest + 反调试 + 壳清理）。
     *
     * @param srcApk       源 APK
     * @param outApk       输出 APK
     * @param realApp      真实 Application 类名（点分，如 com.xxx.MyApp）；
     *                     为 null 时自动从 dex/Manifest 推断
     * @param cleanManifest 是否修 Manifest（StubApp→realApp）
     * @param cleanAntiDebug 是否清理反调试常量串
     * @param keepDex      是否保留原 dex（true=只清壳/改Manifest，不换 dex）
     * @param onProgress   进度
     */
    fun patch(
        srcApk: File,
        outApk: File,
        realApp: String? = null,
        cleanManifest: Boolean = true,
        cleanAntiDebug: Boolean = true,
        keepDex: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): PatchResult {
        if (!srcApk.isFile) {
            return PatchResult(false, null, false, 0, 0, 0, "源 APK 不存在")
        }

        var manifestChanged = false
        var removedSo = 0
        var removedAssets = 0
        var antiDebugCleaned = 0

        try {
            val zin = ZipFile(srcApk)
            outApk.parentFile?.mkdirs()
            val zout = ZipOutputStream(FileOutputStream(outApk))

            val entries = zin.entries().toList()

            // 先尝试解析原 Manifest（找 StubApp 与真实 Application）
            val manifestEntry = entries.firstOrNull { it.name == "AndroidManifest.xml" }
            var manifestBytes: ByteArray? = null
            if (manifestEntry != null) {
                manifestBytes = zin.getInputStream(manifestEntry).readBytes()
            }
            val detectedApp = realApp ?: detectRealApplication(zin, entries, manifestBytes)
            onProgress("真实 Application 推断: ${detectedApp ?: "未识别"}")

            for (e in entries) {
                val name = e.name
                val low = name.lowercase()
                val bn = low.substringAfterLast('/')

                // ---- 1. 清理壳 so ----
                if (bn.endsWith(".so")) {
                    val hit = SHELL_SO.contains(bn) ||
                            SHELL_SO_PREFIX.any { bn.startsWith(it) } ||
                            strategyCleanPatterns().any { p -> p.isNotBlank() && bn.contains(p.lowercase()) }
                    if (hit) {
                        removedSo++
                        onProgress("清壳 so: $name")
                        continue
                    }
                }

                // ---- 2. 清理壳 assets ----
                if (low.contains("assets/")) {
                    val hit = SHELL_ASSETS.any { low.contains(it.lowercase()) } ||
                            strategyCleanPatterns().any { p -> p.isNotBlank() && low.contains(p.lowercase()) }
                    if (hit) {
                        removedAssets++
                        onProgress("清壳 assets: $name")
                        continue
                    }
                }

                var data = zin.getInputStream(e).readBytes()

                // ---- 3. 修 Manifest ----
                if (cleanManifest && name == "AndroidManifest.xml" && data.isNotEmpty()) {
                    val (newData, changed) = patchManifest(data, detectedApp)
                    if (changed) {
                        data = newData
                        manifestChanged = true
                        onProgress("Manifest: 入口替换为 ${detectedApp ?: "?"}")
                    }
                }

                // ---- 4. 反调试常量串清理（仅对 dex，文本级安全替换）----
                if (cleanAntiDebug && Regex("^classes\\d*\\.dex$").matches(File(name).name)) {
                    val (newData, n) = cleanAntiDebugStrings(data)
                    if (n > 0) {
                        data = newData
                        antiDebugCleaned += n
                        onProgress("$name: 清理 $n 处反调试串")
                    }
                }

                // 写回（so/arsc Stored）
                val entry = if (low.endsWith(".so") || low.endsWith("resources.arsc"))
                    StoredZipEntry(name, data, e.time) else ZipEntry(name).apply { time = e.time }
                zout.putNextEntry(entry)
                zout.write(data)
                zout.closeEntry()
            }

            zout.close()
            zin.close()

            val detail = "Manifest改=$manifestChanged 清so=$removedSo 清assets=$removedAssets 反调试=$antiDebugCleaned"
            onProgress("修补完成: $detail")
            return PatchResult(true, outApk, manifestChanged, removedSo, removedAssets,
                antiDebugCleaned, detail)
        } catch (t: Throwable) {
            LogStore.e(TAG, "修补失败: ${t.message}")
            return PatchResult(false, null, false, 0, 0, 0, "修补失败: ${t.message}")
        }
    }

    // ==================== Manifest 修补 ====================

    /**
     * 修补 Manifest：把加固入口类名替换为真实 Application。
     *
     * ⭐ v2.0：改用 AxmEditor（真正的二进制 AXML 编辑，支持任意长度替换）。
     *   优先走 AXML 引擎；失败则回退「等长替换」。
     */
    private fun patchManifest(data: ByteArray, realApp: String?): Pair<ByteArray, Boolean> {
        if (realApp == null || realApp.isBlank()) return data to false

        // 文本 Manifest（apktool 解码过）
        if (!AxmEditor.isBinaryAxm(data)) {
            val text = String(data, Charsets.UTF_8)
            var changed = false
            var out = text
            for (stub in STUB_APP_CLASSES) {
                if (out.contains(stub)) {
                    out = out.replace(stub, realApp)
                    changed = true
                }
            }
            return if (changed) out.toByteArray(Charsets.UTF_8) to true else data to false
        }

        // 二进制 AXML：走 AxmEditor（支持任意长度）
        val viaEditor = AxmEditor.setApplicationName(data, realApp)
        if (viaEditor != null && !viaEditor.contentEquals(data)) {
            return viaEditor to true
        }

        // 回退：等长替换
        val utf16 = encodeUtf16Le(realApp)
        var changed = false
        var result = data
        for (stub in STUB_APP_CLASSES) {
            val stubU16 = encodeUtf16Le(stub)
            if (stubU16.size != utf16.size) continue
            val idx = indexOf(result, stubU16)
            if (idx >= 0) {
                System.arraycopy(utf16, 0, result, idx, utf16.size)
                changed = true
            }
        }
        return result to changed
    }

    /** 从 dex/Manifest 推断真实 Application 类名。 */
    private fun detectRealApplication(
        zin: ZipFile, entries: List<ZipEntry>, manifest: ByteArray?
    ): String? {
        // 策略1：从 dex 里找继承 android.app.Application 的类（字符串扫描）
        val dexEntries = entries.filter {
            Regex("^classes\\d*\\.dex$").matches(File(it.name).name)
        }
        for (de in dexEntries.take(3)) {
            try {
                val bytes = zin.getInputStream(de).use { ins ->
                    val buf = ByteArray(minOf(2 * 1024 * 1024, de.size.toInt()))
                    var off = 0
                    while (off < buf.size) {
                        val r = ins.read(buf, off, buf.size - off)
                        if (r <= 0) break
                        off += r
                    }
                    buf.copyOf(off)
                }
                val s = String(bytes, Charsets.ISO_8859_1)
                // 匹配 .super Landroid/app/Application; 附近的类名（简化：找常见命名）
                val m = Regex("L([A-Za-z0-9_/]+/Application[A-Za-z0-9_]*);")
                    .find(s)
                if (m != null) {
                    val cls = m.groupValues[1].replace('/', '.')
                    // 排除系统 Application
                    if (!cls.startsWith("android.") && !cls.startsWith("com.stub") &&
                        !cls.startsWith("com.qihoo")) {
                        return cls
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    // ==================== 反调试串清理（dex 级文本替换）====================

    /**
     * 在 dex 字节里，把反调试常量串**替换为等长的中性串**（保长度，不破坏 dex 结构）。
     * 例如 "/proc/self/status" → "/proc/self/status" 等长替换为一个不存在的路径。
     *
     * ⚠️ 采用「等长替换」保证 dex 的 string_data 偏移/大小不变。
     */
    private fun cleanAntiDebugStrings(dex: ByteArray): Pair<ByteArray, Int> {
        val out = dex.copyOf()
        var count = 0
        for (kw in ANTI_DEBUG_STRINGS) {
            val kwBytes = kw.toByteArray(Charsets.UTF_8)
            var idx = 0
            while (true) {
                val p = indexOf(out, kwBytes, idx)
                if (p < 0) break
                // 等长替换：把首字符改成 'x'（保持长度与 UTF-8 结构）
                // 更稳妥：整体替换为 'x' 重复（等长）
                val repl = ByteArray(kwBytes.size) { 'x'.code.toByte() }
                System.arraycopy(repl, 0, out, p, repl.size)
                count++
                idx = p + kwBytes.size
            }
        }
        return out to count
    }

    // ==================== 工具 ====================

    private fun encodeUtf16Le(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (c in s) {
            out.write(c.code and 0xFF)
            out.write((c.code shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in from..(hay.size - needle.size)) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

/** ZIP STORED 条目（不压缩） */
private class StoredZipEntry(name: String, raw: ByteArray, t: Long) : ZipEntry(name) {
    init {
        method = STORED
        size = raw.size.toLong()
        time = t
        val crc = java.util.zip.CRC32()
        crc.update(raw)
        this.crc = crc.value
    }
}