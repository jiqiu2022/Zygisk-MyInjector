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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 应用列表Fragment
 */
public class AppListFragment extends Fragment implements AppListAdapter.OnAppToggleListener {
    
    private RecyclerView recyclerView;
    private AppListAdapter adapter;
    private TextInputEditText searchEditText;
    private ProgressBar progressBar;
    
    private List<AppInfo> allApps;
    private boolean hideSystemApps = false;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, 
                           @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_app_list, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
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
        // 这里可以保存应用的启用状态到配置文件或数据库
        // 暂时只是打印日志
        android.util.Log.d("AppListFragment", 
            "App " + appInfo.getAppName() + " toggle: " + isEnabled);
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
