package com.ast2012a.clinicalmaster;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class CaseAdapter extends RecyclerView.Adapter<CaseAdapter.ViewHolder> {

    public interface OnCaseClickListener {
        void onCaseClick(CaseItem item);
    }

    public interface OnFavoriteToggleListener {
        void onFavoriteToggle(CaseItem item, boolean nowFavorite);
    }

    private List<CaseItem> items = new ArrayList<>();
    private final OnCaseClickListener listener;
    private OnFavoriteToggleListener favoriteListener;

    public CaseAdapter(OnCaseClickListener listener) {
        this.listener = listener;
    }

    public void setOnFavoriteToggleListener(OnFavoriteToggleListener l) {
        this.favoriteListener = l;
    }

    public void setItems(List<CaseItem> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_case, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CaseItem item = items.get(position);
        holder.title.setText(item.title);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onCaseClick(item);
        });

        boolean isFavorite = FavoritesManager.isFavorite(holder.itemView.getContext(), item.title);
        holder.favorite.setImageResource(isFavorite ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
        holder.favorite.setOnClickListener(v -> {
            boolean nowFavorite = FavoritesManager.toggleFavorite(holder.itemView.getContext(), item.title);
            holder.favorite.setImageResource(nowFavorite ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
            if (favoriteListener != null) favoriteListener.onFavoriteToggle(item, nowFavorite);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView title;
        ImageView favorite;
        ViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.case_title);
            favorite = itemView.findViewById(R.id.case_favorite);
        }
    }
}
