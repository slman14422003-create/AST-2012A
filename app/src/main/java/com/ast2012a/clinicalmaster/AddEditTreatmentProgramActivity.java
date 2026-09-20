package com.ast2012a.clinicalmaster;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class AddEditTreatmentProgramActivity extends AppCompatActivity {

    private TextInputEditText fTitle, fDiagnosis, fGoals, fPhases, fExercises, fPrecautions, fNotes;
    private String editingId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_edit_treatment_program);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        fTitle = findViewById(R.id.tp_title);
        fDiagnosis = findViewById(R.id.tp_diagnosis);
        fGoals = findViewById(R.id.tp_goals);
        fPhases = findViewById(R.id.tp_phases);
        fExercises = findViewById(R.id.tp_exercises);
        fPrecautions = findViewById(R.id.tp_precautions);
        fNotes = findViewById(R.id.tp_notes);

        Button submitBtn = findViewById(R.id.btn_submit_program);
        Button cancelBtn = findViewById(R.id.btn_cancel_program);
        cancelBtn.setOnClickListener(v -> finish());

        editingId = getIntent().getStringExtra("edit_program_id");
        if (editingId != null) {
            toolbar.setTitle("تعديل برنامج العلاج");
            submitBtn.setText("حفظ التعديلات");
            loadForEdit(editingId);
        }

        submitBtn.setOnClickListener(v -> submit());
    }

    private void loadForEdit(String id) {
        List<TreatmentProgram> list = TreatmentProgramManager.loadPrograms(this);
        for (TreatmentProgram t : list) {
            if (id.equals(t.id)) {
                fTitle.setText(t.title);
                fDiagnosis.setText(t.diagnosis);
                fGoals.setText(t.goals);
                fPhases.setText(t.phases);
                fExercises.setText(t.exercises);
                fPrecautions.setText(t.precautions);
                fNotes.setText(t.notes);
                return;
            }
        }
        Toast.makeText(this, "تعذر العثور على البرنامج المطلوب تعديله.", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void submit() {
        String title = textOf(fTitle);
        if (title.isEmpty()) {
            Toast.makeText(this, "عنوان البرنامج مطلوب.", Toast.LENGTH_SHORT).show();
            return;
        }

        TreatmentProgram t = new TreatmentProgram();
        t.title = title;
        t.diagnosis = textOf(fDiagnosis);
        t.goals = textOf(fGoals);
        t.phases = textOf(fPhases);
        t.exercises = textOf(fExercises);
        t.precautions = textOf(fPrecautions);
        t.notes = textOf(fNotes);

        if (editingId != null) {
            t.id = editingId;
            TreatmentProgramManager.updateProgram(this, t);
            Toast.makeText(this, "تم حفظ التعديلات.", Toast.LENGTH_SHORT).show();
        } else {
            TreatmentProgramManager.addProgram(this, t);
            Toast.makeText(this, "تم حفظ برنامج العلاج.", Toast.LENGTH_SHORT).show();
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
