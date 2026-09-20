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
        DumpResult result = new DumpResult();
        result.packageName = packageName;
        File dirFile = new File(BlackBoxCore.get().getDexDumpDir(), packageName);
        String subDir = BlackBoxCore.get().getDumpSubDir();
        if (subDir != null && !subDir.isEmpty()) {
            dirFile = new File(dirFile, subDir);
        }
        result.dir = dirFile.getAbsolutePath();
        try {
            PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, BActivityThread.getUserId());
            if (packageInfo == null)
                return;
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

            mInitialApplication = application;
            ActivityThread.mInitialApplication.set(BlackBoxCore.mainThread(), mInitialApplication);
            if (Objects.equals(packageName, processName)) {
                ClassLoader loader;
                if (application == null) {
                    loader = LoadedApk.getClassloader.call(loadedApk);
                } else {
                    //走到这里已经寄了，牛奶哥说可以挣扎一下，（`_`）
                    loader = application.getClassLoader();
                }
                sDumping = true;
                handleDumpDex(packageName, result, loader);
            }
        } catch (Throwable e) {
            Log.e(TAG, "handleBindApplication: ", e);
            mAppConfig = null;
            BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpError(e.getMessage()));
            BlackBoxCore.get().uninstallPackage(packageName);
        }
    }

    private void handleDumpDex(String packageName, DumpResult result, ClassLoader classLoader) {
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Log.e(TAG, "handleDumpDex: ", ie);
            }
            try {
                VMCore.cookieDumpDex(classLoader, packageName);
            } finally {
                sDumping = false;
                mAppConfig = null;
                File dir = new File(result.dir);
                if (!dir.exists() || dir.listFiles().length == 0) {
                    BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpError("not found dex file"));
                } else {
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
