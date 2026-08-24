package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText searchField;
    private View emptyHintContainer;
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

    private static final String SYSTEM_PROMPT =
            "أنت مساعد ذكي يساعد أخصائيي العلاج الطبيعي في استخدام جهاز التحفيز الكهربائي " +
            "AST-2012A (أنماط TENS وEMS). أجب بإيجاز ووضوح وبدقة سريرية باللغة العربية، " +
            "واذكر تحذيرات السلامة المهمة عند الحاجة. إذا زُوّدت ببروتوكولات موثقة من قاعدة " +
            "بيانات الجهاز، اجعلها مرجعك الأساسي واقترح خطة علاج مستقرة بناءً عليها. لو السؤال " +
            "غامض أو ينقصه تفاصيل مهمة (مكان الألم، حدة الأعراض، مدة المشكلة)، اسأل سؤال " +
            "توضيحي واحد ومختصر قبل اقتراح بروتوكول كامل.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(this::onToolbarItemClick);

        searchField = findViewById(R.id.search_field);
        emptyHintContainer = findViewById(R.id.empty_hint_container);
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

        TextView aiBtn = findViewById(R.id.btn_ai_assistant);
        aiBtn.setOnClickListener(v -> navigateTo(AiAssistantActivity.class));

        askAiFallback.setOnClickListener(v -> askAiInline(lastQuery));

        suggestionNote.setOnClickListener(v -> {
            String suggestion = suggestionNote.getTag() != null ? suggestionNote.getTag().toString() : null;
            if (suggestion != null) {
                searchField.setText(suggestion);
                searchField.setSelection(suggestion.length());
            }
        });

        Button saveCaseBtn = findViewById(R.id.ai_inline_save_case);
        saveCaseBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, AddEditCaseActivity.class);
            i.putExtra("prefill_explanation", lastAiAnswer);
            startActivity(i);
            overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
        });

        Button openChatBtn = findViewById(R.id.ai_inline_open_chat);
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

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> {
            Intent i = new Intent(this, AddEditCaseActivity.class);
            startActivity(i);
            overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
        });
        fab.setScaleX(0f);
        fab.setScaleY(0f);
        fab.animate().scaleX(1f).scaleY(1f).setStartDelay(200).setDuration(280).start();

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                showingFavorites = false;
                doSearch(s.toString());
            }
        });

        doSearch("");
    }

    private void setupSuggestionChip(int viewId) {
        TextView chip = findViewById(viewId);
        chip.setOnClickListener(v -> searchField.setText(chip.getText()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (showingFavorites) {
            refreshFavoritesView();
        } else if (searchField.getText() != null) {
            doSearch(searchField.getText().toString());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void navigateTo(Class<?> activityClass) {
        startActivity(new Intent(this, activityClass));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private boolean onToolbarItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            navigateTo(SettingsActivity.class);
            return true;
        }
        return false;
    }

    /** يعرض قائمة الحالات المفضّلة فقط، متجاوزًا محرك البحث. */
    private void refreshFavoritesView() {
        showingFavorites = true;
        searchField.setText("");
        resetAiInlineState();
        emptyHintContainer.setVisibility(View.GONE);
        askAiFallback.setVisibility(View.GONE);
        suggestionNote.setVisibility(View.GONE);
        resultsList.setVisibility(View.VISIBLE);

        List<CaseItem> favorites = FavoritesManager.getFavoriteCases(this);
        if (favorites.isEmpty()) {
            resultNote.setVisibility(View.VISIBLE);
            resultNote.setText("⭐ لا توجد حالات مفضّلة بعد. اضغط على النجمة بجانب أي حالة لإضافتها هنا.");
            resultNote.setTextColor(getColor(R.color.accent_gold));
        } else {
            resultNote.setVisibility(View.VISIBLE);
            resultNote.setText("⭐ حالاتك المفضّلة (" + favorites.size() + ")");
            resultNote.setTextColor(getColor(R.color.accent_gold));
        }
        setResults(favorites);
    }

    private void doSearch(String query) {
        lastQuery = query == null ? "" : query;
        resetAiInlineState();
        suggestionNote.setVisibility(View.GONE);

        if (query == null || query.trim().isEmpty()) {
            emptyHintContainer.setVisibility(View.VISIBLE);
            resultNote.setVisibility(View.GONE);
            askAiFallback.setVisibility(View.GONE);
            resultsList.setVisibility(View.VISIBLE);
            populateRecentSearches();
            setResults(new ArrayList<>());
            return;
        }
        emptyHintContainer.setVisibility(View.GONE);

        List<CaseItem> allCases = DataManager.allCases(this);
        DataManager.SearchResult result = DataManager.search(query, allCases, FavoritesManager.getFavoriteTitles(this));

        if (result.items.isEmpty()) {
            resultNote.setVisibility(View.VISIBLE);
            resultNote.setText("لم يتم العثور على نتيجة مطابقة. جرّب صياغة أخرى.");
            resultNote.setTextColor(getColor(R.color.accent_red));
            askAiFallback.setVisibility(View.VISIBLE);
            resultsList.setVisibility(View.VISIBLE);
            if (result.closestTitleSuggestion != null) {
                suggestionNote.setVisibility(View.VISIBLE);
                suggestionNote.setText("💡 هل تقصد: " + result.closestTitleSuggestion + "؟");
                suggestionNote.setTag(result.closestTitleSuggestion);
            }
            setResults(new ArrayList<>());
            return;
        }

        RecentSearchManager.addQuery(this, query);
        askAiFallback.setVisibility(View.GONE);
        resultNote.setVisibility(View.VISIBLE);
        resultsList.setVisibility(View.VISIBLE);
        if (result.items.size() == 1) {
            resultNote.setText("✅ تم العثور على البروتوكول الصحيح المطابق لبحثك.");
        } else {
            resultNote.setText("⚠️ يوجد أكثر من بروتوكول بنفس درجة التطابق، حدد الحالة بدقة أكبر.");
        }
        resultNote.setTextColor(getColor(R.color.accent_green));
        setResults(result.items);
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
            chip.setTextSize(12);
            chip.setBackgroundResource(R.drawable.bg_glass_chip);
            chip.setPadding(36, 22, 36, 22);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(10);
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

    /** يسأل المساعد الذكي مباشرة من نفس شاشة البحث بدون الحاجة للانتقال لشاشة منفصلة. */
    private void askAiInline(String query) {
        if (query == null || query.trim().isEmpty()) return;

        askAiFallback.setVisibility(View.GONE);
        suggestionNote.setVisibility(View.GONE);
        resultsList.setVisibility(View.GONE);
        aiInlineAnswerScroll.setVisibility(View.GONE);
        aiInlineLoading.setVisibility(View.VISIBLE);
        setAiInlineLoadingText("🔎 يبحث في ويكيبيديا وقاعدة بيانات الجهاز...");

        SharedPreferences prefs = getSharedPreferences("settings_prefs", MODE_PRIVATE);
        String apiKey = prefs.getString("ai_api_key", "");

        executor.execute(() -> {
            // المرحلة الأولى: تأريض محلي - بروتوكولات موثقة من قاعدة بيانات الجهاز
            DataManager.GroundingResult grounding = DataManager.buildGroundingContext(this, query, 3);

            // المرحلة الثانية: تأريض خارجي - بحث في موسوعة ويكيبيديا (مجاني، بدون مفتاح)
            WikipediaClient.Result wiki = WikipediaClient.search(query);

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
            final String sourceNote = buildSourceNote(grounding, wiki);

            runOnUiThread(() -> setAiInlineLoadingText("🤖 يفكر في الإجابة..."));

            AiClient.sendMessage(apiKey, systemPromptToUse, query, new AiClient.Callback() {
                @Override
                public void onSuccess(String reply) {
                    runOnUiThread(() -> {
                        aiInlineLoading.setVisibility(View.GONE);
                        lastAiAnswer = reply;
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
            });
        });
    }

    private void setAiInlineLoadingText(String text) {
        TextView label = aiInlineLoading.findViewById(R.id.ai_inline_loading_text);
        if (label != null) label.setText(text);
    }

    /** جملة شفافة قصيرة توضح مصدر المعلومة المستخدَمة في الرد (قاعدة بيانات
     *  الجهاز و/أو ويكيبيديا) - نفس فكرة شارة المصدر في شاشة المحادثة. */
    private String buildSourceNote(DataManager.GroundingResult grounding, WikipediaClient.Result wiki) {
        int groundedCount = grounding != null ? grounding.caseCount : 0;
        if (groundedCount > 0 && wiki != null) {
            return "📖 المصدر: قاعدة بيانات الجهاز (" + groundedCount + ") + ويكيبيديا: " + wiki.title;
        } else if (groundedCount > 0) {
            return "📖 المصدر: قاعدة بيانات الجهاز (" + groundedCount + " بروتوكول موثّق)";
        } else if (wiki != null) {
            return "📖 المصدر: ويكيبيديا - " + wiki.title;
        }
        return null;
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
