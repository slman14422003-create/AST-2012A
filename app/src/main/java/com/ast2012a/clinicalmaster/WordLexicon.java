package com.ast2012a.clinicalmaster;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * قاموس صحة الكلمات العربية (بلا تشكيل) لتمييز الكلمة السليمة من الكلمة المقطوعة أو الملتصقة قبل النطق.
 *
 * مبني من قاموس التدقيق الإملائي (ar_SA.dic + ar_pure.txt) بأداة tools/build_taa_lexicon.py --valid-out،
 * ويحوي نحو 77 ألف كلمة (4 أحرف فأكثر) غير موجودة في قاموس التشكيل. يُستعمل مع {@link WordVerifier#isKnown}
 * (قاموس التشكيل بسوابقه ولواحقه) في {@link WordGlue} فقط للحكم بأن الكلمة الناتجة من الدمج كلمة حقيقية.
 * لا يُستعمل للتشكيل ولا لتصحيح الإملاء. المفاتيح مُوحَّدة بـ {@link WordVerifier#normKey}.
 *
 * التحميل اختياري: لو فُقد الملف تعمل WordGlue بقاموس التشكيل وحده. آمن للاستدعاء من أي خيط.
 */
final class WordLexicon {

    private static final Object LOCK = new Object();
    private static volatile Set<String> words = null;

    private WordLexicon() {
    }

    static void load(Context ctx) {
        if (words != null) return;
        synchronized (LOCK) {
            if (words != null) return;
            try (InputStream in = ctx.getAssets().open("valid_words.txt")) {
                loadFrom(in);
            } catch (Throwable ignored) {
            }
        }
    }

    static void loadFrom(InputStream in) throws java.io.IOException {
        Set<String> s = new HashSet<>(1 << 17);
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.length() >= 4) s.add(WordVerifier.normKey(line));
        }
        words = s;
    }

    static boolean isLoaded() {
        return words != null;
    }

    /** المفتاح مُوحَّد مسبقًا (WordVerifier.normKey). */
    static boolean has(String key) {
        Set<String> s = words;
        return s != null && key != null && s.contains(key);
    }
}
