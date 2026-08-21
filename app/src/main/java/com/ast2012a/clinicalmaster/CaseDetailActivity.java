package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

public class CaseDetailActivity extends AppCompatActivity {

    private CaseItem currentCase;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        container = findViewById(R.id.detail_container);

        loadCaseFromIntent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // إعادة التحميل عند الرجوع من شاشة التعديل حتى تنعكس التغييرات فورًا
        loadCaseFromIntent();
    }

    private void loadCaseFromIntent() {
        String caseId = getIntent().getStringExtra("case_id");
        boolean isCustom = getIntent().getBooleanExtra("is_custom", false);
        String caseTitle = getIntent().getStringExtra("case_title");

        CaseItem found = null;
        if (isCustom && caseId != null) {
            for (CaseItem c : DataManager.loadCustomCases(this)) {
                if (caseId.equals(c.id)) { found = c; break; }
            }
        } else {
            for (CaseItem c : DataManager.loadBuiltinDatabase(this)) {
                if (c.title.equals(caseTitle)) { found = c; break; }
            }
        }

        if (found == null) {
            finish();
            return;
        }
        currentCase = found;
        render();
    }

    private void render() {
        container.removeAllViews();

        TextView titleView = new TextView(this);
        titleView.setText(currentCase.title);
        titleView.setTextColor(getColor(R.color.white));
        titleView.setTextSize(19);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setGravity(android.view.Gravity.END);
        titleView.setTextDirection(View.TEXT_DIRECTION_RTL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = 24;
        container.addView(titleView, tp);

        addField("النمط", currentCase.mode);
        addField("التردد", currentCase.freq);
        addField("القناة", currentCase.channel);
        addField("المدة", currentCase.duration);

        if (!currentCase.poles.isEmpty()) {
            addField("الأقطاب", String.join("\n", currentCase.poles));
        }
        addField("الشرح الإكلينيكي", currentCase.explanation);
        if (currentCase.symptoms != null && !currentCase.symptoms.isEmpty()) {
            addField("الأعراض", currentCase.symptoms);
        }
        if (currentCase.sessionsPlan != null && !currentCase.sessionsPlan.isEmpty()) {
            addField("خطة الجلسات", currentCase.sessionsPlan);
        }
        if (currentCase.tip != null && !currentCase.tip.isEmpty()) {
            addField("نصيحة", currentCase.tip);
        }

        List<String> safetyNotes = DataManager.getGeneralSafetyNote(currentCase.mode);
        if (!safetyNotes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String n : safetyNotes) sb.append("• ").append(n).append("\n");
            addField("⚠️ ملاحظات سلامة عامة", sb.toString().trim());
        }

        String term = currentCase.title.startsWith("بروتوكول")
                ? DataManager.extractEnglishTerm(currentCase.title) : null;
        if (term != null) {
            Button sourcesBtn = new Button(this);
            sourcesBtn.setText("🔗 مصادر طبية موثوقة (Physiopedia)");
            sourcesBtn.setOnClickListener(v -> openSources(term));
            container.addView(sourcesBtn);
        }

        if (currentCase.custom) {
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ap.topMargin = 20;
            actions.setLayoutParams(ap);

            Button editBtn = new Button(this);
            editBtn.setText("✏️ تعديل");
            editBtn.setOnClickListener(v -> editCase());

            Button deleteBtn = new Button(this);
            deleteBtn.setText("🗑️ حذف");
            deleteBtn.setOnClickListener(v -> confirmDelete());

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            actions.addView(editBtn, btnParams);
            actions.addView(deleteBtn, btnParams);
            container.addView(actions);
        }
    }

    private void addField(String label, String value) {
        if (value == null || value.isEmpty()) value = "-";
        View block = LayoutInflater.from(this).inflate(R.layout.item_field_block, container, false);
        TextView labelView = block.findViewById(R.id.field_label);
        TextView valueView = block.findViewById(R.id.field_value);
        labelView.setText(label);
        valueView.setText(value);
        container.addView(block);
    }

    private void openSources(String term) {
        try {
            Uri uri = Uri.parse("https://www.physio-pedia.com/index.php?search=" + Uri.encode(term));
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void editCase() {
        Intent i = new Intent(this, AddEditCaseActivity.class);
        i.putExtra("edit_case_id", currentCase.id);
        startActivity(i);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("تأكيد الحذف")
                .setMessage("هل تريد حذف \"" + currentCase.title + "\" نهائيًا؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    DataManager.deleteCustomCase(this, currentCase.id);
                    finish();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }
}
