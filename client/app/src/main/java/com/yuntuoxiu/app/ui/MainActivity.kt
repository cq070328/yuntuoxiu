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
import com.yuntuoxiu.app.data.SubmitResult
import com.yuntuoxiu.app.data.TaskGroup
import com.yuntuoxiu.app.data.TaskMetaView
import com.yuntuoxiu.app.data.TaskRepository
import com.yuntuoxiu.app.shizuku.ShizukuClient
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

    /** 应用列表项 */
    private data class AppItem(
        val label: String,
        val packageName: String,
        val sourceDir: String,
        val icon: Drawable?,
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

        adapter = TaskAdapter(
            onClick = { task -> openDetail(task) },
            onLongClick = { task -> showTaskMenu(task) },
        )
        rvTasks.layoutManager = LinearLayoutManager(this)
        rvTasks.adapter = adapter

        findViewById<View>(R.id.btnGrantShizuku).setOnClickListener {
            if (ShizukuClient.isGranted()) {
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
            } else {
                ShizukuClient.requestPermission()
                Toast.makeText(this, "请在 Shizuku Manager 中授权", Toast.LENGTH_LONG).show()
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
            AlertDialog.Builder(this)
                .setTitle("清除日志")
                .setMessage("确认清除全部运行日志？")
                .setPositiveButton("清除") { _, _ ->
                    LogStore.clear()
                    refreshLog()
                    Toast.makeText(this, "日志已清除", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
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
        refreshTasks()
        refreshLog()
    }

    // ---------------- 任务长按菜单 ----------------

    /** 长按任务：左「取消」右「删除」 */
    private fun showTaskMenu(task: TaskMetaView) {
        AlertDialog.Builder(this)
            .setTitle("任务：${task.displayName}")
            .setMessage("状态：${task.stateLabel}\n壳：${task.shellLabel}\n${task.taskId}")
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .setPositiveButton("删除") { _, _ -> confirmDeleteTask(task) }
            .show()
    }

    /** 删除任务确认 */
    private fun confirmDeleteTask(task: TaskMetaView) {
        AlertDialog.Builder(this)
            .setTitle("删除任务")
            .setMessage("确认删除任务「${task.displayName}」？\n\n" +
                    "将删除该任务的所有文件（原始副本/dump/修复产物/日志）。\n此操作不可恢复。")
            .setPositiveButton("删除") { _, _ -> doDeleteTask(task) }
            .setNegativeButton("取消", null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("选择 APK 来源")
            .setItems(arrayOf("从文件选择（.apk）", "从已安装应用选择")) { _, which ->
                if (which == 0) pickApkFromFile() else pickApkFromInstalled()
            }
            .show()
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

        // 列表
        val listView = ListView(this)
        container.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val shown = ArrayList<AppItem>(allApps)
        val listAdapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = convertView ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_app, parent, false)
                val item = shown[position]
                val iv = v.findViewById<ImageView>(R.id.ivAppIcon)
                val tvName = v.findViewById<TextView>(R.id.tvAppName)
                val tvPkg = v.findViewById<TextView>(R.id.tvAppPkg)
                // 名字兜底
                tvName.text = item.label.ifBlank { item.packageName }
                tvPkg.text = item.packageName
                if (item.icon != null) iv.setImageDrawable(item.icon) else iv.setImageDrawable(null)
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

        val dialog = AlertDialog.Builder(this)
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
    }

    private fun submitInstalledApp(label: String, pkg: String, srcDir: String) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val src = File(srcDir)
                    if (!src.exists()) return@withContext SubmitResult.Failure("源 APK 不存在")
                    val tmp = File(cacheDir, "installed_${pkg}.apk")
                    src.copyTo(tmp, overwrite = true)
                    TaskRepository.submitTask(this@MainActivity, tmp, allowAutoDegrade = true)
                } catch (t: Throwable) {
                    SubmitResult.Failure("提交失败: ${t.message}")
                }
            }
            when (r) {
                is SubmitResult.Success -> {
                    LogStore.i(TAG, "已提交: $label ($pkg)")
                    Toast.makeText(this@MainActivity, "已提交：$label", Toast.LENGTH_SHORT).show()
                    refreshTasks()
                }
                is SubmitResult.Failure -> {
                    LogStore.e(TAG, "提交失败: ${r.reason}")
                    Toast.makeText(this@MainActivity, "提交失败: ${r.reason}", Toast.LENGTH_LONG).show()
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

    private fun refreshLog() {
        lifecycleScope.launch {
            try {
                val log = withContext(Dispatchers.IO) { LogStore.readAll() }
                tvLog.text = if (log.isBlank()) "（暂无日志）" else log
                svLog.post { svLog.scrollTo(0, 0) }
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
                    // 任务列表刷新快（1s），日志刷新慢（3s）
                    refreshTasks()
                    if (tick % 3 == 0) refreshLog()
                    tick++
                } catch (t: Throwable) {
                    LogStore.e(TAG, "自动刷新异常: ${t.message}")
                }
                delay(1000)
            }
        }
    }

    private fun refreshTasks() {
        if (refreshing) return
        refreshing = true
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) {
                try { TaskRepository.listTasks() } catch (t: Throwable) {
                    LogStore.e(TAG, "读取任务失败: ${t.message}")
                    emptyList()
                }
            }
            // 【提速】去掉本地骨架中「已被后端处理」的重复项
            val deduped = dedupeLocalSkeleton(tasks)
            adapter.submit(deduped)
            tvTaskCount.text = "任务列表（${deduped.size}）"
            refreshing = false
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

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PICK_APK = 100
    }

    // ---------- 任务列表适配器（图标 + 应用名 + 徽章 + 长按）----------
    inner class TaskAdapter(
        private val onClick: (TaskMetaView) -> Unit,
        private val onLongClick: (TaskMetaView) -> Unit,
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
            holder.state.text = "[${t.stateLabel}] 壳: ${t.shellLabel}"
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