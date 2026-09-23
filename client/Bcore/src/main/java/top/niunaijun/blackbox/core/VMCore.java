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

    /**
     * ⭐ v2.2：内存扫描脱壳（对 VMP 壳有效）。
     *   扫描进程内存中所有「dex magic + 结构合法」的区域并 dump。
     */
    public static native void memScanDump(String dir);

    /**
     * ⭐ v2.2：多轮内存扫描（针对 SMZ/VMP 分段解密）。
     *   逐段解密的壳，需在多个时间点扫描才能捕获所有段。
     */
    public static native void memScanMultiRound(String dir, int rounds, int intervalMs);

    //public static native void hookBeforeSoLoad(String fakePath);

    public static void cookieDumpDex(ClassLoader classLoader, String packageName) {
        // ⭐⭐⭐ v2.4 通用脱壳（对齐 Layout Inspect 的确定性思路）：
        //   不依赖「猜 loader / 猜 cookie / 取 APK stub」——
        //   而是**以「内存扫描」为默认主路径**（Layout Inspect 的 dump_maps 思路）：
        //     · 真实 dex（无论抽取壳还是 VMP）最终必然在进程内存中以已解密形态存在；
        //     · 内存扫描可直接命中，无需正确的 loader / cookie。
        //   其余两条路（cookie / APK 提取）作为补充，产物由 DexPostProcessor 统一去重择优。
        //
        //   顺序（关键）：
        //     ① 先扫一轮内存（捕获「早解密」的壳）
        //     ② cookie dump（对已注册进 ART 的 dex）
        //     ③ 从 APK 提取（未加壳 / 壳外置场景）
        //     ④ **再扫多轮内存**（等壳 Application.onCreate 解密稳定后再扫）
        List<Long> loaderCookies = DexFileCompat.getCookies(classLoader);
        BlackBoxCore.bbxLog("VMCore.cookieDumpDex: ① loader cookies="
                + (loaderCookies == null ? "null" : loaderCookies.size()));

        File baseFile = new File(BlackBoxCore.get().getDexDumpDir(), packageName);
        String subDir = BlackBoxCore.get().getDumpSubDir();
        File file = (subDir != null && !subDir.isEmpty()) ? new File(baseFile, subDir) : baseFile;
        FileUtils.mkdirs(file);

        // ⭐ v2.4：① 先做一轮内存扫描（早解密捕获）—— 无条件执行
        try {
            BlackBoxCore.bbxLog("VMCore.cookieDumpDex: ① 内存扫描（早解密）");
            memScanDump(file.getAbsolutePath());
        } catch (Throwable t) {
            BlackBoxCore.bbxLog("VMCore.cookieDumpDex: ① memScanDump 异常: " + t.getMessage());
        }

        // ② 从 APK 提取 dex（>64KB，跳过 stub；不再清空目录）
        int before = DexFileCompat.countDexInDir(packageName);
        DexFileCompat.getCookiesFromApk(packageName);
        int after = DexFileCompat.countDexInDir(packageName);
        BlackBoxCore.bbxLog("VMCore.cookieDumpDex: ② 从 APK 提取 tar dex, 新增=" + (after - before));

        // ⭐ lambda 要求 effectively final → 用 final 别名
        final List<Long> cookies = (loaderCookies == null)
                ? new ArrayList<Long>() : loaderCookies;
        BlackBoxCore.bbxLog("VMCore.cookieDumpDex: pkg=" + packageName
                + " cookies=" + (cookies == null ? "null" : cookies.size())
                + " dir=" + file.getAbsolutePath()
                + " exists=" + file.exists() + " canWrite=" + file.canWrite());

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

        // ⭐⭐⭐ v2.4：③ 无条件执行「多轮内存扫描」——
        //   v2.2 仅在 isFixCodeItem()（深度模式）才扫描，导致普通模式对
        //   抽取壳 / VMP 壳完全无产出。现改为**默认路径**。
        //   对 VMP 壳（腾讯御安全等），真实 dex 由壳 so 运行时解密到内存，
        //   既不在 base.apk 的 classes*.dex 里，也不在 loader 的 dexElements 里 ——
        //   内存扫描是唯一可行手段（即 Layout Inspect 的方案）。
        try {
            BlackBoxCore.bbxLog("VMCore.cookieDumpDex: ③ 内存多轮扫描（默认路径）");
            memScanMultiRound(file.getAbsolutePath(), 8, 700);
        } catch (Throwable t) {
            BlackBoxCore.bbxLog("VMCore.cookieDumpDex: memScanMultiRound 异常: " + t.getMessage());
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
