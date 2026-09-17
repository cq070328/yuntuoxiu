// IYunTuoXiuService.aidl
//
// 云脱修 Shizuku UserService 主接口。
//
// 设计要点：
// 1) 所有方法返回 Bundle，统一结构：
//      code   : int     返回码（见 ShizukuErrorCodes，0=OK，负数=错误）
//      stdout : String  标准输出
//      stderr : String  错误输出
// 2) 常用能力（安装/卸载/启动/清数据/应用信息）全部在服务端封装，
//    客户端拿到的是「已映射错误码」的结果，可直接对齐后端 fail_code。
// 3) execWithTimeout 带超时控制，服务端保证不会卡死调用方。
package com.yuntuoxiu.app.shizuku;

import com.yuntuoxiu.app.shizuku.IYunTuoXiuCallback;

interface IYunTuoXiuService {
    /** 服务版本（与客户端版本做兼容校验） */
    int getVersion();

    /** 执行任意 shell 命令（默认超时 15s） */
    Bundle exec(String cmd);

    /** 执行任意 shell 命令，指定超时（毫秒） */
    Bundle execWithTimeout(String cmd, int timeoutMs);

    // ---------------- 常用能力封装（返回码与后端 fail_code 映射） ----------------

    /** 安装 APK：pm install [-r] <apkPath> */
    Bundle installApk(String apkPath, boolean replace);

    /** 卸载应用：pm uninstall <pkg> */
    Bundle uninstallApp(String pkg);

    /** 启动 Activity：am start -n <pkg>/<cls>（cls 为空则用 monkey 启动） */
    Bundle startActivity(String pkg, String cls);

    /** 清除应用数据：pm clear <pkg>（对抗加固留存标记） */
    Bundle clearAppData(String pkg);

    /** 获取应用信息：dumpsys package <pkg> 摘要（versionName/versionCode/firstInstallTime） */
    Bundle getAppInfo(String pkg);

    // ---------------- 状态回调 ----------------
    void registerCallback(IYunTuoXiuCallback cb);
    void unregisterCallback(IYunTuoXiuCallback cb);
}