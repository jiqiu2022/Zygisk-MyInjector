package com.jiqiu.configapp;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {
    private static final String TAG = "ConfigManager";
    public static final String MODULE_PATH = "/data/adb/modules/zygisk-myinjector";
    public static final String CONFIG_FILE = MODULE_PATH + "/config.json";
    public static final String SO_STORAGE_DIR = MODULE_PATH + "/so_files";
    
    private final Context context;
    private final Gson gson;
    private ModuleConfig config;
    
    static {
        // Configure Shell to use root
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10));
    }
    
    public ConfigManager(Context context) {
        this.context = context;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadConfig();
    }
    
    public boolean isRootAvailable() {
        return Shell.getShell().isRoot();
    }
    
    public void ensureModuleDirectories() {
        Shell.cmd("mkdir -p " + MODULE_PATH).exec();
        Shell.cmd("mkdir -p " + SO_STORAGE_DIR).exec();
    }
    
    private void loadConfig() {
        Shell.Result result = Shell.cmd("cat " + CONFIG_FILE).exec();
        if (result.isSuccess() && !result.getOut().isEmpty()) {
            String json = String.join("\n", result.getOut());
            try {
                config = gson.fromJson(json, ModuleConfig.class);
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse config", e);
                config = new ModuleConfig();
            }
        } else {
            config = new ModuleConfig();
        }
    }
    
    public void saveConfig() {
        String json = gson.toJson(config);
        // Write to temp file first
        String tempFile = context.getCacheDir() + "/config.json";
        try {
            java.io.FileWriter writer = new java.io.FileWriter(tempFile);
            writer.write(json);
            writer.close();
            
            // Copy to module directory with root
            Shell.cmd("cp " + tempFile + " " + CONFIG_FILE).exec();
            Shell.cmd("chmod 644 " + CONFIG_FILE).exec();
            
            // Clean up temp file
            new File(tempFile).delete();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save config", e);
        }
    }
    
    public boolean isAppEnabled(String packageName) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        return appConfig != null && appConfig.enabled;
    }
    
    public void setAppEnabled(String packageName, boolean enabled) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null) {
            appConfig = new AppConfig();
            config.perAppConfig.put(packageName, appConfig);
        }
        appConfig.enabled = enabled;
        saveConfig();
    }
    
    public List<SoFile> getAppSoFiles(String packageName) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(appConfig.soFiles);
    }
    
    public List<SoFile> getAllSoFiles() {
        if (config.globalSoFiles == null) {
            config.globalSoFiles = new ArrayList<>();
        }
        return new ArrayList<>(config.globalSoFiles);
    }
    
    public void addGlobalSoFile(String originalPath, boolean deleteOriginal) {
        if (config.globalSoFiles == null) {
            config.globalSoFiles = new ArrayList<>();
        }
        
        // Generate unique filename
        String fileName = new File(originalPath).getName();
        String storedPath = SO_STORAGE_DIR + "/" + System.currentTimeMillis() + "_" + fileName;
        
        // Copy SO file to our storage
        Shell.Result result = Shell.cmd("cp \"" + originalPath + "\" \"" + storedPath + "\"").exec();
        if (result.isSuccess()) {
            SoFile soFile = new SoFile();
            soFile.name = fileName;
            soFile.storedPath = storedPath;
            soFile.originalPath = originalPath;
            config.globalSoFiles.add(soFile);
            
            if (deleteOriginal) {
                Shell.cmd("rm \"" + originalPath + "\"").exec();
            }
            
            saveConfig();
        }
    }
    
    public void removeGlobalSoFile(SoFile soFile) {
        if (config.globalSoFiles == null) return;
        
        config.globalSoFiles.remove(soFile);
        // Delete the stored file
        Shell.cmd("rm \"" + soFile.storedPath + "\"").exec();
        saveConfig();
    }
    
    public void addSoFileToApp(String packageName, SoFile globalSoFile) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null) {
            appConfig = new AppConfig();
            config.perAppConfig.put(packageName, appConfig);
        }
        
        // Check if already added
        for (SoFile existing : appConfig.soFiles) {
            if (existing.storedPath.equals(globalSoFile.storedPath)) {
                return; // Already added
            }
        }
        
        // Add reference to the global SO file
        appConfig.soFiles.add(globalSoFile);
        saveConfig();
    }
    
    public void removeSoFileFromApp(String packageName, SoFile soFile) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null) return;
        
        appConfig.soFiles.removeIf(s -> s.storedPath.equals(soFile.storedPath));
        saveConfig();
    }
    
    public boolean getHideInjection() {
        return config.hideInjection;
    }
    
    public void setHideInjection(boolean hide) {
        config.hideInjection = hide;
        saveConfig();
    }
    
    // Data classes
    public static class ModuleConfig {
        public boolean enabled = true;
        public boolean hideInjection = false;
        public List<SoFile> globalSoFiles = new ArrayList<>();
        public Map<String, AppConfig> perAppConfig = new HashMap<>();
    }
    
    public static class AppConfig {
        public boolean enabled = false;
        public List<SoFile> soFiles = new ArrayList<>();
    }
    
    public static class SoFile {
        public String name;
        public String storedPath;
        public String originalPath;
        
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof SoFile) {
                return storedPath.equals(((SoFile) obj).storedPath);
            }
            return false;
        }
    }
}