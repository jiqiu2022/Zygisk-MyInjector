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
#include "log.h"
extern "C" {
int riru_hide(const char *name) EXPORT;
}

#endif //ZYGISK_MYINJECTOR_NEWRIRUHIDE_H
