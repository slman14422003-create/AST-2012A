package com.ast2012a.clinicalmaster;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class AddEditPatientActivity extends AppCompatActivity {

    private TextInputEditText fName, fPhone, fNotes;
    private String editingId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_edit_patient);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        fName = findViewById(R.id.pf_name);
        fPhone = findViewById(R.id.pf_phone);
        fNotes = findViewById(R.id.pf_notes);

        Button submitBtn = findViewById(R.id.btn_submit_patient);
        Button cancelBtn = findViewById(R.id.btn_cancel_patient);
        cancelBtn.setOnClickListener(v -> finish());

        editingId = getIntent().getStringExtra("edit_patient_id");
        if (editingId != null) {
            toolbar.setTitle("✏️ تعديل بيانات المريض");
            submitBtn.setText("💾 حفظ التعديلات");
            loadForEdit(editingId);
        }

        submitBtn.setOnClickListener(v -> submit());
    }

    private void loadForEdit(String id) {
        List<Patient> list = PatientManager.loadPatients(this);
        for (Patient p : list) {
            if (id.equals(p.id)) {
                fName.setText(p.name);
                fPhone.setText(p.phone);
                fNotes.setText(p.notes);
                return;
            }
        }
        Toast.makeText(this, "تعذر العثور على بيانات المريض.", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void submit() {
        String name = textOf(fName);
        if (name.isEmpty()) {
            Toast.makeText(this, "اسم المريض مطلوب.", Toast.LENGTH_SHORT).show();
            return;
        }

        Patient p = new Patient();
        p.name = name;
        p.phone = textOf(fPhone);
        p.notes = textOf(fNotes);

        if (editingId != null) {
            p.id = editingId;
            PatientManager.updatePatient(this, p);
            Toast.makeText(this, "✅ تم حفظ التعديلات.", Toast.LENGTH_SHORT).show();
        } else {
            PatientManager.addPatient(this, p);
            Toast.makeText(this, "✅ تم إضافة المريض.", Toast.LENGTH_SHORT).show();
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
