package com.jiqiu.configapp;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.LinearLayout;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.ContentResolver;
import android.database.Cursor;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;
import org.json.JSONObject;

public class GadgetConfigDialog extends DialogFragment {
    
    // UI elements
    private RadioGroup modeRadioGroup;
    private RadioButton radioModeServer;
    private RadioButton radioModeScript;
    private LinearLayout serverModeLayout;
    private LinearLayout scriptModeLayout;
    private RadioGroup addressRadioGroup;
    private RadioButton radioAddressAll;
    private RadioButton radioAddressLocal;
    private RadioButton radioAddressCustom;
    private EditText editCustomAddress;
    private EditText editPort;
    private RadioGroup portConflictRadioGroup;
    private RadioButton radioConflictFail;
    private RadioButton radioConflictPickNext;
    private RadioGroup onLoadRadioGroup;
    private RadioButton radioLoadWait;
    private RadioButton radioLoadResume;
    private EditText editScriptPath;
    private EditText editGadgetName;
    private EditText editJsonPreview;
    
    // Configuration data
    private ConfigManager.GadgetConfig config;
    private OnGadgetConfigListener listener;
    private String customTitle;
    
    // Flag to prevent recursive updates
    private boolean isUpdatingUI = false;
    
    // Activity result launchers
    private ActivityResultLauncher<Intent> fileBrowserLauncher;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    
    public interface OnGadgetConfigListener {
        void onGadgetConfigSaved(ConfigManager.GadgetConfig config);
    }
    
    public static GadgetConfigDialog newInstance(ConfigManager.GadgetConfig config) {
        GadgetConfigDialog dialog = new GadgetConfigDialog();
        dialog.config = config != null ? config : new ConfigManager.GadgetConfig();
        return dialog;
    }
    
    // Constructor for non-fragment usage
    public GadgetConfigDialog(Context context, String title, ConfigManager.GadgetConfig config, OnGadgetConfigListener listener) {
        // This constructor is for compatibility with direct dialog creation
        // The actual dialog will be created in show() method
        this.savedContext = context;
        this.customTitle = title;
        this.config = config != null ? config : new ConfigManager.GadgetConfig();
        this.listener = listener;
    }
    
    // Default constructor required for DialogFragment
    public GadgetConfigDialog() {
        // Empty constructor required
    }
    
    public void setOnGadgetConfigListener(OnGadgetConfigListener listener) {
        this.listener = listener;
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize file browser launcher
        fileBrowserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    String selectedPath = result.getData().getStringExtra("selected_path");
                    if (selectedPath != null) {
                        editScriptPath.setText(selectedPath);
                        config.scriptPath = selectedPath;
                        updateJsonPreview();
                    }
                }
            }
        );
        
        // Initialize file picker launcher
        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        String path = getPathFromUri(uri);
                        if (path != null) {
                            editScriptPath.setText(path);
                            config.scriptPath = path;
                            updateJsonPreview();
                        } else {
                            Toast.makeText(getContext(), "无法获取文件路径", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
        );
    }
    
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_gadget_config, null);
        
        initViews(view);
        loadConfig();
        setupListeners();
        updateJsonPreview();
        
        String title = customTitle != null ? customTitle : "Gadget 配置";
        
        return new MaterialAlertDialogBuilder(getContext())
                .setTitle(title)
                .setView(view)
                .setPositiveButton("保存", (dialog, which) -> saveConfig())
                .setNegativeButton("取消", null)
                .create();
    }
    
    private void initViews(View view) {
        modeRadioGroup = view.findViewById(R.id.modeRadioGroup);
        radioModeServer = view.findViewById(R.id.radioModeServer);
        radioModeScript = view.findViewById(R.id.radioModeScript);
        serverModeLayout = view.findViewById(R.id.serverModeLayout);
        scriptModeLayout = view.findViewById(R.id.scriptModeLayout);
        addressRadioGroup = view.findViewById(R.id.addressRadioGroup);
        radioAddressAll = view.findViewById(R.id.radioAddressAll);
        radioAddressLocal = view.findViewById(R.id.radioAddressLocal);
        radioAddressCustom = view.findViewById(R.id.radioAddressCustom);
        editCustomAddress = view.findViewById(R.id.editCustomAddress);
        editPort = view.findViewById(R.id.editPort);
        portConflictRadioGroup = view.findViewById(R.id.portConflictRadioGroup);
        radioConflictFail = view.findViewById(R.id.radioConflictFail);
        radioConflictPickNext = view.findViewById(R.id.radioConflictPickNext);
        onLoadRadioGroup = view.findViewById(R.id.onLoadRadioGroup);
        radioLoadWait = view.findViewById(R.id.radioLoadWait);
        radioLoadResume = view.findViewById(R.id.radioLoadResume);
        editScriptPath = view.findViewById(R.id.editScriptPath);
        editGadgetName = view.findViewById(R.id.editGadgetName);
        editJsonPreview = view.findViewById(R.id.editJsonPreview);
        
        // File select button
        View btnSelectScript = view.findViewById(R.id.btnSelectScript);
        if (btnSelectScript != null) {
            btnSelectScript.setOnClickListener(v -> selectScriptFile());
        }
    }
    
    private void loadConfig() {
        isUpdatingUI = true;
        
        // Load mode
        if ("script".equals(config.mode)) {
            radioModeScript.setChecked(true);
            serverModeLayout.setVisibility(View.GONE);
            scriptModeLayout.setVisibility(View.VISIBLE);
        } else {
            radioModeServer.setChecked(true);
            serverModeLayout.setVisibility(View.VISIBLE);
            scriptModeLayout.setVisibility(View.GONE);
        }
        
        // Load address
        if ("127.0.0.1".equals(config.address)) {
            radioAddressLocal.setChecked(true);
        } else if ("0.0.0.0".equals(config.address)) {
            radioAddressAll.setChecked(true);
        } else {
            radioAddressCustom.setChecked(true);
            editCustomAddress.setText(config.address);
            editCustomAddress.setEnabled(true);
        }
        
        // Load port
        editPort.setText(String.valueOf(config.port));
        
        // Load port conflict handling
        if ("pick-next".equals(config.onPortConflict)) {
            radioConflictPickNext.setChecked(true);
        } else {
            radioConflictFail.setChecked(true);
        }
        
        // Load on load handling
        if ("resume".equals(config.onLoad)) {
            radioLoadResume.setChecked(true);
        } else {
            radioLoadWait.setChecked(true);
        }
        
        // Load script path
        editScriptPath.setText(config.scriptPath);
        
        // Load gadget name
        editGadgetName.setText(config.gadgetName);
        
        isUpdatingUI = false;
    }
    
    private void setupListeners() {
        // Mode radio group listener
        modeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (!isUpdatingUI) {
                if (checkedId == R.id.radioModeScript) {
                    config.mode = "script";
                    serverModeLayout.setVisibility(View.GONE);
                    scriptModeLayout.setVisibility(View.VISIBLE);
                } else {
                    config.mode = "server";
                    serverModeLayout.setVisibility(View.VISIBLE);
                    scriptModeLayout.setVisibility(View.GONE);
                }
                updateJsonPreview();
            }
        });
        
        // Address radio group listener
        addressRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (!isUpdatingUI) {
                if (checkedId == R.id.radioAddressCustom) {
                    editCustomAddress.setEnabled(true);
                    editCustomAddress.requestFocus();
                } else {
                    editCustomAddress.setEnabled(false);
                    if (checkedId == R.id.radioAddressAll) {
                        config.address = "0.0.0.0";
                    } else if (checkedId == R.id.radioAddressLocal) {
                        config.address = "127.0.0.1";
                    }
                    updateJsonPreview();
                }
            }
        });
        
        // Custom address text watcher
        editCustomAddress.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdatingUI && radioAddressCustom.isChecked()) {
                    config.address = s.toString().trim();
                    updateJsonPreview();
                }
            }
        });
        
        // Port text watcher
        editPort.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdatingUI) {
                    try {
                        int port = Integer.parseInt(s.toString());
                        if (port >= 1 && port <= 65535) {
                            config.port = port;
                            updateJsonPreview();
                        }
                    } catch (NumberFormatException e) {
                        // Ignore invalid input
                    }
                }
            }
        });
        
        // Port conflict radio group listener
        portConflictRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (!isUpdatingUI) {
                config.onPortConflict = (checkedId == R.id.radioConflictPickNext) ? "pick-next" : "fail";
                updateJsonPreview();
            }
        });
        
        // On load radio group listener
        onLoadRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (!isUpdatingUI) {
                config.onLoad = (checkedId == R.id.radioLoadResume) ? "resume" : "wait";
                updateJsonPreview();
            }
        });
        
        // Script path text watcher
        editScriptPath.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdatingUI) {
                    config.scriptPath = s.toString().trim();
                    updateJsonPreview();
                }
            }
        });
        
        // Gadget name text watcher
        editGadgetName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdatingUI) {
                    config.gadgetName = s.toString().trim();
                }
            }
        });
        
        // JSON preview text watcher
        editJsonPreview.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdatingUI) {
                    parseJsonAndUpdateUI(s.toString());
                }
            }
        });
    }
    
    private void updateJsonPreview() {
        if (isUpdatingUI) return;
        
        try {
            JSONObject root = new JSONObject();
            JSONObject interaction = new JSONObject();
            
            if ("script".equals(config.mode)) {
                interaction.put("type", "script");
                interaction.put("path", config.scriptPath);
            } else {
                interaction.put("type", "listen");
                interaction.put("address", config.address);
                interaction.put("port", config.port);
                interaction.put("on_port_conflict", config.onPortConflict);
                interaction.put("on_load", config.onLoad);
            }
            
            root.put("interaction", interaction);
            
            isUpdatingUI = true;
            editJsonPreview.setText(root.toString(2));
            isUpdatingUI = false;
        } catch (JSONException e) {
            // Should not happen
            e.printStackTrace();
        }
    }
    
    private void parseJsonAndUpdateUI(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject interaction = root.getJSONObject("interaction");
            
            isUpdatingUI = true;
            
            // Update mode
            String type = interaction.getString("type");
            if ("script".equals(type)) {
                config.mode = "script";
                radioModeScript.setChecked(true);
                serverModeLayout.setVisibility(View.GONE);
                scriptModeLayout.setVisibility(View.VISIBLE);
                
                // Update script path
                if (interaction.has("path")) {
                    config.scriptPath = interaction.getString("path");
                    editScriptPath.setText(config.scriptPath);
                }
            } else {
                config.mode = "server";
                radioModeServer.setChecked(true);
                serverModeLayout.setVisibility(View.VISIBLE);
                scriptModeLayout.setVisibility(View.GONE);
                
            // Update address
            String address = interaction.getString("address");
            config.address = address;
            if ("0.0.0.0".equals(address)) {
                radioAddressAll.setChecked(true);
                editCustomAddress.setEnabled(false);
            } else if ("127.0.0.1".equals(address)) {
                radioAddressLocal.setChecked(true);
                editCustomAddress.setEnabled(false);
            } else {
                radioAddressCustom.setChecked(true);
                editCustomAddress.setText(address);
                editCustomAddress.setEnabled(true);
            }
            
            // Update port
            config.port = interaction.getInt("port");
            editPort.setText(String.valueOf(config.port));
            
            // Update port conflict
            String onPortConflict = interaction.getString("on_port_conflict");
            config.onPortConflict = onPortConflict;
            if ("pick-next".equals(onPortConflict)) {
                radioConflictPickNext.setChecked(true);
            } else {
                radioConflictFail.setChecked(true);
            }
            
            // Update on load
            String onLoad = interaction.getString("on_load");
            config.onLoad = onLoad;
            if ("resume".equals(onLoad)) {
                radioLoadResume.setChecked(true);
            } else {
                radioLoadWait.setChecked(true);
            }
            }
            
            isUpdatingUI = false;
        } catch (JSONException e) {
            // Invalid JSON, ignore
        }
    }
    
    private void saveConfig() {
        if (listener != null) {
            // Ensure gadget name is not empty
            if (config.gadgetName == null || config.gadgetName.trim().isEmpty()) {
                config.gadgetName = "libgadget.so";
            }
            listener.onGadgetConfigSaved(config);
        }
    }
    
    private void selectScriptFile() {
        String[] options = {"浏览文件系统", "从外部文件管理器选择", "手动输入路径"};
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("选择 Script 文件")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openFileBrowser();
                    } else if (which == 1) {
                        openFilePicker();
                    } else {
                        showPathInputDialog();
                    }
                })
                .show();
    }
    
    private void openFileBrowser() {
        // Show path selection dialog first
        String[] paths = {
            "/data/local/tmp",
            "/sdcard",
            "/sdcard/Download",
            "/storage/emulated/0",
            "自定义路径..."
        };
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("选择起始目录")
                .setItems(paths, (dialog, which) -> {
                    if (which == paths.length - 1) {
                        // Custom path
                        showCustomPathDialog();
                    } else {
                        Intent intent = new Intent(getContext(), FileBrowserActivity.class);
                        intent.putExtra(FileBrowserActivity.EXTRA_START_PATH, paths[which]);
                        intent.putExtra(FileBrowserActivity.EXTRA_FILE_FILTER, ".js");
                        fileBrowserLauncher.launch(intent);
                    }
                })
                .show();
    }
    
    private void showCustomPathDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_input, null);
        android.widget.EditText editText = view.findViewById(android.R.id.edit);
        editText.setText("/");
        editText.setHint("输入起始路径");
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("自定义起始路径")
                .setView(view)
                .setPositiveButton("确定", (dialog, which) -> {
                    String path = editText.getText().toString().trim();
                    if (!path.isEmpty()) {
                        Intent intent = new Intent(getContext(), FileBrowserActivity.class);
                        intent.putExtra(FileBrowserActivity.EXTRA_START_PATH, path);
                        intent.putExtra(FileBrowserActivity.EXTRA_FILE_FILTER, ".js");
                        fileBrowserLauncher.launch(intent);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Add MIME types that might help filter JS files
        String[] mimeTypes = {"text/javascript", "application/javascript", "text/plain", "*/*"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        // Suggest starting location
        intent.putExtra("android.provider.extra.INITIAL_URI", 
            android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
        filePickerLauncher.launch(intent);
    }
    
    private void showPathInputDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_input, null);
        android.widget.EditText editText = view.findViewById(android.R.id.edit);
        editText.setText("/data/local/tmp/");
        editText.setHint("/data/local/tmp/script.js");
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("输入 Script 文件路径")
                .setView(view)
                .setPositiveButton("确定", (dialog, which) -> {
                    String path = editText.getText().toString().trim();
                    if (!path.isEmpty()) {
                        editScriptPath.setText(path);
                        config.scriptPath = path;
                        updateJsonPreview();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    // Show method for non-fragment usage
    public void show() {
        if (getContext() == null) {
            throw new IllegalStateException("Context is required for non-fragment usage");
        }
        
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_gadget_config, null);
        initViews(view);
        
        // Initialize config if null
        if (config == null) {
            config = new ConfigManager.GadgetConfig();
        }
        
        loadConfig();
        setupListeners();
        updateJsonPreview();
        
        String title = customTitle != null ? customTitle : "Gadget 配置";
        
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(title)
                .setView(view)
                .setPositiveButton("保存", (dialog, which) -> saveConfig())
                .setNegativeButton("取消", null)
                .show();
    }
    
    private Context savedContext;
    
    @Override
    public Context getContext() {
        Context context = super.getContext();
        if (context == null) {
            return savedContext;
        }
        return context;
    }
    
    // Constructor for non-fragment usage needs to save context
    public void setContext(Context context) {
        this.savedContext = context;
    }
    
    private String getPathFromUri(Uri uri) {
        String path = null;
        
        // Try to get path from MediaStore
        if ("content".equals(uri.getScheme())) {
            try {
                ContentResolver resolver = getContext().getContentResolver();
                try (Cursor cursor = resolver.query(uri, new String[]{"_data"}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int columnIndex = cursor.getColumnIndex("_data");
                        if (columnIndex != -1) {
                            path = cursor.getString(columnIndex);
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
            
            // Try DocumentsContract if MediaStore fails
            if (path == null && DocumentsContract.isDocumentUri(getContext(), uri)) {
                try {
                    String docId = DocumentsContract.getDocumentId(uri);
                    if (uri.getAuthority().equals("com.android.externalstorage.documents")) {
                        String[] split = docId.split(":");
                        if (split.length >= 2) {
                            String type = split[0];
                            if ("primary".equalsIgnoreCase(type)) {
                                path = "/storage/emulated/0/" + split[1];
                            } else {
                                path = "/storage/" + type + "/" + split[1];
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
        } else if ("file".equals(uri.getScheme())) {
            path = uri.getPath();
        }
        
        return path;
    }
}