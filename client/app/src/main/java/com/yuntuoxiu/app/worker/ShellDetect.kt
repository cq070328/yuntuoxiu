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
        val vendor: String?,         // ⭐ 真实厂商名（如 "360加固" / "腾讯御安全"）
        val confidence: Double,      // 0..1
        val tagsAll: List<String>,   // 全部命中标签
        val reasons: List<String>,   // 依据
        val dexCount: Int
    )

    /** 内部 tag 到中文壳名（未命中厂商特征时的兜底显示） */
    private fun tagLabel(tag: String): String {
        return when (tag) {
            "OVERALL_SHELL" -> "整体加固"
            "EXTRACT_SHELL" -> "抽取壳"
            "VMP" -> "VMP虚拟化"
            "DEX_VM" -> "Dex-VM"
            "DEX2C" -> "Dex2C"
            "METASEC" -> "字节加固"
            "SECSHELL" -> "腾讯御安全"
            "NP" -> "NP加固"
            "HEADER_ERASED" -> "Header擦除"
            "CLOUD_INJECT" -> "云注入"
            "NONE" -> "未加壳"
            "UNKNOWN" -> "未知"
            else -> tag
        }
    }

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
        arrayOf("libshell\\.so|libshellx\\.so|libshellb.*\\.so", "OVERALL_SHELL", "腾讯乐固", 0.85),
        // ⭐ SecShell（腾讯御安全，实测: 爱作业 5.2.5）
        //   文件名常带包名/版本后缀，正则需容忍任意后缀
        arrayOf("libshell-super[\\.-][^/]*\\.so", "SECSHELL", "腾讯御安全/SecShell(super)", 0.92),
        arrayOf("libshellsuper\\.so", "SECSHELL", "SecShell(super)", 0.90),
        arrayOf("libshella-\\d[^/]*\\.so", "SECSHELL", "SecShell 版本指纹", 0.88),
        arrayOf("libshell-super\\.so", "SECSHELL", "腾讯御安全(super)", 0.90),
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
        // ⭐ SecShell 强指纹（实测: 爱作业 5.2.5）
        arrayOf("assets/0OO00l111l1l", "SECSHELL", "SecShell 强指纹", 0.90),
        arrayOf("assets/o0oooOO0ooOo\\.dat", "SECSHELL", "SecShell 加密数据", 0.88),
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
        "libshieldgame", "libshieldui"
    )

    /** 对 APK 做壳识别（读 zip 条目名 + DEX 启发式） */
    fun detect(apkPath: String): Verdict {
        val reasons = ArrayList<String>()
        val tagsAll = ArrayList<String>()
        val scores = HashMap<String, Double>()
        // ⭐ 真实厂商名（在 try 块内赋值，函数尾部使用，故在函数作用域声明）
        var matchedVendorNameOuter: String? = null
        var matchedVendorsOuter: List<String> = emptyList()

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

                // 2b) ⭐ v2.0：扩展特征库（47 厂商，全维度：so + assets + lib）
                //     命中任一 → 判定该厂商（权重 0.85）
                val matchedVendors = ArrayList<String>()
                for (v in ShellSignatures.VENDORS) {
                    var hitName: String? = null

                    // 2b-1: so 名（lib/<abi>/xxx.so）
                    for (so in v.soNames) {
                        if (entries.any {
                                val n = it.substringAfterLast('/')
                                n.equals(so, ignoreCase = true)
                            }) {
                            hitName = so; break
                        }
                    }
                    // 2b-2: lib 目录名（含 .a / .so）
                    if (hitName == null) {
                        for (ln in v.libNames) {
                            if (entries.any {
                                    val n = it.substringAfterLast('/')
                                    n.equals(ln, ignoreCase = true)
                                }) {
                                hitName = ln; break
                            }
                        }
                    }
                    // 2b-3: assets 名
                    if (hitName == null) {
                        for (a in v.assetNames) {
                            if (entries.any {
                                    val n = it.substringAfterLast('/')
                                    n.equals(a, ignoreCase = true) ||
                                            it.equals("assets/$a", true)
                                }) {
                                hitName = a; break
                            }
                        }
                    }
                    if (hitName != null) {
                        matchedVendors.add("${v.vendor}($hitName)")
                        if (matchedVendorNameOuter == null) matchedVendorNameOuter = v.vendor
                        bump(v.tag, 0.85, "${v.vendor} 特征: $hitName")
                    }
                }
                if (matchedVendors.isNotEmpty()) {
                    reasons.add("命中厂商: " + matchedVendors.joinToString(", "))
                }

                // 2c) ⭐ v2.0：厂商策略表的模糊匹配（so 名前缀/子串）
                //     覆盖「特征库精确名没命中，但名称含厂商指纹」的情况
                for (st in com.yuntuoxiu.app.worker.ShellStrategies.ALL) {
                    for (pat in st.cleanSoPatterns) {
                        if (pat.isBlank()) continue
                        val p = pat.lowercase()
                        if (entries.any {
                                val n = it.substringAfterLast('/').lowercase()
                                n.endsWith(".so") && n.contains(p)
                            }) {
                            if (matchedVendorNameOuter == null) matchedVendorNameOuter = st.vendor
                            bump(st.tag, 0.70, "${st.vendor} 模糊特征: $pat")
                            break
                        }
                    }
                }

                // 3) DEX 启发式
                val dexEntries = entries.filter {
                    Regex("^classes\\d*\\.dex$").matches(File(it).name)
                }
                dexCount = dexEntries.size
                val soCount = entries.count { it.endsWith(".so") }

                // 3a) 极小 dex（v1.6.3 修正：避免误报）
                //
                //   旧规则：只要有 1 个 <10KB 的 dex + dex 数 >= 2 → 判壳
                //     ❌ 误报严重（正常多 dex 应用常有小 dex，如资源/注解）
                //
                //   新规则（更准）：
                //     · 主 dex（classes.dex）必须「小」（<200KB）—— 壳引导代码
                //     · 且存在 >= 2 个「大小相同的小 dex」—— 占位 stub
                //   只有「主 dex 小 + stub 占位」才是一代抽取壳的强特征
                val dexSizes = dexEntries.map { n ->
                    n to (zip.getEntry(n)?.size ?: 0L)
                }
                val mainDexSize = dexSizes
                    .firstOrNull { it.first == "classes.dex" }?.second ?: Long.MAX_VALUE

                // 小 dex（< 100KB，排除正常业务 dex）
                val tiny = dexSizes.filter { it.second in 1..102399L }
                // 是否有 >= 2 个「大小相同」的小 dex（stub 占位特征）
                val tinySizeGroups = tiny.groupBy { it.second }
                    .filter { it.value.size >= 2 }
                val hasDupStub = tinySizeGroups.isNotEmpty()

                if (mainDexSize < 204800L && hasDupStub) {
                    // 强特征：主 dex 小 + 重复 stub
                    bump("EXTRACT_SHELL", 0.72,
                        "主 dex 小(${mainDexSize}B) + ${tinySizeGroups.size} 组等大小 stub")
                } else if (mainDexSize < 51200L && dexEntries.size >= 2) {
                    // 中等特征：主 dex 极小（<50KB）
                    bump("EXTRACT_SHELL", 0.55,
                        "主 dex 极小(${mainDexSize}B)疑似壳引导")
                }
                // 否则不判（避免误报）
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
                    "libnpth" to "npth 字符串"
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
            return Verdict("UNKNOWN", "未知", 0.0, listOf("UNKNOWN"),
                listOf("APK 解析失败: ${t.message}"), 0)
        }

        // 4) 选定主标签
        if (scores.isEmpty()) {
            return Verdict("NONE", "未加壳", 0.5, listOf("NONE"),
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

        // ⭐ 真实厂商名：优先用厂商特征命中的 vendor，否则用 tag 中文名兜底
        val vendor = matchedVendorNameOuter ?: tagLabel(bestTag)
        LogStore.i(TAG, "壳识别: $bestTag / $vendor (${"%.2f".format(bestConf)}) dex=$dexCount")
        return Verdict(bestTag, vendor, bestConf, tagsAll, reasons, dexCount)
    }

    /**
     * v1.6 新增：去壳清理（纯 Kotlin，无需 java/python）。
     *
     * 功能：
     *   · 删除壳的 so（按已知特征库）
     *   · 删除壳的 assets
     *   · 重写 APK（保持 .so/arsc Stored + 对齐交给后续步骤）
     *
     * @return 删除的条目数
     */
    fun cleanShellSo(srcApk: String, dstApk: String): Int {
        // 壳 so / assets 特征（与 ytx-unpack-clean.py 对齐）
        val soExact = listOf(
            "libjiagu.so", "libjiagu_art.so", "libjiagu_x86.so", "libjiagu_a64.so",
            "libshell.so", "libshellx.so", "libtup.so", "libtxgui.so",
            "libsecexe.so", "libsecmain.so", "libDexHelper.so",
            "libexec.so", "libexecmain.so", "libijiami.so",
            "libnmmp.so", "libnmmvm.so",
            "libmobisec.so", "libmobisecx.so",
            "libchaosvmp.so", "libddog.so", "libfdog.so",
            "libshellsuper.so", "libshell-super.so",
            "libnesec.so", "libsecneo.so", "libolivesec.so"
        )
        // 前缀匹配（容忍包名/版本后缀，如 libshell-super.<pkg>.so / libshella-4.6.2.2.so）
        val soPrefix = listOf(
            "libshell-", "libshella-", "libshell-super.",
            "libnesec", "libsecneo", "libolive",
            "libnagain", "librsec"
        )
        val assetKeys = listOf("fsapk", "libjiagu", "libsecex", "ijiami",
                               "libsecmain", "0OO00l111l1l", "o0oooOO0ooOo.dat",
                               "libDexHelper")

        var removed = 0
        try {
            val zin = java.util.zip.ZipFile(srcApk)
            val zout = java.util.zip.ZipOutputStream(
                java.io.FileOutputStream(dstApk))
            val entries = zin.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val name = e.name
                val low = name.lowercase()

                // 判定是否壳条目
                var isShell = false
                val bn = low.substringAfterLast('/')
                if (bn.endsWith(".so")) {
                    if (soExact.any { bn == it }) isShell = true
                    if (soPrefix.any { bn.startsWith(it) }) isShell = true
                }
                if (low.startsWith("assets/") || low.contains("assets/")) {
                    if (assetKeys.any { low.contains(it.lowercase()) }) isShell = true
                }

                if (isShell) {
                    removed++
                    LogStore.i(TAG, "去壳: 删除 $name")
                    continue
                }

                // 写回（so/arsc 用 STORED，其余保持 DEFLATED）
                val data = zin.getInputStream(e).readBytes()
                val mustStored = low.endsWith(".so") || low.endsWith("resources.arsc")
                val ne = java.util.zip.ZipEntry(name)
                ne.time = e.time
                zout.putNextEntry(ne)
                zout.write(data)
                zout.closeEntry()
            }
            zout.close()
            zin.close()
            LogStore.i(TAG, "去壳完成: 删除 $removed 个条目 -> $dstApk")
        } catch (t: Throwable) {
            LogStore.e(TAG, "去壳清理失败: ${t.message}")
            throw t
        }
        return removed
    }
}