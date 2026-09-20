//
// Created by Milk on 2021/6/5.
//

#include "ArtM.h"
#include "Art.h"
//
// Created by Milk on 2021/6/5.
//
#include "utils/Log.h"

static struct {
    int api_level;

    int art_method_size;
    uint32_t art_method_flags_offset;
    uint32_t art_method_dex_code_item_offset;
    uint32_t art_method_native_offset;
} ArtMethodEnv;

uint32_t ArtM::GetArtMethodSize() {
    return ArtMethodEnv.art_method_size;
}

uint32_t ArtM::GetArtMethodFlagsOffset() {
    return ArtMethodEnv.art_method_flags_offset;
}

uint32_t ArtM::GetArtMethodDexCodeItemOffset(void *art_method) {
    if (ArtMethodEnv.api_level >= 33) {
        log_print_error("在安卓13以上的版本不支持深度脱壳，请使用主动调用");
    }
    return *reinterpret_cast<const uint32_t *>(reinterpret_cast<char *>(art_method) + ArtMethodEnv.art_method_dex_code_item_offset);
}

uint32_t ArtM::GetArtMethodNativeOffset() {
    return ArtMethodEnv.art_method_native_offset;
}

uint32_t ArtM::GetAccessFlags(const char *art_method) {
    return *reinterpret_cast<const uint32_t *>(art_method + GetArtMethodFlagsOffset());
}

bool ArtM::SetAccessFlags(char *art_method, uint32_t flags) {
    *reinterpret_cast<uint32_t *>(art_method + GetArtMethodFlagsOffset()) = flags;
    return true;
}

bool ArtM::AddAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag | flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

bool ArtM::ClearAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag & ~flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

bool ArtM::HasAccessFlag(char *art_method, uint32_t flag) {
    uint32_t flags = GetAccessFlags(art_method);
    return (flags & flag) == flag;
}

bool ArtM::ClearFastNativeFlag(char *art_method) {
    // FastNative
    return ArtMethodEnv.api_level < __ANDROID_API_P__ &&
           ClearAccessFlag(art_method, kAccFastNative);
}

void *ArtM::GetArtMethod(JNIEnv *env, jclass clazz, jmethodID methodId) {
    if (ArtMethodEnv.api_level >= __ANDROID_API_Q__) {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
        jobject method = env->ToReflectedMethod(clazz, methodId, true);
        return reinterpret_cast<void *>(env->GetLongField(method, artId));
    } else {
        return methodId;
    }
}

void *ArtM::GetArtMethod(JNIEnv *env, jobject method) {
    if (ArtMethodEnv.api_level >= __ANDROID_API_Q__) {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
        return reinterpret_cast<void *>(env->GetLongField(method, artId));
    } else {
        return env->FromReflectedMethod(method);
    }
}

void *GetFieldMethod(JNIEnv *env, jobject field) {
    if (ArtMethodEnv.api_level >= __ANDROID_API_Q__) {
        jclass fieldClass = env->FindClass("java/lang/reflect/Field");
        jmethodID getArtField = env->GetMethodID(fieldClass, "getArtField", "()J");
        return reinterpret_cast<void *>(env->CallLongMethod(field, getArtField));
    } else {
        return env->FromReflectedField(field);
    }
}

__attribute__((section (".mytext")))  JNICALL void native_offset
        (JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext")))  JNICALL void native_offset2
        (JNIEnv *env, jclass obj) {
}

void registerArtNative(JNIEnv *env) {
    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/ArtMethod");
    if (!clazz) {
        env->ExceptionClear();
        return;
    }
    JNINativeMethod gMethods[] = {
            {"nativeOffset",  "()V",        (void *) native_offset},
            {"nativeOffset2", "()V",        (void *) native_offset2},
    };
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        ALOGE("jni register error.");
        env->ExceptionClear();
    }
}

// 深度脱壳是靠加载一个已知的flags的方法‘nativeOffset’来获取flags对于artMethod的偏移。
// 在安卓13以下的版本中dex_code_item_offset_对于artMethod的偏移比access_flags_多1，获取到access_flags_的偏移后加一个uint32_t的大小后就是dex_code_item_offset_
// 而在安卓高版本中artMethod中把dex_code_item_offset_删除了，不能通过这种方法获取codeitem的偏移了。
void ArtM::InitArtMethod(JNIEnv *env, int api_level) {
    registerArtNative(env);
    ArtMethodEnv.api_level = api_level;

    jclass clazz = env->FindClass("top/niunaijun/jnihook/jni/ArtMethod");
    //两个flag为public static final native的Java方法
    jmethodID nativeOffsetId = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    jmethodID nativeOffset2Id = env->GetStaticMethodID(clazz, "nativeOffset2", "()V");
    //获取两个方法的artMethod指针
    void *nativeOffset = GetArtMethod(env, clazz, nativeOffsetId);
    void *nativeOffset2 = GetArtMethod(env, clazz, nativeOffset2Id);
    //计算两个方法artMethod内存地址差值
    ArtMethodEnv.art_method_size = (size_t) nativeOffset2 - (size_t) nativeOffset;

    // calc native offset
    auto artMethod = reinterpret_cast<uintptr_t *>(nativeOffset);
    for (int i = 0; i < ArtMethodEnv.art_method_size; ++i) {
        if (reinterpret_cast<void *>(artMethod[i]) == native_offset) {
            ArtMethodEnv.art_method_native_offset = i;
            break;
        }
    }

    uint32_t flags = 0x0;
    flags = flags | kAccPublic;
    flags = flags | kAccStatic;
    flags = flags | kAccNative;
    flags = flags | kAccFinal;

    if (api_level >= __ANDROID_API_Q__/*Android sdk29*/) {
        flags = flags | kAccPublicApi;
    }


//    char flagsNum[100];
//    snprintf(flagsNum, sizeof(flagsNum), "获取到的flags为：%u", flags);
//    const char* strflags = flagsNum;
//    log_print_debug(strflags);


    char *start = reinterpret_cast<char *>(artMethod);
    for (int i = 1; i < ArtMethodEnv.art_method_size; ++i) {
        auto value = *(uint32_t *) (start + i * sizeof(uint32_t));
        /*char sizeNum[100];
        snprintf(sizeNum, sizeof(sizeNum), "获取到的value为：%u", value);
        const char* str = sizeNum;
        log_print_debug(str);
*/
        if (value == flags ) {
            ArtMethodEnv.art_method_flags_offset = i * sizeof(uint32_t);
            ArtMethodEnv.art_method_dex_code_item_offset = (i + 1) * sizeof(uint32_t);
            break;
        }
    }
}