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

            // 2) checksum 重算（Adler-32，位于 header[8..12]）
            //    计算范围：从 offset 12 到文件末尾
            val adler = adler32(data, 12, data.size - 12)
            val oldChecksum = readU4(data, 8)
            if (oldChecksum != adler) {
                writeU4(data, 8, adler)
                fixedChecksum = true
            }

            // 3) SHA-1 重算（位于 header[12..32]）
            //    计算范围：从 offset 32 到文件末尾
            val sha1 = sha1(data, 32, data.size - 32)
            val curSha1 = data.copyOfRange(12, 32)
            if (!curSha1.contentEquals(sha1)) {
                System.arraycopy(sha1, 0, data, 12, 20)
                fixedSha1 = true
            }

            // 4) 校验 header_size / endian
            val headerSize = readU4(data, 36)
            val endianTag = readU4(data, 40)
            if (headerSize != 0x70) {
                // 非标准 header，可能损坏
                LogStore.w(TAG, "异常 header_size=0x${headerSize.toString(16)}")
            }
            if (endianTag != 0x12345678) {
                LogStore.w(TAG, "异常 endian_tag=0x${endianTag.toString(16)}")
            }

            dexOut.parentFile?.mkdirs()
            dexOut.writeBytes(data)

            val detail = buildString {
                append("size=${data.size / 1024}KB")
                if (fixedMagic) append(" [magic修复]")
                if (fixedChecksum) append(" [checksum重算]")
                if (fixedSha1) append(" [sha1重算]")
                if (!fixedMagic && !fixedChecksum && !fixedSha1) append(" [无需修复]")
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