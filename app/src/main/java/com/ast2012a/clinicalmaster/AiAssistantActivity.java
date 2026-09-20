package com.ast2012a.clinicalmaster;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * شاشة المساعد الذكي. تعمل دائمًا عبر رابط Cloudflare Worker الثابت
 * المبني داخل التطبيق (AiClient.FIXED_WORKER_URL) - بدون أي إعداد أو
 * مفتاح مطلوب من المستخدم.
 *
 * كل سؤال يمر بمرحلتين تأريض (Grounding) قبل الوصول للنموذج، عشان تقل
 * الهلوسة والإجابات المخترعة قدر الإمكان:
 * 1) قاعدة بيانات الجهاز المحلية (البروتوكولات الموثقة لأنماط AST-2012A).
 * 2) موسوعة ويكيبيديا (عربي ثم إنجليزي) - مصدر معرفة عام موثوق ومجاني
 *    بالكامل بدون مفتاح، يُستخدم كخلفية طبية/علمية عامة تكمّل قاعدة الجهاز.
 *
 * أي إجابة استندت لأحد المصدرين تُعرض ومعها شارة مصدر شفافة قابلة للفتح.
 */
public class AiAssistantActivity extends AppCompatActivity {

    private RecyclerView chatList;
    private ChatAdapter adapter;
    private TextInputEditText input;
    private View typingIndicator;
    private TextView typingText;
    private View emptyState;
    private LinearLayout quickPromptsRow;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String[] QUICK_PROMPTS = {
            "اشرحلي الفرق بين TENS و EMS",
            "بروتوكول مقترح لآلام أسفل الظهر",
            "احتياطات السلامة العامة قبل أي جلسة",
            "ما هو عرق النسا؟",
    };

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
        typingText = findViewById(R.id.typing_text);
        emptyState = findViewById(R.id.empty_state);
        quickPromptsRow = findViewById(R.id.quick_prompts_row);
        FloatingActionButton sendBtn = findViewById(R.id.btn_send);

        startSparkleSpin(findViewById(R.id.typing_sparkle));

        adapter = new ChatAdapter(this::openSaveAsCase);
        adapter.setOnRegenerateListener(this::regenerateAnswer);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        chatList.setLayoutManager(lm);
        chatList.setAdapter(adapter);
        LayoutAnimationController controller =
                AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_stagger);
        chatList.setLayoutAnimation(controller);

        sendBtn.setOnClickListener(v -> onSendClicked());

        // استرجاع سجل المحادثة المحفوظ محليًا (لو موجود) قبل أي شيء تاني
        adapter.setMessages(AiChatStore.load(this));
        if (adapter.getItemCount() > 0) {
            chatList.scrollToPosition(adapter.getItemCount() - 1);
        }

        setupQuickPrompts();
        refreshQuickPromptsVisibility();

        String prefillQuery = getIntent().getStringExtra("prefill_query");
        if (prefillQuery != null && !prefillQuery.isEmpty()) {
            input.setText(prefillQuery);
        }
    }

    /** دوران مستمر بطيء لنجمة مؤشر التفكير (مثل نجمة Claude وهي تفكر). */
    private void startSparkleSpin(View sparkle) {
        if (sparkle == null) return;
        ObjectAnimator anim = ObjectAnimator.ofFloat(sparkle, "rotation", 0f, 360f);
        anim.setDuration(2400);
        anim.setInterpolator(new android.view.animation.LinearInterpolator());
        anim.setRepeatCount(ObjectAnimator.INFINITE);
        anim.start();
    }

    /** صفوف اقتراحات سريعة (كبسولات بعرض كامل) تظهر فقط لما المحادثة تكون فاضية،
     *  عشان توجّه المستخدم لأنواع الأسئلة اللي المساعد الذكي يقدر يساعد فيها. */
    private void setupQuickPrompts() {
        quickPromptsRow.removeAllViews();
        for (String prompt : QUICK_PROMPTS) {
            TextView row = new TextView(this);
            row.setText(prompt);
            row.setTextColor(getColor(R.color.text_primary));
            row.setTextSize(14.5f);
            row.setTextDirection(View.TEXT_DIRECTION_RTL);
            row.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_suggestion_row);
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_sparkle, 0, 0, 0);
            row.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.claude_orange)));
            row.setCompoundDrawablePadding(Ui.dp(this, 12));
            row.setMinHeight(Ui.dp(this, 52));
            row.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 10);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> sendQuery(prompt, true));
            quickPromptsRow.addView(row);
        }
    }

    private void refreshQuickPromptsVisibility() {
        emptyState.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
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
        new MaterialAlertDialogBuilder(this)
                .setTitle("مسح المحادثة")
                .setMessage("هل تريد مسح كل سجل المحادثة مع Phizyo AI نهائيًا؟")
                .setPositiveButton("مسح", (dialog, which) -> {
                    adapter.clearAll();
                    AiChatStore.clear(this);
                    refreshQuickPromptsVisibility();
                    Toast.makeText(this, "تم مسح المحادثة.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void onSendClicked() {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) return;
        sendQuery(text, true);
    }

    /** إعادة محاولة رد سابق: بيرسل نفس سؤال المستخدم الأصلي تاني بدون ما
     *  يكرر فقاعة السؤال في المحادثة (already visible above). */
    private void regenerateAnswer(String originalQuery) {
        sendQuery(originalQuery, false);
    }

    /**
     * المسار الموحّد لإرسال أي سؤال (سواء مكتوب يدويًا، من شريحة اقتراح
     * سريع، أو إعادة محاولة). القرار كامل بين "دردشة طبيعية مباشرة" أو
     * "بحث/تأريض ثم رد" بقى مسؤولية AiOrchestrator - والنموذج نفسه هو
     * اللي يحسم الاختيار ده مش قاعدة ثابتة في الشاشة.
     */
    private void sendQuery(String text, boolean addUserBubble) {
        if (text == null || text.trim().isEmpty()) return;

        if (addUserBubble) {
            adapter.addMessage(new ChatMessage(ChatMessage.ROLE_USER, text));
            AiChatStore.save(this, adapter.getMessages());
            refreshQuickPromptsVisibility();
            chatList.smoothScrollToPosition(adapter.getItemCount() - 1);
            input.setText("");
        }

        setTypingStage(0);

        executor.execute(() -> AiOrchestrator.answer(this, text,
                new AiOrchestrator.StageListener() {
                    @Override public void onClassifying() { runOnUiThread(() -> setTypingStage(0)); }
                    @Override public void onSearching() { runOnUiThread(() -> setTypingStage(1)); }
                    @Override public void onThinking() { runOnUiThread(() -> setTypingStage(2)); }
                },
                new AiOrchestrator.ResultCallback() {
                    @Override
                    public void onChatReply(String reply) {
                        runOnUiThread(() -> {
                            typingIndicator.setVisibility(View.GONE);
                            adapter.addMessage(new ChatMessage(ChatMessage.ROLE_AI, reply, null, null, text));
                            AiChatStore.save(AiAssistantActivity.this, adapter.getMessages());
                            chatList.smoothScrollToPosition(adapter.getItemCount() - 1);
                        });
                    }

                    @Override
                    public void onGroundedReply(String reply, String sourceLabel, String sourceUrl) {
                        runOnUiThread(() -> {
                            typingIndicator.setVisibility(View.GONE);
                            adapter.addMessage(new ChatMessage(ChatMessage.ROLE_AI, reply, sourceLabel, sourceUrl, text));
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

    /** ثلاث مراحل بس للمؤشر: 0) بيفهم قصدك (تصنيف خفيف) 1) بيبحث (لو
     *  احتاج فعلًا) 2) بيكتب الرد النهائي. */
    private void setTypingStage(int stage) {
        typingIndicator.setVisibility(View.VISIBLE);
        String label;
        switch (stage) {
            case 1:
                label = "يبحث في Physiopedia وقاعدة بيانات الجهاز...";
                break;
            case 2:
                label = "يكتب الرد...";
                break;
            default:
                label = "بيفهم قصدك...";
        }
        typingText.setText(label);
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
