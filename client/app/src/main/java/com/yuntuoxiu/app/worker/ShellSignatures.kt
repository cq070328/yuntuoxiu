package com.yuntuoxiu.app.worker

/**
 * ShellSignatures —— 加固壳特征库（v2.0）
 *
 * 自动生成自「加固特征1.3」样本库（47 厂商）。
 * 用法：ShellDetect 在扫描 APK 条目时，命中任一 so/assets 名即判定对应壳。
 *
 * 数据来源：加固特征样本（部分）
 */
object ShellSignatures {

    /** 厂商特征（tag, so特征[], assets特征[]） */
    val VENDORS: List<VendorSig> = listOf(
        VendorSig("360", "OVERALL_SHELL", listOf("libjiagu.so", "libjiagu_a64.so", "libjiagu_x64.so", "libjiagu_x86.so"), listOf(".jgapp", "libjiagu.so", "libjiagu_a64.so", "libjiagu_x64.so", "libjiagu_x86.so")),
        VendorSig("360付费", "OVERALL_SHELL", listOf("libjiagu.so", "libjiagu_a64.so", "libjiagu_mips.a", "libjiagu_x64.so", "libjiagu_x86.so"), listOf("libjiagu.so", "libjiagu_a64.so", "libjiagu_mips.a", "libjiagu_x64.so", "libjiagu_x86.so")),
        VendorSig("360企业加固", "OVERALL_SHELL", listOf("libRequestEncoder.so", "libjgdtc.so", "libjiagu.so", "libjiagu_a64.so", "libjiagu_mips.a", "libjiagu_vip_a64.so", "libjiagu_vip_mips.a", "libjiagu_vip_x64.so", "libjiagu_x64.so", "libjiagu_x86.so"), listOf(".jgapp", "libjiagu.so", "libjiagu_a64.so", "libjiagu_mips.a", "libjiagu_vip_a64.so", "libjiagu_vip_mips.a", "libjiagu_vip_x64.so", "libjiagu_x64.so", "libjiagu_x86.so")),
        VendorSig("360加盗版检测", "OVERALL_SHELL", listOf("libX86Bridge.so", "libjiagu.so", "libjiagu_a64.so"), listOf(".jgapp", "libjiagu.so", "libjiagu_a64.so")),
        VendorSig("APKProtect", "OVERALL_SHELL", listOf("libAPKProtect.so"), listOf()),
        VendorSig("ARM加固", "VMP", listOf(), listOf("被加固的dex.dex")),
        VendorSig("Appdome加固", "OVERALL_SHELL", listOf("libloader.so"), listOf()),
        VendorSig("CTools加固", "DEX_VM", listOf("libnmmp.so", "libnmmvm.so"), listOf("ByCrash")),
        VendorSig("DexProtect加固", "OVERALL_SHELL", listOf("libdexprotector.so"), listOf("dp.arm-v7.so.dat")),
        VendorSig("Google加固", "OVERALL_SHELL", listOf("libpairipcore.so"), listOf()),
        VendorSig("OPPO加固", "OVERALL_SHELL", listOf("libomas.so"), listOf("classes.png", "classes2.png", "classes3.png", "classes4.png", "classes5.png", "classes6.png")),
        VendorSig("ShadowSafety", "OVERALL_SHELL", listOf("libShadowSafetyProtect.so", "libShadowSafetyProtect_a64.so", "libShadowSafetyProtect_enc.so", "libShadowSafetyProtect_x64.so", "libShadowSafetyProtect_x86.so"), listOf("libShadowSafetyProtect.so", "libShadowSafetyProtect_a64.so", "libShadowSafetyProtect_enc.so", "libShadowSafetyProtect_x64.so", "libShadowSafetyProtect_x86.so")),
        VendorSig("TiamoMuxue", "OVERALL_SHELL", listOf("libTiamo.so", "libTiamoMuxue.so", "libmuxue.so"), listOf()),
        VendorSig("UU安全", "OVERALL_SHELL", listOf("libuusafe.so", "libuusafeempty.so"), listOf()),
        VendorSig("中国移动加固", "VMP", listOf("decrypt.so", "libcmvmp.so", "libmogosec_dex.so", "libmogosecurity.so", "mogosec_datamogosec_dexinfomogosec_marchmogosec_classesibmogosecurity.so"), listOf("decrypt.so", "libcmvmp.so", "libmogosec_dex.so", "libmogosec_so", "libmogosecurity.so", "mogosec_classes", "mogosec_datamogosec_dexinfomogosec_marchmogosec_classesibmogosecurity.so")),
        VendorSig("云镜加固", "OVERALL_SHELL", listOf("libyj-v3-pt.so"), listOf()),
        VendorSig("几维安全", "OVERALL_SHELL", listOf("libKwProtectSDK.so", "libkwsdataenc.so"), listOf("ec_dt.lic")),
        VendorSig("启明星辰", "OVERALL_SHELL", listOf("libsqlen_venus-x86.so", "libsqlen_venus.so", "libsqlen_venus64.so", "libvenSec-x86.so", "libvenSec.so", "libvenSec64.so", "libvenustech-x86.so", "libvenustech.so", "libvenustech64.so"), listOf("classes10.dex", "classes11.dex", "classes12.dex", "classes13.dex", "classes14.dex", "classes15.dex", "classes16.dex", "classes2.dex", "classes3.dex", "classes4.dex", "classes5.dex", "classes6.dex")),
        VendorSig("娜迦加固", "OVERALL_SHELL", listOf("libxloader.so"), listOf("0ba781d5-0f1a-44a8-8955-65fda370b29c.txt")),
        VendorSig("娜迦加固企业版", "OVERALL_SHELL", listOf("libxloader.so"), listOf("abf8d729-efda-4cc2-b0c3-995a58675b7f.txt")),
        VendorSig("支付宝加固", "OVERALL_SHELL", listOf("libashield.so", "libashieldAdapter.so"), listOf()),
        VendorSig("新百度加固", "OVERALL_SHELL", listOf("libbaiduprotect.so"), listOf("baiduprotect-sec.dex", "baiduprotect.md", "baiduprotect1.jar", "baiduprotect10.jar", "baiduprotect2.jar", "baiduprotect3.jar", "baiduprotect4.jar", "baiduprotect5.jar", "baiduprotect6.jar", "baiduprotect7.jar", "baiduprotect8.jar", "baiduprotect9.jar")),
        VendorSig("易固", "OVERALL_SHELL", listOf("libjiagu.so"), listOf()),
        VendorSig("易固企业版", "OVERALL_SHELL", listOf("libjgdtc.so", "libjiagu.so"), listOf()),
        VendorSig("梆梆企业", "OVERALL_SHELL", listOf("libDexHelper-x86.so", "libDexHelper.so", "libdexjni.so"), listOf("manifest.mf", "rsa.pub", "rsa.sig")),
        VendorSig("梆梆加固", "OVERALL_SHELL", listOf("libSecShell-x86.so", "libSecShell.so"), listOf("classes0.jar", "manifest.mf", "rsa.pub", "rsa.sig")),
        VendorSig("海云安", "OVERALL_SHELL", listOf("libsecidea.so"), listOf("secdata1.dat", "secdata2.dat")),
        VendorSig("深思数盾", "OVERALL_SHELL", listOf("l582671db_a32.so", "l582671db_a64.so", "l582671db_x64.so", "l582671db_x86.so"), listOf("l582671db_a32.so", "l582671db_a64.so", "l582671db_x64.so", "l582671db_x86.so")),
        VendorSig("爱加密", "OVERALL_SHELL", listOf("libexec.so"), listOf("af.bin", "ijiami.ajm", "libexec.so", "signed.bin")),
        VendorSig("爱加密企业", "OVERALL_SHELL", listOf("libijmDataEncryption.so", "libijmDataEncryption_arm64.so", "libijmDataEncryption_x86.so", "libijmDataEncryption_x86_64.so"), listOf("IJMDal.Data", "ijiami.ajm", "ijiami.dat", "libijmDataEncryption.so", "libijmDataEncryption_arm64.so", "libijmDataEncryption_x86.so", "libijmDataEncryption_x86_64.so")),
        VendorSig("珊瑚灵御", "OVERALL_SHELL", listOf("libreincp.so", "libreincp_x86.so"), listOf("libreincp.so", "libreincp_x86.so")),
        VendorSig("瑞星加固", "OVERALL_SHELL", listOf("librsprotect.so"), listOf()),
        VendorSig("百度加固企业", "OVERALL_SHELL", listOf("libbaiduprotect.so"), listOf("baiduprotect.m", "baiduprotect1.jar", "baiduprotect2.jar", "baiduprotect4.jar", "baiduprotectmac-ZGV4fDUuMC4xfDIwMjMtMDctMTggMjA6NDc6MTA=")),
        VendorSig("盛大加固", "OVERALL_SHELL", listOf("libapssec.so"), listOf("libapssec.so")),
        VendorSig("网易易盾", "OVERALL_SHELL", listOf("libnesec-x86.so", "libnesec.so"), listOf("nedata.db")),
        VendorSig("网秦加固", "OVERALL_SHELL", listOf("libnqshield.so"), listOf()),
        VendorSig("腾讯加固", "OVERALL_SHELL", listOf("libshell-super.2019.so", "libshellx-super.2019.so"), listOf("0OO00l111l1l", "libshellx-super.2019.so", "o0oooOO0ooOo.dat", "tosversion")),
        VendorSig("腾讯御安全", "SECSHELL", listOf("libshell-super+包名.so", "libshella-4.6.2.2.so"), listOf("o0oooOO0ooOo.dat", "t86", "t86_64", "tosversion")),
        VendorSig("腾讯御安全企业", "SECSHELL", listOf("libshell-superv.2019.so", "libshell-supervbasic.2019.so"), listOf("0OO00oo01l1l", "0OO00oo11l1l", "dexMethod_00oo1l1l.dat")),
        VendorSig("落叶加固", "OVERALL_SHELL", listOf("libdpt.so"), listOf("OoooooOooo", "app_acf", "app_name", "libdpt.so")),
        VendorSig("蛮犀加固", "OVERALL_SHELL", listOf("libmxldd.so"), listOf()),
        VendorSig("通付盾", "OVERALL_SHELL", listOf("libegis-x86.so", "libegis.a", "libegis.so", "libegis_security.so", "libegis_sls.so"), listOf("libegis.a", "mode", "virtual")),
        VendorSig("阿里加固", "OVERALL_SHELL", listOf("libALBiometricsJni.so", "libalisecuritysdk.so", "libalivcffmpeg.so", "libcn.vcinema.cinema_rcp_alijtca_plus.so", "libcn.vcinema.cinema_shell_alijtca_plus.so", "libcom.njh.biubiu_shell_alijtca_plus.so"), listOf("ali_sec.dat", "alibaba_version")),
        VendorSig("阿里加固旗舰", "OVERALL_SHELL", listOf("libashield.so", "libashieldAdapter.so"), listOf()),
        VendorSig("阿里聚安全", "OVERALL_SHELL", listOf(), listOf("dingtalkttid")),
        VendorSig("顶象企业", "OVERALL_SHELL", listOf("libstub000.so"), listOf()),
        VendorSig("顶象加固", "OVERALL_SHELL", listOf("libstub000.so"), listOf()),
    )
}

/** 单个厂商特征 */
data class VendorSig(
    val vendor: String,
    val tag: String,
    val soNames: List<String>,
    val assetNames: List<String>,
)