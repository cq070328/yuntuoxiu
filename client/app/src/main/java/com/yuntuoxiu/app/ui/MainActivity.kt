package com.yuntuoxiu.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuntuoxiu.app.LogStore
import com.yuntuoxiu.app.R
import com.yuntuoxiu.app.YunTuoXiuApp
import com.yuntuoxiu.app.data.SubmitResult
import com.yuntuoxiu.app.data.TaskGroup
import com.yuntuoxiu.app.data.TaskMetaView
import com.yuntuoxiu.app.data.TaskRepository
import com.yuntuoxiu.app.shizuku.ShizukuClient
import com.yuntuoxiu.app.shizuku.ShizukuShellExecutor
import com.yuntuoxiu.app.worker.WorkerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 云脱修 主界面
 *
 * 功能：
 *  - Shizuku 授权 / Worker 启停
 *  - 任务列表（图标 + 应用名 + 三状态徽章 + 长按菜单）
 *  - 选择 APK（文件 / 已安装应用，带搜索）
 *  - 内嵌实时日志 + 清除日志
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rvTasks: RecyclerView
    private lateinit var tvShizuku: TextView
    private lateinit var tvTaskCount: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: android.widget.ScrollView
    private lateinit var adapter: TaskAdapter
    private var refreshing = false
    /** ⭐ v1.6.5 日志增量游标（-1 = 未初始化） */
    private var logSeq: Int = -1
    /** 是否存在活跃（非终态）任务；用于自适应刷新间隔 */
    @Volatile private var hasActiveTask = false

    /** 应用列表项 */
    private data class AppItem(
        val label: String,
        val packageName: String,
        val sourceDir: String,
        val icon: Drawable?
    )

    /** ⭐ v1.6.6 列表项 ViewHolder（滚动复用，避免 findViewById） */
    private class AppViewHolder(
        val icon: ImageView,
        val name: TextView,
        val pkg: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        LogStore.i(TAG, "MainActivity.onCreate 开始")

        rvTasks = findViewById(R.id.rvTasks)
        tvShizuku = findViewById(R.id.tvShizuku)
        tvTaskCount = findViewById(R.id.tvTaskCount)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)

        // 长按 Termux 状态行 -> 诊断
        findViewById<android.widget.TextView>(R.id.tvTermuxStatus)?.setOnLongClickListener {
            diagnoseTermux()
            true
        }

        adapter = TaskAdapter(
            onClick = { task -> openDetail(task) },
            onLongClick = { task -> showTaskMenu(task) },
        )
        rvTasks.layoutManager = LinearLayoutManager(this)
        rvTasks.adapter = adapter

        findViewById<View>(R.id.btnGrantShizuku).setOnClickListener {
            if (ShizukuClient.isGranted()) {
                Toast.makeText(this, "✅ Shizuku 已授权", Toast.LENGTH_SHORT).show()
            } else {
                // v1.6.1：友好指引（重装 APP 会丢授权，需重新授予）
                ShizukuClient.requestPermission()
                showResultDialog("需要 Shizuku 授权",
                    "请在弹窗中点「允许」。若没弹窗或点错，请手动：\n\n" +
                    "1. 打开 Shizuku Manager\n" +
                    "2. 找到「云脱修」\n" +
                    "3. 打开开关授予权限\n" +
                    "4. 返回本应用\n\n" +
                    "⚠️ 提示：重装 APP 会丢失授权（UID 变化），\n" +
                    "   需重新授权一次。之后覆盖安装可保留。")
            }
        }

        findViewById<View>(R.id.btnPickApk).setOnClickListener { chooseApkSource() }

        // ⭐ v1.9.2：「处理」按钮已移除（与「一键脱修」合并为单一入口）。
        //   任务提交后，Worker 由 Application/一键脱修自动拉起；
        //   用户只需：选APK → 一键脱修。

        // 【新增】清除日志按钮
        findViewById<View>(R.id.btnClearLog).setOnClickListener {
            showConfirmDialog("清除日志", "确认清除全部运行日志？", "清除", danger = true) {
                LogStore.clear()
                logSeq = -1                 // ⭐ 重置增量游标
                tvLog.text = ""
                refreshLog()
                Toast.makeText(this, "日志已清除", Toast.LENGTH_SHORT).show()
            }
        }

        // 【v1.6】选择构建后端（云端 / 容器 / Termux / 自动）
        findViewById<View>(R.id.btnChooseBackend)?.setOnClickListener {
            chooseBuildBackend()
        }

        // 【v1.7.0】一键脱修
        //   · worker 在线 → 直接走 APP 全自动流程
        //   · worker 离线 → 引导到 Operit（容器归 Operit，APP 无法直启）
        findViewById<View>(R.id.btnDiagnose)?.setOnClickListener {
            runOneClickUnpack()
        }

        // 【v1.6】工具面板
        findViewById<View>(R.id.btnOpenTools)?.setOnClickListener {
            showToolsPanel()
        }

        // 版本号显示
        findViewById<TextView>(R.id.tvVersion)?.text = "v" + appVersionName()

        // 【v2.0】本地引擎按钮：
        //   单击 = 本地引擎自检（脱壳/修复/签名）
        //   长按 = 工具面板
        findViewById<View>(R.id.btnStartDaemon)?.apply {
            setOnClickListener {
                lifecycleScope.launch {
                    val rep = withContext(Dispatchers.IO) {
                        val chk = com.yuntuoxiu.app.engine.LocalUnpackEngine
                            .checkAvailability(this@MainActivity)
                        val ready = com.yuntuoxiu.app.engine.LocalUnpackEngine.isReady()
                        val ks = com.yuntuoxiu.app.engine.LocalApkSigner.locateDefaultKeystore()
                        val sb = StringBuilder("== 本地引擎检查 (v2.0) ==\n\n")
                        sb.append("[本地脱壳引擎]\n")
                        sb.append("  ${if (chk.available) "✅" else "❌"} native (${chk.abi})\n")
                        sb.append("      libblackdex.so ${if (chk.soMain) "✅" else "❌"}")
                        sb.append("  libblackdex_d.so ${if (chk.soDump) "✅" else "❌"}\n")
                        sb.append("  ${if (ready) "✅" else "⚠️"} 引擎初始化\n")
                        chk.problems.forEach { sb.append("      ⚠️ $it\n") }
                        sb.append("\n[修复/签名]\n")
                        sb.append("  ✅ 修复引擎\n")
                        sb.append("  ${if (ks != null) "✅" else "❌"} 密钥库 ${ks?.name ?: "(缺失)"}\n")

                        val allOk = chk.available && ks != null
                        sb.append("\n== ${if (allOk) "就绪：可本地脱壳+修复" else "部分缺失"} ==\n")
                        sb.toString()
                    }
                    refreshEngineStatus()
                    showResultDialog("本地引擎", rep)
                }
            }
            setOnLongClickListener {
                showToolsPanel()
                true
            }
        }

        requestPermissionsIfNeeded()
        startAutoRefresh()
        LogStore.i(TAG, "MainActivity.onCreate 完成")
    }

    override fun onResume() {
        super.onResume()
        tvShizuku.text = if (ShizukuClient.isGranted())
            "✅ Shizuku 已授权（ABI: arm64-v8a）"
        else "⚠️ Shizuku 未授权"
        findViewById<TextView>(R.id.tvShizukuBadge)?.setTextColor(
            if (ShizukuClient.isGranted()) 0xFF3FB950.toInt() else 0xFFF85149.toInt())
        refreshEngineStatus()
        refreshBackendStatus()   // v1.6：后端状态
        refreshTasks()
        refreshLog()
    }

    /** 刷新「本地引擎」状态（v1.6.1：不再依赖 Termux）。
     *
     *  检查项（都是「本地/云端」能力）：
     *    · NPatch 素材（CLI 注入必需）
     *    · 脱壳模块（Xposed）
     *    · 构建后端偏好
     *    · token（云端）
     */
    private fun refreshEngineStatus() {
        try {
            val tv = findViewById<TextView>(R.id.tvTermuxStatus) ?: return
            val badge = findViewById<TextView>(R.id.tvServiceBadge)

            // v2.0：本地引擎状态（不再依赖 NPatch/脱壳模块/worker）
            val chk = com.yuntuoxiu.app.engine.LocalUnpackEngine.checkAvailability(this)
            val ready = com.yuntuoxiu.app.engine.LocalUnpackEngine.isReady()
            val ks = com.yuntuoxiu.app.engine.LocalApkSigner.locateDefaultKeystore()

            tv.text = when {
                !chk.available -> "本地引擎：⚠️ " + chk.problems.firstOrNull().orEmpty()
                !ready -> "本地引擎：⚠️ 已就绪但未初始化"
                ks == null -> "本地引擎：✅ 可脱壳（签名缺密钥）"
                else -> "本地引擎：✅ 就绪（脱壳+修复+签名）"
            }
            badge?.setTextColor(
                if (chk.available && ready) 0xFF3FB950.toInt()
                else if (chk.available) 0xFFF0883E.toInt()
                else 0xFFF85149.toInt())
        } catch (t: Throwable) {
            LogStore.w(TAG, "刷新引擎状态失败: ${t.message}")
        }
    }

/**
     * v2.0 环境诊断（长按「本地引擎」行触发）。
     * 输出：本地脱壳引擎 + 修复/签名 + Shizuku + 工作区（全本地，无终端依赖）
     */
    private fun diagnoseTermux() {
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val ws = YunTuoXiuApp.WORKSPACE_ROOT
                val sb = StringBuilder()
                sb.append("== 云脱修 环境诊断 (v2.0 本地化) ==\n\n")

                sb.append("[本地脱壳引擎]\n")
                val chk = com.yuntuoxiu.app.engine.LocalUnpackEngine.checkAvailability(this@MainActivity)
                sb.append("  ${if (chk.available) "✅" else "❌"} native (${chk.abi})\n")
                sb.append("  ${if (chk.soMain) "✅" else "❌"} libblackdex.so\n")
                sb.append("  ${if (chk.soDump) "✅" else "❌"} libblackdex_d.so\n")
                sb.append("  ${if (com.yuntuoxiu.app.engine.LocalUnpackEngine.isReady()) "✅" else "⚠️"} 引擎初始化\n")
                if (chk.problems.isNotEmpty()) {
                    sb.append("  ⚠️ ${chk.problems.joinToString("; ")}\n")
                }
                sb.append("\n[本地修复/签名]\n")
                sb.append("  ✅ 修复引擎（Kotlin）\n")
                val ks = com.yuntuoxiu.app.engine.LocalApkSigner.locateDefaultKeystore()
                sb.append("  ${if (ks != null) "✅" else "❌"} 密钥库 ${ks?.name ?: "(缺失)"}\n")

                sb.append("\n[Shizuku（可选）]\n")
                sb.append("  ${if (ShizukuClient.isGranted()) "✅" else "⚠️"} 已授权${if (ShizukuClient.isGranted()) "" else "（不影响本地脱壳）"}\n")

                sb.append("\n[工作区]\n")
                sb.append("  可读: ${File(ws).exists()}\n")
                sb.append("  dump 目录: ${com.yuntuoxiu.app.engine.LocalUnpackEngine.getDumpDir().absolutePath}\n")
                sb.append("  任务数: ${File("$ws/unpackcloud/tasks").listFiles()?.size ?: 0}\n")
                sb.toString()
            }
            showResultDialog("环境诊断", report)
        }
    }

    // ---------------- 任务长按菜单 ----------------

    // ---------------- v1.6 新增：后端选择 / 诊断 / 工具 ----------------

    /** 应用版本名 */
    private fun appVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (t: Throwable) { "?" }
    }

    /** 选择构建后端（v2.0 已废弃——全本地化，无后端可选） */
    private fun chooseBuildBackend() {
        showResultDialog("构建后端", "v2.0 已全面本地化：\n脱壳/修复/签名均在 App 内完成，无需选择后端。")
    }
    private fun readBackendPref(): String = "local"
    private fun writeBackendPref(v: String) { /* v2.0 无需后端偏好 */ }
    /** 刷新「本地引擎」状态行（原「构建后端」） */
    private fun refreshBackendStatus() {
        lifecycleScope.launch {
            val (txt, ok) = withContext(Dispatchers.IO) {
                val chk = com.yuntuoxiu.app.engine.LocalUnpackEngine.checkAvailability(this@MainActivity)
                val ready = com.yuntuoxiu.app.engine.LocalUnpackEngine.isReady()
                val t = when {
                    !chk.available -> "本地引擎：不可用（${chk.problems.firstOrNull() ?: "?"}）"
                    !ready -> "本地引擎：已就绪未初始化"
                    else -> "本地引擎：✅ ${chk.abi}"
                }
                t to (chk.available && ready)
            }
            findViewById<TextView>(R.id.tvBackend)?.text = txt
            findViewById<TextView>(R.id.tvBackendBadge)?.setTextColor(
                if (ok) 0xFF3FB950.toInt() else 0xFFF85149.toInt())
        }
    }

    /**
     * ⭐ v1.6.2 一键脱修（全自动串行）
     *
     * 流程：
     *   ① 壳诊断（本地 ShellDetect）
     *   ② 壳清理（本地 cleanShellSo）→ 去壳版 APK
     *   ③ CLI 注入 NPatch + 脱壳模块（embed）
     *   ④ Shizuku 安装 + 启动目标
     *   ⑤ 等待模块 dump（10-25s）
     *   ⑥ Shizuku 收集 dump
     *   ⑦ 构建（DEX 替换 + 对齐 + 签名）
     *   ⑧ 诊断产物（能否进）
     *
     * 任何一步失败 → 明确报错 + 停在原地（不静默继续）
     */
    private fun runOneClickUnpack() {
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) {
                try { TaskRepository.listTasks() } catch (t: Throwable) { emptyList() }
            }
            if (tasks.isEmpty()) {
                Toast.makeText(this@MainActivity, "请先「选APK」创建任务", Toast.LENGTH_LONG).show()
                return@launch
            }
            // v2.0：全本地化，无需 worker/容器，直接进入
            val names = tasks.map { "${it.displayName}  [${it.stateLabel}]" }.toTypedArray()
            showItemsDialog(
                "一键脱修 · 选择任务",
                names.map { Triple("", it, "") }
            ) { which ->
                oneClickRun(tasks[which])
            }
        }
    }

    /** v2.0：后端离线提示已废弃（全本地化，无后端） */
    private fun showWorkerOfflineDialog(onRetry: () -> Unit) {
        showResultDialog("提示", "v2.0 已全面本地化，无需启动任何后端。")
    }
    /** 打开 Operit（主界面） */
    private fun openOperit() {
        try {
            val i = packageManager.getLaunchIntentForPackage("com.ai.assistance.operit")
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } else {
                Toast.makeText(this, "未找到 Operit", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "打开失败: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 复制到剪贴板（通用） */
    private fun copyToClipboard(label: String, text: String) {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        } catch (t: Throwable) {
            LogStore.w(TAG, "复制失败: ${t.message}")
        }
    }

    /**
     * ⭐ v1.9.0 一键脱修（统一入口 —— 驱动后端状态机）
     *
     * 架构（顶级工程师定稿：单一真相源）：
     *   任务创建 100% 由容器后端 watcher 负责；
     *   本按钮**不再让 APP 自己跑注入/安装**（那样会与后端状态机并发冲突），
     *   而是：
     *     ① 确保容器后端在线（离线则引导启动）
     *     ② 从「已提交任务」里选一个
     *     ③ **推进并监控后端状态机**：轮询 task_meta，实时显示进度
     *     ④ 需要设备操作的动作（CLEAR/INSTALL/START/DUMP）由 WorkerService
     *        自动消费 work/actions/ 并回执（无需本函数干预）
     *     ⑤ 任务到终态 -> 展示结果（成功/失败原因）
     *
     * 好处：
     *   · 彻底消除「APP 与后端并发注入」的资源竞争/OOM；
     *   · APP 只做「设备执行器 + UI」，职责单一；
     *   · 进度可见（状态机每步都反映在 meta 上）。
     */
    private fun oneClickRun(task: TaskMetaView) {
        val pkg = task.lookupPackage
        if (pkg.isNullOrBlank()) {
            showResultDialog("一键脱修", "❌ 该任务没有包名（需从「已安装应用」选择）")
            return
        }

        lifecycleScope.launch {
            val tid = task.taskId
            val log = StringBuilder("== 一键脱修(本地) · $tid ==\n\n")
            log.append("全本地化：脱壳 → 修复 → 签名，均在 App 内完成\n\n")

            val result = withContext(Dispatchers.IO) {
                try {
                    val pkg = task.lookupPackage
                    val srcApk = task.sourceApk

                    // ① 本地脱壳
                    log.append("[1/3] 本地脱壳...\n")
                    val dexes = if (srcApk.isNotBlank() && File(srcApk).isFile) {
                        com.yuntuoxiu.app.engine.LocalUnpackEngine.dumpFile(
                            this@MainActivity, File(srcApk)) { }
                    } else if (!pkg.isNullOrBlank()) {
                        com.yuntuoxiu.app.engine.LocalUnpackEngine.dumpInstalled(
                            this@MainActivity, pkg) { }
                    } else emptyList()
                    if (dexes.isEmpty()) return@withContext "❌ 脱壳未产出 DEX"
                    log.append("      ✅ " + dexes.size + " 个 dex\n")

                    // 归拢到任务 dump
                    val taskDir = File(YunTuoXiuApp.CLOUD_ROOT, "tasks/$tid")
                    val dumpDir = File(taskDir, "dump"); dumpDir.mkdirs()
                    dexes.forEach { runCatching { it.copyTo(File(dumpDir, it.name), true) } }

                    // ② 本地修复
                    log.append("[2/3] 本地修复(清壳+重组)...\n")
                    if (srcApk.isBlank() || !File(srcApk).isFile) {
                        return@withContext "❌ 缺少原 APK，无法重组"
                    }
                    val repaired = File(taskDir, "build/repaired.apk")
                    val res = com.yuntuoxiu.app.engine.LocalRepairEngine.rebuild(
                        File(srcApk), dexes, repaired, cleanShell = true
                    ) ?: return@withContext "❌ 重组失败"
                    log.append("      ✅ dex=${res.dexCount} 清壳=${res.removedShell}\n")

                    // ③ 本地签名
                    log.append("[3/3] 本地签名...\n")
                    val signed = File(taskDir, "build/signed.apk")
                    val sr = com.yuntuoxiu.app.engine.LocalApkSigner.sign(repaired, signed, null)
                    if (!sr.ok) {
                        log.append("      ⚠️ 签名失败: ${sr.detail}\n")
                        log.append("      修复产物: ${repaired.absolutePath}\n")
                        "⚠️ 脱壳+修复完成（签名失败）"
                    } else {
                        log.append("      ✅ ${signed.absolutePath}\n")
                        // 复制一份到工作区根，便于查找
                        runCatching {
                            signed.copyTo(File(YunTuoXiuApp.WORKSPACE_ROOT, "云脱修-$tid.apk"), true)
                        }
                        "✅ 完成"
                    }
                } catch (e: Throwable) {
                    "❌ 异常: ${e.message}"
                }
            }
            log.append("\n$result\n")
            showResultDialog("一键脱修(本地) · $task", log.toString())
            refreshTasks()
        }
    }

    private fun diagnoseTask(task: TaskMetaView) {
        val outApk = File("${YunTuoXiuApp.WORKSPACE_ROOT}/云脱修-${task.taskId}.apk")
        val pkg = task.lookupPackage ?: "?"
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "正在诊断…", Toast.LENGTH_SHORT).show()
            val out = withContext(Dispatchers.IO) {
                try {
                    ShizukuShellExecutor.exec(
                        "sh ${YunTuoXiuApp.WORKSPACE_ROOT}/ytx_diag_launch.sh " +
                        "'${outApk.absolutePath}' '$pkg' 2>&1 | tail -20"
                    ).getString("stdout") ?: "（无输出）"
                } catch (t: Throwable) { "诊断失败: ${t.message}" }
            }
            showResultDialog("诊断结果", out)
        }
    }

    /** 工具面板（v1.6.2：真功能，不是弹窗说明） */
    private fun showToolsPanel() {
        showMenuDialog(
            "工具",
            listOf(
                Triple("🔍", "壳诊断", "本地读 APK 判定壳类型（秒级）"),
                Triple("🧹", "去壳清理", "删壳 so/assets（本地）"),
                Triple("📤", "本地脱壳", "App 内引擎脱 DEX（无需终端）"),
                Triple("🔧", "本地修复", "清壳重组 + 签名（App 内）"),
                Triple("🩹", "规则修补", "Manifest入口/反调试/壳串（本地）"),
                Triple("📋", "环境自检", "检查 Shizuku / 本地引擎"),
                Triple("ℹ️", "工具用法说明", "各功能说明")
            )
        ) { which ->
            when (which) {
                0 -> toolShellDetect()
                1 -> toolUnpackClean()
                2 -> toolCollectDump()
                3 -> toolCloudBuild()
                4 -> toolSmaliPatch()
                5 -> toolEnvCheck()
                6 -> showToolsHelp()
            }
        }
    }

    /** 工具 1：壳诊断（本地 ShellDetect） */
    private fun toolShellDetect() {
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) {
                try { TaskRepository.listTasks() } catch (t: Throwable) { emptyList() }
            }
            if (tasks.isEmpty()) {
                Toast.makeText(this@MainActivity, "暂无任务（先选个 APK）", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = tasks.map { it.displayName }.toTypedArray()
            showItemsDialog(
            "选择要诊断的 APK",
            names.map { Triple("", it, "") }
        ) { which ->
            val t = tasks[which]
            lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "诊断中…", Toast.LENGTH_SHORT).show()
            val rep = withContext(Dispatchers.IO) {
            try {
            val apk = File(t.sourceApk)
            if (!apk.exists()) {
            "❌ 原 APK 不存在: ${t.sourceApk}"
            } else {
            val v = com.yuntuoxiu.app.worker.ShellDetect.detect(apk.absolutePath)
            buildString {
            append("标签: ${v.tag}\n")
            append("置信度: ${(v.confidence * 100).toInt()}%\n")
            append("DEX 数: ${v.dexCount}\n")
            append("全部命中: ${v.tagsAll.joinToString(", ")}\n\n")
            append("依据:\n")
            v.reasons.take(10).forEach { append("  · $it\n") }
            }
            }
            } catch (e: Throwable) {
            "诊断失败: ${e.message}"
            }
            }
            showResultDialog("壳诊断结果", rep)
            }
        }
        }
    }

    /** 工具 2：去壳清理（本地实现，删壳 so + 找真实 Application） */
    private fun toolUnpackClean() {
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) {
                try { TaskRepository.listTasks() } catch (t: Throwable) { emptyList() }
            }
            if (tasks.isEmpty()) {
                Toast.makeText(this@MainActivity, "暂无任务", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = tasks.map { it.displayName }.toTypedArray()
            showItemsDialog(
            "选择要去壳的 APK",
            names.map { Triple("", it, "") }
        ) { which ->
            val t = tasks[which]
            lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "清理中…", Toast.LENGTH_LONG).show()
            val (out, ok) = withContext(Dispatchers.IO) {
            try {
            val src = File(t.sourceApk)
            if (!src.exists()) return@withContext ("❌ 原 APK 不存在" to false)
            val dst = File(YunTuoXiuApp.CLOUD_ROOT,
            "tasks/${t.taskId}/cleaned.apk")
            dst.parentFile?.mkdirs()
            val n = com.yuntuoxiu.app.worker.ShellDetect.cleanShellSo(
            src.absolutePath, dst.absolutePath)
            ("✅ 已清理 $n 个壳条目 ->\n${dst.absolutePath}" to true)
            } catch (e: Throwable) {
            ("清理失败: ${e.message}" to false)
            }
            }
            showResultDialog("去壳清理", out)
            }
        }
        }
    }

    /** 工具 3：收集 Dump（Shizuku） */
    private fun toolCollectDump() {
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) {
                try { TaskRepository.listTasks() } catch (t: Throwable) { emptyList() }
            }
            val cands = tasks.filter { it.lookupPackage != null }
            if (cands.isEmpty()) {
                Toast.makeText(this@MainActivity, "无带包名的任务", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = cands.map { it.displayName }.toTypedArray()
            showItemsDialog(
                "选择要本地脱壳的任务",
                names.map { Triple("", it, "") }
            ) { which ->
                val t = cands[which]
                val pkg = t.lookupPackage!!
                val srcApk = t.sourceApk
                lifecycleScope.launch {
                    Toast.makeText(this@MainActivity, "本地脱壳中…", Toast.LENGTH_SHORT).show()
                    val out = withContext(Dispatchers.IO) {
                        try {
                            // 优先对本地 APK 脱壳，回退对已安装包脱壳
                            val dexes = if (srcApk.isNotBlank() && File(srcApk).isFile) {
                                com.yuntuoxiu.app.engine.LocalUnpackEngine.dumpFile(
                                    this@MainActivity, File(srcApk)) { }
                            } else {
                                com.yuntuoxiu.app.engine.LocalUnpackEngine.dumpInstalled(
                                    this@MainActivity, pkg) { }
                            }
                            if (dexes.isEmpty()) {
                                "❌ 本地脱壳未产出 DEX\n（目标可能未启动 / 壳对抗 / 引擎异常）"
                            } else {
                                // 归拢到任务 dump 目录
                                val dest = File(YunTuoXiuApp.CLOUD_ROOT,
                                    "tasks/${t.taskId}/dump")
                                dest.mkdirs()
                                var n = 0
                                for (d in dexes) {
                                    try {
                                        d.copyTo(File(dest, d.name), overwrite = true); n++
                                    } catch (_: Throwable) {}
                                }
                                "✅ 本地脱壳 $n 个 dex -> ${dest.absolutePath}"
                            }
                        } catch (e: Throwable) {
                            "本地脱壳失败: ${e.message}"
                        }
                    }
                    showResultDialog("本地脱壳", out)
                }
            }
        }
    }

    /** 工具 4：本地修复 + 签名（原「云端构建」，已完全本地化） */
    private fun toolCloudBuild() {
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) {
                try { TaskRepository.listTasks() } catch (t: Throwable) { emptyList() }
            }
            val cands = tasks.filter { it.sourceApk.isNotBlank() }
            if (cands.isEmpty()) {
                Toast.makeText(this@MainActivity, "暂无任务", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = cands.map { it.displayName }.toTypedArray()
            showItemsDialog(
                "选择要本地修复的任务",
                names.map { Triple("", it, "") }
            ) { which ->
                val t = cands[which]
                val dumpDir = File(YunTuoXiuApp.CLOUD_ROOT, "tasks/${t.taskId}/dump")
                if (!dumpDir.isDirectory || (dumpDir.listFiles()?.isEmpty() != false)) {
                    Toast.makeText(this@MainActivity,
                        "该任务无 dump（先用「本地脱壳」）", Toast.LENGTH_LONG).show()
                    return@showItemsDialog
                }
                lifecycleScope.launch {
                    Toast.makeText(this@MainActivity,
                        "本地修复中…", Toast.LENGTH_LONG).show()
                    val out = withContext(Dispatchers.IO) {
                        try {
                            val taskDir = File(YunTuoXiuApp.CLOUD_ROOT, "tasks/${t.taskId}")
                            val dexes = com.yuntuoxiu.app.engine.LocalUnpackEngine
                                .collectDex(dumpDir)
                            if (dexes.isEmpty()) return@withContext "❌ 无可用 DEX"
                            val repaired = File(taskDir, "build/repaired.apk")
                            val res = com.yuntuoxiu.app.engine.LocalRepairEngine.rebuild(
                                File(t.sourceApk), dexes, repaired, cleanShell = true
                            ) ?: return@withContext "❌ 本地重组失败"
                            // 签名
                            val signed = File(taskDir, "build/signed.apk")
                            val sr = com.yuntuoxiu.app.engine.LocalApkSigner.sign(
                                repaired, signed, null
                            )
                            if (!sr.ok) {
                                "⚠️ 修复成功但签名失败: ${sr.detail}\n修复产物: ${repaired.absolutePath}"
                            } else {
                                "✅ 本地修复+签名完成\n" +
                                "  dex=${res.dexCount} 清壳=${res.removedShell}\n" +
                                "  产物: ${signed.absolutePath}"
                            }
                        } catch (e: Throwable) {
                            "本地修复失败: ${e.message}"
                        }
                    }
                    showResultDialog("本地修复结果", out)
                }
            }
        }
    }

    /** 工具 5：规则修补（原「smali 替换」，v2.0 纯 Kotlin 本地化） */
    private fun toolSmaliPatch() {
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) {
                try { TaskRepository.listTasks() } catch (t: Throwable) { emptyList() }
            }
            val cands = tasks.filter { it.sourceApk.isNotBlank() && java.io.File(it.sourceApk).isFile }
            if (cands.isEmpty()) {
                Toast.makeText(this@MainActivity, "无可用任务（需有原 APK）", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = cands.map { it.displayName }.toTypedArray()
            showItemsDialog(
                "规则修补 · 选择任务",
                names.map { Triple("", it, "") }
            ) { which ->
                val t = cands[which]
                showItemsDialog(
                    "规则修补模式",
                    listOf(
                        Triple("🔍", "仅修补 Manifest", "加固入口 → 真实 Application"),
                        Triple("🩹", "全面修补", "Manifest + 反调试串 + 壳清理")
                    )
                ) { mode ->
                    patchRun(t, full = mode == 1)
                }
            }
        }
    }

    private fun patchRun(task: TaskMetaView, full: Boolean) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity,
                if (full) "全面修补中…" else "修补 Manifest 中…", Toast.LENGTH_SHORT).show()
            val out = withContext(Dispatchers.IO) {
                try {
                    val taskDir = java.io.File(YunTuoXiuApp.CLOUD_ROOT, "tasks/${task.taskId}")
                    val outApk = java.io.File(taskDir, "build/patched.apk")
                    val res = com.yuntuoxiu.app.engine.LocalSmaliPatcher.patch(
                        java.io.File(task.sourceApk), outApk,
                        realApp = null,
                        cleanManifest = true,
                        cleanAntiDebug = full
                    ) { }
                    if (res.ok) {
                        "✅ 修补完成\n" +
                        "  Manifest 改: ${res.manifestChanged}\n" +
                        "  清壳 so: ${res.removedShellSo}\n" +
                        "  清壳 assets: ${res.removedShellAssets}\n" +
                        "  反调试清理: ${res.antiDebugCleaned}\n" +
                        "  产物: ${res.outApk?.absolutePath}"
                    } else "❌ ${res.detail}"
                } catch (e: Throwable) {
                    "❌ 修补失败: ${e.message}"
                }
            }
            showResultDialog(if (full) "全面修补" else "Manifest 修补", out)
        }
    }
/** 工具 6：环境自检（v2.0 纯本地化） */
    private fun toolEnvCheck() {
        lifecycleScope.launch {
            val rep = withContext(Dispatchers.IO) {
                val sb = StringBuilder("== 云脱修 环境自检 (v2.0 本地化) ==\n\n")

                // ---- 本地引擎 ----
                sb.append("[本地脱壳引擎]\n")
                val chk = com.yuntuoxiu.app.engine.LocalUnpackEngine.checkAvailability(this@MainActivity)
                val ready = com.yuntuoxiu.app.engine.LocalUnpackEngine.isReady()
                sb.append("  ${if (chk.available) "✅" else "❌"} native 库 (${chk.abi})\n")
                sb.append("      " + if (chk.soMain) "libblackdex.so ✓" else "libblackdex.so ✗")
                sb.append("  " + if (chk.soDump) "libblackdex_d.so ✓" else "libblackdex_d.so ✗")
                sb.append("\n")
                sb.append("  ${if (ready) "✅" else "⚠️"} 引擎初始化${if (ready) "完成" else "未就绪"}\n")
                if (chk.problems.isNotEmpty()) {
                    sb.append("  ⚠️ 问题: " + chk.problems.joinToString("; ") + "\n")
                }
                sb.append("\n")

                // ---- 修复/签名 ----
                sb.append("[本地修复/签名]\n")
                sb.append("  ✅ 修复引擎（清壳+重组，纯 Kotlin）\n")
                val ks = com.yuntuoxiu.app.engine.LocalApkSigner.locateDefaultKeystore()
                sb.append("  ${if (ks != null) "✅" else "⚠️"} 签名密钥库" +
                        (ks?.let { " (${it.name})" } ?: "（未找到 ytx-release.jks，签名将失败）") + "\n")
                sb.append("\n")

                // ---- Shizuku（可选，仅用于安装/权限操作）----
                sb.append("[Shizuku（可选）]\n")
                sb.append("  ${if (ShizukuClient.isGranted()) "✅" else "⚠️"} Shizuku ${if (ShizukuClient.isGranted()) "已授权" else "未授权（不影响本地脱壳）"}\n")
                sb.append("\n")

                // ---- 工作区 ----
                sb.append("[工作区]\n")
                val dump = com.yuntuoxiu.app.engine.LocalUnpackEngine.getDumpDir()
                sb.append("  ${if (dump.isDirectory) "✅" else "❌"} dump 目录: ${dump.absolutePath}\n")

                sb.toString()
            }
            showResultDialog("环境自检", rep)
        }
    }

    /** 工具 7：用法说明（v2.0 本地化） */
    private fun showToolsHelp() {
        showResultDialog("工具用法说明",
            "【v2.0 全本地化 — 无需终端/容器】\n\n" +
            "· 壳诊断   直接读 APK 判定壳类型\n" +
            "· 去壳清理 删壳 so/assets\n" +
            "· 本地脱壳 App 内引擎（BlackBox）脱 DEX\n" +
            "· 本地修复 清壳重组 + apksig 签名\n" +
            "· 引擎详情 本地脱壳引擎自检\n" +
            "· 环境自检 检查引擎 / 密钥 / 工作区\n\n" +
            "【脱壳流程】\n" +
            "1. 本地脱壳 → unpackcloud/dump/<包名>/\n" +
            "2. 本地修复 → tasks/<id>/build/signed.apk\n\n" +
            "【限制】\n" +
            "· 仅 arm64-v8a\n" +
            "· Android 16 已适配（newBlackDex 3.3.x）\n" +
            "· 高级壳可能检测沙箱导致 dump 为空\n\n" +
            "详见工作区「工具使用说明.md」")
    }

    /** 长按任务：查看信息 + 删除 */
    private fun showTaskMenu(task: TaskMetaView) {
        val dlg = AlertDialog.Builder(this, R.style.YtxDialog)
            .setTitle("任务：${task.displayName}")
            .setMessage("状态：${task.stateLabel}\n壳：${task.shellLabel}\n${task.taskId}")
            .setNegativeButton("关闭") { d, _ -> d.dismiss() }
            .setPositiveButton("删除") { _, _ -> confirmDeleteTask(task) }
            .create()
        dlg.show()
        styleDialogWindow(dlg)
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(0xFFF85149.toInt())
    }

    /** 删除任务确认 */
    private fun confirmDeleteTask(task: TaskMetaView) {
        showConfirmDialog("删除任务",
            "确认删除任务「${task.displayName}」？\n\n" +
            "将删除：\n" +
            "· 任务全部文件（原始副本/dump/修复产物）\n" +
            "· 该任务产生的安装包（云脱修-*.apk）\n" +
            "· 上传的 APK 副本（若无其他任务使用）\n\n" +
            "此操作不可恢复。",
            "删除", danger = true) {
            doDeleteTask(task)
        }
    }

    private fun doDeleteTask(task: TaskMetaView) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                TaskRepository.deleteTask(task.taskId)
            }
            when (r) {
                is SubmitResult.Success -> {
                    LogStore.i(TAG, "已删除任务: ${task.taskId}")
                    Toast.makeText(this@MainActivity, "已删除：${task.displayName}", Toast.LENGTH_SHORT).show()
                    refreshTasks()
                }
                is SubmitResult.Failure -> {
                    LogStore.e(TAG, "删除失败: ${r.reason}")
                    Toast.makeText(this@MainActivity, "删除失败: ${r.reason}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------------- 选择 APK ----------------

    private fun chooseApkSource() {
        showMenuDialog(
            "选择 APK 来源",
            listOf(
                Triple("📁", "从文件选择", "浏览设备上的 .apk 文件"),
                Triple("📱", "从已安装应用选择", "列出第三方应用（带图标/搜索）")
            )
        ) { which ->
            if (which == 0) pickApkFromFile() else pickApkFromInstalled()
        }
    }

    private fun pickApkFromFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        startActivityForResult(intent, REQ_PICK_APK)
    }

    /** 从已安装应用选择（带图标 + 搜索 + 中文优先 + 排除系统应用） */
    private fun pickApkFromInstalled() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "正在加载应用列表...", Toast.LENGTH_SHORT).show()
            val apps = withContext(Dispatchers.IO) {
                try {
                    val pm = packageManager
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { ai ->
                            // 排除自己
                            if (ai.packageName == packageName) return@filter false
                            // 【新增】排除系统应用（FLAG_SYSTEM = 1）
                            val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                            if (isSystem) return@filter false
                            true
                        }
                        .map { ai ->
                            // 【修复】label 兜底：为空则用包名
                            val rawLabel = try {
                                ai.loadLabel(pm)?.toString()?.trim()
                            } catch (e: Throwable) { null }
                            AppItem(
                                label = rawLabel?.takeIf { it.isNotBlank() } ?: ai.packageName,
                                packageName = ai.packageName,
                                sourceDir = ai.sourceDir ?: "",
                                icon = try { ai.loadIcon(pm) } catch (e: Throwable) { null },
                            )
                        }
                        .sortedWith(compareByDescending<AppItem> { containsCJK(it.label) }
                            .thenBy { it.label })
                } catch (t: Throwable) {
                    LogStore.e(TAG, "枚举应用失败: ${t.message}")
                    emptyList()
                }
            }
            LogStore.i(TAG, "已加载 ${apps.size} 个第三方应用")
            if (apps.isEmpty()) {
                Toast.makeText(this@MainActivity, "未获取到应用列表", Toast.LENGTH_LONG).show()
                return@launch
            }
            showAppListDialog(apps)
        }
    }

    /** 应用选择对话框（带搜索框） */
    private fun showAppListDialog(allApps: List<AppItem>) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }

        // 搜索框
        val editSearch = EditText(this).apply {
            hint = "搜索应用名 / 包名"
            isSingleLine = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        container.addView(editSearch)

        // 列表（v1.6.6：ViewHolder + 滚动优化）
        val listView = ListView(this).apply {
            // ⭐ 滚动优化
            isFastScrollEnabled = true                  // 快速滚动条
            setScrollingCacheEnabled(true)              // 滚动缓存（减少重绘）
            isSmoothScrollbarEnabled = true
            setCacheColorHint(0x00000000)               // 拖动不变黑
            divider = null                              // 无分隔线（卡片自带边距）
            dividerHeight = 0
            setPadding(0, dp(4), 0, dp(8))
            clipToPadding = false
            // 弹性滚动手感
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        container.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val shown = ArrayList<AppItem>(allApps)
        val listAdapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                // ⭐ v1.6.6 ViewHolder 模式（避免每次 findViewById）
                val holder: AppViewHolder
                val v: View
                if (convertView == null) {
                    v = LayoutInflater.from(this@MainActivity)
                        .inflate(R.layout.item_app, parent, false)
                    holder = AppViewHolder(
                        v.findViewById(R.id.ivAppIcon),
                        v.findViewById(R.id.tvAppName),
                        v.findViewById(R.id.tvAppPkg)
                    )
                    v.tag = holder
                } else {
                    v = convertView
                    holder = v.tag as AppViewHolder
                }
                val item = shown[position]
                holder.name.text = item.label.ifBlank { item.packageName }
                holder.pkg.text = item.packageName
                holder.icon.setImageDrawable(item.icon)
                return v
            }
        }
        listView.adapter = listAdapter

        // 搜索联动
        editSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                shown.clear()
                if (q.isEmpty()) {
                    shown.addAll(allApps)
                } else {
                    allApps.forEach {
                        if (it.label.lowercase().contains(q) ||
                            it.packageName.lowercase().contains(q)) {
                            shown.add(it)
                        }
                    }
                }
                listAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val dialog = AlertDialog.Builder(this, R.style.YtxDialog)
            .setTitle("选择已安装应用（${allApps.size} 个）")
            .setView(container)
            .setNegativeButton("取消", null)
            .create()
        listView.setOnItemClickListener { _, _, pos, _ ->
            val item = shown[pos]
            dialog.dismiss()
            submitInstalledApp(item.label, item.packageName, item.sourceDir)
        }
        dialog.show()
        styleDialogWindow(dialog)
    }

    /**
     * 提交「已安装应用」为新任务（v1.6.8 修复）
     *
     * ⚠️ 关键：APP **无法直接读** /data/app/<pkg>/base.apk（其他 App 私有）
     *    → 必须用 **Shizuku** 复制到可读位置
     */
    /**
     * 提交「已安装应用」为新任务（v1.7.1 修复）
     *
     * ⚠️ 关键教训（两次踩坑）：
     *   1) APP 无法读 /data/app/<pkg>/base.apk（其他 App 私有）
     *   2) APP 无法写 uploads/（目录权限 750，属 u0_a0:media_rw）
     *   → **全程必须用 Shizuku**（shell 有 media_rw 组 + 能读 /data/app）
     *   → 不要退回「APP 自己 IO」，那必然失败
     */
    private fun submitInstalledApp(label: String, pkg: String, srcDir: String) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "正在提取 $label…", Toast.LENGTH_SHORT).show()

            val r = withContext(Dispatchers.IO) {
                try {
                    val upDir = File(YunTuoXiuApp.UPLOADS_ROOT)
                    val dest = File(upDir, "installed_${pkg}.apk")

                    // ① 确保 uploads 目录存在（用 Shizuku，保证权限对）
                    ShizukuShellExecutor.exec("mkdir -p '${upDir.absolutePath}' 2>&1")

                    // ② 全程 Shizuku 复制（读 /data/app + 写 uploads）
                    val cmd = "cp -f '$srcDir' '${dest.absolutePath}' 2>&1; " +
                              "echo \"SIZE=\$(stat -c %s '${dest.absolutePath}' 2>/dev/null || echo 0)\""
                    val res = ShizukuShellExecutor.exec(cmd)
                    val out = (res.getString("stdout") ?: "").trim()
                    val code = res.getInt("code")

                    // 从输出里解析 SIZE=
                    val size = Regex("SIZE=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                    if (code != 0 || size < 1024) {
                        return@withContext SubmitResult.Failure(
                            "Shizuku 复制失败（code=$code, size=$size）\n" +
                            "源: $srcDir\n" +
                            "目标: ${dest.absolutePath}\n" +
                            "输出: $out\n\n" +
                            "请确认：① Shizuku 已授权 ② 源 APK 路径正确")
                    }

                    // ③ 提交任务（源用 dest）
                    TaskRepository.submitTask(this@MainActivity, dest, allowAutoDegrade = true)
                } catch (t: Throwable) {
                    SubmitResult.Failure("提交失败: ${t.message}")
                }
            }
            when (r) {
                is SubmitResult.Success -> {
                    LogStore.i(TAG, "已提交: $label ($pkg)")
                    Toast.makeText(this@MainActivity, "✅ 已提交：$label（后端建任务中…）", Toast.LENGTH_LONG).show()
                    // ⭐ v1.9.3：立即刷 + 3s/6s 后再刷（等后端 watcher 建出 t_* 任务）
                    refreshTasks()
                    lifecycleScope.launch {
                        delay(3000); refreshTasks()
                        delay(3000); refreshTasks()
                    }
                }
                is SubmitResult.Failure -> {
                    LogStore.e(TAG, "提交失败: ${r.reason}")
                    showResultDialog("提交失败", r.reason)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_APK && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    try {
                        val tmp = File(cacheDir, "picked_${System.currentTimeMillis()}.apk")
                        contentResolver.openInputStream(uri)?.use { ins ->
                            tmp.outputStream().use { outs -> ins.copyTo(outs) }
                        }
                        TaskRepository.submitTask(this@MainActivity, tmp, allowAutoDegrade = true)
                    } catch (t: Throwable) {
                        SubmitResult.Failure("读取文件失败: ${t.message}")
                    }
                }
                when (r) {
                    is SubmitResult.Success -> {
                        Toast.makeText(this@MainActivity, "任务已提交", Toast.LENGTH_SHORT).show()
                        refreshTasks()
                    }
                    is SubmitResult.Failure ->
                        Toast.makeText(this@MainActivity, "提交失败: ${r.reason}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------------- 日志 ----------------

    /**
     * 刷新日志（v1.6.5：增量追加 + 去重；不整段重载）
     *
     *   · 只取「上次之后的新行」
     *   · 连续重复行合并为 1 条 + (xN)
     *   · 内容无变化则跳过
     */
    private fun refreshLog() {
        lifecycleScope.launch {
            try {
                val (fresh, newSeq, dup) = withContext(Dispatchers.IO) {
                    try { LogStore.readSince(logSeq) } catch (t: Throwable) {
                        Triple(emptyList<String>(), logSeq, 0)
                    }
                }
                if (fresh.isEmpty()) return@launch

                // 首次加载：清空占位
                if (logSeq < 0) tvLog.text = ""

                val sb = StringBuilder(tvLog.text?.toString() ?: "")
                fresh.forEach { sb.append(it).append('\n') }
                tvLog.text = sb.toString()
                logSeq = newSeq

                // 滚到底部（最新在下面）
                svLog.post { svLog.fullScroll(android.view.View.FOCUS_DOWN) }
                if (dup > 0) LogStore.d(TAG, "日志去重 $dup 行")
            } catch (t: Throwable) {
                tvLog.text = "日志刷新失败: ${t.message}"
            }
        }
    }

    // ---------------- 任务列表 ----------------

    private fun openDetail(task: TaskMetaView) {
        try {
            startActivity(Intent(this, TaskDetailActivity::class.java)
                .putExtra(TaskDetailActivity.EXTRA_TASK_ID, task.taskId))
        } catch (t: Throwable) {
            LogStore.e(TAG, "打开详情失败: ${t.message}")
            Toast.makeText(this@MainActivity, "打开失败: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAutoRefresh() {
        lifecycleScope.launch {
            var tick = 0
            while (true) {
                try {
                    // 任务列表刷新（内部更新 hasActiveTask 字段）
                    refreshTasks()
                    // ⭐ v1.6.5：日志每轮都「查」，但只追加新行（无变化零成本）
                    refreshLog()
                    tick++
                } catch (t: Throwable) {
                    LogStore.e(TAG, "自动刷新异常: ${t.message}")
                }
                // ⚠️ 自适应刷新：有活跃任务时 1.5s 高频（进度可见），
                //    无活跃任务时 2s（⭐ v1.9.3：从 5s 缩短，让「提交后」
                //    更快看到后端新建的任务——后端 watch tick 3s + 本刷新增速）。
                delay(if (hasActiveTask) 1500L else 2000L)
            }
        }
    }

    /** 刷新任务列表；返回值仅用于调用方判断是否需要高频刷新（此处简化处理）。 */
    private fun refreshTasks() {
        if (refreshing) return
        refreshing = true
        lifecycleScope.launch {
            // ⭐ v1.8.9：用 try/finally 保证 refreshing 复位。
            //   旧实现若 submit/去重抛异常，refreshing 会永久 true，
            //   导致此后列表**永不刷新**（表现为「提交后任务列表里没任务」）。
            try {
                val tasks = withContext(Dispatchers.IO) {
                    try { TaskRepository.listTasks() } catch (t: Throwable) {
                        LogStore.e(TAG, "读取任务失败: ${t.message}")
                        emptyList()
                    }
                }
                val deduped = try { dedupeLocalSkeleton(tasks) } catch (t: Throwable) {
                    LogStore.e(TAG, "去重失败: ${t.message}"); tasks
                }
                adapter.submit(deduped)
                tvTaskCount.text = "任务列表（${deduped.size}）"
                hasActiveTask = deduped.any { !it.isTerminal }
            } catch (t: Throwable) {
                LogStore.e(TAG, "刷新任务列表异常: ${t.message}")
            } finally {
                refreshing = false
            }
        }
    }

    /**
     * 去重：本地骨架（local_xxx/PENDING_LOCAL）若已有后端正式任务
     * 处理同一 source_apk，则隐藏骨架。
     */
    private fun dedupeLocalSkeleton(tasks: List<TaskMetaView>): List<TaskMetaView> {
        val backendApks = tasks.filter { !it.localSkeleton }.map { it.sourceApk }.toSet()
        val out = tasks.filter { t ->
            if (!t.localSkeleton) return@filter true
            // 骨架：若后端已有同 APK 任务，隐藏
            t.sourceApk !in backendApks
        }
        return out.sortedByDescending {
            if (it.updatedAt > 0) it.updatedAt else it.createdAt
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needs = mutableListOf<String>()
        listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                needs.add(it)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needs.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needs.isNotEmpty()) {
            try {
                ActivityCompat.requestPermissions(this, needs.toTypedArray(), 200)
            } catch (t: Throwable) {
                LogStore.e(TAG, "请求权限失败: ${t.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            LogStore.w(TAG, "需要「所有文件访问权限」")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (t: Throwable) {
                LogStore.e(TAG, "跳转权限设置失败: ${t.message}")
            }
        }
    }

    private fun containsCJK(s: String): Boolean {
        for (c in s) {
            if (c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF ||
                c.code in 0x3040..0x30FF) return true
        }
        return false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- v1.6 通用弹窗辅助 ----------------

    /**
     * 美观菜单弹窗（图标 + 标题 + 描述）。
     * @param items Triple(图标, 标题, 描述)
     */
    private fun showMenuDialog(
        title: String,
        items: List<Triple<String, String, String>>,
        onPick: (Int) -> Unit
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(container)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                minOf(dp(420), (resources.displayMetrics.heightPixels * 0.6).toInt()))
        }
        items.forEachIndexed { idx, (icon, t, d) ->
            val row = layoutInflater.inflate(R.layout.dialog_item, container, false)
            row.findViewById<TextView>(R.id.tvItemIcon).text = icon
            row.findViewById<TextView>(R.id.tvItemTitle).text = t
            val tvD = row.findViewById<TextView>(R.id.tvItemDesc)
            if (d.isNotBlank()) {
                tvD.text = d
                tvD.visibility = View.VISIBLE
            }
            row.setOnClickListener {
                onPick(idx)
            }
            container.addView(row)
        }
        val dlg = AlertDialog.Builder(this, R.style.YtxDialog)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
        styleDialogWindow(dlg)
    }

    /**
     * ⭐ v1.6.4 通用「列表选择」弹窗（深色圆角卡片风格，替代 AlertDialog.setItems 灰方框）。
     *
     * @param items 每项 = Triple(图标, 标题, 描述)；图标/描述可空串
     */
    private fun showItemsDialog(
        title: String,
        items: List<Triple<String, String, String>>,
        onPick: (Int) -> Unit
    ) {
        showMenuDialog(title, items, onPick)
    }

    /**
     * ⭐ v1.6.4 通用「单选」弹窗（深色圆角卡片，替代 setSingleChoiceItems）。
     */
    private fun showSingleChoiceDialog(
        title: String,
        items: List<String>,
        checkedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        val list = items.mapIndexed { i, s ->
            Triple(if (i == checkedIndex) "✅" else "○", s, "")
        }
        showMenuDialog(title, list, onPick)
    }

    /** 美化只读结果弹窗（等宽字体 + 可滚动 + 深色圆角卡片） */
    private fun showResultDialog(title: String, body: String) {
        val tv = TextView(this).apply {
            text = body
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFC9D1D9.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(tv)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                minOf(dp(480), (resources.displayMetrics.heightPixels * 0.65).toInt()))
        }
        val dlg = AlertDialog.Builder(this, R.style.YtxDialog)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create()
        dlg.show()
        styleDialogWindow(dlg)
    }

    /**
     * ⭐ v1.6.4 通用「确认」弹窗（深色圆角卡片风格，替代 AlertDialog 灰方框）。
     */
    private fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String = "确定",
        danger: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val dlg = AlertDialog.Builder(this, R.style.YtxDialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirmText) { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        styleDialogWindow(dlg)
        // 危险操作（删除/取消）用红色按钮
        if (danger) {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(0xFFF85149.toInt())
        }
    }

    /**
     * ⭐ 统一弹窗窗口样式（v1.6.8：去多余方框，衔接自然）
     */
    private fun styleDialogWindow(dlg: AlertDialog) {
        try {
            val w = dlg.window ?: return
            // 深色圆角背景
            w.setBackgroundDrawableResource(R.drawable.bg_dialog)
            // 去掉系统「浮动窗口」的额外内边距/装饰
            w.setDimAmount(0.60f)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // ⭐ 内容铺满（避免标题/内容区之间多余留白）
            w.setLayout(
                minOf((resources.displayMetrics.widthPixels * 0.92).toInt(), dp(480)),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            // 阴影层次
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                w.setElevation(dp(6).toFloat())
            }
            // ⭐ 去掉标题与内容之间的分隔线（衔接自然）
            try {
                val titleId = resources.getIdentifier("alertTitle", "id", "android")
                if (titleId != 0) {
                    val tv = dlg.findViewById<TextView>(titleId)
                    tv?.setPadding(dp(20), dp(16), dp(20), dp(8))
                    tv?.setTextColor(0xFFE6EDF3.toInt())
                    tv?.textSize = 15f
                }
            } catch (_: Throwable) {}
            // 内容区 padding（若为自定义 view 用 setView 的）
            val contentId = android.R.id.message
            try {
                if (contentId != 0) {
                    dlg.findViewById<TextView>(contentId)?.apply {
                        setPadding(dp(20), dp(4), dp(20), dp(8))
                        setTextColor(0xFFC9D1D9.toInt())
                        textSize = 13f
                    }
                }
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            LogStore.w(TAG, "弹窗样式设置失败: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PICK_APK = 100
    }

    // ---------- 任务列表适配器（图标 + 应用名 + 徽章 + 长按）----------
    inner class TaskAdapter(
        private val onClick: (TaskMetaView) -> Unit,
        private val onLongClick: (TaskMetaView) -> Unit
    ) : RecyclerView.Adapter<TaskAdapter.VH>() {

        private var items: List<TaskMetaView> = emptyList()
        private val iconCache = HashMap<String, Drawable?>()

        fun submit(list: List<TaskMetaView>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_task, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]

            val icon = lookupIcon(t.lookupPackage)
            if (icon != null) {
                holder.icon.setImageDrawable(icon)
                holder.icon.visibility = View.VISIBLE
            } else {
                holder.icon.visibility = View.GONE
            }

            val appName = lookupAppLabel(t.lookupPackage)
            holder.name.text = appName ?: t.displayName
            // 壳 + 置信度
            val conf = t.shellConfidence?.let { " (${(it * 100).toInt()}%)" } ?: ""
            holder.state.text = "[${t.stateLabel}] 壳: ${t.shellLabel}$conf"
            holder.code.text = t.failCode?.let { "fail: $it  |  ${t.taskId}" } ?: t.taskId

            when (t.group) {
                TaskGroup.PROCESSING -> {
                    holder.badge.text = "处理中"
                    holder.badge.setBackgroundResource(R.drawable.badge_processing)
                }
                TaskGroup.SUCCESS -> {
                    holder.badge.text = "处理成功"
                    holder.badge.setBackgroundResource(R.drawable.badge_success)
                }
                TaskGroup.FAILED -> {
                    holder.badge.text = "处理失败"
                    holder.badge.setBackgroundResource(R.drawable.badge_failed)
                }
            }

            holder.itemView.setOnClickListener { onClick(t) }
            // 【新增】长按菜单
            holder.itemView.setOnLongClickListener {
                onLongClick(t)
                true
            }
        }

        private fun lookupIcon(pkg: String?): Drawable? {
            if (pkg.isNullOrBlank()) return null
            if (iconCache.containsKey(pkg)) return iconCache[pkg]
            val icon = try { packageManager.getApplicationIcon(pkg) } catch (e: Throwable) { null }
            iconCache[pkg] = icon
            return icon
        }

        private fun lookupAppLabel(pkg: String?): String? {
            if (pkg.isNullOrBlank()) return null
            return try {
                packageManager.getApplicationInfo(pkg, 0)
                    .loadLabel(packageManager).toString().takeIf { it.isNotBlank() }
            } catch (e: Throwable) {
                null
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.ivTaskIcon)
            val name: TextView = v.findViewById(R.id.tvName)
            val state: TextView = v.findViewById(R.id.tvState)
            val code: TextView = v.findViewById(R.id.tvCode)
            val badge: TextView = v.findViewById(R.id.tvBadge)
        }
    }
}