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

    /**
     * ⭐ v2.3：壳 / 宿主（云脱修自己）特征串。
     *
     * 用途：从 APK 抽取 classes*.dex 时，过滤掉「壳引导 dex」与「宿主 dex」，
     *   避免把它们当作被脱壳应用的真实业务 dex 写出。
     *
     * 注意：这些是类描述符/包名片段，命中即高度可疑（正常业务 dex 不会引用它们）。
     */
    private static final String[] SHELL_OR_HOST_MARKERS = new String[]{
            // 宿主（云脱修）自身
            "com/yuntuoxiu/app",
            "top/niunaijun/blackbox",
            "com/ai/assistance/operit",
            // 常见壳 stub
            "com/stub/StubApp",
            "com/stub/StubApplication",
            "com/tencent/StubShell",
            "com/secneo/apkwrapper",
            "com/wrapper/proxyapplication",
            "com/qihoo/util",
            "libjiagu",
            "libmetasec",
            "libnpth",
            "libshella",
            "libshell-super",
            "libDexHelper",
            "libsecexe",
    };

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
        // ⭐⭐⭐ v2.4【关键防护】：若传入的 loader 是「宿主/框架 loader」，
        //   其 dexElements 全是宿主自己的 dex → dump 出来就是「云脱修自己的 dex」，
        //   这正是「脱出宿主 DEX」的直接根因之一。
        //   判定：loader 的 pathList 里所有 dex 路径均属于宿主安装位置 → 视为宿主 loader，跳过。
        try {
            if (isHostLoader(classLoader)) {
                BlackBoxCore.bbxLog("getCookies: ⚠️ 传入 ClassLoader 判定为【宿主/框架 loader】，"
                        + "跳过（避免 dump 出宿主 dex）。cls=" +
                        (classLoader == null ? "null" : classLoader.getClass().getName()));
                return cookies;
            }
        } catch (Throwable ignored) {
        }
        List<DexFile> dexFiles = getDexFiles(classLoader);
        for (DexFile dexFile : dexFiles) {
            // 逐个 DexFile 再过滤：名字含宿主包路径的也跳过
            try {
                String name = dexFile.getName();
                if (name != null && isHostDexPath(name)) {
                    BlackBoxCore.bbxLog("getCookies: 跳过宿主 dex 来源 " + name);
                    continue;
                }
            } catch (Throwable ignored) {
            }
            cookies.addAll(getCookies(dexFile));
        }
        return cookies;
    }

    /**
     * ⭐ v2.4：判断某路径是否属于「宿主（云脱修）自身」的 dex。
     *   宿主 APK 安装位置形如：
     *     /data/app/~~xxx/com.yuntuoxiu.app-xxx/base.apk
     *     /data/user/0/com.yuntuoxiu.app/...
     */
    private static boolean isHostDexPath(String path) {
        if (path == null) return false;
        return path.contains("com.yuntuoxiu.app")
                || path.contains("com.ai.assistance.operit")
                || path.contains("/blackbox_dump")
                || path.contains("virtual/data/app/com.yuntuoxiu.app");
    }

    /**
     * ⭐ v2.4：判断 ClassLoader 是否是「宿主/框架 loader」。
     *   依据：pathList.dexElements 中每个 dex 的来源路径都属于宿主安装位置。
     *   若「全部」属于宿主 → 判定为宿主 loader（返回 true）。
     */
    private static boolean isHostLoader(ClassLoader classLoader) {
        if (classLoader == null) return true;  // null loader 视为无效 → 跳过
        try {
            List<DexFile> dexFiles = getDexFiles(classLoader);
            if (dexFiles.isEmpty()) return false;
            int hostCount = 0;
            int total = 0;
            for (DexFile d : dexFiles) {
                if (d == null) continue;
                total++;
                String name;
                try {
                    name = d.getName();
                } catch (Throwable t) {
                    continue;
                }
                if (isHostDexPath(name)) hostCount++;
            }
            // 全部（且至少 1 个）属于宿主 → 宿主 loader
            return total > 0 && hostCount == total;
        } catch (Throwable t) {
            return false;
        }
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
            // ⭐⭐⭐ v2.4 修复（关键）：**不再清空整个 dumpDir**！
            //   原 v2.2 代码在此处删除目录内所有 .dex，而本方法在 VMCore 中
            //   于 cookieDumpDex / memScanMultiRound **之前**被调用，
            //   虽然时序上是「先删后写」，但如果库/上层重入、或 scan 产物已落盘，
            //   就会把内存扫描/壳解密的真实 dex 一并删除 → 只剩壳 stub。
            //   现改为：只删除「本方法自己上次写的 target_*.dex」，
            //   绝不动 cookie_*.dex / scan_*.dex / mem_*.dex。
            try {
                java.io.File[] olds = dumpDir.listFiles();
                if (olds != null) {
                    for (java.io.File f : olds) {
                        if (f.isFile() && f.getName().startsWith("target_")
                                && f.getName().endsWith(".dex")) {
                            f.delete();
                        }
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

                // ⭐⭐ v2.3：跳过「壳 stub dex」——
                //   ① 体积过小（<64KB）：壳引导碎片
                //   ② 内容命中壳/宿主特征：即使体积较大（如御安全 classes.dex=129KB）
                //      也属于壳引导代码，不是被脱壳应用的真实业务 dex。
                //   真实业务 dex 应远大于 64KB 且不含壳/宿主特征。
                if (data.length < 64 * 1024) {
                    BlackBoxCore.bbxLog("getCookiesFromApk: 跳过 stub dex " + dn
                            + " (" + data.length + " bytes, 体积过小)");
                    continue;
                }
                String head = new String(data, 0, Math.min(data.length, 2 * 1024 * 1024),
                        "ISO-8859-1");
                String shellHit = null;
                for (String m : SHELL_OR_HOST_MARKERS) {
                    if (head.contains(m)) { shellHit = m; break; }
                }
                if (shellHit != null) {
                    BlackBoxCore.bbxLog("getCookiesFromApk: 跳过壳/宿主 dex " + dn
                            + " (" + data.length + " bytes, 命中 " + shellHit + ")");
                    continue;
                }

                // 直接写 dump 文件（命名 target_<name>.dex）
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
            BlackBoxCore.bbxLog("getCookiesFromApk: 共写出 " + written + " 个目标 dex（跳过 stub）");
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
