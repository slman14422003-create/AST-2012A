package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * بطاقات قائمة "الملفات السحابية": اسم الملف + الحجم/التاريخ + فتح/تعديل
 * (إعادة تسمية أو استبدال المحتوى)/حذف. نفس أسلوب CaseRowAdapter بالضبط.
 */
public class CloudFileAdapter extends RecyclerView.Adapter<CloudFileAdapter.ViewHolder> {

    public interface Callback {
        void onOpen(CloudFile file);
        void onRename(CloudFile file);
        void onReplace(CloudFile file);
        void onDelete(CloudFile file);
    }

    private List<CloudFile> items = new ArrayList<>();
    private final Callback callback;

    public CloudFileAdapter(Callback callback) {
        this.callback = callback;
    }

    public void setItems(List<CloudFile> newItems) {
        this.items = newItems == null ? new ArrayList<>() : newItems;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cloud_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final CloudFile item = items.get(position);
        Context ctx = holder.itemView.getContext();

        holder.title.setText(item.name);
        holder.icon.setImageResource(iconFor(item.extension()));

        StringBuilder meta = new StringBuilder(Formatter.formatShortFileSize(ctx, item.size));
        if (item.uploadedAt > 0) {
            meta.append(" · ").append(formatDate(item.uploadedAt));
        }
        holder.meta.setText(meta.toString());

        holder.itemView.setOnClickListener(v -> { if (callback != null) callback.onOpen(item); });
        holder.renameBtn.setOnClickListener(v -> { if (callback != null) callback.onRename(item); });
        holder.replaceBtn.setOnClickListener(v -> { if (callback != null) callback.onReplace(item); });
        holder.deleteBtn.setOnClickListener(v -> { if (callback != null) callback.onDelete(item); });

        Ui.applyPressFeedback(holder.itemView);
        Ui.applyPressFeedback(holder.renameBtn);
        Ui.applyPressFeedback(holder.replaceBtn);
        Ui.applyPressFeedback(holder.deleteBtn);
    }

    private static int iconFor(String ext) {
        if ("pdf".equals(ext)) return R.drawable.ic_clipboard;
        return R.drawable.ic_folder;
    }

    private static String formatDate(long millis) {
        return new SimpleDateFormat("d MMM yyyy، HH:mm", new Locale("ar")).format(new java.util.Date(millis));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final android.widget.ImageView icon;
        final TextView title;
        final TextView meta;
        final TextView renameBtn;
        final TextView replaceBtn;
        final TextView deleteBtn;

        ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.row_icon);
            title = itemView.findViewById(R.id.row_title);
            meta = itemView.findViewById(R.id.row_meta);
            renameBtn = itemView.findViewById(R.id.btn_rename);
            replaceBtn = itemView.findViewById(R.id.btn_replace);
            deleteBtn = itemView.findViewById(R.id.btn_delete);
        }
    }
}
