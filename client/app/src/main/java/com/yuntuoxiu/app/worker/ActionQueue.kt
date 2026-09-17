package com.yuntuoxiu.app.worker

import android.util.Log
import com.google.gson.Gson
import com.yuntuoxiu.app.YunTuoXiuApp
import com.yuntuoxiu.app.data.ActionPayload
import com.yuntuoxiu.app.data.ActionResponse
import java.io.File

/**
 * action 队列：轮询后端写入的 work/actions 下的 req 文件，回写 resp 文件。
 *
 * ⚠️ 注意：Kotlin 文档注释内不能出现「星号加斜杠」的字面量
 *    （会被当作注释结束符），因此这里不写通配文件名串。
 *
 * 文件路径契约（与后端 shizuku_dump_scheduler.py 一致）：
 *   <task_dir>/work/actions/&lt;seq&gt;_&lt;action&gt;.req.json   后端 -> 客户端
 *   <task_dir>/work/actions/&lt;seq&gt;_&lt;action&gt;.resp.json  客户端 -> 后端
 */
class ActionQueue(private val taskId: String) {

    private val gson = Gson()
    private val dir: File
        get() = File(YunTuoXiuApp.CLOUD_ROOT, "tasks/$taskId/work/actions")

    /** 扫描所有未回执的 .req.json */
    fun pendingPayloads(): List<Pair<ActionPayload, File>> {
        if (!dir.exists()) return emptyList()
        val reqs = dir.listFiles { f -> f.name.endsWith(".req.json") }?.sortedBy { it.name }
            ?: return emptyList()
        val out = mutableListOf<Pair<ActionPayload, File>>()
        for (req in reqs) {
            val seqAction = req.name.removeSuffix(".req.json") // "0001_PRE_CHECK"
            val resp = File(dir, "$seqAction.resp.json")
            if (resp.exists()) continue // 已回执，跳过（幂等）
            try {
                // Gson 可能返回 null，必须判空后再用
                val payload = gson.fromJson(req.readText(), ActionPayload::class.java)
                if (payload != null) {
                    out.add(payload to resp)
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析 action 失败: ${req.name}", e)
            }
        }
        return out
    }

    fun writeResponse(respFile: File, resp: ActionResponse) {
        val tmp = File(respFile.parentFile, respFile.name + ".tmp")
        tmp.writeText(gson.toJson(resp))
        tmp.renameTo(respFile)
    }

    companion object {
        private const val TAG = "ActionQueue"
    }
}
