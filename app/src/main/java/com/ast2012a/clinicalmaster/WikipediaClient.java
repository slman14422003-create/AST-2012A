package com.ast2012a.clinicalmaster;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * عميل خفيف لموسوعة ويكيبيديا (عربي أولاً، إنجليزي كبديل تلقائي لو مفيش
 * نتيجة عربية) - مصدر معلومات عام مجاني بالكامل وبدون أي مفتاح API أو حد
 * استخدام، يُستخدم لتغذية المساعد الذكي بخلفية معرفية موثوقة من "موسوعة
 * مشهورة" حسب طلب المستخدم، بجانب قاعدة بيانات الجهاز المحلية.
 *
 * يعمل على خطوتين، كلاهما نداءات REST بسيطة بصيغة JSON بدون أي مكتبة
 * خارجية إضافية:
 * 1) action=query&list=search  -> يرجع أقرب عنوان مقالة لكلمات البحث.
 * 2) /api/rest_v1/page/summary/{title} -> يرجع ملخص نصي نظيف + رابط المصدر.
 */
public class WikipediaClient {

    public static class Result {
        public String title;
        public String extract;
        public String sourceUrl;
        public String lang; // "ar" أو "en"
    }

    private static final int TIMEOUT_MS = 8000;

    /** يجرّب النسخة العربية أولًا، ولو مفيش نتيجة يرجع للإنجليزية تلقائيًا.
     *  يُستدعى دائمًا من Thread خلفية (فيه اتصال شبكة مباشر). */
    public static Result search(String query) {
        if (query == null || query.trim().isEmpty()) return null;
        Result arResult = searchInLanguage(query.trim(), "ar");
        if (arResult != null) return arResult;
        return searchInLanguage(query.trim(), "en");
    }

    private static Result searchInLanguage(String query, String lang) {
        try {
            String encodedQuery = URLEncoder.encode(query, "UTF-8");
            String searchUrl = "https://" + lang + ".wikipedia.org/w/api.php?action=query&list=search&srsearch="
                    + encodedQuery + "&format=json&utf8=1&srlimit=1";
            String searchJson = httpGet(searchUrl);
            if (searchJson == null) return null;

            JSONObject root = new JSONObject(searchJson);
            JSONArray hits = root.optJSONObject("query") != null
                    ? root.getJSONObject("query").optJSONArray("search")
                    : null;
            if (hits == null || hits.length() == 0) return null;
            String title = hits.getJSONObject(0).getString("title");

            String encodedTitle = URLEncoder.encode(title, "UTF-8").replace("+", "%20");
            String summaryUrl = "https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/" + encodedTitle;
            String summaryJson = httpGet(summaryUrl);
            if (summaryJson == null) return null;

            JSONObject summary = new JSONObject(summaryJson);
            String extract = summary.optString("extract", "");
            // نتجاهل صفحات التوضيح (disambiguation) والملخصات الفارغة - مفيش قيمة سريرية فيها
            String pageType = summary.optString("type", "");
            if (extract.trim().isEmpty() || "disambiguation".equals(pageType)) return null;

            Result r = new Result();
            r.title = summary.optString("title", title);
            r.extract = extract.length() > 900 ? extract.substring(0, 900) + "…" : extract;
            r.lang = lang;
            try {
                r.sourceUrl = summary.getJSONObject("content_urls").getJSONObject("desktop").getString("page");
            } catch (Exception ignored) {
                r.sourceUrl = "https://" + lang + ".wikipedia.org/wiki/" + encodedTitle;
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            // ويكيبيديا بتطلب User-Agent صريح لطلبات الـ API، وإلا ممكن ترفض الطلب
            conn.setRequestProperty("User-Agent", "AST2012A-ClinicalMaster-Android/1.0 (contact: app-only)");

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) return null;

            InputStream is = conn.getInputStream();
            return readStream(is);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }
}
