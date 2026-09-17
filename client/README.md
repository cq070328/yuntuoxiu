# 云脱修（YunTuoXiu）客户端

APK 云脱壳 + 云修复系统的 Android 前端 App。

## 应用信息
- **应用名**：云脱修
- **包名**：`com.yuntuoxiu.app`
- **版本**：1.5.0
- **ABI**：arm64-v8a（固定）

## 功能
| 模块 | 说明 |
|------|------|
| 任务列表 | 实时扫描工作区 tasks/，显示状态机进度 + 壳识别结果 |
| 选择 APK | SAF 选文件 -> 复制到 uploads/ -> 写创建请求（后端 watcher 消费） |
| 任务详情 | 状态 / fail_code / 壳标签 / 修复轨迹 / 降级记录 / 日志尾部 |
| 取消任务 | 写取消请求 -> 后端 watcher 校验受理 |
| 后台 Worker | 前台服务轮询所有活跃任务的 action 队列并执行回执 |
| Shizuku | 授权 + 系统级 shell（pm/am 借用） |
| 真机 dump | LSPatch + Frida-Gadget 注入 -> 内存 dump -> 0字节过滤 -> MD5 -> 分片写入 chunks/ |

## 架构
```
data/    数据模型（ActionPayload / DexManifest / TaskMetaView / 请求）
worker/  ShizukuManager / ActionQueue / ActionExecutor / DexDumper / WorkerService
ui/      MainActivity（列表+提交）/ TaskDetailActivity（详情+日志+取消）
```

## 职责边界
客户端**只做真机侧操作**，后端**只下发 action_payload**，
二者通过 Operit 公共工作区文件队列通信（可无缝升级为 HTTP）。

## 编译
```bash
cd unpackcloud/client
./gradlew assembleDebug
```

## 依赖
- Shizuku SDK（dev.rikka.shizuku）
- LSPatch（org.lsposed.lspatch:core / service）
- Frida-Gadget（运行时注入，锁定版本 16.5.9）
- Gson / Coroutines / RecyclerView

## 注意事项（编译前必读）
1. `ShizukuManager.execShell` 是**编译骨架**：需按 Shizuku 官方 UserService
   （IUserService AIDL）补全 shell 执行实现。
2. `LspatchHelper.patchWithGadget` 是**编译骨架**：需按 LSPatch core 库
   实际 API 补全注入与重签名调用。
3. 图标为占位（mipmap-anydpi-v26/README.txt），编译前需放入真实 ic_launcher。
4. 与后端配套：后端 `backend/watcher.py` 需由 Operit 定时/常驻运行
   （`python3 -m backend.watcher`），否则任务请求不会被消费。