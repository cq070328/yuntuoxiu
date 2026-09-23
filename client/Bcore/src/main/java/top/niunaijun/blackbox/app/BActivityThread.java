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

            // ⭐⭐⭐ v2.3 关键修复：确保 loadedApkClassLoader 指向【目标 App】。
            //
            //   v2.2 的失败根因（见 blackbox.log）：
            //     `new PathClassLoader(".../virtual/data/app/<pkg>/base.apk", ...)`
            //     → SecurityException: Writable dex file ... is not allowed
            //
            //   Android 7+ 的 DexFile::Open 校验逻辑：
            //     if (access(path, W_OK) == 0) → “Writable dex file is not allowed”
            //   即：只要 APK 文件**可写**，就被拒绝加载。
            //   而沙箱安装的 base.apk 落在应用私有可写目录 → 必然可写 → 必然被拒。
            //
            //   修复：在建 ClassLoader 前，把 base.apk 的**写权限去掉**（chmod a-w）。
            //   文件仍可读 → PathClassLoader 校验通过 → 目标 loader 建立成功。
            //   —— 通用：对所有目标 App 都适用。
            // ⭐⭐⭐ v2.5【关键修复】:p0 进程加载 vm.apk/empty.apk/junit.apk 被拒 → 进程崩溃。
            //
            //   实测日志（:p0 进程）：
            //     E/untuoxiu.app:p0: Attempt to load writable dex file: .../virtual/cache/vm.apk
            //     E/untuoxiu.app:p0: hiddenapi: setHiddenApiExemptions ... denied
            //   随后 :p0 进程**静默消失**（blackbox.log 止于 “PathClassLoader 已建立”）。
            //
            //   根因：BlackBox 的 VM 运行时 dex（vm.apk / empty.apk / junit.apk）位于
            //     virtual/cache/（应用私有可写目录）→ Android 7+ DexFile::Open 拒绝
            //     「可写 dex 文件」→ 加载抛错 → 进程启动即死。
            //
            //   修复：在建任何 loader【之前】，把这些 jar 的写权限去掉（chmod 0400）。
            //   必须在 :p0 进程内执行（跨进程权限不共享，主进程 chmod 无效）。
            try {
                File cacheDir = BEnvironment.getCacheDir();
                String[] jarNames = new String[]{"vm.apk", "empty.apk", "junit.apk", "vm.jar", "empty.jar", "junit.jar"};
                for (String n : jarNames) {
                    File j = new File(cacheDir, n);
                    if (j.isFile()) {
                        makeUnwritable(j);
                    }
                }
                // 兼容旧字段（若它们指向其它路径）
                makeUnwritable(BEnvironment.VM_JAR);
                makeUnwritable(BEnvironment.EMPTY_JAR);
                makeUnwritable(BEnvironment.JUNIT_JAR);
                BlackBoxCore.bbxLog("handleBindApplication: [VM-jar] 已对 vm/empty/junit 去写权限"
                        + " (cacheDir=" + cacheDir.getAbsolutePath() + ")");
            } catch (Throwable t) {
                BlackBoxCore.bbxLog("handleBindApplication: [VM-jar] 去写权限失败: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }

            try {
                File targetApk = BEnvironment.getBaseApkDir(packageName);
                if (targetApk.isFile() && targetApk.length() > 0) {
                    File appLibDir = BEnvironment.getAppLibDir(packageName);
                    appLibDir.mkdirs();   // ★ 先建 lib 目录（否则后面给父目录去写权限后无法再创建）
                    // ★ 去写权限（解决 Writable dex 校验）
                    boolean unwritable = makeUnwritable(targetApk);
                    // ★ 兜底：部分 ROM 还会检查 dex 所在「目录」是否可写 → 目录也去写权限
                    try {
                        File apkParent = targetApk.getParentFile();
                        if (apkParent != null && apkParent.isDirectory()) {
                            android.system.Os.chmod(apkParent.getAbsolutePath(), 0500);
                        }
                    } catch (Throwable ignored) {
                    }
                    ClassLoader target = new dalvik.system.PathClassLoader(
                            targetApk.getAbsolutePath(),
                            appLibDir.getAbsolutePath(),
                            ClassLoader.getSystemClassLoader());
                    // 触发一次真实加载，确保 loader 真的可用（否则延迟到 dump 时才暴露）
                    target.loadClass("android.app.Application");
                    loadedApkClassLoader = target;
                    BlackBoxCore.bbxLog("handleBindApplication: [通用] 目标 PathClassLoader 已建立: "
                            + targetApk.getAbsolutePath() + " size=" + targetApk.length()
                            + " unwritable=" + unwritable + " canWrite=" + targetApk.canWrite());
                } else {
                    BlackBoxCore.bbxLog("handleBindApplication: [通用] 目标 APK 不存在: "
                            + targetApk.getAbsolutePath());
                }
            } catch (Throwable t) {
                // ⭐ v2.3：失败必须显式记录（这是“脱出宿主 dex”的关键断点）
                BlackBoxCore.bbxLog("handleBindApplication: [通用] PathClassLoader 失败: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + " → 后续将无法加载目标壳 Application，可能只 dump 到宿主 dex");
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
                    // ⭐ v2.3：优先用「已成功建立的 目标 PathClassLoader」。
                    //   注意：packageContext.getClassLoader() 在沙箱里往往仍是**宿主** loader，
                    //   用它 Class.forName 会加载到宿主的 Application → 壳永远不会跑。
                    //   因此只在已确认拿到目标 loader 时才继续，避免“假成功”。
                    ClassLoader cl = loadedApkClassLoader;
                    if (cl == null) {
                        BlackBoxCore.bbxLog("handleBindApplication: [方案X] 无可用目标 ClassLoader，"
                                + "跳过手动构造（避免误用宿主 loader 触发假解密）");
                    } else {
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
                    } // end else (cl != null)
                } catch (Throwable e) {
                    BlackBoxCore.bbxLog("handleBindApplication: [方案X] 构造失败: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }

            mInitialApplication = application;
            ActivityThread.mInitialApplication.set(BlackBoxCore.mainThread(), mInitialApplication);

            // ⭐⭐⭐ v2.4【集成 Layout Inspect「真实入口」思路】：运行时解析真实 Application 类名。
            //   背景：脱壳后要把 Manifest 的 application:name 从「壳代理类」替换回「真实入口」，
            //         否则重打包的 APK 起不来。静态 dex 扫描（RealEntryFinder）对抽取壳/VMP 壳
            //         常常失效（真实类被壳动态创建，dex 里看不到）。
            //   Layout Inspect 的强项正是「运行时直接拿到真实 Application」——这里复刻：
            //     1) 壳 Application 已构造/已跑 onCreate（解密已完成）
            //     2) 反射扫描其字段，找「是 Application 实例但类名 != 壳类名」的对象 → 真实入口
            //     3) 写入 dump 目录 entry.txt，供 App 层 RealEntryFinder 作为最高优先级来源
            try {
                String realEntry = resolveRealApplicationName(application);
                if (realEntry != null) {
                    File entryFile = new File(result.dir, "entry.txt");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(entryFile);
                    fos.write(realEntry.getBytes("UTF-8"));
                    fos.flush();
                    fos.close();
                    BlackBoxCore.bbxLog("handleBindApplication: [真实入口] 运行时解析到 " + realEntry
                            + " → 已写入 " + entryFile.getAbsolutePath());
                } else {
                    BlackBoxCore.bbxLog("handleBindApplication: [真实入口] 运行时未解析到"
                            + "（application=" + (application == null ? "null"
                            : application.getClass().getName()) + "）");
                }
            } catch (Throwable t) {
                BlackBoxCore.bbxLog("handleBindApplication: [真实入口] 写 entry.txt 失败: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }

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
            // ⭐⭐⭐ v2.3：dump 前先做「壳解密成功性」校验。
            //   若目标 loader 为空、且不存在任何「非宿主的、clazz 数 > 500 的 dex」，
            //   则说明壳根本没解密（多为反调试/签名校验触发），
            //   此时直接失败回执，避免把宿主 dex 当成成功产物。
            if (classLoader == null) {
                BlackBoxCore.bbxLog("handleDumpDex: ⚠️ 目标 ClassLoader 为空，"
                        + "壳 Application 未构造 → 真实 dex 未解密。"
                        + "仍然执行 dump（可能只捞到宿主/壳 dex），但会标记结果。");
            } else {
                BlackBoxCore.bbxLog("handleDumpDex: 目标 ClassLoader 非空，壳已具备解密条件");
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
                    // ⭐ v2.3：统计「疑似真实目标 dex」数量（非宿主、class 数够大），
                    //   并在产物里写出一个来源清单，供上层判断“是不是只拿到宿主 dex”。
                    int real = 0;
                    int hostLike = 0;
                    StringBuilder names = new StringBuilder();
                    for (File f : dumped) {
                        if (!f.isFile() || !f.getName().endsWith(".dex")) continue;
                        if (names.length() > 0) names.append(",");
                        names.append(f.getName());
                        if (isHostLikeDex(f)) hostLike++;
                        // ⭐ v2.5：阈值 300 → 500，与 DexPostProcessor.minClasses 对齐，
                        //   避免「回执成功但后处理把该 dex 丢了」的不一致。
                        else if (countClasses(f) >= 500) real++;
                    }
                    BlackBoxCore.bbxLog("handleDumpDex: 产出 " + dumped.length + " 个文件，"
                            + "疑似真实目标 dex=" + real + "，疑似宿主 dex=" + hostLike
                            + "，清单=[" + names + "]");
                    if (real == 0) {
                        // 只拿到宿主/壳 dex → 明确失败，避免误导为“脱壳成功”
                        BlackBoxCore.getBDumpManager().noticeMonitor(
                                result.dumpError("only shell/host dex dumped, real dex not decrypted"
                                        + " (hostLike=" + hostLike + ")"));
                    } else {
                        BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpSuccess());
                    }
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
     * ⭐ v2.3：去除 APK 文件的「写权限」，绕过 Android 的 Writable dex 校验。
     *
     * 背景：
     *   Android 7+（libart DexFile::Open）在加载 dex 时校验：
     *     if (access(path, W_OK) == 0) → 抛 SecurityException:
     *         "Writable dex file '...' is not allowed"
     *   沙箱把目标 APK 拷贝到应用私有可写目录（virtual/data/app/&lt;pkg&gt;/base.apk），
     *   该文件对宿主进程天然可写 → PathClassLoader 必然被拒 →
     *   目标 ClassLoader 建立失败 → 壳 Application 构造失败 → 脱出宿主 dex。
     *
     * 修复：在加载前把文件权限改为「只读」（去掉 W 位）。
     *   文件仍可读（R 位保留）→ DexFile 加载校验通过。
     *
     * 实现分三级兜底（任一成功即可）：
     *   ① android.system.Os.chmod（最直接）
     *   ② File.setWritable(false)（Java API）
     *   ③ 反射 access 校验确认（仅日志）
     *
     * @return 是否成功变为不可写
     */
    private static boolean makeUnwritable(File file) {
        if (file == null || !file.isFile()) return false;
        // 已经是不可写 → 直接返回
        if (!file.canWrite()) return true;
        // ① Os.chmod：0600 → 0400（去掉写位，保留读位）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.system.Os.chmod(file.getAbsolutePath(), 0400);
            }
        } catch (Throwable ignored) {
        }
        if (!file.canWrite()) return true;
        // ② Java API
        try {
            file.setWritable(false, false);
        } catch (Throwable ignored) {
        }
        if (!file.canWrite()) return true;
        // ③ FileUtils.chmod 兜底
        try {
            FileUtils.chmod(file.getAbsolutePath(), FileUtils.FileMode.MODE_IRUSR);
        } catch (Throwable ignored) {
        }
        return !file.canWrite();
    }

    /**
     * ⭐⭐⭐ v2.4【集成 Layout Inspect「真实入口」思路】：运行时解析真实 Application 类名。
     *
     * 问题：脱壳后重打包时，Manifest 的 `application:name` 仍是壳代理类
     *   （如 com.tencent.StubShell.TxAppEntry / com.stub.StubApp），
     *   直接替换会导致壳再次运行；必须替换回「真实入口」，重打包后 App 才能正常启动。
     *
     * Layout Inspect 的做法是「运行时直接拿真实 Application」——这里复刻：
     *   壳（如腾讯御安全 SMZ）在 attachBaseContext / onCreate 中会创建**真实的 Application 实例**，
     *   并把它作为自己的字段（mRealApplication / mBase / realApplication 等）或
     *   superclass 之外的引用持有。我们反射遍历壳 Application 的字段，找：
     *     · 类型是 android.app.Application 的对象
     *     · 其类名 **不是**壳类名、不是 android.app.*、不是宿主类
     *   → 即为真实入口。
     *
     * 兜底：若壳 Application 本身就不是「壳类名」（说明 Manifest 已直接指向真实入口），
     *   或系统默认 Application，也返回其真实类名。
     *
     * @return 真实 Application 全限定类名；无法确定返回 null
     */
    private static String resolveRealApplicationName(Application app) {
        if (app == null) return null;
        String shellName = app.getClass().getName();

        // ① 若当前 Application 明显不是壳代理类 → 它本身可能就是真实入口
        //    （例如沙箱直接构造出真实 Application 的场景）
        if (!isStubApplicationName(shellName) && !shellName.equals("android.app.Application")) {
            // 再确认它不是宿主（云脱修/黑盒）自己的 Application
            if (!shellName.startsWith("com.yuntuoxiu.") && !shellName.startsWith("top.niunaijun.")) {
                BlackBoxCore.bbxLog("resolveRealApplicationName: 当前 Application 非壳类，直接采用: " + shellName);
                return shellName;
            }
        }

        // ② 反射遍历字段，找真实 Application 实例（壳常持有它）
        String best = scanForRealAppInObject(app, 0);
        if (best != null) return best;

        // ③ 遍历到找不到：若壳类名本身不是壳特征（也许已加密过），仍返回它自己的名字兜底
        if (!isStubApplicationName(shellName)) {
            return shellName;
        }
        return null;
    }

    /** 判断类名是否「壳代理 Application」特征 */
    private static boolean isStubApplicationName(String name) {
        if (name == null) return false;
        return name.startsWith("com.stub.") || name.startsWith("com.qihoo.")
                || name.startsWith("com.secneo.") || name.startsWith("com.tencent.StubShell")
                || name.startsWith("com.tencent.bugly") || name.contains("StubApp")
                || name.contains("ApplicationWrapper") || name.contains("TxAppEntry")
                || name.contains("ProxyApplication") || name.contains("wrapper.proxyapplication")
                || name.contains("StubApplication");
    }

    /** 在对象（含其父类）字段中递归查找真实 Application 实例（限深 3 层，防循环） */
    private static String scanForRealAppInObject(Object obj, int depth) {
        if (obj == null || depth > 3) return null;
        Class<?> clazz = obj.getClass();
        // 只看自己的类（含静态字段）
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                c = c.getSuperclass();
                continue;
            }
            for (Field f : fields) {
                try {
                    Class<?> ft = f.getType();
                    if (!Application.class.isAssignableFrom(ft)) continue;
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v == null) continue;
                    String vn = v.getClass().getName();
                    if (vn.equals(clazz.getName())) continue;              // 自引用
                    if (vn.equals("android.app.Application")) continue;      // 系统默认
                    if (vn.startsWith("com.yuntuoxiu.") || vn.startsWith("top.niunaijun.")) continue; // 宿主
                    if (isStubApplicationName(vn)) {
                        // 字段值是「另一层壳」→ 继续往里找
                        String deeper = scanForRealAppInObject(v, depth + 1);
                        if (deeper != null) return deeper;
                        continue;
                    }
                    BlackBoxCore.bbxLog("scanForRealApp: 命中真实 Application 字段 "
                            + c.getName() + "#" + f.getName() + " = " + vn);
                    return vn;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * ⭐ v2.3：判断某 dex 文件是否「疑似宿主（云脱修自己）的 dex」。
     *
     * 判定依据（任一命中即判为宿主）：
     *   ① 命中宿主专属类描述符/包名（`Lcom/yuntuoxiu/app`、`Ltop/niunaijun/blackbox` 等）
     *   ② 命中宿主 Application 类名
     *
     * 说明：宿主与目标处于**同一 :p0 进程**，内存扫描必然扫到宿主 dex，
     *   这里用于在回执阶段把宿主 dex 与目标真实 dex 区分开。
     */
    private static boolean isHostLikeDex(File dex) {
        if (dex == null || !dex.isFile()) return false;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(dex);
            // 只扫前 8MB（特征串集中在字符串池/头部）
            int scanLen = (int) Math.min(dex.length(), 8 * 1024 * 1024);
            byte[] buf = new byte[scanLen];
            int read = 0;
            while (read < scanLen) {
                int n = in.read(buf, read, scanLen - read);
                if (n <= 0) break;
                read += n;
            }
            String text = new String(buf, 0, read, "ISO-8859-1");
            String[] markers = new String[]{
                    "Lcom/yuntuoxiu/app",
                    "com/yuntuoxiu/app",
                    "Ltop/niunaijun/blackbox",
                    "top/niunaijun/blackbox",
                    "Lcom/ai/assistance/operit",
                    "com/ai/assistance/operit",
                    // ⭐ v2.5：宿主专属裸词
                    "yuntuoxiu",
                    "niunaijun",
                    "Lcom/stub/StubApp",
                    "Lcom/tencent/StubShell",
                    "Lcom/secneo/apkwrapper",
                    "Lcom/qihoo/util",
            };
            int hits = 0;
            for (String m : markers) {
                int idx = text.indexOf(m);
                if (idx >= 0) hits++;
            }
            // 宿主特征命中 >= 2 个，或命中宿主包名 → 判为宿主
            return hits >= 2
                    || text.contains("Lcom/yuntuoxiu/app")
                    || text.contains("Ltop/niunaijun/blackbox");
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * ⭐ v2.3：读取 dex 头部 0x60 处的 class_defs_size（class 数）。
     */
    private static int countClasses(File dex) {
        if (dex == null || !dex.isFile() || dex.length() < 0x64) return 0;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(dex);
            byte[] h = new byte[0x64];
            int read = 0;
            while (read < h.length) {
                int n = in.read(h, read, h.length - read);
                if (n <= 0) break;
                read += n;
            }
            if (read < 0x64) return 0;
            return (h[0x60] & 0xFF)
                    | ((h[0x61] & 0xFF) << 8)
                    | ((h[0x62] & 0xFF) << 16)
                    | ((h[0x63] & 0xFF) << 24);
        } catch (Throwable t) {
            return 0;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
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
