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
        // ⭐ v2.5：宿主专属裸词
        "yuntuoxiu",
        "niunaijun",
        // ⭐ v2.4：旧 Xposed 脱壳模块（com.ytx.dump）若参与产物，也属宿主侧
        "com/ytx/dump",
        "Lcom/ytx/dump",
    )

    /**
     * ⭐ v2.3：宿主 dex 判定阈值（下调）。
     *
     * 背景：v2.2 用 30 次阈值，实测宿主 10.9MB dex 因前 2MB 内宿主串计数不足
     *   而**漏判** → 宿主 dex 被当作产物上传。
     *
     * 调整：
     *   · 阈值下调到 8（宿主 dex 里 `Lcom/yuntuoxiu/app` 等描述符必然大量出现）
     *   · 扫描范围从 2MB 扩大到 8MB（覆盖更大 dex 的字符串池）
     *   · 增加「类描述符」形式（`Lcom/yuntuoxiu/app`）单独判定：命中即宿主
     */
    private const val HOST_HIT_THRESHOLD = 8

    /** 宿主 dex 的「强特征」（类描述符形式，命中即判为宿主，无需计数） */
    private val HOST_STRONG_MARKERS = listOf(
        "Lcom/yuntuoxiu/app",
        "Ltop/niunaijun/blackbox",
        "Lcom/ai/assistance/operit",
        "Lcom/ytx/dump",
    )

    /** 单个 dex 的扫描窗口（8MB，覆盖大型 dex 的字符串池） */
    private const val SCAN_WINDOW = 8 * 1024 * 1024

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
        // ⭐⭐⭐ v2.5：默认下限从 5 提到 500。
        //   实测：加固 App 的产物里混入大量「壳 stub / 内存碎片」：
        //     · 御安全壳 stub：129KB / 131 class（>5 且无特征命中 → 原逻辑会保留！）
        //     · 内存碎片：2~9 class
        //   真实业务 dex 的 class 数通常 ≫ 500，故 500 是安全下限。
        //   （若目标 App 确实很小，其真实 dex 也基本 >500；此处宁可漏留碎片，不可留壳 stub）
        minClasses: Int = 500,
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
            // ⭐ v2.3：丢弃「宿主（云脱修）自己的 dex」
            //   内存扫描会把宿主 dex 一起 dump 出来（同进程），必须排除。
            //   · 强特征（类描述符 Lcom/yuntuoxiu/app 等）命中 1 次即判宿主
            //   · 否则按出现次数超过阈值（8）判定
            if (info.hostStrongHit != null) {
                onProgress("丢弃 ${dex.name}（宿主 dex，强特征 ${info.hostStrongHit}）")
                dropped.add(dex)
                continue
            }
            if (info.hostHits >= HOST_HIT_THRESHOLD) {
                onProgress("丢弃 ${dex.name}（宿主 dex，特征命中 ${info.hostHits} 次 ≥ $HOST_HIT_THRESHOLD）")
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
        val hostHits: Int,
        val hostStrongHit: String?,
        val fingerprint: String,
    )

    private fun analyze(dex: File): DexInfo? {
        return try {
            val data = dex.readBytes()
            if (data.size < 112) return null
            val magic = String(data, 0, 4, Charsets.US_ASCII)
            if (magic != "dex\n" && magic != "cdex") return null

            val classCount = readU4(data, 0x60)
            // ⭐ v2.3：扫描窗口从 2MB 扩大到 8MB（覆盖大型 dex 的字符串池）
            val scanLen = minOf(data.size, SCAN_WINDOW)
            val text = String(data, 0, scanLen, Charsets.ISO_8859_1)
            val stubHit = STUB_MARKERS.firstOrNull { text.contains(it) }

            // ⭐ v2.3：强特征（类描述符形式）命中即宿主，无需计数
            val hostStrongHit = HOST_STRONG_MARKERS.firstOrNull { text.contains(it) }

            // 统计宿主特征串出现次数（用计数而非布尔，避免误杀）
            var hostHits = 0
            for (m in HOST_MARKERS) {
                var idx = text.indexOf(m)
                while (idx >= 0) {
                    hostHits++
                    idx = text.indexOf(m, idx + m.length)
                }
            }

            // fingerprint：class 数 + 前 4KB 的简单哈希
            val head = data.copyOfRange(0, minOf(data.size, 4096))
            val hash = head.fold(0) { acc, b -> acc * 31 + b }
            DexInfo(dex, classCount, stubHit, hostHits, hostStrongHit, "$classCount:$hash")
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