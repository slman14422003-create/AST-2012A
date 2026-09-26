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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * عميل خفيف لقاعدة PubMed (المكتبة الوطنية الأمريكية للطب - NCBI)، عبر
 * واجهة E-utilities الرسمية والمجانية بالكامل (بدون أي مفتاح API). هذا
 * مصدر خارجي ثالث (v9) بجانب Physiopedia وويكيبيديا - راجع AiOrchestrator
 * (runGrounded) وAiPrompts (MULTI_SOURCE_SYNTHESIS_GUARDRAIL) لتفاصيل
 * إضافته وكيف يوازَن استخدامه مع المصدرين الآخرين.
 *
 * الفرق الجوهري عن Physiopedia/ويكيبيديا: PubMed مش موسوعة أو مرجع
 * تعليمي، ده فهرس لأبحاث ودراسات علمية محكّمة (Peer-reviewed) فعليًا -
 * فهو أعلى مستوى دليل علمي متاح للتطبيق عند سؤال عن فعالية علاج معين أو
 * مقارنة مبنية على أدلة (Evidence-based)، بعكس Physiopedia (أدق لتفاصيل
 * البروتوكول العملي/التطبيقي) وويكيبيديا (خلفية عامة فقط).
 *
 * يعمل على خطوتين، زي WikipediaClient بالضبط لكن بواجهة E-utilities:
 * 1) esearch.fcgi -> يرجع أقرب PMID (معرّف المقالة) لمصطلح البحث.
 * 2) efetch.fcgi (rettype=abstract&retmode=text) -> يرجع نص الاستشهاد
 *    والعنوان والملخص (Abstract) كنص خام، ثم نستخرج منه العنوان وأطول
 *    فقرة (الملخص العلمي نفسه) بفحص بسيط بدون أي مكتبة تحليل خارجية.
 *
 * ملحوظة موثوقية: PubMed غالبيته الساحقة بالإنجليزية، فبيُستدعى بنفس
 * مصطلح البحث الإنجليزي المُجهَّز أصلًا لـPhysiopedia (physioQuery في
 * AiOrchestrator)، مش نص السؤال العربي الأصلي. لو الموقع مش متاح، أو
 * مفيش نتيجة، أو فشل استخراج ملخص فعلي من النص الخام، يرجع null بأمان
 * تام والتطبيق يكمّل بالمصادر التانية المتاحة - بدون أي كراش أو توقف،
 * بنفس فلسفة الأمان المستخدمة في كل عملاء المصادر الخارجية هنا.
 */
public class PubMedClient {

    public static class Result {
        public String title;
        public String extract;
        public String sourceUrl;
    }

    private static final String ESEARCH_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi";
    private static final String EFETCH_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi";
    private static final String TOOL_TAG = "ast2012a_clinicalmaster"; // تعريف أدب استخدام NCBI (اختياري لكن مستحسن)
    private static final int TIMEOUT_MS = 8000;

    /** يُستدعى دائمًا من Thread خلفية (فيه اتصال شبكة مباشر). */
    public static Result search(String query) {
        if (query == null || query.trim().isEmpty()) return null;
        try {
            String pmid = findBestPmid(query.trim());
            if (pmid == null) return null;

            String fetchUrl = EFETCH_URL + "?db=pubmed&rettype=abstract&retmode=text&id="
                    + URLEncoder.encode(pmid, "UTF-8") + "&tool=" + TOOL_TAG;
            String rawText = httpGet(fetchUrl);
            if (rawText == null || rawText.trim().isEmpty()) return null;

            return parseAbstractText(rawText, pmid);
        } catch (Exception e) {
            return null;
        }
    }

    private static String findBestPmid(String query) throws IOException {
        String encodedQuery = URLEncoder.encode(query, "UTF-8");
        String searchUrl = ESEARCH_URL + "?db=pubmed&retmode=json&retmax=1&sort=relevance&term="
                + encodedQuery + "&tool=" + TOOL_TAG;
        String searchJson = httpGet(searchUrl);
        if (searchJson == null) return null;

        try {
            JSONObject root = new JSONObject(searchJson);
            JSONObject esearchresult = root.optJSONObject("esearchresult");
            JSONArray idList = esearchresult != null ? esearchresult.optJSONArray("idlist") : null;
            if (idList == null || idList.length() == 0) return null;
            String pmid = idList.optString(0, null);
            return (pmid == null || pmid.trim().isEmpty()) ? null : pmid.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * نص efetch (rettype=abstract) خام مقسّم لفقرات بفواصل أسطر فاضية،
     * بالترتيب المعتاد: (1) استشهاد المجلة/التاريخ، (2) عنوان المقالة،
     * (3) قائمة المؤلفين، (4) "Author information" (انتماءات - اختياري)،
     * (5) الملخص العلمي نفسه (غالبًا أطول فقرة فعليًا)، (6) DOI/PMID/حقوق
     * نشر ختامية. مفيش ضمان صارم إن كل مقالة عندها كل الفقرات دي بالترتيب
     * ده بالظبط (يفرق حسب نوع المقالة)، فالاستخراج هنا تقريبي وآمن: لو
     * فشل (مفيش فقرة تبان كملخص فعلي)، بيرجع null بدل تخمين محتوى غلط.
     */
    private static Result parseAbstractText(String rawText, String pmid) {
        String[] rawParagraphs = rawText.split("\\n\\s*\\n");
        List<String> paragraphs = new ArrayList<>();
        for (String p : rawParagraphs) {
            String t = p.trim().replaceAll("\\s+", " ");
            if (!t.isEmpty()) paragraphs.add(t);
        }
        if (paragraphs.size() < 2) return null;

        // العنوان: الفقرة الثانية عادة (بعد فقرة استشهاد المجلة الأولى).
        String title = paragraphs.get(1);
        if (title.length() > 300) title = title.substring(0, 300);

        String bestAbstract = null;
        for (int i = 2; i < paragraphs.size(); i++) {
            String p = paragraphs.get(i);
            String lower = p.toLowerCase(Locale.ROOT);
            if (lower.contains("author information")) continue;
            if (lower.startsWith("doi:") || lower.startsWith("pmid:") || lower.startsWith("pmcid:")
                    || lower.startsWith("©") || lower.startsWith("copyright")) continue;
            // فقرة قصيرة جدًا غالبًا سطر مؤلفين أو انتماء، مش ملخص فعلي.
            if (p.length() < 150) continue;
            if (bestAbstract == null || p.length() > bestAbstract.length()) bestAbstract = p;
        }
        if (bestAbstract == null || bestAbstract.trim().isEmpty()) return null;

        Result r = new Result();
        r.title = title;
        r.extract = bestAbstract.length() > 2500 ? bestAbstract.substring(0, 2500) + "…" : bestAbstract;
        r.sourceUrl = "https://pubmed.ncbi.nlm.nih.gov/" + pmid + "/";
        return r;
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            // NCBI بتطلب User-Agent واضح، ومستحسن (مش إلزامي) لأدب الاستخدام.
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
        while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        reader.close();
        return sb.toString();
    }
}
