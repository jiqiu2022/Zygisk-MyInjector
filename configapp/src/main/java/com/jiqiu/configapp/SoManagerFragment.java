package com.jiqiu.configapp;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SoManagerFragment extends Fragment {
    
    private RecyclerView recyclerView;
    private LinearLayout emptyView;
    private SoListAdapter adapter;
    private ConfigManager configManager;
    private List<ConfigManager.SoFile> globalSoFiles = new ArrayList<>();
    
    private ActivityResultLauncher<Intent> filePickerLauncher;
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        configManager = new ConfigManager(requireContext());
        
        // Initialize file picker
        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        handleFileSelection(uri);
                    }
                }
            }
        );
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_so_manager, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        recyclerView = view.findViewById(R.id.recyclerView);
        emptyView = view.findViewById(R.id.emptyView);
        FloatingActionButton fabAdd = view.findViewById(R.id.fabAdd);
        
        // Setup RecyclerView
        adapter = new SoListAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);
        
        adapter.setOnSoFileActionListener(this::showDeleteConfirmation);
        
        // Setup FAB
        fabAdd.setOnClickListener(v -> showAddSoDialog());
        
        // Check root access
        if (!configManager.isRootAvailable()) {
            Toast.makeText(getContext(), "需要Root权限", Toast.LENGTH_LONG).show();
        } else {
            configManager.ensureModuleDirectories();
            loadSoFiles();
        }
    }
    
    private void loadSoFiles() {
        // Load global SO files from the storage directory
        Shell.Result result = Shell.cmd("ls -la " + ConfigManager.SO_STORAGE_DIR).exec();
        
        globalSoFiles.clear();
        if (result.isSuccess()) {
            for (String line : result.getOut()) {
                if (line.contains(".so")) {
                    // Parse file info
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 9) {
                        String fileName = parts[parts.length - 1];
                        ConfigManager.SoFile soFile = new ConfigManager.SoFile();
                        soFile.name = fileName;
                        soFile.storedPath = ConfigManager.SO_STORAGE_DIR + "/" + fileName;
                        soFile.originalPath = soFile.storedPath; // For display
                        globalSoFiles.add(soFile);
                    }
                }
            }
        }
        
        updateUI();
    }
    
    private void updateUI() {
        if (globalSoFiles.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            adapter.setSoFiles(globalSoFiles);
        }
    }
    
    private void showAddSoDialog() {
        String[] options = {"从文件管理器选择", "输入路径"};
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("添加SO文件")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openFilePicker();
                    } else {
                        showPathInputDialog();
                    }
                })
                .show();
    }
    
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(intent);
    }
    
    private void showPathInputDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_input, null);
        android.widget.EditText editText = view.findViewById(android.R.id.edit);
        editText.setHint("/data/local/tmp/example.so");
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("输入SO文件路径")
                .setView(view)
                .setPositiveButton("添加", (dialog, which) -> {
                    String path = editText.getText().toString().trim();
                    if (!path.isEmpty()) {
                        addSoFile(path, false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    private void handleFileSelection(Uri uri) {
        // Get real path from URI
        String path = uri.getPath();
        if (path != null) {
            // Remove the file:// prefix if present
            if (path.startsWith("file://")) {
                path = path.substring(7);
            }
            addSoFile(path, false);
        }
    }
    
    private void addSoFile(String path, boolean deleteOriginal) {
        // Verify file exists
        Shell.Result result = Shell.cmd("test -f " + path + " && echo 'exists'").exec();
        if (!result.isSuccess() || result.getOut().isEmpty()) {
            Toast.makeText(getContext(), "文件不存在: " + path, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Generate unique filename
        String fileName = new File(path).getName();
        String storedPath = ConfigManager.SO_STORAGE_DIR + "/" + System.currentTimeMillis() + "_" + fileName;
        
        // Copy file
        result = Shell.cmd("cp " + path + " " + storedPath).exec();
        if (result.isSuccess()) {
            ConfigManager.SoFile soFile = new ConfigManager.SoFile();
            soFile.name = fileName;
            soFile.storedPath = storedPath;
            soFile.originalPath = path;
            globalSoFiles.add(soFile);
            
            if (deleteOriginal) {
                Shell.cmd("rm " + path).exec();
            }
            
            updateUI();
            Toast.makeText(getContext(), "SO文件已添加", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "复制文件失败", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showDeleteConfirmation(ConfigManager.SoFile soFile) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除SO文件")
                .setMessage("确定要删除 " + soFile.name + " 吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    deleteSoFile(soFile);
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    private void deleteSoFile(ConfigManager.SoFile soFile) {
        Shell.cmd("rm " + soFile.storedPath).exec();
        globalSoFiles.remove(soFile);
        updateUI();
        Toast.makeText(getContext(), "SO文件已删除", Toast.LENGTH_SHORT).show();
    }
}