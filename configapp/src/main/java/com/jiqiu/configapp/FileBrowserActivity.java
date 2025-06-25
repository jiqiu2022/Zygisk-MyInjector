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
        
        // List files using root
        Log.d(TAG, "Loading files from: " + currentPath);
        Shell.Result result = Shell.cmd("ls -la " + currentPath + " 2>/dev/null").exec();
        Log.d(TAG, "ls command success: " + result.isSuccess() + ", output lines: " + result.getOut().size());
        
        if (result.isSuccess()) {
            for (String line : result.getOut()) {
                // Skip empty lines, total line, and symbolic links
                if (line.trim().isEmpty() || line.startsWith("total") || line.contains("->")) {
                    continue;
                }
                
                // Try to parse ls output - handle different formats
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
                    }
                } else {
                    // Simple format, just the filename
                    name = line.trim();
                    // Check if it's a directory by trying to list it
                    Shell.Result dirCheck = Shell.cmd("test -d \"" + currentPath + "/" + name + "\" && echo 'dir'").exec();
                    isDirectory = dirCheck.isSuccess() && !dirCheck.getOut().isEmpty();
                }
                
                if (name != null && !".".equals(name) && !"..".equals(name)) {
                    // Filter files by extension
                    if (!isDirectory && fileFilter != null && !name.endsWith(fileFilter)) {
                        continue;
                    }
                    
                    items.add(new FileItem(name, isDirectory, isReadable));
                }
            }
        } else {
            // If ls fails, try a simpler approach
            Shell.Result simpleResult = Shell.cmd("cd " + currentPath + " && for f in *; do echo \"$f\"; done").exec();
            if (simpleResult.isSuccess()) {
                for (String name : simpleResult.getOut()) {
                    if (!name.trim().isEmpty() && !"*".equals(name)) {
                        Shell.Result dirCheck = Shell.cmd("test -d \"" + currentPath + "/" + name + "\" && echo 'dir'").exec();
                        boolean isDirectory = dirCheck.isSuccess() && !dirCheck.getOut().isEmpty();
                        
                        // Filter files by extension
                        if (!isDirectory && fileFilter != null && !name.endsWith(fileFilter)) {
                            continue;
                        }
                        
                        items.add(new FileItem(name, isDirectory, true));
                    }
                }
            }
        }
        
        // If still no items and not root, add some common directories to try
        if (items.size() <= 1 && "/data/local/tmp".equals(currentPath)) {
            // Try to create a test file to verify access
            Shell.cmd("touch /data/local/tmp/test_access.tmp && rm /data/local/tmp/test_access.tmp").exec();
            
            // Add any .so files we can find
            Shell.Result findResult = Shell.cmd("find " + currentPath + " -maxdepth 1 -name '*.so' -type f 2>/dev/null").exec();
            if (findResult.isSuccess()) {
                for (String path : findResult.getOut()) {
                    if (!path.trim().isEmpty()) {
                        String name = path.substring(path.lastIndexOf('/') + 1);
                        items.add(new FileItem(name, false, true));
                    }
                }
            }
        }
        
        Collections.sort(items, (a, b) -> {
            if (a.isDirectory != b.isDirectory) {
                return a.isDirectory ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        
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
                
                if (item.isDirectory) {
                    icon.setImageResource(android.R.drawable.ic_menu_agenda);
                    info.setText("文件夹");
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
                    } else if (item.isDirectory) {
                        if (!item.isReadable) {
                            Toast.makeText(FileBrowserActivity.this, 
                                    "没有权限访问此目录", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if ("/".equals(currentPath)) {
                            currentPath = "/" + item.name;
                        } else {
                            currentPath = currentPath + "/" + item.name;
                        }
                        loadFiles();
                    } else {
                        // File selected
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