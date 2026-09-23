package top.niunaijun.blackbox.utils.compat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.utils.Reflector;

/**
 * Created by Milk on 2021/5/16.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class DexFileCompat {
    public static final String TAG = "DexFileCompat";

    public static List<String> getClassNameList(ClassLoader classLoader) {
        List<String> allClass = new ArrayList<>();
        try {
            List<DexFile> dexFiles = getDexFiles(classLoader);
            for (DexFile dexFile : dexFiles) {
                Object object = Reflector.with(dexFile)
                        .field("mCookie")
                        .get();
                String[] classNameList = getClassNameList(object);
                allClass.addAll(Arrays.asList(classNameList));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return allClass;
    }

    private static String[] getClassNameList(Object cookie) {
        try {
            String[] list;
            if (BuildCompat.isM()) {
                list = Reflector.on(DexFile.class)
                        .method("getClassNameList", Object.class)
                        .call(cookie);
            } else {
                list = Reflector.on(DexFile.class)
                        .method("getClassNameList", long.class)
                        .call(cookie);
            }
            return list;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static List<Long> getCookies(ClassLoader classLoader) {
        List<Long> cookies = new ArrayList<>();
        List<DexFile> dexFiles = getDexFiles(classLoader);
        for (DexFile dexFile : dexFiles) {
            cookies.addAll(getCookies(dexFile));
        }
        return cookies;
    }

    public static List<Long> getCookies(DexFile dexFile) {
        List<Long> cookies = new ArrayList<>();
        if (dexFile == null)
            return cookies;
        try {
            Object object = Reflector.with(dexFile)
                    .field("mCookie")
                    .get();
            if (BuildCompat.isM()) {
                for (long l : (long[]) object) {
                    cookies.add(l);
                }
            } else {
                cookies.add((long) object);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return cookies;
    }

    /**
     * ⭐ v2.2：直接从沙箱安装的目标 APK 提取 dex cookies。
     *
     * 背景：DexClassLoader 拒绝加载「可写目录」下的 dex
     *   （SecurityException: Writable dex file ... is not allowed），
     *   导致按路径构造的目标 ClassLoader 失败 → 回退宿主 loader → dump 出宿主 dex。
     *
     * 方案：绕过 DexClassLoader，直接用反射构造 dalvik.system.DexPathList.Element[]：
     *   对 APK 内每个 classesN.dex，用 DexFile 打开（DexFile 不走可写校验），
     *   取 mCookie。
     *
     * @param packageName 目标包名
     * @return dex cookies
     */
    public static List<Long> getCookiesFromApk(String packageName) {
        List<Long> cookies = new ArrayList<>();
        try {
            // 沙箱安装的目标 APK：virtual/data/app/<pkg>/base.apk
            java.io.File apk = top.niunaijun.blackbox.core.env.BEnvironment.getBaseApkDir(packageName);
            top.niunaijun.blackbox.BlackBoxCore.bbxLog("getCookiesFromApk: apk=" + apk
                    + " exists=" + (apk != null && apk.isFile()));
            if (apk == null || !apk.isFile()) {
                return cookies;
            }

            // 读取 APK 内所有 classesN.dex 的字节
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk);
            java.util.List<String> dexNames = new ArrayList<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.getName().matches("^classes\\d*\\.dex$")) dexNames.add(e.getName());
            }
            java.util.Collections.sort(dexNames);
            top.niunaijun.blackbox.BlackBoxCore.bbxLog("getCookiesFromApk: dex entries=" + dexNames);

            java.util.List<byte[]> dexBytes = new ArrayList<>();
            for (String dn : dexNames) {
                java.io.InputStream in = zf.getInputStream(zf.getEntry(dn));
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                in.close();
                dexBytes.add(bos.toByteArray());
            }
            zf.close();
            top.niunaijun.blackbox.BlackBoxCore.bbxLog("getCookiesFromApk: 读出 " + dexBytes.size() + " 个 dex 字节");

            // ⭐⭐ A16 关键：不能用 `new DexFile(File)`（API 26+ 已废弃/受限）。
            //   改用 InMemoryDexClassLoader（API 26+ 支持），从内存字节构造，
            //   再反射取它的 DexPathList → Element[] → dexFile → mCookie。
            java.nio.ByteBuffer[] bufs = new java.nio.ByteBuffer[dexBytes.size()];
            for (int i = 0; i < dexBytes.size(); i++) {
                bufs[i] = java.nio.ByteBuffer.wrap(dexBytes.get(i));
            }
            dalvik.system.InMemoryDexClassLoader imcl =
                    new dalvik.system.InMemoryDexClassLoader(bufs, null);
            top.niunaijun.blackbox.BlackBoxCore.bbxLog("getCookiesFromApk: 已构造 InMemoryDexClassLoader");

            // 从 InMemoryDexClassLoader 的 DexPathList 取 cookies
            cookies = getCookies(imcl);
            top.niunaijun.blackbox.BlackBoxCore.bbxLog("getCookiesFromApk: 从 InMemoryDexClassLoader 取 cookies="
                    + cookies.size());
        } catch (Throwable t) {
            // ⭐ 同时写文件日志（logcat + bbxLog），便于定位
            top.niunaijun.blackbox.BlackBoxCore.bbxLog("getCookiesFromApk 失败: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            android.util.Log.e(TAG, "getCookiesFromApk 失败", t);
        }
        return cookies;
    }

    private static List<DexFile> getDexFiles(ClassLoader classLoader) {
        List<DexFile> dexFiles = new ArrayList<>();
        Object[] dexElements = getDexElements(classLoader);
        for (Object dexElement : dexElements) {
            try {
                dexFiles.add(Reflector.with(dexElement)
                        .field("dexFile")
                        .<DexFile>get());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return dexFiles;
    }

    private static Object[] getDexElements(ClassLoader classLoader) {
        Object dexPathList = getDexPathList(classLoader);
        if (dexPathList == null) {
            return new Object[]{};
        }
        try {
            return Reflector.with(dexPathList)
                    .field("dexElements")
                    .get();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new Object[]{};
    }

    private static Object getDexPathList(ClassLoader classLoader) {
        try {
            return Reflector.on("dalvik.system.BaseDexClassLoader")
                    .field("pathList")
                    .get(classLoader);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
