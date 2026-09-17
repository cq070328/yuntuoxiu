package com.yuntuoxiu.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * 云脱修 Shizuku 客户端封装。
 *
 * 职责：
 *  1) 绑定/解绑 UserService（IYunTuoXiuService）
 *  2) 监听 Shizuku 生命周期：binder 上线/下线、权限授予/回收
 *  3) 高层能力封装：exec / installApk / uninstallApp / startActivity /
 *     clearAppData / getAppInfo（内部自动确保绑定）
 *  4) 断连/权限回收时 -> TaskStatusReporter 上报任务失败
 *
 * 线程模型：所有 IPC 调用需在非主线程执行（本类仅在调用方线程同步调用，
 * 调用方负责放到 Dispatchers.IO）。
 */
object ShizukuClient {

    private const val TAG = "ShizukuClient"
    const val REQUEST_CODE_PERMISSION = 301

    @Volatile private var service: IYunTuoXiuService? = null
    @Volatile private var bound = false
    @Volatile private var lastPermissionGranted = false
    /** 是否已有一次绑定在途（防重复 bindUserService 泄漏连接） */
    @Volatile private var bindingInFlight = false

    private var appContext: Context? = null
    private var healthCheckJob: Thread? = null

    /** 服务端断开回调 */
    private val remoteCallback = object : IYunTuoXiuCallback.Stub() {
        override fun onServiceDisconnected(reason: Int) {
            Log.w(TAG, "UserService 主动通知断开: ${ShizukuErrorCodes.describe(reason)}")
            service = null
            bound = false
            TaskStatusReporter.reportToActiveTasks(
                TaskStatusReporter.EVENT_DISCONNECTED,
                ShizukuErrorCodes.toBackendFailCode(ShizukuErrorCodes.ERR_BINDER_DEAD),
                "UserService 断开(reason=$reason)"
            )
        }
    }

    // ---------------- 绑定参数 ----------------

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(pkg(), YunTuoXiuUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku")
            .debuggable(false)
            .version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "UserService 已连接")
            service = IYunTuoXiuService.Stub.asInterface(binder)
            bound = true
            bindingInFlight = false
            try {
                service?.registerCallback(remoteCallback)
                Log.i(TAG, "服务版本: ${service?.getVersion()}")
            } catch (e: RemoteException) {
                Log.w(TAG, "注册回调失败", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "UserService 连接断开")
            service = null
            bound = false
            bindingInFlight = false
        }
    }

    // ---------------- 生命周期监听 ----------------

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder 上线")
        maybeAutoBind()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder 下线（服务被终止/权限回收）")
        service = null
        bound = false
        TaskStatusReporter.reportToActiveTasks(
            TaskStatusReporter.EVENT_DISCONNECTED,
            ShizukuErrorCodes.toBackendFailCode(ShizukuErrorCodes.ERR_SHIZUKU_NOT_RUNNING),
            "Shizuku binder 断开"
        )
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_CODE_PERMISSION) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Shizuku 权限结果: granted=$granted")
            if (!granted && lastPermissionGranted) {
                // 权限被回收
                TaskStatusReporter.reportToActiveTasks(
                    TaskStatusReporter.EVENT_PERMISSION_REVOKED,
                    ShizukuErrorCodes.toBackendFailCode(ShizukuErrorCodes.ERR_PERMISSION_DENIED),
                    "Shizuku 权限被回收"
                )
            }
            lastPermissionGranted = granted
            if (granted) maybeAutoBind()
        }

    // ---------------- 初始化 ----------------

    /** App 启动时调用一次 */
    fun init(context: Context) {
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        lastPermissionGranted = isGranted()
        if (lastPermissionGranted) maybeAutoBind()
        startHealthCheck()
    }

    fun release() {
        stopHealthCheck()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (e: Exception) {
            Log.w(TAG, "解除监听失败", e)
        }
        unbind()
    }

    // ---------------- 健康检查（兜底感知权限回收） ----------------

    private fun startHealthCheck() {
        if (healthCheckJob != null) return
        healthCheckJob = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val granted = isGranted()
                    if (lastPermissionGranted && !granted) {
                        Log.w(TAG, "健康检查发现权限被回收")
                        TaskStatusReporter.reportToActiveTasks(
                            TaskStatusReporter.EVENT_PERMISSION_REVOKED,
                            ShizukuErrorCodes.toBackendFailCode(ShizukuErrorCodes.ERR_PERMISSION_DENIED),
                            "健康检查: 权限被回收"
                        )
                    }
                    lastPermissionGranted = granted
                    // binder 存活检查
                    if (!Shizuku.pingBinder() && bound) {
                        Log.w(TAG, "健康检查发现 binder 已死")
                        service = null
                        bound = false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "健康检查异常", e)
                }
                try {
                    Thread.sleep(5000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }.apply { isDaemon = true; name = "yuntuoxiu-shizuku-health" }
        healthCheckJob?.start()
    }

    private fun stopHealthCheck() {
        healthCheckJob?.interrupt()
        healthCheckJob = null
    }

    // ---------------- 授权 / 绑定 ----------------

    fun isAvailable(): Boolean = Shizuku.pingBinder()

    fun isGranted(): Boolean {
        return try {
            Shizuku.pingBinder() && (Shizuku.isPreV11() ||
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) return
        if (isGranted()) return
        Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
    }

    /** 确保已绑定服务；返回是否可用 */
    fun ensureBound(): Boolean {
        if (bound && service != null) return true
        if (!isGranted()) {
            Log.w(TAG, "未授权，无法绑定")
            return false
        }
        // ⚠️ 避免重复触发 bindUserService（多次绑定会泄漏连接）
        if (!bindingInFlight) maybeAutoBind()
        // 绑定是异步的；指数退避轮询（总上限 ~2s，比原来 3s 更短）
        var waited = 0L
        var step = 50L
        val deadline = 2000L
        while (waited < deadline) {
            if (bound && service != null) return true
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            waited += step
            step = (step * 2).coerceAtMost(400L)  // 50->100->200->400
        }
        return bound && service != null
    }

    private fun maybeAutoBind() {
        if (bound) return
        if (bindingInFlight) return        // 已有绑定在途，避免重复
        if (!isGranted()) return
        bindingInFlight = true
        try {
            // ⚠️ 官方 API（13.1.5）签名：`void bindUserService(UserServiceArgs, ServiceConnection)`
            //    —— 无返回值！绑定结果通过 connection 回调得知。
            Shizuku.bindUserService(userServiceArgs(), connection)
            Log.i(TAG, "bindUserService 已调用，等待 onServiceConnected 回调")
        } catch (e: Exception) {
            bindingInFlight = false
            Log.w(TAG, "绑定 UserService 失败", e)
        }
    }

    fun unbind() {
        try {
            if (bound) Shizuku.unbindUserService(userServiceArgs(), connection, true)
        } catch (e: Exception) {
            Log.w(TAG, "解绑失败", e)
        }
        service = null
        bound = false
        bindingInFlight = false
    }

    // ---------------- 高层能力封装 ----------------

    private fun errBundle(code: Int, detail: String): Bundle = Bundle().apply {
        putInt("code", code)
        putString("stdout", "")
        putString("stderr", detail)
        putString("fail_code", ShizukuErrorCodes.toBackendFailCode(code))
    }

    fun exec(cmd: String, timeoutMs: Int = ShizukuShellExecutor.DEFAULT_TIMEOUT_MS): Bundle {
        if (!ensureBound()) {
            return errBundle(ShizukuErrorCodes.ERR_SERVICE_NOT_BOUND, "服务未绑定")
        }
        return try {
            service!!.execWithTimeout(cmd, timeoutMs)
        } catch (e: RemoteException) {
            errBundle(ShizukuErrorCodes.ERR_BINDER_DEAD, "IPC 异常: ${e.message}")
        }
    }

    fun installApk(apkPath: String, replace: Boolean = true): Bundle {
        if (!ensureBound()) return errBundle(ShizukuErrorCodes.ERR_SERVICE_NOT_BOUND, "服务未绑定")
        return try {
            service!!.installApk(apkPath, replace)
        } catch (e: RemoteException) {
            errBundle(ShizukuErrorCodes.ERR_BINDER_DEAD, "IPC 异常: ${e.message}")
        }
    }

    fun uninstallApp(pkg: String): Bundle {
        if (!ensureBound()) return errBundle(ShizukuErrorCodes.ERR_SERVICE_NOT_BOUND, "服务未绑定")
        return try {
            service!!.uninstallApp(pkg)
        } catch (e: RemoteException) {
            errBundle(ShizukuErrorCodes.ERR_BINDER_DEAD, "IPC 异常: ${e.message}")
        }
    }

    fun startActivity(pkg: String, cls: String = ""): Bundle {
        if (!ensureBound()) return errBundle(ShizukuErrorCodes.ERR_SERVICE_NOT_BOUND, "服务未绑定")
        return try {
            service!!.startActivity(pkg, cls)
        } catch (e: RemoteException) {
            errBundle(ShizukuErrorCodes.ERR_BINDER_DEAD, "IPC 异常: ${e.message}")
        }
    }

    fun clearAppData(pkg: String): Bundle {
        if (!ensureBound()) return errBundle(ShizukuErrorCodes.ERR_SERVICE_NOT_BOUND, "服务未绑定")
        return try {
            service!!.clearAppData(pkg)
        } catch (e: RemoteException) {
            errBundle(ShizukuErrorCodes.ERR_BINDER_DEAD, "IPC 异常: ${e.message}")
        }
    }

    fun getAppInfo(pkg: String): Bundle {
        if (!ensureBound()) return errBundle(ShizukuErrorCodes.ERR_SERVICE_NOT_BOUND, "服务未绑定")
        return try {
            service!!.getAppInfo(pkg)
        } catch (e: RemoteException) {
            errBundle(ShizukuErrorCodes.ERR_BINDER_DEAD, "IPC 异常: ${e.message}")
        }
    }

    private fun pkg(): String = appContext?.packageName ?: "com.yuntuoxiu.app"
}
