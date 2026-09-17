package com.yuntuoxiu.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
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
 * 云脱修 主界面：
 *  - Shizuku 授权状态
 *  - 任务列表（实时刷新）
 *  - 选择 APK（文件 / 已安装应用）
 *  - 启动后台 Worker
 *  - 查看工作日志
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rvTasks: RecyclerView
    private lateinit var tvShizuku: TextView
    private lateinit var tvTaskCount: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: android.widget.ScrollView
    private lateinit var adapter: TaskAdapter
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        LogStore.i(TAG, "MainActivity.onCreate")

        rvTasks = findViewById(R.id.rvTasks)
        tvShizuku = findViewById(R.id.tvShizuku)
        tvTaskCount = findViewById(R.id.tvTaskCount)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)

        adapter = TaskAdapter { task -> openDetail(task) }
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

        // 选择 APK → 弹出二选一（文件 / 已安装应用）
        findViewById<View>(R.id.btnPickApk).setOnClickListener { chooseApkSource() }

        findViewById<View>(R.id.btnStartWorker).setOnClickListener {
            try {
                val intent = Intent(this, WorkerService::class.java)
                startForegroundService(intent)
                LogStore.i(TAG, "已请求启动 Worker")
                Toast.makeText(this, "Worker 已启动", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                LogStore.e(TAG, "启动 Worker 失败: ${t.message}")
                Toast.makeText(this, "启动失败: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }

        // 日志改为内嵌实时刷新（见 refreshLog / startAutoRefresh），不再用按钮
        requestPermissionsIfNeeded()
        startAutoRefresh()
    }

    override fun onResume() {
        super.onResume()
        tvShizuku.text = if (ShizukuClient.isGranted())
            "✅ Shizuku 已授权（ABI: arm64-v8a）"
        else "⚠️ Shizuku 未授权"
        refreshTasks()
    }

    // ---------------- 选择 APK 来源 ----------------

    private fun chooseApkSource() {
        val options = arrayOf(
            "从文件选择（.apk）",
            "从已安装应用选择"
        )
        AlertDialog.Builder(this)
            .setTitle("选择 APK 来源")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickApkFromFile()
                    1 -> pickApkFromInstalled()
                }
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

    /** 从已安装应用选择（用其 APK 路径） */
    private fun pickApkFromInstalled() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                try {
                    packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { it.packageName != packageName }   // 排除自己
                        .map { ai ->
                            val label = ai.loadLabel(packageManager).toString()
                            val srcDir = ai.sourceDir
                            Triple(label, ai.packageName, srcDir)
                        }
                        .sortedBy { it.first }
                } catch (t: Throwable) {
                    LogStore.e(TAG, "枚举已安装应用失败: ${t.message}")
                    emptyList()
                }
            }
            if (apps.isEmpty()) {
                Toast.makeText(this@MainActivity, "未获取到应用列表（检查 QUERY_ALL_PACKAGES 权限）",
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            val names = apps.map { "${it.first}  (${it.second})" }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("选择已安装应用（${apps.size} 个）")
                .setItems(names) { _, which ->
                    val (label, pkg, srcDir) = apps[which]
                    submitInstalledApp(label, pkg, srcDir)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun submitInstalledApp(label: String, pkg: String, srcDir: String) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val src = File(srcDir)
                    if (!src.exists()) return@withContext SubmitResult.Failure("源 APK 不存在: $srcDir")
                    // 复制到 cache 后走统一提交（保持原始文件只读）
                    val tmp = File(cacheDir, "installed_${pkg}.apk")
                    src.copyTo(tmp, overwrite = true)
                    TaskRepository.submitTask(this@MainActivity, tmp, allowAutoDegrade = true)
                } catch (t: Throwable) {
                    SubmitResult.Failure("提交失败: ${t.message}")
                }
            }
            when (r) {
                is SubmitResult.Success -> {
                    LogStore.i(TAG, "已提交已安装应用: $label ($pkg)")
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
                        Toast.makeText(this@MainActivity, "任务已提交，等待后端处理", Toast.LENGTH_SHORT).show()
                        refreshTasks()
                    }
                    is SubmitResult.Failure ->
                        Toast.makeText(this@MainActivity, "提交失败: ${r.reason}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------------- 日志（内嵌实时刷新） ----------------

    /** 刷新内嵌日志区（最新在前，自动滚到顶部看最新） */
    private fun refreshLog() {
        lifecycleScope.launch {
            val log = withContext(Dispatchers.IO) { LogStore.readAll() }
            tvLog.text = if (log.isBlank()) "（暂无日志）" else log
            // 自动滚到顶部（最新日志在最上面）
            svLog.post { svLog.scrollTo(0, 0) }
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
                refreshTasks()
                refreshLog()
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
            ActivityCompat.requestPermissions(this, needs.toTypedArray(), 200)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "请授予「所有文件访问权限」", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PICK_APK = 100
    }

    // ---------- 任务列表适配器 ----------
    inner class TaskAdapter(private val onClick: (TaskMetaView) -> Unit) :
        RecyclerView.Adapter<TaskAdapter.VH>() {

        private var items: List<TaskMetaView> = emptyList()

        fun submit(list: List<TaskMetaView>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_task, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            holder.name.text = t.sourceApk.substringAfterLast('/')
            holder.state.text = "[${t.stateLabel}] 壳: ${t.shellLabel}"
            holder.code.text = t.failCode?.let { "fail: $it" } ?: t.taskId
            holder.itemView.setOnClickListener { onClick(t) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvName)
            val state: TextView = v.findViewById(R.id.tvState)
            val code: TextView = v.findViewById(R.id.tvCode)
        }
    }
}