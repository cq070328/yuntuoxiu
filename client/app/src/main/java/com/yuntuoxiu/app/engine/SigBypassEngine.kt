package com.yuntuoxiu.app.engine

import com.yuntuoxiu.app.LogStore
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * SigBypassEngine —— 去除签名校验（云脱修版实现，v2.0）
 *
 * 参考 SRPatch 原理（Hook PMS 让 App 读到原始签名），但**用云脱修自己的方式实现**：
 *
 *   1) 记录目标 APK 的「原始签名」（在重打包前）
 *   2) 重打包 + 重签名后，把原始签名写入「签名映射表」
 *   3) 目标 App 在 BlackBox 沙箱内运行时，云脱修的 PMS 代理读取映射表，
 *      把 getPackageInfo/getApplicationInfo 的签名替换为原始签名
 *
 * 这样 App 自校验签名时读到的是原始签名 → 通过校验。
 *
 * ⚠️ 与 SRPatch 的区别：
 *   · SRPatch 用 Xposed/注入 Hook 系统 PMS（需 root/Xposed）
 *   · 本引擎用 BlackBox 沙箱内的 PMS 代理（无需 root，云脱修内置）
 */
object SigBypassEngine {

    private const val TAG = "SigBypassEngine"

    /** 签名映射表路径 */
    private fun mapFile(): File = File("/storage/emulated/0/MT2/apks/unpackcloud/sig_map.json")

    data class SigRecord(
        val packageName: String,
        val originalSignature: String, // base64 的签名字节
        val apkSize: Long,
        val timestamp: Long,
    )

    /**
     * 记录目标 APK 的原始签名（重打包前调用）。
     */
    fun recordOriginalSignature(apk: File, packageName: String): SigRecord? {
        return try {
            val certs = extractSignatures(apk)
            if (certs.isEmpty()) {
                LogStore.w(TAG, "APK 无签名（未签名）: ${apk.name}")
                return null
            }
            // 取第一个证书的 DER 字节，base64
            val der = certs[0].encoded
            val b64 = android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP)

            saveRecord(SigRecord(packageName, b64, apk.length(), System.currentTimeMillis()))
            LogStore.i(TAG, "已记录原始签名: $packageName (${der.size}B)")
            SigRecord(packageName, b64, apk.length(), System.currentTimeMillis())
        } catch (t: Throwable) {
            LogStore.e(TAG, "记录签名失败: ${t.message}")
            null
        }
    }

    /** 提取 APK 的签名证书（通过 zip 读 META-INF/*.RSA/*.DSA/*.EC） */
    private fun extractSignatures(apk: File): List<X509Certificate> {
        val out = ArrayList<X509Certificate>()
        try {
            java.util.zip.ZipFile(apk).use { zip ->
                val cf = CertificateFactory.getInstance("X.509")
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val n = e.name
                    val isSig = n.startsWith("META-INF/") &&
                            (n.endsWith(".RSA", true) || n.endsWith(".DSA", true) ||
                                    n.endsWith(".EC", true))
                    if (!isSig) continue
                    try {
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        // PKCS#7 容器，提取其中的 X509 证书
                        cf.generateCertificates(bytes.byteInputStream()).forEach {
                            out.add(it as X509Certificate)
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    /** 保存签名记录到映射表 */
    private fun saveRecord(rec: SigRecord) {
        try {
            val f = mapFile()
            f.parentFile?.mkdirs()
            val gson = com.google.gson.Gson()
            val map = loadMap().toMutableMap()
            map[rec.packageName] = rec
            f.writeText(gson.toJson(map))
        } catch (t: Throwable) {
            LogStore.e(TAG, "保存签名映射失败: ${t.message}")
        }
    }

    /** 读取签名映射表 */
    fun loadMap(): Map<String, SigRecord> {
        return try {
            val f = mapFile()
            if (!f.isFile) return emptyMap()
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<Map<String, SigRecord>>() {}.type
            gson.fromJson<Map<String, SigRecord>>(f.readText(), type) ?: emptyMap()
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    /** 查询某包的原始签名（供 PMS 代理调用） */
    fun originalSignatureOf(packageName: String): ByteArray? {
        val rec = loadMap()[packageName] ?: return null
        return try {
            android.util.Base64.decode(rec.originalSignature, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    data class Result(
        val ok: Boolean,
        val detail: String,
        val recorded: Boolean,
    )

    /**
     * 自动处理：记录原始签名（供后续沙箱运行时的 PMS 代理使用）。
     *
     * @param srcApk 原始 APK（重打包前）
     * @param packageName 目标包名
     */
    fun apply(srcApk: File, packageName: String?, onProgress: (String) -> Unit = {}): Result {
        if (!srcApk.isFile) return Result(false, "源 APK 不存在", false)

        val pkg = packageName ?: guessPackageName(srcApk)
        if (pkg.isNullOrBlank()) {
            return Result(false, "无法确定包名（请显式传入）", false)
        }

        onProgress("读取原始签名: $pkg")
        val rec = recordOriginalSignature(srcApk, pkg)
            ?: return Result(false, "未能提取原始签名（APK 可能未签名）", false)

        onProgress("✅ 已记录原始签名，后续沙箱运行将自动伪造")
        return Result(true, "已记录 $pkg 的原始签名（沙箱运行自动伪造）", true)
    }

    /** 从 AndroidManifest（二进制）猜包名 */
    private fun guessPackageName(apk: File): String? {
        return try {
            java.util.zip.ZipFile(apk).use { zip ->
                val e = zip.getEntry("AndroidManifest.xml") ?: return null
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                // 二进制 AXML 里包名是 UTF-16LE 字符串，扫描 "L...;" 形式不可靠
                // 用简单启发：找 package= 后的 UTF-16 串
                // 这里退化为：交给调用方（返回 null）
                null
            }
        } catch (_: Throwable) { null }
    }
}