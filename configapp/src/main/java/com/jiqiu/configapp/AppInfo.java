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
    private long installTime;      // 安装/更新时间（毫秒，实际存储lastUpdateTime）
    private boolean iconLoaded;    // 图标是否已加载

    public AppInfo(String appName, String packageName, Drawable appIcon, boolean isSystemApp) {
        this.appName = appName;
        this.packageName = packageName;
        this.appIcon = appIcon;
        this.isSystemApp = isSystemApp;
        this.isEnabled = false; // 默认不启用注入
        this.installTime = 0;
        this.iconLoaded = false;
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

    public long getInstallTime() {
        return installTime;
    }

    public void setInstallTime(long installTime) {
        this.installTime = installTime;
    }

    public boolean isIconLoaded() {
        return iconLoaded;
    }

    public void setIconLoaded(boolean iconLoaded) {
        this.iconLoaded = iconLoaded;
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
