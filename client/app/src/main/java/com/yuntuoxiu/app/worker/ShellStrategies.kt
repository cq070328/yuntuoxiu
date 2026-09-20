package com.yuntuoxiu.app.worker

/**
 * ShellStrategies —— 厂商去壳策略表（v2.0）
 *
 * 自动生成自「加固特征1.3」样本库 + 已知入口类。
 * 每个厂商给出：需清理的 so/assets 匹配串 + 壳入口类 + 处理说明。
 */
object ShellStrategies {

    /** 单厂商策略 */
    data class Strategy(
        val vendor: String,
        val cleanSoPatterns: List<String>,   // 匹配 so 名（contains）
        val cleanAssetPatterns: List<String>, // 匹配 assets（contains）
        val stubEntry: String?,              // 壳入口类（Manifest 替换）
        val note: String,
    )

    val ALL: List<Strategy> = listOf(
        Strategy("360", listOf("libjiagu"), listOf("libjiagu"), "com.stub.StubApp", "删 libjiagu*.so + .jgapp，Manifest 换真实Application"),
        Strategy("360付费", listOf("libjiagu"), listOf("libjiagu"), "com.qihoo.util.StubApp", "删 libjiagu*.so，走 cookie dump"),
        Strategy("360企业加固", listOf("libjiagu", "libjgdtc"), listOf("libjiagu", "libjgdtc"), "com.stub.StubApp", "删 libjiagu_vip* + libjgdtc + libRequestEncoder"),
        Strategy("360加盗版检测", listOf("libjiagu", "libX86Bridge"), listOf("libjiagu", "libX86Bridge"), "com.stub.StubApp", "删 libX86Bridge.so"),
        Strategy("APKProtect", listOf("libAPKProtect"), listOf("libAPKProtect"), null, "删 libAPKProtect.so"),
        Strategy("ARM加固", listOf("被加固的dex"), listOf("被加固的dex"), null, "删 assets/被加固的dex.dex"),
        Strategy("Appdome加固", listOf("libloader"), listOf("libloader"), null, "删 libloader.so"),
        Strategy("CTools加固", listOf("libnmmp", "libnmmvm", "ByCrash"), listOf("libnmmp", "libnmmvm", "ByCrash"), null, "DEX-VM：删 libnmmp/nmmvm，需 VM 还原"),
        Strategy("DexProtect加固", listOf("libdexprotector", "dp.arm"), listOf("libdexprotector", "dp.arm"), null, "删 libdexprotector + dp.*.so.dat"),
        Strategy("Google加固", listOf("libpairipcore"), listOf("libpairipcore"), null, "删 libpairipcore.so（Google Play 加固）"),
        Strategy("OPPO加固", listOf("libomas", "classes.png"), listOf("libomas", "classes.png"), null, "删 libomas.so + assets/classes*.png"),
        Strategy("ShadowSafety", listOf("libShadowSafetyProtect"), listOf("libShadowSafetyProtect"), null, "删 libShadowSafetyProtect*.so"),
        Strategy("TiamoMuxue", listOf("libTiamo", "libmuxue", "沐雪"), listOf("libTiamo", "libmuxue", "沐雪"), null, "删 libTiamo*/libmuxue.so + assets/沐雪"),
        Strategy("UU安全", listOf("libuusafe"), listOf("libuusafe"), null, "删 libuusafe*.so"),
        Strategy("中国移动加固", listOf("libcmvmp", "libmogosec", "decrypt", "mogosec"), listOf("libcmvmp", "libmogosec", "decrypt", "mogosec"), null, "VMP：删 libcmvmp + libmogosec*"),
        Strategy("云镜加固", listOf("libyj-v3-pt"), listOf("libyj-v3-pt"), null, "删 libyj-v3-pt.so"),
        Strategy("几维安全", listOf("libKwProtectSDK", "libkwsdataenc", "ec_dt.lic"), listOf("libKwProtectSDK", "libkwsdataenc", "ec_dt.lic"), null, "删 libKwProtectSDK* + assets/ec_dt.lic"),
        Strategy("启明星辰", listOf("libvenSec", "libvenustech", "libsqlen_venus", "venCache"), listOf("libvenSec", "libvenustech", "libsqlen_venus", "venCache"), null, "删 libven* + assets/venCache/*"),
        Strategy("娜迦加固", listOf("libxloader", "maindata"), listOf("libxloader", "maindata"), null, "删 libxloader.so + assets/maindata/*"),
        Strategy("娜迦加固企业版", listOf("libxloader", "maindata"), listOf("libxloader", "maindata"), null, "删 libxloader.so + assets/maindata/*"),
        Strategy("支付宝加固", listOf("libashield"), listOf("libashield"), null, "删 libashield*.so"),
        Strategy("新百度加固", listOf("libbaiduprotect", "baiduprotect"), listOf("libbaiduprotect", "baiduprotect"), null, "删 libbaiduprotect.so + assets/baiduprotect*"),
        Strategy("梆梆企业", listOf("libDexHelper", "libdexjni", "rsa.pub", "rsa.sig", "manifest.mf"), listOf("libDexHelper", "libdexjni", "rsa.pub", "rsa.sig", "manifest.mf"), null, "删 libDexHelper* + assets/rsa.*"),
        Strategy("梆梆加固", listOf("libSecShell", "classes0.jar", "rsa.pub", "rsa.sig"), listOf("libSecShell", "classes0.jar", "rsa.pub", "rsa.sig"), null, "删 libSecShell* + assets/classes0.jar"),
        Strategy("海云安", listOf("libsecidea", "secdata"), listOf("libsecidea", "secdata"), null, "删 libsecidea.so + assets/secdata*.dat"),
        Strategy("深思数盾", listOf("l582671db"), listOf("l582671db"), null, "删 l582671db_*.so"),
        Strategy("爱加密", listOf("libexec", "ijiami.ajm", "af.bin", "signed.bin"), listOf("libexec", "ijiami.ajm", "af.bin", "signed.bin"), null, "删 libexec.so + assets/ijiami.ajm/*"),
        Strategy("爱加密企业", listOf("libijmDataEncryption", "ijiami", "IJMDal"), listOf("libijmDataEncryption", "ijiami", "IJMDal"), null, "删 libijmDataEncryption* + assets/ijiami*"),
        Strategy("珊瑚灵御", listOf("libreincp"), listOf("libreincp"), null, "删 libreincp*.so"),
        Strategy("瑞星加固", listOf("librsprotect"), listOf("librsprotect"), null, "删 librsprotect.so"),
        Strategy("百度加固企业", listOf("libbaiduprotect", "baiduprotect"), listOf("libbaiduprotect", "baiduprotect"), null, "删 libbaiduprotect.so + assets/baiduprotect*"),
        Strategy("盛大加固", listOf("libapssec"), listOf("libapssec"), null, "删 libapssec.so"),
        Strategy("网易易盾", listOf("libnesec", "nedata"), listOf("libnesec", "nedata"), null, "删 libnesec*.so + assets/nedata.db"),
        Strategy("网秦加固", listOf("libnqshield"), listOf("libnqshield"), null, "删 libnqshield.so"),
        Strategy("腾讯加固", listOf("libshell", "libshellx", "0OO00l111l1l", "o0oooOO0ooOo.dat", "tosversion"), listOf("libshell", "libshellx", "0OO00l111l1l", "o0oooOO0ooOo.dat", "tosversion"), null, "删 libshell* + assets/0OO00l111l1l/*"),
        Strategy("腾讯御安全", listOf("libshell-super", "libshella", "t86", "tosversion", "o0oooOO0ooOo.dat"), listOf("libshell-super", "libshella", "t86", "tosversion", "o0oooOO0ooOo.dat"), null, "删 libshell-super*/libshella* + assets/t86*"),
        Strategy("腾讯御安全企业", listOf("libshell-superv", "0OO00oo", "dexMethod"), listOf("libshell-superv", "0OO00oo", "dexMethod"), null, "删 libshell-superv* + assets/0OO00oo*"),
        Strategy("落叶加固", listOf("libdpt", "OoooooOooo", "app_acf"), listOf("libdpt", "OoooooOooo", "app_acf"), null, "删 libdpt.so + assets/OoooooOooo/app_acf"),
        Strategy("蛮犀加固", listOf("libmxldd"), listOf("libmxldd"), null, "删 libmxldd.so"),
        Strategy("通付盾", listOf("libegis", "virtual", "mode", "PK"), listOf("libegis", "virtual", "mode", "PK"), null, "删 libegis*.so + assets/virtual/mode/PK"),
        Strategy("阿里加固", listOf("libalisecuritysdk", "ali_sec.dat", "alibaba_version"), listOf("libalisecuritysdk", "ali_sec.dat", "alibaba_version"), null, "删 libalisecuritysdk + assets/ali_sec.dat"),
        Strategy("阿里加固旗舰", listOf("libashield"), listOf("libashield"), null, "删 libashield*.so"),
        Strategy("阿里聚安全", listOf("dingtalkttid"), listOf("dingtalkttid"), null, "删 assets/dingtalkttid"),
        Strategy("顶象企业", listOf("libstub000"), listOf("libstub000"), null, "删 libstub000.so"),
        Strategy("顶象加固", listOf("libstub000"), listOf("libstub000"), null, "删 libstub000.so"),
    )

    /** 按供应商获取策略 */
    fun forVendor(vendor: String): Strategy? = ALL.firstOrNull { it.vendor == vendor }

    /** 全部清理匹配串（用于 LocalRepairEngine/LocalSmaliPatcher） */
    fun allCleanPatterns(): List<String> {
        val s = LinkedHashSet<String>()
        for (st in ALL) { s.addAll(st.cleanSoPatterns); s.addAll(st.cleanAssetPatterns) }
        return s.toList()
    }
}