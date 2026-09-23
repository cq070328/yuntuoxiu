package com.yuntuoxiu.app.engine

import android.content.Context
import com.android.apksig.ApkSigner
import com.yuntuoxiu.app.LogStore
import com.yuntuoxiu.app.YunTuoXiuApp
import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * LocalApkSigner —— 本地 APK 签名（v2.0，基于 Google apksig）
 *
 * 支持两种密钥来源：
 *   1. ⭐ assets/testkey.pk8 + testkey.x509.pem（AOSP 测试密钥，内置，始终可用）
 *   2. 工作区 ytx-release.jks（JKS 密钥库）
 */
object LocalApkSigner {

    private const val TAG = "LocalApkSigner"

    const val DEFAULT_KS_PASS = "ytx12345"
    const val DEFAULT_KEY_ALIAS = "ytx"
    const val DEFAULT_KEY_PASS = "ytx12345"

    data class SignResult(
        val ok: Boolean,
        val outApk: File?,
        val detail: String
    )

    /**
     * 对 APK 签名。
     *
     * @param inApk    待签名 APK
     * @param outApk   输出 APK
     * @param keystore JKS 密钥库（null → 优先用内置 testkey，再找工作区 jks）
     * @param context  用于读 assets testkey（可空；为 null 时只用工作区 jks）
     */
    fun sign(
        inApk: File,
        outApk: File,
        keystore: File? = null,
        context: Context? = null,
        storePass: String = DEFAULT_KS_PASS,
        keyAlias: String = DEFAULT_KEY_ALIAS,
        keyPass: String = DEFAULT_KEY_PASS,
        onProgress: (String) -> Unit = {}
    ): SignResult {
        if (!inApk.isFile) return SignResult(false, null, "待签名 APK 不存在: ${inApk.absolutePath}")

        // ⭐⭐⭐ v2.5【关键修复】签名优先级调整：**JKS 优先，内置 testkey 兜底**。
        //   原实现「testkey 优先」会导致：
        //     · 运行时产物签名（AOSP testkey）与工程构建签名（ytx-release.jks / CN=YunTuoXiu）不一致
        //     · 覆盖安装报 INSTALL_FAILED_UPDATE_INCOMPATIBLE
        //   现改为：先尝试工作区固定 keystore（跨版本一致），失败再用内置 testkey。

        // 1) 优先：工作区 JKS（固定签名，保证升级兼容）
        val ks = keystore ?: locateDefaultKeystore()
        if (ks != null && ks.isFile) {
            val r = try {
                onProgress("加载密钥库: ${ks.name}")
                // ⭐ v2.5：keystore 可能是 PKCS12 或 JKS —— 自动探测（原先固定 JKS 会加载失败）。
                val keyStore = loadKeyStoreAuto(ks, storePass)
                    ?: return SignResult(false, null, "无法识别密钥库格式（JKS/PKCS12）: ${ks.name}")
                val key = keyStore.getKey(keyAlias, keyPass.toCharArray()) as? PrivateKey
                val certChain = keyStore.getCertificateChain(keyAlias)?.map { it as X509Certificate }
                if (key == null || certChain == null) {
                    SignResult(false, null, "密钥别名/证书链异常: $keyAlias")
                } else {
                    doSign(inApk, outApk, keyAlias, key, certChain, onProgress)
                }
            } catch (t: Throwable) {
                LogStore.e(TAG, "签名失败(JKS): ${t.message}")
                SignResult(false, null, "签名失败(JKS): ${t.message}")
            }
            if (r.ok) return r
            onProgress("JKS 签名失败，回退内置 testkey: ${r.detail}")
        }

        // 2) 兜底：内置 testkey.pk8 + .pem（AOSP，始终可用）
        if (context != null) {
            val r = signWithBuiltinTestkey(inApk, outApk, context, onProgress)
            if (r != null && r.ok) return r
        }

        return SignResult(false, null,
            "无可用签名密钥（未找到 ytx-release.jks，且内置 testkey 加载失败）")
    }

    /**
     * ⭐ v2.5：自动探测密钥库格式（PKCS12 优先尝试，再 JKS，再 BKS）。
     *   实测工程 ytx-release.jks 实为 PKCS12 格式，固定 "JKS" 会抛
     *   "Invalid keystore format"。
     */
    private fun loadKeyStoreAuto(file: File, storePass: String): KeyStore? {
        for (type in arrayOf("PKCS12", "JKS", "BKS", "JCEKS")) {
            try {
                val k = KeyStore.getInstance(type)
                FileInputStream(file).use { k.load(it, storePass.toCharArray()) }
                LogStore.i(TAG, "密钥库格式探测成功: $type (${file.name})")
                return k
            } catch (_: Throwable) {
                // 尝试下一种
            }
        }
        return null
    }

    /**
     * 用内置 testkey.pk8 (PKCS#8 私钥) + testkey.x509.pem (证书) 签名。
     */
    private fun signWithBuiltinTestkey(
        inApk: File, outApk: File, context: Context, onProgress: (String) -> Unit
    ): SignResult? {
        return try {
            onProgress("使用内置 AOSP testkey 签名...")
            // 私钥（PKCS#8 DER）
            val pk8Bytes = context.assets.open("testkey.pk8").use { it.readBytes() }
            val keySpec = PKCS8EncodedKeySpec(pk8Bytes)
            val privateKey = try {
                KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            } catch (e: Throwable) {
                // 有些 testkey.pk8 是 PKCS#8 但算法标注不同，尝试 EC
                try { KeyFactory.getInstance("EC").generatePrivate(keySpec) }
                catch (_: Throwable) { throw e }
            }

            // 证书（PEM）
            val pemText = context.assets.open("testkey.x509.pem").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val cf = CertificateFactory.getInstance("X.509")
            val cert = pemText.byteInputStream().use {
                cf.generateCertificate(it) as X509Certificate
            }

            doSign(inApk, outApk, "testkey", privateKey, listOf(cert), onProgress)
        } catch (t: Throwable) {
            LogStore.w(TAG, "内置 testkey 签名失败: ${t.message}")
            null
        }
    }

    /** 通用签名流程 */
    private fun doSign(
        inApk: File, outApk: File, alias: String,
        key: PrivateKey, certChain: List<X509Certificate>,
        onProgress: (String) -> Unit
    ): SignResult {
        outApk.parentFile?.mkdirs()
        if (outApk.exists()) outApk.delete()
        onProgress("签名中（v1+v2+v3）...")
        val signerConfig = ApkSigner.SignerConfig.Builder(alias, key, certChain).build()
        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inApk)
            .setOutputApk(outApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
        signer.sign()
        onProgress("签名完成: ${outApk.name} (${outApk.length() / 1024}KB)")
        return SignResult(true, outApk, "OK")
    }

    /** 定位工作区 JKS（含 assets 兜底：把内置 keystore 解出到 filesDir） */
    fun locateDefaultKeystore(): File? {
        val candidates = listOf(
            File("/storage/emulated/0/MT2/apks/yuntuoxiu-dev/ytx-release.jks"),
            File("/storage/emulated/0/MT2/apks/yuntuoxiu-dev/src/ytx-release.jks"),
            File("/storage/emulated/0/MT2/apks/ytx-tools/ytx-release.jks"),
            // ⭐ v2.5：运行时解出的 assets keystore（最可靠，不受外部存储权限影响）
            File(YunTuoXiuApp.instance.filesDir, "ytx-release.jks"),
        )
        candidates.firstOrNull { it.isFile }?.let { return it }
        // ⭐ v2.5：全部不存在 → 尝试从 assets 解出（保证任何环境都可用固定签名）
        return try {
            val dst = File(YunTuoXiuApp.instance.filesDir, "ytx-release.jks")
            if (!dst.exists()) {
                YunTuoXiuApp.instance.assets.open("ytx-release.jks").use { ins ->
                    dst.outputStream().use { os -> ins.copyTo(os) }
                }
                LogStore.i(TAG, "已从 assets 解出 keystore → ${dst.absolutePath}")
            }
            if (dst.isFile) dst else null
        } catch (t: Throwable) {
            LogStore.w(TAG, "assets keystore 解出失败: ${t.message}")
            null
        }
    }
}