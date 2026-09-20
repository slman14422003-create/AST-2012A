package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * شاشة "حالاتي": الحالات التي أضافها المستخدم. الأحدث أولًا، مع بحث سريع،
 * وفتح التفاصيل بالضغط على البطاقة، وحالة فارغة فيها زر إضافة مباشر.
 *
 * ملحوظة: العنصر @id/empty_hint_container حاوية (LinearLayout) وليس TextView،
 * لذلك نتعامل معه كـ View فقط (سبب كراش سابق عند التصريح عنه كـ TextView).
 */
public class MyCasesActivity extends AppCompatActivity implements CaseRowAdapter.Callback {

    private RecyclerView list;
    private View content;
    private View emptyBox;
    private View fab;
    private TextView noResults;
    private TextView headerSubtitle;
    private TextInputEditText searchField;
    private CaseRowAdapter adapter;

    private List<CaseItem> all = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_cases);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        list = findViewById(R.id.cases_list);
        content = findViewById(R.id.cases_content);
        emptyBox = findViewById(R.id.empty_hint_container);
        fab = findViewById(R.id.fab_add_case);
        noResults = findViewById(R.id.cases_no_results);
        headerSubtitle = findViewById(R.id.header_subtitle);
        searchField = findViewById(R.id.search_field);

        searchField.setHint("ابحث في حالاتك");

        ((android.widget.ImageView) emptyBox.findViewById(R.id.empty_icon)).setImageResource(R.drawable.ic_folder);
        ((TextView) emptyBox.findViewById(R.id.empty_title)).setText("لا توجد حالات مخصصة بعد");
        ((TextView) emptyBox.findViewById(R.id.empty_body)).setText(
                "أضف بروتوكولك الخاص لأي حالة، وسيظهر هنا وفي نتائج البحث الرئيسية.");
        TextView emptyAction = emptyBox.findViewById(R.id.empty_action);
        emptyAction.setText("إضافة حالة");
        emptyAction.setOnClickListener(v -> openAddCase());
        fab.setOnClickListener(v -> openAddCase());

        adapter = new CaseRowAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setLayoutAnimation(
                android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_stagger));

        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter(false);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<CaseItem> loaded = new ArrayList<>(DataManager.loadCustomCases(this));
        Collections.reverse(loaded); // الأحدث أولًا
        all = loaded;

        boolean empty = all.isEmpty();
        content.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        fab.setVisibility(empty ? View.GONE : View.VISIBLE);

        if (empty) {
            headerSubtitle.setVisibility(View.GONE);
        } else {
            headerSubtitle.setText(all.size() == 1 ? "حالة واحدة" : all.size() + " حالة");
            headerSubtitle.setVisibility(View.VISIBLE);
        }
        applyFilter(true);
    }

    private void applyFilter(boolean animate) {
        String q = DataManager.normalize(searchField.getText() == null ? "" : searchField.getText().toString());
        List<CaseItem> shown = new ArrayList<>();
        for (CaseItem c : all) {
            if (q.isEmpty() || matches(c, q)) shown.add(c);
        }
        adapter.setItems(shown);
        noResults.setVisibility(!all.isEmpty() && shown.isEmpty() ? View.VISIBLE : View.GONE);
        if (animate) list.scheduleLayoutAnimation();
    }

    private boolean matches(CaseItem c, String q) {
        if (DataManager.normalize(c.title).contains(q)) return true;
        if (DataManager.normalize(c.mode).contains(q)) return true;
        for (String k : c.keywords) {
            if (DataManager.normalize(k).contains(q)) return true;
        }
        return false;
    }

    private void openAddCase() {
        startActivity(new Intent(this, AddEditCaseActivity.class));
        overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
    }

    @Override
    public void onOpen(CaseItem item) {
        Intent i = new Intent(this, CaseDetailActivity.class);
        i.putExtra("case_id", item.id);
        i.putExtra("case_title", item.title);
        i.putExtra("is_custom", true);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    @Override
    public void onEdit(CaseItem item) {
        Intent i = new Intent(this, AddEditCaseActivity.class);
        i.putExtra("edit_case_id", item.id);
        startActivity(i);
        overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
    }

    @Override
    public void onDelete(final CaseItem item) {
        new ClaudeDialog(this)
                .setTitle("تأكيد")
                .setMessage("هل تريد حذف \"" + item.title + "\" نهائيًا؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    DataManager.deleteCustomCase(this, item.id);
                    reload();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    @Override
    public void onToggleFavorite(CaseItem item) {
        FavoritesManager.toggleFavorite(this, item.title);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
