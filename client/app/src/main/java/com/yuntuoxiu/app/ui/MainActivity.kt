package com.yuntuoxiu.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import com.yuntuoxiu.app.R
import com.yuntuoxiu.app.data.TaskMetaView
import com.yuntuoxiu.app.data.TaskRepository
import com.yuntuoxiu.app.worker.WorkerService
import com.yuntuoxiu.app.shizuku.ShizukuClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 云脱修 主界面：
 *  - Shizuku 授权状态
 *  - 任务列表（实时刷新）
 *  - 选择 APK 提交新任务
 *  - 启动后台 Worker
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rvTasks: RecyclerView
    private lateinit var tvShizuku: TextView
    private lateinit var adapter: TaskAdapter
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rvTasks = findViewById(R.id.rvTasks)
        tvShizuku = findViewById(R.id.tvShizuku)

        adapter = TaskAdapter { task -> openDetail(task) }
        rvTasks.layoutManager = LinearLayoutManager(this)
        rvTasks.adapter = adapter

        findViewById<View>(R.id.btnGrantShizuku).setOnClickListener {
            if (ShizukuClient.isGranted()) {
                tvShizuku.text = "✅ Shizuku 已授权"
            } else {
                ShizukuClient.requestPermission()
                Toast.makeText(this, "请在 Shizuku Manager 中授权", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<View>(R.id.btnPickApk).setOnClickListener { pickApk() }

        findViewById<View>(R.id.btnStartWorker).setOnClickListener {
            val intent = Intent(this, WorkerService::class.java)
            startForegroundService(intent)
            Toast.makeText(this, "Worker 已启动", Toast.LENGTH_SHORT).show()
        }

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

    private fun pickApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        startActivityForResult(intent, REQ_PICK_APK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_APK && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    // 从 SAF uri 复制到本地临时文件后提交
                    val tmp = File(cacheDir, "picked_${System.currentTimeMillis()}.apk")
                    contentResolver.openInputStream(uri)?.use { ins ->
                        tmp.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                    TaskRepository.submitTask(this@MainActivity, tmp, allowAutoDegrade = true)
                }.onSuccess {
                    Toast.makeText(this@MainActivity, "任务已提交，等待后端处理", Toast.LENGTH_SHORT).show()
                    refreshTasks()
                }.onFailure { e ->
                    Toast.makeText(this@MainActivity, "提交失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openDetail(task: TaskMetaView) {
        val intent = Intent(this, TaskDetailActivity::class.java)
            .putExtra(TaskDetailActivity.EXTRA_TASK_ID, task.taskId)
        startActivity(intent)
    }

    private fun startAutoRefresh() {
        lifecycleScope.launch {
            while (true) {
                refreshTasks()
                delay(3000)
            }
        }
    }

    private fun refreshTasks() {
        if (refreshing) return
        refreshing = true
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) { TaskRepository.listTasks() }
            adapter.submit(tasks)
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
        if (needs.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needs.toTypedArray(), 200)
        }
        // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            Toast.makeText(this, "请授予「所有文件访问权限」", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    companion object {
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