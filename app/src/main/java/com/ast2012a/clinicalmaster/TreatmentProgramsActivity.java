package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * شاشة قائمة برامج العلاج الفيزيائي: بحث سريع، الأحدث أولًا، بطاقة لكل
 * برنامج (العنوان + التشخيص + معاينة الأهداف) تفتح شاشة التفاصيل، وزر إضافة
 * مباشر داخل الشاشة.
 */
public class TreatmentProgramsActivity extends AppCompatActivity {

    private LinearLayout container;
    private View content;
    private View emptyBox;
    private View fab;
    private TextView noResults;
    private TextView headerSubtitle;
    private TextInputEditText searchField;
    private LayoutInflater inflater;

    private List<TreatmentProgram> all = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_treatment_programs);
        inflater = LayoutInflater.from(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        container = findViewById(R.id.programs_container);
        content = findViewById(R.id.programs_content);
        emptyBox = findViewById(R.id.programs_empty_container);
        fab = findViewById(R.id.fab_add_program);
        noResults = findViewById(R.id.programs_no_results);
        headerSubtitle = findViewById(R.id.header_subtitle);
        searchField = findViewById(R.id.search_field);

        ((TextInputLayout) findViewById(R.id.search_layout)).setHint("ابحث في برامج العلاج");

        ((TextView) emptyBox.findViewById(R.id.empty_icon)).setText("📋");
        ((TextView) emptyBox.findViewById(R.id.empty_title)).setText("لا توجد برامج علاج بعد");
        ((TextView) emptyBox.findViewById(R.id.empty_body)).setText(
                "اكتب خطة علاج متكاملة: التشخيص والأهداف والمراحل والتمارين، ثم أسندها لأي مريض.");
        TextView emptyAction = emptyBox.findViewById(R.id.empty_action);
        emptyAction.setText("برنامج جديد");
        emptyAction.setOnClickListener(v -> openAddProgram());
        fab.setOnClickListener(v -> openAddProgram());

        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderList();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void openAddProgram() {
        startActivity(new Intent(this, AddEditTreatmentProgramActivity.class));
    }

    private void reload() {
        List<TreatmentProgram> loaded = new ArrayList<>(TreatmentProgramManager.loadPrograms(this));
        Collections.reverse(loaded); // الأحدث أولًا
        all = loaded;

        boolean empty = all.isEmpty();
        content.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        fab.setVisibility(empty ? View.GONE : View.VISIBLE);

        if (empty) {
            headerSubtitle.setVisibility(View.GONE);
        } else {
            headerSubtitle.setText(all.size() == 1 ? "برنامج واحد" : all.size() + " برامج");
            headerSubtitle.setVisibility(View.VISIBLE);
        }
        renderList();
    }

    private void renderList() {
        String q = DataManager.normalize(searchField.getText() == null ? "" : searchField.getText().toString());
        List<TreatmentProgram> shown = new ArrayList<>();
        for (TreatmentProgram t : all) {
            if (q.isEmpty() || matches(t, q)) shown.add(t);
        }

        container.removeAllViews();
        for (TreatmentProgram t : shown) container.addView(buildRow(t));
        noResults.setVisibility(!all.isEmpty() && shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean matches(TreatmentProgram t, String q) {
        return DataManager.normalize(t.title).contains(q)
                || DataManager.normalize(t.diagnosis).contains(q)
                || DataManager.normalize(t.goals).contains(q);
    }

    private View buildRow(final TreatmentProgram t) {
        View row = inflater.inflate(R.layout.item_treatment_program_row, container, false);

        ((TextView) row.findViewById(R.id.program_title)).setText(
                BidiText.fix(t.title.trim().isEmpty() ? "(بدون عنوان)" : t.title.trim()));

        TextView diagnosis = row.findViewById(R.id.program_diagnosis);
        if (t.diagnosis.trim().isEmpty()) {
            diagnosis.setVisibility(View.GONE);
        } else {
            diagnosis.setText(BidiText.fix("🩺 " + t.diagnosis.trim().replace('\n', ' ')));
            diagnosis.setVisibility(View.VISIBLE);
        }

        TextView goals = row.findViewById(R.id.program_goals);
        if (t.goals.trim().isEmpty()) {
            goals.setVisibility(View.GONE);
        } else {
            goals.setText(BidiText.fix(t.goals.trim().replace('\n', ' ')));
            goals.setVisibility(View.VISIBLE);
        }

        ((TextView) row.findViewById(R.id.program_date)).setText(BidiText.fix(Fmt.date(t.createdAt)));

        row.setOnClickListener(v -> {
            Intent i = new Intent(this, TreatmentProgramDetailActivity.class);
            i.putExtra("program_id", t.id);
            startActivity(i);
        });
        row.findViewById(R.id.program_btn_edit).setOnClickListener(v -> {
            Intent i = new Intent(this, AddEditTreatmentProgramActivity.class);
            i.putExtra("edit_program_id", t.id);
            startActivity(i);
        });
        row.findViewById(R.id.program_btn_delete).setOnClickListener(v -> confirmDelete(t));
        return row;
    }

    private void confirmDelete(final TreatmentProgram t) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("حذف برنامج العلاج")
                .setMessage("هل تريد حذف \"" + t.title + "\" نهائيًا؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    TreatmentProgramManager.deleteProgram(this, t.id);
                    Toast.makeText(this, "تم الحذف.", Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }
}
