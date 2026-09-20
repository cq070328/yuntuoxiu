package top.niunaijun.blackbox.service;

import top.niunaijun.blackbox.entity.dump.DumpResult;

interface IDumpCallback {
    void onDump(in DumpResult result);
}
