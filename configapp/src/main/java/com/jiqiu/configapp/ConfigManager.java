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
                .setFlags(Shell.FLAG_REDIRECT_STDERR | Shell.FLAG_MOUNT_MASTER)
                .setTimeout(30));
    }
    
    public ConfigManager(Context context) {
        this.context = context;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        
        // Ensure we get root shell on creation
        Shell.getShell();
        
        loadConfig();
    }
    
    public boolean isRootAvailable() {
        return Shell.getShell().isRoot();
    }
    
    public void ensureModuleDirectories() {
        // Check root access first
        if (!isRootAvailable()) {
            Log.e(TAG, "Root access not available!");
            return;
        }
        
        // Create module directories
        Shell.Result result1 = Shell.cmd("mkdir -p " + MODULE_PATH).exec();
        if (!result1.isSuccess()) {
            Log.e(TAG, "Failed to create module directory: " + MODULE_PATH);
        }
        
        Shell.Result result2 = Shell.cmd("mkdir -p " + SO_STORAGE_DIR).exec();
        if (!result2.isSuccess()) {
            Log.e(TAG, "Failed to create SO storage directory: " + SO_STORAGE_DIR);
        }
        
        // Set permissions
        Shell.cmd("chmod 755 " + MODULE_PATH).exec();
        Shell.cmd("chmod 755 " + SO_STORAGE_DIR).exec();
        
        // Verify directories exist
        Shell.Result verify = Shell.cmd("ls -la " + MODULE_PATH).exec();
        if (verify.isSuccess()) {
            Log.i(TAG, "Module directory ready: " + String.join("\n", verify.getOut()));
        }
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
        
        // 自动部署或清理 SO 文件
        if (enabled) {
            deploySoFilesToApp(packageName);
        } else {
            cleanupAppSoFiles(packageName);
        }
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
        
        // Keep original filename
        String fileName = new File(originalPath).getName();
        String storedPath = SO_STORAGE_DIR + "/" + fileName;
        
        // Check if file already exists with same name
        for (SoFile existing : config.globalSoFiles) {
            if (existing.name.equals(fileName)) {
                Log.w(TAG, "SO file with same name already exists: " + fileName);
                return;
            }
        }
        
        // Ensure SO storage directory exists
        Shell.cmd("mkdir -p " + SO_STORAGE_DIR).exec();
        Shell.cmd("chmod 755 " + SO_STORAGE_DIR).exec();
        
        // Copy SO file to our storage
        Log.i(TAG, "Copying SO file from: " + originalPath + " to: " + storedPath);
        Shell.Result result = Shell.cmd("cp \"" + originalPath + "\" \"" + storedPath + "\"").exec();
        
        if (result.isSuccess()) {
            // Verify the file was actually copied
            Shell.Result verifyResult = Shell.cmd("test -f \"" + storedPath + "\" && echo 'exists'").exec();
            if (!verifyResult.isSuccess() || verifyResult.getOut().isEmpty()) {
                Log.e(TAG, "File copy appeared successful but file not found at: " + storedPath);
                return;
            }
            
            // Set proper permissions for SO file (readable and executable)
            Shell.Result chmodResult = Shell.cmd("chmod 755 \"" + storedPath + "\"").exec();
            if (!chmodResult.isSuccess()) {
                Log.e(TAG, "Failed to set permissions on SO file: " + String.join("\n", chmodResult.getErr()));
            }
            
            SoFile soFile = new SoFile();
            soFile.name = fileName;
            soFile.storedPath = storedPath;
            soFile.originalPath = originalPath;
            config.globalSoFiles.add(soFile);
            
            Log.i(TAG, "Successfully added SO file: " + fileName + " to storage");
            
            if (deleteOriginal) {
                Shell.cmd("rm \"" + originalPath + "\"").exec();
                Log.i(TAG, "Deleted original file: " + originalPath);
            }
            
            saveConfig();
        } else {
            Log.e(TAG, "Failed to copy SO file: " + String.join("\n", result.getErr()));
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
        
        // If app is enabled, deploy the new SO file
        if (appConfig.enabled) {
            deploySoFilesToApp(packageName);
        }
    }
    
    public void removeSoFileFromApp(String packageName, SoFile soFile) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null) return;
        
        appConfig.soFiles.removeIf(s -> s.storedPath.equals(soFile.storedPath));
        saveConfig();
        
        // If app is enabled, re-deploy to update SO files
        if (appConfig.enabled) {
            deploySoFilesToApp(packageName);
        }
    }
    
    public boolean getHideInjection() {
        return config.hideInjection;
    }
    
    public void setHideInjection(boolean hide) {
        config.hideInjection = hide;
        saveConfig();
    }
    
    public String getAppInjectionMethod(String packageName) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null) {
            return "standard"; // Default to standard
        }
        return appConfig.injectionMethod != null ? appConfig.injectionMethod : "standard";
    }
    
    public void setAppInjectionMethod(String packageName, String method) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null) {
            appConfig = new AppConfig();
            config.perAppConfig.put(packageName, appConfig);
        }
        appConfig.injectionMethod = method;
        saveConfig();
    }
    
    public int getInjectionDelay() {
        return config.injectionDelay;
    }
    
    public void setInjectionDelay(int delay) {
        config.injectionDelay = delay;
        saveConfig();
    }
    
    public GadgetConfig getAppGadgetConfig(String packageName) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null) {
            // If no app config, return global gadget config
            return config.globalGadgetConfig;
        }
        
        // If app is set to use global gadget, return global config
        if (appConfig.useGlobalGadget) {
            return config.globalGadgetConfig;
        }
        
        // Otherwise return app-specific gadget config
        return appConfig.gadgetConfig;
    }
    
    public GadgetConfig getGlobalGadgetConfig() {
        return config.globalGadgetConfig;
    }
    
    public void setGlobalGadgetConfig(GadgetConfig gadgetConfig) {
        config.globalGadgetConfig = gadgetConfig;
        saveConfig();
    }
    
    public boolean getAppUseGlobalGadget(String packageName) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null) {
            return true; // Default to use global
        }
        return appConfig.useGlobalGadget;
    }
    
    public void setAppUseGlobalGadget(String packageName, boolean useGlobal) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null) {
            appConfig = new AppConfig();
            config.perAppConfig.put(packageName, appConfig);
        }
        appConfig.useGlobalGadget = useGlobal;
        saveConfig();
    }
    
    public void setAppGadgetConfig(String packageName, GadgetConfig gadgetConfig) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null) {
            appConfig = new AppConfig();
            config.perAppConfig.put(packageName, appConfig);
        }
        
        // Remove old gadget from SO list if exists
        if (appConfig.gadgetConfig != null) {
            String oldGadgetName = appConfig.gadgetConfig.gadgetName;
            appConfig.soFiles.removeIf(soFile -> soFile.name.equals(oldGadgetName));
        }
        
        appConfig.gadgetConfig = gadgetConfig;
        
        // Add new gadget to SO list if configured
        if (gadgetConfig != null) {
            // Check if gadget SO file exists in global storage
            String gadgetPath = SO_STORAGE_DIR + "/" + gadgetConfig.gadgetName;
            Shell.Result checkResult = Shell.cmd("test -f \"" + gadgetPath + "\" && echo 'exists'").exec();
            
            if (checkResult.isSuccess() && !checkResult.getOut().isEmpty()) {
                // Add gadget as a SO file
                SoFile gadgetSoFile = new SoFile();
                gadgetSoFile.name = gadgetConfig.gadgetName;
                gadgetSoFile.storedPath = gadgetPath;
                gadgetSoFile.originalPath = gadgetPath;
                
                // Check if already in list
                boolean alreadyExists = false;
                for (SoFile soFile : appConfig.soFiles) {
                    if (soFile.name.equals(gadgetSoFile.name)) {
                        alreadyExists = true;
                        break;
                    }
                }
                
                if (!alreadyExists) {
                    appConfig.soFiles.add(gadgetSoFile);
                    Log.i(TAG, "Added gadget SO to app's SO list: " + gadgetSoFile.name);
                }
            } else {
                Log.w(TAG, "Gadget SO file not found in storage: " + gadgetPath);
                Log.w(TAG, "Please ensure " + gadgetConfig.gadgetName + " is added to SO library");
            }
        }
        
        saveConfig();
        
        // If app is enabled, deploy both gadget SO and config file
        if (appConfig.enabled) {
            if (gadgetConfig != null) {
                deployGadgetConfigFile(packageName, gadgetConfig);
            }
            // Re-deploy all SO files including gadget
            deploySoFilesToApp(packageName);
        }
    }
    
    private void deployGadgetConfigFile(String packageName, GadgetConfig gadgetConfig) {
        try {
            // Create gadget config JSON
            String configJson;
            if ("script".equals(gadgetConfig.mode)) {
                configJson = String.format(
                    "{\n" +
                    "  \"interaction\": {\n" +
                    "    \"type\": \"script\",\n" +
                    "    \"path\": \"%s\"\n" +
                    "  }\n" +
                    "}",
                    gadgetConfig.scriptPath
                );
            } else {
                configJson = String.format(
                    "{\n" +
                    "  \"interaction\": {\n" +
                    "    \"type\": \"listen\",\n" +
                    "    \"address\": \"%s\",\n" +
                    "    \"port\": %d,\n" +
                    "    \"on_port_conflict\": \"%s\",\n" +
                    "    \"on_load\": \"%s\"\n" +
                    "  }\n" +
                    "}",
                    gadgetConfig.address,
                    gadgetConfig.port,
                    gadgetConfig.onPortConflict,
                    gadgetConfig.onLoad
                );
            }
            
            // Write to temp file
            String tempFile = context.getCacheDir() + "/" + gadgetConfig.gadgetName + ".config";
            java.io.FileWriter writer = new java.io.FileWriter(tempFile);
            writer.write(configJson);
            writer.close();
            
            // Copy to app's files directory
            String filesDir = "/data/data/" + packageName + "/files";
            String gadgetConfigName = gadgetConfig.gadgetName.replace(".so", ".config.so");
            String targetPath = filesDir + "/" + gadgetConfigName;
            
            Shell.Result copyResult = Shell.cmd("cp " + tempFile + " " + targetPath).exec();
            if (copyResult.isSuccess()) {
                // Set permissions
                Shell.cmd("chmod 644 " + targetPath).exec();
                Log.i(TAG, "Deployed gadget config to: " + targetPath);
            } else {
                Log.e(TAG, "Failed to deploy gadget config: " + String.join("\n", copyResult.getErr()));
            }
            
            // Clean up temp file
            new java.io.File(tempFile).delete();
        } catch (Exception e) {
            Log.e(TAG, "Failed to create gadget config file", e);
        }
    }
    
    // Copy SO files directly to app's data directory
    private void deploySoFilesToApp(String packageName) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null || appConfig.soFiles.isEmpty()) {
            Log.w(TAG, "No SO files to deploy for: " + packageName);
            return;
        }
        
        // First check if we have root access
        if (!Shell.getShell().isRoot()) {
            Log.e(TAG, "No root access available!");
            return;
        }
        
        // Create files directory in app's data dir
        String filesDir = "/data/data/" + packageName + "/files";
        
        Log.i(TAG, "Deploying SO files to: " + filesDir);
        
        // Create directory without su -c for better compatibility
        Shell.Result mkdirResult = Shell.cmd("mkdir -p " + filesDir).exec();
        if (!mkdirResult.isSuccess()) {
            Log.e(TAG, "Failed to create directory: " + filesDir);
            Log.e(TAG, "Error: " + String.join("\n", mkdirResult.getErr()));
            return;
        }
        
        // Set proper permissions and ownership for the files directory
        Shell.cmd("chmod 771 " + filesDir).exec();
        
        // Get UID and GID for the package
        Shell.Result uidResult = Shell.cmd("stat -c %u /data/data/" + packageName).exec();
        String uid = "";
        if (uidResult.isSuccess() && !uidResult.getOut().isEmpty()) {
            uid = uidResult.getOut().get(0).trim();
            Log.i(TAG, "Package UID: " + uid);
            
            // Set ownership of files directory to match app
            Shell.Result chownDirResult = Shell.cmd("chown " + uid + ":" + uid + " \"" + filesDir + "\"").exec();
            if (!chownDirResult.isSuccess()) {
                Log.e(TAG, "Failed to set directory ownership: " + String.join("\n", chownDirResult.getErr()));
            }
            
            // Set SELinux context for the directory
            Shell.cmd("chcon u:object_r:app_data_file:s0 \"" + filesDir + "\"").exec();
        } else {
            Log.e(TAG, "Failed to get package UID");
        }
        
        // Copy each SO file configured for this app
        for (SoFile soFile : appConfig.soFiles) {
            // Use original filename
            String destPath = filesDir + "/" + soFile.name;
            
            // Check if source file exists
            Shell.Result checkResult = Shell.cmd("test -f \"" + soFile.storedPath + "\" && echo 'exists'").exec();
            if (!checkResult.isSuccess() || checkResult.getOut().isEmpty()) {
                Log.e(TAG, "Source SO file not found: " + soFile.storedPath);
                // Log more details about the missing file
                Shell.Result lsResult = Shell.cmd("ls -la \"" + SO_STORAGE_DIR + "\"").exec();
                Log.e(TAG, "Contents of SO storage dir: " + String.join("\n", lsResult.getOut()));
                continue;
            }
            
            Log.i(TAG, "Copying: " + soFile.storedPath + " to " + destPath);
            
            // First, ensure the destination directory exists and has proper permissions
            Shell.cmd("mkdir -p \"" + filesDir + "\"").exec();
            Shell.cmd("chmod 755 \"" + filesDir + "\"").exec();
            
            // Copy file using cp with force flag
            Shell.Result result = Shell.cmd("cp -f \"" + soFile.storedPath + "\" \"" + destPath + "\"").exec();
            
            if (!result.isSuccess()) {
                Log.e(TAG, "Failed with cp, trying cat method");
                Log.e(TAG, "cp error: " + String.join("\n", result.getErr()));
                // Fallback to cat method
                result = Shell.cmd("cat \"" + soFile.storedPath + "\" > \"" + destPath + "\"").exec();
                
                if (!result.isSuccess()) {
                    Log.e(TAG, "Also failed with cat method");
                    Log.e(TAG, "cat error: " + String.join("\n", result.getErr()));
                }
            }
            
            // Set permissions - SO files need to be readable and executable
            Shell.Result chmodResult = Shell.cmd("chmod 755 \"" + destPath + "\"").exec();
            if (!chmodResult.isSuccess()) {
                Log.e(TAG, "Failed to set permissions: " + String.join("\n", chmodResult.getErr()));
            }
            
            // Set ownership to match the app's UID
            if (!uid.isEmpty()) {
                Shell.Result chownResult = Shell.cmd("chown " + uid + ":" + uid + " \"" + destPath + "\"").exec();
                if (!chownResult.isSuccess()) {
                    Log.e(TAG, "Failed to set ownership: " + String.join("\n", chownResult.getErr()));
                    // Try alternative method
                    Shell.cmd("chown " + uid + ".app_" + uid + " \"" + destPath + "\"").exec();
                }
            }
            
            // Set SELinux context to match app's data files
            Shell.Result contextResult = Shell.cmd("chcon u:object_r:app_data_file:s0 \"" + destPath + "\"").exec();
            if (!contextResult.isSuccess()) {
                Log.w(TAG, "Failed to set SELinux context (this may be normal on some devices)");
            }
            
            // Verify the file was copied with correct permissions
            Shell.Result verifyResult = Shell.cmd("ls -laZ \"" + destPath + "\" 2>/dev/null").exec();
            if (verifyResult.isSuccess() && !verifyResult.getOut().isEmpty()) {
                Log.i(TAG, "Successfully deployed: " + String.join(" ", verifyResult.getOut()));
            } else {
                // Fallback verification without SELinux context
                verifyResult = Shell.cmd("ls -la \"" + destPath + "\" 2>/dev/null").exec();
                if (verifyResult.isSuccess() && !verifyResult.getOut().isEmpty()) {
                    Log.i(TAG, "Successfully deployed: " + String.join(" ", verifyResult.getOut()));
                } else {
                    Log.e(TAG, "Failed to verify SO file copy: " + destPath);
                }
            }
        }
        
        Log.i(TAG, "Deployment complete for: " + packageName);
        
        // Deploy gadget config if configured
        ConfigManager.GadgetConfig gadgetToUse = getAppGadgetConfig(packageName);
        if (gadgetToUse != null) {
            deployGadgetConfigFile(packageName, gadgetToUse);
        }
    }
    
    // Clean up deployed SO files when app is disabled
    private void cleanupAppSoFiles(String packageName) {
        AppConfig appConfig = config.perAppConfig.get(packageName);
        if (appConfig == null || appConfig.soFiles.isEmpty()) {
            Log.w(TAG, "No SO files to clean up for: " + packageName);
            return;
        }
        
        // First check if we have root access
        if (!Shell.getShell().isRoot()) {
            Log.e(TAG, "No root access available!");
            return;
        }
        
        String filesDir = "/data/data/" + packageName + "/files";
        
        // Only delete the SO files we deployed, not the entire directory
        for (SoFile soFile : appConfig.soFiles) {
            // Use original filename
            String filePath = filesDir + "/" + soFile.name;
            
            Log.i(TAG, "Cleaning up: " + filePath);
            
            // Check if file exists before trying to delete
            Shell.Result checkResult = Shell.cmd("test -f \"" + filePath + "\" && echo 'exists'").exec();
            if (checkResult.isSuccess() && !checkResult.getOut().isEmpty()) {
                // Try to remove the file
                Shell.Result result = Shell.cmd("rm -f \"" + filePath + "\"").exec();
                
                // Verify deletion
                Shell.Result verifyResult = Shell.cmd("test -f \"" + filePath + "\" && echo 'still_exists'").exec();
                if (!verifyResult.isSuccess() || verifyResult.getOut().isEmpty()) {
                    Log.i(TAG, "Successfully deleted SO file: " + filePath);
                } else {
                    Log.e(TAG, "Failed to delete SO file: " + filePath);
                    // Try with su -c
                    Shell.cmd("su -c 'rm -f \"" + filePath + "\"'").exec();
                }
            } else {
                Log.w(TAG, "SO file not found for cleanup: " + filePath);
            }
        }
        
        // Clean up gadget config file if exists
        ConfigManager.GadgetConfig gadgetToUse = getAppGadgetConfig(packageName);
        if (gadgetToUse != null) {
            String gadgetConfigName = gadgetToUse.gadgetName.replace(".so", ".config.so");
            String configPath = filesDir + "/" + gadgetConfigName;
            
            Shell.Result checkConfigResult = Shell.cmd("test -f \"" + configPath + "\" && echo 'exists'").exec();
            if (checkConfigResult.isSuccess() && !checkConfigResult.getOut().isEmpty()) {
                Shell.Result deleteResult = Shell.cmd("rm -f \"" + configPath + "\"").exec();
                if (deleteResult.isSuccess()) {
                    Log.i(TAG, "Deleted gadget config: " + configPath);
                } else {
                    // Try with su -c
                    Shell.cmd("su -c 'rm -f \"" + configPath + "\"'").exec();
                }
            }
        }
        
        Log.i(TAG, "Cleanup complete for: " + packageName);
    }
    
    // Deploy SO files for all enabled apps
    public void deployAllSoFiles() {
        for (Map.Entry<String, AppConfig> entry : config.perAppConfig.entrySet()) {
            if (entry.getValue().enabled) {
                deploySoFilesToApp(entry.getKey());
            }
        }
    }
    
    // Data classes
    public static class ModuleConfig {
        public boolean enabled = true;
        public boolean hideInjection = false;
        public int injectionDelay = 2; // Default 2 seconds
        public List<SoFile> globalSoFiles = new ArrayList<>();
        public Map<String, AppConfig> perAppConfig = new HashMap<>();
        public GadgetConfig globalGadgetConfig = null; // Global gadget configuration
    }
    
    public static class AppConfig {
        public boolean enabled = false;
        public List<SoFile> soFiles = new ArrayList<>();
        public String injectionMethod = "standard"; // "standard", "riru" or "custom_linker"
        public GadgetConfig gadgetConfig = null;
        public boolean useGlobalGadget = true; // Whether to use global gadget settings
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
    
    public static class GadgetConfig {
        public String mode = "server"; // "server" or "script"
        // Server mode config
        public String address = "0.0.0.0";
        public int port = 27042;
        public String onPortConflict = "fail";
        public String onLoad = "wait";
        // Script mode config
        public String scriptPath = "/data/local/tmp/script.js";
        // Common config
        public String gadgetName = "libgadget.so";
    }
}