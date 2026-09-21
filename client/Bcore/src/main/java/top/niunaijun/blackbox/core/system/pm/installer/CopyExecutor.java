package top.niunaijun.blackbox.core.system.pm.installer;


import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.IOException;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.NativeUtils;

/**
 * Created by Milk on 4/24/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 * 拷贝文件相关
 */
public class CopyExecutor implements Executor {

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        try {
            NativeUtils.copyNativeLib(new File(ps.pkg.baseCodePath), BEnvironment.getAppLibDir(ps.pkg.packageName));
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }

        // ⭐ v2.0 修复：libblackdex*.so 的拷贝改为「尽力而为」。
        //   原代码从 applicationInfo.nativeLibraryDir 拷贝，但 Android 10+ 若
        //   extractNativeLibs=false，so 不会解压到 nativeLibraryDir → FileNotFoundException
        //   → catch → return -1 → 整个安装失败。
        //   （so 实际在宿主 APK 内，运行时由 System.loadLibrary 直接加载，无需拷贝）
        try {
            ApplicationInfo applicationInfo = BlackBoxCore.getContext().getApplicationInfo();
            File libDir = BEnvironment.getAppLibDir(ps.pkg.packageName);
            libDir.mkdirs();
            for (String soName : new String[]{"libblackdex.so", "libblackdex_d.so"}) {
                File src = new File(applicationInfo.nativeLibraryDir, soName);
                if (src.isFile()) {
                    try {
                        FileUtils.copyFile(src, new File(libDir, soName));
                    } catch (Throwable t) {
                        BlackBoxCore.bbxLog("CopyExecutor: 拷贝 " + soName + " 失败(忽略): " + t.getMessage());
                    }
                } else {
                    // nativeLibraryDir 里没有（extractNativeLibs=false）→ 尝试从 APK 内提取
                    boolean ok = false;
                    try {
                        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(
                                BlackBoxCore.getContext().getApplicationInfo().sourceDir);
                        java.util.zip.ZipEntry ze = zf.getEntry("lib/arm64-v8a/" + soName);
                        if (ze == null) ze = zf.getEntry("lib/armeabi-v7a/" + soName);
                        if (ze != null) {
                            java.io.InputStream in = zf.getInputStream(ze);
                            java.io.FileOutputStream out = new java.io.FileOutputStream(new File(libDir, soName));
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                            out.close();
                            in.close();
                            ok = true;
                        }
                        zf.close();
                    } catch (Throwable t) {
                        BlackBoxCore.bbxLog("CopyExecutor: 从 APK 提取 " + soName + " 失败(忽略): " + t.getMessage());
                    }
                    BlackBoxCore.bbxLog("CopyExecutor: " + soName
                            + (ok ? " 已从 APK 提取" : " 未找到(忽略，运行时从宿主加载)"));
                }
            }
        } catch (Throwable t) {
            // ⭐ 不再因 so 拷贝失败中断安装
            BlackBoxCore.bbxLog("CopyExecutor: so 处理异常(忽略): " + t.getMessage());
        }

        if (option.isFlag(InstallOption.FLAG_STORAGE)) {
            // 外部安装
            File origFile = new File(ps.pkg.baseCodePath);
            File newFile = BEnvironment.getBaseApkDir(ps.pkg.packageName);
            try {
                newFile.getParentFile().mkdirs();
                if (option.isFlag(InstallOption.FLAG_URI_FILE)) {
                    boolean b = FileUtils.renameTo(origFile, newFile);
                    if (!b) {
                        FileUtils.copyFile(origFile, newFile);
                    }
                } else {
                    FileUtils.copyFile(origFile, newFile);
                }
                // update baseCodePath
                ps.pkg.baseCodePath = newFile.getAbsolutePath();
                BlackBoxCore.bbxLog("CopyExecutor: APK 已拷贝到 " + newFile.getAbsolutePath()
                        + " size=" + newFile.length());
            } catch (IOException e) {
                BlackBoxCore.bbxLog("CopyExecutor: APK 拷贝失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                e.printStackTrace();
                return -1;
            }
        } else if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
            // 系统安装
        }
        return 0;
    }
}
