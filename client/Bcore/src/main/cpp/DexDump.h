//
// Created by Milk on 2021/5/16.
//

#ifndef BLACKBOX_DEXDUMP_H
#define BLACKBOX_DEXDUMP_H
#include <jni.h>

#define HOOK_FUN(ret, func, ...) \
  ret (*orig_##func)(__VA_ARGS__); \
  ret new_##func(__VA_ARGS__)

class DexDump {
public:
    static void hookDumpDex(JNIEnv *env, jstring dir);
    static void cookieDumpDex(JNIEnv *env, jlong cookie, jstring dir, jboolean fix, jboolean verify);
    // ⭐ v2.2：内存扫描脱壳（对 VMP 壳有效）
    static void memScanDump(JNIEnv *env, jstring dir);
    //static void hookBeforeSoLoad(const char *fakePathArg);
};


#endif //BLACKBOX_DEXDUMP_H
