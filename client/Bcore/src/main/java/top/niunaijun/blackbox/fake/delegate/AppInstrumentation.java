package top.niunaijun.blackbox.fake.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import reflection.android.app.ActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.VMCore;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.compat.ContextCompat;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {

    private static final String TAG = AppInstrumentation.class.getSimpleName();

    private static AppInstrumentation sAppInstrumentation;

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) {
            synchronized (AppInstrumentation.class) {
                if (sAppInstrumentation == null) {
                    sAppInstrumentation = new AppInstrumentation();
                }
            }
        }
        return sAppInstrumentation;
    }

    public AppInstrumentation() {
    }

    @Override
    public void injectHook() {
        try {
            Instrumentation mInstrumentation = getCurrInstrumentation();
            if (mInstrumentation == this || checkInstrumentation(mInstrumentation))
                return;
            mBaseInstrumentation = (Instrumentation) mInstrumentation;
            ActivityThread.mInstrumentation.set(BlackBoxCore.mainThread(), this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Instrumentation getCurrInstrumentation() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return ActivityThread.mInstrumentation.get(currentActivityThread);
    }

    @Override
    public boolean isBadEnv() {
        return !checkInstrumentation(getCurrInstrumentation());
    }

    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation instanceof AppInstrumentation) {
            return true;
        }
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) {
            return false;
        }
        do {
            assert clazz != null;
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (Instrumentation.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        Object obj = field.get(instrumentation);
                        if ((obj instanceof AppInstrumentation)) {
                            return true;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        } while (!Instrumentation.class.equals(clazz));
        return false;
    }

    private void checkHCallback() {
        HookManager.get().checkEnv(HCallbackProxy.class);
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
//        if (cl == BActivityThread.loadedApkClassLoader){
//            super.newApplication(cl,className,context);
//        }
        ContextCompat.fix(context);
        // ⭐ v2.2：仅当「深度脱壳」开启时才走 native hookDump。
        //   原因：A16 上 DexDump::hookDumpDex → Dobby 解析会 SIGSEGV（实测），
        //   标准模式必须关闭；深度模式用户显式选择后才尝试（可能崩，但需要 CodeItem）。
        boolean deep = false;
        try {
            deep = top.niunaijun.blackbox.BlackDexCore.isDeepUnpack();
        } catch (Throwable ignored) {
        }
        if (deep && BlackBoxCore.get().isEnableHookDump()) {
            File hookDir = new File(BlackBoxCore.get().getDexDumpDir(), context.getPackageName());
            String subDir = BlackBoxCore.get().getDumpSubDir();
            if (subDir != null && !subDir.isEmpty()) {
                hookDir = new File(hookDir, subDir);
            }
            String absolutePath = hookDir.getAbsolutePath();
            FileUtils.mkdirs(absolutePath);
            //直接用宿主的VMCore，避免通过目标classloader加载vm.apk里的VMCore造成两个VMCore类
            //（native方法只RegisterNatives到先加载lib的那个，另一个会UnsatisfiedLinkError）
            try {
                VMCore.hookDumpDex(absolutePath);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        return super.newApplication(cl, className, context);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkHCallback();
        Log.d(TAG, "callActivityOnCreate: " + activity.getClass().getName());
        ContextCompat.fix(activity);
        super.callActivityOnCreate(activity, icicle);
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        checkHCallback();
        super.callApplicationOnCreate(app);
    }

    public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException e) {
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }
}
