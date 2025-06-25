#include "hack.h"
#include "config.h"
#include "log.h"
#include <cstring>
#include <thread>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <errno.h>

// External function from newriruhide.cpp
extern "C" void riru_hide(const char *name);

void load_so_file(const char *game_data_dir, const Config::SoFile &soFile) {
    // Extract the mapped filename from storedPath (e.g., "1750851324251_libmylib.so")
    const char *mapped_name = strrchr(soFile.storedPath.c_str(), '/');
    if (!mapped_name) {
        mapped_name = soFile.storedPath.c_str();
    } else {
        mapped_name++; // Skip the '/'
    }
    
    // The file should already be in app's files directory
    char so_path[512];
    snprintf(so_path, sizeof(so_path), "%s/files/%s", game_data_dir, mapped_name);
    
    // Check if file exists
    if (access(so_path, F_OK) != 0) {
        LOGE("SO file not found: %s", so_path);
        return;
    }
    
    // Load the SO file
    void *handle = dlopen(so_path, RTLD_NOW | RTLD_LOCAL);
    if (handle) {
        LOGI("Successfully loaded SO: %s (mapped: %s)", soFile.name.c_str(), mapped_name);
        
        // Hide if configured
        if (Config::shouldHideInjection()) {
            // Hide using the mapped name since that's what we loaded
            riru_hide(mapped_name);
            LOGI("Applied riru_hide to: %s", mapped_name);
        }
    } else {
        LOGE("Failed to load SO: %s - %s", so_path, dlerror());
    }
}

void hack_thread_func(const char *game_data_dir, const char *package_name) {
    LOGI("Hack thread started for package: %s", package_name);
    
    // Wait a bit for app to initialize and files to be copied
    sleep(2);
    
    // Get SO files for this app
    auto soFiles = Config::getAppSoFiles(package_name);
    LOGI("Found %zu SO files to load", soFiles.size());
    
    // Load each SO file
    for (const auto &soFile : soFiles) {
        LOGI("Loading SO: %s (stored as: %s)", soFile.name.c_str(), soFile.storedPath.c_str());
        load_so_file(game_data_dir, soFile);
    }
}

void hack_prepare(const char *game_data_dir, const char *package_name, void *data, size_t length) {
    LOGI("hack_prepare called for package: %s, dir: %s", package_name, game_data_dir);
    
    std::thread hack_thread(hack_thread_func, game_data_dir, package_name);
    hack_thread.detach();
}