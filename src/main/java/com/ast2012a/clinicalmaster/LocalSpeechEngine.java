package com.ast2012a.clinicalmaster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * محرك نطق محلي مكتوب من الصفر (بدون إنترنت، بدون مكتبات، بدون أي ملف نموذج، بدون حدود استخدام).
 *
 * هذا مُركِّب صوت "صيغي" (formant synthesizer على طريقة Klatt) يُولّد الموجة الصوتية بالكامل داخل الجهاز:
 *
 *  1) الواجهة اللغوية: نص عربي مشكَّل (يأتي جاهزًا من SpeechPrep/TashkeelDict) ← وحدات صوتية. تتعامل مع الشدّة
 *     والتنوين والمدود وأل الشمسية والقمرية وهمزة الوصل وواو الجماعة وتاء المربوطة والوقف على آخر الجملة،
 *     مع قراءة تقريبية للكلمات اللاتينية والأرقام.
 *  2) الإيقاع: مدد الحروف، موضع النبر (قاعدة النبر العربية)، نغمة الجملة (هبوط الإثبات، ارتفاع السؤال،
 *     استمرار الفاصلة)، تفخيم الحروف المطبقة (ص ض ط ظ ق) على الحركات المجاورة.
 *  3) مصدر الصوت: نبضة حنجرية (Rosenberg) مع اهتزاز طبيعي بسيط في الطبقة والسعة وتنفّس حسب الصوت.
 *  4) المرشّحات: سلسلة رنانات للصوائت والأنفيات + مسار متوازٍ للاحتكاك والانفجار.
 *
 * نظيف من أي اعتماد على أندرويد، وحتمي (نفس النص = نفس الصوت بالضبط) فيصلح للتخزين المؤقت. كل الدوال آمنة
 * للاستدعاء من أي خيط ولا تُبقي حالة مشتركة.
 *
 * ملاحظة صريحة: هذا ليس نموذج تعلّم عميق مدرَّبًا على تسجيلات بشرية؛ جودته "مركّبة" أقل طبيعية من الأصوات
 * العصبية الأونلاين، لكنه يعمل دائمًا وبلا اتصال.
 */
final class LocalSpeechEngine {

    static final int SAMPLE_RATE = 22050;
    private static final double DT = 1.0 / SAMPLE_RATE;

    // ------------------------------------------------------------------ الأصوات

    static final class Voice {
        final String id;
        final String label;
        final float f0;       // طبقة الصوت الأساسية (هرتز)
        final float fscale;   // مقياس الرنين (طول الحنجرة/المجرى الصوتي)
        final float oq;       // نسبة انفتاح الوترين
        final float closing;  // نسبة طور الإغلاق (الأصغر = أحدّ وأسطع)
        final float breath;   // نسبة التنفّس
        final float jitter;   // اهتزاز الطبقة

        Voice(String id, String label, float f0, float fscale, float oq, float closing, float breath, float jitter) {
            this.id = id;
            this.label = label;
            this.f0 = f0;
            this.fscale = fscale;
            this.oq = oq;
            this.closing = closing;
            this.breath = breath;
            this.jitter = jitter;
        }
    }

    static final Voice[] VOICES = {
            new Voice("salman", "سلمان · رجل", 112f, 1.00f, 0.58f, 0.26f, 0.05f, 0.004f),
            new Voice("yousef", "يوسف · شاب", 136f, 1.07f, 0.60f, 0.28f, 0.06f, 0.004f),
            new Voice("layla", "ليلى · امرأة", 205f, 1.17f, 0.66f, 0.34f, 0.12f, 0.003f),
            new Voice("hadi", "هادي · جهوري", 92f, 0.93f, 0.55f, 0.22f, 0.04f, 0.005f),
    };

    /** الصوت بمعرّفه (أو الأول لو لم يوجد). */
    static Voice voiceById(String id) {
        if (id != null) {
            for (Voice v : VOICES) if (v.id.equals(id)) return v;
        }
        return VOICES[0];
    }

    /** ناتج التركيب: موجة WAV + بدايات الكلمات (ms) ومواضعها في النص. */
    static final class Out {
        final byte[] wav;
        final int[] wordMs;
        final int[] wordChar;
        final int durationMs;

        Out(byte[] wav, int[] wordMs, int[] wordChar, int durationMs) {
            this.wav = wav;
            this.wordMs = wordMs;
            this.wordChar = wordChar;
            this.durationMs = durationMs;
        }
    }

    // ------------------------------------------------------------------ جدول الوحدات الصوتية

    private static final int V = 0, ST = 1, AFR = 2, FR = 3, NA = 4, LQ = 5, GL = 6, HG = 7, PH = 8, PV = 9, GS = 10, SIL = 11;

    private static final class P {
        String sym;
        int type;
        boolean voiced;
        boolean emph;      // مفخّم (ص ض ط ظ)
        boolean mild;      // تفخيم خفيف (ق، خ، غ)
        boolean velar;     // موضع الخلف يتبع الحركة المجاورة
        boolean longV;
        float f1, f2, f3;
        float b1 = 110f, b2 = 130f, b3 = 200f;
        float fc = 4000f, fb = 2000f, fa;
        float bc = 3000f, bb = 2000f, ba;
        float clo, vot, dur;
        float nz;
        float av = 0.7f;

        P loc(float a, float b, float c) {
            f1 = a;
            f2 = b;
            f3 = c;
            return this;
        }

        P bw(float a, float b, float c) {
            b1 = a;
            b2 = b;
            b3 = c;
            return this;
        }

        P fric(float c, float b, float a) {
            fc = c;
            fb = b;
            fa = a;
            return this;
        }

        P burst(float c, float b, float a) {
            bc = c;
            bb = b;
            ba = a;
            return this;
        }

        P stop(float c, float v) {
            clo = c;
            vot = v;
            return this;
        }

        P len(float d) {
            dur = d;
            return this;
        }

        P nas(float n) {
            nz = n;
            return this;
        }

        P amp(float a) {
            av = a;
            return this;
        }

        P emphatic() {
            emph = true;
            return this;
        }

        P mildEmph() {
            mild = true;
            return this;
        }

        P velarLocus() {
            velar = true;
            return this;
        }
    }

    private static final Map<String, P> PT = new HashMap<>();

    private static P def(String sym, int type, boolean voiced) {
        P p = new P();
        p.sym = sym;
        p.type = type;
        p.voiced = voiced;
        PT.put(sym, p);
        return p;
    }

    static {
        // حركات
        def("a", V, true).loc(650, 1450, 2550).bw(60, 90, 150).len(72);
        def("i", V, true).loc(360, 2050, 2850).bw(60, 90, 150).len(72);
        def("u", V, true).loc(380, 950, 2350).bw(60, 90, 150).len(72);
        def("A", V, true).loc(720, 1380, 2500).bw(60, 90, 150).len(150).longV = true;
        def("I", V, true).loc(300, 2200, 3000).bw(60, 90, 150).len(150).longV = true;
        def("U", V, true).loc(320, 800, 2300).bw(60, 90, 150).len(150).longV = true;
        def("e", V, true).loc(500, 1800, 2600).bw(60, 90, 150).len(78);
        def("o", V, true).loc(500, 950, 2400).bw(60, 90, 150).len(78);

        // انفجاريات
        def("b", ST, true).loc(200, 900, 2100).burst(1100, 1800, 0.20f).stop(58, 6).amp(0.2f);
        def("p", ST, false).loc(200, 900, 2100).burst(1100, 1800, 0.18f).stop(66, 18);
        def("t", ST, false).loc(250, 1800, 2900).burst(4300, 3500, 0.42f).stop(64, 28);
        def("d", ST, true).loc(250, 1700, 2800).burst(3800, 3500, 0.32f).stop(55, 8).amp(0.2f);
        def("T", ST, false).loc(320, 1300, 2500).burst(3000, 2500, 0.46f).stop(70, 30).emphatic();
        def("D", ST, true).loc(320, 1200, 2500).burst(2800, 2500, 0.36f).stop(60, 8).amp(0.2f).emphatic();
        def("k", ST, false).loc(300, 1900, 2400).burst(2500, 1800, 0.46f).stop(66, 38).velarLocus();
        def("g", ST, true).loc(300, 1800, 2400).burst(2300, 1800, 0.34f).stop(55, 10).amp(0.2f).velarLocus();
        def("q", ST, false).loc(450, 1200, 2300).burst(1400, 1200, 0.46f).stop(70, 32).mildEmph();
        def("?", GS, false).loc(500, 1500, 2500).len(46);
        def("j", AFR, true).loc(250, 1900, 2700).burst(3500, 2500, 0.28f).fric(3200, 2000, 0.46f).stop(42, 0).len(70);

        // احتكاكيات
        def("f", FR, false).loc(250, 1100, 2300).fric(5500, 6000, 0.12f).len(100);
        def("v", FR, true).loc(250, 1100, 2300).fric(5500, 6000, 0.10f).len(82).amp(0.35f);
        def("th", FR, false).loc(250, 1600, 2700).fric(6000, 6000, 0.10f).len(100);
        def("dh", FR, true).loc(250, 1600, 2700).fric(6000, 6000, 0.09f).len(82).amp(0.35f);
        def("Dh", FR, true).loc(320, 1300, 2500).fric(5000, 5000, 0.12f).len(86).amp(0.35f).emphatic();
        def("s", FR, false).loc(250, 1700, 2800).fric(6800, 3000, 0.52f).len(104);
        def("z", FR, true).loc(250, 1700, 2800).fric(6300, 3000, 0.38f).len(86).amp(0.35f);
        def("S", FR, false).loc(320, 1300, 2500).fric(5200, 2600, 0.56f).len(106).emphatic();
        def("sh", FR, false).loc(280, 1900, 2600).fric(3300, 1800, 0.52f).len(108);
        def("kh", FR, false).loc(300, 1500, 2300).fric(2300, 1500, 0.30f).len(100).velarLocus().mildEmph();
        def("gh", FR, true).loc(300, 1300, 2300).fric(1600, 1300, 0.18f).len(80).amp(0.45f).velarLocus().mildEmph();
        def("H", PH, false).loc(780, 1250, 2400).bw(200, 200, 260).len(90);
        def("3", PV, true).loc(700, 1150, 2400).bw(170, 200, 260).len(82).amp(0.55f);
        def("h", HG, false).loc(500, 1500, 2500).len(72);

        // أنفيات وسوائل وأنصاف حركات
        def("m", NA, true).loc(250, 1000, 2200).bw(90, 150, 220).nas(0.8f).len(70).amp(0.6f);
        def("n", NA, true).loc(250, 1500, 2500).bw(90, 150, 220).nas(0.8f).len(66).amp(0.6f);
        def("l", LQ, true).loc(360, 1250, 2750).bw(100, 140, 220).len(66).amp(0.75f);
        def("L", LQ, true).loc(400, 900, 2500).bw(100, 140, 220).len(70).amp(0.75f).emphatic();
        def("r", LQ, true).loc(400, 1400, 2300).bw(100, 140, 220).len(58).amp(0.7f);
        def("w", GL, true).loc(300, 700, 2200).bw(80, 110, 180).len(70).amp(0.8f);
        def("y", GL, true).loc(280, 2250, 3000).bw(80, 110, 180).len(70).amp(0.8f);

        def("_", SIL, false);
    }

    // ------------------------------------------------------------------ وحدة صوتية مجدولة

    private static final class Ph {
        final P p;
        boolean gem;
        boolean tanN;
        int word = -1;
        boolean stressed;
        boolean light;      // وحدة مدمجة (همزة/هاء مخفّفة)
        float f1, f2, f3;
        float dur;
        float t0;
        float amp = 1f;
        float silMs;
        int silType;        // 0 إثبات، 1 سؤال، 2 فاصلة
        boolean finalPos;   // آخر وحدتين قبل وقف/نهاية

        Ph(P p) {
            this.p = p;
            this.f1 = p.f1;
            this.f2 = p.f2;
            this.f3 = p.f3;
        }
    }

    // ------------------------------------------------------------------ تقطيع النص

    private static final int K_AR = 0, K_LAT = 1, K_NUM = 2, K_SENT = 3, K_COMMA = 4, K_Q = 5;

    private static final class Item {
        int kind;
        int start;
        String s;
    }

    private static boolean isArLetter(char c) {
        return (c >= 0x0621 && c <= 0x063A) || (c >= 0x0641 && c <= 0x064A) || c == 0x0671
                || c == 0x067E || c == 0x0686 || c == 0x06A4 || c == 0x06A9 || c == 0x06AF || c == 0x06CC;
    }

    private static boolean isArMark(char c) {
        return (c >= 0x064B && c <= 0x065F) || c == 0x0670 || c == 0x0640;
    }

    private static boolean isLatin(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= 0xC0 && c <= 0x17F && c != 0xD7 && c != 0xF7);
    }

    private static int digitValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 0x0660 && c <= 0x0669) return c - 0x0660;
        if (c >= 0x06F0 && c <= 0x06F9) return c - 0x06F0;
        return -1;
    }

    private static List<Item> tokenize(String text) {
        List<Item> items = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (isArLetter(c)) {
                int j = i;
                while (j < n && (isArLetter(text.charAt(j)) || isArMark(text.charAt(j)))) j++;
                items.add(item(K_AR, i, text.substring(i, j)));
                i = j;
            } else if (isLatin(c)) {
                int j = i;
                while (j < n && (isLatin(text.charAt(j)) || (text.charAt(j) == '\'' && j + 1 < n && isLatin(text.charAt(j + 1))))) j++;
                items.add(item(K_LAT, i, text.substring(i, j)));
                i = j;
            } else if (digitValue(c) >= 0) {
                int j = i;
                while (j < n && digitValue(text.charAt(j)) >= 0) j++;
                items.add(item(K_NUM, i, text.substring(i, j)));
                i = j;
            } else if (c == '؟' || c == '?') {
                addPunct(items, K_Q, i);
                i++;
            } else if (c == '.' || c == '!' || c == '…' || c == '\n' || c == '\r') {
                addPunct(items, K_SENT, i);
                i++;
            } else if (c == '،' || c == ',' || c == '؛' || c == ';' || c == ':' || c == '–' || c == '—') {
                addPunct(items, K_COMMA, i);
                i++;
            } else {
                i++;
            }
        }
        return items;
    }

    private static Item item(int kind, int start, String s) {
        Item it = new Item();
        it.kind = kind;
        it.start = start;
        it.s = s;
        return it;
    }

    /** علامات الترقيم المتتالية تُدمج في وقفة واحدة (الأقوى تغلب). */
    private static void addPunct(List<Item> items, int kind, int pos) {
        if (!items.isEmpty()) {
            Item last = items.get(items.size() - 1);
            if (last.kind >= K_SENT) {
                if (kind == K_Q) last.kind = K_Q;
                else if (kind == K_SENT && last.kind == K_COMMA) last.kind = K_SENT;
                return;
            }
        } else {
            return; // لا وقفة قبل أول كلمة
        }
        items.add(item(kind, pos, ""));
    }

    // ------------------------------------------------------------------ الواجهة اللغوية: عربي

    private static final class Unit {
        char letter;
        boolean shadda;
        boolean sukun;
        boolean dagger;
        int vowel;  // 0 لا شيء، 1 فتحة، 2 ضمة، 3 كسرة
        int tan;    // 0 لا شيء، 1 فتحتان، 2 ضمتان، 3 كسرتان

        boolean bare() {
            return !shadda && !sukun && !dagger && vowel == 0 && tan == 0;
        }
    }

    private static List<Unit> parseUnits(String w) {
        List<Unit> u = new ArrayList<>();
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (isArLetter(c)) {
                Unit x = new Unit();
                x.letter = c;
                u.add(x);
            } else if (!u.isEmpty()) {
                Unit x = u.get(u.size() - 1);
                switch (c) {
                    case 0x064B:
                        x.tan = 1;
                        break;
                    case 0x064C:
                        x.tan = 2;
                        break;
                    case 0x064D:
                        x.tan = 3;
                        break;
                    case 0x064E:
                        x.vowel = 1;
                        break;
                    case 0x064F:
                        x.vowel = 2;
                        break;
                    case 0x0650:
                        x.vowel = 3;
                        break;
                    case 0x0651:
                        x.shadda = true;
                        break;
                    case 0x0652:
                        x.sukun = true;
                        break;
                    case 0x0670:
                        x.dagger = true;
                        break;
                    default:
                        break;
                }
            }
        }
        return u;
    }

    private static final Set<Character> SUN = new HashSet<>(Arrays.asList(
            'ت', 'ث', 'د', 'ذ', 'ر', 'ز', 'س', 'ش', 'ص', 'ض', 'ط', 'ظ', 'ل', 'ن'));

    private static final Map<Character, String> CONS = new HashMap<>();

    static {
        CONS.put('ب', "b");
        CONS.put('ت', "t");
        CONS.put('ث', "th");
        CONS.put('ج', "j");
        CONS.put('ح', "H");
        CONS.put('خ', "kh");
        CONS.put('د', "d");
        CONS.put('ذ', "dh");
        CONS.put('ر', "r");
        CONS.put('ز', "z");
        CONS.put('س', "s");
        CONS.put('ش', "sh");
        CONS.put('ص', "S");
        CONS.put('ض', "D");
        CONS.put('ط', "T");
        CONS.put('ظ', "Dh");
        CONS.put('ع', "3");
        CONS.put('غ', "gh");
        CONS.put('ف', "f");
        CONS.put('ق', "q");
        CONS.put('ك', "k");
        CONS.put('ل', "l");
        CONS.put('م', "m");
        CONS.put('ن', "n");
        CONS.put('ه', "h");
        CONS.put('پ', "b");
        CONS.put('چ', "j");
        CONS.put('ڤ', "f");
        CONS.put('ک', "k");
        CONS.put('گ', "g");
    }

    /** كلمات شائعة لا تُكتب فيها المدود: تُنطق من قاموس صغير. المفتاح = الحروف بلا حركات. */
    private static final Map<String, String> DICT = new HashMap<>();

    static {
        DICT.put("الله", "? a L L A h");
        DICT.put("هذا", "h A dh a");
        DICT.put("هذه", "h A dh i h i");
        DICT.put("ذلك", "dh A l i k a");
        DICT.put("لكن", "l A k i n");
        DICT.put("أولئك", "? u l A ? i k a");
        DICT.put("اولئك", "? u l A ? i k a");
        DICT.put("هؤلاء", "h a ? u l A ? i");
        DICT.put("إله", "? i l A h");
        DICT.put("الرحمن", "? a r r a H m A n");
        DICT.put("الرحيم", "? a r r a H I m");
        DICT.put("طه", "T A h A");
    }

    private static final String[] DIGIT_WORDS = {
            "صِفْر", "وَاحِد", "اِثْنَان", "ثَلَاثَة", "أَرْبَعَة", "خَمْسَة", "سِتَّة", "سَبْعَة", "ثَمَانِيَة", "تِسْعَة"
    };

    private static Ph mk(String sym, int word) {
        P p = PT.get(sym);
        if (p == null) p = PT.get("a");
        Ph x = new Ph(p);
        x.word = word;
        return x;
    }

    private static void add(List<Ph> out, String sym, int word) {
        out.add(mk(sym, word));
    }

    private static void addGem(List<Ph> out, String sym, int word, boolean gem) {
        Ph x = mk(sym, word);
        x.gem = gem && (x.p.type != V);
        out.add(x);
    }

    private static boolean isAlifLike(char c) {
        return c == 'ا' || c == 'ى' || c == 0x0671;
    }

    private static boolean bareMater(Unit x) {
        if (x == null || !x.bare()) return false;
        char c = x.letter;
        return c == 'ا' || c == 'ى' || c == 'و' || c == 'ي' || c == 'ی';
    }

    private static String vowelSym(int v) {
        return v == 1 ? "a" : v == 2 ? "u" : "i";
    }

    /** يحوّل كلمة عربية إلى وحدات صوتية. */
    private static void arabicWord(String w, boolean phraseStart, boolean phraseFinal, int word, List<Ph> out) {
        List<Unit> u = parseUnits(w);
        int n = u.size();
        if (n == 0) return;
        int base = out.size();

        StringBuilder bare = new StringBuilder();
        for (Unit x : u) bare.append(x.letter);
        String dict = DICT.get(bare.toString());
        if (dict != null) {
            String[] toks = dict.split(" ");
            int from = 0;
            if (!phraseStart && bare.toString().equals("الله")) from = 2; // لا همزة وصل في الدرج
            for (int k = from; k < toks.length; k++) add(out, toks[k], word);
            return;
        }

        int start = 0;
        boolean forceGem = false;
        boolean article = n >= 3 && isAlifLike(u.get(0).letter) && u.get(0).letter != 'ى'
                && u.get(1).letter == 'ل' && u.get(0).bare();
        if (article) {
            if (phraseStart) {
                add(out, "?", word);
                add(out, "a", word);
            }
            Unit l = u.get(1);
            if (SUN.contains(u.get(2).letter) && !l.shadda) {
                forceGem = true;       // أل الشمسية: اللام تُدغم في الحرف التالي
            } else {
                addGem(out, "l", word, false);
            }
            start = 2;
        } else if (n >= 2 && (u.get(0).letter == 'ا' || u.get(0).letter == 0x0671) && u.get(0).bare()) {
            if (phraseStart) {
                add(out, "?", word);
                add(out, "i", word);
            }
            start = 1;
        }

        for (int i = start; i < n; i++) {
            Unit c = u.get(i);
            char L = c.letter;
            boolean gem = c.shadda || (i == start && forceGem);
            Unit nx = i + 1 < n ? u.get(i + 1) : null;
            boolean last = i == n - 1;

            if (L == 'آ') {
                add(out, "?", word);
                add(out, "A", word);
                continue;
            }
            if (L == 'ء' || L == 'أ' || L == 'إ' || L == 'ؤ' || L == 'ئ') {
                int dv = L == 'أ' ? 1 : L == 'إ' ? 3 : L == 'ؤ' ? 2 : L == 'ئ' ? 3 : (last ? 0 : 1);
                i = consonant("?", c, i, u, out, word, gem, dv);
                continue;
            }
            if (L == 'ا' || L == 0x0671) {
                // ألف يتيمة (لم يستهلكها الحرف السابق)
                if (last && i > 0 && u.get(i - 1).letter == 'و') continue; // ألف واو الجماعة: لا تُنطق
                add(out, "A", word);
                continue;
            }
            if (L == 'ى') {
                if (last) add(out, "A", word);
                else i = consonant("y", c, i, u, out, word, gem, 1);
                continue;
            }
            if (L == 'ة') {
                if (phraseFinal && last) {
                    Ph h = mk("h", word);
                    h.light = true;
                    out.add(h);
                } else {
                    i = consonant("t", c, i, u, out, word, gem, 0);
                }
                continue;
            }
            if (L == 'و' || L == 'ي' || L == 'ی') {
                String sym = L == 'و' ? "w" : "y";
                boolean afterA = out.size() > base && out.get(out.size() - 1).p.sym.equals("a");
                int dv = (last || afterA) ? 0 : 1;
                if (i == 0 && !last) dv = 1;
                i = consonant(sym, c, i, u, out, word, gem, dv);
                continue;
            }
            String sym = CONS.get(L);
            if (sym == null) continue;
            i = consonant(sym, c, i, u, out, word, gem, (last ? 0 : 1));
        }

        // الوقف: حذف حركة الإعراب الأخيرة عند نهاية الجملة
        if (phraseFinal && out.size() > base) {
            int sz = out.size();
            Ph l1 = out.get(sz - 1);
            if (l1.tanN && sz - base >= 2) {
                Ph v = out.get(sz - 2);
                out.remove(sz - 1);
                if (v.p.sym.equals("a")) {
                    out.set(sz - 2, mk("A", word));
                } else {
                    out.remove(sz - 2);
                }
            } else if (l1.p.type == V && !l1.p.longV) {
                int vowels = 0;
                for (int k = base; k < sz; k++) if (out.get(k).p.type == V) vowels++;
                if (vowels >= 2 && sz - base >= 3) out.remove(sz - 1);
            }
        }
    }

    /**
     * يُصدر حرفًا ساكنًا وحركته، ويستهلك حرف المدّ التالي إن وُجد. يرجّع فهرس آخر حرف استُهلك.
     * defVowel: الحركة الافتراضية عند غياب التشكيل (0 = لا شيء، 1 = فتحة).
     */
    private static int consonant(String sym, Unit c, int i, List<Unit> u, List<Ph> out, int word, boolean gem, int defVowel) {
        int n = u.size();
        Unit nx = i + 1 < n ? u.get(i + 1) : null;
        addGem(out, sym, word, gem);
        if (c.dagger) {
            add(out, "A", word);
            return i;
        }
        int v = c.vowel;
        if (v != 0) {
            if (nx != null && bareMater(nx)) {
                if (v == 1 && (nx.letter == 'ا' || nx.letter == 'ى')) {
                    add(out, "A", word);
                    return i + 1;
                }
                if (v == 3 && (nx.letter == 'ي' || nx.letter == 'ی')) {
                    add(out, "I", word);
                    return i + 1;
                }
                if (v == 2 && nx.letter == 'و') {
                    add(out, "U", word);
                    return i + 1;
                }
            }
            add(out, vowelSym(v), word);
            return i;
        }
        if (c.tan != 0) {
            add(out, vowelSym(c.tan), word);
            Ph nn = mk("n", word);
            nn.tanN = true;
            out.add(nn);
            return i;
        }
        if (c.sukun) return i;
        if (nx != null && bareMater(nx)) {
            char m = nx.letter;
            add(out, (m == 'ا' || m == 'ى') ? "A" : m == 'و' ? "U" : "I", word);
            return i + 1;
        }
        if (defVowel != 0 && i < n - 1) add(out, defVowel == 2 ? "u" : defVowel == 3 ? "i" : "a", word);
        return i;
    }

    // ------------------------------------------------------------------ الواجهة اللغوية: لاتيني تقريبي

    private static char fold(char c) {
        switch (c) {
            case 'é':
            case 'è':
            case 'ê':
            case 'ë':
                return 'e';
            case 'à':
            case 'â':
            case 'ä':
            case 'á':
                return 'a';
            case 'î':
            case 'ï':
            case 'í':
                return 'i';
            case 'ô':
            case 'ö':
            case 'ó':
                return 'o';
            case 'ù':
            case 'û':
            case 'ü':
            case 'ú':
                return 'u';
            case 'ç':
                return 's';
            default:
                return c;
        }
    }

    private static void latinWord(String w, int word, List<Ph> out) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length(); i++) {
            char c = Character.toLowerCase(w.charAt(i));
            c = fold(c);
            if (c >= 'a' && c <= 'z') sb.append(c);
        }
        String s = sb.toString();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            char nx = i + 1 < n ? s.charAt(i + 1) : 0;
            boolean nextFront = nx == 'e' || nx == 'i' || nx == 'y';
            String two = i + 1 < n ? s.substring(i, i + 2) : "";
            if (two.equals("sh")) {
                add(out, "sh", word);
                i++;
                continue;
            }
            if (two.equals("ch")) {
                add(out, "t", word);
                add(out, "sh", word);
                i++;
                continue;
            }
            if (two.equals("th")) {
                add(out, "th", word);
                i++;
                continue;
            }
            if (two.equals("ph")) {
                add(out, "f", word);
                i++;
                continue;
            }
            if (two.equals("ck")) {
                add(out, "k", word);
                i++;
                continue;
            }
            if (two.equals("ee") || two.equals("ea")) {
                add(out, "I", word);
                i++;
                continue;
            }
            if (two.equals("oo") || two.equals("ou")) {
                add(out, "U", word);
                i++;
                continue;
            }
            if (two.equals("qu")) {
                add(out, "k", word);
                add(out, "w", word);
                i++;
                continue;
            }
            switch (c) {
                case 'a':
                    add(out, "a", word);
                    break;
                case 'e':
                    if (!(i == n - 1 && n > 3)) add(out, "e", word);
                    break;
                case 'i':
                    add(out, "i", word);
                    break;
                case 'o':
                    add(out, "o", word);
                    break;
                case 'u':
                    add(out, "u", word);
                    break;
                case 'y':
                    add(out, i == 0 ? "y" : "i", word);
                    break;
                case 'c':
                    add(out, nextFront ? "s" : "k", word);
                    break;
                case 'g':
                    add(out, nextFront ? "j" : "g", word);
                    break;
                case 'h':
                    if (i == 0) add(out, "h", word);
                    break;
                case 'q':
                    add(out, "k", word);
                    break;
                case 'x':
                    add(out, "k", word);
                    add(out, "s", word);
                    break;
                case 'b':
                case 'd':
                case 'f':
                case 'j':
                case 'k':
                case 'l':
                case 'm':
                case 'n':
                case 'p':
                case 'r':
                case 't':
                case 'v':
                case 'w':
                case 'z':
                    add(out, String.valueOf(c), word);
                    break;
                case 's': {
                    boolean vb = i > 0 && "aeiouy".indexOf(s.charAt(i - 1)) >= 0 && nx != 0 && "aeiouy".indexOf(nx) >= 0;
                    add(out, vb ? "z" : "s", word);
                    break;
                }
                default:
                    break;
            }
        }
    }

    // ------------------------------------------------------------------ التركيب

    /** خطة النطق: الوحدات + مواضع الكلمات. */
    private static final class Plan {
        final List<Ph> ph = new ArrayList<>();
        final List<Integer> wordChar = new ArrayList<>();
    }

    private static Plan buildPlan(List<Item> items, int sentPause, int commaPause) {
        Plan plan = new Plan();
        Ph lead = mk("_", -1);
        lead.silMs = 35;
        plan.ph.add(lead);
        int word = -1;
        boolean any = false;
        for (int k = 0; k < items.size(); k++) {
            Item it = items.get(k);
            boolean prevPunct = k == 0 || items.get(k - 1).kind >= K_SENT;
            boolean nextPunct = k + 1 >= items.size() || items.get(k + 1).kind >= K_SENT;
            if (it.kind >= K_SENT) {
                if (!any) continue;
                Ph s = mk("_", -1);
                s.silMs = it.kind == K_COMMA ? commaPause : sentPause;
                s.silType = it.kind == K_Q ? 1 : it.kind == K_COMMA ? 2 : 0;
                plan.ph.add(s);
                continue;
            }
            word++;
            plan.wordChar.add(it.start);
            int from = plan.ph.size();
            if (it.kind == K_AR) {
                arabicWord(it.s, prevPunct, nextPunct, word, plan.ph);
            } else if (it.kind == K_LAT) {
                latinWord(it.s, word, plan.ph);
            } else {
                for (int d = 0; d < it.s.length(); d++) {
                    int dv = digitValue(it.s.charAt(d));
                    if (dv >= 0) arabicWord(DIGIT_WORDS[dv], false, false, word, plan.ph);
                }
            }
            if (plan.ph.size() > from) {
                any = true;
                markStress(plan.ph, from, plan.ph.size());
                spreadEmphasis(plan.ph, from, plan.ph.size());
            }
        }
        if (!any) return null;
        boolean audible = false;
        for (Ph x : plan.ph) {
            if (x.p.type != SIL && x.p.type != GS) {
                audible = true;
                break;
            }
        }
        if (!audible) return null; // همزة وحدها أو رموز: لا يوجد ما يُسمع
        Ph tail = mk("_", -1);
        tail.silMs = 90;
        plan.ph.add(tail);
        return plan;
    }

    /** قاعدة النبر العربية المبسّطة: المقطع الأخير فائق الثقل، وإلا ما قبل الأخير الثقيل، وإلا السابق له. */
    private static void markStress(List<Ph> ph, int from, int to) {
        List<Integer> vi = new ArrayList<>();
        for (int k = from; k < to; k++) if (ph.get(k).p.type == V) vi.add(k);
        int nsyl = vi.size();
        if (nsyl == 0) return;
        if (nsyl == 1) {
            ph.get(vi.get(0)).stressed = true;
            return;
        }
        int[] weight = new int[nsyl];
        for (int s = 0; s < nsyl; s++) {
            int pos = vi.get(s);
            int cons = 0;
            int end = s + 1 < nsyl ? vi.get(s + 1) : to;
            for (int k = pos + 1; k < end; k++) cons += ph.get(k).gem ? 2 : 1;
            int coda = s + 1 < nsyl ? Math.max(0, cons - 1) : cons;
            boolean longV = ph.get(pos).p.longV;
            if (longV) weight[s] = coda == 0 ? 1 : 2;
            else weight[s] = coda == 0 ? 0 : coda == 1 ? 1 : 2;
        }
        int pick;
        if (weight[nsyl - 1] == 2) pick = nsyl - 1;
        else if (weight[nsyl - 2] >= 1) pick = nsyl - 2;
        else if (nsyl >= 3) pick = nsyl - 3;
        else pick = nsyl - 2;
        ph.get(vi.get(pick)).stressed = true;
    }

    /** التفخيم: الحروف المطبقة تُخفض رنين الحركة المجاورة (F2 أقل وF1 أعلى). */
    private static void spreadEmphasis(List<Ph> ph, int from, int to) {
        for (int k = from; k < to; k++) {
            Ph x = ph.get(k);
            if (x.p.type != V) continue;
            float strength = 0f;
            for (int d = -1; d <= 1; d += 2) {
                int q = k + d;
                if (q < from || q >= to) continue;
                P p = ph.get(q).p;
                if (p.emph) strength = Math.max(strength, 1f);
                else if (p.mild) strength = Math.max(strength, 0.5f);
                else if (p.type == PH || p.type == PV) strength = Math.max(strength, 0.4f);
            }
            if (strength > 0f) {
                float lf = x.p.longV ? 0.78f : 0.84f;
                x.f1 *= 1f + 0.12f * strength;
                x.f2 *= 1f - (1f - lf) * strength;
                x.f3 *= 1f - 0.03f * strength;
            }
        }
    }

    // ------------------------------------------------------------------ مسارات المعاملات

    private static final class Track {
        float[] t = new float[256];
        float[] v = new float[256];
        int n;

        void add(float time, float val) {
            if (n > 0 && time < t[n - 1]) time = t[n - 1];
            if (n == t.length) {
                t = Arrays.copyOf(t, n * 2);
                v = Arrays.copyOf(v, n * 2);
            }
            t[n] = time;
            v[n] = val;
            n++;
        }

        float[] dense(int len, float init) {
            float[] o = new float[len];
            if (n == 0) {
                Arrays.fill(o, init);
                return o;
            }
            int j = 0;
            for (int k = 0; k < len; k++) {
                float tm = k;
                while (j + 1 < n && t[j + 1] <= tm) j++;
                if (tm <= t[0]) o[k] = v[0];
                else if (j + 1 >= n) o[k] = v[n - 1];
                else {
                    float a = t[j];
                    float b = t[j + 1];
                    o[k] = b > a ? v[j] + (v[j + 1] - v[j]) * (tm - a) / (b - a) : v[j + 1];
                }
            }
            return o;
        }
    }

    private static final class Tracks {
        final Track f1 = new Track(), f2 = new Track(), f3 = new Track();
        final Track b1 = new Track(), b2 = new Track(), b3 = new Track();
        final Track av = new Track(), ah = new Track(), af = new Track(), nz = new Track();
        final Track fc = new Track(), fb = new Track();
        final Track bu = new Track(), bc = new Track(), bb = new Track();
        final Track jit = new Track();
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ نقطة الدخول

    /**
     * يركّب النص صوتًا.
     *
     * @param ratePct         نسبة تغيير سرعة النطق (موجب = أسرع)
     * @param pitchHz         إزاحة طبقة الصوت (هرتز)
     * @param sentencePauseMs وقفة نهاية الجملة (0 = افتراضي)
     * @param commaPauseMs    وقفة الفاصلة (0 = افتراضي)
     * @return null لو لا يوجد ما يُنطق
     */
    static Out synthesize(String text, Voice voice, int ratePct, int pitchHz, int sentencePauseMs, int commaPauseMs) {
        if (text == null || text.trim().isEmpty()) return null;
        if (voice == null) voice = VOICES[0];
        int sp = sentencePauseMs > 0 ? sentencePauseMs : 260;
        int cp = commaPauseMs > 0 ? commaPauseMs : 110;
        List<Item> items = tokenize(text);
        Plan plan = buildPlan(items, sp, cp);
        if (plan == null) return null;
        List<Ph> ph = plan.ph;
        int cnt = ph.size();

        // ---- المدد
        float durScale = clamp(0.88f * 100f / (100f + ratePct), 0.5f, 1.9f);
        long seed = 0x9E3779B97F4A7C15L ^ text.hashCode();
        Rng rng = new Rng(seed);

        // وسم آخر وحدتين قبل كل وقفة
        for (int k = 0; k < cnt; k++) {
            if (ph.get(k).p.type == SIL && k > 0) {
                int c = 0;
                for (int q = k - 1; q >= 0 && c < 2; q--) {
                    if (ph.get(q).p.type == SIL) break;
                    ph.get(q).finalPos = true;
                    c++;
                }
            }
        }

        for (int k = 0; k < cnt; k++) {
            Ph x = ph.get(k);
            P p = x.p;
            float d;
            switch (p.type) {
                case SIL:
                    d = x.silMs;
                    break;
                case ST:
                    d = p.clo * (x.gem ? 1.8f : 1f) + 10f + p.vot;
                    break;
                case AFR:
                    d = p.clo * (x.gem ? 1.4f : 1f) + 8f + p.dur;
                    break;
                default:
                    d = p.dur * (x.gem ? 1.65f : 1f);
                    break;
            }
            if (p.type == V) {
                if (x.stressed) d *= 1.12f;
                else if (!p.longV) d *= 0.92f;
            }
            if (x.light) d *= 0.6f;
            if (x.finalPos) d *= p.type == V ? 1.3f : 1.12f;
            if (p.type != SIL) d *= durScale * (1f + 0.04f * (rng.next() - 0.5f) * 2f);
            x.dur = d;
        }
        float t = 0f;
        for (int k = 0; k < cnt; k++) {
            ph.get(k).t0 = t;
            t += ph.get(k).dur;
        }
        final float totalMs = t + 40f;

        // ---- مسارات الصوت
        Tracks tr = new Tracks();
        float fs = voice.fscale;
        tr.jit.add(0, voice.jitter);
        for (int k = 0; k < cnt; k++) {
            emit(ph, k, tr, voice, fs, rng);
        }

        final int len = (int) Math.ceil(totalMs) + 4;
        final float[] F1 = tr.f1.dense(len, 500f), F2 = tr.f2.dense(len, 1500f), F3 = tr.f3.dense(len, 2500f);
        final float[] B1 = tr.b1.dense(len, 80f), B2 = tr.b2.dense(len, 110f), B3 = tr.b3.dense(len, 180f);
        final float[] AV = tr.av.dense(len, 0f), AH = tr.ah.dense(len, 0f), AF = tr.af.dense(len, 0f), NZ = tr.nz.dense(len, 0f);
        final float[] FC = tr.fc.dense(len, 4000f), FB = tr.fb.dense(len, 2000f);
        final float[] BU = tr.bu.dense(len, 0f), BC = tr.bc.dense(len, 3000f), BB = tr.bb.dense(len, 2000f);
        final float[] JT = tr.jit.dense(len, voice.jitter);
        final float[] F0 = buildF0(ph, len, voice, pitchHz);

        float[] pcm = render(len, voice, F1, F2, F3, B1, B2, B3, AV, AH, AF, NZ, FC, FB, BU, BC, BB, JT, F0, rng);

        // ---- مواضع الكلمات
        int nw = plan.wordChar.size();
        int[] wordMs = new int[nw];
        int[] wordStart = new int[nw];
        Arrays.fill(wordStart, -1);
        for (int k = 0; k < cnt; k++) {
            Ph x = ph.get(k);
            if (x.word >= 0 && wordStart[x.word] < 0) wordStart[x.word] = Math.round(x.t0);
        }
        int next = Math.round(totalMs);
        for (int w = nw - 1; w >= 0; w--) {
            if (wordStart[w] < 0) wordStart[w] = next;
            next = wordStart[w];
        }
        int[] wordChar = new int[nw];
        for (int w = 0; w < nw; w++) {
            wordMs[w] = wordStart[w];
            wordChar[w] = plan.wordChar.get(w);
        }
        byte[] wav = toWav(pcm);
        return new Out(wav, wordMs, wordChar, Math.round(pcm.length * 1000f / SAMPLE_RATE));
    }

    // ------------------------------------------------------------------ توليد المعاملات لكل وحدة

    private static Ph nextVowel(List<Ph> ph, int k) {
        for (int q = k + 1; q < ph.size() && q <= k + 2; q++) {
            Ph x = ph.get(q);
            if (x.p.type == V) return x;
            if (x.p.type == SIL) return null;
        }
        return null;
    }

    private static Ph prevVowel(List<Ph> ph, int k) {
        for (int q = k - 1; q >= 0 && q >= k - 2; q--) {
            Ph x = ph.get(q);
            if (x.p.type == V) return x;
            if (x.p.type == SIL) return null;
        }
        return null;
    }

    private static void formants(Tracks tr, float t, float f1, float f2, float f3, float b1, float b2, float b3, float fs) {
        tr.f1.add(t, f1 * fs);
        tr.f2.add(t, f2 * fs);
        tr.f3.add(t, f3 * fs);
        tr.b1.add(t, b1);
        tr.b2.add(t, b2);
        tr.b3.add(t, b3);
    }

    private static void amps(Tracks tr, float t0, float t1, float av, float ah, float af, float nz, float rin, float rout) {
        float a = t0 + rin;
        float b = Math.max(a, t1 - rout);
        tr.av.add(a, av);
        tr.av.add(b, av);
        tr.ah.add(a, ah);
        tr.ah.add(b, ah);
        tr.af.add(a, af);
        tr.af.add(b, af);
        tr.nz.add(a, nz);
        tr.nz.add(b, nz);
        tr.bu.add(a, 0f);
        tr.bu.add(b, 0f);
    }

    private static void fricKeys(Tracks tr, float a, float b, float fc, float fb, float fs) {
        float sc = 1f + (fs - 1f) * 0.5f;
        tr.fc.add(a, fc * sc);
        tr.fc.add(b, fc * sc);
        tr.fb.add(a, fb);
        tr.fb.add(b, fb);
    }

    private static void emit(List<Ph> ph, int k, Tracks tr, Voice voice, float fs, Rng rng) {
        Ph x = ph.get(k);
        P p = x.p;
        float t0 = x.t0;
        float t1 = x.t0 + x.dur;
        float dur = x.dur;

        if (p.type == SIL) {
            tr.av.add(t0, 0f);
            tr.ah.add(t0, 0f);
            tr.af.add(t0, 0f);
            tr.nz.add(t0, 0f);
            tr.bu.add(t0, 0f);
            tr.av.add(t1, 0f);
            tr.ah.add(t1, 0f);
            tr.af.add(t1, 0f);
            tr.nz.add(t1, 0f);
            tr.bu.add(t1, 0f);
            return;
        }

        // قيم الرنين الثابتة للوحدة (مع تكيّف مواضع الخلف)
        float f1 = x.f1, f2 = x.f2, f3 = x.f3;
        if (p.velar) {
            Ph nv = nextVowel(ph, k);
            if (nv != null) f2 = clamp(nv.f2 + (nv.f2 > 1700f ? 100f : 250f), 1300f, 2300f);
        }
        if (p.type == HG) {
            Ph nv = nextVowel(ph, k);
            Ph pv = prevVowel(ph, k);
            Ph src = nv != null ? nv : pv;
            if (src != null) {
                f1 = src.f1;
                f2 = src.f2;
                f3 = src.f3;
            }
        }

        float fa = p.type == V ? Math.min(0.34f * dur, 36f) : Math.min(0.28f * dur, 24f);

        switch (p.type) {
            case V: {
                formants(tr, t0 + fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                formants(tr, t1 - fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                float a = (x.stressed ? 1.0f : 0.88f) * (x.finalPos ? 0.82f : 1f);
                amps(tr, t0, t1, a, 0f, 0f, 0f, 6f, 7f);
                fricKeys(tr, t0 + 6f, t1 - 7f, 4000f, 2000f, 1f);
                break;
            }
            case ST: {
                float clo = p.clo * (x.gem ? 1.8f : 1f);
                float tb = t0 + clo;
                formants(tr, t0 + 4f, f1, f2, f3, 120f, 140f, 220f, fs);
                formants(tr, tb + 2f, f1, f2, f3, 120f, 140f, 220f, fs);
                if (p.voiced) {
                    tr.av.add(t0 + 4f, p.av);
                    tr.av.add(tb + 2f, p.av);
                    tr.av.add(t1 - 1f, 0.6f);
                    tr.ah.add(t0 + 4f, 0f);
                    tr.ah.add(tb, 0f);
                    tr.ah.add(tb + 3f, 0.10f);
                    tr.ah.add(t1 - 1f, 0f);
                } else {
                    tr.av.add(t0 + 3f, 0f);
                    tr.av.add(t1 - 1f, 0f);
                    tr.ah.add(t0 + 3f, 0f);
                    tr.ah.add(tb, 0f);
                    tr.ah.add(tb + 3f, 0.34f);
                    tr.ah.add(Math.max(tb + 3f, t1 - 2f), 0.26f);
                }
                tr.af.add(t0 + 3f, 0f);
                tr.af.add(t1 - 1f, 0f);
                tr.nz.add(t0 + 3f, 0f);
                tr.nz.add(t1 - 1f, 0f);
                // الانفجار
                tr.bu.add(t0 + 3f, 0f);
                tr.bu.add(tb - 0.5f, 0f);
                tr.bu.add(tb + 0.8f, p.ba);
                tr.bu.add(tb + 4f, p.ba * 0.4f);
                tr.bu.add(Math.max(tb + 11f, tb + 5f), 0f);
                tr.bc.add(tb - 1f, p.bc * (1f + (fs - 1f) * 0.5f));
                tr.bb.add(tb - 1f, p.bb);
                fricKeys(tr, t0 + 3f, t1 - 1f, 4000f, 2000f, 1f);
                break;
            }
            case AFR: {
                float clo = p.clo * (x.gem ? 1.4f : 1f);
                float tb = t0 + clo;
                formants(tr, t0 + 4f, f1, f2, f3, 120f, 140f, 220f, fs);
                formants(tr, t1 - 6f, f1, f2, f3, 120f, 140f, 220f, fs);
                tr.av.add(t0 + 4f, 0.2f);
                tr.av.add(tb, 0.2f);
                tr.av.add(tb + 6f, 0.4f);
                tr.av.add(t1 - 8f, 0.4f);
                tr.av.add(t1 - 1f, 0.5f);
                tr.ah.add(t0 + 3f, 0f);
                tr.ah.add(t1 - 1f, 0f);
                tr.nz.add(t0 + 3f, 0f);
                tr.nz.add(t1 - 1f, 0f);
                tr.af.add(t0 + 3f, 0f);
                tr.af.add(tb, 0f);
                tr.af.add(tb + 8f, p.fa);
                tr.af.add(t1 - 8f, p.fa);
                tr.af.add(t1 - 1f, 0f);
                fricKeys(tr, t0 + 3f, t1 - 1f, p.fc, p.fb, fs);
                tr.bu.add(t0 + 3f, 0f);
                tr.bu.add(tb - 0.5f, 0f);
                tr.bu.add(tb + 0.8f, p.ba);
                tr.bu.add(tb + 5f, 0f);
                tr.bc.add(tb - 1f, p.bc * (1f + (fs - 1f) * 0.5f));
                tr.bb.add(tb - 1f, p.bb);
                break;
            }
            case FR: {
                formants(tr, t0 + fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                formants(tr, t1 - fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                float av = p.voiced ? p.av : 0f;
                amps(tr, t0, t1, av, 0f, p.fa, 0f, 8f, 8f);
                fricKeys(tr, t0 + 8f, t1 - 8f, p.fc, p.fb, fs);
                break;
            }
            case NA: {
                formants(tr, t0 + fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                formants(tr, t1 - fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                amps(tr, t0, t1, p.av, 0f, 0f, p.nz, 8f, 8f);
                fricKeys(tr, t0 + 8f, t1 - 8f, 4000f, 2000f, 1f);
                break;
            }
            case LQ: {
                formants(tr, t0 + fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                formants(tr, t1 - fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                if (p.sym.equals("r")) {
                    // نقرة (أو ترديد لو مشدّدة): هبوط قصير في سعة الصوت
                    float taps = x.gem ? 3f : 1f;
                    float seg = (dur - 8f) / taps;
                    float cur = t0 + 4f;
                    tr.av.add(cur, p.av);
                    for (int q = 0; q < (int) taps; q++) {
                        float mid = cur + seg * 0.5f;
                        tr.av.add(mid - 7f, p.av);
                        tr.av.add(mid, 0.12f);
                        tr.av.add(mid + 7f, p.av);
                        cur += seg;
                    }
                    tr.av.add(t1 - 4f, p.av);
                    tr.ah.add(t0 + 4f, 0f);
                    tr.ah.add(t1 - 4f, 0f);
                    tr.af.add(t0 + 4f, 0f);
                    tr.af.add(t1 - 4f, 0f);
                    tr.nz.add(t0 + 4f, 0f);
                    tr.nz.add(t1 - 4f, 0f);
                    tr.bu.add(t0 + 4f, 0f);
                    tr.bu.add(t1 - 4f, 0f);
                } else {
                    amps(tr, t0, t1, p.av, 0f, 0f, 0f, 7f, 7f);
                }
                fricKeys(tr, t0 + 7f, t1 - 7f, 4000f, 2000f, 1f);
                break;
            }
            case GL: {
                formants(tr, t0 + fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                formants(tr, t1 - fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                amps(tr, t0, t1, p.av, 0f, 0f, 0f, 7f, 7f);
                fricKeys(tr, t0 + 7f, t1 - 7f, 4000f, 2000f, 1f);
                break;
            }
            case HG: {
                formants(tr, t0 + 4f, f1, f2, f3, 130f, 150f, 220f, fs);
                formants(tr, t1 - 4f, f1, f2, f3, 130f, 150f, 220f, fs);
                amps(tr, t0, t1, 0f, 0.5f, 0f, 0f, 10f, 10f);
                fricKeys(tr, t0 + 10f, t1 - 10f, 4000f, 2000f, 1f);
                break;
            }
            case PH: {
                formants(tr, t0 + fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                formants(tr, t1 - fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                amps(tr, t0, t1, 0.12f, 0.55f, 0f, 0f, 9f, 9f);
                fricKeys(tr, t0 + 9f, t1 - 9f, 4000f, 2000f, 1f);
                break;
            }
            case PV: {
                formants(tr, t0 + fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                formants(tr, t1 - fa, f1, f2, f3, p.b1, p.b2, p.b3, fs);
                amps(tr, t0, t1, p.av, 0.06f, 0f, 0f, 8f, 8f);
                fricKeys(tr, t0 + 8f, t1 - 8f, 4000f, 2000f, 1f);
                tr.jit.add(t0 - 6f, voice.jitter);
                tr.jit.add(t0 + 6f, 0.03f);
                tr.jit.add(t1 + 4f, 0.03f);
                tr.jit.add(t1 + 18f, voice.jitter);
                break;
            }
            case GS: {
                formants(tr, t0 + 2f, f1, f2, f3, 100f, 130f, 200f, fs);
                formants(tr, t1 - 2f, f1, f2, f3, 100f, 130f, 200f, fs);
                amps(tr, t0, t1, 0f, 0f, 0f, 0f, 4f, 4f);
                fricKeys(tr, t0 + 4f, t1 - 4f, 4000f, 2000f, 1f);
                tr.jit.add(t0 - 10f, voice.jitter);
                tr.jit.add(t0, 0.035f);
                tr.jit.add(t1 + 12f, 0.035f);
                tr.jit.add(t1 + 28f, voice.jitter);
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ نغمة الصوت

    private static float[] buildF0(List<Ph> ph, int len, Voice voice, int pitchHz) {
        float base = voice.f0 + pitchHz * voice.f0 / 115f;
        base = clamp(base, 70f, 320f);
        float[] f0 = new float[len];
        Arrays.fill(f0, base);
        int cnt = ph.size();
        int k = 0;
        float last = base;
        while (k < cnt) {
            if (ph.get(k).p.type == SIL) {
                k++;
                continue;
            }
            int s = k;
            while (k < cnt && ph.get(k).p.type != SIL) k++;
            int e = k; // الجملة = [s, e)
            int type = (e < cnt) ? ph.get(e).silType : 0;
            float ts = ph.get(s).t0;
            Ph le = ph.get(e - 1);
            float te = le.t0 + le.dur;
            float span = Math.max(80f, te - ts);
            int from = (int) Math.max(0, Math.floor(ts));
            int to = (int) Math.min(len, Math.ceil(te) + 1);
            float finalLen = Math.min(300f, 0.45f * span);
            // مراكز المقاطع المنبورة
            List<Float> centers = new ArrayList<>();
            for (int q = s; q < e; q++) {
                Ph x = ph.get(q);
                if (x.p.type == V && x.stressed) centers.add(x.t0 + x.dur * 0.5f);
            }
            for (int m = from; m < to; m++) {
                float prog = (m - ts) / span;
                float decl;
                if (type == 1) decl = 1.0f - 0.03f * prog;
                else if (type == 2) decl = 1.05f - 0.09f * prog;
                else decl = 1.09f - 0.17f * prog;
                float f = base * decl;
                for (int c = 0; c < centers.size(); c++) {
                    float d = (m - centers.get(c)) / 75f;
                    f *= 1f + 0.085f * (float) Math.exp(-d * d);
                }
                float u = (m - (te - finalLen)) / finalLen;
                if (u > 0f) {
                    u = Math.min(1f, u);
                    float sm = u * u * (3f - 2f * u);
                    if (type == 1) f *= 1f + 0.30f * sm;
                    else if (type == 2) f *= 1f + 0.06f * sm;
                    else f *= 1f - 0.20f * sm;
                }
                float lead = (m - ts) / 90f;
                if (lead < 1f) f *= 1f + 0.04f * (1f - lead);
                f0[m] = f;
                last = f;
            }
            // بين الجمل: عودة تدريجية للطبقة الأساسية
            if (e < cnt) {
                Ph sil = ph.get(e);
                int a = (int) Math.max(0, Math.floor(sil.t0));
                int b = (int) Math.min(len, Math.ceil(sil.t0 + sil.dur) + 1);
                for (int m = a; m < b; m++) {
                    float r = b > a ? (m - a) / (float) (b - a) : 1f;
                    f0[m] = last + (base * 1.06f - last) * r;
                }
            }
        }
        // تنعيم (متوسط متحرك 24ms)
        float[] sm = new float[len];
        int w = 12;
        for (int i = 0; i < len; i++) {
            int a = Math.max(0, i - w);
            int b = Math.min(len - 1, i + w);
            float acc = 0f;
            for (int j = a; j <= b; j++) acc += f0[j];
            sm[i] = clamp(acc / (b - a + 1), 60f, 380f);
        }
        return sm;
    }

    // ------------------------------------------------------------------ المرشّحات والتوليد

    private static final class Rng {
        long s;

        Rng(long seed) {
            s = seed == 0 ? 0x2545F4914F6CDD1DL : seed;
        }

        /** عدد في [0,1). */
        float next() {
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            return (float) ((s >>> 40) / (double) (1L << 24));
        }

        /** ضجيج شبه غاوسي في [-1,1] تقريبًا. */
        float noise() {
            return (next() + next() + next() - 1.5f) * (2f / 3f) * 1.7f;
        }
    }

    private static final class Res {
        float a, b, c, y1, y2;

        void set(float f, float bw) {
            f = clamp(f, 40f, SAMPLE_RATE * 0.45f);
            bw = Math.max(30f, bw);
            double r = Math.exp(-Math.PI * bw * DT);
            c = (float) (-r * r);
            b = (float) (2.0 * r * Math.cos(2.0 * Math.PI * f * DT));
            a = 1f - b - c;
        }

        float run(float x) {
            float y = a * x + b * y1 + c * y2;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    /** مرشّح قبول نطاقي بقمة موحّدة تقريبًا (للاحتكاك والانفجار). */
    private static final class Band {
        float a, b, c, y1, y2;

        void set(float f, float bw) {
            f = clamp(f, 200f, SAMPLE_RATE * 0.46f);
            bw = Math.max(80f, bw);
            double r = Math.exp(-Math.PI * bw * DT);
            c = (float) (-r * r);
            b = (float) (2.0 * r * Math.cos(2.0 * Math.PI * f * DT));
            a = (float) (1.0 - r) * 1.2f;
        }

        float run(float x) {
            float y = a * x + b * y1 + c * y2;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    /** مضادّ رنين (صفر) للأنفيات. */
    private static final class Anti {
        float a, b, c, x1, x2;

        void set(float f, float bw) {
            double r = Math.exp(-Math.PI * bw * DT);
            double cc = -r * r;
            double bb = 2.0 * r * Math.cos(2.0 * Math.PI * f * DT);
            double aa = 1.0 - bb - cc;
            a = (float) (1.0 / aa);
            b = (float) (-bb / aa);
            c = (float) (-cc / aa);
        }

        float run(float x) {
            float y = a * x + b * x1 + c * x2;
            x2 = x1;
            x1 = x;
            return y;
        }
    }

    private static final int TABLE = 1024;

    private static float[] pulseTable(Voice v) {
        float[] tb = new float[TABLE];
        float oq = v.oq;
        float tn = oq * v.closing;
        float tp = oq - tn;
        float min = 0f;
        for (int i = 0; i < TABLE; i++) {
            float p = i / (float) TABLE;
            float d;
            if (p < tp) d = (float) (0.5 * Math.PI / tp * Math.sin(Math.PI * p / tp));
            else if (p < oq) d = (float) (-(Math.PI / (2.0 * tn)) * Math.sin(0.5 * Math.PI * (p - tp) / tn));
            else d = 0f;
            tb[i] = d;
            if (d < min) min = d;
        }
        float sc = min < -1e-6f ? -1f / min : 1f;
        float mean = 0f;
        for (int i = 0; i < TABLE; i++) {
            tb[i] *= sc;
            mean += tb[i];
        }
        mean /= TABLE;
        for (int i = 0; i < TABLE; i++) tb[i] -= mean;
        return tb;
    }

    private static float lerp(float[] a, int k, float fr) {
        return a[k] + (a[k + 1] - a[k]) * fr;
    }

    private static float[] render(int len, Voice voice, float[] F1, float[] F2, float[] F3, float[] B1, float[] B2, float[] B3,
                                  float[] AV, float[] AH, float[] AF, float[] NZ, float[] FC, float[] FB,
                                  float[] BU, float[] BC, float[] BB, float[] JT, float[] F0, Rng rng) {
        final int N = (int) Math.ceil((len - 2) * (double) SAMPLE_RATE / 1000.0);
        float[] out = new float[N];
        float[] tab = pulseTable(voice);
        float fs = voice.fscale;

        Res r1 = new Res(), r2 = new Res(), r3 = new Res(), r4 = new Res(), r5 = new Res();
        Res nasPole = new Res();
        Anti nasZero = new Anti();
        Band fricBand = new Band(), burstBand = new Band();
        r4.set(3400f * fs, 250f);
        r5.set(Math.min(4800f * fs, 5600f), 320f);
        nasPole.set(270f, 90f);
        nasZero.set(1100f, 260f);

        double phase = 0.0;
        float pulseAmp = 1f;
        float jitNow = 0f;
        float dcX = 0f, dcY = 0f;
        final float GV = 0.55f, GH = 0.55f, GF = 1.4f, GB = 0.9f;
        // ميل طيفي لمصدر الصوت (مرشّح تمرير منخفض بقطب واحد) يخفّف الطنين الحاد في الترددات العالية
        final float lpA = (float) (1.0 - Math.exp(-2.0 * Math.PI * (2400.0 + 1200.0 * (voice.fscale - 0.9)) * DT));
        float lpY = 0f;

        for (int n = 0; n < N; n++) {
            double tms = n * 1000.0 / SAMPLE_RATE;
            int k = (int) tms;
            if (k > len - 2) k = len - 2;
            float fr = (float) (tms - k);

            if ((n & 15) == 0) {
                r1.set(lerp(F1, k, fr), lerp(B1, k, fr));
                r2.set(lerp(F2, k, fr), lerp(B2, k, fr));
                r3.set(lerp(F3, k, fr), lerp(B3, k, fr));
                fricBand.set(lerp(FC, k, fr), lerp(FB, k, fr));
                burstBand.set(lerp(BC, k, fr), lerp(BB, k, fr));
            }
            float av = lerp(AV, k, fr);
            float ah = lerp(AH, k, fr);
            float af = lerp(AF, k, fr);
            float nz = lerp(NZ, k, fr);
            float bu = lerp(BU, k, fr);
            float jit = lerp(JT, k, fr);

            float f0 = lerp(F0, k, fr) * (1f + jitNow);
            phase += f0 * DT;
            if (phase >= 1.0) {
                phase -= 1.0;
                pulseAmp = 1f + 0.03f * (rng.next() - 0.5f) * 2f;
                jitNow = jit * (rng.next() - 0.5f) * 2f;
            }
            float g = tab[(int) (phase * TABLE) & (TABLE - 1)];
            lpY += lpA * (g - lpY);
            float voiced = lpY * av * pulseAmp * GV * 1.6f;
            float asp = rng.noise() * (ah + voice.breath * av * 0.30f) * GH;
            float src = voiced + asp;

            float y = src;
            if (nz > 0.01f) {
                float yn = nasZero.run(nasPole.run(src)) * 1.4f;
                y = (1f - nz) * src + nz * yn;
            }
            y = r1.run(y);
            y = r2.run(y);
            y = r3.run(y);
            y = r4.run(y);
            y = r5.run(y);

            float fric = af > 0.001f ? fricBand.run(rng.noise()) * af * GF : (fricBand.y1 = fricBand.y2 = 0f);
            float burst = bu > 0.001f ? burstBand.run(rng.noise()) * bu * GB : (burstBand.y1 = burstBand.y2 = 0f);

            float s = y + fric + burst;
            // مرشّح DC
            float dc = s - dcX + 0.995f * dcY;
            dcX = s;
            dcY = dc;
            out[n] = dc;
        }
        return out;
    }

    // ------------------------------------------------------------------ تطبيع وتصدير WAV

    private static byte[] toWav(float[] x) {
        int n = x.length;
        // مستوى الصوت: RMS للأجزاء المسموعة
        double acc = 0.0;
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(x[i]) > 1e-4f) {
                acc += (double) x[i] * x[i];
                c++;
            }
        }
        double rms = c > 0 ? Math.sqrt(acc / c) : 1.0;
        float gain = (float) (0.17 / Math.max(1e-6, rms));
        short[] pcm = new short[n];
        int fade = SAMPLE_RATE / 250;
        for (int i = 0; i < n; i++) {
            float v = x[i] * gain;
            float fadeG = 1f;
            if (i < fade) fadeG = i / (float) fade;
            else if (i > n - fade) fadeG = Math.max(0f, (n - i) / (float) fade);
            v = (float) Math.tanh(v) * fadeG;
            pcm[i] = (short) Math.round(clamp(v, -1f, 1f) * 32000f);
        }
        byte[] wav = new byte[44 + n * 2];
        int dataLen = n * 2;
        putStr(wav, 0, "RIFF");
        putInt(wav, 4, 36 + dataLen);
        putStr(wav, 8, "WAVE");
        putStr(wav, 12, "fmt ");
        putInt(wav, 16, 16);
        putShort(wav, 20, 1);
        putShort(wav, 22, 1);
        putInt(wav, 24, SAMPLE_RATE);
        putInt(wav, 28, SAMPLE_RATE * 2);
        putShort(wav, 32, 2);
        putShort(wav, 34, 16);
        putStr(wav, 36, "data");
        putInt(wav, 40, dataLen);
        for (int i = 0; i < n; i++) {
            wav[44 + i * 2] = (byte) (pcm[i] & 0xFF);
            wav[45 + i * 2] = (byte) ((pcm[i] >> 8) & 0xFF);
        }
        return wav;
    }

    private static void putStr(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i);
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
        b[o + 2] = (byte) ((v >> 16) & 0xFF);
        b[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShort(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private LocalSpeechEngine() {
    }
}
