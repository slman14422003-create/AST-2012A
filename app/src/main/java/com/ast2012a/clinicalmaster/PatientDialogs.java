package com.ast2012a.clinicalmaster;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * نوافذ ملف المريض المشتركة بين الشاشات: تسجيل/تعديل جلسة، تسجيل دفعة،
 * اختيار مريض، اختيار تاريخ ووقت، وإدخال نص واحد. تُستدعى من شاشة المريض
 * ومن شاشة تفاصيل الحالة ومن شاشة تفاصيل برنامج العلاج.
 */
public final class PatientDialogs {

    private PatientDialogs() {}

    public interface TextResult {
        void onText(String text);
    }

    public interface TimeResult {
        void onTime(long millis);
    }

    public interface PatientResult {
        void onPatient(Patient patient);
    }

    // =====================================================================
    // اختيار التاريخ والوقت
    // =====================================================================

    /** يختار تاريخًا ثم وقتًا ويعيد الميلي ثانية. */
    public static void pickDateTime(final Activity activity, final long initial, final TimeResult result) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(initial);
        new DatePickerDialog(activity, (picker, year, month, day) -> {
            final Calendar chosen = Calendar.getInstance();
            chosen.setTimeInMillis(initial);
            chosen.set(year, month, day);
            new TimePickerDialog(activity, (tp, hour, minute) -> {
                chosen.set(Calendar.HOUR_OF_DAY, hour);
                chosen.set(Calendar.MINUTE, minute);
                chosen.set(Calendar.SECOND, 0);
                chosen.set(Calendar.MILLISECOND, 0);
                result.onTime(chosen.getTimeInMillis());
            }, chosen.get(Calendar.HOUR_OF_DAY), chosen.get(Calendar.MINUTE), false).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** يختار تاريخًا فقط (يُحتفظ بوقت اليوم من القيمة الابتدائية). */
    public static void pickDate(final Activity activity, final long initial, final TimeResult result) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(initial);
        new DatePickerDialog(activity, (picker, year, month, day) -> {
            Calendar chosen = Calendar.getInstance();
            chosen.setTimeInMillis(initial);
            chosen.set(year, month, day);
            result.onTime(chosen.getTimeInMillis());
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    // =====================================================================
    // اختيار مريض
    // =====================================================================

    public static void pickPatient(final Activity activity, String title, final PatientResult result) {
        final List<Patient> patients = PatientManager.loadPatients(activity);
        if (patients.isEmpty()) {
            Toast.makeText(activity, "أضف مريضًا أولًا من شاشة المرضى.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[patients.size()];
        for (int i = 0; i < names.length; i++) names[i] = patients.get(i).displayName();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setItems(names, (dialog, which) -> result.onPatient(patients.get(which)))
                .show();
    }

    // =====================================================================
    // إدخال نص واحد
    // =====================================================================

    public static void showTextInput(final Activity activity, String title, String hint, String message,
                                     String initial, final TextResult result) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_input, null);
        ((TextView) view.findViewById(R.id.dlg_title)).setText(title);
        TextView msg = view.findViewById(R.id.dlg_message);
        if (message != null && !message.isEmpty()) {
            msg.setText(message);
            msg.setVisibility(View.VISIBLE);
        }
        ((com.google.android.material.textfield.TextInputLayout) view.findViewById(R.id.dlg_input_layout))
                .setHint(hint);
        final TextInputEditText input = view.findViewById(R.id.dlg_input);
        if (initial != null) input.setText(initial);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setView(view).create();
        view.findViewById(R.id.dlg_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.dlg_save).setOnClickListener(v -> {
            String text = input.getText() == null ? "" : input.getText().toString().trim();
            dialog.dismiss();
            result.onText(text);
        });
        dialog.show();
    }

    // =====================================================================
    // جلسة
    // =====================================================================

    /**
     * يعرض نافذة تسجيل جلسة جديدة (existing = null) أو تعديل جلسة موجودة.
     * presetProtocol يُملأ مسبقًا عند التسجيل من شاشة تفاصيل الحالة.
     */
    public static void showSession(final Activity activity, final String patientId,
                                   final Patient.Session existing, String presetProtocol,
                                   final Runnable onSaved) {
        final Patient patient = PatientManager.getPatient(activity, patientId);
        if (patient == null) return;

        final boolean editing = existing != null;
        final Patient.Session session = editing ? copyOf(existing) : Patient.Session.create();
        if (!editing) {
            session.fee = patient.sessionFee;
            if (presetProtocol != null) session.protocol = presetProtocol;
        }

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_session, null);
        ((TextView) view.findViewById(R.id.dlg_title)).setText(editing ? "تعديل الجلسة" : "تسجيل جلسة");

        final TextView dateView = view.findViewById(R.id.dlg_date);
        final long[] when = {session.date};
        dateView.setText("📅 " + Fmt.dateTime(when[0]));
        dateView.setOnClickListener(v -> pickDateTime(activity, when[0], millis -> {
            when[0] = millis;
            dateView.setText("📅 " + Fmt.dateTime(millis));
        }));

        final MaterialAutoCompleteTextView protocolField = view.findViewById(R.id.dlg_protocol);
        List<String> titles = new ArrayList<>();
        for (CaseItem c : DataManager.allCases(activity)) titles.add(c.title);
        ArrayAdapter<String> protocolAdapter =
                new ArrayAdapter<String>(activity, R.layout.item_dropdown_line, titles);
        protocolField.setAdapter(protocolAdapter);
        protocolField.setText(session.protocol, false);

        final TextInputEditText durationField = view.findViewById(R.id.dlg_duration);
        final TextInputEditText feeField = view.findViewById(R.id.dlg_fee);
        final TextInputEditText painBeforeField = view.findViewById(R.id.dlg_pain_before);
        final TextInputEditText painAfterField = view.findViewById(R.id.dlg_pain_after);
        final TextInputEditText notesField = view.findViewById(R.id.dlg_notes);

        if (session.durationMin > 0) durationField.setText(String.valueOf(session.durationMin));
        if (session.fee > 0) feeField.setText(numText(session.fee));
        if (session.painBefore >= 0) painBeforeField.setText(String.valueOf(session.painBefore));
        if (session.painAfter >= 0) painAfterField.setText(String.valueOf(session.painAfter));
        notesField.setText(session.notes);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setView(view).create();
        view.findViewById(R.id.dlg_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.dlg_save).setOnClickListener(v -> {
            int painBefore = parsePain(painBeforeField);
            int painAfter = parsePain(painAfterField);
            if (painBefore == -2 || painAfter == -2) {
                Toast.makeText(activity, "شدة الألم يجب أن تكون بين 0 و10.", Toast.LENGTH_SHORT).show();
                return;
            }
            session.date = when[0];
            session.protocol = textOf(protocolField);
            session.durationMin = (int) Math.max(0, Math.round(parseNumber(durationField)));
            session.fee = Math.max(0, parseNumber(feeField));
            session.painBefore = painBefore;
            session.painAfter = painAfter;
            session.notes = textOf(notesField);
            PatientManager.saveSession(activity, patientId, session);
            dialog.dismiss();
            if (onSaved != null) onSaved.run();
        });
        dialog.show();
    }

    private static Patient.Session copyOf(Patient.Session s) {
        Patient.Session c = new Patient.Session();
        c.id = s.id;
        c.date = s.date;
        c.protocol = s.protocol;
        c.durationMin = s.durationMin;
        c.painBefore = s.painBefore;
        c.painAfter = s.painAfter;
        c.fee = s.fee;
        c.notes = s.notes;
        return c;
    }

    // =====================================================================
    // دفعة
    // =====================================================================

    public static void showPayment(final Activity activity, final String patientId, final Runnable onSaved) {
        final Patient patient = PatientManager.getPatient(activity, patientId);
        if (patient == null) return;
        String currency = PatientManager.getCurrency(activity);

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_payment, null);
        TextView hint = view.findViewById(R.id.dlg_balance_hint);
        double balance = patient.balance();
        if (balance > 0.004) {
            hint.setText("المتبقي على المريض: " + Fmt.money(balance, currency));
            hint.setVisibility(View.VISIBLE);
        }

        final TextView dateView = view.findViewById(R.id.dlg_date);
        final long[] when = {System.currentTimeMillis()};
        dateView.setText("📅 " + Fmt.date(when[0]));
        dateView.setOnClickListener(v -> pickDate(activity, when[0], millis -> {
            when[0] = millis;
            dateView.setText("📅 " + Fmt.date(millis));
        }));

        final TextInputEditText amountField = view.findViewById(R.id.dlg_amount);
        final TextInputEditText noteField = view.findViewById(R.id.dlg_note);
        if (balance > 0.004) amountField.setText(numText(balance));

        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setView(view).create();
        view.findViewById(R.id.dlg_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.dlg_save).setOnClickListener(v -> {
            double amount = parseNumber(amountField);
            if (amount <= 0) {
                Toast.makeText(activity, "أدخل مبلغًا أكبر من صفر.", Toast.LENGTH_SHORT).show();
                return;
            }
            Patient.Payment payment = Patient.Payment.create();
            payment.date = when[0];
            payment.amount = amount;
            payment.note = textOf(noteField);
            PatientManager.addPayment(activity, patientId, payment);
            dialog.dismiss();
            if (onSaved != null) onSaved.run();
        });
        dialog.show();
    }

    // =====================================================================
    // أدوات مساعدة
    // =====================================================================

    private static String textOf(android.widget.EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    /** يقرأ رقمًا عشريًا (يقبل الفاصلة العربية والأرقام الهندية)؛ الفارغ = 0. */
    private static double parseNumber(android.widget.EditText field) {
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

    /** -1 = فارغ، -2 = غير صالح (خارج 0..10)، وإلا القيمة. */
    private static int parsePain(android.widget.EditText field) {
        String t = textOf(field);
        if (t.isEmpty()) return -1;
        double v = parseNumber(field);
        if (v < 0 || v > 10) return -2;
        return (int) Math.round(v);
    }

    private static String numText(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 100.0) / 100.0);
    }
}
