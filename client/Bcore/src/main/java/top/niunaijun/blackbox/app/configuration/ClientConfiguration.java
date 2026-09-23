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
        // ⭐ v2.2：恢复 true。实际是否走 native hookDump 由「深度脱壳」开关
        //   （BlackDexCore.isDeepUnpack）在 AppInstrumentation 中二次门控：
        //   标准模式 deep=false → 不走 hookDump（避开 A16 Dobby 崩溃）
        //   深度模式 deep=true  → 走 hookDump（尝试 dump CodeItem）
        return true;
    }

    public boolean isAutoCallMethod(){return false;}

    public boolean isVerifyDex() {
        return true;
    }

    public String getDumpSubDir() {
        return "";
    }
}
