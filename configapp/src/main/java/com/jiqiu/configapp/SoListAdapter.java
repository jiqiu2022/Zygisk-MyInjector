package com.jiqiu.configapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SoListAdapter extends RecyclerView.Adapter<SoListAdapter.ViewHolder> {
    
    private List<ConfigManager.SoFile> soFiles = new ArrayList<>();
    private OnSoFileActionListener listener;
    
    public interface OnSoFileActionListener {
        void onDeleteClick(ConfigManager.SoFile soFile);
    }
    
    public void setSoFiles(List<ConfigManager.SoFile> files) {
        this.soFiles = files;
        notifyDataSetChanged();
    }
    
    public void setOnSoFileActionListener(OnSoFileActionListener listener) {
        this.listener = listener;
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_so_file, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ConfigManager.SoFile soFile = soFiles.get(position);
        holder.bind(soFile);
    }
    
    @Override
    public int getItemCount() {
        return soFiles.size();
    }
    
    class ViewHolder extends RecyclerView.ViewHolder {
        private TextView textFileName;
        private TextView textFilePath;
        private ImageButton buttonDelete;
        
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            textFileName = itemView.findViewById(R.id.textFileName);
            textFilePath = itemView.findViewById(R.id.textFilePath);
            buttonDelete = itemView.findViewById(R.id.buttonDelete);
        }
        
        public void bind(ConfigManager.SoFile soFile) {
            textFileName.setText(soFile.name);
            textFilePath.setText(soFile.originalPath);
            
            buttonDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteClick(soFile);
                }
            });
        }
    }
}