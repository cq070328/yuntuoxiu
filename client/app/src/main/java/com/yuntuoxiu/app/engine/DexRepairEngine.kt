package com.yuntuoxiu.app.engine

import com.yuntuoxiu.app.LogStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DexRepairEngine —— DEX 修复引擎（v2.0）
 *
 * 脱壳 dump 出的 DEX 常有「魔改/残缺」，替换回 APK 前需修复：
 *
 *   1) **magic 修复**：把被壳清零/改写的 magic 还原为 `dex\n035`
 *   2) **checksum 重算**：dex 的 Adler-32 校验和（header[8..12]）
 *   3) **SHA-1 签名重算**：dex 的 SHA-1 摘要（header[12..32]）
 *   4) **map_off / header_size 校验**（完整性）
 *   5) **CompactDex(cdex) 处理**：保留或转换
 *
 * 这些修复让 dump 出的 DEX 能被 ART 正常加载（否则安装后闪退）。
 */
object DexRepairEngine {

    private const val TAG = "DexRepairEngine"

    private val DEX_MAGIC = byteArrayOf(
        0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00) // "dex\n035\0"

    data class RepairResult(
        val ok: Boolean,
        val outFile: File?,
        val fixedMagic: Boolean,
        val recomputedChecksum: Boolean,
        val recomputedSha1: Boolean,
        val size: Long,
        val detail: String,
    )

    /**
     * 修复单个 DEX 文件。
     *
     * @param dexIn  输入 dex（dump 产物）
     * @param dexOut 输出（修复后）；默认覆盖 dexIn
     */
    fun repair(dexIn: File, dexOut: File = dexIn): RepairResult {
        if (!dexIn.isFile) return RepairResult(false, null, false, false, false, 0, "dex 不存在")

        return try {
            val data = dexIn.readBytes()
            if (data.size < 112) {
                return RepairResult(false, null, false, false, false, 0,
                    "dex 过小（${data.size}B）")
            }

            var fixedMagic = false
            var fixedChecksum = false
            var fixedSha1 = false

            // 1) magic 修复
            val curMagic = data.copyOfRange(0, 8)
            if (!curMagic.contentEquals(DEX_MAGIC)) {
                // 若是 cdex（CompactDex），保留
                val isCdex = curMagic[0] == 'c'.code.toByte() &&
                        curMagic[1] == 'd'.code.toByte() &&
                        curMagic[2] == 'e'.code.toByte() &&
                        curMagic[3] == 'x'.code.toByte()
                if (!isCdex) {
                    System.arraycopy(DEX_MAGIC, 0, data, 0, 8)
                    fixedMagic = true
                }
            }

            // ⭐⭐⭐ v2.5【关键修复】计算/写入顺序必须是：先 SHA-1，后 checksum。
            //   DEX 规范：
            //     · checksum(Adler-32) 位于 header[8..12]，计算范围 [12, file_size)
            //       —— 该范围**包含** SHA-1 字段（header[12..32]）！
            //     · signature(SHA-1)  位于 header[12..32]，计算范围 [32, file_size)
            //   原实现「先算 checksum 再改 SHA-1」→ SHA-1 一改，先前算出的 checksum 立即失效
            //   → 加载时报 "checksum mismatch" / 校验失败。
            //   现改为：① 先修 magic  ② 再算 SHA-1  ③ 最后算 checksum（覆盖正确值）。

            // 1) magic 修复（已在上方完成）

            // 2) SHA-1 重算（位于 header[12..32]，范围 [32, file_size)）
            val sha1 = sha1(data, 32, data.size - 32)
            val curSha1 = data.copyOfRange(12, 32)
            if (!curSha1.contentEquals(sha1)) {
                System.arraycopy(sha1, 0, data, 12, 20)
                fixedSha1 = true
            }

            // 3) ⭐ v2.5：file_size(header[0x20]) 修正（必须在 checksum 之前）。
            //   内存 dump 的 dex 常被壳篡改 file_size（如 SMZ 写垃圾值），
            //   若与真实长度不符，ART 加载会报 "file size mismatch"。
            var fixedFileSize = false
            run {
                val fileSizeOff = 0x20
                val declaredSize = readU4(data, fileSizeOff)
                if (declaredSize != data.size.toLong() &&
                    (declaredSize < 112 || declaredSize > data.size.toLong() * 4)) {
                    writeU4(data, fileSizeOff, data.size.toLong())
                    fixedFileSize = true
                    LogStore.w(TAG, "file_size $declaredSize → ${data.size}（已修正）")
                }
            }

            // 4) checksum 重算（位于 header[8..12]，范围 [12, file_size)）
            //    ⚠️ 必须在 SHA-1 / file_size 修正**之后**计算，否则覆盖的是过期值。
            val adler = adler32(data, 12, data.size - 12)
            val oldChecksum = readU4(data, 8)
            if (oldChecksum != adler) {
                writeU4(data, 8, adler)
                fixedChecksum = true
            }

            // 5) 校验 header_size / endian
            val headerSize = readU4(data, 36)
            val endianTag = readU4(data, 40)
            if (headerSize != 0x70L) {
                // 非标准 header，可能损坏
                LogStore.w(TAG, "异常 header_size=0x${headerSize.toString(16)}")
            }
            if (endianTag != 0x12345678L) {
                LogStore.w(TAG, "异常 endian_tag=0x${endianTag.toString(16)}")
            }

            dexOut.parentFile?.mkdirs()
            dexOut.writeBytes(data)

            val detail = buildString {
                append("size=${data.size / 1024}KB")
                if (fixedMagic) append(" [magic修复]")
                if (fixedChecksum) append(" [checksum重算]")
                if (fixedSha1) append(" [sha1重算]")
                if (fixedFileSize) append(" [file_size修正]")
                if (!fixedMagic && !fixedChecksum && !fixedSha1 && !fixedFileSize) append(" [无需修复]")
            }
            LogStore.i(TAG, "DEX 修复: ${dexIn.name} → $detail")
            RepairResult(true, dexOut, fixedMagic, fixedChecksum, fixedSha1,
                data.size.toLong(), detail)
        } catch (t: Throwable) {
            LogStore.e(TAG, "DEX 修复失败: ${t.javaClass.simpleName}: ${t.message}")
            RepairResult(false, null, false, false, false, 0,
                "修复失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 批量修复目录下的所有 dex。
     * @return (修复成功数, 详情)
     */
    fun repairAll(dexDir: File, outDir: File = dexDir, onProgress: (String) -> Unit = {}): Pair<Int, String> {
        if (!dexDir.isDirectory) return 0 to "目录不存在"
        outDir.mkdirs()
        val dexes = dexDir.listFiles { f -> f.name.endsWith(".dex") && f.length() > 0 } ?: return 0 to "无 dex"
        var ok = 0
        val sb = StringBuilder()
        for (d in dexes) {
            val out = File(outDir, d.name)
            val r = repair(d, out)
            if (r.ok) {
                ok++
                sb.append("· ${d.name}: ${r.detail}\n")
            } else {
                sb.append("· ${d.name}: ❌ ${r.detail}\n")
            }
            onProgress("修复 ${d.name}: ${if (r.ok) "OK" else "FAIL"}")
        }
        return ok to sb.toString()
    }

    // ==================== 校验算法 ====================

    /** Adler-32 校验和 */
    private fun adler32(data: ByteArray, off: Int, len: Int): Long {
        val MOD = 65521L
        var a = 1L
        var b = 0L
        for (i in off until (off + len)) {
            a = (a + (data[i].toInt() and 0xFF)) % MOD
            b = (b + a) % MOD
        }
        return (b shl 16) or a
    }

    /** SHA-1 摘要 */
    private fun sha1(data: ByteArray, off: Int, len: Int): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update(data, off, len)
        return md.digest()
    }

    private fun readU4(d: ByteArray, off: Int): Long {
        return (d[off].toLong() and 0xFF) or
                ((d[off + 1].toLong() and 0xFF) shl 8) or
                ((d[off + 2].toLong() and 0xFF) shl 16) or
                ((d[off + 3].toLong() and 0xFF) shl 24)
    }

    private fun writeU4(d: ByteArray, off: Int, v: Long) {
        d[off] = (v and 0xFF).toByte()
        d[off + 1] = ((v shr 8) and 0xFF).toByte()
        d[off + 2] = ((v shr 16) and 0xFF).toByte()
        d[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    /** 快速检查 dex 是否有效 */
    fun isValid(data: ByteArray): Boolean {
        if (data.size < 112) return false
        val m = String(data, 0, 4, Charsets.US_ASCII)
        return m == "dex\n" || m == "cdex"
    }
}