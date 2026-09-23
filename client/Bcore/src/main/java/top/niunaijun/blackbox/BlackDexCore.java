package top.niunaijun.blackbox;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Process;

import java.io.File;
import java.util.List;

import top.niunaijun.blackbox.app.configuration.ClientConfiguration;
import top.niunaijun.blackbox.core.system.dump.IBDumpMonitor;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * Created by Milk on 2021/5/22.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BlackDexCore {
    public static final String TAG = "BlackBoxCore";

    private static final BlackDexCore sBlackDexCore = new BlackDexCore();

    public static BlackDexCore get() {
        return sBlackDexCore;
    }

    public void doAttachBaseContext(Context context, ClientConfiguration clientConfiguration) {
        BlackBoxCore.get().doAttachBaseContext(context, clientConfiguration);
    }

    public void doCreate() {
        BlackBoxCore.get().doCreate();
        // uninstall all pckage
        if (BlackBoxCore.get().isMainProcess()) {
            List<PackageInfo> installedPackages =
                    BlackBoxCore.getBPackageManager().getInstalledPackages(0, BlackBoxCore.USER_ID);
            for (PackageInfo installedPackage : installedPackages) {
                BlackBoxCore.get().uninstallPackage(installedPackage.packageName);
            }
        }
    }

    public InstallResult dumpDex(String packageName) {
        InstallResult installResult = BlackBoxCore.get().installPackage(packageName);
        if (installResult.success) {
            boolean b = BlackBoxCore.get().launchApk(packageName);
            if (!b) {
                BlackBoxCore.get().uninstallPackage(installResult.packageName);
                return null;
            }
            return installResult;
        } else {
            return null;
        }
    }

    public InstallResult dumpDex(File file) {
        BlackBoxCore.bbxLog("dumpDex(File): 开始 installPackage " + file.getAbsolutePath());
        InstallResult installResult = BlackBoxCore.get().installPackage(file);
        if (installResult == null) {
            BlackBoxCore.bbxLog("dumpDex(File): installPackage 返回 null（服务未就绪）");
            return null;
        }
        BlackBoxCore.bbxLog("dumpDex(File): install success=" + installResult.success
                + " msg=" + installResult.msg + " pkg=" + installResult.packageName);
        if (installResult.success) {
            BlackBoxCore.bbxLog("dumpDex(File): 调用 launchApk " + installResult.packageName);
            boolean b = BlackBoxCore.get().launchApk(installResult.packageName);
            BlackBoxCore.bbxLog("dumpDex(File): launchApk 返回 " + b);
            if (!b) {
                BlackBoxCore.get().uninstallPackage(installResult.packageName);
                return null;
            }
            // ⭐ v2.2 修复：launchApk 返回 true 仅代表“启动意图已发出”，
            //   并不代表 :p 目标进程真的起来（本机日志中多次出现 bpid=0 后无下文）。
            //   这里轮询确认 :p 进程是否进入运行态；若长时间未起，记录明确错误，
            //   避免上层干等 180s 后只得到“未产出 DEX”的模糊结论。
            boolean alive = waitForTargetProcess(installResult.packageName, 15_000);
            BlackBoxCore.bbxLog("dumpDex(File): 目标进程拉起确认 alive=" + alive
                    + " pkg=" + installResult.packageName);
            if (!alive) {
                BlackBoxCore.bbxLog("dumpDex(File): ⚠️ 目标 :p 进程未拉起（可能是 :black 服务未就绪 / "
                        + "ProviderCall 失败 / 目标进程崩溃），仍返回 installResult 供上层等待。");
            }
            return installResult;
        } else {
            return null;
        }
    }

    /**
     * ⭐ v2.2：轮询等待代办的 :p 目标进程进入运行态。
     *   命中条件（满足其一）：
     *     · isRunning() 返回 true（存在 hostPkg:p* 进程）
     *     · 运行进程列表里存在 processName 以 hostPkg+":p" 开头的进程
     */
    private boolean waitForTargetProcess(String packageName, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                return false;
            }
        }
        return false;
    }

    public InstallResult dumpDex(Uri file) {
        InstallResult installResult = BlackBoxCore.get().installPackage(file);
        if (installResult.success) {
            boolean b = BlackBoxCore.get().launchApk(installResult.packageName);
            if (!b) {
                BlackBoxCore.get().uninstallPackage(installResult.packageName);
                return null;
            }
            return installResult;
        } else {
            return null;
        }
    }

    public void registerDumpMonitor(IBDumpMonitor monitor) {
        BlackBoxCore.getBDumpManager().registerMonitor(monitor.asBinder());
    }

    public void unregisterDumpMonitor(IBDumpMonitor monitor) {
        BlackBoxCore.getBDumpManager().unregisterMonitor(monitor.asBinder());
    }

    public boolean isRunning() {
        ActivityManager am = (ActivityManager) BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        String prefix = BlackBoxCore.getHostPkg() + ":p";
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.processName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean isExistDexFile(String packageName) {
        File[] files = new File(BlackBoxCore.get().getDexDumpDir(), packageName).listFiles();
        return files != null && files.length > 0;
    }
}
