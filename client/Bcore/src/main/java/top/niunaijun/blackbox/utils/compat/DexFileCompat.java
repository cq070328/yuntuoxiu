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
            if (apk == null || !apk.isFile()) {
                android.util.Log.w(TAG, "getCookiesFromApk: APK 不存在 " + apk);
                return cookies;
            }
            // 解压到宿主 cache 下的临时目录（供 DexFile 打开）
            java.io.File tmp = new java.io.File(
                    top.niunaijun.blackbox.BlackBoxCore.getContext().getCacheDir(), "apkdex/" + packageName);
            if (tmp.exists()) {
                // 清空旧内容
                java.io.File[] olds = tmp.listFiles();
                if (olds != null) for (java.io.File f : olds) f.delete();
            } else {
                tmp.mkdirs();
            }

            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk);
            java.util.List<String> dexNames = new ArrayList<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.getName().matches("^classes\\d*\\.dex$")) dexNames.add(e.getName());
            }
            java.util.Collections.sort(dexNames);
            android.util.Log.i(TAG, "getCookiesFromApk: " + apk + " dex=" + dexNames);

            for (String dn : dexNames) {
                java.io.File out = new java.io.File(tmp, dn);
                java.io.InputStream in = zf.getInputStream(zf.getEntry(dn));
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                fos.close();
                in.close();

                // ⭐ 用 DexFile 打开（DexFile 不校验"可写目录"）
                DexFile df = new DexFile(out);
                cookies.addAll(getCookies(df));
            }
            zf.close();
            android.util.Log.i(TAG, "getCookiesFromApk: 共提取 cookies=" + cookies.size());
        } catch (Throwable t) {
            android.util.Log.e(TAG, "getCookiesFromApk 失败: " + t.getMessage(), t);
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
