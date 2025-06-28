package com.jiqiu.configapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.topjohnwu.superuser.Shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FileBrowserActivity extends AppCompatActivity {
    
    private static final String TAG = "FileBrowser";
    public static final String EXTRA_START_PATH = "start_path";
    public static final String EXTRA_FILE_FILTER = "file_filter";
    public static final String EXTRA_SELECTED_PATH = "selected_path";
    
    private RecyclerView recyclerView;
    private TextView currentPathText;
    private View emptyView;
    private FileListAdapter adapter;
    
    private String currentPath;
    private String fileFilter = ".so";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);
        
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("选择SO文件");
        
        currentPathText = findViewById(R.id.currentPath);
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        
        adapter = new FileListAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        
        // Get start path from intent
        String startPath = getIntent().getStringExtra(EXTRA_START_PATH);
        if (startPath == null) {
            startPath = "/data/local/tmp";
        }
        fileFilter = getIntent().getStringExtra(EXTRA_FILE_FILTER);
        if (fileFilter == null) {
            fileFilter = ".so";
        }
        
        // Check if we have root access
        if (!Shell.getShell().isRoot()) {
            Toast.makeText(this, "需要Root权限才能浏览文件", Toast.LENGTH_LONG).show();
            Log.e(TAG, "No root access");
        }
        
        currentPath = startPath;
        loadFiles();
    }
    
    private void loadFiles() {
        currentPathText.setText(currentPath);
        
        List<FileItem> items = new ArrayList<>();
        
        // Add parent directory if not root
        if (!"/".equals(currentPath)) {
            items.add(new FileItem("..", true, true));
        }
        
        // List files using root with better debugging
        Log.d(TAG, "Loading files from: " + currentPath);
        
        // First, try to resolve symbolic links
        Shell.Result resolveResult = Shell.cmd("readlink -f " + currentPath + " 2>/dev/null").exec();
        String resolvedPath = currentPath;
        if (resolveResult.isSuccess() && !resolveResult.getOut().isEmpty()) {
            resolvedPath = resolveResult.getOut().get(0);
            Log.d(TAG, "Resolved path: " + resolvedPath);
        }
        
        // Try multiple methods to list files
        boolean filesFound = false;
        
        // Method 1: Use find command to get only .so files
        Log.d(TAG, "Searching for .so files...");
        Shell.Result findResult = Shell.cmd("find \"" + resolvedPath + "\" -maxdepth 1 -type f -name '*.so' 2>/dev/null").exec();
        if (findResult.isSuccess()) {
            for (String path : findResult.getOut()) {
                if (!path.trim().isEmpty()) {
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    items.add(new FileItem(name, false, true));
                    filesFound = true;
                }
            }
        }
        
        // Method 2: If no .so files found with find, try ls and filter
        if (!filesFound) {
            Log.d(TAG, "Trying ls command and filtering...");
            Shell.Result result = Shell.cmd("ls -la \"" + resolvedPath + "\" 2>/dev/null").exec();
            Log.d(TAG, "ls -la command success: " + result.isSuccess() + ", output lines: " + result.getOut().size());
            
            if (result.isSuccess() && !result.getOut().isEmpty()) {
                for (String line : result.getOut()) {
                    Log.d(TAG, "Processing line: " + line);
                    
                    // Skip empty lines and total line
                    if (line.trim().isEmpty() || line.startsWith("total")) {
                        continue;
                    }
                    
                    String name = null;
                    boolean isDirectory = false;
                    boolean isReadable = true;
                    
                    // Check if line starts with permissions (drwxr-xr-x format)
                    if (line.matches("^[dlrwxst-]{10}.*")) {
                        String[] parts = line.split("\\s+", 9);
                        if (parts.length >= 9) {
                            String permissions = parts[0];
                            name = parts[parts.length - 1];
                            isDirectory = permissions.startsWith("d");
                            isReadable = permissions.length() > 1 && permissions.charAt(1) == 'r';
                            
                            // Handle symbolic links
                            if (line.contains("->")) {
                                // Extract the actual name before the arrow
                                int arrowIndex = name.indexOf(" -> ");
                                if (arrowIndex > 0) {
                                    name = name.substring(0, arrowIndex);
                                }
                            }
                        }
                    } else {
                        // Simple format, just the filename
                        name = line.trim();
                        // Check if it's a directory by trying to list it
                        Shell.Result dirCheck = Shell.cmd("test -d \"" + resolvedPath + "/" + name + "\" && echo 'dir'").exec();
                        isDirectory = dirCheck.isSuccess() && !dirCheck.getOut().isEmpty();
                    }
                    
                    if (name != null && !".".equals(name) && !"..".equals(name)) {
                        // Only show .so files, skip directories and other files
                        if (!isDirectory && name.endsWith(".so")) {
                            items.add(new FileItem(name, false, isReadable));
                            filesFound = true;
                        }
                    }
                }
            }
        }
        
        // Method 3: If still no files, try simple ls and filter
        if (!filesFound) {
            Log.d(TAG, "Trying simple ls command...");
            Shell.Result simpleResult = Shell.cmd("ls \"" + resolvedPath + "\" 2>/dev/null").exec();
            if (simpleResult.isSuccess()) {
                for (String name : simpleResult.getOut()) {
                    if (!name.trim().isEmpty() && !"*".equals(name)) {
                        Shell.Result dirCheck = Shell.cmd("test -d \"" + resolvedPath + "/" + name + "\" && echo 'dir'").exec();
                        boolean isDirectory = dirCheck.isSuccess() && !dirCheck.getOut().isEmpty();
                        
                        // Only show .so files
                        if (!isDirectory && name.endsWith(".so")) {
                            items.add(new FileItem(name, false, true));
                            filesFound = true;
                        }
                    }
                }
            }
        }
        
        // Debug: Log what we found
        Log.d(TAG, "Total .so files found: " + items.size());
        for (FileItem item : items) {
            Log.d(TAG, "SO file: " + item.name);
        }
        
        // Sort files by name
        Collections.sort(items, (a, b) -> a.name.compareToIgnoreCase(b.name));
        
        adapter.setItems(items);
        emptyView.setVisibility(items.isEmpty() || (items.size() == 1 && "..".equals(items.get(0).name)) ? View.VISIBLE : View.GONE);
    }
    
    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
    
    class FileItem {
        String name;
        boolean isDirectory;
        boolean isReadable;
        
        FileItem(String name, boolean isDirectory, boolean isReadable) {
            this.name = name;
            this.isDirectory = isDirectory;
            this.isReadable = isReadable;
        }
    }
    
    class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.ViewHolder> {
        private List<FileItem> items = new ArrayList<>();
        
        void setItems(List<FileItem> items) {
            this.items = items;
            notifyDataSetChanged();
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position));
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView name;
            TextView info;
            
            ViewHolder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.fileIcon);
                name = itemView.findViewById(R.id.fileName);
                info = itemView.findViewById(R.id.fileInfo);
            }
            
            void bind(FileItem item) {
                name.setText(item.name);
                
                if ("..".equals(item.name)) {
                    icon.setImageResource(android.R.drawable.ic_menu_revert);
                    info.setText("返回上级");
                } else {
                    icon.setImageResource(android.R.drawable.ic_menu_save);
                    info.setText("SO文件");
                }
                
                if (!item.isReadable) {
                    itemView.setAlpha(0.5f);
                } else {
                    itemView.setAlpha(1.0f);
                }
                
                itemView.setOnClickListener(v -> {
                    if ("..".equals(item.name)) {
                        // Go to parent directory
                        int lastSlash = currentPath.lastIndexOf('/');
                        if (lastSlash > 0) {
                            currentPath = currentPath.substring(0, lastSlash);
                        } else {
                            currentPath = "/";
                        }
                        loadFiles();
                    } else {
                        // File selected (only .so files are shown now)
                        String selectedPath = currentPath + "/" + item.name;
                        Intent resultIntent = new Intent();
                        resultIntent.putExtra(EXTRA_SELECTED_PATH, selectedPath);
                        setResult(Activity.RESULT_OK, resultIntent);
                        finish();
                    }
                });
            }
        }
    }
}