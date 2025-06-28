#include "hack.h"
#include "config.h"
#include "log.h"
#include "mylinker.h"
#include <cstring>
#include <thread>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <errno.h>
#include <jni.h>

// External function from newriruhide.cpp
extern "C" void riru_hide(const char *name);

void load_so_file_standard(const char *game_data_dir, const Config::SoFile &soFile) {
    // Use original filename
    char so_path[512];
    snprintf(so_path, sizeof(so_path), "%s/files/%s", game_data_dir, soFile.name.c_str());
    
    // Check if file exists
    if (access(so_path, F_OK) != 0) {
        LOGE("SO file not found: %s", so_path);
        return;
    }
    
    // Load the SO file using standard dlopen (no hiding)
    void *handle = dlopen(so_path, RTLD_NOW | RTLD_LOCAL);
    if (handle) {
        LOGI("Successfully loaded SO via standard dlopen: %s", soFile.name.c_str());
    } else {
        LOGE("Failed to load SO via standard dlopen: %s - %s", so_path, dlerror());
    }
}

void load_so_file_riru(const char *game_data_dir, const Config::SoFile &soFile) {
    // Use original filename
    char so_path[512];
    snprintf(so_path, sizeof(so_path), "%s/files/%s", game_data_dir, soFile.name.c_str());
    
    // Check if file exists
    if (access(so_path, F_OK) != 0) {
        LOGE("SO file not found: %s", so_path);
        return;
    }
    
    // Load the SO file using dlopen (Riru method)
    void *handle = dlopen(so_path, RTLD_NOW | RTLD_LOCAL);
    if (handle) {
        LOGI("Successfully loaded SO via Riru: %s", soFile.name.c_str());
        
        // Hide if configured
        if (Config::shouldHideInjection()) {
            // Hide using the original name
            riru_hide(soFile.name.c_str());
            LOGI("Applied riru_hide to: %s", soFile.name.c_str());
        }
    } else {
        LOGE("Failed to load SO via Riru: %s - %s", so_path, dlerror());
    }
}

void load_so_file_custom_linker(const char *game_data_dir, const Config::SoFile &soFile, JavaVM *vm) {
    // Use original filename
    char so_path[512];
    snprintf(so_path, sizeof(so_path), "%s/files/%s", game_data_dir, soFile.name.c_str());
    
    // Check if file exists
    if (access(so_path, F_OK) != 0) {
        LOGE("SO file not found: %s", so_path);
        return;
    }
    
    // Load the SO file using custom linker
    if (mylinker_load_library(so_path, vm)) {
        LOGI("Successfully loaded SO via custom linker: %s", soFile.name.c_str());
        
        // Custom linker doesn't appear in maps, so no need to hide
        if (Config::shouldHideInjection()) {
            LOGI("Custom linker injection is inherently hidden");
        }
    } else {
        LOGE("Failed to load SO via custom linker: %s", so_path);
    }
}

void hack_thread_func(const char *game_data_dir, const char *package_name, JavaVM *vm) {
    LOGI("Hack thread started for package: %s", package_name);
    
    // Note: Delay is now handled in main thread before this thread is created
    LOGI("Starting injection immediately (delay already applied in main thread)");
    
    // Get injection method for this app
    Config::InjectionMethod method = Config::getAppInjectionMethod(package_name);
    const char* methodName = method == Config::InjectionMethod::CUSTOM_LINKER ? "Custom Linker" :
                             method == Config::InjectionMethod::RIRU ? "Riru" : "Standard";
    LOGI("Using injection method: %s", methodName);
    
    // Get SO files for this app
    auto soFiles = Config::getAppSoFiles(package_name);
    LOGI("Found %zu SO files to load", soFiles.size());
    
    // Load each SO file using the configured method
    for (const auto &soFile : soFiles) {
        // Skip config files
        if (soFile.name.find(".config.so") != std::string::npos) {
            LOGI("Skipping config file: %s", soFile.name.c_str());
            continue;
        }
        
        LOGI("Loading SO: %s (stored as: %s)", soFile.name.c_str(), soFile.storedPath.c_str());
        
        if (method == Config::InjectionMethod::CUSTOM_LINKER) {
            load_so_file_custom_linker(game_data_dir, soFile, vm);
        } else if (method == Config::InjectionMethod::RIRU) {
            load_so_file_riru(game_data_dir, soFile);
        } else {
            load_so_file_standard(game_data_dir, soFile);
        }
    }
    
    // Cleanup custom linker resources when done (if used)
    if (method == Config::InjectionMethod::CUSTOM_LINKER) {
        // Keep libraries loaded, don't cleanup
        LOGI("Custom linker injection completed, libraries remain loaded");
    }
}

void hack_prepare(const char *game_data_dir, const char *package_name, void *data, size_t length, JavaVM *vm) {
    LOGI("hack_prepare called for package: %s, dir: %s", package_name, game_data_dir);
    
    std::thread hack_thread(hack_thread_func, game_data_dir, package_name, vm);
    hack_thread.join();
}