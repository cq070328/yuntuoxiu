package com.yuntuoxiu.app.engine

import com.yuntuoxiu.app.LogStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * SystemPatchEngine —— 签名校验绕过（SRPatch 能力集成，v2.0）
 *
 * 原理（来自 SRPatch 逆向分析）：
 *   App 重签名后，运行时自校验签名会失败。本引擎把「签名绕过模块」静态注入 APK：
 *     1) 放入 assets/patch.dex（Java Hook 代码）
 *     2) 放入 lib/arm64-v8a/libSRPatch.so（原生支持）
 *     3) 改 AndroidManifest 的 application:name → 壳入口（触发 com.srp.patch.Init）
 *
 * ⚠️ 本实现为「静态嵌入」路径（SRPatch 自身用法）。
 *    完整的 BlackBox 集成（运行时 Hook）需更大工程。
 *
 * 资产来源：ytx-tools/srpatch/
 */
object SystemPatchEngine {

    private const val TAG = "SystemPatchEngine"

    /** SRPatch 资产目录 */
    private fun assetDir(): File = File("/storage/emulated/0/MT2/apks/ytx-tools/srpatch")

    data class Result(
        val ok: Boolean,
        val outApk: File?,
        val injectedDex: Boolean,
        val injectedSo: Boolean,
        val detail: String,
    )

    /**
     * 把签名绕过模块注入 APK。
     *
     * @param srcApk  源 APK
     * @param outApk  输出 APK
     * @param onProgress 进度
     */
    fun inject(
        srcApk: File,
        outApk: File,
        onProgress: (String) -> Unit = {}
    ): Result {
        if (!srcApk.isFile) return Result(false, null, false, false, "源 APK 不存在")

        val ad = assetDir()
        val patchDex = File(ad, "patch.dex")
        val libSo = File(ad, "lib/arm64-v8a/libSRPatch.so")
        if (!patchDex.isFile) {
            return Result(false, null, false, false,
                "缺少 SRPatch 资产（${patchDex.absolutePath}）")
        }

        var injectedDex = false
        var injectedSo = false

        try {
            val zin = ZipFile(srcApk)
            outApk.parentFile?.mkdirs()
            val zout = ZipOutputStream(java.io.FileOutputStream(outApk))

            val entries = zin.entries().toList()
            val names = entries.map { it.name }.toSet()

            // 1) 复制原条目
            for (e in entries) {
                val name = e.name
                val low = name.lowercase()
                // 同名冲突：现有 patch.dex / libSRPatch.so 先删（避免重复）
                if (name == "assets/patch.dex" || name.endsWith("libSRPatch.so")) {
                    onProgress("跳过已存在的: $name")
                    continue
                }
                val data = zin.getInputStream(e).readBytes()
                val outEntry = if (low.endsWith(".so") || low.endsWith("resources.arsc"))
                    StoredEntry2(name, data, e.time)
                else ZipEntry(name).apply { time = e.time }
                zout.putNextEntry(outEntry)
                zout.write(data)
                zout.closeEntry()
            }

            // 2) 注入 patch.dex
            if (!names.contains("assets/patch.dex")) {
                val dexData = patchDex.readBytes()
                zout.putNextEntry(ZipEntry("assets/patch.dex").apply { time = System.currentTimeMillis() })
                zout.write(dexData)
                zout.closeEntry()
                injectedDex = true
                onProgress("注入 assets/patch.dex (${dexData.size / 1024}KB)")
            }

            // 3) 注入 libSRPatch.so
            if (libSo.isFile && names.none { it.endsWith("libSRPatch.so") }) {
                val soData = libSo.readBytes()
                val entry = StoredEntry2("lib/arm64-v8a/libSRPatch.so", soData, System.currentTimeMillis())
                zout.putNextEntry(entry)
                zout.write(soData)
                zout.closeEntry()
                injectedSo = true
                onProgress("注入 lib/arm64-v8a/libSRPatch.so (${soData.size / 1024}KB)")
            }

            // 4) 注入 patch.txt
            val patchTxt = File(ad, "patch.txt")
            if (patchTxt.isFile && !names.contains("assets/patch.txt")) {
                val t = patchTxt.readBytes()
                zout.putNextEntry(ZipEntry("assets/patch.txt").apply { time = System.currentTimeMillis() })
                zout.write(t)
                zout.closeEntry()
                onProgress("注入 assets/patch.txt")
            }

            zout.close()
            zin.close()

            onProgress("注入完成: dex=$injectedDex so=$injectedSo")
            return Result(true, outApk, injectedDex, injectedSo,
                "已注入签名绕过模块（dex=$injectedDex so=$injectedSo）\n" +
                "⚠️ 还需改 Manifest application:name 才能生效（见 note）")
        } catch (t: Throwable) {
            LogStore.e(TAG, "注入失败: ${t.message}")
            return Result(false, null, false, false, "注入失败: ${t.message}")
        }
    }

    /** SRPatch 资产是否就绪 */
    fun assetReady(): Boolean {
        val ad = assetDir()
        return File(ad, "patch.dex").isFile
    }

    /** 说明（供 UI 展示） */
    fun note(): String = """
签名绕过（SRPatch）说明：
· 原理：Hook PMS，让 App 读到原始签名
· 本机静态注入：assets/patch.dex + libSRPatch.so
· 生效需：Manifest 的 application:name 指向壳入口
· 资产：ytx-tools/srpatch/
    """.trimIndent()
}

/** ZIP STORED 条目 */
private class StoredEntry2(name: String, raw: ByteArray, t: Long) : ZipEntry(name) {
    init {
        method = STORED
        size = raw.size.toLong()
        time = t
        val crc = java.util.zip.CRC32()
        crc.update(raw)
        this.crc = crc.value
    }
}