package com.ast2012a.clinicalmaster;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * جدول السياق: يجعل القارئ يشكّل الكلمة الملتبسة بحسب الجملة لا بحسب الأشيع وحده.
 *
 * القاموس العادي (TashkeelDict) يحفظ لكل كلمة شكلًا واحدًا، فكلمة "كتب" إمّا كَتَبَ دائمًا أو كُتُب دائمًا أو تُهمل.
 * هذا الجدول يُبنى من نفس المدوّنة المشكولة بأداة tools/build_tashkeel_context.py ويحفظ للكلمات الملتبسة فقط
 * أي شكل يغلب مع الكلمة السابقة (P) ومع الكلمة التالية (N): "ثم كتب" -> كَتَب، "في كتب" -> كُتُب.
 *
 * الملف assets/tashkeel_ctx.txt اختياري تمامًا: لو غاب أو كان فارغًا لا يتغيّر شيء في سلوك القارئ.
 * كل صيغة تمرّ بنفس فحص القاموس (WordVerifier.acceptEntry): الحروف مطابقة حرفًا بحرف وبنية التشكيل سليمة.
 * لا يتدخل إلا في كلمة غير مشكولة أصلًا، ولا يتغلّب على تصحيح علّمه المستخدم (يُتحقق منه في SpeechPrep).
 */
final class ContextDict {

    private static final char SEP = '\u0001';
    private static final int MAX_ENTRIES = 400000;

    private static final class Ent {
        final String form;
        final int n;

        Ent(String form, int n) {
            this.form = form;
            this.n = n;
        }
    }

    private static final Object LOCK = new Object();
    private static volatile Map<String, Ent> prev = null; // سابقة + كلمة
    private static volatile Map<String, Ent> next = null; // كلمة + تالية
    private static volatile boolean ready = false;
    private static volatile int hits = 0;

    private ContextDict() {
    }

    /** يحمّل الجدول من assets في خيط خلفي مرة واحدة. */
    static void loadAsync(final Context ctx) {
        if (ready) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> load(app), "tashkeel-ctx-load").start();
    }

    static void load(Context ctx) {
        if (ready) return;
        synchronized (LOCK) {
            if (ready) return;
            Map<String, Ent> p = new HashMap<>(1 << 16);
            Map<String, Ent> n = new HashMap<>(1 << 16);
            try (InputStream in = ctx.getAssets().open("tashkeel_ctx.txt");
                 BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                int count = 0;
                while ((line = br.readLine()) != null && count < MAX_ENTRIES) {
                    String[] f = line.split("\t");
                    if (f.length < 5) continue;
                    boolean isP = "P".equals(f[0]);
                    if (!isP && !"N".equals(f[0])) continue;
                    String plain = isP ? f[2] : f[1];
                    String form = f[3];
                    int c;
                    try {
                        c = Integer.parseInt(f[4].trim());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (!WordVerifier.acceptEntry(plain, form)) continue;
                    String key = f[1] + SEP + f[2];
                    (isP ? p : n).put(key, new Ent(form, c));
                    count++;
                }
            } catch (Throwable ignored) {
                // الملف غير موجود (طبيعي) أو تالف: يعمل القارئ بالقاموس وحده
            }
            if (!p.isEmpty() || !n.isEmpty()) {
                prev = p;
                next = n;
            }
            ready = !p.isEmpty() || !n.isEmpty();
        }
    }

    /** هل في الجدول ما يُستعمل؟ (false لو لم يُحمَّل أو الملف غائب) */
    static boolean isReady() {
        return ready;
    }

    static int size() {
        Map<String, Ent> p = prev;
        Map<String, Ent> n = next;
        return (p == null ? 0 : p.size()) + (n == null ? 0 : n.size());
    }

    /** مفتاح الكلمة كما تبنيه الأداة: حروف عربية فقط بلا علامات ولا تطويل (ٱ->ا، ک->ك، ی->ي). null لو ليست عربية خالصة. */
    static String key(String token) {
        if (token == null) return null;
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (ArabicPhonetics.isMark(c) || c == '\u0640') continue;
            if (c == '\u0671') c = '\u0627';
            else if (c == '\u06A9') c = '\u0643';
            else if (c == '\u06CC') c = '\u064A';
            if (c < 0x0621 || c > 0x064A) return null;
            sb.append(c);
        }
        return sb.length() < 3 ? null : sb.toString();
    }

    /**
     * أفضل شكل للكلمة بحسب جيرانها، أو null لو لا سياق محفوظ (فيُستعمل القاموس العادي).
     * prevKey: "^" أول الجملة، nextKey: "$" آخرها، وإلا مفتاح الجار (أو "" لو الجار ليس كلمة عربية).
     */
    static String lookup(String prevKey, String key, String nextKey) {
        if (!ready || key == null) return null;
        Map<String, Ent> p = prev;
        Map<String, Ent> n = next;
        Ent a = p == null ? null : p.get(prevKey + SEP + key);
        Ent b = n == null ? null : n.get(key + SEP + nextKey);
        Ent best = a;
        if (b != null && (a == null || b.n > a.n)) best = b;
        if (best == null) return null;
        hits++;
        return best.form;
    }

    static String stats() {
        return ready ? "ctx: " + size() + " سياق | استُعمل " + hits + " مرة" : "ctx: غير محمَّل";
    }
}
