package com.jiqiu.configapp;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity implements SettingsFragment.OnSettingsChangeListener {

    private BottomNavigationView bottomNavigationView;
    private AppListFragment appListFragment;
    private SettingsFragment settingsFragment;
    private SoManagerFragment soManagerFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        setupBottomNavigation();

        // 默认显示应用列表
        if (savedInstanceState == null) {
            showAppListFragment();
        }
    }

    private void initViews() {
        bottomNavigationView = findViewById(R.id.bottom_navigation);
    }

    private void setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_apps) {
                showAppListFragment();
                return true;
            } else if (itemId == R.id.navigation_so_manager) {
                showSoManagerFragment();
                return true;
            } else if (itemId == R.id.navigation_settings) {
                showSettingsFragment();
                return true;
            }
            return false;
        });
    }

    private void showAppListFragment() {
        if (appListFragment == null) {
            appListFragment = new AppListFragment();
        }
        showFragment(appListFragment);
    }

    private void showSoManagerFragment() {
        if (soManagerFragment == null) {
            soManagerFragment = new SoManagerFragment();
        }
        showFragment(soManagerFragment);
    }

    private void showSettingsFragment() {
        if (settingsFragment == null) {
            settingsFragment = new SettingsFragment();
            settingsFragment.setOnSettingsChangeListener(this);
        }
        showFragment(settingsFragment);
    }

    private void showFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.nav_host_fragment, fragment);
        transaction.commit();
    }

    @Override
    public void onHideSystemAppsChanged(boolean hideSystemApps) {
        // 当设置改变时，通知应用列表Fragment更新过滤
        if (appListFragment != null) {
            appListFragment.setHideSystemApps(hideSystemApps);
        }
    }
}