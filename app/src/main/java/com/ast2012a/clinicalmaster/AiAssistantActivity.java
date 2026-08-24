package com.ast2012a.clinicalmaster;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * شاشة المساعد الذكي. تعمل افتراضيًا بدون أي إعداد (عبر مزوّدين مجانيين
 * بالتناوب التلقائي، بدون مفتاح)، وتستخدم مفتاح OpenRouter الخاص
 * بالمستخدم تلقائيًا لو أضافه في الإعدادات.
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
    private View modeHint;
    private View quickPromptsScroll;
    private LinearLayout quickPromptsRow;
    private String apiKey;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String SYSTEM_PROMPT =
            "أنت مساعد ذكي يساعد أخصائيي العلاج الطبيعي في استخدام جهاز التحفيز الكهربائي " +
            "AST-2012A (أنماط TENS وEMS). أجب بإيجاز ووضوح وبدقة سريرية باللغة العربية، " +
            "واذكر تحذيرات السلامة المهمة عند الحاجة (مثل منظمات ضربات القلب والحمل والجروح المفتوحة). " +
            "إذا زُوّدت ببروتوكولات موثقة من قاعدة بيانات الجهاز و/أو خلفية معرفية من ويكيبيديا، " +
            "اجعلها مرجعك الأساسي، ووازن بينها وبين معرفتك العامة بوضوح (اتفاق أو اختلاف)، ونبّه لو " +
            "المصدر الخارجي عام ومش متخصص طبيًا بدقة. اقترح خطة علاج مستقرة ومتماسكة بناءً على أفضل " +
            "مصدر متاح. نظّم إجاباتك الطويلة في نقاط قصيرة وواضحة بدل الفقرات المطوّلة. لو السؤال " +
            "غامض أو ينقصه تفاصيل سريرية مهمة (موضع الألم بالضبط، شدة الأعراض، هل توجد حالة طبية " +
            "مصاحبة)، اسأل سؤالًا توضيحيًا واحدًا مختصرًا أولًا بدل تخمين إجابة كاملة قد تكون غير دقيقة.";

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
        modeHint = findViewById(R.id.mode_hint);
        quickPromptsScroll = findViewById(R.id.quick_prompts_scroll);
        quickPromptsRow = findViewById(R.id.quick_prompts_row);
        FloatingActionButton sendBtn = findViewById(R.id.btn_send);

        startDotPulse(findViewById(R.id.typing_dot_1), 0);
        startDotPulse(findViewById(R.id.typing_dot_2), 150);
        startDotPulse(findViewById(R.id.typing_dot_3), 300);

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

    /** نبضة تلاشي متكررة لنقطة واحدة من مؤشر الكتابة، بتأخير بداية مختلف
     *  لكل نقطة عشان تدي إحساس حركة متتابعة (زي مؤشرات الدردشة الحديثة). */
    private void startDotPulse(View dot, long startDelay) {
        if (dot == null) return;
        ObjectAnimator anim = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f);
        anim.setDuration(500);
        anim.setStartDelay(startDelay);
        anim.setRepeatMode(ObjectAnimator.REVERSE);
        anim.setRepeatCount(ObjectAnimator.INFINITE);
        anim.start();
    }

    /** شرائح اقتراحات سريعة تظهر فقط لما المحادثة تكون فاضية، عشان توجّه
     *  المستخدم لأنواع الأسئلة اللي المساعد الذكي يقدر يساعد فيها. */
    private void setupQuickPrompts() {
        quickPromptsRow.removeAllViews();
        for (String prompt : QUICK_PROMPTS) {
            TextView chip = new TextView(this);
            chip.setText(prompt);
            chip.setTextColor(getColor(R.color.primary_cyan));
            chip.setTextSize(12);
            chip.setBackgroundResource(R.drawable.bg_glass_chip);
            chip.setPadding(34, 22, 34, 22);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> sendQuery(prompt, true));
            quickPromptsRow.addView(chip);
        }
    }

    private void refreshQuickPromptsVisibility() {
        quickPromptsScroll.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences("settings_prefs", MODE_PRIVATE);
        apiKey = prefs.getString("ai_api_key", "");
        if (modeHint instanceof TextView) {
            TextView hint = (TextView) modeHint;
            if (apiKey != null && !apiKey.isEmpty()) {
                hint.setText("🔑 يعمل حاليًا بمفتاح OpenRouter الخاص بك، مع بحث تلقائي في ويكيبيديا وقاعدة بيانات الجهاز لكل سؤال.");
            } else {
                hint.setText("🤖 وضع مجاني بالكامل (بدون مفتاح) - يبحث تلقائيًا في ويكيبيديا وقاعدة بيانات الجهاز قبل كل رد.");
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
     * سريع، أو إعادة محاولة). يمر بمرحلتي التأريض ثم يستدعي النموذج.
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

        setTypingStage(1);

        final String finalApiKey = apiKey;
        executor.execute(() -> {
            // المرحلة الأولى: تأريض محلي - بروتوكولات موثقة من قاعدة بيانات الجهاز
            DataManager.GroundingResult grounding = DataManager.buildGroundingContext(this, text, 3);

            // المرحلة الثانية: تأريض خارجي - بحث في موسوعة ويكيبيديا (مجاني، بدون مفتاح)
            WikipediaClient.Result wiki = WikipediaClient.search(text);

            StringBuilder extraContext = new StringBuilder();
            if (grounding != null) {
                extraContext.append("بروتوكولات موثقة ذات صلة من قاعدة بيانات الجهاز:\n")
                        .append(grounding.contextText).append("\n\n");
            }
            if (wiki != null) {
                extraContext.append("خلفية معرفية عامة من ويكيبيديا (مقالة: ").append(wiki.title).append("):\n")
                        .append(wiki.extract);
            }

            final String systemPromptToUse = extraContext.length() > 0
                    ? SYSTEM_PROMPT + "\n\n" + extraContext
                    : SYSTEM_PROMPT;
            final int groundedCount = grounding != null ? grounding.caseCount : 0;

            runOnUiThread(() -> setTypingStage(2));

            AiClient.sendMessage(finalApiKey, systemPromptToUse, text, new AiClient.Callback() {
                @Override
                public void onSuccess(String reply) {
                    runOnUiThread(() -> {
                        typingIndicator.setVisibility(View.GONE);

                        String sourceLabel = null;
                        String sourceUrl = null;
                        if (groundedCount > 0 && wiki != null) {
                            sourceLabel = "قاعدة بيانات الجهاز (" + groundedCount + ") + ويكيبيديا: " + wiki.title;
                            sourceUrl = wiki.sourceUrl;
                        } else if (groundedCount > 0) {
                            sourceLabel = "قاعدة بيانات الجهاز (" + groundedCount + " بروتوكول موثّق)";
                        } else if (wiki != null) {
                            sourceLabel = "ويكيبيديا: " + wiki.title;
                            sourceUrl = wiki.sourceUrl;
                        }

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
            });
        });
    }

    private void setTypingStage(int stage) {
        typingIndicator.setVisibility(View.VISIBLE);
        typingText.setText(stage == 1 ? "🔎 يبحث في ويكيبيديا وقاعدة بيانات الجهاز..." : "🤖 يفكر في الإجابة...");
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
