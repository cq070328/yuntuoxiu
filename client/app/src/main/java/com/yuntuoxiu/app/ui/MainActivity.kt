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
import com.yuntuoxiu.app.worker.BackendBridge
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

        findViewById<View>(R.id.btnStartWorker).setOnClickListener {
            try {
                startForegroundService(Intent(this, WorkerService::class.java))
                LogStore.i(TAG, "已请求启动 Worker")
                Toast.makeText(this, "Worker 已启动", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                LogStore.e(TAG, "启动 Worker 失败: ${t.message}")
                Toast.makeText(this, "启动失败: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }

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

        // 【v1.6.1】本地引擎按钮：
        //   单击 = 检查本地引擎（NPatch素材/脱壳模块/注入器）
        //   长按 = 工具面板
        findViewById<View>(R.id.btnStartDaemon)?.apply {
            setOnClickListener {
                lifecycleScope.launch {
                    val rep = withContext(Dispatchers.IO) {
                        val ws = YunTuoXiuApp.WORKSPACE_ROOT
                        val items = listOf(
                            "NPatch素材" to "$ws/ytx-tools/npatch_assets/assets/lspatch/metaloader.dex",
                            "脱壳模块" to "$ws/ytx-tools/ytxdump-module.apk",
                            "注入器" to "$ws/ytx_npatch_inject.sh",
                            "DEX替换" to "$ws/ytx-dex-replace.py",
                            "对齐工具" to "$ws/ytx-zipalign.py",
                            "去壳清理" to "$ws/ytx-unpack-clean.py",
                            "启动诊断" to "$ws/ytx_diag_launch.sh",
                            "云端构建" to "$ws/ytx-cloud-build.py",
                            "token" to "$ws/yuntuoxiu-dev/token.txt"
                        )
                        val sb = StringBuilder("== 本地引擎检查 ==\n\n")
                        var okN = 0
                        items.forEach { (name, path) ->
                            val ok = File(path).exists()
                            if (ok) okN++
                            sb.append("${if (ok) "✅" else "❌"} $name\n")
                        }
                        sb.append("\n就绪：$okN/${items.size}\n\n")
                        sb.append("说明：\n")
                        sb.append("· 前 3 项是「脱壳注入」必需\n")
                        sb.append("· token 是「云端构建」必需\n")
                        if (okN == items.size) sb.append("\n🎉 全部就绪，可全自动脱壳！")
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

            val npatch = File(YunTuoXiuApp.NPATCH_ASSETS).exists()
            val module = File(YunTuoXiuApp.DUMP_MODULE_APK).exists()
            val injector = File("${YunTuoXiuApp.WORKSPACE_ROOT}/ytx_npatch_inject.sh").exists()
            val token = File(YunTuoXiuApp.TOKEN_FILE).exists()

            val ready = npatch && module && injector

            // ⭐ v1.6.8 显示「内置 worker」真实状态（便于诊断）
            val workerAlive = try {
                com.yuntuoxiu.app.worker.ContainerBridge.isWorkerAlive()
            } catch (_: Throwable) { false }
            val hbInfo = try {
                com.yuntuoxiu.app.worker.ContainerBridge.heartbeatInfo()
            } catch (_: Throwable) { "读取失败" }

            tv.text = when {
                !ready -> "本地引擎：⚠️ 缺 " + listOfNotNull(
                    if (!npatch) "NPatch素材" else null,
                    if (!module) "脱壳模块" else null,
                    if (!injector) "注入器" else null
                ).joinToString("/")
                workerAlive -> "本地引擎：✅ 就绪（worker 在线${if (token) " + 云端" else ""}）"
                else -> "本地引擎：⚠️ 就绪但 worker 离线\n  $hbInfo"
            }
            badge?.setTextColor(
                if (ready && workerAlive) 0xFF3FB950.toInt()
                else if (ready) 0xFFF0883E.toInt()
                else 0xFFF85149.toInt())
        } catch (t: Throwable) {
            LogStore.w(TAG, "刷新引擎状态失败: ${t.message}")
        }
    }

    /**
     * v1.6.1 环境诊断（长按「本地引擎」行触发）。
     * 输出：Shizuku + 本地引擎 + 工作区 + 后端偏好
     */
    private fun diagnoseTermux() {
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val ws = YunTuoXiuApp.WORKSPACE_ROOT
                val sb = StringBuilder()
                sb.append("== 云脱修 环境诊断 ==\n\n")

                sb.append("[Shizuku]\n")
                sb.append("  已授权: ${ShizukuClient.isGranted()}\n\n")

                sb.append("[本地引擎]\n")
                val engine = listOf(
                    "NPatch素材" to "$ws/ytx-tools/npatch_assets/assets/lspatch/metaloader.dex",
                    "脱壳模块" to "$ws/ytx-tools/ytxdump-module.apk",
                    "注入器" to "$ws/ytx_npatch_inject.sh",
                    "DEX替换" to "$ws/ytx-dex-replace.py",
                    "对齐" to "$ws/ytx-zipalign.py",
                    "去壳" to "$ws/ytx-unpack-clean.py",
                    "诊断" to "$ws/ytx_diag_launch.sh",
                    "云端" to "$ws/ytx-cloud-build.py",
                    "token" to "$ws/yuntuoxiu-dev/token.txt"
                )
                engine.forEach { (n, p) ->
                    sb.append("  ${if (File(p).exists()) "✅" else "❌"} $n\n")
                }

                sb.append("\n[工作区]\n")
                sb.append("  可读: ${File(ws).exists()}\n")
                sb.append("  任务数: ${File("$ws/unpackcloud/tasks").listFiles()?.size ?: 0}\n")

                sb.append("\n[后端偏好]\n")
                sb.append("  ${readBackendPref()}\n")

                sb.append("\n[可选环境]\n")
                sb.append("  容器后端: ${BackendBridge.readDaemonStatus().second}\n")
                sb.append("  容器: ${File("/root/ytx-tools/apktool.jar").exists()}\n")

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

    /** 选择构建后端（持久化到 .build_backend） */
    private fun chooseBuildBackend() {
        lifecycleScope.launch {
            val cur = withContext(Dispatchers.IO) { readBackendPref() }
            val labels = listOf(
                "自动（推荐：云端 > 容器 > Termux）",
                "云端构建（GitHub Actions）",
                "容器构建（Operit Ubuntu）",
                "Termux 构建"
            )
            val vals = listOf("auto", "cloud", "container", "termux")
            val checked = vals.indexOf(cur).coerceAtLeast(0)
            showSingleChoiceDialog("选择构建后端（当前：$cur）", labels, checked) { which ->
                val v = vals[which]
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { writeBackendPref(v) }
                    Toast.makeText(this@MainActivity,
                        "已设置：$v", Toast.LENGTH_SHORT).show()
                    refreshBackendStatus()
                }
            }
        }
    }

    private fun readBackendPref(): String {
        return try {
            val f = File(YunTuoXiuApp.CLOUD_ROOT, ".build_backend")
            if (f.exists()) f.readText().trim().ifBlank { "auto" } else "auto"
        } catch (t: Throwable) { "auto" }
    }

    private fun writeBackendPref(v: String) {
        try {
            val f = File(YunTuoXiuApp.CLOUD_ROOT, ".build_backend")
            f.parentFile?.mkdirs()
            f.writeText(v)
        } catch (t: Throwable) {
            LogStore.e(TAG, "写后端偏好失败: ${t.message}")
        }
    }

    /** 刷新「构建后端」状态行 */
    private fun refreshBackendStatus() {
        lifecycleScope.launch {
            val (txt, ok) = withContext(Dispatchers.IO) {
                val pref = readBackendPref()
                // 可用性检查（粗粒度）
                val cloudOk = File(YunTuoXiuApp.TOKEN_FILE).let {
                    it.exists() && it.length() > 20
                }
                val containerOk = File("/root/ytx-tools/apktool.jar").exists() ||
                        File("${YunTuoXiuApp.TOOLS_DIR}/apktool.jar").exists()
                // ⭐ v1.8.4：Termux 已弃用 -> 用「容器后端在线」替代 termuxOk
                val backendOk = BackendBridge.readDaemonStatus().first

                val pick = when (pref) {
                    "cloud" -> if (cloudOk) "cloud" else null
                    "container" -> if (containerOk) "container" else null
                    // Termux 已弃用：选择 termux 时回退到容器后端
                    "termux" -> if (backendOk) "container" else null
                    else -> when {
                        cloudOk -> "cloud"
                        containerOk -> "container"
                        backendOk -> "container"
                        else -> null
                    }
                }
                val title = "构建后端：$pref" +
                    (if (pick != null) " → 用 $pick" else "（无可用）")
                title to (pick != null)
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

            // ⭐ v1.6.9：先确保 worker 在线（否则提前给出可操作提示）
            if (!com.yuntuoxiu.app.worker.ContainerBridge.isWorkerAlive()) {
                showWorkerOfflineDialog { runOneClickUnpack() }
                return@launch
            }

            val names = tasks.map { "${it.displayName}  [${it.stateLabel}]" }.toTypedArray()
            showItemsDialog(
                "一键脱修 · 选择任务",
                names.map { Triple("", it, "") }
            ) { which ->
                oneClickRun(tasks[which])
            }
        }
    }

    /**
     * ⭐ v1.7.0 后端离线提示（阶梯式引导）
     *
     * 为什么需要引导：
     *   容器进程属 Operit（uid 隔离），Shizuku/本 APP 都无法启动它。
     *   → 只能让 Operit 侧启动。
     */
    private fun showWorkerOfflineDialog(onRetry: () -> Unit) {
        try {
            val hb = com.yuntuoxiu.app.worker.ContainerBridge.heartbeatInfo()

            // ★ 给 Operit 的指令（复制到剪贴板）
            val operitCmd = "启动云脱修后端（bash /sdcard/MT2/apks/ytx.sh start）"

            AlertDialog.Builder(this, R.style.YtxDialog)
                .setTitle("需要先启动后端")
                .setMessage(
                    "云脱修的「注入 / 构建」依赖 Operit 容器（bash+java+python3），\n" +
                    "而容器进程属于 Operit，本 APP 无法直接启动它。\n\n" +
                    "【方式一 · 推荐】\n" +
                    "复制下面这句，粘贴到 Operit 对话框发送：\n" +
                    "  $operitCmd\n" +
                    "（Operit 会调用 yuntuoxiu 包启动后端）\n\n" +
                    "【方式二 · 终端】\n" +
                    "在 Operit 终端执行：\n" +
                    "  ${YunTuoXiuApp.START_CMD}\n\n" +
                    "当前心跳: $hb"
                )
                .setPositiveButton("复制并打开 Operit") { _, _ ->
                    copyToClipboard("ytx_operit", operitCmd)
                    Toast.makeText(this,
                        "✅ 已复制，粘贴到 Operit 发送即可", Toast.LENGTH_LONG).show()
                    openOperit()
                }
                .setNeutralButton("打开 Operit") { _, _ -> openOperit() }
                .setNegativeButton("我已启动，重试") { _, _ -> onRetry() }
                .create().also { styleDialogWindow(it) }.show()
        } catch (t: Throwable) {
            showResultDialog("后端离线",
                "请在 Operit 执行：\n${YunTuoXiuApp.START_CMD}")
        }
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

    private fun oneClickRun(task: TaskMetaView) {
        val pkg = task.lookupPackage
        if (pkg.isNullOrBlank()) {
            showResultDialog("一键脱修", "❌ 该任务没有包名（需从「已安装应用」选择）")
            return
        }

        lifecycleScope.launch {
            val ws = YunTuoXiuApp.WORKSPACE_ROOT
            val tid = task.taskId
            val taskDir = File("$ws/unpackcloud/tasks/$tid")
            val log = StringBuilder("== 一键脱修 · $tid ==\n\n")
            var stage = ""

            fun fail(msg: String) {
                log.append("\n❌ [$stage] $msg\n")
                showResultDialog("一键脱修 · 失败", log.toString())
            }

            try {
                // ---------- ① 壳诊断 ----------
                stage = "1/7 壳诊断"
                log.append("[$stage] …\n")
                val srcApk = File(task.sourceApk)
                if (!srcApk.exists()) { fail("原 APK 不存在: ${task.sourceApk}"); return@launch }
                val v = com.yuntuoxiu.app.worker.ShellDetect.detect(srcApk.absolutePath)
                val shellTag = v.tag
                log.append("  壳类型: $shellTag (${(v.confidence * 100).toInt()}%)\n")
                log.append("  DEX: ${v.dexCount} 个\n")

                val isRealShell = !shellTag.equals("NONE", true) &&
                        !shellTag.equals("CLEAN", true) && v.confidence > 0.5

                // ⭐ v1.6.3 关键修复：无壳 App 不需要注入！
                //   原因：注入 lspatch 后，无壳 App 会因 loadLibrary 失败而崩
                //        （lspatch 需要目标已有某些 native 依赖）
                //   正确做法：无壳 -> 直接结束，提示「无需脱壳」
                if (!isRealShell) {
                    log.append("\n✅ 未检测到加固（壳类型: $shellTag）\n")
                    log.append("无需脱壳 —— 原 APK 可直接使用。\n\n")
                    log.append("如果确实有壳但未被识别，可：\n")
                    log.append("· 工具 →「壳诊断」详情确认\n")
                    log.append("· 工具 →「smali 替换」手动修\n")
                    showResultDialog("一键脱修 · 无需处理", log.toString())
                    return@launch
                }

                // ---------- ② 壳清理 ----------
                stage = "2/7 壳清理"
                var workApk = task.sourceApk
                if (isRealShell) {
                    log.append("[$stage] …\n")
                    val cleaned = File(taskDir, "cleaned.apk")
                    cleaned.parentFile?.mkdirs()
                    val n = withContext(Dispatchers.IO) {
                        com.yuntuoxiu.app.worker.ShellDetect.cleanShellSo(
                            task.sourceApk, cleaned.absolutePath)
                    }
                    if (n > 0 && cleaned.exists()) {
                        workApk = cleaned.absolutePath
                        log.append("  已删壳条目 $n 个\n")
                    } else {
                        log.append("  无壳特征可删（跳过）\n")
                    }
                } else {
                    log.append("[$stage] 跳过（非加固）\n")
                }

                // ---------- ③ CLI 注入（委托容器） ----------
                stage = "3/7 注入脱壳模块"
                if (!com.yuntuoxiu.app.worker.ContainerBridge.isWorkerAlive()) {
                    fail("容器 worker 未运行。\n\n" +
                        "注入/构建需要容器（有 bash+java+python3），\n" +
                        "而 Shizuku shell 没有这些。\n\n" +
                        "解决：在 Operit 终端执行：\n" +
                        "  " + YunTuoXiuApp.START_CMD + "\n\n" +
                        "（或点长按「本地引擎」查看心跳）")
                    return@launch
                }
                log.append("[$stage] …（可能 1-3 分钟）\n")
                val injected = File(taskDir, "injected.apk")
                val (injOk, injDetail, _) = com.yuntuoxiu.app.worker.ContainerBridge.execSync(
                    "$ws/ytx_npatch_inject.sh",
                    listOf(workApk, injected.absolutePath, pkg,
                           "--modules", "$ws/ytx-tools/ytxdump-module.apk"),
                    600_000
                )
                if (!injOk || !injected.exists() || injected.length() < 1024) {
                    fail("注入失败\n$injDetail"); return@launch
                }
                log.append("  ✅ 注入产物 ${injected.length() / 1024}KB\n")

                // ---------- ④ 安装 + 启动 ----------
                stage = "4/7 安装 + 启动"
                log.append("[$stage] …\n")
                // ⭐ v1.8.8：安装前先卸旧版（避免 lspatch 注入版与设备原版签名冲突
                //   导致 INSTALL_FAILED_INCOMPATIBLE）。安装走 /data/local/tmp 中转，
                //   绕开 Android 14+ SELinux 对 /sdcard 的读取限制。
                val installOut = withContext(Dispatchers.IO) {
                    val r = ShizukuShellExecutor.exec(
                        "cp -f '${injected.absolutePath}' /data/local/tmp/ytx_oc.apk && " +
                        "pm uninstall $pkg >/dev/null 2>&1; " +
                        "pm install -r -d /data/local/tmp/ytx_oc.apk 2>&1 | tail -2")
                    (r.getString("stdout") ?: "") + (r.getString("stderr") ?: "")
                }
                log.append("  ${installOut.trim().take(200)}\n")
                if (!installOut.contains("Success", ignoreCase = true)) {
                    fail("安装失败：\n${installOut.trim().take(400)}\n\n" +
                        "提示：若为签名冲突，可先手动卸载设备上的「爱作业」再重试。")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    ShizukuShellExecutor.exec(
                        "am force-stop $pkg; " +
                        "monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                }
                log.append("  已安装并启动\n")

                // ---------- ⑤ 等 dump ----------
                stage = "5/7 等待 dump"
                log.append("[$stage] …（最多 30s）\n")
                val modDump = "/sdcard/Android/data/$pkg/files/ytx_dump"
                var dexN = 0
                for (i in 1..10) {
                    delay(3000)
                    dexN = withContext(Dispatchers.IO) {
                        val r = ShizukuShellExecutor.exec(
                            "ls $modDump/dex_*.dex 2>/dev/null | wc -l")
                        (r.getString("stdout") ?: "0").trim().toIntOrNull() ?: 0
                    }
                    if (dexN > 0) break
                }
                if (dexN <= 0) {
                    fail("30s 内未产出 DEX。可能：\n" +
                        "· 目标无壳（不需要脱壳）→ 直接用原 APK\n" +
                        "· 壳对抗（需 smali 替换）→ 去工具里试\n" +
                        "· 模块未加载（检查日志）")
                    return@launch
                }
                log.append("  ✅ 产出 $dexN 个 dex\n")

                // ---------- ⑥ 收集 ----------
                stage = "6/7 收集 DEX"
                log.append("[$stage] …\n")
                val dumpDir = File(taskDir, "dump")
                dumpDir.mkdirs()
                withContext(Dispatchers.IO) {
                    ShizukuShellExecutor.exec(
                        "cp -f $modDump/dex_*.dex '${dumpDir.absolutePath}/' 2>/dev/null; " +
                        "ls '${dumpDir.absolutePath}'/dex_*.dex | wc -l")
                }
                log.append("  已收集到 ${dumpDir.absolutePath}\n")

                // ---------- ⑦ 构建（委托容器：替换+对齐+签名） ----------
                stage = "7/7 构建（替换+对齐+签名）"
                log.append("[$stage] …\n")
                val outApk = File("$ws/云脱修-$tid-oc.apk")
                val (bOk, bDetail, _) = com.yuntuoxiu.app.worker.ContainerBridge.execSync(
                    "$ws/ytx_build_from_dump.sh",
                    listOf(task.sourceApk, dumpDir.absolutePath, outApk.absolutePath),
                    900_000
                )
                if (!bOk || !outApk.exists() || outApk.length() < 1024) {
                    fail("构建失败\n$bDetail"); return@launch
                }
                log.append("  ✅ 产物: ${outApk.absolutePath}\n")
                log.append("  （${outApk.length() / 1024}KB）\n")

                log.append("\n🎉 一键脱修完成！\n")
                log.append("下一步：安装验证 → bash ytx_diag_launch.sh\n")
                log.append("若闪退 → 工具里用「smali 替换」修壳桩\n")
                showResultDialog("一键脱修 · 成功", log.toString())
                refreshTasks()

            } catch (t: Throwable) {
                fail("异常: ${t.message}")
            }
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
                Triple("📤", "收集 Dump", "Shizuku 读取 Xposed 模块产物"),
                Triple("☁️", "云端构建", "上传 DEX → GitHub Actions"),
                Triple("🩹", "smali 替换", "修壳桩/native桩（闪退时用）"),
                Triple("📋", "环境自检", "检查 Shizuku/token/脚本"),
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
            "选择要收集 Dump 的任务",
            names.map { Triple("", it, "") }
        ) { which ->
            val t = cands[which]
            val pkg = t.lookupPackage!!
            lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "收集中…", Toast.LENGTH_SHORT).show()
            val out = withContext(Dispatchers.IO) {
            try {
            val dest = File(YunTuoXiuApp.CLOUD_ROOT,
            "tasks/${t.taskId}/dump")
            dest.mkdirs()
            val cmd = "mkdir -p '${dest.absolutePath}' && " +
            "if [ -d '/sdcard/Android/data/$pkg/files/ytx_dump' ]; then " +
            "cp -f '/sdcard/Android/data/$pkg/files/ytx_dump'/dex_*.dex " +
            "'${dest.absolutePath}/' 2>/dev/null; " +
            "ls '${dest.absolutePath}'/dex_*.dex 2>/dev/null | wc -l; " +
            "else echo NOT_FOUND; fi"
            val r = ShizukuShellExecutor.exec(cmd)
            val o = (r.getString("stdout") ?: "").trim()
            if (o == "NOT_FOUND")
            "❌ 未找到模块 dump 目录（先让模块跑一次）"
            else "✅ 收集 $o 个 dex -> ${dest.absolutePath}"
            } catch (e: Throwable) {
            "收集失败: ${e.message}"
            }
            }
            showResultDialog("收集 Dump", out)
            }
        }
        }
    }

    /** 工具 4：云端构建 */
    private fun toolCloudBuild() {
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) {
                try { TaskRepository.listTasks() } catch (t: Throwable) { emptyList() }
            }
            val cands = tasks.filter { it.isTerminal }
            if (cands.isEmpty()) {
                Toast.makeText(this@MainActivity, "暂无已处理任务", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = cands.map { it.displayName }.toTypedArray()
            showItemsDialog(
            "选择要云端构建的任务",
            names.map { Triple("", it, "") }
        ) { which ->
            val t = cands[which]
            val ws = YunTuoXiuApp.WORKSPACE_ROOT
            val dumpDir = File(YunTuoXiuApp.CLOUD_ROOT, "tasks/${t.taskId}/dump")
            if (!dumpDir.isDirectory || (dumpDir.listFiles()?.isEmpty() != false)) {
                Toast.makeText(this@MainActivity,
                    "该任务无 dump（先用「收集 Dump」）", Toast.LENGTH_LONG).show()
                return@showItemsDialog
            }
// ⭐ v1.6.7 修复：改为容器委托（Shizuku shell 无 python3）
                    lifecycleScope.launch {
                        Toast.makeText(this@MainActivity,
                            "云端构建中（可能几分钟）…", Toast.LENGTH_LONG).show()
                        if (!com.yuntuoxiu.app.worker.ContainerBridge.isWorkerAlive()) {
                            showResultDialog("云端构建", 
                                "❌ 容器 worker 未运行。\n\n" +
                                "云端构建需 python3（容器有，Shizuku 无）。\n" +
                                "请在 Operit 终端执行：\n" +
                                "  " + YunTuoXiuApp.START_CMD + "")
                            return@launch
                        }
                        val (ok, detail, _) = withContext(Dispatchers.IO) {
                            com.yuntuoxiu.app.worker.ContainerBridge.execSync(
                                "$ws/ytx-cloud-build.py",
                                listOf("--task", t.taskId,
                                       "--apk", t.sourceApk,
                                       "--dump", dumpDir.absolutePath),
                                1_800_000
                            )
                        }
                        showResultDialog("云端构建结果",
                            if (ok) "✅ $detail" else "❌ $detail")
                    }
        }
        }
    }

    /** 工具 5：smali 替换（修壳桩，闪退时用） */
    private fun toolSmaliPatch() {
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
                "smali 替换 · 选择任务",
                names.map { Triple("", it, "") }
            ) { which ->
                val t = tasks[which]
                showItemsDialog(
                    "smali 替换模式",
                    listOf(
                        Triple("🔍", "仅识别（预览，不改）", "扫描壳特征但不动文件"),
                        Triple("🩹", "识别并替换", "有则替换，无则跳过")
                    )
                ) { mode ->
                    smaliPatchRun(t, mode == 0)
                }
            }
        }
    }

    private fun smaliPatchRun(task: TaskMetaView, dryRun: Boolean) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity,
                if (dryRun) "识别中…" else "替换中…", Toast.LENGTH_SHORT).show()

            // ⭐ v1.6.7 修复：改为容器委托（Shizuku shell 无 python3/java）
            if (!com.yuntuoxiu.app.worker.ContainerBridge.isWorkerAlive()) {
                showResultDialog(if (dryRun) "smali 识别" else "smali 替换",
                    "❌ 容器 worker 未运行。\n\n" +
                    "smali 替换需 python3 + apktool(java)，\n" +
                    "Shizuku shell 没有这些。\n\n" +
                    "请在 Operit 终端执行：\n" +
                    "  " + YunTuoXiuApp.START_CMD + "")
                return@launch
            }

            val out = withContext(Dispatchers.IO) {
                try {
                    val ws = YunTuoXiuApp.WORKSPACE_ROOT
                    val tid = task.taskId
                    val dec = "$ws/unpackcloud/tasks/$tid/work/apktool_dec"

                    // ① 若还没解包，先委托容器解包
                    if (!File(dec).exists()) {
                        val (ok1, d1, _) = com.yuntuoxiu.app.worker.ContainerBridge.execSh(
                            "mkdir -p '$dec' && java -jar $ws/ytx-tools/apktool.jar d " +
                            "'${task.sourceApk}' -o '$dec' -f 2>&1 | tail -5",
                            300_000
                        )
                        if (!File(dec).exists()) {
                            return@withContext "❌ 解包失败\n$d1"
                        }
                    }

                    // ② 委托容器调 ytx-smali-patch.py
                    val args = mutableListOf(dec)
                    if (dryRun) args += "--dry-run"
                    val (ok2, detail, _) = com.yuntuoxiu.app.worker.ContainerBridge.execSync(
                        "$ws/ytx-smali-patch.py", args, 300_000
                    )
                    if (ok2) detail else "❌ $detail"
                } catch (t: Throwable) {
                    "❌ 失败: ${t.message}"
                }
            }
            showResultDialog(if (dryRun) "smali 识别" else "smali 替换", out)
        }
    }

    /** 工具 6：环境自检 */
    private fun toolEnvCheck() {
        lifecycleScope.launch {
            val rep = withContext(Dispatchers.IO) {
                val ws = YunTuoXiuApp.WORKSPACE_ROOT
                val sb = StringBuilder("== 云脱修 环境自检 ==\n\n")
                sb.append("[核心]\n")
                sb.append("  ${if (ShizukuClient.isGranted()) "✅" else "❌"} Shizuku 已授权\n")
                sb.append("  ${if (File("$ws/yuntuoxiu-dev/token.txt").exists()) "✅" else "❌"} token.txt\n")
                sb.append("  ${if (File("$ws/ytx-tools/npatch_assets").exists()) "✅" else "❌"} NPatch素材\n")
                sb.append("  ${if (File("$ws/ytx-tools/ytxdump-module.apk").exists()) "✅" else "❌"} 脱壳模块\n\n")

                sb.append("[工具链]\n")
                listOf("ytx_npatch_inject.sh", "ytx-dex-replace.py", "ytx-zipalign.py",
                    "ytx-unpack-clean.py", "ytx-cloud-build.py", "ytx_diag_launch.sh")
                    .forEach { f ->
                        sb.append("  ${if (File("$ws/$f").exists()) "✅" else "❌"} $f\n")
                    }

                sb.append("\n[构建后端]\n")
                sb.append("  偏好: ${readBackendPref()}\n")
                sb.append("  ${if (File("$ws/yuntuoxiu-dev/token.txt").exists()) "✅" else "❌"} 云端（GitHub）\n")
                sb.append("  ${if (File("/root/ytx-tools/apktool.jar").exists()) "✅" else "❌"} 容器（Operit）\n")
                sb.append("  ${if (BackendBridge.readDaemonStatus().first) "✅" else "❌"} 容器后端（调度器+worker）\n")
                sb.toString()
            }
            showResultDialog("环境自检", rep)
        }
    }

    /** 工具 6：用法说明 */
    private fun showToolsHelp() {
        showResultDialog("工具用法说明", 
                "【本地功能】（无需环境）\n" +
                "· 壳诊断：直接读 APK，判定壳类型（秒级）\n" +
                "· 去壳清理：删壳 so/assets，找真实入口\n" +
                "· 收集 Dump：Shizuku 读取模块产物\n\n" +
                "【云端功能】（需 token + 网络）\n" +
                "· 云端构建：上传 DEX → GitHub Actions → 下载 APK\n\n" +
                "【需要 Termux/容器】（可选）\n" +
                "· 构建脱壳模块（make_dump_module.sh）\n" +
                "· smali 正则替换（需 baksmali/java）\n\n" +
                "详见工作区「架构说明.md」")
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
                    Toast.makeText(this@MainActivity, "✅ 已提交：$label", Toast.LENGTH_LONG).show()
                    refreshTasks()
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
                // ⚠️ 自适应刷新：有活跃任务时 1s 高频（进度可见），
                //    全部终态时降到 5s（省电、省 IO，避免空转扫目录）。
                delay(if (hasActiveTask) 1500L else 5000L)
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