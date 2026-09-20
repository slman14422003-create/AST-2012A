package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * تخزين قائمة المرضى محليًا (ملف JSON داخل تخزين التطبيق الخاص)، بنفس
 * نمط تخزين الحالات المخصصة تمامًا في DataManager. كل عمليات الجلسات
 * والمدفوعات والربط تمر من هنا: تحميل ← تعديل مريض واحد ← حفظ.
 */
public class PatientManager {

    private static final String FILE = "patients.json";
    private static final String PREFS = "patients_prefs";
    private static final String KEY_CURRENCY = "currency";

    public static List<Patient> loadPatients(Context ctx) {
        List<Patient> list = new ArrayList<>();
        try {
            if (!ctx.getFileStreamPath(FILE).exists()) return list;
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    ctx.openFileInput(FILE), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) list.add(Patient.fromJson(arr.getJSONObject(i)));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public static void savePatients(Context ctx, List<Patient> patients) {
        writeToDisk(ctx, patients);
        // كل حفظ محلي (إضافة/تعديل مريض، جلسة، دفعة، ربط برنامج...) يمر من
        // هنا، فهي نقطة واحدة مناسبة لجدولة نسخة احتياطية سحابية مؤجَّلة لو
        // كانت مفعّلة من الإعدادات. لا تفعل شيئًا لو غير مفعّلة.
        FirebaseSyncManager.scheduleAutoBackup(ctx);
    }

    /** حفظ محلي بدون تشغيل النسخ الاحتياطي التلقائي - تستخدمه فقط عملية
     *  "الاستعادة من السحابة" حتى لا يُعاد رفع نفس البيانات فورًا بعد نزولها. */
    static void savePatientsSuppressingCloud(Context ctx, List<Patient> patients) {
        writeToDisk(ctx, patients);
    }

    private static void writeToDisk(Context ctx, List<Patient> patients) {
        try {
            JSONArray arr = new JSONArray();
            for (Patient p : patients) arr.put(p.toJson());
            FileOutputStream fos = ctx.openFileOutput(FILE, Context.MODE_PRIVATE);
            fos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
    }

    public static Patient getPatient(Context ctx, String id) {
        if (id == null) return null;
        for (Patient p : loadPatients(ctx)) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    public static Patient addPatient(Context ctx, Patient p) {
        List<Patient> list = loadPatients(ctx);
        p.id = UUID.randomUUID().toString().substring(0, 12);
        p.createdAt = System.currentTimeMillis();
        list.add(p);
        savePatients(ctx, list);
        return p;
    }

    public static void updatePatient(Context ctx, Patient updated) {
        List<Patient> list = loadPatients(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(updated.id)) {
                list.set(i, updated);
                break;
            }
        }
        savePatients(ctx, list);
    }

    public static void deletePatient(Context ctx, String id) {
        List<Patient> list = loadPatients(ctx);
        List<Patient> filtered = new ArrayList<>();
        for (Patient p : list) if (!id.equals(p.id)) filtered.add(p);
        savePatients(ctx, filtered);
    }

    // ---------------------------------------------------------------- الجلسات

    /** يضيف الجلسة أو يستبدل الموجودة بنفس المعرّف. */
    public static void saveSession(Context ctx, String patientId, Patient.Session session) {
        List<Patient> list = loadPatients(ctx);
        for (Patient p : list) {
            if (patientId.equals(p.id)) {
                boolean replaced = false;
                for (int i = 0; i < p.sessions.size(); i++) {
                    if (p.sessions.get(i).id.equals(session.id)) {
                        p.sessions.set(i, session);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) p.sessions.add(session);
                savePatients(ctx, list);
                return;
            }
        }
    }

    public static void deleteSession(Context ctx, String patientId, String sessionId) {
        List<Patient> list = loadPatients(ctx);
        for (Patient p : list) {
            if (patientId.equals(p.id)) {
                List<Patient.Session> kept = new ArrayList<>();
                for (Patient.Session s : p.sessions) if (!s.id.equals(sessionId)) kept.add(s);
                p.sessions = kept;
                savePatients(ctx, list);
                return;
            }
        }
    }

    // -------------------------------------------------------------- المدفوعات

    public static void addPayment(Context ctx, String patientId, Patient.Payment payment) {
        List<Patient> list = loadPatients(ctx);
        for (Patient p : list) {
            if (patientId.equals(p.id)) {
                p.payments.add(payment);
                savePatients(ctx, list);
                return;
            }
        }
    }

    public static void deletePayment(Context ctx, String patientId, String paymentId) {
        List<Patient> list = loadPatients(ctx);
        for (Patient p : list) {
            if (patientId.equals(p.id)) {
                List<Patient.Payment> kept = new ArrayList<>();
                for (Patient.Payment pay : p.payments) if (!pay.id.equals(paymentId)) kept.add(pay);
                p.payments = kept;
                savePatients(ctx, list);
                return;
            }
        }
    }

    // ------------------------------------------------------ البرامج المرتبطة

    /** يربط برنامجًا بالمريض؛ يتجاهل التكرار. يعيد false لو كان مربوطًا مسبقًا. */
    public static boolean addAssignment(Context ctx, String patientId, Patient.Assignment a) {
        List<Patient> list = loadPatients(ctx);
        for (Patient p : list) {
            if (patientId.equals(p.id)) {
                for (Patient.Assignment existing : p.assignments) {
                    if (existing.type.equals(a.type) && existing.refId.equals(a.refId)) return false;
                }
                p.assignments.add(a);
                savePatients(ctx, list);
                return true;
            }
        }
        return false;
    }

    public static void removeAssignment(Context ctx, String patientId, String assignmentId) {
        List<Patient> list = loadPatients(ctx);
        for (Patient p : list) {
            if (patientId.equals(p.id)) {
                List<Patient.Assignment> kept = new ArrayList<>();
                for (Patient.Assignment a : p.assignments) if (!a.id.equals(assignmentId)) kept.add(a);
                p.assignments = kept;
                savePatients(ctx, list);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ الموعد

    public static void setNextAppointment(Context ctx, String patientId, long millis) {
        List<Patient> list = loadPatients(ctx);
        for (Patient p : list) {
            if (patientId.equals(p.id)) {
                p.nextAppointment = millis;
                savePatients(ctx, list);
                return;
            }
        }
    }

    // ---------------------------------------------------------- تأريض المساعد الذكي
    // -----------------------------------------------------------------
    // Phizyo AI بقى له حق الوصول لحالات المرضى (تشخيص، جلسات، تطور الألم،
    // ملاحظات، البرامج المرتبطة) عشان يقدر فعلًا يساعد في متابعة حالة
    // مريض معيّن أو يدي نظرة عامة على كل المرضى - لكن بشرط صارم: الاسم
    // ورقم الهاتف ممنوع نهائيًا يوصلوا لأي نص بيتبعت لأي نموذج خارجي (حتى
    // لو الووركر نفسه موثوق) - الطريقتين تحت أصلًا لا يقرآن الحقلين دول
    // من كائن Patient نفسه إطلاقًا، فمفيش احتمال تسريب حتى بالخطأ.
    // -----------------------------------------------------------------

    /** حد أقصى لعدد الجلسات المُرفَقة في تأريض مريض واحد، تجنبًا لتضخيم
     *  حجم الطلب المرسل للنموذج مع مرضى عندهم سجل جلسات طويل جدًا. */
    private static final int MAX_SESSIONS_IN_CONTEXT = 12;

    /**
     * ملخّص مُعرَّف (مجهول الهوية) لمريض واحد - يُستخدم كخلفية معرفية للمساعد
     * الذكي عند سؤال مرتبط بمريض محدد. لا يحتوي إطلاقًا على name أو phone.
     */
    public static String buildRedactedPatientContext(Patient p) {
        if (p == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("ملف مريض من عيادة المستخدم (بيانات تعريفية محذوفة عمدًا لحماية الخصوصية - ")
          .append("لا تطلب ولا تفترض اسم المريض أو رقم هاتفه، وأشر إليه بـ \"المريض\" فقط):\n");
        if (p.age > 0) sb.append("• العمر: ").append(p.age).append(" سنة\n");
        if (!p.gender.trim().isEmpty()) sb.append("• الجنس: ").append(p.gender.trim()).append("\n");
        if (!p.diagnosis.trim().isEmpty()) sb.append("• الشكوى/التشخيص: ").append(p.diagnosis.trim()).append("\n");
        if (!p.notes.trim().isEmpty()) sb.append("• ملاحظات إكلينيكية: ").append(p.notes.trim()).append("\n");

        double drop = p.averagePainDrop();
        if (drop > -999) {
            sb.append("• متوسط انخفاض الألم عبر الجلسات المسجَّلة: ")
              .append(String.format(java.util.Locale.US, "%.1f", drop)).append(" نقطة\n");
        }

        List<Patient.Session> sessions = p.sessionsNewestFirst();
        if (!sessions.isEmpty()) {
            sb.append("• سجل الجلسات (الأحدث أولًا");
            if (sessions.size() > MAX_SESSIONS_IN_CONTEXT) {
                sb.append("، آخر ").append(MAX_SESSIONS_IN_CONTEXT).append(" من أصل ").append(sessions.size());
            }
            sb.append("):\n");
            int shown = Math.min(MAX_SESSIONS_IN_CONTEXT, sessions.size());
            for (int i = 0; i < shown; i++) {
                Patient.Session s = sessions.get(i);
                sb.append("  - ").append(Fmt.date(s.date));
                if (!s.protocol.trim().isEmpty()) sb.append(" — البروتوكول/العلاج: ").append(s.protocol.trim());
                if (s.hasPain()) sb.append(" — الألم قبل/بعد: ").append(s.painBefore).append("→").append(s.painAfter);
                if (s.durationMin > 0) sb.append(" — المدة: ").append(s.durationMin).append(" دقيقة");
                if (!s.notes.trim().isEmpty()) sb.append(" — ملاحظة: ").append(s.notes.trim());
                sb.append("\n");
            }
        }

        if (!p.assignments.isEmpty()) {
            sb.append("• برامج/بروتوكولات مرتبطة بالمريض: ");
            List<String> titles = new ArrayList<>();
            for (Patient.Assignment a : p.assignments) if (!a.title.trim().isEmpty()) titles.add(a.title.trim());
            sb.append(String.join("، ", titles)).append("\n");
        }

        return sb.toString().trim();
    }

    /** نفس التأريض السابق لكن لمريض واحد محدد بمعرّفه - تُستخدم لما المستخدم
     *  يسأل المساعد الذكي عن مريض معيّن من داخل شاشة ملفه مباشرة. */
    public static String buildRedactedPatientContext(Context ctx, String patientId) {
        return buildRedactedPatientContext(getPatient(ctx, patientId));
    }

    /** حد أقصى لعدد المرضى المُرفَقين في نظرة عامة، تجنبًا لتضخيم الطلب
     *  عند عيادات فيها عدد كبير من المرضى. */
    private static final int MAX_PATIENTS_IN_OVERVIEW = 20;

    /**
     * نظرة عامة مُعرَّفة (مجهولة الهوية) على كل المرضى - تُستخدم لما سؤال
     * المستخدم للمساعد عام عن "مرضاه" (مش عن مريض واحد بعينه)، زي "كام
     * مريض عندي بيتحسن؟" أو "إيه أكتر تشخيص متكرر عندي؟". كل مريض بيتشار
     * له برقم ترتيبي داخلي فقط ("مريض 1"، "مريض 2"...) - بدون اسمه إطلاقًا.
     */
    public static String buildAllPatientsOverviewContext(Context ctx) {
        List<Patient> all = loadPatients(ctx);
        if (all.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("نظرة عامة مجهولة الهوية على مرضى عيادة المستخدم (بيانات تعريفية محذوفة عمدًا - ")
          .append("لا تطلب ولا تفترض أي اسم أو رقم هاتف، أشر لكل مريض برقمه فقط زي \"مريض 1\"):\n");

        int shown = Math.min(MAX_PATIENTS_IN_OVERVIEW, all.size());
        long now = System.currentTimeMillis();
        for (int i = 0; i < shown; i++) {
            Patient p = all.get(i);
            sb.append("• مريض ").append(i + 1).append(": ");
            List<String> parts = new ArrayList<>();
            if (p.age > 0) parts.add(p.age + " سنة");
            if (!p.gender.trim().isEmpty()) parts.add(p.gender.trim());
            if (!p.diagnosis.trim().isEmpty()) parts.add("التشخيص: " + p.diagnosis.trim());
            parts.add(p.sessions.size() + " جلسة");
            double drop = p.averagePainDrop();
            if (drop > -999) parts.add("متوسط انخفاض الألم: " + String.format(java.util.Locale.US, "%.1f", drop));
            Patient.Session last = p.lastSession();
            if (last != null) parts.add("آخر جلسة: " + Fmt.relative(last.date, now));
            sb.append(String.join(" — ", parts)).append("\n");
        }
        if (all.size() > shown) {
            sb.append("(يوجد ").append(all.size() - shown).append(" مريض إضافي لم يُعرض هنا لتقليل الحجم.)\n");
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ العملة

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** رمز العملة المعروض بجانب المبالغ (فارغ افتراضيًا: تُعرض الأرقام فقط). */
    public static String getCurrency(Context ctx) {
        return prefs(ctx).getString(KEY_CURRENCY, "");
    }

    public static void setCurrency(Context ctx, String currency) {
        prefs(ctx).edit().putString(KEY_CURRENCY, currency == null ? "" : currency.trim()).apply();
    }
}
