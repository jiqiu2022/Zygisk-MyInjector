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
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
        // Ensure module directories exist
        configManager.ensureModuleDirectories();
        
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
        RadioGroup injectionMethodGroup = dialogView.findViewById(R.id.injectionMethodGroup);
        RadioButton radioStandardInjection = dialogView.findViewById(R.id.radioStandardInjection);
        RadioButton radioRiruInjection = dialogView.findViewById(R.id.radioRiruInjection);
        RadioButton radioCustomLinkerInjection = dialogView.findViewById(R.id.radioCustomLinkerInjection);
        RadioGroup gadgetConfigGroup = dialogView.findViewById(R.id.gadgetConfigGroup);
        RadioButton radioNoGadget = dialogView.findViewById(R.id.radioNoGadget);
        RadioButton radioUseGlobalGadget = dialogView.findViewById(R.id.radioUseGlobalGadget);
        RadioButton radioUseCustomGadget = dialogView.findViewById(R.id.radioUseCustomGadget);
        TextView tvGlobalGadgetInfo = dialogView.findViewById(R.id.tvGlobalGadgetInfo);
        com.google.android.material.button.MaterialButton btnConfigureGadget = dialogView.findViewById(R.id.btnConfigureGadget);
        
        appIcon.setImageDrawable(appInfo.getAppIcon());
        appName.setText(appInfo.getAppName());
        packageName.setText(appInfo.getPackageName());
        
        // Load current config
        String injectionMethod = configManager.getAppInjectionMethod(appInfo.getPackageName());
        if ("custom_linker".equals(injectionMethod)) {
            radioCustomLinkerInjection.setChecked(true);
        } else if ("riru".equals(injectionMethod)) {
            radioRiruInjection.setChecked(true);
        } else {
            radioStandardInjection.setChecked(true);
        }
        
        // Load gadget config
        boolean useGlobalGadget = configManager.getAppUseGlobalGadget(appInfo.getPackageName());
        ConfigManager.GadgetConfig appSpecificGadget = configManager.getAppGadgetConfig(appInfo.getPackageName());
        ConfigManager.GadgetConfig globalGadget = configManager.getGlobalGadgetConfig();
        
        // Update global gadget info
        if (globalGadget != null) {
            String info = "全局: " + globalGadget.gadgetName;
            if (globalGadget.mode.equals("server")) {
                info += " (端口: " + globalGadget.port + ")";
            }
            tvGlobalGadgetInfo.setText(info);
        } else {
            tvGlobalGadgetInfo.setText("未配置全局Gadget");
        }
        
        // Set initial radio selection
        if (!useGlobalGadget && appSpecificGadget != null) {
            radioUseCustomGadget.setChecked(true);
            btnConfigureGadget.setVisibility(View.VISIBLE);
            btnConfigureGadget.setEnabled(true);
        } else if (useGlobalGadget && globalGadget != null) {
            radioUseGlobalGadget.setChecked(true);
            btnConfigureGadget.setVisibility(View.GONE);
        } else {
            radioNoGadget.setChecked(true);
            btnConfigureGadget.setVisibility(View.GONE);
        }
        
        // Setup gadget radio group listener
        gadgetConfigGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioNoGadget) {
                btnConfigureGadget.setVisibility(View.GONE);
                configManager.setAppUseGlobalGadget(appInfo.getPackageName(), false);
                configManager.setAppGadgetConfig(appInfo.getPackageName(), null);
            } else if (checkedId == R.id.radioUseGlobalGadget) {
                btnConfigureGadget.setVisibility(View.GONE);
                configManager.setAppUseGlobalGadget(appInfo.getPackageName(), true);
                configManager.setAppGadgetConfig(appInfo.getPackageName(), null);
            } else if (checkedId == R.id.radioUseCustomGadget) {
                btnConfigureGadget.setVisibility(View.VISIBLE);
                btnConfigureGadget.setEnabled(true);
                configManager.setAppUseGlobalGadget(appInfo.getPackageName(), false);
            }
        });
        
        // Configure button listener
        btnConfigureGadget.setOnClickListener(v -> {
            ConfigManager.GadgetConfig currentConfig = null;
            if (!useGlobalGadget) {
                currentConfig = configManager.getAppGadgetConfig(appInfo.getPackageName());
            }
            if (currentConfig == null) {
                currentConfig = new ConfigManager.GadgetConfig();
            }
            
            GadgetConfigDialog dialog = new GadgetConfigDialog(
                getContext(),
                "配置" + appInfo.getAppName() + "的Gadget",
                currentConfig,
                config -> {
                    configManager.setAppUseGlobalGadget(appInfo.getPackageName(), false);
                    configManager.setAppGadgetConfig(appInfo.getPackageName(), config);
                }
            );
            dialog.show();
        });
        
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
                    // Save injection method
                    String selectedMethod;
                    if (radioCustomLinkerInjection.isChecked()) {
                        selectedMethod = "custom_linker";
                    } else if (radioRiruInjection.isChecked()) {
                        selectedMethod = "riru";
                    } else {
                        selectedMethod = "standard";
                    }
                    configManager.setAppInjectionMethod(appInfo.getPackageName(), selectedMethod);
                    
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
