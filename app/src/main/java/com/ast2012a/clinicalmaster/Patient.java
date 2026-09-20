package com.ast2012a.clinicalmaster;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * ملف المريض: البيانات الأساسية + سجل الجلسات + المدفوعات + البروتوكولات
 * والبرامج المرتبطة. التخزين متوافق للخلف: الحقول الجديدة اختيارية عند القراءة
 * (ملفات patients.json القديمة تُقرأ بدون أي مشكلة).
 */
public class Patient {

    public String id;
    public String name = "";
    public String phone = "";
    public String notes = "";
    public long createdAt;

    /** 0 = غير محدد */
    public int age;
    /** "ذكر" أو "أنثى" أو "" */
    public String gender = "";
    /** الشكوى الرئيسية / التشخيص */
    public String diagnosis = "";
    /** سعر الجلسة الافتراضي (0 = غير محدد) */
    public double sessionFee;
    /** موعد الجلسة القادمة بالميلي ثانية (0 = غير محدد) */
    public long nextAppointment;

    public List<Session> sessions = new ArrayList<>();
    public List<Payment> payments = new ArrayList<>();
    public List<Assignment> assignments = new ArrayList<>();

    // =====================================================================
    // أنواع فرعية
    // =====================================================================

    /** جلسة علاج واحدة. */
    public static class Session {
        public String id;
        public long date;
        /** عنوان البروتوكول المستخدم (نص حر أو عنوان حالة موجودة) */
        public String protocol = "";
        public int durationMin;
        /** شدة الألم 0-10 قبل/بعد الجلسة، -1 = غير مسجّلة */
        public int painBefore = -1;
        public int painAfter = -1;
        /** المبلغ المستحق عن هذه الجلسة */
        public double fee;
        public String notes = "";

        public static Session create() {
            Session s = new Session();
            s.id = newId();
            s.date = System.currentTimeMillis();
            return s;
        }

        static Session fromJson(JSONObject o) {
            Session s = new Session();
            s.id = o.optString("id", newId());
            s.date = o.optLong("date", System.currentTimeMillis());
            s.protocol = o.optString("protocol", "");
            s.durationMin = o.optInt("duration", 0);
            s.painBefore = o.optInt("painBefore", -1);
            s.painAfter = o.optInt("painAfter", -1);
            s.fee = o.optDouble("fee", 0);
            s.notes = o.optString("notes", "");
            return s;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("date", date);
            o.put("protocol", protocol);
            o.put("duration", durationMin);
            o.put("painBefore", painBefore);
            o.put("painAfter", painAfter);
            o.put("fee", finite(fee));
            o.put("notes", notes);
            return o;
        }

        public boolean hasPain() {
            return painBefore >= 0 && painAfter >= 0;
        }
    }

    /** دفعة مالية مستلمة من المريض. */
    public static class Payment {
        public String id;
        public long date;
        public double amount;
        public String note = "";

        public static Payment create() {
            Payment p = new Payment();
            p.id = newId();
            p.date = System.currentTimeMillis();
            return p;
        }

        static Payment fromJson(JSONObject o) {
            Payment p = new Payment();
            p.id = o.optString("id", newId());
            p.date = o.optLong("date", System.currentTimeMillis());
            p.amount = o.optDouble("amount", 0);
            p.note = o.optString("note", "");
            return p;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("date", date);
            o.put("amount", finite(amount));
            o.put("note", note);
            return o;
        }
    }

    /** برنامج علاج (أو حالة) مرتبط بالمريض يدويًا. */
    public static class Assignment {
        public static final String TYPE_PROGRAM = "program";

        public String id;
        public String type = TYPE_PROGRAM;
        public String refId = "";
        public String title = "";
        public long date;

        public static Assignment create(String type, String refId, String title) {
            Assignment a = new Assignment();
            a.id = newId();
            a.type = type;
            a.refId = refId == null ? "" : refId;
            a.title = title == null ? "" : title;
            a.date = System.currentTimeMillis();
            return a;
        }

        static Assignment fromJson(JSONObject o) {
            Assignment a = new Assignment();
            a.id = o.optString("id", newId());
            a.type = o.optString("type", TYPE_PROGRAM);
            a.refId = o.optString("refId", "");
            a.title = o.optString("title", "");
            a.date = o.optLong("date", System.currentTimeMillis());
            return a;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("type", type);
            o.put("refId", refId);
            o.put("title", title);
            o.put("date", date);
            return o;
        }
    }

    // =====================================================================
    // JSON
    // =====================================================================

    static String newId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    private static double finite(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? 0 : v;
    }

    public static Patient fromJson(JSONObject o) {
        Patient p = new Patient();
        p.id = o.optString("id", "");
        p.name = o.optString("name", "");
        p.phone = o.optString("phone", "");
        p.notes = o.optString("notes", "");
        p.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        p.age = o.optInt("age", 0);
        p.gender = o.optString("gender", "");
        p.diagnosis = o.optString("diagnosis", "");
        p.sessionFee = o.optDouble("sessionFee", 0);
        p.nextAppointment = o.optLong("nextAppointment", 0);

        JSONArray sa = o.optJSONArray("sessions");
        if (sa != null) {
            for (int i = 0; i < sa.length(); i++) {
                JSONObject so = sa.optJSONObject(i);
                if (so != null) p.sessions.add(Session.fromJson(so));
            }
        }
        JSONArray pa = o.optJSONArray("payments");
        if (pa != null) {
            for (int i = 0; i < pa.length(); i++) {
                JSONObject po = pa.optJSONObject(i);
                if (po != null) p.payments.add(Payment.fromJson(po));
            }
        }
        JSONArray aa = o.optJSONArray("assignments");
        if (aa != null) {
            for (int i = 0; i < aa.length(); i++) {
                JSONObject ao = aa.optJSONObject(i);
                if (ao != null) p.assignments.add(Assignment.fromJson(ao));
            }
        }
        return p;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("phone", phone);
        o.put("notes", notes);
        o.put("createdAt", createdAt);
        o.put("age", age);
        o.put("gender", gender);
        o.put("diagnosis", diagnosis);
        o.put("sessionFee", finite(sessionFee));
        o.put("nextAppointment", nextAppointment);

        JSONArray sa = new JSONArray();
        for (Session s : sessions) sa.put(s.toJson());
        o.put("sessions", sa);

        JSONArray pa = new JSONArray();
        for (Payment p : payments) pa.put(p.toJson());
        o.put("payments", pa);

        JSONArray aa = new JSONArray();
        for (Assignment a : assignments) aa.put(a.toJson());
        o.put("assignments", aa);
        return o;
    }

    // =====================================================================
    // حسابات مشتقة
    // =====================================================================

    public String displayName() {
        return name == null || name.trim().isEmpty() ? "(بدون اسم)" : name.trim();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** إجمالي رسوم الجلسات المسجلة. */
    public double totalCharged() {
        double t = 0;
        for (Session s : sessions) t += s.fee;
        return round2(t);
    }

    public double totalPaid() {
        double t = 0;
        for (Payment p : payments) t += p.amount;
        return round2(t);
    }

    /** موجب = على المريض مستحقات (دين)، سالب = رصيد لصالح المريض. */
    public double balance() {
        return round2(totalCharged() - totalPaid());
    }

    public List<Session> sessionsNewestFirst() {
        List<Session> copy = new ArrayList<>(sessions);
        Collections.sort(copy, new Comparator<Session>() {
            @Override
            public int compare(Session a, Session b) {
                return Long.compare(b.date, a.date);
            }
        });
        return copy;
    }

    public List<Session> sessionsOldestFirst() {
        List<Session> copy = new ArrayList<>(sessions);
        Collections.sort(copy, new Comparator<Session>() {
            @Override
            public int compare(Session a, Session b) {
                return Long.compare(a.date, b.date);
            }
        });
        return copy;
    }

    public Session lastSession() {
        Session best = null;
        for (Session s : sessions) {
            if (best == null || s.date > best.date) best = s;
        }
        return best;
    }

    /** متوسط انخفاض الألم (قبل − بعد) عبر الجلسات المسجّل فيها الألم، أو -1000 لو لا توجد بيانات. */
    public double averagePainDrop() {
        double sum = 0;
        int n = 0;
        for (Session s : sessions) {
            if (s.hasPain()) {
                sum += (s.painBefore - s.painAfter);
                n++;
            }
        }
        return n == 0 ? -1000 : sum / n;
    }

    public int painSessionCount() {
        int n = 0;
        for (Session s : sessions) if (s.hasPain()) n++;
        return n;
    }

    /** آخر نشاط (إنشاء الملف أو آخر جلسة) للترتيب بالأحدث. */
    public long lastActivity() {
        long t = createdAt;
        for (Session s : sessions) if (s.date > t) t = s.date;
        return t;
    }
}
