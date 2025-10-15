package com.jiqiu.configapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Process;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

/**
 * BroadcastReceiver to apply configurations pushed from ADB
 * 接收来自 ADB shell 的广播以应用配置
 * 
 * 安全机制：动态注册 + UID 权限检查，只允许 shell(2000) 或 root(0) 调用
 */
public class ConfigApplyReceiver extends BroadcastReceiver {
    private static final String TAG = "ConfigApplyReceiver";
    
    // UID constants
    private static final int SHELL_UID = 2000;  // ADB shell user
    private static final int ROOT_UID = 0;       // Root user
    
    @Override
    public void onReceive(Context context, Intent intent) {
        // 权限检查：只允许 shell 或 root 用户发送广播
        int callingUid = Binder.getCallingUid();
        if (callingUid != SHELL_UID && callingUid != ROOT_UID) {
            Log.w(TAG, "Unauthorized broadcast attempt from UID: " + callingUid);
            Log.w(TAG, "Only shell (2000) or root (0) can send this broadcast");
            return;
        }
        
        Log.i(TAG, "Received config apply broadcast from authorized UID: " + callingUid);
        
        String action = intent.getAction();
        if (!"com.jiqiu.configapp.APPLY_CONFIG".equals(action)) {
            Log.w(TAG, "Unknown action: " + action);
            return;
        }
        
        // 获取广播参数
        String packageName = intent.getStringExtra("package_name");
        String tmpConfigPath = intent.getStringExtra("tmp_config_path");
        String tmpGadgetConfigPath = intent.getStringExtra("tmp_gadget_config_path");
        boolean deployOnly = intent.getBooleanExtra("deploy_only", false);
        
        Log.i(TAG, "Processing config for package: " + packageName);
        Log.i(TAG, "Config path: " + tmpConfigPath);
        Log.i(TAG, "Gadget config path: " + tmpGadgetConfigPath);
        Log.i(TAG, "Deploy only: " + deployOnly);
        
        if (packageName == null || packageName.isEmpty()) {
            Log.e(TAG, "Package name is required");
            return;
        }
        
        // 在后台线程处理，避免阻塞主线程
        new Thread(() -> {
            try {
                ConfigManager configManager = new ConfigManager(context);
                
                // 确保目录存在
                configManager.ensureModuleDirectories();
                
                // 如果提供了配置文件路径，复制到模块目录
                if (tmpConfigPath != null && !tmpConfigPath.isEmpty()) {
                    Shell.Result checkResult = Shell.cmd("test -f \"" + tmpConfigPath + "\" && echo 'exists'").exec();
                    if (checkResult.isSuccess() && !checkResult.getOut().isEmpty()) {
                        Log.i(TAG, "Copying main config: " + tmpConfigPath + " -> " + ConfigManager.CONFIG_FILE);
                        Shell.Result copyResult = Shell.cmd(
                            "cp \"" + tmpConfigPath + "\" \"" + ConfigManager.CONFIG_FILE + "\"",
                            "chmod 644 \"" + ConfigManager.CONFIG_FILE + "\""
                        ).exec();
                        
                        if (copyResult.isSuccess()) {
                            Log.i(TAG, "Main config copied successfully");
                            // 重新加载配置
                            configManager.reloadConfig();
                        } else {
                            Log.e(TAG, "Failed to copy main config: " + String.join("\n", copyResult.getErr()));
                        }
                    } else {
                        Log.w(TAG, "Main config file not found at: " + tmpConfigPath);
                    }
                }
                
                // 如果提供了 Gadget 配置文件，复制到应用数据目录
                if (tmpGadgetConfigPath != null && !tmpGadgetConfigPath.isEmpty()) {
                    Shell.Result checkResult = Shell.cmd("test -f \"" + tmpGadgetConfigPath + "\" && echo 'exists'").exec();
                    if (checkResult.isSuccess() && !checkResult.getOut().isEmpty()) {
                        String filesDir = "/data/data/" + packageName + "/files";
                        
                        // 从路径中提取文件名
                        String gadgetConfigFileName = tmpGadgetConfigPath.substring(tmpGadgetConfigPath.lastIndexOf('/') + 1);
                        String targetPath = filesDir + "/" + gadgetConfigFileName;
                        
                        Log.i(TAG, "Copying gadget config: " + tmpGadgetConfigPath + " -> " + targetPath);
                        
                        // 创建目录
                        Shell.cmd("mkdir -p \"" + filesDir + "\"").exec();
                        
                        Shell.Result copyResult = Shell.cmd(
                            "cp \"" + tmpGadgetConfigPath + "\" \"" + targetPath + "\"",
                            "chmod 644 \"" + targetPath + "\""
                        ).exec();
                        
                        if (copyResult.isSuccess()) {
                            Log.i(TAG, "Gadget config copied successfully");
                            
                            // 设置正确的所有权
                            Shell.Result uidResult = Shell.cmd("stat -c %u /data/data/" + packageName).exec();
                            if (uidResult.isSuccess() && !uidResult.getOut().isEmpty()) {
                                String uid = uidResult.getOut().get(0).trim();
                                Shell.cmd("chown " + uid + ":" + uid + " \"" + targetPath + "\"").exec();
                                Shell.cmd("chcon u:object_r:app_data_file:s0 \"" + targetPath + "\"").exec();
                            }
                        } else {
                            Log.e(TAG, "Failed to copy gadget config: " + String.join("\n", copyResult.getErr()));
                        }
                    } else {
                        Log.w(TAG, "Gadget config file not found at: " + tmpGadgetConfigPath);
                    }
                }
                
                // 如果不是仅部署模式，或者没有提供配置文件，执行部署
                if (!deployOnly || (tmpConfigPath == null && tmpGadgetConfigPath == null)) {
                    Log.i(TAG, "Deploying SO files for package: " + packageName);
                    configManager.deployForPackage(packageName);
                    Log.i(TAG, "Deployment completed for: " + packageName);
                } else {
                    Log.i(TAG, "Config updated, skipping deployment (deploy_only=true)");
                }
                
                // 清理临时文件
                if (tmpConfigPath != null && !tmpConfigPath.isEmpty()) {
                    Shell.cmd("rm -f \"" + tmpConfigPath + "\"").exec();
                }
                if (tmpGadgetConfigPath != null && !tmpGadgetConfigPath.isEmpty()) {
                    Shell.cmd("rm -f \"" + tmpGadgetConfigPath + "\"").exec();
                }
                
                Log.i(TAG, "Config application completed successfully");
                
            } catch (Exception e) {
                Log.e(TAG, "Error applying config", e);
            }
        }).start();
    }
}
