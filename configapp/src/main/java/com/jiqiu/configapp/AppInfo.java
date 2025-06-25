package com.jiqiu.configapp;

import android.graphics.drawable.Drawable;

/**
 * 应用程序信息数据模型
 */
public class AppInfo {
    private String appName;        // 应用名称
    private String packageName;    // 包名
    private Drawable appIcon;      // 应用图标
    private boolean isSystemApp;   // 是否为系统应用
    private boolean isEnabled;     // 是否启用注入

    public AppInfo(String appName, String packageName, Drawable appIcon, boolean isSystemApp) {
        this.appName = appName;
        this.packageName = packageName;
        this.appIcon = appIcon;
        this.isSystemApp = isSystemApp;
        this.isEnabled = false; // 默认不启用注入
    }

    // Getter 和 Setter 方法
    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public Drawable getAppIcon() {
        return appIcon;
    }

    public void setAppIcon(Drawable appIcon) {
        this.appIcon = appIcon;
    }

    public boolean isSystemApp() {
        return isSystemApp;
    }

    public void setSystemApp(boolean systemApp) {
        isSystemApp = systemApp;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    @Override
    public String toString() {
        return "AppInfo{" +
                "appName='" + appName + '\'' +
                ", packageName='" + packageName + '\'' +
                ", isSystemApp=" + isSystemApp +
                ", isEnabled=" + isEnabled +
                '}';
    }
}
