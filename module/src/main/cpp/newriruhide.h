//
// Created by Mac on 2024/11/15.
//

#ifndef ZYGISK_MYINJECTOR_NEWRIRUHIDE_H
#define ZYGISK_MYINJECTOR_NEWRIRUHIDE_H
#define EXPORT __attribute__((visibility("default"))) __attribute__((used))
#include <cinttypes>
#include <sys/mman.h>
#include <set>
#include <string_view>
#include "pmparser.h"
#include "android/log.h"

#ifndef LOG_TAG
#ifdef __LP64__
#define LOG_TAG    "Riru64"
#else
#define LOG_TAG    "Riru"
#endif
#endif

#ifndef NDEBUG
#define LOGV(...)  __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGV(...)
#define LOGD(...)
#endif
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define PLOGE(fmt, args...) LOGE(fmt " failed with %d: %s", ##args, errno, strerror(errno))
extern "C" {
int riru_hide(const std::set<std::string_view> &) EXPORT;
}

#endif //ZYGISK_MYINJECTOR_NEWRIRUHIDE_H
