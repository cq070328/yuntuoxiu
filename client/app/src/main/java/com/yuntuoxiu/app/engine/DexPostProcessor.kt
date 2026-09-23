package com.yuntuoxiu.app.engine

import com.yuntuoxiu.app.LogStore
import java.io.File

/**
 * DexPostProcessor —— 脱壳 DEX 后处理（v2.2）
 *
 * 问题背景（实测）：
 *   `VMCore.cookieDumpDex` 走 **cookie 模式**，把 ART 内存中**每一个 DexFile 段**
 *   单独 dump 成 `cookie_<size>.dex`。结果：
 *     · 混入大量**壳自身的 stub dex**（1KB~500KB 的小碎片）
 *     · 业务 dex 与壳 dex 混在一起，顺序随机（按 size 命名）
 *     · 直接塞回 APK 会导致：类找不到 / 加载壳 stub 崩溃
 *
 * 本处理器对 dump 出的 dex 做：
 *   ① **有效性过滤**：magic 非法 / 过小 / class 数 = 0 → 丢弃
 *   ② **壳 stub 过滤**：命中壳类名特征（StubApp / Apkwrapper / TxAppEntry /
 *      secneo / qihoo / 壳 so 字符串）→ 丢弃
 *   ③ **去重**：按 (class_defs_size + 前 4KB 哈希) 去重（同段被 dump 两次时）
 *   ④ **排序**：按 class 数降序（主业务 dex 在前）→ classes.dex / classes2.dex ...
 *   ⑤ **命名**：输出类文件名统一为 classesN.dex（供替换进 APK）
 *
 * 与 DexRepairEngine 的分工：
 *   · DexRepairEngine = 单 dex 的 magic/checksum/sha1 修复
 *   · DexPostProcessor = 多 dex 的过滤/去重/排序/命名（本类）
 */
object DexPostProcessor {

    private const val TAG = "DexPostProcessor"

    /** 壳 stub 类名 / 字符串特征（出现在 dex 内 → 判为壳 dex） */
    private val STUB_MARKERS = listOf(
        "com/stub/StubApp",
        "com/qihoo/util/",
        "com/secneo/apkwrapper",
        "com/tencent/StubShell",
        "Lcom/wrapper/proxyapplication",
        "com/stub/StubApplication",
        "com/tencent/bugly/",
        "libjiagu",
        "libmetasec",
        "libnpth",
        "libshella",
        "libshell-super",
        "libDexHelper",
        "libsecexe",
    )

    /** 宿主机（云脱修）特征串 —— 用于识别并丢弃「宿主自己的 dex」 */
    private val HOST_MARKERS = listOf(
        "com/yuntuoxiu/app",
        "top/niunaijun/blackbox",
        "com/ai/assistance/operit",
        "Operit",
    )

    data class Result(
        val kept: List<File>,          // 保留（已排序）的 dex
        val dropped: List<File>,       // 丢弃的 dex
        val detail: String,
    )

    /**
     * 后处理一组 dump 出的 dex。
     *
     * @param dexFiles  原始 dump dex 列表
     * @param outDir    输出目录（打平后的 classesN.dex 写这里）
     * @param minClasses 最小 class 数（低于此判为壳 stub / 无效；默认 5）
     * @param onProgress 进度
     */
    fun process(
        dexFiles: List<File>,
        outDir: File,
        minClasses: Int = 5,
        onProgress: (String) -> Unit = {},
    ): Result {
        outDir.mkdirs()
        val kept = ArrayList<DexInfo>()
        val dropped = ArrayList<File>()

        for (dex in dexFiles) {
            if (!dex.isFile || dex.length() < 112) {
                dropped.add(dex)
                continue
            }
            val info = analyze(dex)
            if (info == null) {
                dropped.add(dex)
                continue
            }

            // 有效性：class 数过低 → 丢弃（壳 stub / 空 dex）
            if (info.classCount < minClasses) {
                onProgress("丢弃 ${dex.name}（class=${info.classCount} < $minClasses，疑似壳 stub）")
                dropped.add(dex)
                continue
            }
            // 壳 stub 特征
            if (info.stubHit != null) {
                onProgress("丢弃 ${dex.name}（命中壳特征: ${info.stubHit}）")
                dropped.add(dex)
                continue
            }
            // ⭐ v2.2：丢弃「宿主（云脱修）自己的 dex」
            //   内存扫描会把宿主 dex 一起 dump 出来（同进程），必须排除。
            if (info.hostHit != null) {
                onProgress("丢弃 ${dex.name}（宿主 dex，命中 ${info.hostHit}）")
                dropped.add(dex)
                continue
            }
            kept.add(info)
        }

        // 去重（class 数 + 前 4KB 哈希）
        val unique = LinkedHashMap<String, DexInfo>()
        for (d in kept) {
            if (!unique.containsKey(d.fingerprint)) unique[d.fingerprint] = d
            else {
                onProgress("去重：${d.file.name} 与已有 dex 重复")
                dropped.add(d.file)
            }
        }

        // 排序：class 数降序（主业务 dex 在前）
        val sorted = unique.values.sortedByDescending { it.classCount }

        // 输出为 classesN.dex
        val out = ArrayList<File>()
        var idx = 1
        for (d in sorted) {
            val name = if (idx == 1) "classes.dex" else "classes$idx.dex"
            idx++
            val dst = File(outDir, name)
            try {
                d.file.copyTo(dst, overwrite = true)
                out.add(dst)
            } catch (t: Throwable) {
                LogStore.w(TAG, "写出 $name 失败: ${t.message}")
            }
        }

        val detail = buildString {
            append("保留 ${out.size} 个 / 丢弃 ${dropped.size} 个")
            append("（有效+去重后，按 class 数排序）")
        }
        onProgress("DEX 后处理完成: $detail")
        LogStore.i(TAG, "process: $detail")
        return Result(out, dropped, detail)
    }

    // ==================== 分析单个 dex ====================

    private data class DexInfo(
        val file: File,
        val classCount: Int,
        val stubHit: String?,
        val hostHit: String?,
        val fingerprint: String,
    )

    private fun analyze(dex: File): DexInfo? {
        return try {
            val data = dex.readBytes()
            if (data.size < 112) return null
            val magic = String(data, 0, 4, Charsets.US_ASCII)
            if (magic != "dex\n" && magic != "cdex") return null

            val classCount = readU4(data, 0x60)
            // 扫描前 2MB 找壳特征
            val scanLen = minOf(data.size, 2 * 1024 * 1024)
            val text = String(data, 0, scanLen, Charsets.ISO_8859_1)
            val stubHit = STUB_MARKERS.firstOrNull { text.contains(it) }

            // ⭐ v2.2：识别「宿主（云脱修）的 dex」—— 含宿主类名特征
            val hostHit = HOST_MARKERS.firstOrNull { text.contains(it) }

            // fingerprint：class 数 + 前 4KB 的简单哈希
            val head = data.copyOfRange(0, minOf(data.size, 4096))
            val hash = head.fold(0) { acc, b -> acc * 31 + b }
            DexInfo(dex, classCount, stubHit, hostHit, "$classCount:$hash")
        } catch (t: Throwable) {
            LogStore.w(TAG, "analyze ${dex.name} 失败: ${t.message}")
            null
        }
    }

    private fun readU4(d: ByteArray, off: Int): Int {
        if (off + 4 > d.size) return 0
        return (d[off].toInt() and 0xFF) or
                ((d[off + 1].toInt() and 0xFF) shl 8) or
                ((d[off + 2].toInt() and 0xFF) shl 16) or
                ((d[off + 3].toInt() and 0xFF) shl 24)
    }
}