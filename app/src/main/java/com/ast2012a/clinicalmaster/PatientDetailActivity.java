package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * ملف المريض. القسم العلوي (الاسم/التواصل/الملاحظات) فعّال بالكامل.
 * الأقسام الثلاثة السفلية (الجلسات، التاريخ العلاجي، الحساب المالي)
 * "مجهّزة" فقط كواجهة بصرية بشارة "قريبًا" - بحسب طلب المستخدم أن
 * الميزة تُحضَّر الآن دون تفعيلها بالكامل بعد.
 */
public class PatientDetailActivity extends AppCompatActivity {

    private String patientId;
    private Patient current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient_detail);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        patientId = getIntent().getStringExtra("patient_id");

        bindComingSoonSection(R.id.section_sessions, "📅 الجلسات",
                "سجل جلسات العلاج، تواريخها، والملاحظات الخاصة بكل جلسة.");
        bindComingSoonSection(R.id.section_history, "📋 التاريخ العلاجي",
                "الحالات والبروتوكولات السابقة المرتبطة بهذا المريض عبر الزمن.");
        bindComingSoonSection(R.id.section_billing, "💳 الحساب المالي",
                "متابعة المدفوعات والمستحقات (الديون) الخاصة بالمريض.");

        findViewById(R.id.btn_edit_patient).setOnClickListener(v -> {
            Intent i = new Intent(this, AddEditPatientActivity.class);
            i.putExtra("edit_patient_id", patientId);
            startActivity(i);
        });

        findViewById(R.id.btn_delete_patient).setOnClickListener(v -> confirmDelete());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPatient();
    }

    private void loadPatient() {
        current = null;
        List<Patient> list = PatientManager.loadPatients(this);
        for (Patient p : list) {
            if (patientId != null && patientId.equals(p.id)) {
                current = p;
                break;
            }
        }
        if (current == null) {
            Toast.makeText(this, "تعذر العثور على بيانات المريض.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        TextView name = findViewById(R.id.detail_patient_name);
        TextView phone = findViewById(R.id.detail_patient_phone);
        TextView notes = findViewById(R.id.detail_patient_notes);

        name.setText(current.name.isEmpty() ? "(بدون اسم)" : current.name);
        if (!current.phone.isEmpty()) {
            phone.setText("📞 " + current.phone);
            phone.setVisibility(View.VISIBLE);
        } else {
            phone.setVisibility(View.GONE);
        }
        if (!current.notes.isEmpty()) {
            notes.setText(current.notes);
            notes.setVisibility(View.VISIBLE);
        } else {
            notes.setVisibility(View.GONE);
        }
    }

    private void bindComingSoonSection(int includeRootId, String title, String desc) {
        View section = findViewById(includeRootId);
        ((TextView) section.findViewById(R.id.cs_title)).setText(title);
        ((TextView) section.findViewById(R.id.cs_desc)).setText(desc);
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("حذف المريض")
                .setMessage("هل تريد حذف بيانات هذا المريض نهائيًا؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    PatientManager.deletePatient(this, patientId);
                    Toast.makeText(this, "تم حذف المريض.", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
