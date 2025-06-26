#pragma once

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((visibility("default"))) bool mylinker_load_library(const char* library_path, JavaVM* vm);

__attribute__((visibility("default"))) void* mylinker_get_symbol(const char* library_path, const char* symbol_name);

__attribute__((visibility("default"))) void mylinker_cleanup();

#ifdef __cplusplus
}
#endif