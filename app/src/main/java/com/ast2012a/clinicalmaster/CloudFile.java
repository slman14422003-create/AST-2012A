package com.ast2012a.clinicalmaster;

import org.json.JSONObject;

/**
 * ملف واحد مخزَّن على التخزين السحابي (Cloudflare Worker + R2) - اسم الملف
 * (يُستخدم كمفتاح فريد)، الحجم بالبايت، ووقت آخر رفع/تعديل بالميلي ثانية.
 */
public class CloudFile {
    public String name = "";
    public long size;
    public long uploadedAt;

    static CloudFile fromJson(JSONObject o) {
        CloudFile f = new CloudFile();
        f.name = o.optString("name", "");
        f.size = o.optLong("size", 0);
        // الووركر يرجّع "uploaded" كنص ISO-8601 (R2 httpMetadata) أو رقم مباشر؛
        // نتعامل مع الحالتين حتى لو تغيّر شكل استجابة الووركر مستقبلًا.
        long uploaded = o.optLong("uploadedMs", 0);
        if (uploaded == 0) {
            String iso = o.optString("uploaded", "");
            uploaded = JsonUtils.parseIsoOrZero(iso);
        }
        f.uploadedAt = uploaded;
        return f;
    }

    /** امتداد الملف بحروف صغيرة بدون النقطة، أو نص فارغ لو مفيش امتداد. */
    public String extension() {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
