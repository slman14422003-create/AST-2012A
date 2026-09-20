package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText searchField;
    private View emptyHintContainer;
    private View resultsContainer;
    private LinearLayout recentSearchesContainer;
    private LinearLayout recentSearchesRow;
    private TextView resultNote;
    private TextView suggestionNote;
    private TextView askAiFallback;
    private View aiInlineLoading;
    private View aiInlineAnswerScroll;
    private TextView aiInlineAnswerText;
    private RecyclerView resultsList;
    private CaseAdapter adapter;
    private LayoutAnimationController listAnimation;
    private String lastQuery = "";
    private String lastAiAnswer = "";
    private boolean showingFavorites = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ملحوظة إصلاح "لاج" مربع البحث الرئيسي: doSearch() كانت تُستدعى مع كل
    // حرف يكتبه المستخدم، وهي تعمل على UI thread (تحميل الحالات + محرك
    // بحث فيه Levenshtein). مع الكتابة السريعة كانت تتراكم وتتزاحم مع رسم
    // لوحة المفاتيح فيظهر تقطّع واضح. الحل: تأخير بسيط (بنفس أسلوب شريط
    // بحث Claude) بحيث لا يُنفَّذ البحث الفعلي إلا بعد توقف الكتابة، مع
    // تنفيذ البحث نفسه على خيط خلفية بدل UI thread.
    private static final long SEARCH_DEBOUNCE_MS = 200;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    // ملحوظة إصلاح خطأ "زر المفضلة يرجع للشاشة الرئيسية": refreshFavoritesView()
    // كانت بتنادي searchField.setText("") عشان تفضي مربع البحث، وده بيشغّل
    // TextWatcher.afterTextChanged تلقائيًا واللي بيحط showingFavorites = false
    // ويجدول doSearch("") بعد 200ms (SEARCH_DEBOUNCE_MS) - فبعد ربع ثانية
    // بالظبط الشاشة كانت "ترجع" لواجهة الترحيب الفارغة فوق نتيجة المفضلة
    // اللي ظهرت لحظة واحدة قبلها، وكأن الزر مش شغال. الحل: هذا العلم يخلي
    // afterTextChanged يتجاهل مرة واحدة بس أي تغيير برمجي (مش من كتابة
    // المستخدم الفعلية) في النص، فمايشغلش بحث جديد ولا يلغي showingFavorites.
    private boolean suppressNextTextChange = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ملحوظة: زر تبديل الوضع الليلي/النهاري اتشال من هنا لأن نفس التحكم
        // موجود فعلًا داخل شاشة الإعدادات ("المظهر") - مفيش داعي لتكراره في
        // الشريط العلوي (نفس أسلوب تطبيقات زي Claude اللي بتسيب هذا التحكم
        // داخل الإعدادات فقط بدل ما يكون زر منفصل طايف في كل شاشة).
        ImageButton settingsBtn = findViewById(R.id.btn_settings);
        settingsBtn.setOnClickListener(v -> navigateTo(SettingsActivity.class));
        Ui.applyPressFeedback(settingsBtn);

        // عبارة ترحيب متغيّرة (صباح الخير/مساء الخير...) تظهر كعنوان الشاشة
        // الترحيبية (بأسلوب Claude) بدل النص الثابت القديم.
        TextView heroTitle = findViewById(R.id.empty_hint_title);
        if (heroTitle != null) heroTitle.setText(GreetingProvider.randomGreeting());

        searchField = findViewById(R.id.search_field);
        emptyHintContainer = findViewById(R.id.empty_hint_container);
        // ملحوظة إصلاح كراش/عطل تفاعلي مهم: هذا الـ LinearLayout (يحتوي
        // نتائج البحث ورد المساعد) كان بلا id وبدون visibility مبدئي، فكان
        // دايمًا VISIBLE ويملأ كل الشاشة فوق شاشة الترحيب الفارغة (لأنه
        // معلن بعدها في FrameLayout فيرسم فوقها) - فيعترض كل لمسة حتى لو
        // مافيهوش نتائج ظاهرة، وهو السبب الحقيقي وراء عدم عمل شرائح
        // الاقتراحات ("بماذا تفكر؟") عند الضغط عليها. الحل: نعطيه id ونتحكم
        // في ظهوره صراحة في doSearch() بدل ما يفضل ظاهر افتراضيًا دايمًا.
        resultsContainer = findViewById(R.id.results_container);
        recentSearchesContainer = findViewById(R.id.recent_searches_container);
        recentSearchesRow = findViewById(R.id.recent_searches_row);
        resultNote = findViewById(R.id.result_note);
        suggestionNote = findViewById(R.id.suggestion_note);
        askAiFallback = findViewById(R.id.btn_ask_ai_fallback);
        aiInlineLoading = findViewById(R.id.ai_inline_loading);
        aiInlineAnswerScroll = findViewById(R.id.ai_inline_answer_scroll);
        aiInlineAnswerText = findViewById(R.id.ai_inline_answer_text);
        resultsList = findViewById(R.id.results_list);

        adapter = new CaseAdapter(this::openDetail);
        adapter.setOnFavoriteToggleListener((item, nowFavorite) -> {
            if (showingFavorites && !nowFavorite) refreshFavoritesView();
        });
        resultsList.setLayoutManager(new LinearLayoutManager(this));
        resultsList.setAdapter(adapter);
        listAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_stagger);

        TextView favoritesBtn = findViewById(R.id.btn_favorites);
        favoritesBtn.setOnClickListener(v -> refreshFavoritesView());

        TextView myCasesBtn = findViewById(R.id.btn_my_cases);
        myCasesBtn.setOnClickListener(v -> navigateTo(MyCasesActivity.class));

        TextView encyclopediaBtn = findViewById(R.id.btn_encyclopedia);
        encyclopediaBtn.setOnClickListener(v -> navigateTo(EncyclopediaActivity.class));

        TextView anatomyBtn = findViewById(R.id.btn_anatomy);
        anatomyBtn.setOnClickListener(v -> navigateTo(AnatomyActivity.class));

        TextView patientsBtn = findViewById(R.id.btn_patients);
        patientsBtn.setOnClickListener(v -> navigateTo(PatientsActivity.class));

        TextView treatmentProgramsBtn = findViewById(R.id.btn_treatment_programs);
        treatmentProgramsBtn.setOnClickListener(v -> navigateTo(TreatmentProgramsActivity.class));

        TextView aiBtn = findViewById(R.id.btn_ai_assistant);
        aiBtn.setOnClickListener(v -> navigateTo(AiAssistantActivity.class));

        // تحسين انميشن: كبسولات التنقل السفلية بقت تدي إحساس ضغط فعلي
        // (تصغير خفيف + نبضة رجوع) بدل ما تعتمد بس على تغيير لون الخلفية.
        for (View chip : new View[]{favoritesBtn, myCasesBtn, encyclopediaBtn, anatomyBtn,
                patientsBtn, treatmentProgramsBtn, aiBtn}) {
            Ui.applyPressFeedback(chip);
        }

        askAiFallback.setOnClickListener(v -> askAiInline(lastQuery));

        suggestionNote.setOnClickListener(v -> {
            String suggestion = suggestionNote.getTag() != null ? suggestionNote.getTag().toString() : null;
            if (suggestion != null) {
                searchField.setText(suggestion);
                searchField.setSelection(suggestion.length());
            }
        });

        View saveCaseBtn = findViewById(R.id.ai_inline_save_case);
        saveCaseBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, AddEditCaseActivity.class);
            i.putExtra("prefill_explanation", lastAiAnswer);
            startActivity(i);
            overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
        });

        View openChatBtn = findViewById(R.id.ai_inline_open_chat);
        openChatBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, AiAssistantActivity.class);
            i.putExtra("prefill_query", lastQuery);
            startActivity(i);
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });

        setupSuggestionChip(R.id.chip_suggestion_1);
        setupSuggestionChip(R.id.chip_suggestion_2);
        setupSuggestionChip(R.id.chip_suggestion_3);
        setupSuggestionChip(R.id.chip_suggestion_4);

        View fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> showAddChooser());
        fab.setScaleX(0f);
        fab.setScaleY(0f);
        fab.animate().scaleX(1f).scaleY(1f).setStartDelay(200).setDuration(320)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (suppressNextTextChange) {
                    suppressNextTextChange = false;
                    return;
                }
                showingFavorites = false;
                final String query = s.toString();
                if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
                pendingSearch = () -> doSearch(query);
                searchHandler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
            }
        });

        doSearch("");
    }

    private void setupSuggestionChip(int viewId) {
        TextView chip = findViewById(viewId);
        chip.setOnClickListener(v -> searchField.setText(chip.getText()));
        Ui.applyPressFeedback(chip);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (showingFavorites) {
            refreshFavoritesView();
        } else if (searchField.getText() != null) {
            doSearch(searchField.getText().toString());
        }
        // فحص صامت للتحديثات (كل 6 ساعات) وعرض نافذة التحديث لو وُجد إصدار أحدث
        UpdateManager.autoCheck(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
    }

    private void navigateTo(Class<?> activityClass) {
        startActivity(new Intent(this, activityClass));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    /**
     * زر "+" بقى يفتح خيارين بدل ما يروح مباشرة لإضافة حالة جهاز فقط:
     * 1) حالة جديدة (بروتوكول مرتبط بجهاز AST-2012A - زي قبل).
     * 2) برنامج علاج فيزيائي كامل (خطة علاج طبيعي عامة، مش مرتبطة بجهاز
     *    معيّن - تشخيص، أهداف، مراحل، تمارين، احتياطات).
     */
    private void showAddChooser() {
        String[] options = {"حالة جديدة (بروتوكول جهاز)", "برنامج علاج فيزيائي كامل"};
        new ClaudeDialog(this)
                .setTitle("ماذا تريد أن تضيف؟")
                .setItemIcons(R.drawable.ic_folder, R.drawable.ic_clipboard)
                .setItems(options, (dialog, which) -> {
                    Intent i = which == 0
                            ? new Intent(this, AddEditCaseActivity.class)
                            : new Intent(this, AddEditTreatmentProgramActivity.class);
                    startActivity(i);
                    overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
                })
                .show();
    }

    /** يعرض قائمة الحالات المفضّلة فقط، متجاوزًا محرك البحث. */
    private void refreshFavoritesView() {
        showingFavorites = true;
        // نلغي أي بحث مؤجَّل (debounced) كان في انتظاره قبل ما نمسح الحقل،
        // وإلا كان ممكن يتنفذ بعد 200ms ويكتب فوق نتيجة المفضلة اللي هنعرضها.
        if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
        suppressNextTextChange = true;
        searchField.setText("");
        resetAiInlineState();
        emptyHintContainer.setVisibility(View.GONE);
        resultsContainer.setVisibility(View.VISIBLE);
        askAiFallback.setVisibility(View.GONE);
        suggestionNote.setVisibility(View.GONE);
        resultsList.setVisibility(View.VISIBLE);

        List<CaseItem> favorites = FavoritesManager.getFavoriteCases(this);
        if (favorites.isEmpty()) {
            showNote(R.drawable.ic_star_stroke, R.color.accent_gold,
                    "لا توجد حالات مفضّلة بعد. اضغط على النجمة بجانب أي حالة لإضافتها هنا.");
        } else {
            showNote(R.drawable.ic_star_stroke, R.color.accent_gold,
                    "حالاتك المفضّلة (" + favorites.size() + ")");
        }
        setResults(favorites);
    }

    private void doSearch(String query) {
        lastQuery = query == null ? "" : query;
        resetAiInlineState();
        suggestionNote.setVisibility(View.GONE);

        if (query == null || query.trim().isEmpty()) {
            emptyHintContainer.setVisibility(View.VISIBLE);
            resultsContainer.setVisibility(View.GONE);
            resultNote.setVisibility(View.GONE);
            askAiFallback.setVisibility(View.GONE);
            resultsList.setVisibility(View.VISIBLE);
            populateRecentSearches();
            setResults(new ArrayList<>());
            return;
        }
        emptyHintContainer.setVisibility(View.GONE);
        resultsContainer.setVisibility(View.VISIBLE);

        // البحث نفسه (تحميل الحالات + محرك البحث بما فيه مطابقة Levenshtein
        // للاقتراحات) ينتقل لخيط خلفية بدل UI thread، حتى لا يتجمّد الرسم
        // أثناء الكتابة السريعة. النتيجة تُطبَّق على الواجهة بعدها فقط.
        executor.execute(() -> {
            List<CaseItem> allCases = DataManager.allCases(this);
            DataManager.SearchResult result = DataManager.search(query, allCases, FavoritesManager.getFavoriteTitles(this));
            runOnUiThread(() -> {
                if (!query.equals(lastQuery)) return; // كتب المستخدم شيئًا آخر أثناء البحث
                applySearchResult(query, result);
            });
        });
    }

    private void applySearchResult(String query, DataManager.SearchResult result) {
        if (result.items.isEmpty()) {
            showNote(R.drawable.ic_alert, R.color.accent_red,
                    "لم يتم العثور على نتيجة مطابقة. جرّب صياغة أخرى.");
            askAiFallback.setVisibility(View.VISIBLE);
            resultsList.setVisibility(View.VISIBLE);
            if (result.closestTitleSuggestion != null) {
                suggestionNote.setVisibility(View.VISIBLE);
                suggestionNote.setText("هل تقصد: " + result.closestTitleSuggestion + "؟");
                suggestionNote.setTag(result.closestTitleSuggestion);
            }
            setResults(new ArrayList<>());
            return;
        }

        RecentSearchManager.addQuery(this, query);
        askAiFallback.setVisibility(View.GONE);
        resultsList.setVisibility(View.VISIBLE);
        if (result.items.size() == 1) {
            showNote(R.drawable.ic_check, R.color.accent_green,
                    "تم العثور على البروتوكول الصحيح المطابق لبحثك.");
        } else {
            showNote(R.drawable.ic_alert, R.color.accent_green,
                    "يوجد أكثر من بروتوكول بنفس درجة التطابق، حدد الحالة بدقة أكبر.");
        }
        setResults(result.items);
    }

    /** سطر ملاحظة صغير أعلى النتائج: أيقونة + نص بلون واحد (بدل الإيموجي القديمة). */
    private void showNote(int iconRes, int colorRes, String text) {
        int color = getColor(colorRes);
        resultNote.setVisibility(View.VISIBLE);
        resultNote.setText(text);
        resultNote.setTextColor(color);
        resultNote.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        resultNote.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
    }

    private void populateRecentSearches() {
        List<String> recent = RecentSearchManager.getRecent(this);
        recentSearchesRow.removeAllViews();
        if (recent.isEmpty()) {
            recentSearchesContainer.setVisibility(View.GONE);
            return;
        }
        recentSearchesContainer.setVisibility(View.VISIBLE);
        for (String q : recent) {
            TextView chip = new TextView(this);
            chip.setText(q);
            chip.setTextColor(getColor(R.color.text_secondary));
            chip.setTextSize(13.5f);
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            chip.setBackgroundResource(R.drawable.bg_glass_chip);
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_history, 0, 0, 0);
            chip.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text_tertiary)));
            chip.setCompoundDrawablePadding(Ui.dp(this, 8));
            chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                searchField.setText(q);
                searchField.setSelection(q.length());
            });
            recentSearchesRow.addView(chip);
        }
    }

    private void resetAiInlineState() {
        aiInlineLoading.setVisibility(View.GONE);
        aiInlineAnswerScroll.setVisibility(View.GONE);
    }

    /** يسأل المساعد الذكي مباشرة من نفس شاشة البحث بدون الحاجة للانتقال
     *  لشاشة منفصلة. بيستخدم نفس مسار القرار الموحّد (AiOrchestrator):
     *  النموذج نفسه يقرر هل محتاج يبحث في قاعدة الجهاز/Physiopedia/ويكيبيديا
     *  ولا يرد مباشرة، بدل ما يشغّل البحث الثلاثي إجباريًا مع كل استعلام. */
    private void askAiInline(String query) {
        if (query == null || query.trim().isEmpty()) return;

        askAiFallback.setVisibility(View.GONE);
        suggestionNote.setVisibility(View.GONE);
        resultsList.setVisibility(View.GONE);
        aiInlineAnswerScroll.setVisibility(View.GONE);
        aiInlineLoading.setVisibility(View.VISIBLE);
        setAiInlineLoadingText("بيفهم قصدك...");

        executor.execute(() -> AiOrchestrator.answer(this, query,
                new AiOrchestrator.StageListener() {
                    @Override public void onClassifying() { runOnUiThread(() -> setAiInlineLoadingText("بيفهم قصدك...")); }
                    @Override public void onSearching() { runOnUiThread(() -> setAiInlineLoadingText("يبحث في Physiopedia وقاعدة بيانات الجهاز...")); }
                    @Override public void onThinking() { runOnUiThread(() -> setAiInlineLoadingText("يفكر في الإجابة...")); }
                },
                new AiOrchestrator.ResultCallback() {
                    @Override
                    public void onChatReply(String reply) {
                        runOnUiThread(() -> {
                            aiInlineLoading.setVisibility(View.GONE);
                            lastAiAnswer = reply;
                            aiInlineAnswerText.setText(reply);
                            aiInlineAnswerScroll.setVisibility(View.VISIBLE);
                        });
                    }

                    @Override
                    public void onGroundedReply(String reply, String sourceLabel, String sourceUrl) {
                        runOnUiThread(() -> {
                            aiInlineLoading.setVisibility(View.GONE);
                            lastAiAnswer = reply;
                            String sourceNote = sourceLabel != null ? "المصدر: " + sourceLabel : null;
                            aiInlineAnswerText.setText(sourceNote != null ? reply + "\n\n" + sourceNote : reply);
                            aiInlineAnswerScroll.setVisibility(View.VISIBLE);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            aiInlineLoading.setVisibility(View.GONE);
                            askAiFallback.setVisibility(View.VISIBLE);
                            resultsList.setVisibility(View.VISIBLE);
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        });
                    }
                }));
    }

    private void setAiInlineLoadingText(String text) {
        TextView label = aiInlineLoading.findViewById(R.id.ai_inline_loading_text);
        if (label != null) label.setText(text);
    }

    private void setResults(List<CaseItem> items) {
        adapter.setItems(items);
        resultsList.setLayoutAnimation(listAnimation);
        resultsList.scheduleLayoutAnimation();
    }

    private void openDetail(CaseItem item) {
        Intent i = new Intent(this, CaseDetailActivity.class);
        i.putExtra("case_id", item.id);
        i.putExtra("is_custom", item.custom);
        i.putExtra("case_title", item.title);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }
}
