package com.jiqiu.configapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;

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
        
        initViews(view);
        initSharedPreferences();
        loadSettings();
        setupListeners();
    }
    
    private void initViews(View view) {
        radioGroupFilter = view.findViewById(R.id.radio_group_filter);
        radioShowAll = view.findViewById(R.id.radio_show_all);
        radioHideSystem = view.findViewById(R.id.radio_hide_system);
    }
    
    private void initSharedPreferences() {
        sharedPreferences = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    private void loadSettings() {
        boolean hideSystemApps = sharedPreferences.getBoolean(KEY_HIDE_SYSTEM_APPS, false);
        
        if (hideSystemApps) {
            radioHideSystem.setChecked(true);
        } else {
            radioShowAll.setChecked(true);
        }
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
    }
    
    public void setOnSettingsChangeListener(OnSettingsChangeListener listener) {
        this.settingsChangeListener = listener;
    }
    
    public boolean isHideSystemApps() {
        return sharedPreferences.getBoolean(KEY_HIDE_SYSTEM_APPS, false);
    }
}
