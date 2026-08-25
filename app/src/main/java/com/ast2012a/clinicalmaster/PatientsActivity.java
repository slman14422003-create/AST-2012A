package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;

/**
 * شاشة "المرضى" - نقطة البداية لميزة ملفات المرضى. حاليًا تدير فقط
 * البيانات الأساسية (اسم، تواصل، ملاحظة)؛ تفاصيل الجلسات والتاريخ
 * والحساب المالي مجهّزة في PatientDetailActivity كأقسام "قريبًا".
 */
public class PatientsActivity extends AppCompatActivity {

    private LinearLayout container;
    private View emptyHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patients);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        container = findViewById(R.id.patients_container);
        emptyHint = findViewById(R.id.patients_empty_hint);

        FloatingActionButton fab = findViewById(R.id.fab_add_patient);
        fab.setOnClickListener(v -> startActivity(new Intent(this, AddEditPatientActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<Patient> patients = PatientManager.loadPatients(this);
        container.removeAllViews();
        emptyHint.setVisibility(patients.isEmpty() ? View.VISIBLE : View.GONE);

        for (Patient p : patients) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_patient_row, container, false);
            TextView name = row.findViewById(R.id.patient_name);
            TextView phone = row.findViewById(R.id.patient_phone);

            name.setText(p.name.isEmpty() ? "(بدون اسم)" : p.name);
            if (!p.phone.isEmpty()) {
                phone.setText("📞 " + p.phone);
                phone.setVisibility(View.VISIBLE);
            } else {
                phone.setVisibility(View.GONE);
            }

            row.setOnClickListener(v -> {
                Intent i = new Intent(this, PatientDetailActivity.class);
                i.putExtra("patient_id", p.id);
                startActivity(i);
            });

            container.addView(row);
        }
    }
}
