package com.jiqiu.configapp;

import android.content.Context;
import android.content.SharedPreferences;
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
    
    private static final String PREFS_NAME = "MyInjectorSettings";
    private static final String KEY_HIDE_SYSTEM_APPS = "hide_system_apps";
    
    private RecyclerView recyclerView;
    private AppListAdapter adapter;
    private TextInputEditText searchEditText;
    private ProgressBar progressBar;
    
    private List<AppInfo> allApps;
    private boolean hideSystemApps = false;
    private ConfigManager configManager;
    private SharedPreferences sharedPreferences;
    
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
        
        // 初始化SharedPreferences并读取保存的过滤设置
        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        hideSystemApps = sharedPreferences.getBoolean(KEY_HIDE_SYSTEM_APPS, false);
        
        // Setup Fragment Result Listener for GadgetConfigDialog
        setupGadgetConfigResultListener();
        
        initViews(view);
        setupRecyclerView();
        setupSearchView();
        loadApps();
    }
    
    private void setupGadgetConfigResultListener() {
        getParentFragmentManager().setFragmentResultListener(
            GadgetConfigDialog.REQUEST_KEY,
            getViewLifecycleOwner(),
            (requestKey, result) -> {
                String packageName = result.getString("packageName");
                if (packageName != null) {
                    // Extract config from bundle
                    ConfigManager.GadgetConfig config = new ConfigManager.GadgetConfig();
                    config.mode = result.getString("mode", "script");
                    config.address = result.getString("address", "0.0.0.0");
                    config.port = result.getInt("port", 27042);
                    config.onPortConflict = result.getString("onPortConflict", "fail");
                    config.onLoad = result.getString("onLoad", "wait");
                    config.scriptPath = result.getString("scriptPath", "/data/local/tmp/script.js");
                    config.gadgetName = result.getString("gadgetName", "libgadget.so");
                    
                    configManager.setAppUseGlobalGadget(packageName, false);
                    configManager.setAppGadgetConfig(packageName, config);
                }
            }
        );
    }
    
    private void initViews(View view) {
        recyclerView = view.findViewById(R.id.recycler_view_apps);
        searchEditText = view.findViewById(R.id.search_edit_text);
        progressBar = view.findViewById(R.id.progress_bar);
        
        // 设置根布局点击监听，点击时清除搜索框焦点
        View rootLayout = view.findViewById(R.id.root_layout);
        if (rootLayout != null) {
            rootLayout.setOnClickListener(v -> clearSearchFocus());
        }
    }
    
    private void setupRecyclerView() {
        adapter = new AppListAdapter();
        adapter.setOnAppToggleListener(this);
        adapter.setOnAppClickListener(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);
        
        // 添加滚动监听，滚动时清除搜索框焦点
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    clearSearchFocus();
                }
            }
        });
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
    
    /**
     * 清除搜索框焦点并隐藏键盘
     */
    private void clearSearchFocus() {
        if (searchEditText != null && searchEditText.hasFocus()) {
            searchEditText.clearFocus();
            // 隐藏软键盘
            android.view.inputmethod.InputMethodManager imm = 
                (android.view.inputmethod.InputMethodManager) requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchEditText.getWindowToken(), 0);
            }
        }
    }
    
    @Override
    public void onAppToggle(AppInfo appInfo, boolean isEnabled) {
        // 保存应用的启用状态到配置文件
        configManager.setAppEnabled(appInfo.getPackageName(), isEnabled);
        android.util.Log.d("AppListFragment", 
            "App " + appInfo.getAppName() + " toggle: " + isEnabled);
        
        // 重新排序列表，让已启用的应用显示在最上面
        if (adapter != null) {
            adapter.refreshSort();
        }
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
            
            GadgetConfigDialog dialog = GadgetConfigDialog.newInstance(currentConfig);
            dialog.setCustomTitle("配置" + appInfo.getAppName() + "的Gadget");
            
            // Store package name for result callback
            Bundle args = dialog.getArguments();
            if (args == null) {
                args = new Bundle();
            }
            args.putString("packageName", appInfo.getPackageName());
            dialog.setArguments(args);
            
            dialog.show(getParentFragmentManager(), "GadgetConfigDialog");
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
                    
                    // 不在这里加载图标，留给ViewHolder延迟加载
                    AppInfo app = new AppInfo(
                        appName,
                        packageName,
                        null,  // 图标稍后加载
                        isSystemApp
                    );
                    
                    // 从配置中加载启用状态
                    app.setEnabled(configManager.isAppEnabled(packageName));
                    
                    // 获取应用最后更新时间（而不是首次安装旴间）
                    try {
                        long updateTime = pm.getPackageInfo(packageName, 0).lastUpdateTime;
                        app.setInstallTime(updateTime);
                    } catch (Exception e) {
                        app.setInstallTime(0);
                    }
                    
                    apps.add(app);
                } catch (Exception e) {
                    // 忽略无法获取信息的应用
                    e.printStackTrace();
                }
            }
            
            // 按启用状态和更新时间排序：已启用的应用在前面，最近更新的排在前面
            Collections.sort(apps, new Comparator<AppInfo>() {
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
