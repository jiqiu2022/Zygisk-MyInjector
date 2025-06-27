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
    
    enum class InjectionMethod {
        STANDARD = 0,
        RIRU = 1,
        CUSTOM_LINKER = 2
    };
    
    struct GadgetConfig {
        std::string address = "0.0.0.0";
        int port = 27042;
        std::string onPortConflict = "fail";
        std::string onLoad = "wait";
        std::string gadgetName = "libgadget.so";
    };
    
    struct AppConfig {
        bool enabled = false;
        InjectionMethod injectionMethod = InjectionMethod::STANDARD;
        std::vector<SoFile> soFiles;
        GadgetConfig* gadgetConfig = nullptr;
    };
    
    struct ModuleConfig {
        bool enabled = true;
        bool hideInjection = false;
        int injectionDelay = 2; // Default 2 seconds
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
    
    // Get injection method for specific app
    InjectionMethod getAppInjectionMethod(const std::string& packageName);
    
    // Get injection delay in seconds
    int getInjectionDelay();
}

#endif // CONFIG_H