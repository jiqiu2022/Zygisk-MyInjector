package com.jiqiu.configapp;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KPM 隐藏功能 Fragment
 * 管理 KPM 内核模块和 SO 文件隐藏配置
 */
public class KpmHideFragment extends Fragment {
    
    private static final String TAG = "KpmHideFragment";
    
    private TextView tvModuleStatus;
    private TextView tvModuleInfo;
    private TextView tvConfigPath;
    private Button btnReloadModule;
    private RecyclerView rvSoList;
    
    private ConfigManager configManager;
    private HideSoAdapter adapter;
    private ExecutorService executor;
    private Handler mainHandler;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_kpm_hide, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initViews(view);
        initExecutor();
        initConfigManager();
        setupListeners();
        loadData();
    }
    
    private void initViews(View view) {
        tvModuleStatus = view.findViewById(R.id.tvModuleStatus);
        tvModuleInfo = view.findViewById(R.id.tvModuleInfo);
        tvConfigPath = view.findViewById(R.id.tvConfigPath);
        btnReloadModule = view.findViewById(R.id.btnReloadModule);
        rvSoList = view.findViewById(R.id.rvSoList);
        
        rvSoList.setLayoutManager(new LinearLayoutManager(getContext()));
    }
    
    private void initExecutor() {
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    private void initConfigManager() {
        configManager = new ConfigManager(getContext());
    }
    
    private void setupListeners() {
        btnReloadModule.setOnClickListener(v -> reloadModule());
    }
    
    private void loadData() {
        // 显示配置路径
        tvConfigPath.setText("配置文件: " + ConfigManager.KPM_HIDE_CONFIG);
        
        // 异步加载模块状态和 SO 列表
        executor.execute(() -> {
            try {
                final boolean isLoaded = configManager.isKpmModuleLoaded();
                final List<String> availableSos = configManager.getAvailableSoList();
                final List<String> hiddenSos = configManager.getHiddenSoList();
                
                mainHandler.post(() -> {
                    updateModuleStatus(isLoaded);
                    setupSoList(availableSos, hiddenSos);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading data", e);
                mainHandler.post(() -> {
                    Toast.makeText(getContext(), "加载数据失败: " + e.getMessage(), 
                                 Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    private void updateModuleStatus(boolean isLoaded) {
        if (isLoaded) {
            tvModuleStatus.setText("● 已加载");
            tvModuleStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            tvModuleInfo.setText("KPM 内核模块运行中\n模块名称: hideInject\n版本: 0.0.1");
        } else {
            tvModuleStatus.setText("● 未加载");
            tvModuleStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
            tvModuleInfo.setText("KPM 内核模块未运行\n请检查模块文件是否存在或手动重载");
        }
    }
    
    private void setupSoList(List<String> availableSos, List<String> hiddenSos) {
        List<HideSoItem> items = new ArrayList<>();
        
        for (String soName : availableSos) {
            HideSoItem item = new HideSoItem();
            item.soName = soName;
            item.isHidden = hiddenSos.contains(soName);
            // libmyinjector.so 是固定隐藏的
            item.isFixed = soName.equals("libmyinjector.so");
            items.add(item);
        }
        
        adapter = new HideSoAdapter(items, this::onSoItemCheckedChanged);
        rvSoList.setAdapter(adapter);
    }
    
    private void onSoItemCheckedChanged(HideSoItem item, boolean isChecked) {
        if (item.isFixed) {
            // 固定项不允许取消勾选
            Toast.makeText(getContext(), "libmyinjector.so 是必需的，不能取消隐藏", 
                         Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 异步更新配置
        executor.execute(() -> {
            try {
                boolean success;
                if (isChecked) {
                    success = configManager.addSoToHideList(item.soName);
                } else {
                    success = configManager.removeSoFromHideList(item.soName);
                }
                
                final boolean finalSuccess = success;
                mainHandler.post(() -> {
                    if (finalSuccess) {
                        item.isHidden = isChecked;
                        Toast.makeText(getContext(), 
                                     isChecked ? "已添加到隐藏列表" : "已从隐藏列表移除", 
                                     Toast.LENGTH_SHORT).show();
                        // 更新模块状态
                        refreshModuleStatus();
                    } else {
                        Toast.makeText(getContext(), "更新失败，请检查日志", 
                                     Toast.LENGTH_SHORT).show();
                        // 恢复原来的状态
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error updating SO hide status", e);
                mainHandler.post(() -> {
                    Toast.makeText(getContext(), "更新失败: " + e.getMessage(), 
                                 Toast.LENGTH_SHORT).show();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }
    
    private void reloadModule() {
        btnReloadModule.setEnabled(false);
        btnReloadModule.setText("重载中...");
        
        executor.execute(() -> {
            try {
                final boolean success = configManager.reloadKpmModule();
                
                mainHandler.post(() -> {
                    btnReloadModule.setEnabled(true);
                    btnReloadModule.setText("重载模块");
                    
                    if (success) {
                        Toast.makeText(getContext(), "模块重载成功", Toast.LENGTH_SHORT).show();
                        refreshModuleStatus();
                    } else {
                        Toast.makeText(getContext(), "模块重载失败，请查看日志", 
                                     Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error reloading module", e);
                mainHandler.post(() -> {
                    btnReloadModule.setEnabled(true);
                    btnReloadModule.setText("重载模块");
                    Toast.makeText(getContext(), "重载失败: " + e.getMessage(), 
                                 Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    private void refreshModuleStatus() {
        executor.execute(() -> {
            try {
                final boolean isLoaded = configManager.isKpmModuleLoaded();
                mainHandler.post(() -> updateModuleStatus(isLoaded));
            } catch (Exception e) {
                Log.e(TAG, "Error refreshing module status", e);
            }
        });
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
    
    /**
     * SO 隐藏项数据类
     */
    public static class HideSoItem {
        public String soName;
        public boolean isHidden;
        public boolean isFixed; // 是否是固定隐藏项（不可取消）
    }
}

