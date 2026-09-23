package com.yuntuoxiu.app.engine

import com.yuntuoxiu.app.LogStore
import java.io.File

/**
 * RealEntryFinder —— 真实 Application 入口探测器（v2.0）
 *
 * 脱壳后，App 的 Manifest `application:name` 往往还是**壳入口**（如 com.stub.StubApp），
 * 需要在 dump 出的 dex 里找出**真实的 Application 类**，并替换回去。
 *
 * 探测策略（按可靠性排序）：
 *   ① Manifest meta-data：壳常把真实入口写在这里（如 com.stub.StubApp.CLASSNAME）
 *   ② dex 继承扫描：找 `.super Landroid/app/Application;` 的类
 *   ③ 壳入口类内联：反查壳类里 loadClass/反射加载的类名
 *   ④ 包名启发：`<包名>.App` / `<包名>.Application` / `<包名>.MyApplication`
 */
object RealEntryFinder {

    private const val TAG = "RealEntryFinder"

    data class Found(
        val className: String?,
        val source: String,       // 来源（meta-data / dex-super / heuristic）
        val candidates: List<String>,
        val detail: String,
    )

    /** 常见壳入口类（用于判断哪些是"假的"） */
    private val STUB_ENTRIES = setOf(
        "com.stub.StubApp", "com.qihoo.util.StubApp",
        "com.secneo.apkwrapper.ApplicationWrapper",
        "com.tencent.StubShell.TxAppEntry",
        "com.tencent.bugly.beta.Beta",
    )

    /** 已知壳存放真实入口的 meta-data key */
    private val META_KEYS = listOf(
        "com.stub.StubApp.CLASSNAME",   // 360
        "com.qihoo.util.StubApp",        // 360
        "APPLICATION_CLASS_NAME",        // 通用
        "application_class_name",
        "org.chromium.content.browser.APP_CLASS_NAME",
        "com.tencent.StubShell.TxAppEntry",  // 腾讯
    )

    /**
     * 探测真实 Application 类。
     *
     * @param dexFiles   dump 出的 dex 列表
     * @param packageName 目标包名（用于启发）
     * @param manifestMeta 从 Manifest 提取的 meta-data（key→value，可空）
     */
    fun find(
        dexFiles: List<File>,
        packageName: String?,
        manifestMeta: Map<String, String> = emptyMap(),
    ): Found {
        val candidates = LinkedHashSet<String>()

        // ① Manifest meta-data
        for (k in META_KEYS) {
            val v = manifestMeta[k] ?: continue
            val cls = v.trim().trimStart('.').let {
                if (it.startsWith("L") && it.endsWith(";")) it else it
            }
            val dot = v.replace('/', '.').trim()
            if (dot.isNotBlank() && dot != "com.stub.StubApp") {
                LogStore.i(TAG, "从 meta-data 找到真实入口: $dot (key=$k)")
                return Found(dot, "meta-data($k)", listOf(dot), "meta-data 命中 $k")
            }
        }

        // ② dex 继承扫描：找 super = android/app/Application 的类
        val dexClasses = scanDexForApplicationSubclasses(dexFiles)
        candidates.addAll(dexClasses)

        if (dexClasses.isNotEmpty()) {
            // 优先选「不在壳包名下的」
            val best = dexClasses.firstOrNull { c ->
                !c.startsWith("com.stub") && !c.startsWith("com.qihoo") &&
                        !c.startsWith("android.") && !c.startsWith("androidx.")
            } ?: dexClasses.first()
            LogStore.i(TAG, "从 dex 找到真实入口: $best（候选 ${dexClasses.size}）")
            return Found(best, "dex-super", dexClasses.toList(), "dex 继承扫描命中")
        }

        // ②b ⭐ v2.2：从壳入口类的「被引用类」反查真实 Application
        //     壳 stub（如 com.tencent.StubShell.TxAppEntry）的 dex 里常内联真实入口类名。
        //     策略：在 dex 中查找「继承 Application」之外，也接受名字含 App/Application 的类。
        val appLike = scanDexForAppLikeClasses(dexFiles, packageName)
        if (appLike.isNotEmpty()) {
            candidates.addAll(appLike)
            val best = appLike.first()
            LogStore.i(TAG, "从 dex(App-like) 找到疑似入口: $best")
            return Found(best, "dex-applike", appLike, "dex App-like 类名命中")
        }

        // ③ 包名启发
        if (!packageName.isNullOrBlank()) {
            val guesses = listOf(
                "$packageName.App", "$packageName.Application", "$packageName.MyApplication",
                "$packageName.app.App", "$packageName.base.App",
            )
            candidates.addAll(guesses)
            // 在 dex 里验证哪一个真实存在
            val real = guesses.firstOrNull { g -> dexHasClass(dexFiles, g) }
            if (real != null) {
                LogStore.i(TAG, "启发命中真实入口: $real")
                return Found(real, "heuristic", guesses, "包名启发命中 $real")
            }
        }

        LogStore.w(TAG, "未能确定真实入口（候选 ${candidates.size}）")
        return Found(null, "none", candidates.toList(),
            "未找到真实 Application（需人工确认；候选：${candidates.take(5).joinToString()}）")
    }

    /** 判断某个类名是否壳入口 */
    fun isStubEntry(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        val c = className.trimStart('.')
        return STUB_ENTRIES.contains(c) ||
                c.startsWith("com.stub.") || c.startsWith("com.qihoo.") ||
                c.contains("StubApp") || c.contains("ApplicationWrapper") ||
                c.contains("TxAppEntry")
    }

    // ==================== dex 扫描 ====================

    /**
     * 扫描 dex，找「继承 android.app.Application」的类。
     * 实现：读 dex 的 class_defs，逐个解析其 superclass 指向。
     * 简化：用字符串扫描 `Lxxx;` + `.super` 关联不可靠，改为**检查类名列表 + 类型引用**。
     *
     * 这里用**字符串启发**：dex 中 `Landroid/app/Application;` 会作为 type 出现；
     * 通过 class_defs 的 superclass_idx 找指向它的类。
     */
    private fun scanDexForApplicationSubclasses(dexFiles: List<File>): List<String> {
        val result = LinkedHashSet<String>()
        for (dex in dexFiles) {
            if (!dex.isFile) continue
            try {
                val classes = parseDexClasses(dex)
                // classes: (className, superName)
                for ((name, sup) in classes) {
                    if (sup == "Landroid/app/Application;" ||
                        sup == "Landroid/app/MultiDexApplication;") {
                        // 转成点分
                        val dot = name.trimStart('L').trimEnd(';').replace('/', '.')
                        result.add(dot)
                    }
                }
            } catch (t: Throwable) {
                LogStore.w(TAG, "解析 dex 失败 ${dex.name}: ${t.message}")
            }
        }
        return result.toList()
    }

    /** 简单判断 dex 中是否含某类（字符串扫描） */
    private fun dexHasClass(dexFiles: List<File>, dotName: String): Boolean {
        val desc = "L" + dotName.replace('.', '/') + ";"
        val bytes = desc.toByteArray(Charsets.UTF_8)
        for (dex in dexFiles) {
            if (!dex.isFile) continue
            try {
                val data = dex.readBytes()
                if (indexOf(data, bytes) >= 0) return true
            } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * ⭐ v2.2：扫描 dex 中「名字像 Application 入口」的类（放宽启发）。
     *   匹配规则（按优先级）：
     *     1) 类名以包名开头 且 以 App / Application / MyApp / BaseApp 结尾
     *     2) 任意类名含 "Application"（继承关系未知）
     *   排除系统/壳类名。
     */
    private fun scanDexForAppLikeClasses(dexFiles: List<File>, packageName: String?): List<String> {
        val result = LinkedHashSet<String>()
        val suffixes = listOf("App", "Application", "MyApplication", "MyApp", "BaseApp")
        for (dex in dexFiles) {
            if (!dex.isFile) continue
            try {
                val classes = parseDexClasses(dex)
                for ((name, _) in classes) {
                    val dot = name.trimStart('L').trimEnd(';').replace('/', '.')
                    if (dot.startsWith("android.") || dot.startsWith("androidx.") ||
                        isStubEntry(dot)) continue
                    // 优先：以包名开头 + 常见后缀
                    val pkgPrefix = packageName?.let { dot.startsWith("$it.") || dot == it } ?: false
                    if (pkgPrefix && suffixes.any { dot.endsWith(it) }) {
                        result.add(dot)
                    } else if (dot.endsWith("Application") || dot.endsWith("MyApplication")) {
                        result.add(dot)
                    }
                }
            } catch (_: Throwable) {}
        }
        return result.toList()
    }

    /**
     * 解析 dex 的 class_defs，返回 (类名描述符, 父类描述符)。
     * 依据 DEX 格式：class_def_item 的 class_idx / superclass_idx → type_ids → string_ids
     *
     * ⚠️ 全部读取都带边界检查（防止损坏/截断的 dex 导致越界崩溃）。
     */
    private fun parseDexClasses(dex: File): List<Pair<String, String>> {
        val d = dex.readBytes()
        if (d.size < 112) return emptyList()

        // 校验 dex magic（dex/cdex）
        val magic = String(d, 0, 4, Charsets.US_ASCII)
        if (magic != "dex\n" && magic != "cdex") return emptyList()

        fun u4(off: Int): Int {
            if (off < 0 || off + 4 > d.size) return 0
            return (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8) or
                    ((d[off + 2].toInt() and 0xFF) shl 16) or ((d[off + 3].toInt() and 0xFF) shl 24)
        }

        val stringIdsSize = u4(0x38)
        val stringIdsOff = u4(0x3C)
        val typeIdsSize = u4(0x40)
        val typeIdsOff = u4(0x44)
        val classDefsSize = u4(0x60)
        val classDefsOff = u4(0x64)

        // 基本合法性（防越界）
        if (stringIdsOff <= 0 || stringIdsOff >= d.size) return emptyList()
        if (typeIdsOff <= 0 || typeIdsOff >= d.size) return emptyList()
        if (classDefsOff <= 0 || classDefsOff >= d.size) return emptyList()
        if (classDefsSize <= 0 || classDefsSize > 200000) return emptyList()
        // class_defs 表越界 → 截断
        val maxDefs = minOf(classDefsSize, (d.size - classDefsOff) / 32)

        fun uleb(off: Int): Pair<Int, Int> {
            var result = 0; var shift = 0; var p = off
            while (p < d.size) {
                val b = d[p].toInt() and 0xFF; p++
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
                if (shift > 28) break
            }
            return result to p
        }

        fun str(idx: Int): String {
            if (idx < 0 || idx >= stringIdsSize) return ""
            val strOff = stringIdsOff + idx * 4
            if (strOff + 4 > d.size) return ""
            val off = u4(strOff)
            if (off <= 0 || off >= d.size) return ""
            val (_, p) = uleb(off)
            var end = p
            while (end < d.size && d[end] != 0.toByte()) end++
            if (end <= p) return ""
            return String(d, p, end - p, Charsets.UTF_8)
        }

        fun type(idx: Int): String {
            if (idx < 0 || idx >= typeIdsSize) return ""
            val tOff = typeIdsOff + idx * 4
            if (tOff + 4 > d.size) return ""
            val strIdx = u4(tOff)
            return str(strIdx)
        }

        val out = ArrayList<Pair<String, String>>(maxDefs)
        for (i in 0 until maxDefs) {
            val base = classDefsOff + i * 32
            if (base + 32 > d.size) break
            val classIdx = u4(base)
            val superIdx = u4(base + 4)
            val name = type(classIdx)
            val sup = type(superIdx)
            if (name.isNotEmpty()) out.add(name to sup)
        }
        return out
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in from..(hay.size - needle.size)) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}