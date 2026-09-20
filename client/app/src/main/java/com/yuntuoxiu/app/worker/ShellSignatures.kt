package com.yuntuoxiu.app.worker

/**
 * ShellSignatures —— 加固壳特征库（v2.0，全维度）
 *
 * 自动生成自「加固特征1.3」样本库（47 厂商）。
 * 每个厂商包含：
 *   · soNames     —— 特征 so（对应 lib/<abi>/ 下的文件名）
 *   · assetNames  —— 特征 assets 文件
 *   · libNames    —— 特征 lib 目录文件（含 .so/.a）
 *
 * 匹配策略：命中任一即判定该厂商（权重 0.85）
 */
object ShellSignatures {

    /** 厂商特征 */
    data class VendorSig(
        val vendor: String,
        val tag: String,
        val soNames: List<String>,
        val assetNames: List<String>,
        val libNames: List<String>,
    )

    val VENDORS: List<VendorSig> = listOf(
        VendorSig("360", "OVERALL_SHELL", listOf("libjiagu.so", "libjiagu_a64.so", "libjiagu_x64.so", "libjiagu_x86.so"), listOf(".jgapp", "libjiagu.so", "libjiagu_a64.so", "libjiagu_x64.so", "libjiagu_x86.so"), listOf()),
        VendorSig("360付费", "OVERALL_SHELL", listOf("libjiagu.so", "libjiagu_a64.so", "libjiagu_mips.a", "libjiagu_x64.so", "libjiagu_x86.so"), listOf("libjiagu.so", "libjiagu_a64.so", "libjiagu_mips.a", "libjiagu_x64.so", "libjiagu_x86.so"), listOf()),
        VendorSig("360企业加固", "OVERALL_SHELL", listOf("libRequestEncoder.so", "libjgdtc.so", "libjiagu.so", "libjiagu_a64.so", "libjiagu_mips.a", "libjiagu_vip_a64.so", "libjiagu_vip_mips.a", "libjiagu_vip_x64.so", "libjiagu_x64.so", "libjiagu_x86.so"), listOf(".jgapp", "libjiagu.so", "libjiagu_a64.so", "libjiagu_mips.a", "libjiagu_vip_a64.so", "libjiagu_vip_mips.a", "libjiagu_vip_x64.so", "libjiagu_x64.so", "libjiagu_x86.so"), listOf("libRequestEncoder.so", "libjgdtc.so")),
        VendorSig("360加盗版检测", "OVERALL_SHELL", listOf("libX86Bridge.so", "libjiagu.so", "libjiagu_a64.so"), listOf(".jgapp", "libjiagu.so", "libjiagu_a64.so"), listOf("libX86Bridge.so")),
        VendorSig("APKProtect", "OVERALL_SHELL", listOf("libAPKProtect.so"), listOf(), listOf("libAPKProtect.so")),
        VendorSig("ARM加固", "VMP", listOf(), listOf("被加固的dex.dex"), listOf()),
        VendorSig("Appdome加固", "OVERALL_SHELL", listOf("libloader.so"), listOf(), listOf("libloader.so")),
        VendorSig("CTools加固", "DEX_VM", listOf("libnmmp.so", "libnmmvm.so"), listOf("ByCrash"), listOf("libnmmp.so", "libnmmvm.so")),
        VendorSig("DexProtect加固", "OVERALL_SHELL", listOf("libdexprotector.so"), listOf("dp.arm-v7.so.dat"), listOf("libdexprotector.so")),
        VendorSig("Google加固", "OVERALL_SHELL", listOf("libpairipcore.so"), listOf(), listOf("libpairipcore.so")),
        VendorSig("OPPO加固", "OVERALL_SHELL", listOf("libomas.so"), listOf("classes.png", "classes2.png", "classes3.png", "classes4.png", "classes5.png", "classes6.png"), listOf("libomas.so")),
        VendorSig("ShadowSafety", "OVERALL_SHELL", listOf("libShadowSafetyProtect.so", "libShadowSafetyProtect_a64.so", "libShadowSafetyProtect_enc.so", "libShadowSafetyProtect_x64.so", "libShadowSafetyProtect_x86.so"), listOf("libShadowSafetyProtect.so", "libShadowSafetyProtect_a64.so", "libShadowSafetyProtect_enc.so", "libShadowSafetyProtect_x64.so", "libShadowSafetyProtect_x86.so"), listOf()),
        VendorSig("TiamoMuxue", "OVERALL_SHELL", listOf("libTiamo.so", "libTiamoMuxue.so", "libmuxue.so"), listOf(), listOf("libTiamo.so", "libTiamoMuxue.so", "libmuxue.so")),
        VendorSig("UU安全", "OVERALL_SHELL", listOf("libuusafe.so", "libuusafeempty.so"), listOf(), listOf("libuusafe.so", "libuusafeempty.so")),
        VendorSig("中国移动加固", "VMP", listOf("decrypt.so", "libcmvmp.so", "libmogosec_dex.so", "libmogosecurity.so", "mogosec_datamogosec_dexinfomogosec_marchmogosec_classesibmogosecurity.so"), listOf("decrypt.so", "libcmvmp.so", "libmogosec_dex.so", "libmogosec_so", "libmogosecurity.so", "mogosec_classes", "mogosec_datamogosec_dexinfomogosec_marchmogosec_classesibmogosecurity.so"), listOf()),
        VendorSig("云镜加固", "OVERALL_SHELL", listOf("libyj-v3-pt.so"), listOf(), listOf("libyj-v3-pt.so")),
        VendorSig("几维安全", "OVERALL_SHELL", listOf("libKwProtectSDK.so", "libkwsdataenc.so"), listOf("ec_dt.lic"), listOf("libKwProtectSDK.so", "libkwsdataenc.so")),
        VendorSig("启明星辰", "OVERALL_SHELL", listOf("libsqlen_venus-x86.so", "libsqlen_venus.so", "libsqlen_venus64.so", "libvenSec-x86.so", "libvenSec.so", "libvenSec64.so", "libvenustech-x86.so", "libvenustech.so", "libvenustech64.so"), listOf("classes10.dex", "classes11.dex", "classes12.dex", "classes13.dex", "classes14.dex", "classes15.dex", "classes16.dex", "classes2.dex", "classes3.dex", "classes4.dex", "classes5.dex", "classes6.dex", "classes7.dex", "classes8.dex"), listOf()),
        VendorSig("娜迦加固", "OVERALL_SHELL", listOf("libxloader.so"), listOf("0ba781d5-0f1a-44a8-8955-65fda370b29c.txt"), listOf("libxloader.so")),
        VendorSig("娜迦加固企业版", "OVERALL_SHELL", listOf("libxloader.so"), listOf("abf8d729-efda-4cc2-b0c3-995a58675b7f.txt"), listOf("libxloader.so")),
        VendorSig("支付宝加固", "OVERALL_SHELL", listOf("libashield.so", "libashieldAdapter.so"), listOf(), listOf("libashield.so", "libashieldAdapter.so")),
        VendorSig("新百度加固", "OVERALL_SHELL", listOf("libbaiduprotect.so"), listOf("baiduprotect-sec.dex", "baiduprotect.md", "baiduprotect1.jar", "baiduprotect10.jar", "baiduprotect2.jar", "baiduprotect3.jar", "baiduprotect4.jar", "baiduprotect5.jar", "baiduprotect6.jar", "baiduprotect7.jar", "baiduprotect8.jar", "baiduprotect9.jar"), listOf("libbaiduprotect.so")),
        VendorSig("易固", "OVERALL_SHELL", listOf("libjiagu.so"), listOf(), listOf("libjiagu.so")),
        VendorSig("易固企业版", "OVERALL_SHELL", listOf("libjgdtc.so", "libjiagu.so"), listOf(), listOf("libjgdtc.so", "libjiagu.so")),
        VendorSig("梆梆企业", "OVERALL_SHELL", listOf("libDexHelper-x86.so", "libDexHelper.so", "libdexjni.so"), listOf("manifest.mf", "rsa.pub", "rsa.sig"), listOf("libDexHelper-x86.so", "libDexHelper.so", "libdexjni.so")),
        VendorSig("梆梆加固", "OVERALL_SHELL", listOf("libSecShell-x86.so", "libSecShell.so"), listOf("classes0.jar", "manifest.mf", "rsa.pub", "rsa.sig"), listOf("libSecShell-x86.so", "libSecShell.so")),
        VendorSig("海云安", "OVERALL_SHELL", listOf("libsecidea.so"), listOf("secdata1.dat", "secdata2.dat"), listOf("libsecidea.so")),
        VendorSig("深思数盾", "OVERALL_SHELL", listOf("l582671db_a32.so", "l582671db_a64.so", "l582671db_x64.so", "l582671db_x86.so"), listOf("l582671db_a32.so", "l582671db_a64.so", "l582671db_x64.so", "l582671db_x86.so"), listOf()),
        VendorSig("爱加密", "OVERALL_SHELL", listOf("libexec.so"), listOf("af.bin", "ijiami.ajm", "libexec.so", "signed.bin"), listOf()),
        VendorSig("爱加密企业", "OVERALL_SHELL", listOf("libijmDataEncryption.so", "libijmDataEncryption_arm64.so", "libijmDataEncryption_x86.so", "libijmDataEncryption_x86_64.so"), listOf("IJMDal.Data", "ijiami.ajm", "ijiami.dat", "libijmDataEncryption.so", "libijmDataEncryption_arm64.so", "libijmDataEncryption_x86.so", "libijmDataEncryption_x86_64.so"), listOf()),
        VendorSig("珊瑚灵御", "OVERALL_SHELL", listOf("libreincp.so", "libreincp_x86.so"), listOf("libreincp.so", "libreincp_x86.so"), listOf()),
        VendorSig("瑞星加固", "OVERALL_SHELL", listOf("librsprotect.so"), listOf(), listOf("librsprotect.so")),
        VendorSig("百度加固企业", "OVERALL_SHELL", listOf("libbaiduprotect.so"), listOf("baiduprotect.m", "baiduprotect1.jar", "baiduprotect2.jar", "baiduprotect4.jar", "baiduprotectmac-ZGV4fDUuMC4xfDIwMjMtMDctMTggMjA6NDc6MTA="), listOf("libbaiduprotect.so")),
        VendorSig("盛大加固", "OVERALL_SHELL", listOf("libapssec.so"), listOf("libapssec.so"), listOf()),
        VendorSig("网易易盾", "OVERALL_SHELL", listOf("libnesec-x86.so", "libnesec.so"), listOf("nedata.db"), listOf("libnesec-x86.so", "libnesec.so")),
        VendorSig("网秦加固", "OVERALL_SHELL", listOf("libnqshield.so"), listOf(), listOf("libnqshield.so")),
        VendorSig("腾讯加固", "OVERALL_SHELL", listOf("libshell-super.2019.so", "libshellx-super.2019.so"), listOf("0OO00l111l1l", "libshellx-super.2019.so", "o0oooOO0ooOo.dat", "tosversion"), listOf("libshell-super.2019.so")),
        VendorSig("腾讯御安全", "SECSHELL", listOf("libshell-super+包名.so", "libshella-4.6.2.2.so"), listOf("o0oooOO0ooOo.dat", "t86", "t86_64", "tosversion"), listOf("libshell-super+包名.so", "libshella-4.6.2.2.so")),
        VendorSig("腾讯御安全企业", "SECSHELL", listOf("libshell-superv.2019.so", "libshell-supervbasic.2019.so"), listOf("0OO00oo01l1l", "0OO00oo11l1l", "dexMethod_00oo1l1l.dat"), listOf("libshell-superv.2019.so", "libshell-supervbasic.2019.so")),
        VendorSig("落叶加固", "OVERALL_SHELL", listOf("libdpt.so"), listOf("OoooooOooo", "app_acf", "app_name", "libdpt.so"), listOf()),
        VendorSig("蛮犀加固", "OVERALL_SHELL", listOf("libmxldd.so"), listOf(), listOf("libmxldd.so")),
        VendorSig("通付盾", "OVERALL_SHELL", listOf("libegis-x86.so", "libegis.a", "libegis.so", "libegis_security.so", "libegis_sls.so"), listOf("libegis.a", "mode", "virtual"), listOf("libegis-x86.so", "libegis.so", "libegis_security.so", "libegis_sls.so")),
        VendorSig("阿里加固", "OVERALL_SHELL", listOf("libALBiometricsJni.so", "libalisecuritysdk.so", "libalivcffmpeg.so", "libcn.vcinema.cinema_rcp_alijtca_plus.so", "libcn.vcinema.cinema_shell_alijtca_plus.so", "libcom.njh.biubiu_shell_alijtca_plus.so"), listOf("ali_sec.dat", "alibaba_version"), listOf("libALBiometricsJni.so", "libalisecuritysdk.so", "libalivcffmpeg.so", "libcn.vcinema.cinema_rcp_alijtca_plus.so", "libcn.vcinema.cinema_shell_alijtca_plus.so", "libcom.njh.biubiu_shell_alijtca_plus.so")),
        VendorSig("阿里加固旗舰", "OVERALL_SHELL", listOf("libashield.so", "libashieldAdapter.so"), listOf(), listOf("libashield.so", "libashieldAdapter.so")),
        VendorSig("阿里聚安全", "OVERALL_SHELL", listOf(), listOf("dingtalkttid"), listOf()),
        VendorSig("顶象企业", "OVERALL_SHELL", listOf("libstub000.so"), listOf(), listOf("libstub000.so")),
        VendorSig("顶象加固", "OVERALL_SHELL", listOf("libstub000.so"), listOf(), listOf("libstub000.so")),
    )

    /** 全部 so 特征名（扁平化，供快速匹配） */
    val ALL_SO: List<String> = VENDORS.flatMap { it.soNames + it.libNames }.distinct()

    /** 全部 assets 特征名 */
    val ALL_ASSETS: List<String> = VENDORS.flatMap { it.assetNames }.distinct()

    /** 按 so 名反查厂商 */
    fun vendorOfSo(name: String): VendorSig? =
        VENDORS.firstOrNull { v ->
            (v.soNames + v.libNames).any { it == name }
        }
}