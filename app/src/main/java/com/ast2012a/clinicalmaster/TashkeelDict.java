package com.ast2012a.clinicalmaster;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * قاموس تشكيل محلي بالكامل (بدون إنترنت ولا ذكاء اصطناعي ولا حدود استخدام).
 *
 * يُبنى مرة واحدة على الكمبيوتر من مدوّنة عربية مشكولة عبر tools/build_tashkeel_dict.py
 * ويوضع في app/src/main/assets/tashkeel_dict.txt (كل سطر: كلمة_بلا_تشكيل TAB كلمة_مشكولة).
 *
 * في حال عدم وجود الملف يبقى القاموس فارغًا ويعمل التطبيق كما كان تمامًا (لا أخطاء).
 * يُستدعى من SpeechPrep.diacritize بعد القاموس اليدوي (D) وقاموس المصطلحات الطبية
 * (ArabicPhonetics.lookup)، فلا يغيّر أي كلمة مضبوطة يدويًا.
 */
final class TashkeelDict {

    private static final String ASSET = "tashkeel_dict.txt";
    private static final Object LOCK = new Object();
    private static volatile Map<String, String> map = null;

    private TashkeelDict() {
    }

    /** تحميل القاموس (يُنفَّذ في خيط خلفي عند تشغيل التطبيق). آمن للاستدعاء المتكرر. */
    static void load(Context ctx) {
        if (map != null) return;
        synchronized (LOCK) {
            if (map != null) return;
            Map<String, String> m = new HashMap<>(1 << 16);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(ctx.getAssets().open(ASSET), StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.indexOf('\t');
                    if (t <= 0 || t >= line.length() - 1) continue;
                    String plain = line.substring(0, t);
                    String shaped = line.substring(t + 1).trim();
                    // حماية: المشكول يجب أن يطابق المجرّد حرفًا بحرف (لا حرف ضائع ولا زائد)
                    if (!plain.equals(stripMarks(shaped))) continue;
                    m.put(plain, shaped);
                }
            } catch (Throwable ignored) {
                // الملف غير موجود أو تالف: نبقى بدون قاموس
            }
            map = m;
        }
    }

    /** الكلمة المشكولة للكلمة المجرّدة، أو null لو غير موجودة (أو القاموس لم يُحمَّل بعد). */
    static String lookup(String plain) {
        Map<String, String> m = map;
        if (m == null || m.isEmpty() || plain == null || plain.length() < 3) return null;
        String v = m.get(plain);
        if (v != null) return v;
        // كلمة مسبوقة بواو/فاء العطف (والكتاب، فالمريض): نشكّل الباقي ونضيف الفتحة
        char c = plain.charAt(0);
        if ((c == '\u0648' || c == '\u0641') && plain.length() >= 4) {
            String base = m.get(plain.substring(1));
            if (base != null) return c + "\u064E" + base;
        }
        return null;
    }

    private static String stripMarks(String w) {
        StringBuilder sb = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!ArabicPhonetics.isMark(c)) sb.append(c);
        }
        return sb.toString();
    }
}
