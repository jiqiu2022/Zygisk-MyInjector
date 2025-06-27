#include "config.h"
#include <fstream>
#include <sstream>
#include <android/log.h>

#define LOG_TAG "MyInjector"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace Config {
    
    static ModuleConfig g_config;
    static bool g_configLoaded = false;
    
    // Simple JSON parser for our specific format
    std::string extractValue(const std::string& json, const std::string& key) {
        size_t keyPos = json.find("\"" + key + "\"");
        if (keyPos == std::string::npos) return "";
        
        size_t colonPos = json.find(":", keyPos);
        if (colonPos == std::string::npos) return "";
        
        size_t valueStart = json.find_first_not_of(" \t\n", colonPos + 1);
        if (valueStart == std::string::npos) return "";
        
        if (json[valueStart] == '"') {
            // String value
            size_t valueEnd = json.find('"', valueStart + 1);
            if (valueEnd == std::string::npos) return "";
            return json.substr(valueStart + 1, valueEnd - valueStart - 1);
        } else if (json[valueStart] == 't' || json[valueStart] == 'f') {
            // Boolean value
            return (json.substr(valueStart, 4) == "true") ? "true" : "false";
        } else {
            // Number value
            size_t valueEnd = json.find_first_of(",} \t\n", valueStart);
            if (valueEnd == std::string::npos) {
                return json.substr(valueStart);
            }
            return json.substr(valueStart, valueEnd - valueStart);
        }
        
        return "";
    }
    
    void parseAppConfig(const std::string& packageName, const std::string& appJson) {
        AppConfig appConfig;
        
        // Parse enabled
        std::string enabledStr = extractValue(appJson, "enabled");
        appConfig.enabled = (enabledStr == "true");
        
        // Parse injection method
        std::string methodStr = extractValue(appJson, "injectionMethod");
        if (methodStr == "2" || methodStr == "custom_linker") {
            appConfig.injectionMethod = InjectionMethod::CUSTOM_LINKER;
        } else if (methodStr == "1" || methodStr == "riru") {
            appConfig.injectionMethod = InjectionMethod::RIRU;
        } else {
            appConfig.injectionMethod = InjectionMethod::STANDARD;
        }
        
        // Parse soFiles array
        size_t soFilesPos = appJson.find("\"soFiles\"");
        if (soFilesPos != std::string::npos) {
            size_t arrayStart = appJson.find("[", soFilesPos);
            size_t arrayEnd = appJson.find("]", arrayStart);
            
            if (arrayStart != std::string::npos && arrayEnd != std::string::npos) {
                std::string soFilesArray = appJson.substr(arrayStart + 1, arrayEnd - arrayStart - 1);
                
                // Parse each SO file object
                size_t objStart = 0;
                while ((objStart = soFilesArray.find("{", objStart)) != std::string::npos) {
                    size_t objEnd = soFilesArray.find("}", objStart);
                    if (objEnd == std::string::npos) break;
                    
                    std::string soFileObj = soFilesArray.substr(objStart, objEnd - objStart + 1);
                    
                    SoFile soFile;
                    soFile.name = extractValue(soFileObj, "name");
                    soFile.storedPath = extractValue(soFileObj, "storedPath");
                    soFile.originalPath = extractValue(soFileObj, "originalPath");
                    
                    if (!soFile.storedPath.empty()) {
                        appConfig.soFiles.push_back(soFile);
                        LOGD("Added SO file: %s at %s", soFile.name.c_str(), soFile.storedPath.c_str());
                    }
                    
                    objStart = objEnd + 1;
                }
            }
        }
        
        // Parse gadgetConfig if exists
        size_t gadgetPos = appJson.find("\"gadgetConfig\"");
        if (gadgetPos != std::string::npos) {
            size_t gadgetObjStart = appJson.find("{", gadgetPos);
            size_t gadgetObjEnd = appJson.find("}", gadgetObjStart);
            
            if (gadgetObjStart != std::string::npos && gadgetObjEnd != std::string::npos) {
                std::string gadgetObj = appJson.substr(gadgetObjStart, gadgetObjEnd - gadgetObjStart + 1);
                
                GadgetConfig* gadgetConfig = new GadgetConfig();
                
                std::string address = extractValue(gadgetObj, "address");
                if (!address.empty()) gadgetConfig->address = address;
                
                std::string portStr = extractValue(gadgetObj, "port");
                if (!portStr.empty()) gadgetConfig->port = std::stoi(portStr);
                
                std::string onPortConflict = extractValue(gadgetObj, "onPortConflict");
                if (!onPortConflict.empty()) gadgetConfig->onPortConflict = onPortConflict;
                
                std::string onLoad = extractValue(gadgetObj, "onLoad");
                if (!onLoad.empty()) gadgetConfig->onLoad = onLoad;
                
                std::string gadgetName = extractValue(gadgetObj, "gadgetName");
                if (!gadgetName.empty()) gadgetConfig->gadgetName = gadgetName;
                
                appConfig.gadgetConfig = gadgetConfig;
                LOGD("Loaded gadget config: %s:%d, name: %s", 
                     gadgetConfig->address.c_str(), gadgetConfig->port, gadgetConfig->gadgetName.c_str());
            }
        }
        
        g_config.perAppConfig[packageName] = appConfig;
        const char* methodName = appConfig.injectionMethod == InjectionMethod::CUSTOM_LINKER ? "custom_linker" :
                                 appConfig.injectionMethod == InjectionMethod::RIRU ? "riru" : "standard";
        LOGD("Loaded config for app: %s, enabled: %d, method: %s, SO files: %zu, gadget: %s", 
             packageName.c_str(), appConfig.enabled, methodName, appConfig.soFiles.size(),
             appConfig.gadgetConfig ? "yes" : "no");
    }
    
    ModuleConfig readConfig() {
        if (g_configLoaded) {
            return g_config;
        }
        
        const char* configPath = "/data/adb/modules/zygisk-myinjector/config.json";
        std::ifstream file(configPath);
        
        if (!file.is_open()) {
            LOGE("Failed to open config file: %s", configPath);
            g_configLoaded = true;
            return g_config;
        }
        
        std::stringstream buffer;
        buffer << file.rdbuf();
        std::string json = buffer.str();
        file.close();
        
        // Parse global settings
        std::string enabledStr = extractValue(json, "enabled");
        g_config.enabled = (enabledStr != "false");
        
        std::string hideStr = extractValue(json, "hideInjection");
        g_config.hideInjection = (hideStr == "true");
        
        std::string delayStr = extractValue(json, "injectionDelay");
        if (!delayStr.empty()) {
            g_config.injectionDelay = std::stoi(delayStr);
        }
        
        LOGD("Module enabled: %d, hide injection: %d, injection delay: %d", 
             g_config.enabled, g_config.hideInjection, g_config.injectionDelay);
        
        // Parse perAppConfig
        size_t perAppPos = json.find("\"perAppConfig\"");
        if (perAppPos != std::string::npos) {
            size_t objStart = json.find("{", perAppPos + 14);
            size_t objEnd = json.rfind("}");
            
            if (objStart != std::string::npos && objEnd != std::string::npos) {
                std::string perAppObj = json.substr(objStart + 1, objEnd - objStart - 1);
                
                // Find each package config
                size_t pos = 0;
                while (pos < perAppObj.length()) {
                    // Find package name
                    size_t pkgStart = perAppObj.find("\"", pos);
                    if (pkgStart == std::string::npos) break;
                    
                    size_t pkgEnd = perAppObj.find("\"", pkgStart + 1);
                    if (pkgEnd == std::string::npos) break;
                    
                    std::string packageName = perAppObj.substr(pkgStart + 1, pkgEnd - pkgStart - 1);
                    
                    // Find app config object
                    size_t appObjStart = perAppObj.find("{", pkgEnd);
                    if (appObjStart == std::string::npos) break;
                    
                    // Find matching closing brace
                    int braceCount = 1;
                    size_t appObjEnd = appObjStart + 1;
                    while (appObjEnd < perAppObj.length() && braceCount > 0) {
                        if (perAppObj[appObjEnd] == '{') braceCount++;
                        else if (perAppObj[appObjEnd] == '}') braceCount--;
                        appObjEnd++;
                    }
                    
                    if (braceCount == 0) {
                        std::string appConfigStr = perAppObj.substr(appObjStart, appObjEnd - appObjStart);
                        parseAppConfig(packageName, appConfigStr);
                    }
                    
                    pos = appObjEnd;
                }
            }
        }
        
        g_configLoaded = true;
        return g_config;
    }
    
    bool isAppEnabled(const std::string& packageName) {
        if (!g_configLoaded) {
            readConfig();
        }
        
        auto it = g_config.perAppConfig.find(packageName);
        if (it != g_config.perAppConfig.end()) {
            return it->second.enabled;
        }
        return false;
    }
    
    std::vector<SoFile> getAppSoFiles(const std::string& packageName) {
        if (!g_configLoaded) {
            readConfig();
        }
        
        auto it = g_config.perAppConfig.find(packageName);
        if (it != g_config.perAppConfig.end()) {
            LOGD("Found app config for %s with %zu SO files", packageName.c_str(), it->second.soFiles.size());
            return it->second.soFiles;
        }
        LOGD("No app config found for %s", packageName.c_str());
        return {};
    }
    
    bool shouldHideInjection() {
        if (!g_configLoaded) {
            readConfig();
        }
        return g_config.hideInjection;
    }
    
    InjectionMethod getAppInjectionMethod(const std::string& packageName) {
        if (!g_configLoaded) {
            readConfig();
        }
        
        auto it = g_config.perAppConfig.find(packageName);
        if (it != g_config.perAppConfig.end()) {
            return it->second.injectionMethod;
        }
        return InjectionMethod::STANDARD;
    }
    
    int getInjectionDelay() {
        if (!g_configLoaded) {
            readConfig();
        }
        return g_config.injectionDelay;
    }
}