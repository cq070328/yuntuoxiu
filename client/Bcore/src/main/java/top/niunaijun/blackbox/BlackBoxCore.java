package top.niunaijun.blackbox;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.Process;

import top.niunaijun.blackbox.app.configuration.ClientConfiguration;
import top.niunaijun.blackbox.fake.delegate.ContentProviderDelegate;
import top.niunaijun.blackbox.fake.frameworks.BDumpManager;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.core.system.DaemonService;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.ShellUtils;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.BundleCompat;
import top.niunaijun.blackbox.utils.provider.ProviderCall;
import top.niunaijun.blackbox.fake.frameworks.BActivityManager;
import top.niunaijun.blackbox.fake.frameworks.BPackageManager;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import me.weishu.reflection.Reflection;
import reflection.android.app.ActivityThread;
import top.niunaijun.blackbox.fake.frameworks.BStorageManager;
import top.niunaijun.blackbox.core.system.ServiceManager;

/**
 * Created by Milk on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@SuppressLint("StaticFieldLeak")
public class BlackBoxCore extends ClientConfiguration {
    public static final String TAG = "BlackBoxCore";
    public static final int USER_ID = 0;

    private static final BlackBoxCore sBlackBoxCore = new BlackBoxCore();
    private static Context sContext;
    private ProcessType mProcessType;
    private final Map<String, IBinder> mServices = new HashMap<>();
    private ClientConfiguration mClientConfiguration;
    private AppLifecycleCallback mAppLifecycleCallback = AppLifecycleCallback.EMPTY;

    public static BlackBoxCore get() {
        return sBlackBoxCore;
    }

    public static PackageManager getPackageManager() {
        return sContext.getPackageManager();
    }

    public static String getHostPkg() {
        return get().getHostPackageName();
    }

    public static Context getContext() {
        return sContext;
    }

    public void doAttachBaseContext(Context context, ClientConfiguration clientConfiguration) {
        if (clientConfiguration == null) {
            throw new IllegalArgumentException("ClientConfiguration is null!");
        }
        Reflection.unseal(context);
        sContext = context;
        mClientConfiguration = clientConfiguration;
        mClientConfiguration.init();
        String processName = getProcessName(getContext());
        if (processName.equals(BlackBoxCore.getHostPkg())) {
            mProcessType = ProcessType.Main;
            startLogcat();
        } else if (processName.endsWith(getContext().getString(R.string.black_box_service_name))) {
            mProcessType = ProcessType.Server;
        } else {
            mProcessType = ProcessType.BAppClient;
        }
        if (BlackBoxCore.get().isVirtualProcess()) {
            if (processName.endsWith("p0")) {
//                android.os.Debug.waitForDebugger();
            }
//            android.os.Debug.waitForDebugger();
        }
        if (isServerProcess()) {
//            Intent intent = new Intent();
//            intent.setClass(getContext(), DaemonService.class);
//            if (BuildCompat.isOreo()) {
//                getContext().startForegroundService(intent);
//            } else {
//                getContext().startService(intent);
//            }
        }
        HookManager.get().init();
    }

    public void doCreate() {
        if (isVirtualProcess()) {
            ContentProviderDelegate.init();
        }
        if (!isServerProcess()) {
            initService();
        }
    }

    private void initService() {
        get().getService(ServiceManager.ACTIVITY_MANAGER);
        get().getService(ServiceManager.PACKAGE_MANAGER);
        get().getService(ServiceManager.STORAGE_MANAGER);
        get().getService(ServiceManager.DUMP_MANAGER);
    }

    public static Object mainThread() {
        return ActivityThread.currentActivityThread.call();
    }

    public void startActivity(Intent intent, int userId) {
        getBActivityManager().startActivity(intent, userId);
    }

    public static BPackageManager getBPackageManager() {
        return BPackageManager.get();
    }

    public static BActivityManager getBActivityManager() {
        return BActivityManager.get();
    }

    public static BStorageManager getBStorageManager() {
        return BStorageManager.get();
    }

    public static BDumpManager getBDumpManager() {
        return BDumpManager.get();
    }

    public boolean launchApk(String packageName) {
        Intent launchIntentForPackage = getBPackageManager().getLaunchIntentForPackage(packageName, USER_ID);
        if (launchIntentForPackage == null) {
            bbxLog("launchApk: getLaunchIntentForPackage 返回 null（找不到启动 Activity）");
            return false;
        }
        bbxLog("launchApk: 启动意图 = " + launchIntentForPackage);
        try {
            startActivity(launchIntentForPackage, USER_ID);
            bbxLog("launchApk: startActivity 已发出");
        } catch (Throwable t) {
            bbxLog("launchApk: startActivity 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return true;
    }

    public boolean isInstalled(String packageName) {
        return getBPackageManager().isInstalled(packageName, USER_ID);
    }

    public void uninstallPackage(String packageName) {
        getBPackageManager().uninstallPackageAsUser(packageName, USER_ID);
    }

    public InstallResult installPackage(String packageName) {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(packageName, 0);
            return getBPackageManager().installPackageAsUser(packageInfo.applicationInfo.sourceDir, InstallOption.installBySystem(), USER_ID);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return new InstallResult().installError(e.getMessage());
        }
    }

    public InstallResult installPackage(File apk) {
        try {
            bbxLog("installPackage(File): " + apk.getAbsolutePath()
                    + " exists=" + apk.exists() + " size=" + apk.length());
            InstallResult r = getBPackageManager().installPackageAsUser(
                    apk.getAbsolutePath(), InstallOption.installByStorage(), USER_ID);
            String msg = (r == null ? "null" : ("success=" + r.success + " msg=" + r.msg));
            bbxLog("installPackage(File) 结果: " + msg);
            return r;
        } catch (Throwable t) {
            bbxLog("installPackage(File) 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return new InstallResult().installError(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** ⭐ v2.0：把 BlackBox 日志同时写 logcat + 文件（Bcore 不能依赖 app 的 LogStore） */
    public static void bbxLog(String msg) {
        android.util.Log.i("BlackBoxCore", msg);
        try {
            java.io.File f = new java.io.File(
                    "/storage/emulated/0/MT2/apks/unpackcloud/logs/blackbox.log");
            f.getParentFile().mkdirs();
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + msg + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    public InstallResult installPackage(Uri apk) {
        return getBPackageManager().installPackageAsUser(apk.toString(), InstallOption.installByStorage().makeUriFile(), USER_ID);
    }

    public AppLifecycleCallback getAppLifecycleCallback() {
        return mAppLifecycleCallback;
    }

    public void setAppLifecycleCallback(AppLifecycleCallback appLifecycleCallback) {
        if (appLifecycleCallback == null) {
            throw new IllegalArgumentException("AppLifecycleCallback is null!");
        }
        mAppLifecycleCallback = appLifecycleCallback;
    }

    public IBinder getService(String name) {
        IBinder binder = mServices.get(name);
        if (binder != null && binder.isBinderAlive()) {
            return binder;
        }
        Bundle bundle = new Bundle();
        bundle.putString("_VM_|_server_name_", name);
        Bundle vm = null;
        try {
            vm = ProviderCall.callSafely(ProxyManifest.getBindProvider(), "VM", null, bundle);
        } catch (Throwable t) {
            android.util.Log.e("BlackBoxCore", "getService(" + name + ") callSafely 异常: "
                    + t.getClass().getName() + ": " + t.getMessage(), t);
            throw new RuntimeException("BlackBox 服务调用失败(" + name + "): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
        if (vm == null) {
            android.util.Log.e("BlackBoxCore", "getService(" + name + ") 返回 null "
                    + "(provider=" + ProxyManifest.getBindProvider() + ")");
            throw new RuntimeException("BlackBox 服务未就绪(" + name
                    + ")：Provider(" + ProxyManifest.getBindProvider()
                    + ") 无法拉起，可能是 :black 进程启动失败");
        }
        binder = BundleCompat.getBinder(vm, "_VM_|_server_");
        if (binder == null) {
            throw new RuntimeException("BlackBox 服务未就绪(" + name + ")：binder 为 null");
        }
        mServices.put(name, binder);
        return binder;
    }

    private enum ProcessType {
        Server,
        BAppClient,
        Main,
    }

    public boolean isVirtualProcess() {
        return mProcessType == ProcessType.BAppClient;
    }

    public boolean isMainProcess() {
        return mProcessType == ProcessType.Main;
    }

    public boolean isServerProcess() {
        return mProcessType == ProcessType.Server;
    }

    @Override
    public String getHostPackageName() {
        if (mClientConfiguration == null) {
            // ⭐ v2.0：:black 进程 Provider 先于 Application.onCreate，
            //   ClientConfiguration 可能尚未设置 → 用 sContext 兜底
            Context ctx = sContext;
            if (ctx != null) return ctx.getPackageName();
            return "";
        }
        return mClientConfiguration.getHostPackageName();
    }

    /**
     * ⭐ v2.0：供 :black 服务进程的 SystemCallProvider 使用。
     *   只设置 sContext / mClientConfiguration，并把进程标记为 Server。
     *   ⚠️ 不跑 Reflection.unseal / HookManager.init（避免与 :black 的 startup 冲突）
     */
    public void setContextForServer(Context context, ClientConfiguration clientConfiguration) {
        sContext = context;
        mClientConfiguration = clientConfiguration;
        if (mClientConfiguration != null) {
            mClientConfiguration.init();
        }
        mProcessType = ProcessType.Server;
    }

    /**
     * ⭐ v2.0：生成默认 ClientConfiguration（供 SystemCallProvider 兜底初始化用）。
     */
    public static ClientConfiguration getDefaultClientConfiguration(final Context ctx) {
        return new ClientConfiguration() {
            @Override
            public String getHostPackageName() {
                return ctx.getPackageName();
            }

            @Override
            public String getDexDumpDir() {
                File d = new File(ctx.getCacheDir(), "dump");
                if (!d.exists()) d.mkdirs();
                return d.getAbsolutePath();
            }

            @Override
            public boolean isFixCodeItem() { return false; }
            @Override
            public boolean isEnableHookDump() { return false; } // ⭐ v2.2：A16 上 Dobby 崩溃，关闭
            @Override
            public boolean isAutoCallMethod() { return true; }
            @Override
            public boolean isVerifyDex() { return true; }
        };
    }

    @Override
    public String getDexDumpDir() {
        if (mClientConfiguration == null) {
            Context ctx = sContext;
            if (ctx != null) {
                File d = new File(ctx.getCacheDir(), "dump");
                if (!d.exists()) d.mkdirs();
                return d.getAbsolutePath();
            }
            return "/data/local/tmp/blackbox_dump";
        }
        return mClientConfiguration.getDexDumpDir();
    }

    public String getDumpSubDir() {
        return mClientConfiguration.getDumpSubDir();
    }

    @Override
    public boolean isFixCodeItem() {
        return mClientConfiguration.isFixCodeItem();
    }
    @Override
    public boolean isAutoCallMethod(){
        return mClientConfiguration.isAutoCallMethod();
    }

    @Override
    public boolean isEnableHookDump() {
        return mClientConfiguration.isEnableHookDump();
    }

    @Override
    public boolean isVerifyDex() {
        return mClientConfiguration.isVerifyDex();
    }

    private void startLogcat() {
        // ⭐ v2.0：不再写 /sdcard/Download（污染公共目录/相册）
        //    改为写 App 私有目录（cache 下），避免被媒体扫描
        File logDir = new File(getContext().getCacheDir(), "logcat");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        File file = new File(logDir, getContext().getPackageName() + "_logcat.txt");
        FileUtils.deleteDir(file);
        ShellUtils.execCommand("logcat -c", false);
        ShellUtils.execCommand("logcat >> " + file.getAbsolutePath() + " &", false);
    }

    private static String getProcessName(Context context) {
        int pid = Process.myPid();
        String processName = null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.pid == pid) {
                processName = info.processName;
                break;
            }
        }
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

    public static boolean is64Bit() {
        if (BuildCompat.isM()) {
            return Process.is64Bit();
        } else {
            return Build.CPU_ABI.equals("arm64-v8a");
        }
    }
}
