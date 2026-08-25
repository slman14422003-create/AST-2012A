package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/** شاشة قائمة برامج العلاج الفيزيائي المحفوظة. */
public class TreatmentProgramsActivity extends AppCompatActivity {

    private LinearLayout container;
    private View emptyHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_treatment_programs);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        container = findViewById(R.id.programs_container);
        emptyHint = findViewById(R.id.programs_empty_hint);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<TreatmentProgram> programs = TreatmentProgramManager.loadPrograms(this);
        container.removeAllViews();
        emptyHint.setVisibility(programs.isEmpty() ? View.VISIBLE : View.GONE);

        for (TreatmentProgram t : programs) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_treatment_program_row, container, false);
            TextView title = row.findViewById(R.id.program_title);
            TextView diagnosis = row.findViewById(R.id.program_diagnosis);

            title.setText(t.title.isEmpty() ? "(بدون عنوان)" : t.title);
            if (!t.diagnosis.isEmpty()) {
                diagnosis.setText("🩺 " + t.diagnosis);
                diagnosis.setVisibility(View.VISIBLE);
            } else {
                diagnosis.setVisibility(View.GONE);
            }

            row.findViewById(R.id.program_btn_edit).setOnClickListener(v -> {
                Intent i = new Intent(this, AddEditTreatmentProgramActivity.class);
                i.putExtra("edit_program_id", t.id);
                startActivity(i);
            });
            row.findViewById(R.id.program_btn_delete).setOnClickListener(v -> confirmDelete(t));

            container.addView(row);
        }
    }

    private void confirmDelete(TreatmentProgram t) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("حذف برنامج العلاج")
                .setMessage("هل تريد حذف \"" + t.title + "\" نهائيًا؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    TreatmentProgramManager.deleteProgram(this, t.id);
                    Toast.makeText(this, "تم الحذف.", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }
}
