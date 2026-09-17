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
import android.widget.ImageView
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
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rvTasks: RecyclerView
    private lateinit var tvShizuku: TextView
    private lateinit var tvTaskCount: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: android.widget.ScrollView
    private lateinit var adapter: TaskAdapter
    private var refreshing = false

    /** 应用列表项（带图标） */
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

        adapter = TaskAdapter { task -> openDetail(task) }
        rvTasks.layoutManager = LinearLayoutManager(this)
        rvTasks.adapter = adapter
        LogStore.i(TAG, "视图初始化完成")

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

        LogStore.i(TAG, "准备请求权限")
        requestPermissionsIfNeeded()
        LogStore.i(TAG, "启动自动刷新循环")
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
        LogStore.i(TAG, "onResume 刷新完成")
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

    /** 从已安装应用选择（带图标 + 中文名优先） */
    private fun pickApkFromInstalled() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "正在加载应用列表...", Toast.LENGTH_SHORT).show()
            val apps = withContext(Dispatchers.IO) {
                try {
                    val pm = packageManager
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { it.packageName != packageName }
                        .map { ai ->
                            AppItem(
                                label = ai.loadLabel(pm).toString(),
                                packageName = ai.packageName,
                                sourceDir = ai.sourceDir,
                                icon = try { ai.loadIcon(pm) } catch (e: Throwable) { null },
                            )
                        }
                        // 中文名优先：含 CJK 的排前面，其余按字母
                        .sortedWith(compareByDescending<AppItem> { containsCJK(it.label) }
                            .thenBy { it.label })
                } catch (t: Throwable) {
                    LogStore.e(TAG, "枚举应用失败: ${t.message}")
                    emptyList()
                }
            }
            LogStore.i(TAG, "已加载 ${apps.size} 个应用")
            if (apps.isEmpty()) {
                Toast.makeText(this@MainActivity, "未获取到应用列表", Toast.LENGTH_LONG).show()
                return@launch
            }
            showAppListDialog(apps)
        }
    }

    /** 显示带图标的应用选择对话框 */
    private fun showAppListDialog(apps: List<AppItem>) {
        val iconSize = (resources.displayMetrics.density * 40).toInt()
        val listAdapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = convertView ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_app, parent, false)
                val item = apps[position]
                val iv = v.findViewById<ImageView>(R.id.ivAppIcon)
                val tvName = v.findViewById<TextView>(R.id.tvAppName)
                val tvPkg = v.findViewById<TextView>(R.id.tvAppPkg)
                tvName.text = item.label
                tvPkg.text = item.packageName
                if (item.icon != null) iv.setImageDrawable(item.icon) else iv.setImageDrawable(null)
                return v
            }
        }

        AlertDialog.Builder(this)
            .setTitle("选择已安装应用（${apps.size} 个）")
            .setAdapter(listAdapter) { _, which ->
                val item = apps[which]
                submitInstalledApp(item.label, item.packageName, item.sourceDir)
            }
            .setNegativeButton("取消", null)
            .show()
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

    // ---------------- 日志（内嵌实时刷新） ----------------

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
        startActivity(Intent(this, TaskDetailActivity::class.java)
            .putExtra(TaskDetailActivity.EXTRA_TASK_ID, task.taskId))
    }

    private fun startAutoRefresh() {
        lifecycleScope.launch {
            while (true) {
                try {
                    refreshTasks()
                    refreshLog()
                } catch (t: Throwable) {
                    LogStore.e(TAG, "自动刷新异常: ${t.message}")
                }
                delay(2000)
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
            adapter.submit(tasks)
            tvTaskCount.text = "任务列表（${tasks.size}）"
            refreshing = false
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

    /** 判断字符串是否含中日韩字符（用于中文优先排序） */
    private fun containsCJK(s: String): Boolean {
        for (c in s) {
            if (c.code in 0x4E00..0x9FFF ||   // CJK 统一表意
                c.code in 0x3400..0x4DBF ||   // 扩展 A
                c.code in 0x3040..0x30FF) {   // 日文假名
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PICK_APK = 100
    }

    // ---------- 任务列表适配器（带图标 + 三状态徽章）----------
    inner class TaskAdapter(private val onClick: (TaskMetaView) -> Unit) :
        RecyclerView.Adapter<TaskAdapter.VH>() {

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

            // 1) 图标：优先按包名查已安装应用
            val icon = lookupIcon(t.lookupPackage)
            if (icon != null) {
                holder.icon.setImageDrawable(icon)
                holder.icon.visibility = View.VISIBLE
            } else {
                holder.icon.setImageDrawable(null)
                holder.icon.visibility = View.GONE
            }

            // 2) 应用名（有包名时优先显示应用名，否则显示文件名）
            val appName = lookupAppLabel(t.lookupPackage)
            holder.name.text = appName ?: t.displayName

            // 3) 状态（详细状态 + 壳标签）
            holder.state.text = "[${t.stateLabel}] 壳: ${t.shellLabel}"

            // 4) 任务 id / 失败码
            holder.code.text = t.failCode?.let { "fail: $it  |  ${t.taskId}" } ?: t.taskId

            // 5) 三状态徽章
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
        }

        /** 按包名查图标（带缓存） */
        private fun lookupIcon(pkg: String?): Drawable? {
            if (pkg.isNullOrBlank()) return null
            if (iconCache.containsKey(pkg)) return iconCache[pkg]
            val icon = try {
                packageManager.getApplicationIcon(pkg)
            } catch (e: Throwable) {
                null
            }
            iconCache[pkg] = icon
            return icon
        }

        /** 按包名查应用显示名 */
        private fun lookupAppLabel(pkg: String?): String? {
            if (pkg.isNullOrBlank()) return null
            return try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                ai.loadLabel(packageManager).toString()
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