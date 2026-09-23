package top.niunaijun.blackbox.utils.compat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
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
                return cookies;
            }
            BlackBoxCore.bbxLog("getCookiesFromApk: apk=" + apk);

            // ⭐⭐ v2.2 关键修复：直接把 APK 里的 dex 字节【写到 dump 目录】，
            //   而不是返回 cookies 让 native 从内存读。
            //   原因：InMemoryDexClassLoader 的 mCookie 结构与普通 DexFile 不同，
            //   用 cookieDumpDex 的 beginOffset 读到的是错误地址（实测 dump 出宿主 dex）。
            //   —— 直接写文件最可靠。
            java.io.File dumpDir = new java.io.File(BlackBoxCore.get().getDexDumpDir(), packageName);
            dumpDir.mkdirs();
            // ⭐⭐ 先清空目录，避免上一次的残留（cookie_*.dex 宿主 dex）混入
            try {
                java.io.File[] olds = dumpDir.listFiles();
                if (olds != null) {
                    for (java.io.File f : olds) {
                        if (f.isFile() && f.getName().endsWith(".dex")) f.delete();
                    }
                }
            } catch (Throwable ignored) {
            }

            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk);
            java.util.List<String> dexNames = new ArrayList<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.getName().matches("^classes\\d*\\.dex$")) dexNames.add(e.getName());
            }
            java.util.Collections.sort(dexNames);
            BlackBoxCore.bbxLog("getCookiesFromApk: dex entries=" + dexNames);

            int written = 0;
            for (String dn : dexNames) {
                byte[] data;
                java.io.InputStream in = zf.getInputStream(zf.getEntry(dn));
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                in.close();
                data = bos.toByteArray();

                // 直接写 dump 文件（命名 target_<size>.dex，便于与宿主 cookie_* 区分）
                java.io.File out = new java.io.File(dumpDir, "target_" + dn);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                fos.write(data);
                fos.flush();
                fos.close();
                written++;
                BlackBoxCore.bbxLog("getCookiesFromApk: 已写出 " + out.getAbsolutePath()
                        + " (" + data.length + " bytes)");
            }
            zf.close();
            BlackBoxCore.bbxLog("getCookiesFromApk: 共写出 " + written + " 个目标 dex");
            // 返回空 cookies（不再走 native 内存读取路径）
            return cookies;
        } catch (Throwable t) {
            top.niunaijun.blackbox.BlackBoxCore.bbxLog("getCookiesFromApk 失败: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            android.util.Log.e(TAG, "getCookiesFromApk 失败", t);
        }
        return cookies;
    }

    /**
     * ⭐ v2.2：统计某包 dump 目录下的 dex 文件数（用于判断是否已写出目标 dex）。
     */
    public static int countDexInDir(String packageName) {
        try {
            java.io.File dir = new java.io.File(
                    top.niunaijun.blackbox.BlackBoxCore.get().getDexDumpDir(), packageName);
            if (!dir.isDirectory()) return 0;
            java.io.File[] fs = dir.listFiles();
            if (fs == null) return 0;
            int n = 0;
            for (java.io.File f : fs) {
                if (f.isFile() && f.getName().endsWith(".dex") && f.length() > 0) n++;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
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
