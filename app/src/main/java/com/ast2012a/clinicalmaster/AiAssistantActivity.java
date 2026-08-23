package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * شاشة المساعد الذكي. تعمل افتراضيًا بدون أي إعداد (عبر Pollinations،
 * بدون مفتاح API)، وتستخدم مفتاح OpenRouter الخاص بالمستخدم تلقائيًا لو
 * أضافه في الإعدادات. كل إجابة تُقارَن أولًا مع البروتوكولات الموثقة ذات
 * الصلة من قاعدة بيانات الجهاز (تأريض/Grounding) لتقليل الهلوسة والحفاظ
 * على الاتساق مع معلومات الجهاز الرسمية.
 */
public class AiAssistantActivity extends AppCompatActivity {

    private RecyclerView chatList;
    private ChatAdapter adapter;
    private TextInputEditText input;
    private View typingIndicator;
    private View modeHint;
    private String apiKey;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String SYSTEM_PROMPT =
            "أنت مساعد ذكي يساعد أخصائيي العلاج الطبيعي في استخدام جهاز التحفيز الكهربائي " +
            "AST-2012A (أنماط TENS وEMS). أجب بإيجاز ووضوح وبدقة سريرية باللغة العربية، " +
            "واذكر تحذيرات السلامة المهمة عند الحاجة (مثل منظمات ضربات القلب والحمل والجروح المفتوحة). " +
            "إذا زُوّدت ببروتوكولات موثقة من قاعدة بيانات الجهاز، اجعلها مرجعك الأساسي، قارن " +
            "معرفتك العامة معها بوضوح (اتفاق أو اختلاف)، واقترح خطة علاج مستقرة ومتماسكة بناءً عليها.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_assistant);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_ai_assistant);
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);

        chatList = findViewById(R.id.chat_list);
        input = findViewById(R.id.chat_input);
        typingIndicator = findViewById(R.id.typing_indicator);
        modeHint = findViewById(R.id.mode_hint);
        FloatingActionButton sendBtn = findViewById(R.id.btn_send);

        adapter = new ChatAdapter(this::openSaveAsCase);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        chatList.setLayoutManager(lm);
        chatList.setAdapter(adapter);
        android.view.animation.LayoutAnimationController controller =
                android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_stagger);
        chatList.setLayoutAnimation(controller);

        sendBtn.setOnClickListener(v -> onSendClicked());

        // استرجاع سجل المحادثة المحفوظ محليًا (لو موجود) قبل أي شيء تاني
        adapter.setMessages(AiChatStore.load(this));
        if (adapter.getItemCount() > 0) {
            chatList.scrollToPosition(adapter.getItemCount() - 1);
        }

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
        if (modeHint instanceof android.widget.TextView) {
            android.widget.TextView hint = (android.widget.TextView) modeHint;
            if (apiKey != null && !apiKey.isEmpty()) {
                hint.setText("🔑 يعمل حاليًا بمفتاح OpenRouter الخاص بك (من الإعدادات).");
            } else {
                hint.setText("🤖 يعمل حاليًا بالوضع المجاني الجاهز (بدون مفتاح). يمكنك إضافة مفتاح OpenRouter اختياري من الإعدادات لتجربة نموذج بديل.");
            }
        }
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_clear_chat) {
            confirmClearChat();
            return true;
        }
        return false;
    }

    private void confirmClearChat() {
        if (adapter.getItemCount() == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("مسح المحادثة")
                .setMessage("هل تريد مسح كل سجل المحادثة مع المساعد الذكي نهائيًا؟")
                .setPositiveButton("مسح", (dialog, which) -> {
                    adapter.clearAll();
                    AiChatStore.clear(this);
                    Toast.makeText(this, "تم مسح المحادثة.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void onSendClicked() {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) return;

        adapter.addMessage(new ChatMessage(ChatMessage.ROLE_USER, text));
        AiChatStore.save(this, adapter.getMessages());
        chatList.smoothScrollToPosition(adapter.getItemCount() - 1);
        input.setText("");
        typingIndicator.setVisibility(View.VISIBLE);

        // تأريض الإجابة: نبحث في قاعدة بيانات الجهاز عن بروتوكولات ذات صلة
        // بسؤال المستخدم أولًا، ونزوّد المساعد الذكي بها كمرجع أساسي.
        DataManager.GroundingResult grounding = DataManager.buildGroundingContext(this, text, 3);
        final String systemPromptToUse = grounding != null
                ? SYSTEM_PROMPT + "\n\nبروتوكولات موثقة ذات صلة من قاعدة بيانات الجهاز:\n" + grounding.contextText
                : SYSTEM_PROMPT;
        final int groundedCount = grounding != null ? grounding.caseCount : 0;

        executor.execute(() -> AiClient.sendMessage(apiKey, systemPromptToUse, text, new AiClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                runOnUiThread(() -> {
                    typingIndicator.setVisibility(View.GONE);
                    String finalReply = reply;
                    if (groundedCount > 0) {
                        finalReply = "🔎 تمت مقارنة الإجابة مع " + groundedCount +
                                " بروتوكول موثّق من قاعدة الجهاز.\n\n" + reply;
                    }
                    adapter.addMessage(new ChatMessage(ChatMessage.ROLE_AI, finalReply));
                    AiChatStore.save(AiAssistantActivity.this, adapter.getMessages());
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
