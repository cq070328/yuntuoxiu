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
        List<Long> loaderCookies = DexFileCompat.getCookies(classLoader);
        // ⭐ v2.2：若 loader 取不到 cookies（如 loader=宿主/目标加载失败），
        //   则直接从沙箱安装的目标 APK 提取 dex 并【直接写出】（不经 native 内存读取）。
        boolean apkWroteDex = false;
        if (loaderCookies == null || loaderCookies.isEmpty()) {
            BlackBoxCore.bbxLog("VMCore.cookieDumpDex: loader cookies 为空，尝试从目标 APK 直接提取");
            int before = DexFileCompat.countDexInDir(packageName);
            loaderCookies = DexFileCompat.getCookiesFromApk(packageName);
            int after = DexFileCompat.countDexInDir(packageName);
            apkWroteDex = (after > before);
            BlackBoxCore.bbxLog("VMCore.cookieDumpDex: 从 APK 提取完成, 新增 dex 文件=" + (after - before)
                    + " cookies=" + (loaderCookies == null ? "null" : loaderCookies.size()));
        }
        // ⭐ lambda 要求 effectively final → 用 final 别名
        final List<Long> cookies = (loaderCookies == null)
                ? new ArrayList<Long>() : loaderCookies;
        File baseFile = new File(BlackBoxCore.get().getDexDumpDir(), packageName);
        String subDir = BlackBoxCore.get().getDumpSubDir();
        File file = (subDir != null && !subDir.isEmpty()) ? new File(baseFile, subDir) : baseFile;
        // ⭐ v2.2：目录先建好（原先只在循环内 mkdirs，cookies 为空则不建）
        FileUtils.mkdirs(file);
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

        // ⭐⭐ v2.2：深度脱壳时，**总是**额外做「内存扫描」——
        //   对 VMP 壳（腾讯御安全等），base.apk 里的 classesN.dex 只是壳 stub
        //   （实测：classes.dex 仅 126KB + 3 个 2.4KB），真实代码加密在 assets。
        //   解密后的完整 dex 必然在某段可读内存中 → 扫描 /proc/self/maps 找出来。
        //
        //   ⚠️ 修正 v2.2：即使已从 APK 写出 stub dex，**也必须**做内存扫描
        //      （stub ≠ 真实代码）。两者叠加，DexPostProcessor 会保留 class 数多的。
        if (BlackBoxCore.get().isFixCodeItem()) {
            try {
                BlackBoxCore.bbxLog("VMCore.cookieDumpDex: 深度模式 → 触发多轮内存扫描（SMZ 分段解密）");
                // ⭐ 6 轮 × 800ms ≈ 覆盖 5 秒解密窗口（SMZ 逐段解密）
                memScanMultiRound(file.getAbsolutePath(), 6, 800);
            } catch (Throwable t) {
                BlackBoxCore.bbxLog("VMCore.cookieDumpDex: memScanMultiRound 异常: " + t.getMessage());
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
