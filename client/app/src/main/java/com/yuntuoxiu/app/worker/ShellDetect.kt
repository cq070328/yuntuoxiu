package com.yuntuoxiu.app.worker

import com.yuntuoxiu.app.LogStore
import java.io.File
import java.util.zip.ZipFile

/**
 * ShellDetect —— APP 内置的壳识别（Kotlin 版）。
 *
 * 移植自后端 backend/Core/shell_detect.py，逻辑对齐：
 *   1) 特征 so 名匹配
 *   2) assets 特征匹配
 *   3) DEX 启发式（极小 dex / dex 少 so 多 / magic 非法 / dex 内壳类串）
 *   4) 伪加固排除
 *
 * 输出：ShellTag 字符串 + 置信度 + 依据列表
 */
object ShellDetect {

    private const val TAG = "ShellDetect"

    data class Verdict(
        val tag: String,             // 主标签（如 NP / METASEC / NONE）
        val confidence: Double,      // 0..1
        val tagsAll: List<String>,   // 全部命中标签
        val reasons: List<String>,   // 依据
        val dexCount: Int
    )

    // ---- 特征 so（正则, 标签, 说明, 权重）----
    private val SO_SIG: List<Array<Any>> = listOf(
        // Dex-VM / 虚拟化
        arrayOf("libnmmp\\.so|libnmmvm\\.so|libdexvm\\.so", "DEX_VM", "Dex-VM 解释器 so", 0.95),
        arrayOf("libvmp\\.so|libVMP\\.so", "VMP", "VMP 虚拟化 so", 0.85),
        arrayOf("libdexvmp\\.so|libdvmp\\.so", "VMP", "DexVMP 虚拟化 so", 0.85),
        // 字节
        arrayOf("libmetasec.*\\.so", "METASEC", "libmetasec*.so(字节)", 0.90),
        arrayOf("libnpth[_.].*\\.so|libnpth\\.so", "METASEC", "libnpth*.so(字节)", 0.75),
        arrayOf("libmannorarmor\\.so|libmaparmor\\.so|libpanglearmor", "METASEC", "*armor.so(字节)", 0.60),
        arrayOf("libbdbadger\\.so|libnative_badger\\.so", "METASEC", "lib*badger.so", 0.70),
        // NP
        arrayOf("libmtprotect\\.so|libnp\\.so|libnprotect\\.so", "NP", "NP 加固 so", 0.85),
        // 360
        arrayOf("libjiagu\\.so|libjiagu_art\\.so|libjiagu_x86\\.so", "OVERALL_SHELL", "libjiagu*.so(360)", 0.90),
        arrayOf("libprotectClass\\.so|lib360\\.so", "OVERALL_SHELL", "360 组件", 0.70),
        // 梆梆
        arrayOf("libDexHelper\\.so|libDexHelper-x86\\.so", "OVERALL_SHELL", "libDexHelper.so(梆梆)", 0.90),
        arrayOf("libsecexe\\.so|libsecmain\\.so|libSecShell\\.so", "OVERALL_SHELL", "梆梆 sec*.so", 0.85),
        arrayOf("libfakejni\\.so", "OVERALL_SHELL", "梆梆 fakejni", 0.75),
        // 爱加密
        arrayOf("libmobisec.*\\.so|libmogosec.*\\.so", "OVERALL_SHELL", "爱加密 so", 0.85),
        arrayOf("libexec\\.so|libexecmain\\.so", "OVERALL_SHELL", "爱加密 exec", 0.70),
        arrayOf("libijiami.*\\.so", "OVERALL_SHELL", "libijiami*.so", 0.85),
        // 腾讯（乐固 + 御安全）
        arrayOf("libshell\\.so|libshellx\\.so|libshella.*\\.so|libshellb.*\\.so", "OVERALL_SHELL", "腾讯乐固", 0.85),
        arrayOf("libshell-super\\.so|libshellsuper\\.so", "OVERALL_SHELL", "腾讯御安全(super)", 0.90),
        arrayOf("libtosprotection.*\\.so", "OVERALL_SHELL", "腾讯御安全 tosprotection", 0.85),
        arrayOf("libtersafe2?\\.so|libGameSecurity\\.so", "OVERALL_SHELL", "腾讯御/游戏安全", 0.80),
        arrayOf("libtp\\.so|libteso\\.so|libtx\\.so", "OVERALL_SHELL", "腾讯安全组件", 0.60),
        // 企业版 / 私有化
        arrayOf("libEnterprise.*\\.so|libentershell\\.so", "OVERALL_SHELL", "企业版加固", 0.80),
        arrayOf("libprivateshell\\.so|libprivprotect\\.so", "OVERALL_SHELL", "私有化加固", 0.80),
        arrayOf("libcompanyprotect\\.so|libcorpprotect\\.so", "OVERALL_SHELL", "企业自定义加固", 0.75),
        arrayOf("libcustomshell\\.so|libcustomprotect\\.so", "OVERALL_SHELL", "定制定向加固", 0.80),
        arrayOf("libshenzhou\\.so|libszyz\\.so", "OVERALL_SHELL", "神州网安", 0.75),
        arrayOf("libhuluxia.*\\.so|libhlxshell\\.so", "OVERALL_SHELL", "葫芦娃/第三方", 0.70),
        // 其他商业壳
        arrayOf("libcloudinject\\.so", "CLOUD_INJECT", "云注入", 0.90),
        arrayOf("libAPKProtect\\.so|libapkprotect\\.so", "OVERALL_SHELL", "APKProtect", 0.80),
        arrayOf("libegg\\.so", "OVERALL_SHELL", "APKProtect(libegg)", 0.75),
        arrayOf("libkwscmm.*\\.so|libkwscpu.*\\.so", "OVERALL_SHELL", "几维安全", 0.80),
        arrayOf("libtup\\.so|libnessus\\.so", "OVERALL_SHELL", "通付盾", 0.75),
        arrayOf("libreincp\\.so|libreincp32\\.so", "OVERALL_SHELL", "通付盾特征", 0.75),
        arrayOf("libnqshield\\.so", "OVERALL_SHELL", "网秦", 0.75),
        arrayOf("libbdprotect\\.so", "OVERALL_SHELL", "百度加固", 0.75),
        arrayOf("libddog\\.so|libddog_art\\.so", "OVERALL_SHELL", "顶象", 0.75),
        arrayOf("libdexsprotect\\.so|libdexprotector\\.so", "OVERALL_SHELL", "DexProtector", 0.85),
        arrayOf("libmobprotect\\.so|libappshield\\.so", "OVERALL_SHELL", "AppShield", 0.70),
    )

    // ---- assets 特征 ----
    private val ASSET_SIG: List<Array<Any>> = listOf(
        arrayOf("assets/protected_by_np/", "NP", "assets protected_by_np", 0.95),
        arrayOf("assets/protected_by_np/ApkDex2CPro", "DEX2C", "ApkDex2CPro 标记", 0.95),
        arrayOf("assets/jiagu|assets/360|assets/libjiagu", "OVERALL_SHELL", "assets 360 特征", 0.70),
        arrayOf("assets/ijiami|assets/mobisec", "OVERALL_SHELL", "assets 爱加密", 0.75),
        arrayOf("assets/dexhelper|assets/bangcle", "OVERALL_SHELL", "assets 梆梆", 0.75),
        arrayOf("assets/.*_dex$|assets/.*dex.*\\.jar", "EXTRACT_SHELL", "assets 加密 dex", 0.60),
        arrayOf("assets/stub|assets/shell", "OVERALL_SHELL", "assets 壳桩", 0.60),
    )

    // ---- 伪加固（业务库，不当壳）----
    private val FALSE_POSITIVE = listOf(
        "libshellcommand", "libshellutils", "libprotectview",
        "libprotectactivity", "libsecurit", "libsecuritysdk",
        "libshieldgame", "libshieldui",
    )

    /** 对 APK 做壳识别（读 zip 条目名 + DEX 启发式） */
    fun detect(apkPath: String): Verdict {
        val reasons = ArrayList<String>()
        val tagsAll = ArrayList<String>()
        val scores = HashMap<String, Double>()

        fun bump(tag: String, w: Double, reason: String) {
            if ((scores[tag] ?: 0.0) < w) scores[tag] = w
            if (tag !in tagsAll) tagsAll.add(tag)
            reasons.add("[$tag] $reason")
        }

        var dexCount = 0
        try {
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries().toList().map { it.name }
                val joined = entries.joinToString("\n")

                // 1) 特征 so
                for (sig in SO_SIG) {
                    val pat = sig[0] as String
                    val tag = sig[1] as String
                    val why = sig[2] as String
                    var w = sig[3] as Double
                    val m = Regex(pat, RegexOption.IGNORE_CASE).find(joined) ?: continue
                    val matched = m.value.lowercase()
                    // 伪加固过滤
                    if (FALSE_POSITIVE.any { matched.contains(it) }) {
                        reasons.add("[跳过伪加固] $matched")
                        continue
                    }
                    // 业务性 protect 降权
                    if (matched.contains("privacy") || matched.contains("permission") ||
                        matched.contains("payment") || matched.contains("player") ||
                        matched.contains("crypto") || matched.contains("ssl") ||
                        matched.contains("bugly") || matched.contains("umeng")) {
                        w *= 0.3
                    }
                    bump(tag, w, why)
                }

                // 2) assets 特征
                for (sig in ASSET_SIG) {
                    val pat = sig[0] as String
                    if (Regex(pat, RegexOption.IGNORE_CASE).containsMatchIn(joined)) {
                        bump(sig[1] as String, sig[3] as Double, sig[2] as String)
                    }
                }

                // 3) DEX 启发式
                val dexEntries = entries.filter {
                    Regex("^classes\\d*\\.dex$").matches(File(it).name)
                }
                dexCount = dexEntries.size
                val soCount = entries.count { it.endsWith(".so") }

                // 3a) 极小 dex（需 >= 2 个 dex 才算壳入口）
                val dexSizes = dexEntries.map { n ->
                    n to (zip.getEntry(n)?.size ?: 0L)
                }
                val tiny = dexSizes.filter { it.second in 1..10239L }
                if (tiny.isNotEmpty() && dexEntries.size >= 2) {
                    bump("EXTRACT_SHELL", 0.45,
                        "极小 dex(${tiny[0].first}=${tiny[0].second}B)疑似壳入口")
                }
                // 3b) dex 少 so 多
                if (dexEntries.size <= 2 && soCount > 10) {
                    bump("OVERALL_SHELL", 0.45,
                        "dex 少(${dexEntries.size})但 so 多($soCount)疑似加固")
                }
                // 3c) 主 dex magic 非法
                if (dexEntries.isNotEmpty()) {
                    try {
                        val head = zip.getInputStream(zip.getEntry(dexEntries[0]))
                            .use { it.readBytes().take(8).toByteArray() }
                        if (!(head.size >= 4 && head[0] == 'd'.code.toByte() &&
                                    head[1] == 'e'.code.toByte() &&
                                    head[2] == 'x'.code.toByte())) {
                            bump("HEADER_ERASED", 0.80, "主 dex magic 非法疑似 header 擦除")
                        }
                    } catch (_: Throwable) {}
                }
                // 3d) dex 内壳类串（扫前 2 个 dex 的前 256KB）
                val kws = listOf(
                    "com/stub/StubApp" to "StubApp 壳类",
                    "com/qihoo/util/" to "360 壳类",
                    "com/tencent/StubShell" to "腾讯壳类",
                    "com/secneo/apkwrapper" to "梆梆壳类",
                    "Lcom/wrapper/proxyapplication" to "ProxyApplication",
                    "libjiagu" to "jiagu 字符串",
                    "libmetasec" to "metasec 字符串",
                    "libnpth" to "npth 字符串",
                )
                for (dn in dexEntries.take(2)) {
                    try {
                        val data = zip.getInputStream(zip.getEntry(dn)).use {
                            val buf = ByteArray(256 * 1024)
                            val n = it.read(buf)
                            if (n > 0) buf.copyOf(n) else ByteArray(0)
                        }
                        val s = String(data, Charsets.ISO_8859_1)
                        for ((kw, why) in kws) {
                            if (s.contains(kw)) {
                                val tag = when {
                                    why.contains("metasec") || why.contains("npth") -> "METASEC"
                                    else -> "OVERALL_SHELL"
                                }
                                bump(tag, 0.60, "dex 内壳特征串: $why")
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            LogStore.e(TAG, "解析 APK 失败: ${t.message}")
            return Verdict("UNKNOWN", 0.0, listOf("UNKNOWN"),
                listOf("APK 解析失败: ${t.message}"), 0)
        }

        // 4) 选定主标签
        if (scores.isEmpty()) {
            return Verdict("NONE", 0.5, listOf("NONE"),
                listOf("未命中任何加固特征，判定未加壳"), dexCount)
        }

        // 复合壳：NP + DEX2C → 主标签 NP
        var bestTag = scores.maxByOrNull { it.value }!!.key
        var bestConf = scores[bestTag]!!
        if (scores.containsKey("NP") && scores.containsKey("DEX2C")) {
            bestTag = "NP"
            bestConf = maxOf(bestConf, 0.95)
            reasons.add("复合判定: NP + Dex2C（主标签 NP）")
        }

        LogStore.i(TAG, "壳识别: $bestTag (${"%.2f".format(bestConf)}) dex=$dexCount")
        return Verdict(bestTag, bestConf, tagsAll, reasons, dexCount)
    }
}