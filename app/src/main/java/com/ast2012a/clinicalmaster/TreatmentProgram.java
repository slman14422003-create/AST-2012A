package com.ast2012a.clinicalmaster;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * برنامج علاج فيزيائي كامل - أوسع من "حالة" واحدة مرتبطة بجهاز
 * AST-2012A فقط؛ يوثّق خطة علاج طبيعي متكاملة (تشخيص، أهداف، مراحل،
 * تمارين، احتياطات) يمكن للمعالج استخدامها كمرجع أو تصدير للمريض.
 */
public class TreatmentProgram {
    public String id;
    public String title = "";
    public String diagnosis = "";
    public String goals = "";
    public String phases = "";
    public String exercises = "";
    public String precautions = "";
    public String sessionsPlan = "";
    public String notes = "";
    public long createdAt;

    public static TreatmentProgram fromJson(JSONObject o) {
        TreatmentProgram t = new TreatmentProgram();
        t.id = o.optString("id", "");
        t.title = o.optString("title", "");
        t.diagnosis = o.optString("diagnosis", "");
        t.goals = o.optString("goals", "");
        t.phases = o.optString("phases", "");
        t.exercises = o.optString("exercises", "");
        t.precautions = o.optString("precautions", "");
        t.sessionsPlan = o.optString("sessionsPlan", "");
        t.notes = o.optString("notes", "");
        t.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        return t;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("diagnosis", diagnosis);
        o.put("goals", goals);
        o.put("phases", phases);
        o.put("exercises", exercises);
        o.put("precautions", precautions);
        o.put("sessionsPlan", sessionsPlan);
        o.put("notes", notes);
        o.put("createdAt", createdAt);
        return o;
    }
}
