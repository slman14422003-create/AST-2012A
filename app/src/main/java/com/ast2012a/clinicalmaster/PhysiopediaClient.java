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
 * عميل خفيف لموقع Physiopedia (physio-pedia.com) - أكبر مرجع متخصص
 * ومجاني في العلاج الطبيعي عالميًا، يعمل بنفس تقنية ميدياويكي المستخدمة
 * في ويكيبيديا. هذا هو المصدر المتخصص "الموثوق" الأول اللي يُستشار قبل
 * ويكيبيديا العامة، لأنه محتوى مراجَع من أخصائيي علاج طبيعي فعليًا -
 * بينما ويكيبيديا يبقى مصدر عام احتياطي لو مفيش نتيجة من Physiopedia.
 *
 * ملحوظة موثوقية: Physiopedia موقع ميدياويكي مستقل (مش تابع لمؤسسة
 * ويكيميديا)، فمفيش عنده نفس واجهة REST الخاصة اللي عند ويكيبيديا
 * (api/rest_v1/page/summary)؛ بنستخدم بدل منها واجهة action=query
 * القياسية المتاحة في أي ميدياويكي (extracts). لو الموقع مش متاح أو
 * غيّر بنيته، يرجع null بأمان والتطبيق يكمّل على ويكيبيديا تلقائيًا -
 * بدون أي كراش أو توقف.
 */
public class PhysiopediaClient {

    public static class Result {
        public String title;
        public String extract;
        public String sourceUrl;
    }

    private static final String BASE = "https://www.physio-pedia.com/api.php";
    private static final int TIMEOUT_MS = 8000;

    /** يُستدعى دائمًا من Thread خلفية (فيه اتصال شبكة مباشر). */
    public static Result search(String query) {
        if (query == null || query.trim().isEmpty()) return null;
        try {
            String q = query.trim();
            String encodedQuery = URLEncoder.encode(q, "UTF-8");
            String searchUrl = BASE + "?action=query&list=search&srsearch=" + encodedQuery
                    + "&format=json&utf8=1&srlimit=1";
            String searchJson = httpGet(searchUrl);
            if (searchJson == null) return null;

            JSONObject root = new JSONObject(searchJson);
            JSONArray hits = root.optJSONObject("query") != null
                    ? root.getJSONObject("query").optJSONArray("search")
                    : null;
            if (hits == null || hits.length() == 0) return null;
            String title = hits.getJSONObject(0).getString("title");

            String encodedTitle = URLEncoder.encode(title, "UTF-8");
            String extractUrl = BASE + "?action=query&prop=extracts&exintro=1&explaintext=1"
                    + "&titles=" + encodedTitle + "&format=json&utf8=1";
            String extractJson = httpGet(extractUrl);
            if (extractJson == null) return null;

            JSONObject extractRoot = new JSONObject(extractJson);
            JSONObject pages = extractRoot.optJSONObject("query") != null
                    ? extractRoot.getJSONObject("query").optJSONObject("pages")
                    : null;
            if (pages == null || pages.length() == 0) return null;

            String extract = null;
            java.util.Iterator<String> keys = pages.keys();
            while (keys.hasNext()) {
                JSONObject page = pages.optJSONObject(keys.next());
                if (page != null) extract = page.optString("extract", null);
                if (extract != null) break;
            }
            if (extract == null || extract.trim().isEmpty()) return null;

            Result r = new Result();
            r.title = title;
            r.extract = extract.length() > 900 ? extract.substring(0, 900) + "…" : extract;
            r.sourceUrl = "https://www.physio-pedia.com/" + title.replace(' ', '_');
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
