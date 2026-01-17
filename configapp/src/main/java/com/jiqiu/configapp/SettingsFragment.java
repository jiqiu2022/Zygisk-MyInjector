package com.jiqiu.configapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.EditText;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.TextView;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 设置Fragment
 */
public class SettingsFragment extends Fragment {
    
    private static final String PREFS_NAME = "MyInjectorSettings";
    private static final String KEY_HIDE_SYSTEM_APPS = "hide_system_apps";
    
    private RadioGroup radioGroupFilter;
    private RadioButton radioShowAll;
    private RadioButton radioHideSystem;
    private EditText editInjectionDelay;
    private TextView tvGlobalGadgetStatus;
    private Button btnConfigureGlobalGadget;
    private Button btnResetGlobalGadget;
    private ConfigManager configManager;
    
    private SharedPreferences sharedPreferences;
    private OnSettingsChangeListener settingsChangeListener;
    
    public interface OnSettingsChangeListener {
        void onHideSystemAppsChanged(boolean hideSystemApps);
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, 
                           @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Setup Fragment Result Listener for GadgetConfigDialog
        setupGadgetConfigResultListener();
        
        initViews(view);
        initSharedPreferences();
        // 先加载设置，再设置监听器，避免触发动画
        loadSettings();
        setupListeners();
    }
    
    private void setupGadgetConfigResultListener() {
        getParentFragmentManager().setFragmentResultListener(
            GadgetConfigDialog.REQUEST_KEY,
            getViewLifecycleOwner(),
            (requestKey, result) -> {
                boolean isGlobalConfig = result.getBoolean("isGlobalConfig", false);
                if (isGlobalConfig) {
                    // Extract config from bundle
                    ConfigManager.GadgetConfig config = new ConfigManager.GadgetConfig();
                    config.mode = result.getString("mode", "script");
                    config.address = result.getString("address", "0.0.0.0");
                    config.port = result.getInt("port", 27042);
                    config.onPortConflict = result.getString("onPortConflict", "fail");
                    config.onLoad = result.getString("onLoad", "wait");
                    config.scriptPath = result.getString("scriptPath", "/data/local/tmp/script.js");
                    config.gadgetName = result.getString("gadgetName", "libgadget.so");
                    
                    // Save global gadget configuration
                    configManager.setGlobalGadgetConfig(config);
                    updateGlobalGadgetStatus();
                }
            }
        );
    }
    
    private void initViews(View view) {
        radioGroupFilter = view.findViewById(R.id.radio_group_filter);
        radioShowAll = view.findViewById(R.id.radio_show_all);
        radioHideSystem = view.findViewById(R.id.radio_hide_system);
        editInjectionDelay = view.findViewById(R.id.editInjectionDelay);
        tvGlobalGadgetStatus = view.findViewById(R.id.tvGlobalGadgetStatus);
        btnConfigureGlobalGadget = view.findViewById(R.id.btnConfigureGlobalGadget);
        btnResetGlobalGadget = view.findViewById(R.id.btnResetGlobalGadget);
        
        configManager = new ConfigManager(getContext());
    }
    
    private void initSharedPreferences() {
        sharedPreferences = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    private void loadSettings() {
        boolean hideSystemApps = sharedPreferences.getBoolean(KEY_HIDE_SYSTEM_APPS, false);
        
        // 检查是否需要改变状态，避免不必要的动画
        boolean needsChange = hideSystemApps != radioHideSystem.isChecked();
        
        if (needsChange) {
            // 临时禁用动画
            radioGroupFilter.jumpDrawablesToCurrentState();
            
            if (hideSystemApps) {
                radioHideSystem.setChecked(true);
            } else {
                radioShowAll.setChecked(true);
            }
            
            // 立即跳过动画到最终状态
            radioGroupFilter.jumpDrawablesToCurrentState();
            radioShowAll.jumpDrawablesToCurrentState();
            radioHideSystem.jumpDrawablesToCurrentState();
        }
        
        // Load injection delay
        int injectionDelay = configManager.getInjectionDelay();
        editInjectionDelay.setText(String.valueOf(injectionDelay));
        
        // Load global gadget status
        updateGlobalGadgetStatus();
    }
    
    private void setupListeners() {
        radioGroupFilter.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                boolean hideSystemApps = (checkedId == R.id.radio_hide_system);
                
                // 保存设置
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putBoolean(KEY_HIDE_SYSTEM_APPS, hideSystemApps);
                editor.apply();
                
                // 通知设置变化
                if (settingsChangeListener != null) {
                    settingsChangeListener.onHideSystemAppsChanged(hideSystemApps);
                }
            }
        });
        
        // Injection delay listener
        editInjectionDelay.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String text = s.toString().trim();
                if (!text.isEmpty()) {
                    try {
                        int delay = Integer.parseInt(text);
                        // Limit delay between 0 and 60 seconds
                        if (delay < 0) delay = 0;
                        if (delay > 60) delay = 60;
                        
                        configManager.setInjectionDelay(delay);
                    } catch (NumberFormatException e) {
                        // Ignore invalid input
                    }
                }
            }
        });
        
        // Global gadget configuration button
        btnConfigureGlobalGadget.setOnClickListener(v -> {
            showGlobalGadgetConfigDialog();
        });
        
        // Reset global gadget to default button
        btnResetGlobalGadget.setOnClickListener(v -> {
            configManager.resetGlobalGadgetConfigToDefault();
            updateGlobalGadgetStatus();
            // Show confirmation message
            if (getContext() != null) {
                android.widget.Toast.makeText(getContext(), "全局Gadget配置已重置为默认值", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    public void setOnSettingsChangeListener(OnSettingsChangeListener listener) {
        this.settingsChangeListener = listener;
    }
    
    public boolean isHideSystemApps() {
        return sharedPreferences.getBoolean(KEY_HIDE_SYSTEM_APPS, false);
    }
    
    private void updateGlobalGadgetStatus() {
        ConfigManager.GadgetConfig globalGadget = configManager.getGlobalGadgetConfig();
        if (globalGadget != null) {
            String status = "已配置: " + globalGadget.gadgetName;
            if (globalGadget.mode.equals("server")) {
                status += " (Server模式, 端口: " + globalGadget.port + ")";
            } else {
                status += " (Script模式)";
            }
            tvGlobalGadgetStatus.setText(status);
        } else {
            tvGlobalGadgetStatus.setText("未配置");
        }
    }
    
    private void showGlobalGadgetConfigDialog() {
        // Use existing GadgetConfigDialog
        GadgetConfigDialog dialog = GadgetConfigDialog.newInstance(configManager.getGlobalGadgetConfig());
        dialog.setCustomTitle("全局Gadget配置");
        
        // Mark this as global config for result callback
        Bundle args = dialog.getArguments();
        if (args == null) {
            args = new Bundle();
        }
        args.putBoolean("isGlobalConfig", true);
        dialog.setArguments(args);
        
        dialog.show(getParentFragmentManager(), "GlobalGadgetConfigDialog");
    }
}
