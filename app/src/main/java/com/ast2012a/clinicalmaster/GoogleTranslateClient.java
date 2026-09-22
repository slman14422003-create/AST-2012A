package com.ast2012a.clinicalmaster;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * عميل خفيف لخدمة ترجمة جوجل المجانية (نفس الواجهة غير الرسمية التي تعتمد
 * عليها إضافات المتصفح وأدوات الترجمة المجانية - translate_a/single بمعامل
 * client=gtx) - بدون أي مفتاح API أو اشتراك، تُستخدم من PdfViewerActivity
 * لترجمة نص صفحة PDF (النص مُستخرَج فعليًا عبر PdfBox، مش OCR).
 *
 * لأن هذه الواجهة غير رسمية وممكن تحظر عنوان IP مؤقتًا (خطأ 429) لو تكرر
 * النداء عليها بسرعة كبيرة كأنه بوت، هذا العميل "يلتف" حولها بأربع طبقات:
 *
 *  1) طابور مُسلسَل بخيط واحد فقط: أبدًا لا يوجد طلبان يخرجان بالتوازي،
 *     حتى لو المستخدم ضغط "ترجمة" على صفحتين بسرعة.
 *  2) فاصل زمني أدنى إلزامي بين أي طلبين متتاليين (حتى لو نجحا) - يحاكي
 *     سلوك متصفح عادي بدل قصف الخدمة.
 *  3) إعادة محاولة بتأخير متصاعد (exponential backoff + jitter) عند فشل
 *     مؤقت (429/5xx/انقطاع شبكة)، مع تبديل تلقائي بين مضيفين مختلفين
 *     لنفس الواجهة (translate.googleapis.com و translate.google.com)
 *     حتى لا يعتمد كل شيء على مضيف واحد قد يكون محظورًا مؤقتًا.
 *  4) ذاكرة تخزين مؤقت صغيرة (نفس نص الصفحة + نفس اللغة الهدف) حتى لا
 *     تُعاد ترجمة نفس الصفحة مرتين لو رجع المستخدم إليها.
 */
final class GoogleTranslateClient {

    private GoogleTranslateClient() {
    }

    interface Callback {
        /** يُستدعى دائمًا على الخيط الرئيسي. عند الفشل: translated=null وerror != null. */
        void onDone(String translated, Exception error);
    }

    private static final String[] HOSTS = {
            "https://translate.googleapis.com",
            "https://translate.google.com",
    };

    private static final int TIMEOUT_MS = 12000;
    private static final int MAX_CHUNK_CHARS = 1800;
    private static final int MAX_ATTEMPTS = 3; // لكل جزء نص
    private static final long MIN_INTERVAL_MS = 400; // فاصل أدنى بين أي طلبين
    private static final long BASE_BACKOFF_MS = 900;
    private static final int CACHE_MAX_ENTRIES = 40;

    /** خيط واحد فقط لكل طلبات الترجمة - يضمن التسلسل (لا تزامن) تلقائيًا. */
    private static final ExecutorService queue = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Object throttleLock = new Object();
    private static volatile long lastRequestAtMs = 0L;

    private static final Object cacheLock = new Object();
    private static final LinkedHashMap<String, String> cache = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_MAX_ENTRIES;
        }
    };

    /** يترجم نصًا (يُقسَّم تلقائيًا لو طويل) إلى لغة الهدف. غير حاجب - النتيجة
     *  ترجع عبر callback على الخيط الرئيسي. آمن للاستدعاء من الخيط الرئيسي مباشرة. */
    static void translateAsync(String text, String targetLangCode, Callback callback) {
        if (text == null || text.trim().isEmpty()) {
            postResult(callback, "", null);
            return;
        }
        final String trimmed = text.trim();
        queue.execute(() -> {
            try {
                String result = translateBlocking(trimmed, targetLangCode);
                postResult(callback, result, null);
            } catch (Exception e) {
                postResult(callback, null, e);
            }
        });
    }

    private static void postResult(Callback callback, String result, Exception error) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onDone(result, error));
    }

    /** يشتغل فقط على خيط الطابور الداخلي (queue) - لا يُستدعى مباشرة من الخارج. */
    private static String translateBlocking(String text, String targetLangCode) throws IOException {
        String cacheKey = targetLangCode + "\u0001" + text;
        synchronized (cacheLock) {
            String hit = cache.get(cacheKey);
            if (hit != null) return hit;
        }

        List<String> chunks = splitIntoChunks(text, MAX_CHUNK_CHARS);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0 && needsSeparator(out)) out.append("\n\n");
            out.append(translateChunkWithRetry(chunks.get(i), targetLangCode));
        }
        String result = out.toString().trim();

        synchronized (cacheLock) {
            cache.put(cacheKey, result);
        }
        return result;
    }

    private static boolean needsSeparator(StringBuilder sb) {
        return sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n';
    }

    private static String translateChunkWithRetry(String chunk, String targetLangCode) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            throttleBeforeRequest();
            String host = HOSTS[attempt % HOSTS.length];
            try {
                return translateChunkOnce(host, chunk, targetLangCode);
            } catch (IOException e) {
                lastError = e;
                if (attempt < MAX_ATTEMPTS - 1) {
                    sleepQuietly(backoffDelay(attempt));
                }
            }
        }
        throw lastError != null ? lastError : new IOException("تعذّر الوصول لخدمة الترجمة.");
    }

    /** يضمن فاصلًا زمنيًا أدنى بين أي طلبين فعليين للخدمة - حتى لو من إعادة محاولة. */
    private static void throttleBeforeRequest() {
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestAtMs;
            if (elapsed < MIN_INTERVAL_MS) {
                sleepQuietly(MIN_INTERVAL_MS - elapsed);
            }
            lastRequestAtMs = System.currentTimeMillis();
        }
    }

    private static long backoffDelay(int attempt) {
        long base = BASE_BACKOFF_MS * (1L << attempt);
        long jitter = (long) (Math.random() * 250);
        return base + jitter;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String translateChunkOnce(String host, String chunk, String targetLangCode) throws IOException {
        HttpURLConnection conn = null;
        try {
            String encoded = URLEncoder.encode(chunk, "UTF-8");
            String url = host + "/translate_a/single?client=gtx&sl=auto&tl="
                    + targetLangCode + "&dt=t&q=" + encoded;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            // بدون هذا الترويسة بعض المضيفين يرفضون الطلب فورًا (403) لأنه يبدو كبوت صريح.
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36");

            int status = conn.getResponseCode();
            if (status == 429 || status >= 500) {
                throw new IOException("رفضت خدمة الترجمة الطلب مؤقتًا (كود " + status + ").");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("طلب ترجمة غير ناجح (كود " + status + ").");
            }

            String body = readStream(conn.getInputStream());
            return parseTranslatedText(body);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** شكل الرد: [[["مترجم1","أصلي1",...], ["مترجم2","أصلي2",...], ...], null, "en"] */
    private static String parseTranslatedText(String json) throws IOException {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray sentences = root.getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sentences.length(); i++) {
                JSONArray sentence = sentences.optJSONArray(i);
                if (sentence == null || sentence.isNull(0)) continue;
                sb.append(sentence.getString(0));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("رد غير متوقع من خدمة الترجمة.", e);
        }
    }

    /** يقسّم نصًا طويلًا لأجزاء تحت الحد الأقصى، محاولًا القطع عند فقرة/سطر
     *  كامل بدل تقطيع كلمة أو جملة في المنتصف، مع قطع صارم كحل أخير. */
    private static List<String> splitIntoChunks(String text, int maxLen) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= maxLen) {
            chunks.add(text);
            return chunks;
        }
        String[] paragraphs = text.split("\n+");
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            if (para.trim().isEmpty()) continue;
            if (current.length() > 0 && current.length() + para.length() + 1 > maxLen) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (para.length() > maxLen) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                for (int i = 0; i < para.length(); i += maxLen) {
                    chunks.add(para.substring(i, Math.min(para.length(), i + maxLen)));
                }
            } else {
                if (current.length() > 0) current.append('\n');
                current.append(para);
            }
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks.isEmpty() ? java.util.Collections.singletonList(text.substring(0, Math.min(text.length(), maxLen))) : chunks;
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}
