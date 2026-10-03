package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * إعدادات محرك الصوت البشري (طبقة الإيقاع فوق الأصوات العصبية) - تُحفظ على الجهاز وتُقرأ من أي خيط.
 *
 * كل تغيير يرفع {@link #version()}؛ وPdfSpeaker يضيف هذا الرقم إلى مفتاح الصوت المخزَّن مؤقتًا، فتُعاد صياغة
 * الصوت تلقائيًا بالإعدادات الجديدة بدون أن نمسح الكاش يدويًا أو نعيد تشغيل القارئ.
 */
final class VoiceEngineConfig {

    private static final String PREFS = "voice_engine_cfg";

    // ---- المفاتيح
    private static final String K_ENABLED = "enabled";
    private static final String K_PRESET = "preset";
    private static final String K_NATURAL = "natural";
    private static final String K_EXPRESS = "express";
    private static final String K_PAUSE = "pause_pct";
    private static final String K_RATE = "rate_off";
    private static final String K_PITCH = "pitch_off";
    private static final String K_QUESTION = "question";
    private static final String K_HEADING = "heading";
    private static final String K_PAREN = "paren";
    private static final String K_BREATH = "breath";
    private static final String K_LEARN = "behavior_learn";
    private static final String K_VERSION = "version";

    // ---- الأنماط الجاهزة
    static final int PRESET_BALANCED = 0;
    static final int PRESET_STORY = 1;
    static final int PRESET_STUDY = 2;
    static final int PRESET_NEWS = 3;
    static final int PRESET_CUSTOM = 4;

    static final String[] PRESET_NAMES = {
            "متوازن (إنساني)", "راوي قصص", "دراسة وتركيز", "نشرة أخبار", "مخصّص"
    };

    private static SharedPreferences prefs;

    // نسخة مخزّنة في الذاكرة حتى لا نقرأ SharedPreferences في كل جملة (تُحدَّث عند أي تعديل)
    private static volatile boolean enabled = true;
    private static volatile int preset = PRESET_BALANCED;
    private static volatile int natural = 60;
    private static volatile int express = 60;
    private static volatile int pausePct = 100;
    private static volatile int rateOff = 0;
    private static volatile int pitchOff = 0;
    private static volatile boolean question = true;
    private static volatile boolean heading = true;
    private static volatile boolean paren = true;
    private static volatile boolean breath = true;
    private static volatile boolean learn = true;
    private static volatile int version = 0;

    private VoiceEngineConfig() {
    }

    static synchronized void init(Context ctx) {
        if (prefs != null || ctx == null) return;
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        reload();
    }

    /** يعيد القراءة من التخزين (مفيد لو عُدّلت الإعدادات من شاشة أخرى بينما القارئ حيّ). */
    static synchronized void reload() {
        if (prefs == null) return;
        enabled = prefs.getBoolean(K_ENABLED, true);
        preset = clamp(prefs.getInt(K_PRESET, PRESET_BALANCED), 0, PRESET_CUSTOM);
        natural = clamp(prefs.getInt(K_NATURAL, 60), 0, 100);
        express = clamp(prefs.getInt(K_EXPRESS, 60), 0, 100);
        pausePct = clamp(prefs.getInt(K_PAUSE, 100), 50, 200);
        rateOff = clamp(prefs.getInt(K_RATE, 0), -30, 30);
        pitchOff = clamp(prefs.getInt(K_PITCH, 0), -20, 20);
        question = prefs.getBoolean(K_QUESTION, true);
        heading = prefs.getBoolean(K_HEADING, true);
        paren = prefs.getBoolean(K_PAREN, true);
        breath = prefs.getBoolean(K_BREATH, true);
        learn = prefs.getBoolean(K_LEARN, true);
        version = prefs.getInt(K_VERSION, 0);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ قراءة

    static boolean isEnabled() {
        return enabled;
    }

    static int preset() {
        return preset;
    }

    static String presetName() {
        return PRESET_NAMES[preset];
    }

    /** مقدار التنويع الطبيعي في الطبقة والسرعة بين الجمل (0..100). */
    static int naturalness() {
        return natural;
    }

    /** مقدار التعبير: ارتفاع نغمة السؤال، هبوط العناوين... (0..100). */
    static int expressiveness() {
        return express;
    }

    /** مقياس طول الوقفات (50..200 %). */
    static int pausePercent() {
        return pausePct;
    }

    /** إزاحة السرعة (نسبة مئوية) فوق سرعة القارئ. */
    static int rateOffset() {
        return rateOff;
    }

    /** إزاحة طبقة الصوت (هرتز). */
    static int pitchOffset() {
        return pitchOff;
    }

    static boolean questionRise() {
        return question;
    }

    static boolean headingStyle() {
        return heading;
    }

    static boolean parentheticalStyle() {
        return paren;
    }

    static boolean breathPauses() {
        return breath;
    }

    static boolean behaviorLearning() {
        return learn;
    }

    /** يتغيّر مع كل تعديل - يدخل في مفتاح الكاش الصوتي. */
    static int version() {
        return version;
    }

    // ------------------------------------------------------------------ كتابة

    static void setEnabled(boolean v) {
        enabled = v;
        put(K_ENABLED, v);
    }

    static void setNaturalness(int v) {
        natural = clamp(v, 0, 100);
        markCustom();
        put(K_NATURAL, natural);
    }

    static void setExpressiveness(int v) {
        express = clamp(v, 0, 100);
        markCustom();
        put(K_EXPRESS, express);
    }

    static void setPausePercent(int v) {
        pausePct = clamp(v, 50, 200);
        markCustom();
        put(K_PAUSE, pausePct);
    }

    static void setRateOffset(int v) {
        rateOff = clamp(v, -30, 30);
        markCustom();
        put(K_RATE, rateOff);
    }

    static void setPitchOffset(int v) {
        pitchOff = clamp(v, -20, 20);
        markCustom();
        put(K_PITCH, pitchOff);
    }

    static void setQuestionRise(boolean v) {
        question = v;
        markCustom();
        put(K_QUESTION, v);
    }

    static void setHeadingStyle(boolean v) {
        heading = v;
        markCustom();
        put(K_HEADING, v);
    }

    static void setParentheticalStyle(boolean v) {
        paren = v;
        markCustom();
        put(K_PAREN, v);
    }

    static void setBreathPauses(boolean v) {
        breath = v;
        markCustom();
        put(K_BREATH, v);
    }

    static void setBehaviorLearning(boolean v) {
        learn = v; // لا يغيّر الصوت نفسه: لا نرفع رقم النسخة ولا نحوّل النمط إلى "مخصّص"
        if (prefs != null) prefs.edit().putBoolean(K_LEARN, v).apply();
    }

    /** أي تعديل يدوي على قيمة نمط جاهز يحوّله إلى "مخصّص". */
    private static void markCustom() {
        if (preset != PRESET_CUSTOM) {
            preset = PRESET_CUSTOM;
            put(K_PRESET, PRESET_CUSTOM);
        }
    }

    /** يطبّق نمطًا جاهزًا (يستبدل كل قيم الإيقاع، ولا يمسّ مفتاح التعلّم ولا التفعيل). */
    static synchronized void applyPreset(int p) {
        if (prefs == null) return;
        p = clamp(p, 0, PRESET_CUSTOM - 1);
        switch (p) {
            case PRESET_STORY:
                natural = 80; express = 85; pausePct = 125; rateOff = -4; pitchOff = 0;
                question = true; heading = true; paren = true; breath = true;
                break;
            case PRESET_STUDY:
                natural = 40; express = 40; pausePct = 140; rateOff = -10; pitchOff = 0;
                question = true; heading = true; paren = true; breath = true;
                break;
            case PRESET_NEWS:
                natural = 45; express = 50; pausePct = 80; rateOff = 3; pitchOff = -2;
                question = true; heading = true; paren = false; breath = false;
                break;
            case PRESET_BALANCED:
            default:
                natural = 60; express = 60; pausePct = 100; rateOff = 0; pitchOff = 0;
                question = true; heading = true; paren = true; breath = true;
                break;
        }
        preset = p;
        SharedPreferences.Editor e = prefs.edit();
        e.putInt(K_PRESET, p);
        e.putInt(K_NATURAL, natural);
        e.putInt(K_EXPRESS, express);
        e.putInt(K_PAUSE, pausePct);
        e.putInt(K_RATE, rateOff);
        e.putInt(K_PITCH, pitchOff);
        e.putBoolean(K_QUESTION, question);
        e.putBoolean(K_HEADING, heading);
        e.putBoolean(K_PAREN, paren);
        e.putBoolean(K_BREATH, breath);
        e.putInt(K_VERSION, prefs.getInt(K_VERSION, 0) + 1);
        e.apply();
        version = version + 1;
    }

    // ------------------------------------------------------------------ مساعدات الكتابة

    private static synchronized void put(String key, int v) {
        if (prefs == null) return;
        prefs.edit().putInt(key, v).putInt(K_VERSION, prefs.getInt(K_VERSION, 0) + 1).apply();
        version = version + 1;
    }

    private static synchronized void put(String key, boolean v) {
        if (prefs == null) return;
        prefs.edit().putBoolean(key, v).putInt(K_VERSION, prefs.getInt(K_VERSION, 0) + 1).apply();
        version = version + 1;
    }
}
