package com.jiqiu.configapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 应用列表适配器
 */
public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.AppViewHolder> {
    
    private List<AppInfo> appList;
    private List<AppInfo> filteredAppList;
    private OnAppToggleListener onAppToggleListener;
    private OnAppClickListener onAppClickListener;
    
    public interface OnAppToggleListener {
        void onAppToggle(AppInfo appInfo, boolean isEnabled);
    }
    
    public interface OnAppClickListener {
        void onAppClick(AppInfo appInfo);
    }
    
    public AppListAdapter() {
        this.appList = new ArrayList<>();
        this.filteredAppList = new ArrayList<>();
    }
    
    public void setAppList(List<AppInfo> appList) {
        this.appList = appList;
        this.filteredAppList = new ArrayList<>(appList);
        notifyDataSetChanged();
    }
    
    public void setOnAppToggleListener(OnAppToggleListener listener) {
        this.onAppToggleListener = listener;
    }
    
    public void setOnAppClickListener(OnAppClickListener listener) {
        this.onAppClickListener = listener;
    }
    
    public void filterApps(String query, boolean hideSystemApps) {
        filteredAppList.clear();
        
        for (AppInfo app : appList) {
            // 过滤系统应用
            if (hideSystemApps && app.isSystemApp()) {
                continue;
            }
            
            // 搜索过滤
            if (query == null || query.isEmpty() || 
                app.getAppName().toLowerCase().contains(query.toLowerCase()) ||
                app.getPackageName().toLowerCase().contains(query.toLowerCase())) {
                filteredAppList.add(app);
            }
        }
        
        // 排序：已启用的应用在前面，然后按名称排序
        sortAppList();
        
        notifyDataSetChanged();
    }
    
    /**
     * 排序应用列表：已启用的应用显示在最前面，然后按更新时间排序
     */
    private void sortAppList() {
        Collections.sort(filteredAppList, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo app1, AppInfo app2) {
                // 首先按启用状态排序（已启用的在前）
                if (app1.isEnabled() != app2.isEnabled()) {
                    return app1.isEnabled() ? -1 : 1;
                }
                // 然后按更新时间排序（最近更新的在前，即降序）
                return Long.compare(app2.getInstallTime(), app1.getInstallTime());
            }
        });
    }
    
    /**
     * 重新排序并刷新列表（用于开关切换后）
     */
    public void refreshSort() {
        sortAppList();
        notifyDataSetChanged();
    }
    
    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new AppViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo appInfo = filteredAppList.get(position);
        holder.bind(appInfo);
    }
    
    @Override
    public int getItemCount() {
        return filteredAppList.size();
    }
    
    class AppViewHolder extends RecyclerView.ViewHolder {
        private ImageView appIcon;
        private TextView appName;
        private TextView packageName;
        private TextView systemAppLabel;
        private SwitchMaterial switchEnable;
        
        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.app_icon);
            appName = itemView.findViewById(R.id.app_name);
            packageName = itemView.findViewById(R.id.package_name);
            systemAppLabel = itemView.findViewById(R.id.system_app_label);
            switchEnable = itemView.findViewById(R.id.switch_enable);
        }
        
        public void bind(AppInfo appInfo) {
            // 使用 tag 标记当前绑定的包名，防止回收的 ViewHolder 显示错误图标
            appIcon.setTag(appInfo.getPackageName());
            
            // 延迟加载图标
            if (appInfo.getAppIcon() == null && !appInfo.isIconLoaded()) {
                // 设置默认图标
                appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
                appInfo.setIconLoaded(true);
                
                // 保存当前包名用于后续验证
                final String currentPackageName = appInfo.getPackageName();
                
                // 异步加载真实图标
                new Thread(() -> {
                    try {
                        android.content.pm.PackageManager pm = itemView.getContext().getPackageManager();
                        android.graphics.drawable.Drawable icon = pm.getApplicationIcon(currentPackageName);
                        appInfo.setAppIcon(icon);
                        
                        // 在主线程更新UI，但要检查 ViewHolder 是否仍然绑定到同一个应用
                        itemView.post(() -> {
                            // 验证 ViewHolder 是否仍然绑定到同一个包名
                            if (currentPackageName.equals(appIcon.getTag())) {
                                appIcon.setImageDrawable(icon);
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            } else if (appInfo.getAppIcon() != null) {
                appIcon.setImageDrawable(appInfo.getAppIcon());
            } else {
                appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            
            appName.setText(appInfo.getAppName());
            packageName.setText(appInfo.getPackageName());
            
            // 显示系统应用标签
            if (appInfo.isSystemApp()) {
                systemAppLabel.setVisibility(View.VISIBLE);
            } else {
                systemAppLabel.setVisibility(View.GONE);
            }
            
            // 设置开关状态
            switchEnable.setOnCheckedChangeListener(null); // 清除之前的监听器
            switchEnable.setChecked(appInfo.isEnabled());
            
            // 设置开关监听器
            switchEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
                appInfo.setEnabled(isChecked);
                if (onAppToggleListener != null) {
                    onAppToggleListener.onAppToggle(appInfo, isChecked);
                }
            });
            
            // 设置整个item的点击监听器
            itemView.setOnClickListener(v -> {
                if (onAppClickListener != null) {
                    onAppClickListener.onAppClick(appInfo);
                }
            });
        }
    }
}
