package com.ast2012a.clinicalmaster;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * نموذج بيانات المريض. هذه هي البداية الأساسية لملف المريض (الاسم،
 * التواصل، ملاحظة عامة) - حقول الجلسات والتاريخ العلاجي والحساب المالي
 * (الديون) مجهّزة في واجهة التفاصيل كأقسام "قريبًا" لحين تفعيلها لاحقًا،
 * بناءً على طلب المستخدم أن الميزة تُجهَّز الآن دون إكمالها بالكامل.
 */
public class Patient {
    public String id;
    public String name = "";
    public String phone = "";
    public String notes = "";
    public long createdAt;

    public static Patient fromJson(JSONObject o) {
        Patient p = new Patient();
        p.id = o.optString("id", "");
        p.name = o.optString("name", "");
        p.phone = o.optString("phone", "");
        p.notes = o.optString("notes", "");
        p.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        return p;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("phone", phone);
        o.put("notes", notes);
        o.put("createdAt", createdAt);
        return o;
    }
}
