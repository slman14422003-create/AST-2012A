package com.ast2012a.clinicalmaster;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    public interface OnSaveAsCaseListener {
        void onSaveAsCase(String aiText);
    }

    private final List<ChatMessage> messages = new ArrayList<>();
    private final OnSaveAsCaseListener saveListener;

    public ChatAdapter(OnSaveAsCaseListener saveListener) {
        this.saveListener = saveListener;
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

        holder.role.setText(isUser ? "أنت" : "🤖 المساعد الذكي");
        holder.text.setText(m.text);
        holder.text.setBackgroundResource(isUser ? R.drawable.bg_bubble_user : R.drawable.bg_bubble_ai);
        holder.text.setTextColor(holder.itemView.getContext().getColor(
                isUser ? R.color.white : R.color.text_primary));

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) holder.text.getLayoutParams();
        lp.gravity = isUser ? Gravity.START : Gravity.END;
        holder.text.setLayoutParams(lp);

        LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) holder.role.getLayoutParams();
        rp.gravity = isUser ? Gravity.START : Gravity.END;
        holder.role.setLayoutParams(rp);

        if (!isUser && saveListener != null) {
            holder.saveBtn.setVisibility(View.VISIBLE);
            holder.saveBtn.setOnClickListener(v -> saveListener.onSaveAsCase(m.text));
        } else {
            holder.saveBtn.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView role, text;
        Button saveBtn;
        ViewHolder(View itemView) {
            super(itemView);
            role = itemView.findViewById(R.id.bubble_role);
            text = itemView.findViewById(R.id.bubble_text);
            saveBtn = itemView.findViewById(R.id.bubble_save_case);
        }
    }
}
