package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * تعلّم تكيّفي على الجهاز (بلا إنترنت) من سلوك الاستماع:
 *
 *  1) أنواع الجمل المتعبة: لكل نوع (سؤال، عنوان، أقواس، أرقام، جملة طويلة...) نحسب نسبة "الرجوع للخلف" مقابل
 *     "السماع حتى النهاية". النوع الذي يرجع إليه المستخدم أكثر من المتوسط يُبطَّأ قليلًا (حتى -6%) تلقائيًا.
 *  2) الكلمات المشتبه بها: كلمة في مقطع رجع إليه المستخدم ولا يستطيع القاموس تشكيلها بيقين تُسجَّل مشتبهًا بها.
 *     هذه القائمة هي ما يُرسَل (كلمات فقط، بدون جمل) إلى المدرّب الذكي VoiceAiTutor عند طلب المستخدم.
 *
 * كل شيء يُحفظ في SharedPreferences ويُهمَل أي خطأ داخلي بصمت (لا يجوز أن يوقف القراءة).
 */
final class VoiceBehaviorLearner {

    private static final String PREFS = "voice_behavior";
    private static final int KINDS = 8; // 0..6 أنواع HumanProsody + 7 = مقاطع أجنبية
    private static final int MIN_SAMPLES = 12;
    private static final int MAX_SUSPECTS = 300;
    private static final long SAVE_EVERY_MS = 15_000L;
    private static final Object LOCK = new Object();

    private static final float[] rewinds = new float[KINDS];
    private static final float[] heard = new float[KINDS];
    private static final Map<String, Integer> suspects = new HashMap<>();

    private static SharedPreferences prefs;
    private static boolean dirty;
    private static long lastSave;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voice-behavior");
        t.setDaemon(true);
        return t;
    });

    private VoiceBehaviorLearner() {
    }

    // ------------------------------------------------------------------ تهيئة وحفظ

    static void init(Context ctx) {
        synchronized (LOCK) {
            if (prefs != null || ctx == null) return;
            prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            try {
                load();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void load() {
        parseFloats(prefs.getString("rw", ""), rewinds);
        parseFloats(prefs.getString("hd", ""), heard);
        suspects.clear();
        String s = prefs.getString("sus", "");
        if (s == null || s.isEmpty()) return;
        for (String line : s.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                suspects.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static void parseFloats(String s, float[] out) {
        if (s == null || s.isEmpty()) return;
        String[] p = s.split(",");
        for (int i = 0; i < out.length && i < p.length; i++) {
            try {
                out[i] = Float.parseFloat(p[i]);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static String joinFloats(float[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    static void flush() {
        synchronized (LOCK) {
            save(true);
        }
    }

    private static void touch() {
        dirty = true;
        if (System.currentTimeMillis() - lastSave >= SAVE_EVERY_MS) save(false);
    }

    private static void save(boolean force) {
        if (prefs == null || (!dirty && !force)) return;
        lastSave = System.currentTimeMillis();
        dirty = false;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : suspects.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        prefs.edit()
                .putString("rw", joinFloats(rewinds))
                .putString("hd", joinFloats(heard))
                .putString("sus", sb.toString())
                .apply();
    }

    // ------------------------------------------------------------------ تصنيف

    private static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u0621' && c <= '\u064A') return true;
        }
        return false;
    }

    private static int classOf(String raw) {
        if (!hasArabic(raw)) return KINDS - 1;
        int k = HumanProsody.classify(raw, false);
        return Math.max(0, Math.min(KINDS - 2, k));
    }

    // ------------------------------------------------------------------ إشارات الاستماع

    /** المستخدم رجع إلى هذا المقطع: النوع يُتَّهم، وكلماته غير المشكولة تُسجَّل مشتبهًا بها. */
    static void noteRewind(final String rawChunk) {
        if (rawChunk == null || rawChunk.trim().isEmpty()) return;
        if (!VoiceEngineConfig.behaviorLearning()) return;
        try {
            WORKER.execute(() -> {
                try {
                    int k = classOf(rawChunk);
                    synchronized (LOCK) {
                        rewinds[k] += 1f;
                        decay(k);
                    }
                    recordSuspects(rawChunk);
                    synchronized (LOCK) {
                        touch();
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** المقطع سُمع كاملًا بلا رجوع. */
    static void noteHeard(final String rawChunk) {
        if (rawChunk == null || rawChunk.trim().isEmpty()) return;
        if (!VoiceEngineConfig.behaviorLearning()) return;
        try {
            WORKER.execute(() -> {
                try {
                    int k = classOf(rawChunk);
                    synchronized (LOCK) {
                        heard[k] += 1f;
                        decay(k);
                        dirty = true;
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** يخفّف عدّادات النوع القديمة كي تعكس السلوك الأخير (يُستدعى داخل LOCK). */
    private static void decay(int k) {
        if (rewinds[k] + heard[k] > 300f) {
            rewinds[k] *= 0.5f;
            heard[k] *= 0.5f;
        }
    }

    private static void recordSuspects(String raw) {
        if (!TashkeelDict.isReady()) return;
        List<String> words = arabicWords(raw);
        for (String w : words) {
            if (w.length() < 3) continue;
            if (SpeechLearner.isTaught(w)) continue;
            String known;
            try {
                known = TashkeelDict.lookup(w);
            } catch (Throwable t) {
                continue;
            }
            if (known != null) continue; // القاموس يعرفها بيقين: ليست مشتبهًا بها
            synchronized (LOCK) {
                Integer c = suspects.get(w);
                suspects.put(w, c == null ? 1 : c + 1);
                if (suspects.size() > MAX_SUSPECTS) evictWeakest();
            }
        }
    }

    private static void evictWeakest() {
        String worst = null;
        int min = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : suspects.entrySet()) {
            if (e.getValue() < min) {
                min = e.getValue();
                worst = e.getKey();
            }
        }
        if (worst != null) suspects.remove(worst);
    }

    // ------------------------------------------------------------------ أثر التعلّم على السرعة

    /**
     * تعديل سرعة (≤ 0) لنوع الجملة: فقط لو كان رجوع المستخدم لهذا النوع أعلى من متوسطه العام.
     * لا يتكرر مع تعديل SpeechLearner العام لأنه يحسب الزيادة فوق المتوسط فقط.
     */
    static int extraRatePct(int kind, String lang) {
        if (!"ar".equals(lang)) return 0; // السرعة لا تُطبَّق إلا على الصوت العربي
        if (kind < 0 || kind >= KINDS - 1) return 0;
        synchronized (LOCK) {
            float n = rewinds[kind] + heard[kind];
            if (n < MIN_SAMPLES) return 0;
            float totalRw = 0f;
            float totalHd = 0f;
            for (int i = 0; i < KINDS; i++) {
                totalRw += rewinds[i];
                totalHd += heard[i];
            }
            float total = totalRw + totalHd;
            if (total <= 0f) return 0;
            float extra = (rewinds[kind] / n) - (totalRw / total);
            if (extra <= 0.03f) return 0;
            return -Math.round(Math.min(6f, extra * 40f));
        }
    }

    // ------------------------------------------------------------------ الكلمات المشتبه بها

    static int suspectCount() {
        synchronized (LOCK) {
            return suspects.size();
        }
    }

    /** أكثر الكلمات اشتباهًا (الأعلى تكرارًا أولًا)، دون ما عُلِّم نطقه فعلًا. */
    static List<String> topSuspects(int max) {
        List<Map.Entry<String, Integer>> all;
        synchronized (LOCK) {
            all = new ArrayList<>(suspects.entrySet());
        }
        Collections.sort(all, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : all) {
            if (out.size() >= max) break;
            if (SpeechLearner.isTaught(e.getKey())) continue;
            out.add(e.getKey());
        }
        return out;
    }

    static void removeSuspect(String word) {
        synchronized (LOCK) {
            suspects.remove(word);
            touch();
        }
    }

    static void clearSuspects() {
        synchronized (LOCK) {
            suspects.clear();
            touch();
        }
    }

    static void resetAll() {
        synchronized (LOCK) {
            for (int i = 0; i < KINDS; i++) {
                rewinds[i] = 0f;
                heard[i] = 0f;
            }
            suspects.clear();
            dirty = true;
            save(true);
        }
    }

    static String stats() {
        synchronized (LOCK) {
            float rw = 0f;
            float hd = 0f;
            for (int i = 0; i < KINDS; i++) {
                rw += rewinds[i];
                hd += heard[i];
            }
            return "سُمع " + Math.round(hd) + " مقطعًا · رجوع " + Math.round(rw)
                    + " مرة · كلمات مشتبه بها " + suspects.size();
        }
    }

    // ------------------------------------------------------------------ أدوات نصية (تُستعمل أيضًا من VoiceAiTutor)

    /** الكلمة بلا تشكيل ولا تطويل (الحروف العربية فقط). */
    static String bare(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u0621' && c <= '\u064A') sb.append(c);
        }
        return sb.toString();
    }

    /** الكلمات العربية في النص (بلا تشكيل)، بلا تكرار داخل المقطع. */
    static List<String> arabicWords(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            boolean arabic = (c >= '\u0621' && c <= '\u064A') || (c >= '\u064B' && c <= '\u065F')
                    || c == '\u0670' || c == '\u0640';
            if (arabic) {
                cur.append(c);
            } else if (cur.length() > 0) {
                String w = bare(cur.toString());
                if (!w.isEmpty() && !out.contains(w)) out.add(w);
                cur.setLength(0);
            }
        }
        return out;
    }
}
