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
        ShizukuClient.init(this)
    }

    override fun onTerminate() {
        ShizukuClient.release()
        super.onTerminate()
    }
}
