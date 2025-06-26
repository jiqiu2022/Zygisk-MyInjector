#include "mylinker.h"
#include "elf_loader.h"
#include "common.h"
#include <unordered_map>
#include <memory>
#include <string>

static std::unordered_map<std::string, std::unique_ptr<ElfLoader>> loaded_libraries;

bool mylinker_load_library(const char* library_path, JavaVM* vm) {
    if (!library_path) {
        LOGE("Invalid library path");
        return false;
    }

    std::string path(library_path);
    
    if (loaded_libraries.find(path) != loaded_libraries.end()) {
        LOGI("Library already loaded: %s", library_path);
        return true;
    }

    auto loader = std::make_unique<ElfLoader>();
    if (!loader->LoadLibrary(library_path)) {
        LOGE("Failed to load library: %s", library_path);
        return false;
    }

    JNIEnv* env = nullptr;
    if (vm && vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        typedef jint (*JNI_OnLoad_t)(JavaVM*, void*);
        auto jni_onload = reinterpret_cast<JNI_OnLoad_t>(loader->GetSymbol("JNI_OnLoad"));
        if (jni_onload) {
            LOGI("Calling JNI_OnLoad");
            jni_onload(vm, nullptr);
        }
    }

    loaded_libraries[path] = std::move(loader);
    LOGI("Successfully loaded library: %s", library_path);
    return true;
}

void* mylinker_get_symbol(const char* library_path, const char* symbol_name) {
    if (!library_path || !symbol_name) {
        return nullptr;
    }

    auto it = loaded_libraries.find(library_path);
    if (it == loaded_libraries.end()) {
        LOGE("Library not loaded: %s", library_path);
        return nullptr;
    }

    return it->second->GetSymbol(symbol_name);
}

void mylinker_cleanup() {
    loaded_libraries.clear();
    LOGI("Cleaned up all loaded libraries");
}