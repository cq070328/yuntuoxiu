package top.niunaijun.blackbox.core;

import android.os.Build;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.dump.DumpResult;
import top.niunaijun.blackbox.utils.DexUtils;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.compat.DexFileCompat;
import top.niunaijun.jnihook.MethodUtils;

/**
 * Created by Milk on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class VMCore {
    public static final String TAG = "VMCoreJava";

    static {
        //牛奶哥写的，不知道有什么寓意，没删，留着
        new File("");
        System.loadLibrary("blackdex");
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    public static native void hideXposed();

    private static native void cookieDumpDex(long cookie, String dir, boolean fixMethod, boolean verify);

    public static native void hookDumpDex(String dir);

    //public static native void hookBeforeSoLoad(String fakePath);

    public static void cookieDumpDex(ClassLoader classLoader, String packageName) {
        List<Long> cookies = DexFileCompat.getCookies(classLoader);
        File baseFile = new File(BlackBoxCore.get().getDexDumpDir(), packageName);
        String subDir = BlackBoxCore.get().getDumpSubDir();
        File file = (subDir != null && !subDir.isEmpty()) ? new File(baseFile, subDir) : baseFile;

        DumpResult result = new DumpResult();
        result.dir = file.getAbsolutePath();
        result.packageName = packageName;
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        ExecutorService executorService = Executors.newFixedThreadPool(availableProcessors <= 0 ? 1 : availableProcessors);
        CountDownLatch countDownLatch = new CountDownLatch(cookies.size());
        AtomicInteger atomicInteger = new AtomicInteger(0);

        BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpProcess(cookies.size(), atomicInteger.getAndIncrement()));
        if (BlackBoxCore.get().isAutoCallMethod()){
            //主动调用脱壳，尝试调用所有类。深度解析没修好，用主动调用来代替
            autoCallAllMethod(BActivityThread.loadedApkClassLoader);
        }
        for (int i = 0; i < cookies.size(); i++) {
            long cookie = cookies.get(i);
            if (cookie == 0) {
                countDownLatch.countDown();
                BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpProcess(cookies.size(), atomicInteger.getAndIncrement()));
                continue;
            }
            FileUtils.mkdirs(file);
            if (atomicInteger.get() == 1) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
            executorService.execute(() -> {
                cookieDumpDex(cookie, file.getAbsolutePath(), BlackBoxCore.get().isFixCodeItem(), BlackBoxCore.get().isVerifyDex());
                BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpProcess(cookies.size(), atomicInteger.getAndIncrement()));
                countDownLatch.countDown();
            });
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException ignored) {
        }
        File[] files = file.listFiles();
        if (files != null) {
            for (File dex : files) {
                if (dex.isFile() && dex.getAbsolutePath().endsWith(".dex")) {
                    DexUtils.fixDex(dex);
                }
            }
        }
    }

    private static void autoCallAllMethod(ClassLoader origianClassLoader){
            List<String> nameList = DexFileCompat.getClassNameList(origianClassLoader);
            for (String str : nameList) {
                // 过滤dpt的检测类，防止主动调用被检测到
                if (str.startsWith("com.luoye.dpt")){
                    Log.d(TAG, "autoCallAllMethod: 加载到dpt壳类"+str+"自动跳过");
                    continue;
                } else if (str.startsWith("top.niunaijun")){
                    Log.d(TAG, "autoCallAllMethod: 加载到了自身的类"+str);
                    continue;
                }
                try {
                    origianClassLoader.loadClass(str);
                } catch (Throwable ignored) {}
            }
    }

    @Keep
    public static int getCallingUid(int origCallingUid) {
//        if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID)
//            return origCallingUid;
//        // 非用户应用
//        if (origCallingUid > Process.LAST_APPLICATION_UID)
//            return origCallingUid;
//
//        Log.d(TAG, "origCallingUid: " + origCallingUid + " => " + BClient.getBaseVUid());
//        return BClient.getBaseVUid();
        return origCallingUid;
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Keep
    public static long[] loadEmptyDex() {
        try {
            File cacheFile = new File(BActivityThread.dumpTargetContext.getDataDir().getParentFile().getParentFile().getParentFile().getParentFile(),"cache");
            File emptyJar = new File(cacheFile,"empty.apk");
            DexFile dexFile = new DexFile(emptyJar);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            long[] longs = new long[cookies.size()];
            for (int i = 0; i < cookies.size(); i++) {
                longs[i] = cookies.get(i);
            }
            return longs;
        }catch (ExceptionInInitializerError exceptionInInitializerError){
            Log.e(TAG, "loadEmptyDex: ", exceptionInInitializerError.getCause());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new long[]{};
    }

    @Keep
    public static Object findMethod(String className, String methodName, String signature) {
        try {
            className = className.replace("/", ".");
            if (className.startsWith("L")) {
                className = className.substring(1);
            }
            if (className.endsWith(";")) {
                className = className.substring(0, className.length() - 1);
            }
            ClassLoader classLoader = BActivityThread.getApplication().getClassLoader();
            Class<?> aClass = Class.forName(className, false, classLoader);
            if ("<init>".equals(methodName)) {
                Constructor<?>[] constructors = aClass.getDeclaredConstructors();
                for (Constructor<?> constructor : constructors) {
                    String desc = MethodUtils.getDesc(constructor);
                    if (signature.equals(desc)) {
                        return constructor;
                    }
                }
            }

            try {
                Method[] declaredMethods = aClass.getDeclaredMethods();
                for (Method declaredMethod : declaredMethods) {
                    if (declaredMethod.getName().equals(methodName)) {
                        String desc = MethodUtils.getDesc(declaredMethod);
                        if (desc.equals(signature)) {
                            return declaredMethod;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            Method[] methods = aClass.getMethods();
            for (Method method : methods) {
                if (method.getName().equals(methodName)) {
                    String desc = MethodUtils.getDesc(method);
                    if (desc.equals(signature)) {
                        return method;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
