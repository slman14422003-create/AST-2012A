package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText searchField;
    private TextView emptyHint;
    private TextView resultNote;
    private RecyclerView resultsList;
    private CaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(this::onToolbarItemClick);

        searchField = findViewById(R.id.search_field);
        emptyHint = findViewById(R.id.empty_hint);
        resultNote = findViewById(R.id.result_note);
        resultsList = findViewById(R.id.results_list);

        adapter = new CaseAdapter(this::openDetail);
        resultsList.setLayoutManager(new LinearLayoutManager(this));
        resultsList.setAdapter(adapter);

        Button myCasesBtn = findViewById(R.id.btn_my_cases);
        myCasesBtn.setOnClickListener(v -> startActivity(new Intent(this, MyCasesActivity.class)));

        Button encyclopediaBtn = findViewById(R.id.btn_encyclopedia);
        encyclopediaBtn.setOnClickListener(v -> startActivity(new Intent(this, EncyclopediaActivity.class)));

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> {
            Intent i = new Intent(this, AddEditCaseActivity.class);
            startActivity(i);
        });

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

    private boolean onToolbarItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return false;
    }

    private void doSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            emptyHint.setVisibility(View.VISIBLE);
            resultNote.setVisibility(View.GONE);
            adapter.setItems(new java.util.ArrayList<>());
            return;
        }
        emptyHint.setVisibility(View.GONE);

        List<CaseItem> allCases = DataManager.allCases(this);
        DataManager.SearchResult result = DataManager.search(query, allCases);

        if (result.items.isEmpty()) {
            resultNote.setVisibility(View.VISIBLE);
            resultNote.setText("لم يتم العثور على نتيجة مطابقة. جرّب صياغة أخرى.");
            resultNote.setTextColor(getColor(R.color.accent_red));
            adapter.setItems(new java.util.ArrayList<>());
            return;
        }

        resultNote.setVisibility(View.VISIBLE);
        if (result.items.size() == 1) {
            resultNote.setText("✅ تم العثور على البروتوكول الصحيح المطابق لبحثك.");
        } else {
            resultNote.setText("⚠️ يوجد أكثر من بروتوكول بنفس درجة التطابق، حدد الحالة بدقة أكبر.");
        }
        resultNote.setTextColor(getColor(R.color.accent_green));
        adapter.setItems(result.items);
    }

    private void openDetail(CaseItem item) {
        Intent i = new Intent(this, CaseDetailActivity.class);
        i.putExtra("case_id", item.id);
        i.putExtra("is_custom", item.custom);
        // نمرر عنوان الحالة كمفتاح احتياطي لإيجادها لو كانت من القاعدة المدمجة (بدون id)
        i.putExtra("case_title", item.title);
        startActivity(i);
    }
}
