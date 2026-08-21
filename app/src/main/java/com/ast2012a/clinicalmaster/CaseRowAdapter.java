package com.ast2012a.clinicalmaster;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class CaseRowAdapter extends RecyclerView.Adapter<CaseRowAdapter.ViewHolder> {

    public interface Callback {
        void onEdit(CaseItem item);
        void onDelete(CaseItem item);
    }

    private List<CaseItem> items = new ArrayList<>();
    private final Callback callback;

    public CaseRowAdapter(Callback callback) {
        this.callback = callback;
    }

    public void setItems(List<CaseItem> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_case_row, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CaseItem item = items.get(position);
        holder.title.setText(item.title);
        holder.editBtn.setOnClickListener(v -> { if (callback != null) callback.onEdit(item); });
        holder.deleteBtn.setOnClickListener(v -> { if (callback != null) callback.onDelete(item); });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView title;
        Button editBtn, deleteBtn;
        ViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.row_title);
            editBtn = itemView.findViewById(R.id.btn_edit);
            deleteBtn = itemView.findViewById(R.id.btn_delete);
        }
    }
}
