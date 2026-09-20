package com.yuntuoxiu.app.worker

/**
 * ShellStrategies —— 厂商去壳策略表（v2.0）
 *
 * 自动生成自「加固特征1.3」样本库。
 * 每个厂商给出：tag(壳类型) + 清理匹配串 + 壳入口类 + 处理说明。
 */
object ShellStrategies {

    /** 单厂商策略 */
    data class Strategy(
        val vendor: String,
        val tag: String,                     // 壳类型标签
        val cleanSoPatterns: List<String>,   // 匹配 so/文件名（contains）
        val cleanAssetPatterns: List<String>, // 匹配 assets
        val stubEntry: String?,              // 壳入口类
        val note: String,
    )

    val ALL: List<Strategy> = listOf(
        Strategy("360", "OVERALL_SHELL", listOf("libjiagu"), listOf("libjiagu"), "com.stub.StubApp", "清理: libjiagu"),
        Strategy("360付费", "OVERALL_SHELL", listOf("libjiagu"), listOf("libjiagu"), "com.qihoo.util.StubApp", "清理: libjiagu"),
        Strategy("360企业加固", "OVERALL_SHELL", listOf("libjiagu", "libjgdtc", "libRequestEncoder"), listOf("libjiagu", "libjgdtc", "libRequestEncoder"), "com.stub.StubApp", "清理: libjiagu|libjgdtc|libRequestEncoder"),
        Strategy("360加盗版检测", "OVERALL_SHELL", listOf("libjiagu", "libX86Bridge"), listOf("libjiagu", "libX86Bridge"), "com.stub.StubApp", "清理: libjiagu|libX86Bridge"),
        Strategy("APKProtect", "OVERALL_SHELL", listOf("libAPKProtect"), listOf("libAPKProtect"), null, "清理: libAPKProtect"),
        Strategy("ARM加固", "VMP", listOf("被加固的dex", "libarm_protect", "libArmEpicVm"), listOf("被加固的dex", "libarm_protect", "libArmEpicVm"), null, "清理: 被加固的dex|libarm_protect|libArmEpicVm"),
        Strategy("Appdome加固", "OVERALL_SHELL", listOf("libloader"), listOf("libloader"), null, "清理: libloader"),
        Strategy("CTools加固", "DEX_VM", listOf("libnmmp", "libnmmvm", "ByCrash"), listOf("libnmmp", "libnmmvm", "ByCrash"), null, "清理: libnmmp|libnmmvm|ByCrash"),
        Strategy("DexProtect加固", "OVERALL_SHELL", listOf("libdexprotector", "dp.arm"), listOf("libdexprotector", "dp.arm"), null, "清理: libdexprotector|dp.arm"),
        Strategy("Google加固", "OVERALL_SHELL", listOf("libpairipcore"), listOf("libpairipcore"), null, "清理: libpairipcore"),
        Strategy("OPPO加固", "OVERALL_SHELL", listOf("libomas", "classes.png"), listOf("libomas", "classes.png"), null, "清理: libomas|classes.png"),
        Strategy("ShadowSafety", "OVERALL_SHELL", listOf("libShadowSafetyProtect"), listOf("libShadowSafetyProtect"), null, "清理: libShadowSafetyProtect"),
        Strategy("TiamoMuxue", "OVERALL_SHELL", listOf("libTiamo", "libmuxue", "沐雪"), listOf("libTiamo", "libmuxue", "沐雪"), null, "清理: libTiamo|libmuxue|沐雪"),
        Strategy("UU安全", "OVERALL_SHELL", listOf("libuusafe"), listOf("libuusafe"), null, "清理: libuusafe"),
        Strategy("中国移动加固", "VMP", listOf("libcmvmp", "libmogosec", "decrypt", "mogosec"), listOf("libcmvmp", "libmogosec", "decrypt", "mogosec"), null, "清理: libcmvmp|libmogosec|decrypt|mogosec"),
        Strategy("云镜加固", "OVERALL_SHELL", listOf("libyj-v3-pt"), listOf("libyj-v3-pt"), null, "清理: libyj-v3-pt"),
        Strategy("几维安全", "OVERALL_SHELL", listOf("libKwProtectSDK", "libkwsdataenc", "ec_dt.lic"), listOf("libKwProtectSDK", "libkwsdataenc", "ec_dt.lic"), null, "清理: libKwProtectSDK|libkwsdataenc|ec_dt.lic"),
        Strategy("启明星辰", "OVERALL_SHELL", listOf("libvenSec", "libvenustech", "libsqlen_venus", "venCache"), listOf("libvenSec", "libvenustech", "libsqlen_venus", "venCache"), null, "清理: libvenSec|libvenustech|libsqlen_venus|venCache"),
        Strategy("娜迦加固", "OVERALL_SHELL", listOf("libxloader", "maindata"), listOf("libxloader", "maindata"), null, "清理: libxloader|maindata"),
        Strategy("娜迦加固企业版", "OVERALL_SHELL", listOf("libxloader", "maindata"), listOf("libxloader", "maindata"), null, "清理: libxloader|maindata"),
        Strategy("支付宝加固", "OVERALL_SHELL", listOf("libashield"), listOf("libashield"), null, "清理: libashield"),
        Strategy("新百度加固", "OVERALL_SHELL", listOf("libbaiduprotect", "baiduprotect"), listOf("libbaiduprotect", "baiduprotect"), null, "清理: libbaiduprotect|baiduprotect"),
        Strategy("梆梆企业", "OVERALL_SHELL", listOf("libDexHelper", "libdexjni", "rsa.pub", "rsa.sig", "manifest.mf"), listOf("libDexHelper", "libdexjni", "rsa.pub", "rsa.sig", "manifest.mf"), "com.secneo.apkwrapper.ApplicationWrapper", "清理: libDexHelper|libdexjni|rsa.pub|rsa.sig|manifest.mf"),
        Strategy("梆梆加固", "OVERALL_SHELL", listOf("libSecShell", "classes0.jar", "rsa.pub", "rsa.sig"), listOf("libSecShell", "classes0.jar", "rsa.pub", "rsa.sig"), "com.secneo.apkwrapper.ApplicationWrapper", "清理: libSecShell|classes0.jar|rsa.pub|rsa.sig"),
        Strategy("海云安", "OVERALL_SHELL", listOf("libsecidea", "secdata"), listOf("libsecidea", "secdata"), null, "清理: libsecidea|secdata"),
        Strategy("深思数盾", "OVERALL_SHELL", listOf("l582671db"), listOf("l582671db"), null, "清理: l582671db"),
        Strategy("爱加密", "OVERALL_SHELL", listOf("libexec", "ijiami.ajm", "af.bin", "signed.bin"), listOf("libexec", "ijiami.ajm", "af.bin", "signed.bin"), null, "清理: libexec|ijiami.ajm|af.bin|signed.bin"),
        Strategy("爱加密企业", "OVERALL_SHELL", listOf("libijmDataEncryption", "ijiami", "IJMDal"), listOf("libijmDataEncryption", "ijiami", "IJMDal"), null, "清理: libijmDataEncryption|ijiami|IJMDal"),
        Strategy("珊瑚灵御", "OVERALL_SHELL", listOf("libreincp"), listOf("libreincp"), null, "清理: libreincp"),
        Strategy("瑞星加固", "OVERALL_SHELL", listOf("librsprotect"), listOf("librsprotect"), null, "清理: librsprotect"),
        Strategy("百度加固企业", "OVERALL_SHELL", listOf("libbaiduprotect", "baiduprotect"), listOf("libbaiduprotect", "baiduprotect"), null, "清理: libbaiduprotect|baiduprotect"),
        Strategy("盛大加固", "OVERALL_SHELL", listOf("libapssec"), listOf("libapssec"), null, "清理: libapssec"),
        Strategy("网易易盾", "OVERALL_SHELL", listOf("libnesec", "nedata"), listOf("libnesec", "nedata"), null, "清理: libnesec|nedata"),
        Strategy("网秦加固", "OVERALL_SHELL", listOf("libnqshield"), listOf("libnqshield"), null, "清理: libnqshield"),
        Strategy("腾讯加固", "OVERALL_SHELL", listOf("libshell", "libshellx", "0OO00l111l1l", "tosversion"), listOf("libshell", "libshellx", "0OO00l111l1l", "tosversion"), "com.tencent.StubShell.TxAppEntry", "清理: libshell|libshellx|0OO00l111l1l|tosversion"),
        Strategy("腾讯御安全", "SECSHELL", listOf("libshell-super", "libshella", "t86", "tosversion", "o0oooOO0ooOo.dat"), listOf("libshell-super", "libshella", "t86", "tosversion", "o0oooOO0ooOo.dat"), "com.tencent.StubShell.TxAppEntry", "清理: libshell-super|libshella|t86|tosversion|o0oooOO0ooOo.dat"),
        Strategy("腾讯御安全企业", "SECSHELL", listOf("libshell-superv", "0OO00oo", "dexMethod"), listOf("libshell-superv", "0OO00oo", "dexMethod"), null, "清理: libshell-superv|0OO00oo|dexMethod"),
        Strategy("落叶加固", "OVERALL_SHELL", listOf("libdpt", "OoooooOooo", "app_acf"), listOf("libdpt", "OoooooOooo", "app_acf"), null, "清理: libdpt|OoooooOooo|app_acf"),
        Strategy("蛮犀加固", "OVERALL_SHELL", listOf("libmxldd"), listOf("libmxldd"), null, "清理: libmxldd"),
        Strategy("通付盾", "OVERALL_SHELL", listOf("libegis", "virtual"), listOf("libegis", "virtual"), null, "清理: libegis|virtual"),
        Strategy("阿里加固", "OVERALL_SHELL", listOf("libalisecuritysdk", "ali_sec.dat", "alibaba_version"), listOf("libalisecuritysdk", "ali_sec.dat", "alibaba_version"), null, "清理: libalisecuritysdk|ali_sec.dat|alibaba_version"),
        Strategy("阿里加固旗舰", "OVERALL_SHELL", listOf("libashield"), listOf("libashield"), null, "清理: libashield"),
        Strategy("阿里聚安全", "OVERALL_SHELL", listOf("dingtalkttid"), listOf("dingtalkttid"), null, "清理: dingtalkttid"),
        Strategy("顶象企业", "OVERALL_SHELL", listOf("libstub000"), listOf("libstub000"), null, "清理: libstub000"),
        Strategy("顶象加固", "OVERALL_SHELL", listOf("libstub000"), listOf("libstub000"), null, "清理: libstub000"),
    )

    /** 按供应商获取策略 */
    fun forVendor(vendor: String): Strategy? = ALL.firstOrNull { it.vendor == vendor }

    /** 全部清理匹配串（供去壳清理） */
    fun allCleanPatterns(): List<String> {
        val s = LinkedHashSet<String>()
        for (st in ALL) { s.addAll(st.cleanSoPatterns); s.addAll(st.cleanAssetPatterns) }
        return s.toList()
    }
}