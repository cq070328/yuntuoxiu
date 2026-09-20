package com.yuntuoxiu.app.engine

import com.android.apksig.ApkSigner
import com.yuntuoxiu.app.LogStore
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * LocalApkSigner —— 本地 APK 签名（v2.0，基于 Google apksig）
 *
 * 用固定 debug 密钥库（App 内置或工作区）对重建后的 APK 签名，
 * 产出 v1+v2+v3 签名，可直接安装。
 */
object LocalApkSigner {

    private const val TAG = "LocalApkSigner"

    /** 默认密钥库参数（与云脱修 CI 用的 ytx-release.jks 一致） */
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
     * @param inApk   待签名 APK
     * @param outApk  输出（已签名）APK
     * @param keystore 密钥库文件（jks）；为 null 时尝试从工作区 ytx-release.jks
     */
    fun sign(
        inApk: File,
        outApk: File,
        keystore: File?,
        storePass: String = DEFAULT_KS_PASS,
        keyAlias: String = DEFAULT_KEY_ALIAS,
        keyPass: String = DEFAULT_KEY_PASS,
        onProgress: (String) -> Unit = {}
    ): SignResult {
        if (!inApk.isFile) return SignResult(false, null, "待签名 APK 不存在: ${inApk.absolutePath}")

        val ks = keystore ?: locateDefaultKeystore()
        if (ks == null || !ks.isFile) {
            return SignResult(false, null, "未找到签名密钥库（ytx-release.jks）")
        }

        return try {
            onProgress("加载密钥库: ${ks.name}")
            val keyStore = KeyStore.getInstance("JKS")
            FileInputStream(ks).use { keyStore.load(it, storePass.toCharArray()) }

            val key = keyStore.getKey(keyAlias, keyPass.toCharArray()) as? PrivateKey
                ?: return SignResult(false, null, "密钥别名不存在或非私钥: $keyAlias")
            val certChain = keyStore.getCertificateChain(keyAlias)
                ?.map { it as X509Certificate }
                ?: return SignResult(false, null, "证书链为空: $keyAlias")

            val signerConfig = ApkSigner.SignerConfig.Builder(
                keyAlias, key, certChain
            ).build()

            outApk.parentFile?.mkdirs()
            onProgress("签名中（v1+v2+v3）...")
            val signer = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(inApk)
                .setOutputApk(outApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
            signer.sign()

            onProgress("签名完成: ${outApk.name} (${outApk.length() / 1024}KB)")
            SignResult(true, outApk, "OK")
        } catch (t: Throwable) {
            LogStore.e(TAG, "签名失败: ${t.message}")
            SignResult(false, null, "签名失败: ${t.message}")
        }
    }

    /**
     * 定位默认密钥库：优先 App 私有目录（由构建/首启释放），
     * 其次工作区 ytx-tools。
     */
    fun locateDefaultKeystore(): File? {
        val candidates = listOf(
            File("/storage/emulated/0/MT2/apks/yuntuoxiu-dev/ytx-release.jks"),
            File("/storage/emulated/0/MT2/apks/yuntuoxiu-dev/src/ytx-release.jks"),
            File("/storage/emulated/0/MT2/apks/ytx-tools/ytx-release.jks"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    /**
     * 生成一个全新 debug 密钥库（当工作区没有可用密钥时）。
     */
    fun generateDebugKeystore(outKs: File): Boolean {
        return try {
            outKs.parentFile?.mkdirs()
            val ks = KeyStore.getInstance("JKS")
            ks.load(null, null)
            // 用 keytool 逻辑生成自签名证书（通过 sun.security 太底层；这里用简易 RSA 自签）
            // 简化：调用系统 keytool 不可行（无 java），故用 BouncyCastle 缺失时跳过。
            // → 实际由 CI 侧 ytx-release.jks 提供，这里仅占位。
            LogStore.w(TAG, "内置生成密钥库暂不支持（请提供 ytx-release.jks）")
            false
        } catch (t: Throwable) {
            LogStore.e(TAG, "生成密钥库失败: ${t.message}")
            false
        }
    }
}