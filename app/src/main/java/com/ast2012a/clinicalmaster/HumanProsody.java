package com.ast2012a.clinicalmaster;

/**
 * مخطِّط الإيقاع البشري: يأخذ الجملة وأسلوب القارئ الأساسي ويرجّع أسلوبًا مضبوطًا لهذه الجملة وحدها.
 *
 * الأصوات العصبية تقرأ كل جملة بنفس النغمة والسرعة فتبدو آلية. الإنسان يرفع صوته في السؤال، يهبط عند
 * العناوين، يسرّع داخل الأقواس، يبطّئ عند الأرقام والجمل الطويلة، ويلتقط نفسه بين الجمل، ولا يكرّر الجملة
 * بنفس الدرجة مرتين. هذه الفئة تحاكي ذلك عبر معاملات SSML المتاحة (طبقة، سرعة، وقفات) دون أي تغيير في المحرك.
 *
 * التنويع بين الجمل "حتمي": مشتق من نص الجملة نفسه، فتتكرر نفس النتيجة لنفس الجملة (الكاش يبقى صالحًا
 * وإعادة القراءة تبدو كما كانت) لكنه يختلف بين جملة وأخرى.
 */
final class HumanProsody {

    static final int KIND_PLAIN = 0;
    static final int KIND_QUESTION = 1;
    static final int KIND_EXCLAIM = 2;
    static final int KIND_HEADING = 3;
    static final int KIND_PAREN = 4;
    static final int KIND_LONG = 5;
    static final int KIND_NUMERIC = 6;

    private static final String TERMINALS = ".!?\u061F\u061B\u2026:,\u060C;";
    private static final String CLOSERS = "\"'\u201D\u2019\u00BB)]}";
    private static final String OPENERS = "([{\u00AB\u2014\u2013";

    private HumanProsody() {
    }

    /** يصنّف الجملة بنيويًا (يُستعمل هنا وفي تعلّم السلوك). */
    static int classify(String raw, boolean cont) {
        if (raw == null) return KIND_PLAIN;
        String t = raw.trim();
        if (t.isEmpty()) return KIND_PLAIN;

        char first = t.charAt(0);
        if (OPENERS.indexOf(first) >= 0) return KIND_PAREN;

        // آخر محرف ذي معنى (نتجاوز علامات الاقتباس والأقواس الختامية)
        int end = t.length() - 1;
        while (end > 0 && CLOSERS.indexOf(t.charAt(end)) >= 0) end--;
        char last = t.charAt(end);

        if (!cont) {
            if (last == '?' || last == '\u061F') return KIND_QUESTION;
            if (last == '!') return KIND_EXCLAIM;
        }

        int words = countWords(t);
        if (!cont && TERMINALS.indexOf(last) < 0 && words <= 7) return KIND_HEADING;

        int digits = 0;
        int letters = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isDigit(c)) digits++;
            else if (Character.isLetter(c)) letters++;
        }
        if (digits >= 3 && digits * 5 >= (digits + letters)) return KIND_NUMERIC;

        if (words > 24 || t.length() > 170) return KIND_LONG;
        return KIND_PLAIN;
    }

    private static int countWords(String t) {
        int n = 0;
        boolean in = false;
        for (int i = 0; i < t.length(); i++) {
            boolean ws = Character.isWhitespace(t.charAt(i));
            if (!ws && !in) n++;
            in = !ws;
        }
        return n;
    }

    /** قيمة ثابتة في المدى [-1, 1) مشتقة من البذرة (خلط بتات بسيط). */
    private static float unit(int seed) {
        int x = seed * 0x9E3779B1;
        x ^= x >>> 15;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        return ((x & 0xFFFF) / 32768f) - 1f;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * @param raw  نص الجملة كما هو في الصفحة (قبل التشكيل)
     * @param lang لغة المقطع (ar/en/...)
     * @param cont true = الجملة قُطعت لطولها وتكمل بعد هذا المقطع
     * @param base الأسلوب الأساسي من القارئ (النمط + سرعة التعلّم + طبقة المستخدم)
     */
    static EdgeTtsClient.Style shape(String raw, String lang, boolean cont, EdgeTtsClient.Style base) {
        if (base == null) base = EdgeTtsClient.Style.DEFAULT;
        if (!VoiceEngineConfig.isEnabled() || raw == null) return base;
        String t = raw.trim();
        if (t.isEmpty()) return base;

        final int kind = classify(t, cont);
        final float nat = VoiceEngineConfig.naturalness() / 100f;
        final float ex = VoiceEngineConfig.expressiveness() / 100f;

        final int h = t.hashCode();
        int pitch = base.pitchHz + VoiceEngineConfig.pitchOffset() + Math.round(unit(h) * 3f * nat);
        int rate = base.arRatePct + VoiceEngineConfig.rateOffset() + Math.round(unit(h * 31 + 7) * 3f * nat);

        switch (kind) {
            case KIND_QUESTION:
                if (VoiceEngineConfig.questionRise()) pitch += Math.round(6f * ex);
                break;
            case KIND_EXCLAIM:
                if (VoiceEngineConfig.questionRise()) {
                    pitch += Math.round(4f * ex);
                    rate += Math.round(3f * ex);
                }
                break;
            case KIND_HEADING:
                if (VoiceEngineConfig.headingStyle()) {
                    pitch -= Math.round(3f * ex);
                    rate -= Math.round(6f * ex);
                }
                break;
            case KIND_PAREN:
                if (VoiceEngineConfig.parentheticalStyle()) {
                    pitch -= Math.round(3f * ex);
                    rate += Math.round(4f * ex);
                }
                break;
            case KIND_NUMERIC:
                rate -= 4; // الأرقام والقياسات: أوضح وأبطأ
                break;
            case KIND_LONG:
                rate -= 3; // الجملة الطويلة يتنفس فيها القارئ
                break;
            default:
                break;
        }

        // تعلّم السلوك: الأنواع التي يرجع المستخدم إليها أكثر من غيرها تُبطَّأ قليلًا (≤ 0 دائمًا)
        if (VoiceEngineConfig.behaviorLearning()) {
            try {
                rate += VoiceBehaviorLearner.extraRatePct(kind, lang);
            } catch (Throwable ignored) {
            }
        }

        // ---- الوقفات
        final boolean breath = VoiceEngineConfig.breathPauses();
        final float scale = VoiceEngineConfig.pausePercent() / 100f;
        float sp = breath ? Math.max(base.sentencePauseMs, 130) : base.sentencePauseMs;
        float cp = breath ? Math.max(base.commaPauseMs, 55) : base.commaPauseMs;
        sp *= scale;
        cp *= scale;
        if (kind == KIND_HEADING && VoiceEngineConfig.headingStyle()) sp *= 2.2f;
        else if (kind == KIND_QUESTION) sp *= 1.15f;
        else if (kind == KIND_PAREN) sp *= 0.8f;
        if (kind == KIND_LONG) cp *= 1.25f;

        return new EdgeTtsClient.Style(
                clamp(rate, -45, 40),
                clamp(pitch, -40, 40),
                clamp(Math.round(sp), 0, 900),
                clamp(Math.round(cp), 0, 400));
    }
}
