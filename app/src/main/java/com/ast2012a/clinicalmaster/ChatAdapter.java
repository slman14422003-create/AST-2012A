package com.ast2012a.clinicalmaster;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    public interface OnSaveAsCaseListener {
        void onSaveAsCase(String aiText);
    }

    public interface OnRegenerateListener {
        void onRegenerate(String originalQuery);
    }

    private final List<ChatMessage> messages = new ArrayList<>();
    private final OnSaveAsCaseListener saveListener;
    private OnRegenerateListener regenerateListener;

    public ChatAdapter(OnSaveAsCaseListener saveListener) {
        this.saveListener = saveListener;
    }

    public void setOnRegenerateListener(OnRegenerateListener l) {
        this.regenerateListener = l;
    }

    public void addMessage(ChatMessage m) {
        messages.add(m);
        notifyItemInserted(messages.size() - 1);
    }

    /** تحميل سجل محادثة كامل دفعة واحدة (عند فتح الشاشة من جديد). */
    public void setMessages(List<ChatMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
        notifyDataSetChanged();
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void clearAll() {
        messages.clear();
        notifyDataSetChanged();
    }

    public int size() {
        return messages.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_bubble, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChatMessage m = messages.get(position);
        boolean isUser = m.role == ChatMessage.ROLE_USER;
        Context ctx = holder.itemView.getContext();

        holder.role.setText(isUser ? "أنت" : "المساعد الذكي");
        holder.avatar.setText(isUser ? "🧑" : "🤖");
        holder.time.setText(DateFormat.format("hh:mm a", m.timestamp));
        holder.text.setText(m.text);
        holder.text.setBackgroundResource(isUser ? R.drawable.bg_bubble_user : R.drawable.bg_bubble_ai);
        holder.text.setTextColor(ctx.getColor(R.color.text_primary));

        setChildGravity(holder.header, isUser ? Gravity.START : Gravity.END);
        setChildGravity(holder.text, isUser ? Gravity.START : Gravity.END);
        setChildGravity(holder.source, isUser ? Gravity.START : Gravity.END);

        // شارة المصدر: تظهر تحت رد المساعد لو الإجابة استندت لمصدر موثّق
        // (قاعدة بيانات الجهاز و/أو ويكيبيديا) - شفافية كاملة لأصل المعلومة.
        if (!isUser && m.sourceLabel != null && !m.sourceLabel.isEmpty()) {
            holder.source.setVisibility(View.VISIBLE);
            holder.source.setText("📖 المصدر: " + m.sourceLabel + (m.sourceUrl != null ? "  ↗" : ""));
            holder.source.setOnClickListener(v -> {
                if (m.sourceUrl == null) return;
                try {
                    ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(m.sourceUrl)));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(ctx, "لا يوجد متصفح متاح لفتح الرابط.", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            holder.source.setVisibility(View.GONE);
        }

        if (!isUser && saveListener != null) {
            holder.saveBtn.setVisibility(View.VISIBLE);
            holder.saveBtn.setOnClickListener(v -> saveListener.onSaveAsCase(m.text));
        } else {
            holder.saveBtn.setVisibility(View.GONE);
        }

        if (!isUser) {
            holder.copyBtn.setVisibility(View.VISIBLE);
            holder.copyBtn.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("ai_answer", m.text));
                    Toast.makeText(ctx, "تم نسخ الرد.", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            holder.copyBtn.setVisibility(View.GONE);
        }

        // إعادة المحاولة: تعيد إرسال نفس سؤال المستخدم الأصلي للحصول على رد
        // جديد، مفيدة لو الرد الحالي غير مقنع أو غير مكتمل.
        if (!isUser && regenerateListener != null && m.relatedQuery != null) {
            holder.regenerateBtn.setVisibility(View.VISIBLE);
            holder.regenerateBtn.setOnClickListener(v -> regenerateListener.onRegenerate(m.relatedQuery));
        } else {
            holder.regenerateBtn.setVisibility(View.GONE);
        }
    }

    private void setChildGravity(View view, int gravity) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).gravity = gravity;
            view.setLayoutParams(params);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View header;
        TextView role, avatar, text, time, source;
        Button saveBtn, copyBtn, regenerateBtn;
        ViewHolder(View itemView) {
            super(itemView);
            header = itemView.findViewById(R.id.bubble_header);
            role = itemView.findViewById(R.id.bubble_role);
            avatar = itemView.findViewById(R.id.bubble_avatar);
            text = itemView.findViewById(R.id.bubble_text);
            time = itemView.findViewById(R.id.bubble_time);
            source = itemView.findViewById(R.id.bubble_source);
            saveBtn = itemView.findViewById(R.id.bubble_save_case);
            copyBtn = itemView.findViewById(R.id.bubble_copy);
            regenerateBtn = itemView.findViewById(R.id.bubble_regenerate);
        }
    }
}
