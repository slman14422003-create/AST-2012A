package com.ast2012a.clinicalmaster;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

/**
 * نموذج إضافة/تعديل بيانات المريض الأساسية: الاسم، الهاتف، العمر، الجنس،
 * الشكوى/التشخيص، سعر الجلسة الافتراضي، الملاحظات، وجدول الجلسات الأسبوعي
 * (أيام + وقت). عند التعديل نحدّث حقول المريض الحالي فقط فتبقى جلساته
 * ودفعاته كما هي.
 */
public class AddEditPatientActivity extends AppCompatActivity {

    private static final String MALE = "ذكر";
    private static final String FEMALE = "أنثى";

    private String editingId;
    private String gender = "";

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_down_out, R.anim.fade_out);
    }

    private TextInputEditText fieldName;
    private TextInputEditText fieldPhone;
    private TextInputEditText fieldAge;
    private TextInputEditText fieldDiagnosis;
    private TextInputEditText fieldFee;
    private TextInputEditText fieldNotes;
    private TextView genderMale;
    private TextView genderFemale;

    // جدول الجلسات الأسبوعي: ترتيب المعرّفات = Patient.WEEK_ORDER (السبت ... الجمعة)
    private final int[] dayIds = {R.id.day_sat, R.id.day_sun, R.id.day_mon, R.id.day_tue,
            R.id.day_wed, R.id.day_thu, R.id.day_fri};
    private int sessionDaysMask;
    private int sessionTimeMin = -1;
    private TextView sessionTimeText;
    private View sessionTimeClear;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_edit_patient);

        editingId = getIntent().getStringExtra("edit_patient_id");

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.toolbar_title)).setText(
                editingId != null ? "تعديل بيانات المريض" : "مريض جديد");

        fieldName = findViewById(R.id.field_name);
        fieldPhone = findViewById(R.id.field_phone);
        fieldAge = findViewById(R.id.field_age);
        fieldDiagnosis = findViewById(R.id.field_diagnosis);
        fieldFee = findViewById(R.id.field_fee);
        fieldNotes = findViewById(R.id.field_notes);
        genderMale = findViewById(R.id.gender_male);
        genderFemale = findViewById(R.id.gender_female);

        genderMale.setOnClickListener(v -> setGender(MALE.equals(gender) ? "" : MALE));
        genderFemale.setOnClickListener(v -> setGender(FEMALE.equals(gender) ? "" : FEMALE));

        sessionTimeText = findViewById(R.id.session_time_text);
        sessionTimeClear = findViewById(R.id.session_time_clear);
        for (int i = 0; i < dayIds.length; i++) {
            final int dow = Patient.WEEK_ORDER[i];
            findViewById(dayIds[i]).setOnClickListener(v -> {
                sessionDaysMask ^= (1 << (dow - 1));
                updateDayChips();
            });
        }
        findViewById(R.id.session_time_row).setOnClickListener(v -> pickSessionTime());
        sessionTimeClear.setOnClickListener(v -> {
            sessionTimeMin = -1;
            updateTimeRow();
        });

        if (editingId != null) {
            Patient p = PatientManager.getPatient(this, editingId);
            if (p == null) {
                finish();
                return;
            }
            fieldName.setText(p.name);
            fieldPhone.setText(p.phone);
            if (p.age > 0) fieldAge.setText(String.valueOf(p.age));
            fieldDiagnosis.setText(p.diagnosis);
            if (p.sessionFee > 0) fieldFee.setText(numText(p.sessionFee));
            fieldNotes.setText(p.notes);
            gender = p.gender;
            sessionDaysMask = p.sessionDaysMask;
            sessionTimeMin = p.sessionTimeMin;
        }
        updateGenderChips();
        updateDayChips();
        updateTimeRow();

        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
    }

    private void setGender(String value) {
        gender = value;
        updateGenderChips();
    }

    private void updateGenderChips() {
        styleChip(genderMale, MALE.equals(gender));
        styleChip(genderFemale, FEMALE.equals(gender));
    }

    private void updateDayChips() {
        for (int i = 0; i < dayIds.length; i++) {
            int dow = Patient.WEEK_ORDER[i];
            styleChip(findViewById(dayIds[i]), (sessionDaysMask & (1 << (dow - 1))) != 0);
        }
    }

    private void updateTimeRow() {
        if (sessionTimeMin >= 0) {
            sessionTimeText.setText(Fmt.minutesToTime(sessionTimeMin));
            sessionTimeText.setTextColor(getColor(R.color.text_primary));
            sessionTimeClear.setVisibility(View.VISIBLE);
        } else {
            sessionTimeText.setText("اختر الوقت");
            sessionTimeText.setTextColor(getColor(R.color.text_tertiary));
            sessionTimeClear.setVisibility(View.GONE);
        }
    }

    private void pickSessionTime() {
        int initial = sessionTimeMin >= 0 ? sessionTimeMin : 17 * 60;
        final MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(initial / 60)
                .setMinute(initial % 60)
                .setTitleText("وقت الجلسة")
                .build();
        picker.addOnPositiveButtonClickListener(v -> {
            sessionTimeMin = picker.getHour() * 60 + picker.getMinute();
            updateTimeRow();
        });
        picker.show(getSupportFragmentManager(), "session_time");
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.bg_glass_chip_selected : R.drawable.bg_glass_chip);
        chip.setTextColor(getColor(selected ? R.color.white : R.color.text_secondary));
    }

    private String textOf(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private double numberOf(TextInputEditText field) {
        String t = textOf(field);
        if (t.isEmpty()) return 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch >= '\u0660' && ch <= '\u0669') ch = (char) ('0' + (ch - '\u0660'));
            else if (ch == '\u066B' || ch == '\u060C' || ch == ',') ch = '.';
            sb.append(ch);
        }
        try {
            double v = Double.parseDouble(sb.toString());
            return (Double.isNaN(v) || Double.isInfinite(v)) ? 0 : v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String numText(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 100.0) / 100.0);
    }

    private void save() {
        String name = textOf(fieldName);
        if (name.isEmpty()) {
            fieldName.setError("الاسم مطلوب");
            fieldName.requestFocus();
            return;
        }
        int age = (int) Math.round(numberOf(fieldAge));
        if (age < 0 || age > 120) {
            fieldAge.setError("عمر غير صحيح");
            fieldAge.requestFocus();
            return;
        }

        Patient p;
        if (editingId != null) {
            p = PatientManager.getPatient(this, editingId);
            if (p == null) {
                finish();
                return;
            }
        } else {
            p = new Patient();
        }
        p.name = name;
        p.phone = textOf(fieldPhone);
        p.age = age;
        p.gender = gender;
        p.diagnosis = textOf(fieldDiagnosis);
        p.sessionFee = Math.max(0, numberOf(fieldFee));
        p.notes = textOf(fieldNotes);
        p.sessionDaysMask = sessionDaysMask;
        p.sessionTimeMin = sessionTimeMin;

        if (editingId != null) {
            PatientManager.updatePatient(this, p);
            Toast.makeText(this, "تم حفظ التعديلات.", Toast.LENGTH_SHORT).show();
        } else {
            PatientManager.addPatient(this, p);
            Toast.makeText(this, "تمت إضافة المريض.", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
