package com.ast2012a.clinicalmaster;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * محلّل الجملة قبل النطق: يرى الجملة كاملة (لا كلمة كلمة) ثم يقرّر تشكيل كل كلمة بحسب موقعها وجيرانها.
 *
 * يعمل بعد أن تُجهَّز كل كلمة على حدة في {@link SpeechPrep} وقبل ضبط الأواخر، وله ثلاث مهام:
 *
 *  1) إصلاح تشكيل الـ PDF نفسه: كثير من الملفات تأتي مشكولة جزئيًا أو بتشكيل مزاح/مكرَّر/مستحيل نطقًا
 *     (سكونان متتاليان، حركة على ألف المدّ، واو ساكنة بعد كسرة...). الكلمة المشكولة بكثافة كانت تُنطق كما خرجت
 *     حتى لو فسدت، والمشكولة جزئيًا بأكثر من علامتين كانت تُترك ناقصة. الآن: تُنظَّف البنية، فإن كانت مستحيلة
 *     صوتيًا أُعيد تشكيلها من القاموس، وإن كانت ناقصة أُكملت من القاموس بشرط ألا تخالف أي علامة في الملف،
 *     وإلا أكملها نموذج الحروف ({@link LetterModel#complete}) بعلاماته المثبّتة.
 *
 *  2) فهم السياق: الكلمة الملتبسة (كتب: كُتُب/كَتَبَ، ضغط: ضَغْط/ضَغَطَ، أنّ/أنْ، لكنّ/لكنْ...) تُشكَّل بحسب ما قبلها
 *     وما بعدها: بعد حرف جر أو أداة تعريف اسمٌ، وبعد لم/لن/قد/ضمير فعلٌ. لا نتدخل إلا لو كان تشكيل القاموس الحالي
 *     هو الافتراضي (لا نمس ما جاء من الملف ولا ما علّمه المستخدم ولا قاموسه اليدوي).
 *
 *  3) التعلّم من السياق: كل كلمة مشكولة في ملف تُحفظ مع \"صنف سياقها\" (اسمي/فعلي/غيرهما)، فإذا ظهرت لاحقًا بلا تشكيل
 *     في سياق من الصنف نفسه نُطقت بالتشكيل المتعلَّم. وما يعلّمه المستخدم من مختبر النطق (قاموس الحروف) يدخل الذاكرة نفسها
 *     بوزن أعلى ويغذّي نموذج الحروف.
 *
 * كل شيء محلي بلا إنترنت، وكل خطأ يُبتلع (تبقى الكلمة كما جاءت)، وكل تعديل يُتحقَّق منه: الحروف مطابقة حرفًا بحرف
 * والبنية سليمة وإلا يُترك.
 */
final class SentenceShaper {

    private SentenceShaper() {
    }

    private static final char SHADDA = '\u0651';
    private static final char SUKUN = '\u0652';
    private static final char FATHA = '\u064E';
    private static final char DAMMA = '\u064F';
    private static final char KASRA = '\u0650';
    private static final char FATHATAN = '\u064B';
    private static final char KASRATAN = '\u064D';
    private static final char ALEF = '\u0627';
    private static final char LAM = '\u0644';
    private static final char WAW = '\u0648';
    private static final char YAA = '\u064A';

    static final int CUE_NONE = 0, CUE_NOUN = 1, CUE_VERB = 2;

    private static final String STOPS = ".!?\u061F\u061B:";
    private static final String SOFT = ",\u060C;";
    private static final String PUNCT = ".,;:!?\u060C\u061B\u061F";

    // ------------------------------------------------------------------ مفاتيح وكلمات السياق

    /** مفتاح موحّد للمقارنة: بلا علامات ولا تطويل، والهمزات على الألف ألف، والألف المقصورة ياء. */
    static String ukey(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = SpeechAuditor.unifyLetter(s.charAt(i));
            if (ArabicPhonetics.isMark(c) || c == '\u0640') continue;
            if (c == '\u0623' || c == '\u0625' || c == '\u0622' || c == '\u0671') c = ALEF;
            else if (c == '\u0649') c = YAA;
            sb.append(c);
        }
        return sb.toString();
    }

    private static Set<String> keys(String... words) {
        Set<String> s = new HashSet<>();
        for (String w : words) s.add(ukey(w));
        return s;
    }

    /** بعدها اسم (مجرور أو مضاف إليه). */
    private static final Set<String> NOUN_PREV = keys("في", "من", "إلى", "على", "عن", "مع", "حتى", "خلال", "عبر",
            "بعد", "قبل", "حول", "نحو", "لدى", "عند", "ضد", "بين", "تحت", "فوق", "دون", "كل", "بعض", "هذا", "هذه",
            "ذلك", "تلك", "هؤلاء", "ذو", "ذات", "أي", "أحد", "إحدى", "غير", "مثل", "عدم");

    /** بعدها فعل مضارع غالبًا. */
    private static final Set<String> VERB_PREV = keys("لم", "لن", "قد", "سوف", "كي", "لكي", "يجب", "يمكن", "يستطيع",
            "يتم", "لقد", "هو", "هي", "هم", "هن", "نحن", "أنا", "أنت", "أنتم", "ينبغي", "يحتاج", "تستطيع");

    /** أفعال مضارعة شائعة (لتمييز \"أنْ\" + فعل من \"أنَّ\" + اسم). */
    private static final Set<String> VERB_LEX = keys("يكون", "تكون", "نكون", "أكون", "يتم", "تتم", "يمكن", "تمكن",
            "يجب", "ينبغي", "يعمل", "تعمل", "يؤدي", "تؤدي", "يؤثر", "تؤثر", "يستخدم", "تستخدم", "يساعد", "تساعد",
            "يزيد", "تزيد", "يقلل", "تقلل", "يحدث", "تحدث", "يعتمد", "تعتمد", "يتكون", "تتكون", "يسبب", "تسبب",
            "يتحسن", "تتحسن", "يشعر", "تشعر", "يستطيع", "تستطيع", "يحتاج", "تحتاج", "يتعلم", "يقوم", "تقوم",
            "يبدأ", "تبدأ", "يتجنب", "يظهر", "تظهر", "يقل", "تقل", "يزداد", "تزداد", "يخفف", "تخفف", "يحسن", "تحسن",
            "يتناول", "يستمر", "تستمر", "يرتفع", "ترتفع", "ينخفض", "تنخفض", "يتحرك", "تتحرك", "يمارس", "تمارس");

    private static final Set<String> NOMINAL_NEXT = keys("هذا", "هذه", "ذلك", "تلك", "هو", "هي", "هم", "نحن", "أنا",
            "هؤلاء", "كل", "بعض");

    // ------------------------------------------------------------------ الكلمات الملتبسة (اسم / فعل)

    private static final Map<String, String[]> HOMO = new HashMap<>();

    private static void h(String plain, String noun, String verb) {
        HOMO.put(ukey(plain), new String[]{noun, verb});
    }

    /** فَعْل / فَعَلَ المطّردة. */
    private static void reg(String plain) {
        char a = plain.charAt(0);
        char b = plain.charAt(1);
        char c = plain.charAt(2);
        h(plain, "" + a + FATHA + b + SUKUN + c, "" + a + FATHA + b + FATHA + c + FATHA);
    }

    static {
        String[] regular = {"درس", "حكم", "ضغط", "فحص", "وضع", "نقل", "جمع", "مسح", "رفع", "نظر", "ربط", "منع",
                "قطع", "فتح", "نشر", "غسل", "ضبط", "خفض", "حمل", "شرح", "طلب", "زرع", "أمر", "بدء", "قصد", "لمس",
                "شحن", "دفع", "سحب", "ضرب", "مدّ", "حفظ", "قيس", "نسخ"};
        for (String w : regular) {
            if (w.length() == 3 && !w.contains("\u0651")) reg(w);
        }
        h("كتب", "\u0643\u064F\u062A\u064F\u0628", "\u0643\u064E\u062A\u064E\u0628\u064E");
        h("علم", "\u0639\u0650\u0644\u0652\u0645", "\u0639\u064E\u0644\u0650\u0645\u064E");
        h("عمل", "\u0639\u064E\u0645\u064E\u0644", "\u0639\u064E\u0645\u0650\u0644\u064E");
        h("شكل", "\u0634\u064E\u0643\u0652\u0644", "\u0634\u064E\u0643\u0651\u064E\u0644\u064E");
        h("سبب", "\u0633\u064E\u0628\u064E\u0628", "\u0633\u064E\u0628\u0651\u064E\u0628\u064E");
        h("أثر", "\u0623\u064E\u062B\u064E\u0631", "\u0623\u064E\u062B\u0651\u064E\u0631\u064E");
        h("عدد", "\u0639\u064E\u062F\u064E\u062F", "\u0639\u064E\u062F\u0651\u064E\u062F\u064E");
        h("قدم", "\u0642\u064E\u062F\u064E\u0645", "\u0642\u064E\u062F\u0651\u064E\u0645\u064E");
        h("قرر", "\u0642\u064E\u0631\u0651\u064E\u0631", "\u0642\u064E\u0631\u0651\u064E\u0631\u064E");
        h("حدد", "\u062D\u064E\u062F\u0651", "\u062D\u064E\u062F\u0651\u064E\u062F\u064E");
        h("سجل", "\u0633\u0650\u062C\u0650\u0644\u0651", "\u0633\u064E\u062C\u0651\u064E\u0644\u064E");
        h("رقم", "\u0631\u064E\u0642\u0652\u0645", "\u0631\u064E\u0642\u064E\u0645\u064E");
        h("حل", "\u062D\u064E\u0644\u0651", "\u062D\u064E\u0644\u0651\u064E");
    }

    // ------------------------------------------------------------------ ذاكرة السياق (تعلّم)

    private static final String FILE_NAME = "sentence_memory.txt";
    private static final int MAX_ENTRIES = 5000;
    private static final long SAVE_EVERY_MS = 20_000L;
    private static final Object LOCK = new Object();
    private static File file;
    private static boolean loaded;
    private static boolean dirty;
    private static long lastSave;

    /** مفتاح: ukey|cue  ->  [التشكيل, العدد]. */
    private static final Map<String, String[]> memory = new LinkedHashMap<String, String[]>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String[]> e) {
            return size() > MAX_ENTRIES;
        }
    };

    /** يُنادى مرة عند بدء القارئ (بجانب SpeechLearner.init). */
    static void init(File dir) {
        synchronized (LOCK) {
            if (loaded || dir == null) return;
            file = new File(dir, FILE_NAME);
            try {
                if (file.exists()) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            String[] p = line.split("\t");
                            if (p.length < 5 || !"M".equals(p[0])) continue;
                            memory.put(p[1] + "|" + p[2], new String[]{p[3], p[4]});
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
            loaded = true;
        }
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
        if (file == null || (!dirty && !force)) return;
        lastSave = System.currentTimeMillis();
        File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"))) {
            for (Map.Entry<String, String[]> e : memory.entrySet()) {
                int bar = e.getKey().lastIndexOf('|');
                w.write("M\t" + e.getKey().substring(0, bar) + "\t" + e.getKey().substring(bar + 1) + "\t"
                        + e.getValue()[0] + "\t" + e.getValue()[1] + "\n");
            }
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            tmp.renameTo(file);
        }
        dirty = false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** يسجّل أن الكلمة (بمفتاحها) نُطقت بهذا التشكيل في هذا الصنف من السياق. weight: 1 ظهور في ملف، 3+ تعليم مباشر. */
    private static void remember(String key, int cue, String form, int weight) {
        if (key.length() < 3 || form == null) return;
        synchronized (LOCK) {
            String k = key + "|" + cue;
            String[] cur = memory.get(k);
            if (cur == null) {
                memory.put(k, new String[]{form, String.valueOf(weight)});
            } else if (cur[0].equals(form)) {
                cur[1] = String.valueOf(Math.min(1000, num(cur[1]) + weight));
            } else {
                int c = num(cur[1]) - weight;
                if (c <= 0) memory.put(k, new String[]{form, String.valueOf(weight)});
                else cur[1] = String.valueOf(c);
            }
            touch();
        }
    }

    private static String recall(String key, int cue) {
        synchronized (LOCK) {
            String[] cur = memory.get(key + "|" + cue);
            return cur != null && num(cur[1]) >= 1 ? cur[0] : null;
        }
    }

    static int memorySize() {
        synchronized (LOCK) {
            return memory.size();
        }
    }

    static void resetMemory() {
        synchronized (LOCK) {
            memory.clear();
            touch();
        }
    }

    // ------------------------------------------------------------------ أدوات الرمز

    /** رمز مقسوم: ما قبل الكلمة العربية، الكلمة (حروف وعلامات)، ما بعدها. arabic=false لو ليست كلمة عربية صرفة. */
    private static final class Tok {
        String lead = "";
        String core = "";
        String trail = "";
        String plain = "";
        int marks;
        boolean arabic;
    }

    private static boolean isLetter(char c) {
        return (c >= 0x0621 && c <= 0x064A) || c == 0x0671;
    }

    private static Tok split(String s) {
        Tok t = new Tok();
        if (s == null || s.isEmpty()) return t;
        int f = -1;
        int l = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isLetter(c)) {
                if (f < 0) f = i;
                l = i;
            } else if (f >= 0 && (ArabicPhonetics.isMark(c) || c == '\u0640')) {
                l = i;
            }
        }
        if (f < 0) {
            t.lead = s;
            return t;
        }
        t.lead = s.substring(0, f);
        t.core = s.substring(f, l + 1);
        t.trail = s.substring(l + 1);
        StringBuilder p = new StringBuilder(t.core.length());
        for (int i = 0; i < t.core.length(); i++) {
            char c = t.core.charAt(i);
            if (ArabicPhonetics.isMark(c)) t.marks++;
            else if (c != '\u0640') {
                if (!isLetter(c)) return t; // حرف غريب داخل الكلمة: لا نمسّها
                p.append(c);
            }
        }
        for (int i = 0; i < t.lead.length(); i++) if (Character.isLetterOrDigit(t.lead.charAt(i))) return t;
        for (int i = 0; i < t.trail.length(); i++) if (Character.isLetterOrDigit(t.trail.charAt(i))) return t;
        t.plain = p.toString();
        t.arabic = t.plain.length() >= 1;
        return t;
    }

    private static boolean hasAny(String s, String set) {
        for (int i = 0; i < s.length(); i++) if (set.indexOf(s.charAt(i)) >= 0) return true;
        return false;
    }

    private static String stripMarks(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!ArabicPhonetics.isMark(c) && c != '\u0640') sb.append(c);
        }
        return sb.toString();
    }

    /** الكلمة بلا علامة الإعراب الأخيرة (للمقارنة بين تشكيلين يختلفان بآخر الكلمة فقط). */
    private static String stem(String w) {
        int e = w.length();
        while (e > 0 && ArabicPhonetics.isMark(w.charAt(e - 1)) && w.charAt(e - 1) != SHADDA) e--;
        return w.substring(0, e);
    }

    private static boolean sameStem(String a, String b) {
        return stem(ArabicPhonetics.orderMarks(a)).equals(stem(ArabicPhonetics.orderMarks(b)));
    }

    /** تشكيل القاموس الافتراضي لكلمة مجرّدة (طبي ثم المحلي)، أو null. */
    private static String dictShape(String plain) {
        if (plain == null || plain.length() < 3) return null;
        String f = null;
        try {
            f = ArabicPhonetics.lookup(plain);
            if (f == null) f = TashkeelDict.lookup(plain);
        } catch (RuntimeException ignored) {
        }
        return f;
    }

    private static boolean nominalShape(String key) {
        if (key.length() >= 5 && key.startsWith("\u0627\u0644")) return true;
        if (key.length() >= 6 && "\u0648\u0641".indexOf(key.charAt(0)) >= 0 && key.startsWith("\u0627\u0644", 1)) return true;
        if (key.length() >= 6 && "\u0628\u0643\u0644".indexOf(key.charAt(0)) >= 0 && key.startsWith("\u0627\u0644", 1)) return true;
        if (key.length() >= 7 && "\u0648\u0641".indexOf(key.charAt(0)) >= 0
                && "\u0628\u0643\u0644".indexOf(key.charAt(1)) >= 0 && key.startsWith("\u0627\u0644", 2)) return true;
        if (key.length() >= 5 && key.startsWith("\u0644\u0644")) return true;
        return key.length() >= 4 && key.charAt(key.length() - 1) == '\u0629';
    }

    /** يُسقط واو/فاء العطف من الأول لو كان الباقي من مفردات القائمة (وفي، فمن). */
    private static String inSet(Set<String> set, String key) {
        if (set.contains(key)) return key;
        if (key.length() > 2 && (key.charAt(0) == WAW || key.charAt(0) == '\u0641') && set.contains(key.substring(1))) {
            return key.substring(1);
        }
        return null;
    }

    // ------------------------------------------------------------------ التقاط شرح التحليل (لمختبر النطق)

    private static final ThreadLocal<List<String[]>> CAPTURE = new ThreadLocal<>();

    /** يبدأ التقاط ما يقرّره المحلّل لكل كلمة في هذا الخيط (لشاشة مختبر النطق). */
    static void beginCapture() {
        CAPTURE.set(new ArrayList<String[]>());
    }

    /** ينهي الالتقاط ويعيد صفوفًا: [الكلمة كما في النص, نطقها النهائي, مصدر القرار]. */
    static List<String[]> endCapture() {
        List<String[]> r = CAPTURE.get();
        CAPTURE.remove();
        return r == null ? new ArrayList<String[]>() : r;
    }

    private static void note(String word, String spoken, String why) {
        List<String[]> c = CAPTURE.get();
        if (c != null) c.add(new String[]{word, spoken, why});
    }

    // ------------------------------------------------------------------ نقطة الدخول

    static void refine(List<String> toks, String[] sps) {
        refine(toks, sps, null);
    }

    /**
     * يحلّل الجملة ويعدّل sps في مكانها. toks = الكلمات بعد التهيئة، sps = نطق كل كلمة، locked[t] = الكلمة تحت سيطرة
     * المستخدم (قاموسه اليدوي أو ما علّمه) فلا تُمسّ. أي خطأ في كلمة يتركها كما هي.
     */
    static void refine(List<String> toks, String[] sps, boolean[] locked) {
        int n = toks.size();
        if (n == 0 || sps == null || sps.length < n) return;
        Tok[] o = new Tok[n];
        Tok[] s = new Tok[n];
        boolean[] ok = new boolean[n];
        for (int i = 0; i < n; i++) {
            o[i] = split(toks.get(i));
            s[i] = split(sps[i] == null ? "" : sps[i]);
            ok[i] = o[i].arabic && s[i].arabic && o[i].plain.length() >= 2
                    && WordVerifier.sameLetters(o[i].plain, s[i].plain);
        }
        for (int i = 0; i < n; i++) {
            if (!ok[i]) continue;
            try {
                int cue = cueAt(o, s, ok, i);
                int nxt = nextClass(o, s, ok, i);
                String out = decide(o[i], s[i], cue, nxt, locked != null && locked[i]);
                if (out != null && !out.equals(s[i].core)) {
                    sps[i] = s[i].lead + out + s[i].trail;
                }
            } catch (RuntimeException ignored) {
                // أي خطأ: تبقى الكلمة كما جهّزها SpeechPrep
            }
        }
    }

    private static boolean boundaryAfter(Tok o, Tok s) {
        return hasAny(o.trail, STOPS) || hasAny(s.trail, STOPS);
    }

    private static boolean softAfter(Tok o, Tok s) {
        return hasAny(o.trail, SOFT) || hasAny(s.trail, SOFT);
    }

    /** صنف السياق قبل الكلمة i: اسمي لو سبقها حرف جر/أداة اسمية أو كانت هي اسمية الشكل، فعلي لو سبقها جازم/ضمير. */
    private static int cueAt(Tok[] o, Tok[] s, boolean[] ok, int i) {
        String key = ukey(o[i].plain);
        if (nominalShape(key)) return CUE_NOUN;
        int p = i - 1;
        while (p >= 0 && !ok[p] && s[p].plain.isEmpty() && o[p].plain.isEmpty()) p--;
        if (p < 0) return CUE_NONE;
        if (boundaryAfter(o[p], s[p]) || softAfter(o[p], s[p])) return CUE_NONE;
        if (!ok[p]) return CUE_NONE;
        String pk = ukey(o[p].plain);
        if (inSet(NOUN_PREV, pk) != null) return CUE_NOUN;
        if (inSet(VERB_PREV, pk) != null) return CUE_VERB;
        return CUE_NONE;
    }

    /** ما بعد الكلمة i: اسمي (بعدها اسم أو ضمير)، فعلي (فعل مضارع معروف)، أو غير ذلك. */
    private static int nextClass(Tok[] o, Tok[] s, boolean[] ok, int i) {
        if (boundaryAfter(o[i], s[i]) || softAfter(o[i], s[i])) return CUE_NONE;
        int q = i + 1;
        while (q < o.length && !ok[q] && s[q].plain.isEmpty() && o[q].plain.isEmpty()) q++;
        if (q >= o.length || !ok[q]) return CUE_NONE;
        String qk = ukey(o[q].plain);
        if (nominalShape(qk) || NOMINAL_NEXT.contains(qk)) return CUE_NOUN;
        if (VERB_LEX.contains(qk)) return CUE_VERB;
        return CUE_NONE;
    }

    // ------------------------------------------------------------------ قرار الكلمة

    /** يعيد نص الكلمة (بلا علامات الترقيم) الذي يجب نطقه، أو null/نفس الحالي لو لا تغيير. */
    private static String decide(Tok o, Tok s, int cue, int next, boolean locked) {
        String plain = o.plain;
        String key = ukey(plain);
        if (locked) {
            note(o.core, s.core, "بيد المستخدم");
            return null;
        }

        // 1) الكلمة مشكولة في الـ PDF: نتعلّم منها ثم نصلح ما فسد
        if (o.marks > 0) {
            boolean unchanged = s.core.equals(o.core);
            if (!unchanged) {
                note(o.core, s.core, "أُكمل من القاموس بما يوافق علامات الملف");
                learnFrom(o.core, key, cue);
                return null;
            }
            String[] rr = reconcile(o.core, plain);
            if (rr == null) {
                note(o.core, s.core, "تشكيل الملف كما هو");
                learnFrom(o.core, key, cue);
                return null;
            }
            note(o.core, rr[0], rr[1]);
            learnFrom(rr[0], key, cue);
            return rr[0];
        }

        // 2) كلمة بلا تشكيل في الملف: ذاكرة السياق، ثم الأدوات الملتبسة، ثم ما حدّده القاموس
        String cur = s.core;
        boolean curMarked = !cur.equals(stripMarks(cur));
        // مقارنة تشكيل القاموس مكلفة نسبيًا: لا نجريها إلا لكلمة قد يغيّرها السياق
        boolean maybe = cue != CUE_NONE && (HOMO.containsKey(key)
                || (key.length() == 4 && (key.charAt(0) == WAW || key.charAt(0) == '\u0641') && HOMO.containsKey(key.substring(1)))
                || recall(key, cue) != null);
        boolean dictDefault = maybe && isDictionaryDerived(plain, cur);

        if (cue != CUE_NONE && (dictDefault || !curMarked)) {
            String mem = recall(key, cue);
            if (mem != null && WordVerifier.sameLetters(plain, mem) && WordVerifier.wellFormed(mem)) {
                String m = withStop(mem, o, s);
                note(o.core, m, "تعلّمه من سياق مشابه");
                return m;
            }
        }

        String[] hm = HOMO.get(key);
        if (hm != null && cue != CUE_NONE && (dictDefault || !curMarked || sameStem(cur, hm[0]) || sameStem(cur, hm[1]))) {
            String pick = withStop(cue == CUE_NOUN ? hm[0] : hm[1], o, s);
            if (WordVerifier.sameLetters(plain, pick) && WordVerifier.wellFormed(pick)) {
                note(o.core, pick, cue == CUE_NOUN ? "اسم بحسب السياق" : "فعل بحسب السياق");
                return pick;
            }
        }

        String particle = particle(plain, next, s, o);
        if (particle != null) {
            note(o.core, particle, "أداة بحسب ما بعدها");
            return particle;
        }

        // و/ف + كلمة ملتبسة (وضغط، فنقل)
        if (key.length() == 4 && (key.charAt(0) == WAW || key.charAt(0) == '\u0641') && cue != CUE_NONE) {
            String[] hm2 = HOMO.get(key.substring(1));
            if (hm2 != null && (dictDefault || !curMarked)) {
                char conj = plain.charAt(0);
                String form = conj + "" + FATHA + (cue == CUE_NOUN ? hm2[0] : hm2[1]);
                form = withStop(form, o, s);
                if (WordVerifier.sameLetters(plain, form) && WordVerifier.wellFormed(form)) {
                    note(o.core, form, cue == CUE_NOUN ? "اسم بحسب السياق" : "فعل بحسب السياق");
                    return form;
                }
            }
        }

        note(o.core, cur, curMarked ? "من القاموس أو مما تعلّمه" : "بلا تشكيل (يقدّره المحرك)");
        return null;
    }

    private static boolean isDictionaryDerived(String plain, String cur) {
        if (cur.equals(plain)) return true;
        String d = dictShape(plain);
        return d != null && sameStem(cur, d);
    }

    private static String withStop(String form, Tok o, Tok s) {
        boolean stop = hasAny(o.trail, PUNCT) || hasAny(s.trail, PUNCT);
        return stop ? ArabicPhonetics.pausal(form) : form;
    }

    /** أنّ/أنْ، إنّ/إنْ، لكنّ/لكنْ بحسب ما بعدها (اسم أو فعل مضارع معروف). */
    private static String particle(String plain, int next, Tok s, Tok o) {
        if (next == CUE_NONE) return null;
        String cur = s.core;
        boolean fresh = cur.equals(plain) || stem(ArabicPhonetics.orderMarks(cur)).equals(stem(plain))
                || cur.equals("\u0623\u064E\u0646\u0651\u064E") || cur.equals("\u0623\u064E\u0646\u0652")
                || cur.equals("\u0625\u0650\u0646\u0651\u064E") || cur.equals("\u0625\u0650\u0646\u0652")
                || cur.equals("\u0644\u064E\u0643\u0650\u0646\u0651\u064E") || cur.equals("\u0644\u064E\u0643\u0650\u0646\u0652");
        if (!fresh) return null;
        String r = null;
        if (plain.equals("\u0623\u0646") || plain.equals("\u0627\u0646")) {
            r = next == CUE_NOUN ? "\u0623\u064E\u0646\u0651\u064E" : "\u0623\u064E\u0646\u0652";
        } else if (plain.equals("\u0625\u0646")) {
            r = next == CUE_NOUN ? "\u0625\u0650\u0646\u0651\u064E" : "\u0625\u0650\u0646\u0652";
        } else if (plain.equals("\u0644\u0643\u0646") && next == CUE_NOUN) {
            r = "\u0644\u064E\u0643\u0650\u0646\u0651\u064E";
        }
        return r;
    }

    // ------------------------------------------------------------------ إصلاح تشكيل الملف

    /**
     * يفحص كلمة مشكولة من الـ PDF. يعيد النص المُصلَح، أو null لو لا يلزم تغيير.
     * الحالات: بنية مرتّبة/مكرّرة -> تُرتَّب؛ مستحيلة نطقًا -> تُعاد من القاموس (أو تُجرَّد)؛ ناقصة -> تُكمَل من القاموس
     * ثم من نموذج الحروف بعلاماتها المثبّتة.
     */
    private static String[] reconcile(String core, String plain) {
        String c = ArabicPhonetics.orderMarks(core);
        c = ArabicPhonetics.relocateTanween(c);
        boolean reordered = !c.equals(core);

        if (!WordVerifier.wellFormed(c) || impossible(c)) {
            String d = dictShape(plain);
            if (d != null && WordVerifier.sameLetters(plain, d)) {
                return new String[]{d, "تشكيل الملف غير صالح للنطق فأُعيد من القاموس"};
            }
            return new String[]{plain, "تشكيل الملف غير صالح للنطق فأُسقط"};
        }

        int missing = missingMarks(c);
        if (missing > 0) {
            String full = dictShape(plain);
            if (full != null && WordVerifier.sameLetters(plain, full) && marksFitIn(c, full)) {
                return new String[]{full, "ناقص التشكيل فأُكمل من القاموس بما يوافق الملف"};
            }
            String comp = null;
            try {
                comp = LetterModel.complete(c);
            } catch (RuntimeException ignored) {
            }
            if (comp != null && WordVerifier.sameLetters(plain, comp) && WordVerifier.wellFormed(comp)
                    && marksFitIn(c, comp) && !impossible(comp)) {
                return new String[]{comp, "ناقص التشكيل فأكمله نموذج الحروف بما يوافق الملف"};
            }
        }
        if (reordered) return new String[]{c, "رُتّبت علامات الملف"};
        return null;
    }

    private static boolean isMadd(char ch) {
        return ch == ALEF || ch == '\u0649' || ch == WAW || ch == YAA;
    }

    /** يفكّك كلمة مشكولة: letters[i] وعلامات الحرف i (vowel: حركة/سكون/تنوين أو 0، shadda). */
    private static final class Parsed {
        char[] ch;
        char[] vowel;
        boolean[] shadda;
        int n;
    }

    private static Parsed parse(String w) {
        Parsed p = new Parsed();
        int cap = w.length();
        p.ch = new char[cap];
        p.vowel = new char[cap];
        p.shadda = new boolean[cap];
        int n = -1;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (ArabicPhonetics.isMark(c)) {
                if (n < 0) continue;
                if (c == SHADDA) p.shadda[n] = true;
                else if (c >= FATHATAN && c <= SUKUN) p.vowel[n] = c;
            } else if (c != '\u0640') {
                p.ch[++n] = c;
            }
        }
        p.n = n + 1;
        return p;
    }

    /**
     * مستحيل نطقًا: ساكن في أول الكلمة، حرفان ساكنان متتاليان صراحةً (خارج الآخر)، حركة قصيرة على ألف مدّ وسط الكلمة،
     * شدّة مع سكون، واو ساكنة بعد كسرة، ياء ساكنة بعد ضمة.
     */
    static boolean impossible(String w) {
        Parsed p = parse(w);
        for (int i = 0; i < p.n; i++) {
            char ch = p.ch[i];
            char v = p.vowel[i];
            if (i == 0 && v == SUKUN) return true;
            if (p.shadda[i] && v == SUKUN) return true;
            if (v == SUKUN && i > 0 && p.vowel[i - 1] == SUKUN && i < p.n - 1) return true;
            if (ch == ALEF && i > 0 && (v == FATHA || v == DAMMA || v == KASRA)) return true;
            if (v == SUKUN && i > 0) {
                char pv = p.vowel[i - 1];
                if (ch == WAW && pv == KASRA) return true;
                if (ch == YAA && pv == DAMMA) return true;
            }
        }
        return false;
    }

    /** عدد الحروف التي ينقصها تشكيل (غير آخر الكلمة، وغير مدّ ظاهر، وغير لام \"ال\"). */
    static int missingMarks(String w) {
        Parsed p = parse(w);
        int start = 0;
        // لام \"ال\" وألفها وما قبلهما من سوابق لا يُطلب لها تشكيل صريح هنا
        for (int i = 0; i + 1 < p.n; i++) {
            if (p.ch[i] == ALEF && p.ch[i + 1] == LAM && (i == 0 || i <= 3)) {
                start = i + 2;
                break;
            }
        }
        int miss = 0;
        for (int i = 0; i < p.n - 1; i++) {
            if (i < start) continue;
            if (p.vowel[i] != 0 || p.shadda[i]) continue;
            char ch = p.ch[i];
            if (isMadd(ch)) {
                char pv = i > 0 ? p.vowel[i - 1] : 0;
                if (ch == ALEF || ch == '\u0649') continue;
                if (ch == WAW && pv == DAMMA) continue;
                if (ch == YAA && pv == KASRA) continue;
            }
            if (ch == '\u0629' && i == p.n - 1) continue;
            miss++;
        }
        return miss;
    }

    private static boolean alefLike(char c) {
        return c == ALEF || c == '\u0623' || c == '\u0625' || c == '\u0622' || c == '\u0671';
    }

    private static List<String> letterMarks(String w) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (ArabicPhonetics.isMark(c)) {
                if (cur != null) cur.append(c);
            } else if (c != '\u0640') {
                if (cur != null) out.add(cur.toString());
                cur = new StringBuilder();
                cur.append(c);
            }
        }
        if (cur != null) out.add(cur.toString());
        return out;
    }

    /** كل علامة في part موجودة على الحرف نفسه في full، وحروفهما واحدة (الهمزات متسامح فيها). */
    static boolean marksFitIn(String part, String full) {
        List<String> a = letterMarks(part);
        List<String> b = letterMarks(full);
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i);
            String y = b.get(i);
            if (alefLike(x.charAt(0)) != alefLike(y.charAt(0))) return false;
            if (!alefLike(x.charAt(0)) && x.charAt(0) != y.charAt(0)) return false;
            for (int k = 1; k < x.length(); k++) if (y.indexOf(x.charAt(k)) < 0) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ التعلّم

    private static void learnFrom(String shapedCore, String key, int cue) {
        try {
            if (key.length() < 3 || cue == CUE_NONE) return;
            if (!WordVerifier.wellFormed(shapedCore) || impossible(shapedCore)) return;
            if (missingMarks(shapedCore) > 0) return; // ناقص: لا نتعلّم منه
            String form = ArabicPhonetics.pausal(shapedCore);
            if (!WordVerifier.sameLetters(stripMarks(shapedCore), form)) return;
            remember(key, cue, form, 1);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * تعليم المستخدم من مختبر النطق: \"sentence\" الجملة التي كُتبت، و\"shaped\" الكلمة بتشكيلها الصحيح.
     * يجد الكلمة في الجملة ويحسب صنف سياقها ويحفظها بوزن عالٍ، ويغذّي نموذج الحروف.
     * يعيد وصفًا قصيرًا لما حدث (للعرض)، أو null لو لم تُوجد الكلمة في الجملة.
     */
    static String teach(String sentence, String shaped) {
        if (sentence == null || shaped == null) return null;
        String sh = shaped.trim();
        String plain = stripMarks(sh);
        if (plain.length() < 3 || !WordVerifier.wellFormed(sh)) return null;
        String[] words = sentence.trim().split("\\s+");
        List<String> toks = new ArrayList<>(Arrays.asList(words));
        int n = toks.size();
        Tok[] o = new Tok[n];
        Tok[] s = new Tok[n];
        boolean[] ok = new boolean[n];
        for (int i = 0; i < n; i++) {
            o[i] = split(toks.get(i));
            s[i] = o[i];
            ok[i] = o[i].arabic;
        }
        int idx = -1;
        for (int i = 0; i < n && idx < 0; i++) {
            if (ok[i] && WordVerifier.sameLetters(o[i].plain, plain)) idx = i;
        }
        if (idx < 0) return null;
        int cue = cueAt(o, s, ok, idx);
        String key = ukey(plain);
        String form = ArabicPhonetics.pausal(sh);
        String r;
        if (cue == CUE_NONE) {
            SpeechLearner.teach(plain, sh);
            r = "حُفظ لهذه الكلمة في كل موضع";
        } else {
            remember(key, cue, form, 4);
            r = cue == CUE_NOUN ? "حُفظ لها في مثل هذا السياق (بعد حرف جر أو أداة اسمية)"
                    : "حُفظ لها في مثل هذا السياق (بعد جازم أو ضمير فعل)";
        }
        try {
            LetterModel.learnUser(sh, 6);
        } catch (RuntimeException ignored) {
        }
        flush();
        return r;
    }

    static String stats() {
        return "sentenceShaper: ذاكرة سياق " + memorySize() + " كلمة";
    }
}
