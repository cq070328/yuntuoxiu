package top.niunaijun.blackbox.app.configuration;

import java.io.File;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.FileUtils;

/**
 * Created by Milk on 5/4/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public abstract class ClientConfiguration {
    private File mExternalDir;

    public final void init() {
        mExternalDir = BlackBoxCore.getContext().getExternalCacheDir().getParentFile();
    }

    public abstract String getHostPackageName();

    public String getDexDumpDir() {
        File dump = new File(mExternalDir, "dump");
        FileUtils.mkdirs(dump);
        return dump.getAbsolutePath();
    }

    public boolean isFixCodeItem() {
        return false;
    }

    public boolean isEnableHookDump() {
        // ⭐ v2.2：默认关闭（Android 16 上 native hookDumpDex → Dobby 解析 SIGSEGV）
        return false;
    }

    public boolean isAutoCallMethod(){return false;}

    public boolean isVerifyDex() {
        return true;
    }

    public String getDumpSubDir() {
        return "";
    }
}
