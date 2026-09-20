package top.niunaijun.blackbox.service;

import top.niunaijun.blackbox.service.IDumpCallback;

interface IDumpService {
    boolean isReady();
    boolean startDump(String packageName, String dumpDir,
                      boolean fixMethod, boolean hookDump,
                      boolean autoCallMethod, boolean verifyDex,
                      IDumpCallback callback);
    boolean isRunning();
    void cancel();
}
