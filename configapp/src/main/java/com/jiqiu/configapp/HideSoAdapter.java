package com.jiqiu.configapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * SO 文件隐藏列表适配器
 */
public class HideSoAdapter extends RecyclerView.Adapter<HideSoAdapter.ViewHolder> {
    
    private final List<KpmHideFragment.HideSoItem> items;
    private final OnItemCheckedChangeListener listener;
    
    public interface OnItemCheckedChangeListener {
        void onItemCheckedChanged(KpmHideFragment.HideSoItem item, boolean isChecked);
    }
    
    public HideSoAdapter(List<KpmHideFragment.HideSoItem> items, 
                        OnItemCheckedChangeListener listener) {
        this.items = items;
        this.listener = listener;
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_hide_so, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        KpmHideFragment.HideSoItem item = items.get(position);
        holder.bind(item);
    }
    
    @Override
    public int getItemCount() {
        return items.size();
    }
    
    class ViewHolder extends RecyclerView.ViewHolder {
        
        private final CheckBox cbHide;
        private final TextView tvSoName;
        private final TextView tvSoStatus;
        private final TextView tvFixedBadge;
        
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            cbHide = itemView.findViewById(R.id.cbHide);
            tvSoName = itemView.findViewById(R.id.tvSoName);
            tvSoStatus = itemView.findViewById(R.id.tvSoStatus);
            tvFixedBadge = itemView.findViewById(R.id.tvFixedBadge);
        }
        
        public void bind(KpmHideFragment.HideSoItem item) {
            tvSoName.setText(item.soName);
            
            // 设置勾选状态
            cbHide.setOnCheckedChangeListener(null); // 先移除监听器避免触发
            cbHide.setChecked(item.isHidden);
            
            // 显示状态
            if (item.isFixed) {
                tvSoStatus.setText("必需项 - 始终隐藏");
                tvSoStatus.setTextColor(itemView.getContext().getResources()
                        .getColor(android.R.color.holo_green_dark, null));
                tvFixedBadge.setVisibility(View.VISIBLE);
                cbHide.setEnabled(false);
                cbHide.setChecked(true); // 固定项始终勾选
            } else {
                tvSoStatus.setText(item.isHidden ? "已隐藏" : "未隐藏");
                tvSoStatus.setTextColor(itemView.getContext().getResources()
                        .getColor(android.R.color.darker_gray, null));
                tvFixedBadge.setVisibility(View.GONE);
                cbHide.setEnabled(true);
            }
            
            // 设置点击监听器
            cbHide.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (listener != null && !item.isFixed) {
                    listener.onItemCheckedChanged(item, isChecked);
                }
            });
            
            // 整行点击也触发勾选
            itemView.setOnClickListener(v -> {
                if (!item.isFixed) {
                    cbHide.setChecked(!cbHide.isChecked());
                }
            });
        }
    }
}

