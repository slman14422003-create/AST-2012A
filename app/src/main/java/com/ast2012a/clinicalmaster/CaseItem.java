package com.ast2012a.clinicalmaster;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * نموذج بيانات "الحالة السريرية" - يمثل حالة من قاعدة البيانات المدمجة أو
 * حالة مخصصة أضافها المستخدم بنفسه.
 */
public class CaseItem {
    public String id;          // فارغ/null للحالات المدمجة، معرّف فريد للحالات المخصصة
    public boolean custom;
    public List<String> keywords = new ArrayList<>();
    public String title = "";
    public String mode = "";
    public String freq = "";
    public String channel = "";
    public String duration = "";
    public List<String> poles = new ArrayList<>();
    public String explanation = "";
    public String symptoms = "";
    public String sessionsPlan = "";
    public String tip = "";

    public static CaseItem fromJson(JSONObject o) {
        CaseItem c = new CaseItem();
        c.id = o.optString("id", null);
        c.custom = o.optBoolean("custom", false);
        c.keywords = jsonArrayToList(o.optJSONArray("keywords"));
        c.title = o.optString("title", "");
        c.mode = o.optString("mode", "");
        c.freq = o.optString("freq", "");
        c.channel = o.optString("channel", "");
        c.duration = o.optString("duration", "");
        c.poles = jsonArrayToList(o.optJSONArray("poles"));
        c.explanation = o.optString("explanation", "");
        c.symptoms = o.optString("symptoms", "");
        c.sessionsPlan = o.optString("sessionsPlan", "");
        c.tip = o.optString("tip", "");
        return c;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        if (id != null) o.put("id", id);
        o.put("custom", custom);
        o.put("keywords", new JSONArray(keywords));
        o.put("title", title);
        o.put("mode", mode);
        o.put("freq", freq);
        o.put("channel", channel);
        o.put("duration", duration);
        o.put("poles", new JSONArray(poles));
        o.put("explanation", explanation);
        if (symptoms != null && !symptoms.isEmpty()) o.put("symptoms", symptoms);
        if (sessionsPlan != null && !sessionsPlan.isEmpty()) o.put("sessionsPlan", sessionsPlan);
        if (tip != null && !tip.isEmpty()) o.put("tip", tip);
        return o;
    }

    private static List<String> jsonArrayToList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            list.add(arr.optString(i, ""));
        }
        return list;
    }
}
