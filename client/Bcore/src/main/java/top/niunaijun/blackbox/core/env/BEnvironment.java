package top.niunaijun.blackbox.core.env;

import java.io.File;
import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.FileUtils;

/**
 * Created by Milk on 4/22/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BEnvironment {
    // ⭐ v2.0 Android 16 修复：
    //   原来用 static final 在类加载时立即 `BlackBoxCore.getContext()`，
    //   但 ContentProvider.onCreate 在 Application.onCreate **之前**执行，
    //   此时 sContext 尚为 null → NPE → :black 进程崩溃。
    //   改为「懒加载」：首次访问时再取 context。
    private static volatile File sVirtualRoot;
    private static volatile File sExternalVirtualRoot;

    private static File computeVirtualRoot() {
        File r = sVirtualRoot;
        if (r == null) {
            synchronized (BEnvironment.class) {
                r = sVirtualRoot;
                if (r == null) {
                    android.content.Context ctx = BlackBoxCore.getContext();
                    if (ctx == null) {
                        // 极端兜底：用 /data/local/tmp（几乎总有权限）
                        r = new File("/data/local/tmp/blackbox_virtual");
                    } else {
                        r = new File(ctx.getCacheDir().getParent(), "virtual");
                    }
                    sVirtualRoot = r;
                }
            }
        }
        return r;
    }

    private static File computeExternalVirtualRoot() {
        File r = sExternalVirtualRoot;
        if (r == null) {
            synchronized (BEnvironment.class) {
                r = sExternalVirtualRoot;
                if (r == null) {
                    android.content.Context ctx = BlackBoxCore.getContext();
                    if (ctx != null && ctx.getExternalFilesDir("virtual") != null) {
                        r = ctx.getExternalFilesDir("virtual");
                    } else {
                        r = new File(computeVirtualRoot(), "external");
                    }
                    sExternalVirtualRoot = r;
                }
            }
        }
        return r;
    }

    // 注意：这些也改为懒加载（不依赖 static final 初始化顺序）
    public static File getJUnitJar() { return new File(getCacheDir(), "junit.apk"); }
    public static File getEmptyJar() { return new File(getCacheDir(), "empty.apk"); }
    public static File getVmJar() { return new File(getCacheDir(), "vm.apk"); }

    // 兼容旧字段（若外部代码引用）
    public static File JUNIT_JAR = new File(computeVirtualRoot(), "cache/junit.apk");
    public static File EMPTY_JAR = new File(computeVirtualRoot(), "cache/empty.apk");
    public static File VM_JAR = new File(computeVirtualRoot(), "cache/vm.apk");

    public static void load() {
        FileUtils.mkdirs(computeVirtualRoot());
        FileUtils.mkdirs(computeExternalVirtualRoot());
        FileUtils.mkdirs(getSystemDir());
        FileUtils.mkdirs(getCacheDir());
    }

    public static File getVirtualRoot() {
        return computeVirtualRoot();
    }

    public static File getExternalVirtualRoot() {
        return computeExternalVirtualRoot();
    }

    public static File getSystemDir() {
        return new File(getVirtualRoot(), "system");
    }

    public static File getCacheDir() {
        return new File(getVirtualRoot(), "cache");
    }

    public static File getUserInfoConf() {
        return new File(getSystemDir(), "user.conf");
    }

    public static File getUidConf() {
        return new File(getSystemDir(), "uid.conf");
    }

    public static File getXPModuleConf() {
        return new File(getSystemDir(), "xposed-module.conf");
    }

    public static File getPackageConf(String packageName) {
        return new File(getAppDir(packageName), "package.conf");
    }

    public static File getExternalUserDir(int userId) {
        return new File(getExternalVirtualRoot(), String.format(Locale.CHINA, "storage/emulated/%d/", userId));
    }

    public static File getUserDir(int userId) {
        return new File(getVirtualRoot(), String.format(Locale.CHINA, "data/user/%d", userId));
    }

    public static File getDeDataDir(String packageName, int userId) {
        return new File(getVirtualRoot(), String.format(Locale.CHINA, "data/user_de/%d/%s", userId, packageName));
    }

    public static File getExternalDataDir(String packageName, int userId) {
        return new File(getExternalUserDir(userId), String.format(Locale.CHINA, "Android/data/%s", packageName));
    }


    public static File getDataDir(String packageName, int userId) {
        return new File(getVirtualRoot(), String.format(Locale.CHINA, "data/user/%d/%s", userId, packageName));
    }

    public static File getExternalDataFilesDir(String packageName, int userId) {
        return new File(getExternalDataDir(packageName, userId), "files");
    }

    public static File getDataFilesDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "files");
    }

    public static File getExternalDataCacheDir(String packageName, int userId) {
        return new File(getExternalDataDir(packageName, userId), "cache");
    }

    public static File getDataCacheDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "cache");
    }

    public static File getDataLibDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "lib");
    }

    public static File getDataDatabasesDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "databases");
    }

    public static File getAppRootDir() {
        return getAppDir("");
    }

    public static File getAppDir(String packageName) {
        return new File(getVirtualRoot(), "data/app/" + packageName);
    }

    public static File getBaseApkDir(String packageName) {
        return new File(getVirtualRoot(), "data/app/" + packageName + "/base.apk");
    }

    public static File getAppLibDir(String packageName) {
        return new File(getAppDir(packageName), "lib");
    }
}
