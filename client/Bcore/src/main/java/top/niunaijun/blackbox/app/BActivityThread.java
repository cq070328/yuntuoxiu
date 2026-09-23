package top.niunaijun.blackbox.app;

import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import reflection.android.app.ActivityThread;
import reflection.android.app.ContextImpl;
import reflection.android.app.LoadedApk;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.core.VMCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.entity.dump.DumpResult;
import top.niunaijun.blackbox.utils.FileUtils;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 *
 * 此条吐槽来自紫檀:bug多着嘞（bushi
 */
public class BActivityThread extends IBActivityThread.Stub {
    public static final String TAG = "BActivityThread";

    private static BActivityThread sBActivityThread;
    private AppBindData mBoundApplication;
    private Application mInitialApplication;
    private AppConfig mAppConfig;
    private final List<ProviderInfo> mProviders = new ArrayList<>();

    public static BActivityThread currentActivityThread() {
        if (sBActivityThread == null) {
            synchronized (BActivityThread.class) {
                if (sBActivityThread == null) {
                    sBActivityThread = new BActivityThread();
                }
            }
        }
        return sBActivityThread;
    }

    public static synchronized AppConfig getAppConfig() {
        return currentActivityThread().mAppConfig;
    }

    public static List<ProviderInfo> getProviders() {
        return currentActivityThread().mProviders;
    }

    public static String getAppProcessName() {
        if (getAppConfig() != null) {
            return getAppConfig().processName;
        } else if (currentActivityThread().mBoundApplication != null) {
            return currentActivityThread().mBoundApplication.processName;
        } else {
            return null;
        }
    }

    public static String getAppPackageName() {
        if (getAppConfig() != null) {
            return getAppConfig().packageName;
        } else if (currentActivityThread().mInitialApplication != null) {
            return currentActivityThread().mInitialApplication.getPackageName();
        } else {
            return null;
        }
    }

    public static Application getApplication() {
        return currentActivityThread().mInitialApplication;
    }

    public static int getAppPid() {
        return getAppConfig() == null ? -1 : getAppConfig().bpid;
    }

    public static int getAppUid() {
        return getAppConfig() == null ? 10000 : getAppConfig().buid;
    }

    public static int getBaseAppUid() {
        return getAppConfig() == null ? 10000 : getAppConfig().baseBUid;
    }

    public static int getUid() {
        return getAppConfig() == null ? -1 : getAppConfig().uid;
    }

    public static int getUserId() {
        return getAppConfig() == null ? 0 : getAppConfig().userId;
    }

    public void initProcess(AppConfig appConfig) {
        if (this.mAppConfig != null) {
            throw new RuntimeException("reject init process: " + appConfig.processName + ", this process is : " + this.mAppConfig.processName);
        }
        this.mAppConfig = appConfig;
    }

    public boolean isInit() {
        return mBoundApplication != null;
    }

    public void bindApplication(final String packageName, final String processName) {
        if (mAppConfig == null) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final ConditionVariable conditionVariable = new ConditionVariable();
            new Handler(Looper.getMainLooper()).post(() -> {
                handleBindApplication(packageName, processName);
                conditionVariable.open();
            });
            conditionVariable.block();
        } else {
            handleBindApplication(packageName, processName);
        }
    }

    public static ClassLoader loadedApkClassLoader;
    public static Context dumpTargetContext;

    public static volatile boolean sDumping = false;

    private synchronized void handleBindApplication(String packageName, String processName) {
        BlackBoxCore.bbxLog("handleBindApplication: 进入 pkg=" + packageName
                + " proc=" + processName + " userId=" + BActivityThread.getUserId());
        DumpResult result = new DumpResult();
        result.packageName = packageName;
        File dirFile = new File(BlackBoxCore.get().getDexDumpDir(), packageName);
        String subDir = BlackBoxCore.get().getDumpSubDir();
        if (subDir != null && !subDir.isEmpty()) {
            dirFile = new File(dirFile, subDir);
        }
        result.dir = dirFile.getAbsolutePath();
        // ⭐ v2.2：主动创建 dump 目录（原代码只在 VMCore 循环内 mkdirs，
        //   cookie 为空时循环不执行 → 目录不存在 → 上层永远数不到 dex）
        try {
            if (!dirFile.exists()) {
                dirFile.mkdirs();
            }
        } catch (Throwable ignored) {
        }
        BlackBoxCore.bbxLog("handleBindApplication: dump 目标目录=" + result.dir
                + " exists=" + dirFile.exists() + " canWrite=" + dirFile.canWrite());
        try {
            PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, BActivityThread.getUserId());
            if (packageInfo == null) {
                BlackBoxCore.bbxLog("handleBindApplication: packageInfo == null，直接返回");
                return;
            }
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            if (packageInfo.providers == null) {
                packageInfo.providers = new ProviderInfo[]{};
            }
            mProviders.addAll(Arrays.asList(packageInfo.providers));

            Object boundApplication = ActivityThread.mBoundApplication.get(BlackBoxCore.mainThread());
            Context packageContext = createPackageContext(applicationInfo);

            dumpTargetContext = packageContext;
            Object loadedApk = ContextImpl.mPackageInfo.get(packageContext);

            LoadedApk.mSecurityViolation.set(loadedApk, false);
            // fix applicationInfo
            LoadedApk.mApplicationInfo.set(loadedApk, applicationInfo);

            // clear dump file
            FileUtils.deleteDir(dirFile);

            // init vmCore
            VMCore.init(Build.VERSION.SDK_INT);
            IOCore.get().enableRedirect(packageContext);

            AppBindData bindData = new AppBindData();
            bindData.appInfo = applicationInfo;
            bindData.processName = processName;
            bindData.info = loadedApk;
            bindData.providers = mProviders;

            ActivityThread.AppBindData.instrumentationName.set(boundApplication,
                    new ComponentName(bindData.appInfo.packageName, Instrumentation.class.getName()));
            ActivityThread.AppBindData.appInfo.set(boundApplication, bindData.appInfo);
            ActivityThread.AppBindData.info.set(boundApplication, bindData.info);
            ActivityThread.AppBindData.processName.set(boundApplication, bindData.processName);
            ActivityThread.AppBindData.providers.set(boundApplication, bindData.providers);

            mBoundApplication = bindData;
            //创建要脱壳的程序的application
            Application application = null;
            Method newApplication = null;
            Object mInstrumentation = null;
            try{
                Field mActivityThreadField = loadedApk.getClass().getDeclaredField("mActivityThread");
                mActivityThreadField.setAccessible(true);
                Object mActivityThread = mActivityThreadField.get(loadedApk);
                Field mInstrumentationField = mActivityThread.getClass().getDeclaredField("mInstrumentation");
                mInstrumentationField.setAccessible(true);
                mInstrumentation = mInstrumentationField.get(mActivityThread);

                newApplication = mInstrumentation.getClass().getDeclaredMethod("newApplication",ClassLoader.class,String.class,Context.class);
                Method getClassLoader = loadedApk.getClass().getDeclaredMethod("getClassLoader");
                loadedApkClassLoader = (ClassLoader) getClassLoader.invoke(loadedApk);
            }catch (Exception e){
                Log.e(TAG, "handleBindApplication: ", e);
            }

            // ⭐⭐⭐ v2.2 通用修复：确保 loadedApkClassLoader 指向【目标 App】。
            //
            //   实测：`loadedApk.getClassLoader()` 在沙箱里拿到的是**宿主**的
            //   PathClassLoader（DexPathList 指向 com.yuntuoxiu.app/base.apk），
            //   导致：① dump 出宿主 dex  ② Class.forName 找不到目标的壳 Application。
            //
            //   修复：用 PathClassLoader（**不校验可写目录**，DexClassLoader 会被
            //   SecurityException 拒绝）加载沙箱安装的目标 APK。
            //   —— 通用：对所有目标 App 都适用。
            try {
                File targetApk = BEnvironment.getBaseApkDir(packageName);
                if (targetApk.isFile() && targetApk.length() > 0) {
                    File appLibDir = BEnvironment.getAppLibDir(packageName);
                    appLibDir.mkdirs();
                    ClassLoader target = new dalvik.system.PathClassLoader(
                            targetApk.getAbsolutePath(),
                            appLibDir.getAbsolutePath(),
                            ClassLoader.getSystemClassLoader());
                    loadedApkClassLoader = target;
                    BlackBoxCore.bbxLog("handleBindApplication: [通用] 目标 PathClassLoader 已建立: "
                            + targetApk.getAbsolutePath() + " size=" + targetApk.length());
                }
            } catch (Throwable t) {
                BlackBoxCore.bbxLog("handleBindApplication: [通用] PathClassLoader 失败: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }

            BlackBoxCore.get().getAppLifecycleCallback().beforeCreateApplication(packageName, processName, packageContext, loadedApk);
            if (Build.VERSION.SDK_INT>=34) {
                if (!BEnvironment.EMPTY_JAR.setWritable(false)){
                    FileUtils.chmod(BEnvironment.EMPTY_JAR.getAbsolutePath(), FileUtils.FileMode.MODE_IRUSR);
                }
                if (!BEnvironment.JUNIT_JAR.setWritable(false)){
                    FileUtils.chmod(BEnvironment.JUNIT_JAR.getAbsolutePath(),FileUtils.FileMode.MODE_IRUSR);
                }
                if (!BEnvironment.VM_JAR.setWritable(false)){
                    FileUtils.chmod(BEnvironment.VM_JAR.getAbsolutePath(),FileUtils.FileMode.MODE_IRUSR);
                }
            }

            try {
                //判断是否为arm加固程序，如果是的话进行静态解密（dump无法成功脱壳）
                loadedApkClassLoader.loadClass("arm.StubApp");
                boolean decodeResult = DecodeArmDex.dumpArmStub(loadedApk,result,packageName);
                //解密结束后清除此任务，从blackdex中卸载目标程序
                if (decodeResult){
                    mAppConfig = null;
                    BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpSuccess());
                    BlackBoxCore.get().uninstallPackage(packageName);
                    return;
                }
            }catch (Exception ignored){}

            //尝试构造application
            try {
                //application = (Application) loadedApk.getClass().getDeclaredMethod("makeApplication",boolean.class,Class.forName("android.app.Instrumentation")).invoke(loadedApk,false,null);
                application = LoadedApk.makeApplication.call(loadedApk, false, null);
            } catch (Throwable e) {
                Log.e(TAG, "第一次构造application失败 : ", e);
            }

            //上面的application构造方法未能成功构造application，尝试通过ActivityThread的Instrumentation构造application
            if (application==null){
                try{
                    application = (Application) newApplication.invoke(mInstrumentation,loadedApkClassLoader,packageInfo.applicationInfo.name,packageContext);
                }catch (Exception e){
                    //这里application未能成功构造，脱壳初始工作已经失败了，理论上已经寄了
                    Log.e(TAG, "application最终构建失败: ", e);
                }
            }

            // ⭐⭐⭐ v2.2 方案X 关键修复：手动构造目标 Application 并执行 onCreate！
            //
            //   根因：A16 上 `LoadedApk.makeApplication` / `newApplication` 均失败 →
            //        application=null → 壳的 Application.onCreate **从未执行** →
            //        腾讯御安全的 SMZ 永不解密 → 内存里没有真实 dex。
            //
            //   修复：用「目标 ClassLoader + 目标类名」直接反射构造 Application，
            //        注入 BaseContext，**显式调用 attachBaseContext + onCreate** ——
            //        这会触发壳的初始化流程（SMZ 解密就在 onCreate 里）。
            if (application == null) {
                try {
                    String appClassName = packageInfo.applicationInfo.className;
                    if (appClassName == null || appClassName.isEmpty()) {
                        // 未声明 Application → 用默认 android.app.Application
                        appClassName = "android.app.Application";
                    }
                    BlackBoxCore.bbxLog("handleBindApplication: [方案X] 手动构造 Application: "
                            + appClassName);
                    ClassLoader cl = (loadedApkClassLoader != null)
                            ? loadedApkClassLoader
                            : packageContext.getClassLoader();
                    Class<?> appClazz = Class.forName(appClassName, true, cl);
                    Object appObj = appClazz.newInstance();
                    if (appObj instanceof Application) {
                        Application app = (Application) appObj;
                        // 注入 Context（内部会调 attachBaseContext）
                        try {
                            Method attach = Application.class.getDeclaredMethod(
                                    "attach", Context.class);
                            attach.setAccessible(true);
                            attach.invoke(app, packageContext);
                        } catch (Throwable t1) {
                            BlackBoxCore.bbxLog("handleBindApplication: [方案X] attach 失败: "
                                    + t1.getMessage());
                        }
                        // ⭐ 关键：显式调用 onCreate → 触发壳（SMZ）解密
                        try {
                            BlackBoxCore.bbxLog("handleBindApplication: [方案X] 调用 onCreate（触发壳解密）...");
                            app.onCreate();
                            BlackBoxCore.bbxLog("handleBindApplication: [方案X] onCreate 完成");
                        } catch (Throwable t2) {
                            BlackBoxCore.bbxLog("handleBindApplication: [方案X] onCreate 异常(可忽略): "
                                    + t2.getClass().getSimpleName() + ": " + t2.getMessage());
                        }
                        application = app;
                    }
                } catch (Throwable e) {
                    BlackBoxCore.bbxLog("handleBindApplication: [方案X] 构造失败: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }

            mInitialApplication = application;
            ActivityThread.mInitialApplication.set(BlackBoxCore.mainThread(), mInitialApplication);
            BlackBoxCore.bbxLog("handleBindApplication: application 构造完成 application=" +
                    (application == null ? "null" : application.getClass().getName())
                    + " pkg==proc? " + Objects.equals(packageName, processName));
            // ⭐ v2.2 修复：原判定 `Objects.equals(packageName, processName)` 过于脆弱。
            //   当目标 App 主 Activity 声明在 :sub 子进程（processName = "pkg:sub"）时，
            //   packageName != processName → 永久跳过 dump → “未产出 DEX”。
            //   正确语义：只要该进程属于目标包，就应执行 dump。
            //   （packageName 为包名；processName 可能是 "pkg" 或 "pkg:xxx"）
            if (isTargetProcess(packageName, processName)) {
                // ⭐⭐⭐ v2.2 通用修复：传【目标 PathClassLoader】给 handleDumpDex。
                //   · 若壳把解密 dex 注册进 loader → cookie dump 能直接拿到
                //   · 否则 VMCore 内会回退到「从目标 APK 提取」+「多轮内存扫描」
                //   （三者叠加，通用覆盖各类壳）
                ClassLoader loader = loadedApkClassLoader;
                BlackBoxCore.bbxLog("handleBindApplication: 进入 handleDumpDex, loader="
                        + (loader == null ? "null" : "目标 PathClassLoader"));
                sDumping = true;
                handleDumpDex(packageName, result, loader);
            } else {
                // ⚠️ 该进程与本包无关 → 不执行 dump
                BlackBoxCore.bbxLog("handleBindApplication: 非目标进程，跳过 dump (pkg="
                        + packageName + " proc=" + processName + ")");
            }
        } catch (Throwable e) {
            BlackBoxCore.bbxLog("handleBindApplication: 异常 " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            Log.e(TAG, "handleBindApplication: ", e);
            mAppConfig = null;
            BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpError(e.getMessage()));
            BlackBoxCore.get().uninstallPackage(packageName);
        }
    }

    private void handleDumpDex(String packageName, DumpResult result, ClassLoader classLoader) {
        new Thread(() -> {
            // ⭐⭐⭐ v2.2 关键修复：等待目标 App「壳初始化完成」再 dump！
            //
            //   时序分析（实测）：
            //     · handleBindApplication 发生在 :p0 绑定 Application 时
            //     · 此刻目标 App 的 Application.onCreate **还没执行**
            //     · 腾讯御安全的 SMZ 解密**发生在壳 Application.onCreate 里**
            //     · 因此过早 dump → 只能拿到宿主 dex（真实 dex 还没解密）
            //
            //   修复：先等 3 秒（让 launchApk 的 Activity 真正启动、壳跑 onCreate 解密），
            //        再多轮扫描。若目标 App 未启动，也至少给壳充分的初始化时间。
            try {
                BlackBoxCore.bbxLog("handleDumpDex: 等待目标 App 壳初始化（6s）...");
                Thread.sleep(6000);
            } catch (InterruptedException ie) {
                Log.e(TAG, "handleDumpDex: ", ie);
            }
            try {
                VMCore.cookieDumpDex(classLoader, packageName);
            } finally {
                BlackBoxCore.bbxLog("handleDumpDex: cookieDumpDex 返回, 检查 " + result.dir);
                sDumping = false;
                mAppConfig = null;
                File dir = new File(result.dir);
                // ⭐ v2.2 修复：dir.listFiles() 可能为 null（目录不存在/不可读）
                //   原代码 dir.listFiles().length 会 NPE → 整个 finally 崩 →
                //   uninstallPackage 不执行、monitor 不回执 → 上层永远“等待中”。
                File[] dumped = dir.isDirectory() ? dir.listFiles() : null;
                if (dumped == null || dumped.length == 0) {
                    BlackBoxCore.bbxLog("handleDumpDex: 无产出 dex 于 " + result.dir
                            + " (exists=" + dir.exists() + ")");
                    BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpError("not found dex file"));
                } else {
                    BlackBoxCore.bbxLog("handleDumpDex: 产出 " + dumped.length + " 个文件");
                    BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpSuccess());
                }
                BlackBoxCore.get().uninstallPackage(packageName);
                Process.killProcess(Process.myPid());
            }
        }).start();
    }

    private Context createPackageContext(ApplicationInfo info) {
        try {
            return BlackBoxCore.getContext().createPackageContext(info.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception e) {
            Log.e(TAG, "createPackageContext: ", e);
        }
        return null;
    }

    /**
     * ⭐ v2.2：判断当前进程是否属于目标包（用于决定是否执行 dump）。
     *
     * 规则（按优先级）：
     *   1) processName 与 packageName 完全相等 → 目标主进程 ✅
     *   2) processName 以 "packageName:" 开头 → 目标子进程（:xxx）✅
     *   3) processName 以 "packageName." 开头 → 某些壳的特殊进程命名 ✅
     *   4) 其余 → 非目标进程 ❌
     *
     * 这样既保留了「避免在无关进程 dump」的安全性，
     * 又修复了「主 Activity 声明在 :sub 进程 → 永不 dump」的缺陷。
     */
    private static boolean isTargetProcess(String packageName, String processName) {
        if (packageName == null || processName == null) {
            return false;
        }
        if (packageName.equals(processName)) {
            return true;
        }
        return processName.startsWith(packageName + ":")
                || processName.startsWith(packageName + ".");
    }

    @Override
    public IBinder getActivityThread() {
        return ActivityThread.getApplicationThread.call(BlackBoxCore.mainThread());
    }

    @Override
    public void bindApplication() {
        if (!isInit()) {
            bindApplication(getAppPackageName(), getAppProcessName());
        }
    }

    public static class AppBindData {
        String processName;
        ApplicationInfo appInfo;
        List<ProviderInfo> providers;
        Object info;
    }
}
