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
