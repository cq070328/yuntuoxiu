package com.yuntuoxiu.app.worker

import android.util.Log
import com.yuntuoxiu.app.YunTuoXiuApp
import com.yuntuoxiu.app.data.DexManifest
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Dex 产出处理：过滤 0 字节空 dex + 计算 MD5 + 分片写入 chunks/。
 *
 * 分片契约（与后端 upload_contract.py 一致）：
 *   chunks/<dex_name>/manifest.json
 *   chunks/<dex_name>/<idx>.part
 */
object DexDumper {

    private const val TAG = "DexDumper"
    private const val CHUNK_SIZE = 4 * 1024 * 1024  // 4MB，与后端 ChunkPolicy 一致

    fun md5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return BigInteger(1, md.digest()).toString(16).padStart(32, '0')
    }

    /**
     * dump 出来的 dex 目录 -> 过滤 -> 分片写入 chunks/。
     *
     * @return 成功上传的 dex 名称列表
     */
    fun filterAndUpload(dumpDir: File, taskId: String): List<String> {
        if (!dumpDir.exists() || !dumpDir.isDirectory) {
            Log.w(TAG, "dump 目录不存在: ${dumpDir.absolutePath}")
            return emptyList()
        }
        val chunksRoot = File(YunTuoXiuApp.CLOUD_ROOT, "tasks/$taskId/chunks")
        val uploaded = mutableListOf<String>()

        val dexFiles = dumpDir.listFiles { f -> f.name.endsWith(".dex") } ?: return emptyList()

        for (dex in dexFiles) {
            // 1) 丢弃 0 字节空 dex
            if (dex.length() == 0L) {
                Log.w(TAG, "丢弃 0 字节空 dex: ${dex.name}")
                continue
            }
            // 2) MD5
            val md5 = md5(dex)
            // 3) 分片写入
            writeChunks(dex, dex.name, md5, chunksRoot)
            uploaded.add(dex.name)
        }
        return uploaded
    }

    private fun writeChunks(dex: File, dexName: String, md5: String, chunksRoot: File) {
        val dexDir = File(chunksRoot, dexName)
        if (!dexDir.exists()) dexDir.mkdirs()

        val total = dex.length()
        val totalChunks = ((total + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()

        val manifest = DexManifest(
            dexName = dexName,
            totalChunks = totalChunks,
            fileSize = total,
            md5 = md5,
            chunkSize = CHUNK_SIZE
        )
        File(dexDir, "manifest.json").writeText(
            com.google.gson.Gson().toJson(manifest)
        )

        dex.inputStream().use { ins ->
            val buf = ByteArray(CHUNK_SIZE)
            var idx = 0
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                val partFile = File(dexDir, "$idx.part")
                if (!partFile.exists()) { // 断点续传：已存在跳过
                    partFile.outputStream().use { os ->
                        os.write(buf, 0, n)
                    }
                }
                idx++
            }
        }
        Log.i(TAG, "已分片写入 $dexName ($total bytes, $totalChunks chunks, md5=$md5)")
    }
}
