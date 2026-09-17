package com.yuntuoxiu.app
import android.app.Application
import com.yuntuoxiu.app.shizuku.ShizukuClient

/**
 * 云脱修 —— APK 云脱壳 + 云修复 客户端。
 *
 * 全局常量：与后端 config.py 对齐的工作区路径。
 */
class YunTuoXiuApp : Application() {

    companion object {
        lateinit var instance: YunTuoXiuApp
            private set

        /** Operit 公共工作区根路径（与后端 WORKSPACE_ROOT 一致） */
        const val WORKSPACE_ROOT = "/storage/emulated/0/MT2/apks"

        /** 本系统根目录（与后端 CLOUD_ROOT 一致） */
        const val CLOUD_ROOT = "$WORKSPACE_ROOT/unpackcloud"

        /** 上传目录（APP 把用户选中的 APK 复制到这里并提交创建请求） */
        const val UPLOADS_ROOT = "$CLOUD_ROOT/uploads"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化 Shizuku 客户端：注册生命周期监听（断连/权限回收自动上报）
        try {
            ShizukuClient.init(this)
        } catch (t: Throwable) {
            LogStore.e("YunTuoXiuApp", "ShizukuClient.init 失败: ${t.message}")
        }
        // 【新增】App 启动即自动启动 Worker（无需手动点）
        try {
            val intent = android.content.Intent(this,
                com.yuntuoxiu.app.worker.WorkerService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            LogStore.i("YunTuoXiuApp", "已自动启动 Worker")
        } catch (t: Throwable) {
            LogStore.e("YunTuoXiuApp", "自动启动 Worker 失败: ${t.message}")
        }

        // 【新增】Termux 环境自检 + 自动搭建（若有 Termux）
        // 在后台线程执行，不阻塞启动
        Thread {
            try {
                if (com.yuntuoxiu.app.worker.TermuxBridge.isTermuxInstalled(this)) {
                    // 检查 bootstrap 脚本存在性 + 触发（幂等）
                    val boot = java.io.File("$WORKSPACE_ROOT/termux_bootstrap.sh")
                    if (boot.exists()) {
                        LogStore.i("YunTuoXiuApp", "检测到 Termux，触发环境自检/搭建")
                        com.yuntuoxiu.app.worker.TermuxBridge.ensureEnvironment(this)
                    } else {
                        LogStore.w("YunTuoXiuApp", "bootstrap 脚本缺失: ${boot.absolutePath}")
                    }
                } else {
                    LogStore.i("YunTuoXiuApp", "未检测到 Termux，跳过环境搭建")
                }
            } catch (t: Throwable) {
                LogStore.e("YunTuoXiuApp", "Termux 环境自检失败: ${t.message}")
            }
        }.start()
    }

    override fun onTerminate() {
        ShizukuClient.release()
        super.onTerminate()
    }
}
