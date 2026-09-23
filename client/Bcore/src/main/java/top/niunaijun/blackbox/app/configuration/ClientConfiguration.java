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
        // ⭐ v2.5：默认 **false**。
        //   历史：注释写「恢复 true」，但实测 A16 上
        //     DexDump::hookDumpDex → DobbySymbolResolver → elf_ctx_init 会 SIGSEGV，
        //   导致 :p0 在 handleBindApplication 阶段崩溃 → 永远无 dump。
        //   且全部实际配置（YunTuoXiuApp 主/子进程）均已显式返回 false。
        //   为避免「未覆盖该方法的配置误走 native hook」，此处默认改为 false。
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
