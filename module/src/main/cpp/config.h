#ifndef CONFIG_H
#define CONFIG_H

#include <string>
#include <vector>
#include <unordered_map>

namespace Config {
    
    struct SoFile {
        std::string name;
        std::string storedPath;
        std::string originalPath;
    };
    
    struct AppConfig {
        bool enabled = false;
        std::vector<SoFile> soFiles;
    };
    
    struct ModuleConfig {
        bool enabled = true;
        bool hideInjection = false;
        std::unordered_map<std::string, AppConfig> perAppConfig;
    };
    
    // Read configuration from file
    ModuleConfig readConfig();
    
    // Check if app is enabled for injection
    bool isAppEnabled(const std::string& packageName);
    
    // Get SO files for specific app
    std::vector<SoFile> getAppSoFiles(const std::string& packageName);
    
    // Get hide injection setting
    bool shouldHideInjection();
}

#endif // CONFIG_H