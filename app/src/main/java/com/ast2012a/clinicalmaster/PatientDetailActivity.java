package com.ast2012a.clinicalmaster;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ملف المريض: بيانات أساسية + اتصال سريع + الموعد القادم + سجل الجلسات
 * (مع مخطط تطور الألم) + التاريخ العلاجي (البروتوكولات والبرامج) + الحساب
 * المالي (رسوم ودفعات ورصيد) + مشاركة ملخص.
 */
public class PatientDetailActivity extends AppCompatActivity {

    private static final int SESSIONS_PREVIEW = 5;

    private String patientId;
    private Patient patient;
    private String currency = "";
    private boolean showAllSessions = false;
    private LayoutInflater inflater;

    private LinearLayout sessionsContainer;
    private LinearLayout historyContainer;
    private LinearLayout paymentsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient_detail);
        inflater = LayoutInflater.from(this);

        patientId = getIntent().getStringExtra("patient_id");

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_share);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_share) {
                shareSummary();
                return true;
            }
            return false;
        });

        sessionsContainer = findViewById(R.id.sessions_container);
        historyContainer = findViewById(R.id.history_container);
        paymentsContainer = findViewById(R.id.payments_container);

        findViewById(R.id.btn_add_session).setOnClickListener(v ->
                PatientDialogs.showSession(this, patientId, null, null, this::reload));
        findViewById(R.id.btn_add_payment).setOnClickListener(v ->
                PatientDialogs.showPayment(this, patientId, this::reload));
        findViewById(R.id.btn_add_assignment).setOnClickListener(v -> pickProgramToAssign());
        findViewById(R.id.btn_set_appointment).setOnClickListener(v -> pickAppointment());
        findViewById(R.id.btn_clear_appointment).setOnClickListener(v -> {
            PatientManager.setNextAppointment(this, patientId, 0);
            reload();
        });
        findViewById(R.id.btn_add_to_calendar).setOnClickListener(v -> addToCalendar());
        findViewById(R.id.btn_call).setOnClickListener(v -> openPhone("tel:"));
        findViewById(R.id.btn_sms).setOnClickListener(v -> openPhone("smsto:"));
        findViewById(R.id.btn_whatsapp).setOnClickListener(v -> openWhatsApp());
        findViewById(R.id.btn_share_summary).setOnClickListener(v -> shareSummary());
        findViewById(R.id.btn_edit_patient).setOnClickListener(v -> {
            Intent i = new Intent(this, AddEditPatientActivity.class);
            i.putExtra("edit_patient_id", patientId);
            startActivity(i);
        });
        findViewById(R.id.btn_delete_patient).setOnClickListener(v -> confirmDeletePatient());
        findViewById(R.id.btn_more_sessions).setOnClickListener(v -> {
            showAllSessions = !showAllSessions;
            bindSessions();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        patient = PatientManager.getPatient(this, patientId);
        if (patient == null) {
            finish();
            return;
        }
        currency = PatientManager.getCurrency(this);
        bindHeader();
        bindStats();
        bindAppointment();
        bindSessions();
        bindHistory();
        bindFinance();
        bindNotes();
    }

    // =====================================================================
    // الرأس والملخص
    // =====================================================================

    private void bindHeader() {
        ((TextView) findViewById(R.id.detail_avatar)).setText(Fmt.initial(patient.name));
        ((TextView) findViewById(R.id.detail_name)).setText(BidiText.fix(patient.displayName()));

        TextView meta = findViewById(R.id.detail_meta);
        StringBuilder sb = new StringBuilder();
        if (patient.age > 0) sb.append(patient.age).append(" سنة");
        if (!patient.gender.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(patient.gender);
        }
        if (sb.length() > 0) {
            meta.setText(BidiText.fix(sb.toString()));
            meta.setVisibility(View.VISIBLE);
        } else {
            meta.setVisibility(View.GONE);
        }

        boolean hasPhone = !patient.phone.trim().isEmpty();
        TextView phone = findViewById(R.id.detail_phone);
        phone.setVisibility(hasPhone ? View.VISIBLE : View.GONE);
        if (hasPhone) phone.setText(patient.phone.trim());
        findViewById(R.id.contact_row).setVisibility(hasPhone ? View.VISIBLE : View.GONE);

        boolean hasDiagnosis = !patient.diagnosis.trim().isEmpty();
        findViewById(R.id.detail_diagnosis_box).setVisibility(hasDiagnosis ? View.VISIBLE : View.GONE);
        if (hasDiagnosis) {
            ((TextView) findViewById(R.id.detail_diagnosis)).setText(BidiText.fix(patient.diagnosis.trim()));
        }
    }

    private void bindStats() {
        ((TextView) findViewById(R.id.detail_stat_sessions)).setText(String.valueOf(patient.sessions.size()));

        double drop = patient.averagePainDrop();
        ((TextView) findViewById(R.id.detail_stat_pain)).setText(
                drop <= -999 ? "—" : String.format(Locale.US, "%.1f", drop));

        Patient.Session last = patient.lastSession();
        ((TextView) findViewById(R.id.detail_stat_last)).setText(
                last == null ? "—" : BidiText.fix(Fmt.relative(last.date, System.currentTimeMillis())));
    }

    private void bindNotes() {
        boolean has = !patient.notes.trim().isEmpty();
        findViewById(R.id.notes_card).setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) ((TextView) findViewById(R.id.detail_notes)).setText(BidiText.fix(patient.notes.trim()));
    }

    // =====================================================================
    // الموعد القادم
    // =====================================================================

    private void bindAppointment() {
        TextView text = findViewById(R.id.appointment_text);
        TextView setBtn = findViewById(R.id.btn_set_appointment);
        View calBtn = findViewById(R.id.btn_add_to_calendar);
        View clearBtn = findViewById(R.id.btn_clear_appointment);

        if (patient.nextAppointment > 0) {
            String value = Fmt.dateTime(patient.nextAppointment);
            if (patient.nextAppointment < System.currentTimeMillis()) value += "  (انقضى)";
            text.setText(BidiText.fix(value));
            setBtn.setText("تغيير الموعد");
            calBtn.setVisibility(View.VISIBLE);
            clearBtn.setVisibility(View.VISIBLE);
        } else {
            text.setText("غير محدد");
            setBtn.setText("تحديد موعد");
            calBtn.setVisibility(View.GONE);
            clearBtn.setVisibility(View.GONE);
        }
    }

    private void pickAppointment() {
        long initial = patient.nextAppointment > 0
                ? patient.nextAppointment
                : System.currentTimeMillis() + 24L * 60 * 60 * 1000;
        PatientDialogs.pickDateTime(this, initial, millis -> {
            PatientManager.setNextAppointment(this, patientId, millis);
            reload();
        });
    }

    private void addToCalendar() {
        if (patient.nextAppointment <= 0) return;
        Intent i = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, patient.nextAppointment)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, patient.nextAppointment + 45L * 60 * 1000)
                .putExtra(CalendarContract.Events.TITLE, "جلسة علاج — " + patient.displayName());
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لا يوجد تطبيق تقويم على هذا الجهاز.", Toast.LENGTH_SHORT).show();
        }
    }

    // =====================================================================
    // الجلسات
    // =====================================================================

    private void bindSessions() {
        List<Patient.Session> newestFirst = patient.sessionsNewestFirst();
        sessionsContainer.removeAllViews();

        boolean has = !newestFirst.isEmpty();
        findViewById(R.id.sessions_empty).setVisibility(has ? View.GONE : View.VISIBLE);

        int limit = showAllSessions ? newestFirst.size() : Math.min(SESSIONS_PREVIEW, newestFirst.size());
        for (int i = 0; i < limit; i++) sessionsContainer.addView(buildSessionRow(newestFirst.get(i)));

        TextView more = findViewById(R.id.btn_more_sessions);
        if (newestFirst.size() > SESSIONS_PREVIEW) {
            more.setVisibility(View.VISIBLE);
            more.setText(showAllSessions ? "عرض أقل" : "عرض كل الجلسات (" + newestFirst.size() + ")");
        } else {
            more.setVisibility(View.GONE);
        }

        // مخطط الألم: من الجلسات المسجّل فيها الألم قبل وبعد، بترتيب زمني
        List<Patient.Session> oldestFirst = patient.sessionsOldestFirst();
        List<Float> before = new ArrayList<>();
        List<Float> after = new ArrayList<>();
        for (Patient.Session s : oldestFirst) {
            if (s.hasPain()) {
                before.add((float) s.painBefore);
                after.add((float) s.painAfter);
            }
        }
        View painCard = findViewById(R.id.pain_card);
        if (before.size() >= 2) {
            float[] b = new float[before.size()];
            float[] a = new float[after.size()];
            for (int i = 0; i < b.length; i++) {
                b[i] = before.get(i);
                a[i] = after.get(i);
            }
            ((PainTrendView) findViewById(R.id.pain_trend)).setData(b, a);
            painCard.setVisibility(View.VISIBLE);
        } else {
            painCard.setVisibility(View.GONE);
        }
    }

    private View buildSessionRow(final Patient.Session s) {
        View row = inflater.inflate(R.layout.item_session_row, sessionsContainer, false);
        ((TextView) row.findViewById(R.id.session_date)).setText(BidiText.fix(Fmt.dateTime(s.date)));

        TextView fee = row.findViewById(R.id.session_fee);
        if (s.fee > 0) {
            fee.setText(Fmt.money(s.fee, currency));
            fee.setVisibility(View.VISIBLE);
        } else {
            fee.setVisibility(View.GONE);
        }

        TextView protocol = row.findViewById(R.id.session_protocol);
        if (s.protocol.trim().isEmpty()) {
            protocol.setVisibility(View.GONE);
        } else {
            protocol.setText(BidiText.fix(s.protocol.trim()));
        }

        LinearLayout chips = row.findViewById(R.id.session_chips);
        if (s.hasPain()) addSessionChip(chips, "الألم " + s.painBefore + " ← " + s.painAfter, true);
        if (s.durationMin > 0) addSessionChip(chips, Fmt.minutes(s.durationMin), false);
        if (chips.getChildCount() == 0) chips.setVisibility(View.GONE);

        TextView notes = row.findViewById(R.id.session_notes);
        if (s.notes.trim().isEmpty()) {
            notes.setVisibility(View.GONE);
        } else {
            notes.setText(BidiText.fix(s.notes.trim()));
            notes.setVisibility(View.VISIBLE);
        }

        row.setOnClickListener(v -> showSessionOptions(s));
        return row;
    }

    private void addSessionChip(LinearLayout parent, String text, boolean pain) {
        TextView chip = (TextView) inflater.inflate(R.layout.item_badge, parent, false);
        chip.setText(BidiText.fix(text));
        chip.setTextDirection(View.TEXT_DIRECTION_RTL);
        chip.setBackgroundResource(pain ? R.drawable.bg_badge_ems : R.drawable.bg_badge_tens);
        chip.setTextColor(getColor(pain ? R.color.m3_on_primary_container : R.color.m3_on_secondary_container));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) chip.getLayoutParams();
        lp.setMarginEnd(Ui.dp(this, 6));
        parent.addView(chip);
    }

    private void showSessionOptions(final Patient.Session s) {
        final CaseItem linked = findCaseByTitle(s.protocol);
        final List<String> labels = new ArrayList<>();
        final List<Integer> actions = new ArrayList<>(); // 0 تعديل، 1 فتح البروتوكول، 2 حذف
        labels.add("تعديل الجلسة");
        actions.add(0);
        if (linked != null) {
            labels.add("فتح البروتوكول");
            actions.add(1);
        }
        labels.add("حذف الجلسة");
        actions.add(2);

        new MaterialAlertDialogBuilder(this)
                .setTitle(BidiText.fix(Fmt.dateTime(s.date)))
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    int action = actions.get(which);
                    if (action == 0) {
                        PatientDialogs.showSession(this, patientId, s, null, this::reload);
                    } else if (action == 1) {
                        openCase(linked);
                    } else {
                        confirmDeleteSession(s);
                    }
                })
                .show();
    }

    private void confirmDeleteSession(final Patient.Session s) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("حذف الجلسة")
                .setMessage("سيتم حذف هذه الجلسة ورسمها من الحساب. متابعة؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    PatientManager.deleteSession(this, patientId, s.id);
                    reload();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // =====================================================================
    // التاريخ العلاجي
    // =====================================================================

    /** ملخص استخدام بروتوكول داخل جلسات المريض. */
    private static class ProtocolUse {
        int count;
        long lastDate;
    }

    private void bindHistory() {
        historyContainer.removeAllViews();
        long now = System.currentTimeMillis();
        int rows = 0;

        // البرامج المربوطة يدويًا
        for (final Patient.Assignment a : patient.assignments) {
            final TreatmentProgram program = findProgram(a.refId);
            View row = inflater.inflate(R.layout.item_history_row, historyContainer, false);
            ((android.widget.ImageView) row.findViewById(R.id.history_icon)).setImageResource(R.drawable.ic_clipboard);
            String title = program != null ? program.title : a.title;
            if (title.trim().isEmpty()) title = "(برنامج بدون عنوان)";
            ((TextView) row.findViewById(R.id.history_title)).setText(BidiText.fix(title));
            String sub = program != null
                    ? "برنامج علاج · رُبط " + Fmt.relative(a.date, now)
                    : "برنامج محذوف · رُبط " + Fmt.relative(a.date, now);
            ((TextView) row.findViewById(R.id.history_sub)).setText(BidiText.fix(sub));
            row.setOnClickListener(v -> {
                if (program == null) {
                    Toast.makeText(this, "هذا البرنامج لم يعد موجودًا.", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent i = new Intent(this, TreatmentProgramDetailActivity.class);
                i.putExtra("program_id", program.id);
                startActivity(i);
            });
            row.setOnLongClickListener(v -> {
                confirmRemoveAssignment(a);
                return true;
            });
            historyContainer.addView(row);
            rows++;
        }

        // البروتوكولات المستخدمة في الجلسات (مجمّعة بالعنوان)
        Map<String, ProtocolUse> uses = new LinkedHashMap<>();
        for (Patient.Session s : patient.sessionsNewestFirst()) {
            String key = s.protocol.trim();
            if (key.isEmpty()) continue;
            ProtocolUse u = uses.get(key);
            if (u == null) {
                u = new ProtocolUse();
                u.lastDate = s.date;
                uses.put(key, u);
            }
            u.count++;
        }
        for (Map.Entry<String, ProtocolUse> e : uses.entrySet()) {
            final String protocolTitle = e.getKey();
            ProtocolUse u = e.getValue();
            View row = inflater.inflate(R.layout.item_history_row, historyContainer, false);
            ((android.widget.ImageView) row.findViewById(R.id.history_icon)).setImageResource(R.drawable.ic_zap);
            ((TextView) row.findViewById(R.id.history_title)).setText(BidiText.fix(protocolTitle));
            ((TextView) row.findViewById(R.id.history_sub)).setText(BidiText.fix(
                    Fmt.sessionsLabel(u.count) + " · آخر استخدام " + Fmt.relative(u.lastDate, now)));
            row.setOnClickListener(v -> {
                CaseItem c = findCaseByTitle(protocolTitle);
                if (c == null) {
                    Toast.makeText(this, "هذا البروتوكول غير موجود في قاعدة البيانات.", Toast.LENGTH_SHORT).show();
                } else {
                    openCase(c);
                }
            });
            historyContainer.addView(row);
            rows++;
        }

        findViewById(R.id.history_empty).setVisibility(rows == 0 ? View.VISIBLE : View.GONE);
    }

    private void pickProgramToAssign() {
        final List<TreatmentProgram> programs = TreatmentProgramManager.loadPrograms(this);
        if (programs.isEmpty()) {
            Toast.makeText(this, "لا توجد برامج علاج محفوظة. أنشئ برنامجًا من شاشة برامج العلاج.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[programs.size()];
        for (int i = 0; i < names.length; i++) {
            String t = programs.get(i).title;
            names[i] = t.trim().isEmpty() ? "(برنامج بدون عنوان)" : t;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("ربط برنامج علاج")
                .setItems(names, (dialog, which) -> {
                    TreatmentProgram p = programs.get(which);
                    boolean added = PatientManager.addAssignment(this, patientId,
                            Patient.Assignment.create(Patient.Assignment.TYPE_PROGRAM, p.id, p.title));
                    Toast.makeText(this, added ? "تم ربط البرنامج بالمريض." : "هذا البرنامج مربوط بالفعل.",
                            Toast.LENGTH_SHORT).show();
                    reload();
                })
                .show();
    }

    private void confirmRemoveAssignment(final Patient.Assignment a) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("إزالة الربط")
                .setMessage("إزالة هذا البرنامج من ملف المريض؟ (لن يُحذف البرنامج نفسه)")
                .setPositiveButton("إزالة", (dialog, which) -> {
                    PatientManager.removeAssignment(this, patientId, a.id);
                    reload();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private TreatmentProgram findProgram(String id) {
        if (id == null || id.isEmpty()) return null;
        for (TreatmentProgram t : TreatmentProgramManager.loadPrograms(this)) {
            if (id.equals(t.id)) return t;
        }
        return null;
    }

    private CaseItem findCaseByTitle(String title) {
        if (title == null || title.trim().isEmpty()) return null;
        String wanted = title.trim();
        for (CaseItem c : DataManager.allCases(this)) {
            if (wanted.equals(c.title.trim())) return c;
        }
        return null;
    }

    private void openCase(CaseItem c) {
        Intent i = new Intent(this, CaseDetailActivity.class);
        i.putExtra("case_id", c.id);
        i.putExtra("case_title", c.title);
        i.putExtra("is_custom", c.custom);
        startActivity(i);
    }

    // =====================================================================
    // الحساب المالي
    // =====================================================================

    private void bindFinance() {
        double charged = patient.totalCharged();
        double paid = patient.totalPaid();
        double balance = patient.balance();

        ((TextView) findViewById(R.id.finance_charged)).setText(Fmt.money(charged, currency));
        ((TextView) findViewById(R.id.finance_paid)).setText(Fmt.money(paid, currency));

        TextView balanceView = findViewById(R.id.finance_balance);
        TextView balanceLabel = findViewById(R.id.finance_balance_label);
        if (balance < -0.004) {
            balanceLabel.setText("رصيد للمريض");
            balanceView.setText(Fmt.money(-balance, currency));
            balanceView.setTextColor(getColor(R.color.accent_green));
        } else if (balance > 0.004) {
            balanceLabel.setText("المتبقي");
            balanceView.setText(Fmt.money(balance, currency));
            balanceView.setTextColor(getColor(R.color.accent_red));
        } else {
            balanceLabel.setText("المتبقي");
            balanceView.setText(Fmt.money(0, currency));
            balanceView.setTextColor(getColor(R.color.accent_green));
        }

        paymentsContainer.removeAllViews();
        List<Patient.Payment> sorted = new ArrayList<>(patient.payments);
        java.util.Collections.sort(sorted, new java.util.Comparator<Patient.Payment>() {
            @Override
            public int compare(Patient.Payment a, Patient.Payment b) {
                return Long.compare(b.date, a.date);
            }
        });
        for (final Patient.Payment p : sorted) {
            View row = inflater.inflate(R.layout.item_payment_row, paymentsContainer, false);
            ((TextView) row.findViewById(R.id.payment_date)).setText(BidiText.fix(Fmt.date(p.date)));
            ((TextView) row.findViewById(R.id.payment_amount)).setText(Fmt.money(p.amount, currency));
            TextView note = row.findViewById(R.id.payment_note);
            if (p.note.trim().isEmpty()) {
                note.setVisibility(View.GONE);
            } else {
                note.setText(BidiText.fix(p.note.trim()));
                note.setVisibility(View.VISIBLE);
            }
            row.setOnClickListener(v -> confirmDeletePayment(p));
            paymentsContainer.addView(row);
        }
        findViewById(R.id.payments_empty).setVisibility(sorted.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void confirmDeletePayment(final Patient.Payment p) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("حذف الدفعة")
                .setMessage("حذف دفعة بمبلغ " + Fmt.money(p.amount, currency) + "؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    PatientManager.deletePayment(this, patientId, p.id);
                    reload();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // =====================================================================
    // اتصال ومشاركة
    // =====================================================================

    private void openPhone(String scheme) {
        try {
            startActivity(new Intent(scheme.startsWith("tel") ? Intent.ACTION_DIAL : Intent.ACTION_SENDTO,
                    Uri.parse(scheme + Uri.encode(patient.phone.trim()))));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لا يوجد تطبيق مناسب على هذا الجهاز.", Toast.LENGTH_SHORT).show();
        }
    }

    /** واتساب يحتاج رقمًا دوليًا؛ نقبل +962... أو 00962... فقط. */
    private void openWhatsApp() {
        String raw = patient.phone.trim().replace(" ", "").replace("-", "");
        String digits;
        if (raw.startsWith("+")) {
            digits = raw.substring(1);
        } else if (raw.startsWith("00")) {
            digits = raw.substring(2);
        } else {
            Toast.makeText(this, "اكتب الرقم بصيغة دولية (مثل +9627XXXXXXXX) لفتح واتساب.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            char ch = digits.charAt(i);
            if (ch >= '0' && ch <= '9') clean.append(ch);
        }
        if (clean.length() < 7) {
            Toast.makeText(this, "رقم الهاتف غير صالح لواتساب.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + clean)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "تعذر فتح واتساب.", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareSummary() {
        if (patient == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("ملف المريض: ").append(patient.displayName()).append('\n');
        if (patient.age > 0) sb.append("العمر: ").append(patient.age).append(" سنة\n");
        if (!patient.gender.isEmpty()) sb.append("الجنس: ").append(patient.gender).append('\n');
        if (!patient.diagnosis.trim().isEmpty()) sb.append("الشكوى/التشخيص: ").append(patient.diagnosis.trim()).append('\n');
        sb.append("عدد الجلسات: ").append(patient.sessions.size()).append('\n');
        double drop = patient.averagePainDrop();
        if (drop > -999) sb.append("متوسط انخفاض الألم: ").append(String.format(Locale.US, "%.1f", drop)).append('\n');

        List<Patient.Session> list = patient.sessionsNewestFirst();
        if (!list.isEmpty()) {
            sb.append("\nآخر الجلسات:\n");
            int n = Math.min(10, list.size());
            for (int i = 0; i < n; i++) {
                Patient.Session s = list.get(i);
                sb.append("• ").append(Fmt.date(s.date));
                if (!s.protocol.trim().isEmpty()) sb.append(" — ").append(s.protocol.trim());
                if (s.hasPain()) sb.append(" — الألم ").append(s.painBefore).append("←").append(s.painAfter);
                if (s.durationMin > 0) sb.append(" — ").append(Fmt.minutes(s.durationMin));
                sb.append('\n');
            }
        }

        if (patient.totalCharged() > 0 || patient.totalPaid() > 0) {
            sb.append("\nالحساب:\n");
            sb.append("• إجمالي الجلسات: ").append(Fmt.money(patient.totalCharged(), currency)).append('\n');
            sb.append("• المدفوع: ").append(Fmt.money(patient.totalPaid(), currency)).append('\n');
            double balance = patient.balance();
            if (balance > 0.004) sb.append("• المتبقي: ").append(Fmt.money(balance, currency)).append('\n');
            else if (balance < -0.004) sb.append("• رصيد للمريض: ").append(Fmt.money(-balance, currency)).append('\n');
        }
        if (patient.nextAppointment > System.currentTimeMillis()) {
            sb.append("\nالموعد القادم: ").append(Fmt.dateTime(patient.nextAppointment)).append('\n');
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "ملف المريض: " + patient.displayName());
        send.putExtra(Intent.EXTRA_TEXT, sb.toString().trim());
        startActivity(Intent.createChooser(send, "مشاركة ملخص المريض"));
    }

    private void confirmDeletePatient() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("حذف المريض")
                .setMessage("سيتم حذف ملف \"" + patient.displayName()
                        + "\" بكل جلساته ودفعاته نهائيًا. هل أنت متأكد؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    PatientManager.deletePatient(this, patientId);
                    Toast.makeText(this, "تم حذف المريض.", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }
}
