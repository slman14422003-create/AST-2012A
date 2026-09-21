package com.ast2012a.clinicalmaster;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * تحويل بين org.json (اللي تستخدمه Patient/CaseItem محليًا) و Map/List
 * (اللي يقبلها Firestore مباشرة عبر document.set(Object)). Firestore
 * لا يقبل JSONObject/JSONArray كنوع مباشر، فنحوّلها لهياكل عامة أولًا.
 */
final class JsonUtils {

    private JsonUtils() {}

    static Map<String, Object> toMap(JSONObject o) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (o == null) return map;
        java.util.Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            map.put(key, wrap(o.opt(key)));
        }
        return map;
    }

    static List<Object> toList(JSONArray a) {
        List<Object> list = new ArrayList<>();
        if (a == null) return list;
        for (int i = 0; i < a.length(); i++) {
            list.add(wrap(a.opt(i)));
        }
        return list;
    }

    private static Object wrap(Object value) {
        if (value instanceof JSONObject) return toMap((JSONObject) value);
        if (value instanceof JSONArray) return toList((JSONArray) value);
        if (value == JSONObject.NULL) return null;
        return value;
    }

    /** يحوّل Map راجع من Firestore (getData()) رجوعًا لـ JSONObject متوافق مع fromJson() الحالية. */
    @SuppressWarnings("unchecked")
    static JSONObject fromMap(Map<String, Object> map) throws JSONException {
        JSONObject o = new JSONObject();
        if (map == null) return o;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            o.put(e.getKey(), unwrap(e.getValue()));
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Object unwrap(Object value) throws JSONException {
        if (value instanceof Map) return fromMap((Map<String, Object>) value);
        if (value instanceof List) {
            JSONArray arr = new JSONArray();
            for (Object item : (List<Object>) value) arr.put(unwrap(item));
            return arr;
        }
        if (value == null) return JSONObject.NULL;
        // Firestore يرجّع الأرقام الصحيحة كـ Long دايمًا؛ متوافق تمامًا مع
        // optLong/optInt الموجودة في Patient.fromJson.
        return value;
    }

    /** يحوّل نص تاريخ ISO-8601 (زي اللي يرجعه Cloudflare R2: "2026-09-21T10:00:00.000Z")
     *  لميلي ثانية Unix، أو 0 لو النص فاضي/غير صالح. minSdk 24 هنا فمفيش
     *  java.time.Instant.parse متاح بدون desugaring، فبنجرب صيغتين شائعتين
     *  بـ SimpleDateFormat بدل كده. */
    static long parseIsoOrZero(String iso) {
        if (iso == null || iso.trim().isEmpty()) return 0;
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
        };
        for (String pattern : patterns) {
            try {
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return sdf.parse(iso).getTime();
            } catch (Exception ignored) {
                // نجرّب الصيغة التالية
            }
        }
        return 0;
    }
}
