package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText searchField;
    private View emptyHintContainer;
    private TextView resultNote;
    private TextView askAiFallback;
    private RecyclerView resultsList;
    private CaseAdapter adapter;
    private LayoutAnimationController listAnimation;
    private String lastQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(this::onToolbarItemClick);

        searchField = findViewById(R.id.search_field);
        emptyHintContainer = findViewById(R.id.empty_hint_container);
        resultNote = findViewById(R.id.result_note);
        askAiFallback = findViewById(R.id.btn_ask_ai_fallback);
        resultsList = findViewById(R.id.results_list);

        adapter = new CaseAdapter(this::openDetail);
        resultsList.setLayoutManager(new LinearLayoutManager(this));
        resultsList.setAdapter(adapter);
        listAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_stagger);

        TextView myCasesBtn = findViewById(R.id.btn_my_cases);
        myCasesBtn.setOnClickListener(v -> navigateTo(MyCasesActivity.class));

        TextView encyclopediaBtn = findViewById(R.id.btn_encyclopedia);
        encyclopediaBtn.setOnClickListener(v -> navigateTo(EncyclopediaActivity.class));

        TextView aiBtn = findViewById(R.id.btn_ai_assistant);
        aiBtn.setOnClickListener(v -> navigateTo(AiAssistantActivity.class));

        askAiFallback.setOnClickListener(v -> {
            Intent i = new Intent(this, AiAssistantActivity.class);
            i.putExtra("prefill_query", lastQuery);
            startActivity(i);
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> {
            Intent i = new Intent(this, AddEditCaseActivity.class);
            startActivity(i);
            overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
        });
        // دخول أنيميشن بسيط للزر العائم عند فتح الشاشة (تكبير تدريجي)
        fab.setScaleX(0f);
        fab.setScaleY(0f);
        fab.animate().scaleX(1f).scaleY(1f).setStartDelay(200).setDuration(280).start();

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { doSearch(s.toString()); }
        });

        doSearch("");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // إعادة تنفيذ البحث الحالي عند الرجوع من شاشة تعديل/إضافة/حذف، حتى تنعكس أي تغييرات فورًا
        if (searchField.getText() != null) doSearch(searchField.getText().toString());
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

    private void doSearch(String query) {
        lastQuery = query == null ? "" : query;

        if (query == null || query.trim().isEmpty()) {
            emptyHintContainer.setVisibility(View.VISIBLE);
            resultNote.setVisibility(View.GONE);
            askAiFallback.setVisibility(View.GONE);
            setResults(new ArrayList<>());
            return;
        }
        emptyHintContainer.setVisibility(View.GONE);

        List<CaseItem> allCases = DataManager.allCases(this);
        DataManager.SearchResult result = DataManager.search(query, allCases);

        if (result.items.isEmpty()) {
            resultNote.setVisibility(View.VISIBLE);
            resultNote.setText("لم يتم العثور على نتيجة مطابقة. جرّب صياغة أخرى.");
            resultNote.setTextColor(getColor(R.color.accent_red));
            askAiFallback.setVisibility(View.VISIBLE);
            setResults(new ArrayList<>());
            return;
        }

        askAiFallback.setVisibility(View.GONE);
        resultNote.setVisibility(View.VISIBLE);
        if (result.items.size() == 1) {
            resultNote.setText("✅ تم العثور على البروتوكول الصحيح المطابق لبحثك.");
        } else {
            resultNote.setText("⚠️ يوجد أكثر من بروتوكول بنفس درجة التطابق، حدد الحالة بدقة أكبر.");
        }
        resultNote.setTextColor(getColor(R.color.accent_green));
        setResults(result.items);
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
        // نمرر عنوان الحالة كمفتاح احتياطي لإيجادها لو كانت من القاعدة المدمجة (بدون id)
        i.putExtra("case_title", item.title);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }
}
