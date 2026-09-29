package com.ast2012a.clinicalmaster;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
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
        new ClaudeDialog(activity)
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
        dateView.setText("" + Fmt.dateTime(when[0]));
        dateView.setOnClickListener(v -> pickDateTime(activity, when[0], millis -> {
            when[0] = millis;
            dateView.setText("" + Fmt.dateTime(millis));
        }));

        final TextInputLayout protocolLayout = view.findViewById(R.id.dlg_protocol_layout);
        final TextInputEditText protocolField = view.findViewById(R.id.dlg_protocol);
        protocolField.setText(session.protocol);
        View.OnClickListener openPicker = v -> showProtocolPicker(activity, textOf(protocolField),
                text -> protocolField.setText(text));
        protocolField.setOnClickListener(openPicker);
        protocolLayout.setEndIconOnClickListener(openPicker);

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

    // =====================================================================
    // اختيار البروتوكول (نافذة بحث)
    // =====================================================================

    /**
     * نافذة اختيار بروتوكول/حالة: حقل بحث في الأعلى وقائمة تتصفّى فورًا أثناء
     * الكتابة. الضغط على أي عنصر يختاره مباشرة، ويمكن أيضًا كتابة اسم غير موجود
     * في القاعدة واعتماده كنص حر. (بديل عن AutoCompleteTextView الذي كان لا يعرض
     * أي شيء عند الضغط عليه).
     */
    private static void showProtocolPicker(final Activity activity, final String current,
                                           final TextResult result) {
        final List<String> titles = new ArrayList<>();
        final List<String> normalized = new ArrayList<>();
        try {
            for (CaseItem c : DataManager.allCases(activity)) {
                if (c == null || c.title == null) continue;
                String t = c.title.trim();
                if (t.isEmpty() || titles.contains(t)) continue;
                titles.add(t);
                normalized.add(DataManager.normalize(t));
            }
        } catch (Throwable ignored) {
        }

        final float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        final EditText search = new EditText(activity);
        search.setBackgroundResource(R.drawable.bg_input_field);
        search.setHint("ابحث أو اكتب اسم بروتوكول...");
        search.setHintTextColor(activity.getColor(R.color.text_tertiary));
        search.setTextColor(activity.getColor(R.color.text_primary));
        search.setTextSize(15f);
        search.setSingleLine(true);
        search.setTextDirection(View.TEXT_DIRECTION_RTL);
        search.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        search.setCompoundDrawablePadding(Math.round(10 * density));
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        int padH = Math.round(14 * density);
        int padV = Math.round(12 * density);
        search.setPadding(padH, padV, padH, padV);
        if (current != null && !current.trim().isEmpty()) {
            search.setText(current.trim());
            search.selectAll();
        }
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(280 * density));
        scrollLp.topMargin = Math.round(10 * density);
        root.addView(scroll, scrollLp);

        final Dialog[] ref = new Dialog[1];
        final String currentTrim = current == null ? "" : current.trim();

        final Runnable render = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                String q = DataManager.normalize(search.getText().toString().trim());
                int shown = 0;
                for (int i = 0; i < titles.size(); i++) {
                    if (!q.isEmpty() && !normalized.get(i).contains(q)) continue;
                    list.addView(buildPickerRow(activity, list, titles.get(i),
                            titles.get(i).equals(currentTrim), ref, result));
                    if (++shown >= 100) break;
                }
                if (shown == 0) {
                    TextView empty = new TextView(activity);
                    empty.setText(q.isEmpty()
                            ? "لا توجد حالات محفوظة بعد."
                            : "لا توجد نتائج مطابقة. اضغط «اعتماد النص» لاستخدام ما كتبته كما هو.");
                    empty.setTextColor(activity.getColor(R.color.text_secondary));
                    empty.setTextSize(14f);
                    empty.setLineSpacing(0f, 1.25f);
                    empty.setTextDirection(View.TEXT_DIRECTION_RTL);
                    empty.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                    empty.setGravity(Gravity.CENTER_VERTICAL);
                    empty.setPadding(Math.round(6 * density), Math.round(18 * density),
                            Math.round(6 * density), Math.round(18 * density));
                    list.addView(empty);
                }
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render.run();
            }
        });
        render.run();

        ClaudeDialog builder = new ClaudeDialog(activity)
                .setTitle("اختيار البروتوكول")
                .setView(root)
                .setPositiveButton("اعتماد النص", (d, w) -> {
                    String typed = search.getText() == null ? "" : search.getText().toString().trim();
                    if (!typed.isEmpty()) result.onText(typed);
                })
                .setNegativeButton("إلغاء", null);
        if (!currentTrim.isEmpty()) {
            builder.setNeutralButton("إزالة الاختيار", (d, w) -> result.onText(""));
        }
        ref[0] = builder.show();

        search.postDelayed(() -> {
            try {
                search.requestFocus();
                InputMethodManager imm =
                        (InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            } catch (Throwable ignored) {
            }
        }, 220);
    }

    private static View buildPickerRow(final Activity activity, ViewGroup parent, final String title,
                                       boolean selected, final Dialog[] ref, final TextResult result) {
        TextView row = (TextView) LayoutInflater.from(activity)
                .inflate(R.layout.item_dropdown_line, parent, false);
        row.setText(BidiText.fix(title));
        row.setClickable(true);
        row.setFocusable(true);
        TypedValue tv = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }
        if (selected) {
            row.setTextColor(activity.getColor(R.color.primary_cyan_dark));
            row.setCompoundDrawablePadding(Math.round(8 * activity.getResources().getDisplayMetrics().density));
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_check, 0);
            row.setCompoundDrawableTintList(ColorStateList.valueOf(activity.getColor(R.color.primary_cyan)));
        }
        row.setOnClickListener(v -> {
            result.onText(title);
            if (ref[0] != null) ref[0].dismiss();
        });
        return row;
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
        dateView.setText("" + Fmt.date(when[0]));
        dateView.setOnClickListener(v -> pickDate(activity, when[0], millis -> {
            when[0] = millis;
            dateView.setText("" + Fmt.date(millis));
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
