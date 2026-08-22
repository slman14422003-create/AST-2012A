package com.ast2012a.clinicalmaster;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AddEditCaseActivity extends AppCompatActivity {

    private TextInputEditText fKeywords, fTitle, fMode, fFreq, fChannel, fDuration,
            fPolePos, fPoleNeg, fExplanation, fSymptoms, fSessionsPlan, fTip;
    private MaterialToolbar toolbar;
    private Button submitBtn;

    private String editingCaseId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_edit);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        fKeywords = findViewById(R.id.f_keywords);
        fTitle = findViewById(R.id.f_title);
        fMode = findViewById(R.id.f_mode);
        fFreq = findViewById(R.id.f_freq);
        fChannel = findViewById(R.id.f_channel);
        fDuration = findViewById(R.id.f_duration);
        fPolePos = findViewById(R.id.f_pole_pos);
        fPoleNeg = findViewById(R.id.f_pole_neg);
        fExplanation = findViewById(R.id.f_explanation);
        fSymptoms = findViewById(R.id.f_symptoms);
        fSessionsPlan = findViewById(R.id.f_sessions_plan);
        fTip = findViewById(R.id.f_tip);

        submitBtn = findViewById(R.id.btn_submit);
        Button cancelBtn = findViewById(R.id.btn_cancel);

        submitBtn.setOnClickListener(v -> submit());
        cancelBtn.setOnClickListener(v -> finish());

        editingCaseId = getIntent().getStringExtra("edit_case_id");
        if (editingCaseId != null) {
            loadForEdit(editingCaseId);
        } else {
            toolbar.setTitle("➕ إضافة حالة جديدة");
            submitBtn.setText("💾 حفظ الحالة");
            applyPrefillFromAi();
        }
    }

    /**
     * لو التطبيق فتح هذه الشاشة من زر "حفظ كحالة جديدة" في المساعد الذكي،
     * نعبّئ حقل الشرح تلقائيًا بردّ الذكاء الاصطناعي حتى يراجعه المستخدم
     * ويكمل باقي الحقول بنفسه قبل الحفظ.
     */
    private void applyPrefillFromAi() {
        String prefillExplanation = getIntent().getStringExtra("prefill_explanation");
        if (prefillExplanation != null && !prefillExplanation.isEmpty()) {
            fExplanation.setText(prefillExplanation);
            Toast.makeText(this, "راجع الحقول وأكمل العنوان والكلمات المفتاحية قبل الحفظ.", Toast.LENGTH_LONG).show();
        }
    }

    private void loadForEdit(String id) {
        CaseItem item = null;
        for (CaseItem c : DataManager.loadCustomCases(this)) {
            if (id.equals(c.id)) { item = c; break; }
        }
        if (item == null) {
            Toast.makeText(this, "تعذر العثور على الحالة المطلوب تعديلها.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        toolbar.setTitle("✏️ تعديل الحالة");
        submitBtn.setText("💾 حفظ التعديلات");

        fKeywords.setText(String.join(", ", item.keywords));
        fTitle.setText(item.title);
        fMode.setText(item.mode);
        fFreq.setText(item.freq);
        fChannel.setText(item.channel);
        fDuration.setText(item.duration);
        fPolePos.setText(item.poles.size() > 0 ? item.poles.get(0) : "");
        fPoleNeg.setText(item.poles.size() > 1 ? item.poles.get(1) : "");
        fExplanation.setText(item.explanation);
        fSymptoms.setText(item.symptoms);
        fSessionsPlan.setText(item.sessionsPlan);
        fTip.setText(item.tip);
    }

    private void submit() {
        String title = textOf(fTitle);
        String mode = textOf(fMode);
        String explanation = textOf(fExplanation);
        String keywordsRaw = textOf(fKeywords);

        if (title.isEmpty() || mode.isEmpty() || explanation.isEmpty() || keywordsRaw.isEmpty()) {
            Toast.makeText(this, "العنوان، النمط، الشرح، والكلمات المفتاحية حقول إلزامية.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> keywords = new ArrayList<>();
        for (String k : keywordsRaw.split(",")) {
            String t = k.trim();
            if (!t.isEmpty()) keywords.add(t);
        }

        String polePos = textOf(fPolePos);
        String poleNeg = textOf(fPoleNeg);
        List<String> poles = new ArrayList<>();
        if (!polePos.isEmpty()) {
            poles.add(polePos);
            if (!poleNeg.isEmpty()) poles.add(poleNeg);
        }

        CaseItem fields = new CaseItem();
        fields.keywords = keywords;
        fields.title = title;
        fields.mode = mode;
        fields.freq = textOf(fFreq);
        fields.channel = textOf(fChannel).isEmpty() ? "القناتين 1 و 2" : textOf(fChannel);
        fields.duration = textOf(fDuration).isEmpty() ? "20-30 دقيقة" : textOf(fDuration);
        fields.poles = poles;
        fields.explanation = explanation;
        fields.symptoms = textOf(fSymptoms);
        fields.sessionsPlan = textOf(fSessionsPlan);
        fields.tip = textOf(fTip);

        if (editingCaseId != null) {
            DataManager.updateCustomCase(this, editingCaseId, fields);
            Toast.makeText(this, "✅ تم حفظ التعديلات وتحديث محرك البحث فوراً.", Toast.LENGTH_SHORT).show();
        } else {
            DataManager.addCustomCase(this, fields);
            Toast.makeText(this, "✅ تم حفظ الحالة وربطها بمحرك البحث فوراً.", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private String textOf(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_down_out, R.anim.fade_out);
    }
}
