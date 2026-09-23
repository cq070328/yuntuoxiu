package top.niunaijun.blackbox.core.system;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.AppSystemEnv;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.core.system.am.BActivityManagerService;
import top.niunaijun.blackbox.core.system.os.BStorageManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageInstallerService;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.core.system.user.BUserManagerService;
import top.niunaijun.blackbox.utils.FileUtils;

import static top.niunaijun.blackbox.core.env.BEnvironment.EMPTY_JAR;
import static top.niunaijun.blackbox.core.env.BEnvironment.JUNIT_JAR;
import static top.niunaijun.blackbox.core.env.BEnvironment.VM_JAR;

/**
 * Created by Milk on 4/22/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BlackBoxSystem {
    private static BlackBoxSystem sBlackBoxSystem;

    public static BlackBoxSystem getSystem() {
        if (sBlackBoxSystem == null) {
            synchronized (BlackBoxSystem.class) {
                if (sBlackBoxSystem == null) {
                    sBlackBoxSystem = new BlackBoxSystem();
                }
            }
        }
        return sBlackBoxSystem;
    }

    public void startup() {
        BEnvironment.load();

        BPackageManagerService.get().systemReady();
        BUserManagerService.get().systemReady();
        BActivityManagerService.get().systemReady();
        BStorageManagerService.get().systemReady();
        BPackageInstallerService.get().systemReady();

        List<String> preInstallPackages = AppSystemEnv.getPreInstallPackages();
        for (String preInstallPackage : preInstallPackages) {
            // ⭐ v2.2 修复：预装包安装必须“尽力而为、绝不外溢异常”。
            //   原代码只 catch NameNotFoundException；但 A16 上 installPackageAsUser
            //   可能抛 NoSuchFieldError 等其他 Throwable（如 com.huawei.hwid 的
            //   PackageParser$Package.baseCodePath 缺失）→ 直接中断整个 startup，
            //   导致 :black 服务进程起不来 → 目标 :p 进程拉不起 → 未产出 DEX。
            try {
                if (!BPackageManagerService.get().isInstalled(preInstallPackage, BUserHandle.USER_ALL)) {
                    PackageInfo packageInfo = BlackBoxCore.getPackageManager().getPackageInfo(preInstallPackage, 0);
                    BPackageManagerService.get().installPackageAsUser(packageInfo.applicationInfo.sourceDir, InstallOption.installBySystem(), BUserHandle.USER_ALL);
                    BlackBoxCore.bbxLog("startup: 预装包成功 " + preInstallPackage);
                }
            } catch (Throwable t) {
                // 预装失败不影响脱壳主流程 —— 记录后继续
                BlackBoxCore.bbxLog("startup: 预装包跳过 " + preInstallPackage
                        + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
        }
        initJarEnv();
    }

    private void initJarEnv() {
        try {
            //handleBindApplication 会把这些 jar 设为只读（Android14+ dex校验），覆盖更新前必须先设回可写，
            //否则旧的只读 vm.apk 无法被新版本覆盖，导致 :p0 加载到过期的 VMCore 类
            JUNIT_JAR.setWritable(true);
            EMPTY_JAR.setWritable(true);
            VM_JAR.setWritable(true);

            InputStream junit = BlackBoxCore.getContext().getAssets().open("junit.jar");
            FileUtils.copyFile(junit, JUNIT_JAR);

            InputStream empty = BlackBoxCore.getContext().getAssets().open("empty.jar");
            FileUtils.copyFile(empty, EMPTY_JAR);

            InputStream vm = BlackBoxCore.getContext().getAssets().open("vm.jar");
            FileUtils.copyFile(vm, VM_JAR);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // ⭐⭐⭐ v2.5【关键修复】拷贝完成后必须**立即设回只读**。
        //   否则 :p0 进程加载 vm.apk 时 Android 会抛：
        //     "Attempt to load writable dex file: .../virtual/cache/vm.apk"
        //   → 进程启动即崩溃 → 永远无 dump（"未产出 DEX"）。
        //   注意：:p0 与 :black 是不同进程，必须在 jar 落盘后（本处）锁定，
        //         仅靠 BActivityThread 内的 chmod 不够（时机晚于 :p0 启动）。
        lockJarReadOnly(JUNIT_JAR);
        lockJarReadOnly(EMPTY_JAR);
        lockJarReadOnly(VM_JAR);
        // ⭐ v2.5：同时覆盖「懒加载方法」路径（getCacheDir()/vm.apk 等），
        //   防止静态字段 VM_JAR 因 context 尚未就绪而指向错误路径。
        lockJarReadOnly(BEnvironment.getJUnitJar());
        lockJarReadOnly(BEnvironment.getEmptyJar());
        lockJarReadOnly(BEnvironment.getVmJar());
    }

    /** 把 jar 去写权限（三级兜底），供 :p0 进程安全加载 */
    private void lockJarReadOnly(java.io.File jar) {
        if (jar == null || !jar.isFile()) return;
        try {
            if (!jar.canWrite()) return;
            try {
                android.system.Os.chmod(jar.getAbsolutePath(), 0400);
            } catch (Throwable ignored) {
            }
            if (jar.canWrite()) {
                try { jar.setWritable(false, false); } catch (Throwable ignored) {}
            }
            if (jar.canWrite()) {
                try {
                    FileUtils.chmod(jar.getAbsolutePath(), FileUtils.FileMode.MODE_IRUSR);
                } catch (Throwable ignored) {}
            }
            BlackBoxCore.bbxLog("initJarEnv: " + jar.getName()
                    + " 去写权限 -> canWrite=" + jar.canWrite());
        } catch (Throwable t) {
            BlackBoxCore.bbxLog("initJarEnv: lock " + jar.getName() + " 失败: " + t.getMessage());
        }
    }
}
