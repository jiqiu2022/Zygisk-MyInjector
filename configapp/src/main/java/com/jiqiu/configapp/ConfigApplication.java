package com.jiqiu.configapp;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

/**
 * Application class for dynamic receiver registration
 * 动态注册 BroadcastReceiver，避免被第三方 app 发现
 */
public class ConfigApplication extends Application {
    private static final String TAG = "ConfigApplication";
    private static final String ACTION_APPLY_CONFIG = "com.jiqiu.configapp.APPLY_CONFIG";
    
    private ConfigApplyReceiver configReceiver;
    
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Application onCreate - registering receiver dynamically");
        
        // 动态注册 ConfigApplyReceiver
        configReceiver = new ConfigApplyReceiver();
        IntentFilter filter = new IntentFilter(ACTION_APPLY_CONFIG);
        
        // 使用 RECEIVER_NOT_EXPORTED 标志，明确表示不导出
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(configReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(configReceiver, filter);
        }
        Log.d(TAG, "Receiver registered dynamically (UID check: shell/root only)");
        Log.i(TAG, "ConfigApplyReceiver registered dynamically - invisible to third-party apps");
    }
    
    @Override
    public void onTerminate() {
        super.onTerminate();
        
        // 注销 receiver（注意：onTerminate 在真实设备上通常不会被调用，仅在模拟器中）
        if (configReceiver != null) {
            try {
                unregisterReceiver(configReceiver);
                Log.d(TAG, "Receiver unregistered");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Receiver was not registered or already unregistered");
            }
        }
    }
}
