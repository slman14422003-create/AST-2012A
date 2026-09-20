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

/**
 * تفاصيل برنامج علاج فيزيائي: العنوان والتشخيص ثم الأهداف والمراحل والتمارين
 * والاحتياطات وخطة الجلسات والملاحظات، مع مشاركة نصية وإسناد لمريض.
 */
public class TreatmentProgramDetailActivity extends AppCompatActivity {

    private String programId;
    private TreatmentProgram program;
    private LinearLayout container;
    private LayoutInflater inflater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_treatment_program_detail);
        inflater = LayoutInflater.from(this);

        programId = getIntent().getStringExtra("program_id");

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_share);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_share) {
                shareProgram();
                return true;
            }
            return false;
        });

        container = findViewById(R.id.program_detail_container);
    }

    @Override
    protected void onResume() {
        super.onResume();
        program = null;
        for (TreatmentProgram t : TreatmentProgramManager.loadPrograms(this)) {
            if (t.id != null && t.id.equals(programId)) {
                program = t;
                break;
            }
        }
        if (program == null) {
            finish();
            return;
        }
        render();
    }

    private boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private void render() {
        container.removeAllViews();

        // الرأس: العنوان + شريحة التشخيص + تاريخ الإضافة
        View hero = inflater.inflate(R.layout.item_detail_hero, container, false);
        LinearLayout badges = hero.findViewById(R.id.hero_badges);
        ((TextView) hero.findViewById(R.id.hero_title)).setText(
                BidiText.fix(notEmpty(program.title) ? program.title.trim() : "(بدون عنوان)"));

        if (notEmpty(program.diagnosis)) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_badge, badges, false);
            chip.setText(BidiText.fix("" + program.diagnosis.trim().replace('\n', ' ')));
            chip.setTextDirection(View.TEXT_DIRECTION_RTL);
            chip.setTextSize(12.5f);
            chip.setBackgroundResource(R.drawable.bg_source_chip);
            chip.setTextColor(getColor(R.color.m3_on_primary_container));
            badges.addView(chip);
        } else {
            badges.setVisibility(View.GONE);
        }

        TextView date = hero.findViewById(R.id.hero_english);
        date.setTextDirection(View.TEXT_DIRECTION_RTL);
        date.setText(BidiText.fix("أُضيف في " + Fmt.date(program.createdAt)));
        date.setVisibility(View.VISIBLE);
        container.addView(hero);

        addSection("الأهداف", program.goals, false);
        addSection("مراحل العلاج", program.phases, false);
        addSection("التمارين", program.exercises, false);
        addSection("خطة الجلسات", program.sessionsPlan, false);
        addSection("الاحتياطات", program.precautions, true);
        addSection("ملاحظات", program.notes, false);

        View actions = inflater.inflate(R.layout.item_program_actions, container, false);
        actions.findViewById(R.id.btn_assign_patient).setOnClickListener(v -> assignToPatient());
        actions.findViewById(R.id.btn_program_edit).setOnClickListener(v -> {
            Intent i = new Intent(this, AddEditTreatmentProgramActivity.class);
            i.putExtra("edit_program_id", program.id);
            startActivity(i);
        });
        actions.findViewById(R.id.btn_program_delete).setOnClickListener(v -> confirmDelete());
        container.addView(actions);
    }

    /** نص من سطر واحد = فقرة؛ عدة أسطر = قائمة نقطية. */
    private void addSection(String title, String text, boolean warning) {
        if (!notEmpty(text)) return;
        String[] lines = text.trim().split("\\r?\\n");
        int count = 0;
        for (String l : lines) if (!l.trim().isEmpty()) count++;

        if (count <= 1) {
            View card = inflater.inflate(R.layout.item_text_card, container, false);
            TextView label = card.findViewById(R.id.text_card_label);
            TextView body = card.findViewById(R.id.text_card_body);
            label.setText(title);
            body.setText(BidiText.fix(text.trim()));
            if (warning) {
                card.setBackgroundResource(R.drawable.bg_glass_card_warning);
                label.setTextColor(getColor(R.color.accent_red));
            }
            container.addView(card);
            return;
        }

        View card = inflater.inflate(R.layout.item_list_card, container, false);
        if (warning) card.setBackgroundResource(R.drawable.bg_glass_card_warning);
        TextView titleView = card.findViewById(R.id.list_title);
        titleView.setText(title);
        if (warning) titleView.setTextColor(getColor(R.color.accent_red));
        LinearLayout list = card.findViewById(R.id.list_container);
        for (String l : lines) {
            String clean = l.trim().replaceFirst("^[\\-\\u2022*]\\s+", "");
            if (clean.isEmpty()) continue;
            View row = inflater.inflate(R.layout.item_bullet_row, list, false);
            ((TextView) row.findViewById(R.id.bullet_text)).setText(BidiText.fix(clean));
            list.addView(row);
        }
        container.addView(card);
    }

    private void assignToPatient() {
        PatientDialogs.pickPatient(this, "إسناد البرنامج لأي مريض؟", patient -> {
            boolean added = PatientManager.addAssignment(this, patient.id,
                    Patient.Assignment.create(Patient.Assignment.TYPE_PROGRAM, program.id, program.title));
            Toast.makeText(this,
                    added ? "تم إسناد البرنامج إلى " + patient.displayName() + "."
                            : "هذا البرنامج مسند لهذا المريض بالفعل.",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void shareProgram() {
        if (program == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(notEmpty(program.title) ? program.title.trim() : "برنامج علاج").append('\n');
        appendSection(sb, "التشخيص", program.diagnosis);
        appendSection(sb, "الأهداف", program.goals);
        appendSection(sb, "مراحل العلاج", program.phases);
        appendSection(sb, "التمارين", program.exercises);
        appendSection(sb, "خطة الجلسات", program.sessionsPlan);
        appendSection(sb, "الاحتياطات", program.precautions);
        appendSection(sb, "ملاحظات", program.notes);

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, program.title);
        send.putExtra(Intent.EXTRA_TEXT, sb.toString().trim());
        startActivity(Intent.createChooser(send, "مشاركة البرنامج"));
    }

    private void appendSection(StringBuilder sb, String title, String text) {
        if (!notEmpty(text)) return;
        sb.append('\n').append(title).append(":\n").append(text.trim()).append('\n');
    }

    private void confirmDelete() {
        new ClaudeDialog(this)
                .setTitle("حذف برنامج العلاج")
                .setMessage("هل تريد حذف \"" + program.title + "\" نهائيًا؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    TreatmentProgramManager.deleteProgram(this, program.id);
                    Toast.makeText(this, "تم الحذف.", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }
}
