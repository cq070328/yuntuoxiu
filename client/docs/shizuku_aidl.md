# Shizuku UserService AIDL 说明

云脱修客户端的底层 IPC 地基。所有需要系统级权限的操作（install / am / pm clear /
dumpsys）都通过 Shizuku UserService 执行（无需 root）。

## 文件清单

| 文件 | 位置 | 说明 |
|------|------|------|
| `IYunTuoXiuService.aidl` | `src/main/aidl/com/yuntuoxiu/app/shizuku/` | 主接口（能力封装） |
| `IYunTuoXiuCallback.aidl` | 同上 | 状态回调 |
| `ShizukuErrorCodes.kt` | `src/main/java/.../shizuku/` | 返回码 + 后端 fail_code 映射 |
| `ShizukuShellExecutor.kt` | 同上 | Shell 执行（超时 + 并发输出捕获） |
| `YunTuoXiuUserService.kt` | 同上 | 服务端实现（跑在 shell uid） |
| `ShizukuClient.kt` | 同上 | 客户端封装（绑定/监听/高层能力） |
| `TaskStatusReporter.kt` | 同上 | 断连/权限回收上报任务失败 |

## 返回码约定

所有方法返回 `Bundle`：

```
code      : Int     0=OK，负数=错误（见 ShizukuErrorCodes）
stdout    : String  标准输出
stderr    : String  错误输出
fail_code : String  已映射的后端 fail_code（错误时存在）
```

`getAppInfo` 额外返回 `versionName / versionCode / firstInstallTime / raw`。

## 错误码 ↔ 后端 fail_code 映射

| 客户端错误码 | 后端 fail_code | 可重试 |
|---|---|---|
| ERR_TIMEOUT (-1004) | TIMEOUT_DUMP_EXEC | ✅ |
| ERR_SHIZUKU_NOT_RUNNING (-1001) | DUMP_GADGET_LOAD_FAIL | ✅ |
| ERR_PERMISSION_DENIED (-1002) | AUTH_TOKEN_INVALID | ❌ |
| ERR_SERVICE_NOT_BOUND / ERR_BINDER_DEAD | DUMP_CLIENT_CRASH | ✅ |
| INSTALL_FAILED* (-200x) | DUMP_INSTALL_FAIL | 部分 |
| START_FAILED (-3001) | DUMP_CLIENT_CRASH | ❌ |
| CLEAR_FAILED (-5001) | DUMP_CLIENT_CRASH | ❌ |
| 其他 | SYS_INTERNAL_ERROR | ❌ |

> 映射表在 `ShizukuErrorCodes.toBackendFailCode()`，与后端
> `backend/Core/fail_codes.py` 的枚举值**精确对齐**。

## 状态监听（断连 / 权限回收）

`ShizukuClient.init()` 注册三路监听 + 一路兜底：

1. `addBinderReceivedListenerSticky` — binder 上线自动重绑
2. `addBinderDeadListener` — **binder 下线** → 上报 `SHIZUKU_DISCONNECTED`
3. `addRequestPermissionResultListener` — **权限回调** → 权限被拒上报 `PERMISSION_REVOKED`
4. 健康检查线程（5s 周期）— 兜底感知权限回收 / binder 死亡

任一路触发都会调用 `TaskStatusReporter.reportToActiveTasks()`，把事件写入：
```
tasks/<task_id>/work/client_status.json
{ "event":"SHIZUKU_DISCONNECTED", "fail_code":"DUMP_CLIENT_CRASH", ... }
```

后端 `watcher.py` 轮询到该文件后，**立即**把任务置 FAILED（fail_code 采用上报值），
或按状态机允许时触发降级兜底。此闭环已实测通过。

## Shell 防护

`ShizukuShellExecutor`：
- **超时**：`waitFor(timeout)`，超时 `destroyForcibly()`，返回 `ERR_TIMEOUT`
- **并发读取**：stdout/stderr 各起一个线程读，避免管道缓冲区写满死锁
- **默认超时**：15s；安装 120s；卸载/启动/清数据 30~60s

## 使用示例

```kotlin
// 1) App 启动时初始化（YunTuoXiuApp.onCreate 已调用）
ShizukuClient.init(this)

// 2) 请求授权
if (!ShizukuClient.isGranted()) ShizukuClient.requestPermission()

// 3) 调用能力（务必在 IO 线程）
lifecycleScope.launch(Dispatchers.IO) {
    val r = ShizukuClient.installApk("/data/local/tmp/clone.apk", replace = true)
    val code = r.getInt("code")
    if (code != ShizukuErrorCodes.OK) {
        val failCode = r.getString("fail_code")   // 直接可用于回执后端
    }
}
```

## 编译要求

`build.gradle.kts` 无需额外配置（AGP 会自动处理 `src/main/aidl`）。
依赖：
```kotlin
implementation("dev.rikka.shizuku:api:13.1.5")
implementation("dev.rikka.shizuku:provider:13.1.5")
```

## 已知限制

- `ShizukuService` 基类由 Shizuku SDK 提供；本工程用 `ShizukuService()` 作为基类，
  实际应继承 SDK 中的 `rikka.shizuku.ShizukuService`（UserService 基类）。
  如你的 Shizuku 版本 API 不同（如基类名/包名变化），按官方 sample 调整 import。
- 服务端在 shell uid 下运行，`pm`/`am` 命令的权限等同 adb shell。