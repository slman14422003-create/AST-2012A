package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AiAssistantActivity extends AppCompatActivity {

    private RecyclerView chatList;
    private ChatAdapter adapter;
    private TextInputEditText input;
    private View typingIndicator;
    private View noKeyHint;
    private String apiKey;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String SYSTEM_PROMPT =
            "أنت مساعد ذكي يساعد أخصائيي العلاج الطبيعي في استخدام جهاز التحفيز الكهربائي " +
            "AST-2012A (أنماط TENS وEMS). أجب بإيجاز ووضوح وبدقة سريرية باللغة العربية، " +
            "واذكر تحذيرات السلامة المهمة عند الحاجة (مثل منظمات ضربات القلب والحمل والجروح المفتوحة).";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_assistant);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        chatList = findViewById(R.id.chat_list);
        input = findViewById(R.id.chat_input);
        typingIndicator = findViewById(R.id.typing_indicator);
        noKeyHint = findViewById(R.id.no_key_hint);
        FloatingActionButton sendBtn = findViewById(R.id.btn_send);

        adapter = new ChatAdapter(this::openSaveAsCase);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        chatList.setLayoutManager(lm);
        chatList.setAdapter(adapter);
        android.view.animation.LayoutAnimationController controller =
                android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_stagger);
        chatList.setLayoutAnimation(controller);

        sendBtn.setOnClickListener(v -> onSendClicked());

        String prefillQuery = getIntent().getStringExtra("prefill_query");
        if (prefillQuery != null && !prefillQuery.isEmpty()) {
            input.setText(prefillQuery);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences("settings_prefs", MODE_PRIVATE);
        apiKey = prefs.getString("ai_api_key", "");
        noKeyHint.setVisibility(apiKey.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void onSendClicked() {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) return;

        if (apiKey == null || apiKey.isEmpty()) {
            Toast.makeText(this, "أضف مفتاح API أولًا من شاشة الإعدادات.", Toast.LENGTH_LONG).show();
            return;
        }

        adapter.addMessage(new ChatMessage(ChatMessage.ROLE_USER, text));
        chatList.smoothScrollToPosition(adapter.getItemCount() - 1);
        input.setText("");
        typingIndicator.setVisibility(View.VISIBLE);

        executor.execute(() -> AiClient.sendMessage(apiKey, SYSTEM_PROMPT, text, new AiClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                runOnUiThread(() -> {
                    typingIndicator.setVisibility(View.GONE);
                    adapter.addMessage(new ChatMessage(ChatMessage.ROLE_AI, reply));
                    chatList.smoothScrollToPosition(adapter.getItemCount() - 1);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    typingIndicator.setVisibility(View.GONE);
                    Toast.makeText(AiAssistantActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        }));
    }

    private void openSaveAsCase(String aiText) {
        Intent i = new Intent(this, AddEditCaseActivity.class);
        i.putExtra("prefill_explanation", aiText);
        startActivity(i);
        overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
