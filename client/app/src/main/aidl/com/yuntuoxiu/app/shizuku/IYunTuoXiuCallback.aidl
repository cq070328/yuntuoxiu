// IYunTuoXiuCallback.aidl
//
// 云脱修 Shizuku 用户服务状态回调。
// 由客户端实现（Stub），注册到服务端，用于接收：
//  - onServiceDisconnected : 服务进程断开（shizuku server 重启 / 服务被杀）
//
// 注意：权限回收在客户端侧由 ShizukuClient 周期检测 + binder listener 感知，
//       两条路径都会触发 TaskStatusReporter 上报，避免依赖单一回调。
package com.yuntuoxiu.app.shizuku;

interface IYunTuoXiuCallback {
    /** 服务端主动通知：本服务即将/已经断开 */
    void onServiceDisconnected(int reason);
}