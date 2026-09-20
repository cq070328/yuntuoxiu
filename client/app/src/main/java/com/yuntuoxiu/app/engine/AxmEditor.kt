package com.yuntuoxiu.app.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AxmEditor —— 二进制 AndroidManifest(AXML) 解析/编辑引擎（v2.0，纯 Kotlin）
 *
 * 参考自 AXML 标准格式（aapt 的 ResourceTypes.h）：
 *
 *   AXML 文件结构：
 *     [XML Chunk Header]  (type=0x0003, headerSize=8)
 *       [String Pool Chunk]  (type=0x0001) —— 字符串池（UTF-8 或 UTF-16）
 *       [Resource Ids Chunk] (type=0x0180) —— 可省略
 *       [XML tree chunks...] —— 一系列 START_TAG / END_TAG / TEXT / CDATA
 *
 *   每个 node chunk 的 header: type(u2) headerSize(u2) size(u4)
 *
 * 本引擎提供：
 *   · 解析 → 找到 <application android:name="..."> 节点
 *   · 改写 android:name（**自动扩缩字符串池**，支持任意长度替换）
 *   · 序列化回二进制 AXML
 *
 * 这解决了「只能等长替换」的痛点。
 */
object AxmEditor {

    // ---- Chunk types ----
    private const val RES_NULL_TYPE = 0x0000
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_XML_TYPE = 0x0003

    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

    // String pool flags
    private const val SORTED_FLAG = 1 shl 0
    private const val UTF8_FLAG = 1 shl 8

    /** AXML 是否二进制 */
    fun isBinaryAxm(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val type = readU16(data, 0)
        return type == RES_XML_TYPE
    }

    /** 字符串池（解析后） */
    private class StringPool(
        val strings: MutableList<String>,
        val utf8: Boolean,
        val styleOffsets: IntArray,
        val styleIndices: IntArray,
    )

    /** 解析结果：保留原始字节布局以便回写 */
    private class AxmDoc(
        val raw: ByteArray,
        val pool: StringPool,
        // 每个 START_ELEMENT chunk 的 (offset, size, nameIdx, attrStart, attrCount, ...)
        // 我们只关心「属性值」位置，用于替换
    )

    /**
     * 查找 <application android:name="xxx"> 的属性值位置，并替换为 newName。
     *
     * @return 新 AXML 字节；null 表示失败（找不到/非二进制）
     */
    fun setApplicationName(data: ByteArray, newName: String): ByteArray? {
        if (!isBinaryAxm(data)) return null

        return try {
            val out = rewriteStringPoolAndAttrs(data) { poolIndex, oldValue ->
                // 回调：判断该字符串是否需要替换
                if (oldValue.contains("StubApp") ||
                    oldValue.contains("qihoo") ||
                    oldValue.contains("apkwrapper") ||
                    oldValue.contains("TxAppEntry")) {
                    newName
                } else null
            }
            out
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 通用重写：
     *   1) 解析 string pool
     *   2) 对每个字符串调用 mapper（返回替换值或 null）
     *   3) 重建 string pool（支持长度变化，重算 offsets/indices）
     *   4) 修正所有引用该字符串的 attribute 值（其实 attribute 存的是 pool index，
     *      index 不变 → 无需改 attribute！只需重建 pool 即可）
     *
     *   ✅ 关键洞察：AXML 里 attribute 的 value 存的是 **string pool 索引**，
     *      只要索引顺序不变，替换 pool 内字符串的**内容**就不需要动其它 chunk，
     *      但需要重算 pool 的 offsets 并调整后续所有 chunk 的**字节偏移引用**吗？
     *      → 不需要：AXML 的 chunk 之间用 size 串联，不存绝对偏移（除 root size）。
     *      因此**只需重建 string pool + 更新 root size**。
     */
    private fun rewriteStringPoolAndAttrs(
        data: ByteArray, mapper: (Int, String) -> String?
    ): ByteArray {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // ---- root header (XML type) ----
        val rootType = bb.getShort(0).toInt() and 0xFFFF
        if (rootType != RES_XML_TYPE) throw IllegalArgumentException("非 AXML")
        val rootHeaderSize = bb.getShort(2).toInt() and 0xFFFF
        val rootSize = bb.getInt(4)

        // ---- 第 1 个 chunk：String Pool ----
        var pos = rootHeaderSize
        val spType = bb.getShort(pos).toInt() and 0xFFFF
        if (spType != RES_STRING_POOL_TYPE) throw IllegalArgumentException("非 StringPool")
        val spHeaderSize = bb.getShort(pos + 2).toInt() and 0xFFFF
        val spSize = bb.getInt(pos + 4)
        val spChunkEnd = pos + spSize

        val sp = parseStringPool(data, pos)
        val utf8 = sp.utf8

        // 映射每个字符串
        var changed = false
        val newStrings = ArrayList<String>(sp.strings.size)
        for (i in sp.strings.indices) {
            val old = sp.strings[i]
            val nv = mapper(i, old)
            if (nv != null && nv != old) {
                newStrings.add(nv)
                changed = true
            } else newStrings.add(old)
        }
        if (!changed) return data

        // 重建 string pool chunk
        val newPool = buildStringPoolChunk(newStrings, utf8, spHeaderSize)

        // 拼接：rootHeader(8) + newPool + 其余 chunks
        val rest = data.copyOfRange(spChunkEnd, data.size)
        val out = ByteArrayOutputStream()
        // root header
        val rh = ByteArray(8)
        val rhb = ByteBuffer.wrap(rh).order(ByteOrder.LITTLE_ENDIAN)
        rhb.putShort(RES_XML_TYPE.toShort())
        rhb.putShort(rootHeaderSize.toShort())
        // size 稍后回填
        rhb.putInt(0)
        out.write(rh)
        out.write(newPool)
        out.write(rest)

        val result = out.toByteArray()
        // 回填 root size
        val newSize = result.size
        val szb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        szb.putInt(4, newSize)
        return result
    }

    // ---- String Pool 解析 ----

    private fun parseStringPool(data: ByteArray, pos: Int): StringPool {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = bb.getShort(pos + 2).toInt() and 0xFFFF
        val size = bb.getInt(pos + 4)
        val stringCount = bb.getInt(pos + 8)
        val styleCount = bb.getInt(pos + 12)
        val flags = bb.getInt(pos + 16)
        val stringsStart = bb.getInt(pos + 20)
        val stylesStart = bb.getInt(pos + 24)

        val utf8 = (flags and UTF8_FLAG) != 0

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = bb.getInt(pos + headerSize + i * 4)
        }
        val styleOffsets = IntArray(styleCount)
        for (i in 0 until styleCount) {
            styleOffsets[i] = bb.getInt(pos + headerSize + stringCount * 4 + i * 4)
        }

        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val off = pos + stringsStart + offsets[i]
            strings.add(if (utf8) readUtf8String(data, off) else readUtf16String(data, off))
        }

        return StringPool(strings, utf8, styleOffsets, IntArray(0))
    }

    /** 读 UTF-8 字符串（AXML 变长长度编码，双字节长度） */
    private fun readUtf8String(data: ByteArray, off: Int): String {
        var p = off
        // u16len 变长（1~2 字节）
        val (u16len, p1) = readVarLen8(data, p)
        p = p1
        // u8len 变长
        val (u8len, p2) = readVarLen8(data, p)
        p = p2
        val s = String(data, p, u8len, Charsets.UTF_8)
        p += u8len
        // 结尾 \0
        if (p < data.size && data[p] == 0.toByte()) p++
        return s
    }

    /** 读 UTF-16LE 字符串（长度 u16，含结尾） */
    private fun readUtf16String(data: ByteArray, off: Int): String {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var len = bb.getShort(off).toInt() and 0xFFFF
        // 高字节可能扩展长度（>0x7FFF）
        val len2 = bb.getShort(off + 2).toInt() and 0xFFFF
        if ((len and 0x8000) != 0) {
            len = ((len and 0x7FFF) shl 16) or len2
        }
        val charOff = off + 4
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val c = bb.getShort(charOff + i * 2).toInt() and 0xFFFF
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readVarLen8(data: ByteArray, off: Int): Pair<Int, Int> {
        var b = data[off].toInt() and 0xFF
        var val0 = b
        var p = off + 1
        if ((b and 0x80) != 0) {
            b = data[p].toInt() and 0xFF
            p++
            val0 = ((val0 and 0x7F) shl 8) or b
        }
        return val0 to p
    }

    // ---- String Pool 重建 ----

    private fun buildStringPoolChunk(
        strings: List<String>, utf8: Boolean, headerSize: Int
    ): ByteArray {
        val count = strings.size

        // 1) 编码每串（含长度前缀 + \0）
        val encoded = ArrayList<ByteArray>()
        for (s in strings) {
            encoded.add(if (utf8) encodeUtf8String(s) else encodeUtf16String(s))
        }

        // 2) offsets
        val offsets = IntArray(count)
        var cursor = 0
        for (i in 0 until count) {
            offsets[i] = cursor
            cursor += encoded[i].size
        }
        var stringDataSize = cursor
        // 对齐到 4 字节
        while (stringDataSize % 4 != 0) stringDataSize++

        // 3) header 大小（标准 28 字节）
        val actualHeaderSize = 28
        val offsetsSize = count * 4
        // styleOffsets（本实现不保留 style，写 0 个）
        val stylesSize = 0

        val stringsStart = actualHeaderSize + offsetsSize + stylesSize
        val totalSize = stringsStart + stringDataSize
        val chunk = ByteArray(totalSize)
        val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)

        bb.putShort(RES_STRING_POOL_TYPE.toShort())
        bb.putShort(actualHeaderSize.toShort())
        bb.putInt(totalSize)
        bb.putInt(count)      // stringCount
        bb.putInt(0)          // styleCount = 0
        bb.putInt(if (utf8) UTF8_FLAG else 0) // flags
        bb.putInt(stringsStart)
        bb.putInt(0)          // stylesStart

        // offsets
        for (i in 0 until count) bb.putInt(offsets[i])
        // strings
        for (i in 0 until count) {
            System.arraycopy(encoded[i], 0, chunk, stringsStart + offsets[i], encoded[i].size)
        }
        return chunk
    }

    private fun encodeUtf8String(s: String): ByteArray {
        val body = s.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        val u16len = s.length
        val u8len = body.size
        // u16len 变长
        writeVarLen8(out, u16len)
        writeVarLen8(out, u8len)
        out.write(body)
        out.write(0)
        return out.toByteArray()
    }

    private fun encodeUtf16String(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        val len = s.length
        val bb = ByteBuffer.allocate(4 + len * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
        if (len > 0x7FFF) {
            bb.putShort(((len shr 16) or 0x8000).toShort())
            bb.putShort((len and 0xFFFF).toShort())
        } else {
            bb.putShort(len.toShort())
            bb.putShort(0)
        }
        for (c in s) bb.putShort(c.code.toShort())
        bb.putShort(0)
        return bb.array()
    }

    private fun writeVarLen8(out: ByteArrayOutputStream, v: Int) {
        if (v > 0x7F) {
            out.write((v shr 8) or 0x80)
            out.write(v and 0xFF)
        } else {
            out.write(v)
        }
    }

    private fun readU16(data: ByteArray, off: Int): Int {
        return (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    }
}