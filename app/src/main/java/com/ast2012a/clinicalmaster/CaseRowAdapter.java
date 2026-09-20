package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * بطاقات "حالاتي": العنوان + شارات النمط (TENS/EMS مع رقم النمط) + ملخص
 * المدة والتردد + نجمة المفضلة + تعديل/حذف. الضغط على البطاقة يفتح التفاصيل.
 */
public class CaseRowAdapter extends RecyclerView.Adapter<CaseRowAdapter.ViewHolder> {

    public interface Callback {
        void onOpen(CaseItem item);
        void onEdit(CaseItem item);
        void onDelete(CaseItem item);
        void onToggleFavorite(CaseItem item);
    }

    private List<CaseItem> items = new ArrayList<>();
    private final Callback callback;

    public CaseRowAdapter(Callback callback) {
        this.callback = callback;
    }

    public void setItems(List<CaseItem> newItems) {
        this.items = newItems == null ? new ArrayList<CaseItem>() : newItems;
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
        final CaseItem item = items.get(position);
        Context ctx = holder.itemView.getContext();

        holder.title.setText(BidiText.fix(item.title));

        // الشارات
        holder.badges.removeAllViews();
        ProtocolParser.Modes modes = ProtocolParser.parseModes(item.mode);
        int shown = 0;
        for (ProtocolParser.ModeChip chip : modes.chips) {
            if (shown == 3) break;
            addBadge(holder.badges, (chip.type.isEmpty() ? "" : chip.type + " ") + chip.number, chip.type);
            shown++;
        }
        if (modes.chips.size() > 3) {
            addBadge(holder.badges, "+" + (modes.chips.size() - 3), "");
        }
        if (modes.chips.isEmpty()) {
            if (ProtocolParser.hasTens(item.mode)) addBadge(holder.badges, "TENS", "TENS");
            if (ProtocolParser.hasEms(item.mode)) addBadge(holder.badges, "EMS", "EMS");
        }
        holder.badges.setVisibility(holder.badges.getChildCount() == 0 ? View.GONE : View.VISIBLE);

        // ملخص المدة والتردد
        StringBuilder meta = new StringBuilder();
        if (item.duration != null && !item.duration.trim().isEmpty()) meta.append(item.duration.trim());
        String freq = ProtocolParser.shortFrequency(item.freq);
        if (!freq.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(freq);
        }
        if (meta.length() == 0) {
            holder.meta.setVisibility(View.GONE);
        } else {
            holder.meta.setText(BidiText.fix(meta.toString()));
            holder.meta.setVisibility(View.VISIBLE);
        }

        boolean fav = FavoritesManager.isFavorite(ctx, item.title);
        holder.favorite.setImageResource(fav ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);

        holder.itemView.setOnClickListener(v -> { if (callback != null) callback.onOpen(item); });
        holder.favorite.setOnClickListener(v -> {
            Ui.popAnimation(holder.favorite);
            if (callback != null) callback.onToggleFavorite(item);
        });
        holder.editBtn.setOnClickListener(v -> { if (callback != null) callback.onEdit(item); });
        holder.deleteBtn.setOnClickListener(v -> { if (callback != null) callback.onDelete(item); });

        Ui.applyPressFeedback(holder.itemView);
        Ui.applyPressFeedback(holder.editBtn);
        Ui.applyPressFeedback(holder.deleteBtn);
    }

    private void addBadge(LinearLayout parent, String text, String type) {
        Context ctx = parent.getContext();
        TextView badge = (TextView) LayoutInflater.from(ctx).inflate(R.layout.item_badge, parent, false);
        badge.setText(text);
        if ("EMS".equals(type)) {
            badge.setBackgroundResource(R.drawable.bg_badge_ems);
            badge.setTextColor(ctx.getColor(R.color.m3_on_primary_container));
        } else {
            badge.setBackgroundResource(R.drawable.bg_badge_tens);
            badge.setTextColor(ctx.getColor(R.color.m3_on_secondary_container));
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) badge.getLayoutParams();
        lp.setMarginEnd(Ui.dp(ctx, 6));
        parent.addView(badge);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView meta;
        final LinearLayout badges;
        final ImageView favorite;
        final TextView editBtn;
        final TextView deleteBtn;

        ViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.row_title);
            meta = itemView.findViewById(R.id.row_meta);
            badges = itemView.findViewById(R.id.row_badges);
            favorite = itemView.findViewById(R.id.row_favorite);
            editBtn = itemView.findViewById(R.id.btn_edit);
            deleteBtn = itemView.findViewById(R.id.btn_delete);
        }
    }
}
