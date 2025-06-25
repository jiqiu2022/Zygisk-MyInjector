package com.jiqiu.configapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class AppSoConfigActivity extends AppCompatActivity {
    
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    public static final String EXTRA_APP_NAME = "app_name";
    
    private RecyclerView recyclerView;
    private TextView emptyView;
    private SoSelectionAdapter adapter;
    private ConfigManager configManager;
    
    private String packageName;
    private String appName;
    private List<ConfigManager.SoFile> appSoFiles;
    private List<ConfigManager.SoFile> globalSoFiles;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_so_config);
        
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        appName = getIntent().getStringExtra(EXTRA_APP_NAME);
        
        if (packageName == null) {
            finish();
            return;
        }
        
        configManager = new ConfigManager(this);
        
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(appName != null ? appName : packageName);
        getSupportActionBar().setSubtitle("配置SO文件");
        
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        
        adapter = new SoSelectionAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        
        loadData();
    }
    
    private void loadData() {
        // Load app-specific SO files
        appSoFiles = configManager.getAppSoFiles(packageName);
        
        // Load global SO files
        globalSoFiles = configManager.getAllSoFiles();
        
        if (globalSoFiles.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            adapter.setData(globalSoFiles, appSoFiles);
        }
    }
    
    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
    
    class SoSelectionAdapter extends RecyclerView.Adapter<SoSelectionAdapter.ViewHolder> {
        private List<ConfigManager.SoFile> availableSoFiles = new ArrayList<>();
        private List<ConfigManager.SoFile> selectedSoFiles = new ArrayList<>();
        
        void setData(List<ConfigManager.SoFile> available, List<ConfigManager.SoFile> selected) {
            this.availableSoFiles = available;
            this.selectedSoFiles = selected;
            notifyDataSetChanged();
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
            holder.bind(availableSoFiles.get(position));
        }
        
        @Override
        public int getItemCount() {
            return availableSoFiles.size();
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
            
            void bind(ConfigManager.SoFile soFile) {
                nameText.setText(soFile.name);
                pathText.setText(soFile.originalPath);
                
                // Check if this SO is selected for the app
                boolean isSelected = false;
                for (ConfigManager.SoFile selected : selectedSoFiles) {
                    if (selected.storedPath.equals(soFile.storedPath)) {
                        isSelected = true;
                        break;
                    }
                }
                
                checkBox.setOnCheckedChangeListener(null);
                checkBox.setChecked(isSelected);
                
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        // Add SO to app
                        configManager.addSoFileToApp(packageName, soFile.originalPath, false);
                    } else {
                        // Remove SO from app
                        configManager.removeSoFileFromApp(packageName, soFile);
                    }
                    
                    // Reload data
                    loadData();
                });
                
                itemView.setOnClickListener(v -> checkBox.toggle());
            }
        }
    }
}