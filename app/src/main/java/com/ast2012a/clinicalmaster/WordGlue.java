package com.ast2012a.clinicalmaster;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * إصلاح الكلمات المتقطعة والملتصقة قبل النطق بالاعتماد على القاموس.
 *
 * ملفات الـ PDF تُخرج أحيانًا الكلمة الواحدة مقطوعة (عضل ات، ال تمارين، الم فصل) فينطقها المحرك مقاطع منفصلة،
 * أو تلصق كلمتين (فيالمرحلة، منخلال) فلا يجد القاموس أيًّا منهما فتُنطق بلا تشكيل.
 *
 *  1) {@link #shouldMerge}: وصل جزأين لو كانت الكلمة الناتجة موجودة في القاموس والجزءان ليسا كلمتين صحيحتين معًا.
 *     (عضل + ات -> عضلات، الت + مارين -> التمارين، ا + لمفصل -> المفصل). لا نصل كلمتين صحيحتين أبدًا
 *     (في + ما، أن + هم، قال + ان) حتى لا نخلط جملة سليمة، ولا حرفًا مفردًا قد يكون رمزًا (النقطة ب).
 *  2) {@link #splitGlued}: فصل كلمة غير موجودة تبدأ بحرف جر/أداة شائعة (في، من، على، عن...) وما بعدها كلمة صحيحة:
 *     فيالمرحلة -> في المرحلة.
 *
 * الصحة = {@link WordVerifier#isKnown} (قاموس التشكيل بسوابقه ولواحقه) أو {@link WordLexicon} (قاموس التدقيق).
 * كل شيء للنطق فقط؛ النص المعروض والتظليل لا يتأثران (يبقى التظليل على أول جزء). لا شيء يعمل قبل اكتمال القاموس.
 */
final class WordGlue {

    private WordGlue() {
    }

    private static volatile boolean enabled = true;

    static void setEnabled(boolean v) {
        enabled = v;
    }

    static boolean isEnabled() {
        return enabled;
    }

    private static Set<String> keys(String... words) {
        Set<String> s = new HashSet<>();
        for (String w : words) s.add(WordVerifier.normKey(w));
        return s;
    }

    /** كلمات قصيرة صحيحة قائمة بذاتها (لا تُعدّ أجزاء مقطوعة): أدوات وحروف وضمائر وأفعال شائعة. */
    private static final Set<String> FUNC = keys(
            "من", "في", "عن", "على", "علي", "إلى", "الى", "الي", "أن", "ان", "إن", "أو", "او", "لا", "ما", "لم", "لن", "قد",
            "بل", "ثم", "كي", "لو", "هل", "هو", "هي", "هم", "هن", "ها", "نا", "كم", "كن", "ذا", "ذي", "ذو", "مع", "رب", "يا",
            "أي", "اي", "أم", "ام", "إذ", "اذ", "إذا", "اذا", "تم", "تمت", "كل", "بن", "أب", "اب", "أخ", "اخ", "أنا", "انا",
            "أنت", "انت", "نحن", "هذا", "هذه", "ذلك", "تلك", "كان", "ليس", "كما", "حتى", "بعد", "قبل", "بين", "عند", "منذ",
            "دون", "غير", "مثل", "لدى", "أين", "اين", "متى", "كيف", "نعم", "بلى", "كلا", "إلا", "الا", "حيث", "حين", "سوف",
            "لكن", "لكي", "فقط", "أيضا", "ايضا", "جدا", "قط", "فيه", "فيها", "منه", "منها", "عنه", "عنها", "به", "بها",
            "له", "لها", "لهم", "لنا", "لك", "بك", "بي", "لي", "مني", "عني", "إني", "اني", "أنه", "انه", "أنها", "انها",
            "إنه", "انه", "إنها", "الذي", "التي", "الذين", "اللذان", "هنا", "هناك", "هنالك", "أمام", "امام", "خلف", "فوق",
            "تحت", "حول", "خلال", "ضد", "نحو", "عبر", "سوى", "ذاك", "هما", "كلا", "كلتا",
            "قم", "دع", "خذ", "ذه", "ال", "وال", "فال", "بال", "كال", "لل");

    /** لواحق صرفية لا تقف وحدها (جمع، مثنى، نسبة، تأنيث): قطعة منها بعد كلمة صحيحة = كلمة مقطوعة. */
    private static final Set<String> SUFFIX_FRAG = keys(
            "ات", "ين", "ون", "ية", "يه", "ة", "ئة", "اء", "اة", "تين", "تان", "يات", "يين", "ائي", "ائية", "وا",
            "ه", "ن", "ت", "ي", "ا", "ى");

    /** أدوات تُلصق بما بعدها عند ضياع المسافة (فيالمرحلة): تُفصل لو كان ما بعدها كلمة صحيحة. */
    private static final String[] GLUED_HEADS = {"في", "من", "عن", "على", "إلى", "الى", "مع", "بين", "حتى", "منذ", "خلال",
            "بعد", "قبل", "عند", "ثم", "لا", "ما", "لم", "لن", "قد", "كل"};

    private static final int MAX_LEN = 24;

    // ------------------------------------------------------------------ أدوات

    private static boolean isLetter(char c) {
        return c >= 0x0621 && c <= 0x064A;
    }

    /**
     * مفتاح الكلمة: حروفها فقط (بلا تشكيل ولا تطويل ولا ترقيم في الطرفين)، موحَّدة بـ normKey.
     * null لو فيها غير الحروف العربية في وسطها (أرقام، لاتينية، رموز).
     */
    private static String key(String w) {
        if (w == null || w.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(w.length());
        int a = 0;
        int b = w.length();
        while (a < b && !isLetter(w.charAt(a)) && !ArabicPhonetics.isMark(w.charAt(a))) a++;
        while (b > a && !isLetter(w.charAt(b - 1)) && !ArabicPhonetics.isMark(w.charAt(b - 1))) b--;
        for (int i = a; i < b; i++) {
            char c = w.charAt(i);
            if (ArabicPhonetics.isMark(c) || c == '\u0640') continue;
            if (!isLetter(SpeechAuditor.unifyLetter(c))) return null;
            sb.append(c);
        }
        return sb.length() == 0 ? null : WordVerifier.normKey(sb.toString());
    }

    private static final String[] PRE = {"", "\u0648", "\u0641", "\u0628", "\u0643", "\u0644", "\u0648\u0628", "\u0641\u0628",
            "\u0648\u0643", "\u0641\u0643", "\u0648\u0644", "\u0641\u0644"};

    private static boolean knownBare(String k) {
        return k.length() >= 3 && (WordVerifier.isKnown(k) || WordLexicon.has(k));
    }

    /**
     * كلمة صحيحة: في قاموس التشكيل/التدقيق مباشرة، أو بأداة التعريف مع سابقة اختيارية (المفصل، والتمارين، بالمرحلة، للمريض)،
     * أو بواو/فاء عطف قبل كلمة 4 أحرف فأكثر (ومرض). قاموس التدقيق يحوي الصيغ المجرّدة فقط فنفكّ اللواصق هنا.
     */
    private static boolean known(String k) {
        if (k == null || k.length() < 3) return false;
        try {
            if (knownBare(k)) return true;
            for (String p : PRE) {
                if (!k.startsWith(p)) continue;
                String r = k.substring(p.length());
                if (r.startsWith("\u0627\u0644") && r.length() >= 5 && knownBare(r.substring(2))) return true;
                // للمريض: لل = ل + ال
                if (p.isEmpty() && r.startsWith("\u0644\u0644") && r.length() >= 5 && knownBare(r.substring(2))) return true;
            }
            if (k.length() >= 5 && (k.charAt(0) == '\u0648' || k.charAt(0) == '\u0641') && knownBare(k.substring(1))) return true;
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** كلمة صحيحة قائمة بذاتها. */
    private static boolean standalone(String k) {
        return FUNC.contains(k) || known(k);
    }

    // ------------------------------------------------------------------ 1) وصل المقطوع

    /** (سابقة اختيارية) + ال + حرف أو حرفان فقط: بداية كلمة مقطوعة بعد أداة التعريف. */
    private static boolean isArticleFragment(String k) {
        for (String p : PRE) {
            if (!k.startsWith(p)) continue;
            String r = k.substring(p.length());
            if (r.startsWith("\u0627\u0644") && r.length() >= 3 && r.length() <= 4) return true;
        }
        return false;
    }

    /**
     * هل يُوصل الجزء a بالجزء b (كلمتان متجاورتان بلا ترقيم بينهما) فيصيران كلمة واحدة؟
     * a و b بعد clean (قد يحمل b ترقيمًا في آخره فلا يمنع الوصل).
     */
    static boolean shouldMerge(String a, String b) {
        if (!enabled || !TashkeelDict.isReady()) return false;
        String ka = key(a);
        String kb = key(b);
        if (ka == null || kb == null) return false;
        String m = ka + kb;
        if (m.length() < 4 || m.length() > MAX_LEN) return false;
        boolean sa = standalone(ka);
        boolean sb = standalone(kb);
        if (sa && sb) return false; // كلمتان صحيحتان: لا نخلط جملة سليمة
        if (!known(m)) return false;
        // أداة تعريف + حرف أو حرفان (الم، الت، والت، بالم) ثم قطعة ليست كلمة: الم ريض -> المريض. الكلمة الصحيحة بعدها تمنع الوصل.
        if (!sb && isArticleFragment(ka)) return true;
        if (sa) {
            // الأول كلمة صحيحة والثاني قطعة: لاحقة صرفية فقط (عضل + ات). الحرف المفرد من القائمة فقط، وبعد 3 أحرف فأكثر،
            // وبعد كلمة لا تدل أن ما بعدها رمز بذاته (النقطة ب).
            if (!SUFFIX_FRAG.contains(kb)) return false;
            if (ka.length() < 3) return false;
            return !SpeechAuditor.isLetterContext(ka);
        }
        if (sb) {
            // الأول قطعة (ا، ال، الم، مف) والثاني كلمة صحيحة: بادئة قصيرة.
            return ka.length() <= 3 && m.length() >= 5 && kb.length() >= 3;
        }
        // الجزءان ليسا كلمتين (الت + مارين، عض + لة): الناتج كلمة صحيحة 5 أحرف فأكثر
        return m.length() >= 5;
    }

    // ------------------------------------------------------------------ 2) فصل الملتصق

    /**
     * يفصل الرموز الملتصقة (فيالمرحلة -> في + المرحلة) ويحفظ موضع كل جزء في النص الأصلي.
     * تُعالَج فقط الرموز العربية الخالصة (بلا تشكيل) وقد يلحقها ترقيم في آخرها.
     */
    static void splitGlued(List<Integer> starts, List<String> toks) {
        if (!enabled || !TashkeelDict.isReady()) return;
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (t.length() < 7) continue;
            int end = t.length();
            while (end > 0 && !isLetter(t.charAt(end - 1))) end--;
            if (end < 7 || end > MAX_LEN) continue;
            boolean pure = true;
            for (int k = 0; k < end; k++) {
                if (!isLetter(t.charAt(k))) {
                    pure = false;
                    break;
                }
            }
            if (!pure) continue;
            String core = t.substring(0, end);
            String ck = WordVerifier.normKey(core);
            if (known(ck)) continue; // كلمة صحيحة كما هي
            for (String h : GLUED_HEADS) {
                if (!core.startsWith(h)) continue;
                String rest = core.substring(h.length());
                if (rest.length() < 4) continue;
                if (!known(WordVerifier.normKey(rest))) continue;
                int s0 = starts.get(i);
                toks.set(i, h);
                toks.add(i + 1, t.substring(h.length()));
                starts.add(i + 1, s0 + h.length());
                break;
            }
        }
    }

    static List<String> headsForTest() {
        return Arrays.asList(GLUED_HEADS);
    }
}
