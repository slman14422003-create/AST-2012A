package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "مكتبة معرفة" Phizyo AI من ملفات التخزين السحابي: بدل ما تفضل ملفات
 * PDF/المستندات اللي رفعها المستخدم على CloudStorageActivity (بروتوكولات،
 * أبحاث، أدلة علاجية...) بعيدة تمامًا عن المساعد الذكي، هذا الكلاس بيبني
 * كاش محلي من نصها المستخرَج (عبر DocumentTextExtractor) ويوفّر خلفية
 * معرفية من أكثر المقتطفات صلة بسؤال المستخدم الحالي - يُستخدم من
 * AiOrchestrator.runGrounded كمصدر تأريض إضافي بجانب قاعدة بيانات الجهاز
 * وPhysiopedia وملف المريض.
 *
 * هذا هو "التعلّم الذاتي" اللي طلبه المستخدم فعليًا: المساعد مش بيتدرّب
 * من جديد (غير ممكن من داخل تطبيق أندرويد بيستدعي API خارجي)، لكنه
 * بيقرأ فعليًا أي مستند يضيفه المستخدم للمكتبة السحابية ويستخدمه كمرجع في
 * ردوده التالية تلقائيًا - بدون أي تدخّل يدوي زيادة عن الرفع نفسه.
 *
 * تصميم مقصود للأداء: المزامنة (تنزيل + استخراج نص أي ملف جديد/معدَّل)
 * بتتعمل بحد أقصى مرة كل MIN_SYNC_INTERVAL_MS، مش مع كل رسالة - وبتتجاهل
 * أي ملف لسه بنفس الحجم ووقت الرفع المحفوظين بالفهرس المحلي. بناء خلفية
 * السياق نفسه (buildCloudDocumentsContext) بيشتغل من الكاش المحلي بس
 * (بدون شبكة) فبيفضل سريع مع كل سؤال.
 */
final class CloudKnowledgeManager {

    private CloudKnowledgeManager() {}

    private static final String PREFS = "settings_prefs";
    private static final String KEY_INDEX = "cloud_knowledge_index";
    private static final String KEY_LAST_SYNC = "cloud_knowledge_last_sync";

    /** أقل فاصل زمني بين محاولتين لمزامنة فهرس الملفات مع السحابة. */
    private static final long MIN_SYNC_INTERVAL_MS = 5 * 60 * 1000L; // 5 دقائق

    private static final Set<String> SUPPORTED_EXTENSIONS =
            new LinkedHashSet<>(Arrays.asList("pdf", "txt", "md"));

    private static final int MAX_CONTEXT_CHARS = 3000;
    private static final int MAX_DOCS_IN_CONTEXT = 3;
    private static final int MAX_CHARS_PER_DOC_IN_CONTEXT = 1200;

    private static File cacheDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "ai_knowledge_cache");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * يزامن فهرس مستندات السحابة مع الكاش المحلي لو مضى وقت كافٍ من آخر
     * مزامنة (أو أول استخدام). لا يرمي أي استثناء أبدًا - أي فشل شبكة أو
     * غيره يُتجاهل بصمت والكاش الحالي (لو موجود) يفضل صالح للاستخدام،
     * عشان فشل مؤقت في الاتصال بالسحابة ما يوقفش رد المساعد الذكي على
     * الإطلاق. لازم تُستدعى من Thread خلفية دايمًا (بتعمل شبكة).
     */
    static void syncIfNeeded(Context ctx) {
        if (!CloudStorageClient.isConfigured(ctx)) return;
        long lastSync = prefs(ctx).getLong(KEY_LAST_SYNC, 0);
        if (System.currentTimeMillis() - lastSync < MIN_SYNC_INTERVAL_MS) return;
        try {
            sync(ctx);
        } catch (Throwable ignored) {
            // نتجاهل: هنكمل بأحدث كاش محلي متاح (لو فيه) بدل ما نعطّل الرد.
        }
    }

    private static void sync(Context ctx) throws IOException, JSONException {
        List<CloudFile> remote = CloudStorageClient.list(ctx);
        JSONObject oldIndex = loadIndex(ctx);
        JSONObject newIndex = new JSONObject();
        File dir = cacheDir(ctx);

        for (CloudFile f : remote) {
            String ext = f.extension();
            if (!SUPPORTED_EXTENSIONS.contains(ext)) continue;

            JSONObject existing = oldIndex.optJSONObject(f.name);
            String cachedFile = existing != null ? existing.optString("cache", "") : "";
            boolean unchanged = existing != null
                    && existing.optLong("size") == f.size
                    && existing.optLong("uploadedAt") == f.uploadedAt
                    && !cachedFile.isEmpty()
                    && new File(dir, cachedFile).exists();

            if (unchanged) {
                newIndex.put(f.name, existing);
                continue;
            }

            String text = downloadAndExtract(ctx, f, ext);
            if (text == null) continue; // فشل تنزيل/استخراج ملف واحد - نتخطاه ونكمل الباقي

            String cacheFileName = "doc_" + Math.abs(f.name.hashCode()) + ".txt";
            File out = new File(dir, cacheFileName);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }

            JSONObject entry = new JSONObject();
            entry.put("size", f.size);
            entry.put("uploadedAt", f.uploadedAt);
            entry.put("cache", cacheFileName);
            newIndex.put(f.name, entry);
        }

        cleanupOrphanCacheFiles(dir, newIndex);
        saveIndex(ctx, newIndex);
        prefs(ctx).edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply();
    }

    /** يحذف نسخ الكاش المحلي الخاصة بملفات اتشالت من التخزين السحابي (أو
     *  بقت غير مدعومة)، عشان الكاش ما يكبرش من غير داعٍ مع الوقت. */
    private static void cleanupOrphanCacheFiles(File dir, JSONObject newIndex) {
        Set<String> kept = new LinkedHashSet<>();
        Iterator<String> keys = newIndex.keys();
        while (keys.hasNext()) {
            JSONObject entry = newIndex.optJSONObject(keys.next());
            if (entry != null) kept.add(entry.optString("cache", ""));
        }
        File[] existingFiles = dir.listFiles();
        if (existingFiles == null) return;
        for (File ef : existingFiles) {
            if (!kept.contains(ef.getName())) //noinspection ResultOfMethodCallIgnored
                ef.delete();
        }
    }

    private static String downloadAndExtract(Context ctx, CloudFile f, String ext) {
        File tmp = new File(ctx.getCacheDir(), "ai_dl_" + Math.abs(f.name.hashCode()) + "." + ext);
        try {
            CloudStorageClient.downloadToFile(ctx, f.name, tmp);
            return DocumentTextExtractor.extractText(ctx, tmp, ext);
        } catch (Throwable t) {
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static JSONObject loadIndex(Context ctx) {
        String raw = prefs(ctx).getString(KEY_INDEX, "");
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void saveIndex(Context ctx, JSONObject index) {
        prefs(ctx).edit().putString(KEY_INDEX, index.toString()).apply();
    }

    /**
     * يبني خلفية معرفية من أكثر مستندات الكاش المحلي صلة بسؤال المستخدم
     * الحالي، عن طريق تطابق كلمات دلالية بسيط (بدون أي نداء شبكة أو نموذج
     * إضافي - محلي بالكامل فبيفضل سريع). يرجع null لو مفيش أي مستند مخزَّن
     * أصلًا أو مفيش تطابق ذو دلالة مع السؤال.
     */
    static String buildCloudDocumentsContext(Context ctx, String userQuery) {
        JSONObject index = loadIndex(ctx);
        if (index.length() == 0) return null;

        List<String> queryWords = significantWords(userQuery);
        if (queryWords.isEmpty()) return null;

        File dir = cacheDir(ctx);
        List<ScoredDoc> scored = new ArrayList<>();
        Iterator<String> keys = index.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            JSONObject entry = index.optJSONObject(name);
            if (entry == null) continue;
            String cacheFileName = entry.optString("cache", "");
            if (cacheFileName.isEmpty()) continue;
            File f = new File(dir, cacheFileName);
            if (!f.exists()) continue;
            String text = readFile(f);
            if (text == null || text.isEmpty()) continue;
            int score = scoreText(text, queryWords);
            if (score > 0) scored.add(new ScoredDoc(name, text, score));
        }
        if (scored.isEmpty()) return null;

        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        StringBuilder sb = new StringBuilder();
        sb.append("مقتطفات ذات صلة من مكتبة مستندات المستخدم على التخزين السحابي ")
          .append("(ملفات PDF/مستندات رفعها المستخدم بنفسه من شاشة \"الملفات السحابية\" - ")
          .append("مصدر إضافي محتمل الصلة، وليس بالضرورة مراجَعًا علميًا مثل Physiopedia؛ ")
          .append("وازن بينه وبين بقية المصادر ونبّه لو تعارض معها):\n");

        int used = 0, docsUsed = 0;
        for (ScoredDoc d : scored) {
            if (docsUsed >= MAX_DOCS_IN_CONTEXT || used >= MAX_CONTEXT_CHARS) break;
            String snippet = bestSnippet(d.text, queryWords, MAX_CHARS_PER_DOC_IN_CONTEXT);
            if (snippet == null || snippet.isEmpty()) continue;
            String block = "\n• من ملف \"" + d.name + "\":\n" + snippet + "\n";
            if (used + block.length() > MAX_CONTEXT_CHARS && docsUsed > 0) break;
            sb.append(block);
            used += block.length();
            docsUsed++;
        }
        return docsUsed == 0 ? null : sb.toString();
    }

    private static String readFile(File f) {
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** يفكّك سؤال المستخدم لكلمات دالة (3 حروف فأكثر) بالعربي والإنجليزي،
     *  متجاهلًا علامات الترقيم الشائعة - أساس تطابق بسيط بدون أي مكتبة NLP. */
    private static List<String> significantWords(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        String[] parts = query.toLowerCase(Locale.ROOT)
                .split("[\\s,.;:!؟،؛\\-()\\[\\]\"'/\\\\]+");
        for (String p : parts) {
            if (p.length() >= 3) out.add(p);
        }
        return out;
    }

    private static int scoreText(String text, List<String> words) {
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String w : words) {
            int idx = 0;
            while ((idx = lower.indexOf(w, idx)) != -1) {
                score++;
                idx += w.length();
            }
        }
        return score;
    }

    /** يرجّع مقطع من النص حوالين أقرب تطابق لكلمات السؤال (بدل أول
     *  MAX_CHARS_PER_DOC_IN_CONTEXT حرف بشكل عشوائي مفيش له علاقة بالسؤال). */
    private static String bestSnippet(String text, List<String> words, int maxChars) {
        String lower = text.toLowerCase(Locale.ROOT);
        int bestIdx = -1;
        for (String w : words) {
            int idx = lower.indexOf(w);
            if (idx != -1 && (bestIdx == -1 || idx < bestIdx)) bestIdx = idx;
        }
        if (bestIdx == -1) bestIdx = 0;
        int start = Math.max(0, bestIdx - 200);
        int end = Math.min(text.length(), start + maxChars);
        String snippet = text.substring(start, end).trim();
        if (start > 0) snippet = "…" + snippet;
        if (end < text.length()) snippet = snippet + "…";
        return snippet;
    }

    private static final class ScoredDoc {
        final String name;
        final String text;
        final int score;

        ScoredDoc(String name, String text, int score) {
            this.name = name;
            this.text = text;
            this.score = score;
        }
    }
}
