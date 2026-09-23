#include <android/log.h>
#include <cstdio>
#include <cstdarg>
#include <ctime>
#include <pthread.h>

#define TAG "VmCore"

#if 1
#define log_print_error(...) \
    do { \
        __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); \
        ytx_native_log(__VA_ARGS__); \
    } while (0)
#define log_print_debug(...) \
    do { \
        __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__); \
        ytx_native_log(__VA_ARGS__); \
    } while (0)
#else
#define log_print_error(...)
#endif

#define ALOGE(...) log_print_error(__VA_ARGS__)
#define ALOGD(...) log_print_debug(__VA_ARGS__)

// ⭐ v2.2：native 日志同时写 blackbox.log（方便文件查看，logcat 常被清）
static inline void ytx_native_log(const char *fmt, ...) {
    static pthread_mutex_t sLock = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&sLock);
    FILE *f = fopen("/storage/emulated/0/MT2/apks/unpackcloud/logs/blackbox.log", "a");
    if (f) {
        time_t t = time(nullptr);
        struct tm tmv;
        localtime_r(&t, &tmv);
        char ts[32];
        strftime(ts, sizeof(ts), "%m-%d %H:%M:%S", &tmv);
        fprintf(f, "%s [native] ", ts);
        va_list ap;
        va_start(ap, fmt);
        vfprintf(f, fmt, ap);
        va_end(ap);
        fprintf(f, "\n");
        fclose(f);
    }
    pthread_mutex_unlock(&sLock);
}

#ifndef SPEED_LOG_H
#define SPEED_LOG_H 1

#endif
