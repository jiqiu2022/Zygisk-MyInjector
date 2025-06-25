package com.jiqiu.configapp;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.app.Dialog;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 应用列表Fragment
 */
public class AppListFragment extends Fragment implements AppListAdapter.OnAppToggleListener, AppListAdapter.OnAppClickListener {
    
    private RecyclerView recyclerView;
    private AppListAdapter adapter;
    private TextInputEditText searchEditText;
    private ProgressBar progressBar;
    
    private List<AppInfo> allApps;
    private boolean hideSystemApps = false;
    private ConfigManager configManager;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, 
                           @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_app_list, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        configManager = new ConfigManager(requireContext());
        
        initViews(view);
        setupRecyclerView();
        setupSearchView();
        loadApps();
    }
    
    private void initViews(View view) {
        recyclerView = view.findViewById(R.id.recycler_view_apps);
        searchEditText = view.findViewById(R.id.search_edit_text);
        progressBar = view.findViewById(R.id.progress_bar);
    }
    
    private void setupRecyclerView() {
        adapter = new AppListAdapter();
        adapter.setOnAppToggleListener(this);
        adapter.setOnAppClickListener(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);
    }
    
    private void setupSearchView() {
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }
            
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }
    
    private void loadApps() {
        progressBar.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        
        new LoadAppsTask().execute();
    }
    
    private void filterApps(String query) {
        if (adapter != null) {
            adapter.filterApps(query, hideSystemApps);
        }
    }
    
    public void setHideSystemApps(boolean hideSystemApps) {
        this.hideSystemApps = hideSystemApps;
        filterApps(searchEditText.getText().toString());
    }
    
    @Override
    public void onAppToggle(AppInfo appInfo, boolean isEnabled) {
        // 保存应用的启用状态到配置文件
        configManager.setAppEnabled(appInfo.getPackageName(), isEnabled);
        android.util.Log.d("AppListFragment", 
            "App " + appInfo.getAppName() + " toggle: " + isEnabled);
    }
    
    @Override
    public void onAppClick(AppInfo appInfo) {
        showAppConfigDialog(appInfo);
    }
    
    private void showAppConfigDialog(AppInfo appInfo) {
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_app_config, null);
        
        // Set app info
        ImageView appIcon = dialogView.findViewById(R.id.appIcon);
        TextView appName = dialogView.findViewById(R.id.appName);
        TextView packageName = dialogView.findViewById(R.id.packageName);
        RecyclerView soListRecyclerView = dialogView.findViewById(R.id.soListRecyclerView);
        TextView emptyText = dialogView.findViewById(R.id.emptyText);
        SwitchMaterial switchHideInjection = dialogView.findViewById(R.id.switchHideInjection);
        
        appIcon.setImageDrawable(appInfo.getAppIcon());
        appName.setText(appInfo.getAppName());
        packageName.setText(appInfo.getPackageName());
        
        // Load current config
        boolean hideInjection = configManager.getHideInjection();
        switchHideInjection.setChecked(hideInjection);
        
        // Setup SO list
        List<ConfigManager.SoFile> globalSoFiles = configManager.getAllSoFiles();
        List<ConfigManager.SoFile> appSoFiles = configManager.getAppSoFiles(appInfo.getPackageName());
        
        if (globalSoFiles.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            soListRecyclerView.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            soListRecyclerView.setVisibility(View.VISIBLE);
            
            SoSelectionAdapter soAdapter = new SoSelectionAdapter(globalSoFiles, appSoFiles);
            soListRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            soListRecyclerView.setAdapter(soAdapter);
        }
        
        // Create dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext())
                .setTitle("配置注入")
                .setView(dialogView)
                .setPositiveButton("保存", (dialog, which) -> {
                    // Save hide injection setting
                    configManager.setHideInjection(switchHideInjection.isChecked());
                    
                    // Save SO selection
                    if (soListRecyclerView.getAdapter() != null) {
                        SoSelectionAdapter adapter = (SoSelectionAdapter) soListRecyclerView.getAdapter();
                        List<ConfigManager.SoFile> selectedSoFiles = adapter.getSelectedSoFiles();
                        
                        // Clear existing SO files for this app
                        for (ConfigManager.SoFile existingSo : appSoFiles) {
                            configManager.removeSoFileFromApp(appInfo.getPackageName(), existingSo);
                        }
                        
                        // Add selected SO files
                        for (ConfigManager.SoFile soFile : selectedSoFiles) {
                            configManager.addSoFileToApp(appInfo.getPackageName(), soFile);
                        }
                    }
                })
                .setNegativeButton("取消", null);
        
        builder.show();
    }
    
    // Inner class for SO selection adapter
    private static class SoSelectionAdapter extends RecyclerView.Adapter<SoSelectionAdapter.ViewHolder> {
        private List<ConfigManager.SoFile> globalSoFiles;
        private List<ConfigManager.SoFile> selectedSoFiles;
        
        public SoSelectionAdapter(List<ConfigManager.SoFile> globalSoFiles, List<ConfigManager.SoFile> appSoFiles) {
            this.globalSoFiles = globalSoFiles;
            this.selectedSoFiles = new ArrayList<>(appSoFiles);
        }
        
        public List<ConfigManager.SoFile> getSelectedSoFiles() {
            return new ArrayList<>(selectedSoFiles);
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_so_selection, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ConfigManager.SoFile soFile = globalSoFiles.get(position);
            holder.bind(soFile, selectedSoFiles);
        }
        
        @Override
        public int getItemCount() {
            return globalSoFiles.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            CheckBox checkBox;
            TextView nameText;
            TextView pathText;
            
            ViewHolder(@NonNull View itemView) {
                super(itemView);
                checkBox = itemView.findViewById(R.id.checkBox);
                nameText = itemView.findViewById(R.id.textName);
                pathText = itemView.findViewById(R.id.textPath);
            }
            
            void bind(ConfigManager.SoFile soFile, List<ConfigManager.SoFile> selectedList) {
                nameText.setText(soFile.name);
                pathText.setText(soFile.originalPath);
                
                // Check if this SO is selected
                boolean isSelected = false;
                for (ConfigManager.SoFile selected : selectedList) {
                    if (selected.storedPath.equals(soFile.storedPath)) {
                        isSelected = true;
                        break;
                    }
                }
                
                checkBox.setOnCheckedChangeListener(null);
                checkBox.setChecked(isSelected);
                
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        selectedList.add(soFile);
                    } else {
                        selectedList.removeIf(s -> s.storedPath.equals(soFile.storedPath));
                    }
                });
                
                itemView.setOnClickListener(v -> checkBox.toggle());
            }
        }
    }
    
    /**
     * 异步加载应用列表
     */
    private class LoadAppsTask extends AsyncTask<Void, Void, List<AppInfo>> {
        
        @Override
        protected List<AppInfo> doInBackground(Void... voids) {
            List<AppInfo> apps = new ArrayList<>();
            PackageManager pm = getContext().getPackageManager();
            
            List<ApplicationInfo> installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            
            for (ApplicationInfo appInfo : installedApps) {
                try {
                    String appName = pm.getApplicationLabel(appInfo).toString();
                    String packageName = appInfo.packageName;
                    boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    
                    AppInfo app = new AppInfo(
                        appName,
                        packageName,
                        pm.getApplicationIcon(appInfo),
                        isSystemApp
                    );
                    
                    // 从配置中加载启用状态
                    app.setEnabled(configManager.isAppEnabled(packageName));
                    
                    apps.add(app);
                } catch (Exception e) {
                    // 忽略无法获取信息的应用
                    e.printStackTrace();
                }
            }
            
            // 按应用名称排序
            Collections.sort(apps, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo o1, AppInfo o2) {
                    return o1.getAppName().compareToIgnoreCase(o2.getAppName());
                }
            });
            
            return apps;
        }
        
        @Override
        protected void onPostExecute(List<AppInfo> apps) {
            allApps = apps;
            adapter.setAppList(apps);
            
            progressBar.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            
            // 应用当前的过滤设置
            filterApps(searchEditText.getText().toString());
        }
    }
}
