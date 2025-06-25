#include "hack.h"
#include "config.h"
#include "log.h"
#include <cstring>
#include <thread>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>

void load_so_file(const char *game_data_dir, const Config::SoFile &soFile) {
    char dest_path[512];
    snprintf(dest_path, sizeof(dest_path), "%s/files/%s", game_data_dir, soFile.name.c_str());
    
    // Copy SO file from storage to app directory
    int src_fd = open(soFile.storedPath.c_str(), O_RDONLY);
    if (src_fd < 0) {
        LOGE("Failed to open source SO: %s", soFile.storedPath.c_str());
        return;
    }
    
    int dest_fd = open(dest_path, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (dest_fd < 0) {
        LOGE("Failed to create dest SO: %s", dest_path);
        close(src_fd);
        return;
    }
    
    char buffer[4096];
    ssize_t bytes;
    while ((bytes = read(src_fd, buffer, sizeof(buffer))) > 0) {
        if (write(dest_fd, buffer, bytes) != bytes) {
            LOGE("Failed to write SO file");
            close(src_fd);
            close(dest_fd);
            return;
        }
    }
    
    close(src_fd);
    close(dest_fd);
    chmod(dest_path, 0755);
    
    // Load the SO file
    void *handle = dlopen(dest_path, RTLD_NOW | RTLD_LOCAL);
    if (handle) {
        LOGI("Successfully loaded SO: %s", soFile.name.c_str());
        
        // Hide if configured
        if (Config::shouldHideInjection()) {
            // Call hide function if available
            void (*hide_func)(const char*) = (void(*)(const char*))dlsym(handle, "riru_hide");
            if (hide_func) {
                hide_func(soFile.name.c_str());
            }
        }
    } else {
        LOGE("Failed to load SO: %s - %s", dest_path, dlerror());
    }
}

void hack_thread_func(const char *game_data_dir, const char *package_name) {
    LOGI("Hack thread started for package: %s", package_name);
    
    // Wait a bit for app to initialize
    sleep(1);
    
    // Get SO files for this app
    auto soFiles = Config::getAppSoFiles(package_name);
    LOGI("Found %zu SO files to load", soFiles.size());
    
    // Load each SO file
    for (const auto &soFile : soFiles) {
        LOGI("Loading SO: %s", soFile.name.c_str());
        load_so_file(game_data_dir, soFile);
    }
}

void hack_prepare(const char *game_data_dir, void *data, size_t length) {
    // Get package name from game_data_dir
    // Format: /data/user/0/com.example.app or /data/data/com.example.app
    const char *package_name = nullptr;
    if (strstr(game_data_dir, "/data/user/")) {
        package_name = strrchr(game_data_dir, '/');
        if (package_name) package_name++;
    } else if (strstr(game_data_dir, "/data/data/")) {
        package_name = game_data_dir + strlen("/data/data/");
    }
    
    if (!package_name) {
        LOGE("Failed to extract package name from: %s", game_data_dir);
        return;
    }
    
    std::thread hack_thread(hack_thread_func, game_data_dir, package_name);
    hack_thread.detach();
}