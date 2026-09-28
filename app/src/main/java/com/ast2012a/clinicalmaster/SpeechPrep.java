package com.ast2012a.clinicalmaster;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * تجهيز نص المقطع للمحرك الصوتي (العصبي أو صوت الجهاز) قبل النطق.
 *
 * المشكلة: نص الـ PDF يحتوي رموزًا لا تُنطق (شرطات، نقاط تعداد، أقواس مراجع، روابط)،
 * وترقيمًا قد يُقرأ حرفيًا ("1-" تُنطق "واحد شرطة")، ووحدات لاتينية (mg, Hz) تُنطق حروفًا،
 * وأدوات إشارة وأسماء موصولة وألفاظ عموم (هذا، ذلك، الذي، كل...) يخطئ المحرك في تشكيلها.
 *
 * الحل: نحوّل كل كلمة (Token) إلى صيغتها المنطوقة، ونحتفظ بخريطة (موضع في النص المنطوق -> موضع
 * في النص الأصلي) حتى يبقى تظليل الكلمة الجاري نطقها صحيحًا فوق صفحة الـ PDF.
 * لا يلمس النص المعروض إطلاقًا - التعديل للنطق فقط.
 */
final class SpeechPrep {

    private SpeechPrep() {
    }

    /** النص المنطوق + خريطة كل حرف منه إلى موضع الكلمة الأصلية (نسبةً لبداية النص المُدخل). */
    /** مقطع من النص المنطوق بلغة واحدة (ar / en / fr / tr) - لاختيار الصوت الأدق لكل جزء. */
    static final class Run {
        final int start;
        final int end;
        final String lang;

        Run(int start, int end, String lang) {
            this.start = start;
            this.end = end;
            this.lang = lang;
        }
    }

    static final class Spoken {
        final String text;
        final int[] map;
        /** null = لغة واحدة. غير ذلك: مقاطع متتالية تغطي النص كله بلغات مختلفة (عربي داخل إنجليزي وبالعكس). */
        final List<Run> runs;

        Spoken(String text, int[] map) {
            this(text, map, null);
        }

        Spoken(String text, int[] map, List<Run> runs) {
            this.text = text;
            this.map = map;
            this.runs = runs;
        }

        boolean isMixed() {
            return runs != null && runs.size() > 1;
        }

        int toOriginal(int spokenOffset) {
            if (map == null || map.length == 0) return Math.max(0, spokenOffset);
            return map[Math.max(0, Math.min(map.length - 1, spokenOffset))];
        }
    }

    private static final String PUNCT = ".,;:!?\u060C\u061B\u061F";

    // ------------------------------------------------------------------ نقطة الدخول

    static Spoken prepare(String src, String lang) {
        return prepare(src, lang, null, false);
    }

    /**
     * latinLang = لغة الكلمات اللاتينية في الملف (en/fr/tr) عندما يكون المقطع عربيًا؛
     * mixed = true يُنتج runs لتبديل الصوت عند كل كلمة بلغة مختلفة.
     */
    static Spoken prepare(String src, String lang, String latinLang, boolean mixed) {
        LATIN.set(latinLang);
        try {
            return prepareImpl(src, lang, latinLang, mixed);
        } finally {
            LATIN.set(null);
        }
    }

    private static Spoken prepareImpl(String src, String lang, String latinLang, boolean mixed) {
        if (src == null || src.isEmpty()) return new Spoken("", new int[0]);
        final boolean ar = "ar".equals(lang);
        int n = src.length();

        // 1) تقطيع إلى كلمات مع موضع بداية كل واحدة في النص الأصلي
        List<Integer> starts = new ArrayList<>();
        List<String> toks = new ArrayList<>();
        int i = 0;
        while (i < n) {
            while (i < n && isSpace(src.charAt(i))) i++;
            if (i >= n) break;
            int s = i;
            while (i < n && !isSpace(src.charAt(i))) i++;
            starts.add(s);
            toks.add(src.substring(s, i));
        }

        // 2) دمج الكلمات العربية المتقطّعة في الـ PDF: "ال" منفصلة عن كلمتها، أو حروف متباعدة
        //    (كانت تُنطق "ألف لام" أو تُقطَّع الكلمة). التظليل يبقى على أول جزء.
        if (ar) {
            List<Integer> ms = new ArrayList<>();
            List<String> mt = new ArrayList<>();
            for (int k = 0; k < toks.size(); k++) {
                String cur = toks.get(k);
                final int firstIdx = k;
                boolean run = false;
                while (k + 1 < toks.size()) {
                    String nx = toks.get(k + 1);
                    int mode = glueMode(clean(cur), clean(nx), run);
                    if (mode == 0) break;
                    if (mode == 2) run = true;
                    cur = cur + nx;
                    k++;
                }
                ms.add(starts.get(firstIdx));
                mt.add(cur);
            }
            starts = ms;
            toks = mt;
        }

        // 3) تحويل كل كلمة إلى صيغتها المنطوقة مع خريطة المواضع
        StringBuilder out = new StringBuilder(src.length() + 32);
        int[] map = new int[src.length() + 64];
        int mlen = 0;
        String prevBare = "";
        boolean prevNum = false;
        boolean first = true;
        final List<Integer> runS = new ArrayList<>();
        final List<String> runL = new ArrayList<>();
        final List<Boolean> runU = new ArrayList<>();
        for (int t = 0; t < toks.size(); t++) {
            String tok = toks.get(t);
            int s = starts.get(t);
            String sp;
            try {
                sp = speakToken(tok, lang, first, prevBare, prevNum);
                // "بال" / "وال" منفصلة قبل كلمة لاتينية (بالـ TENS): تُنطق al لا "با ل"
                if (t + 1 < toks.size() && startsLatin(clean(toks.get(t + 1)))) {
                    String cb = bareOf(clean(tok));
                    String cl = AL_ONLY.contains(cb) ? ArabicPhonetics.clitic(cb) : null;
                    if (cl != null) sp = cl;
                }
            } catch (RuntimeException e) {
                sp = tok; // أي خطأ غير متوقع: ننطق الكلمة كما هي
            }
            String bare = bareOf(clean(tok));
            prevNum = NUMBER.matcher(bare).matches();
            prevBare = bare;
            if (sp.isEmpty()) continue;
            first = false;
            // أقواس: نضع وقفة قبل المحتوى وبعده ليُفهم أنه تفسير جانبي (ترقيم فقط - لا كلمات)
            String ct = clean(tok);
            if (out.length() > 0 && (ct.startsWith("(") || ct.startsWith("[") || ct.startsWith("\uFF08"))
                    && PUNCT.indexOf(out.charAt(out.length() - 1)) < 0) {
                map = ensure(map, mlen + 1);
                out.append(pause(lang));
                map[mlen++] = s;
            }
            if ((ct.endsWith(")") || ct.endsWith("]") || ct.endsWith("\uFF09"))
                    && PUNCT.indexOf(sp.charAt(sp.length() - 1)) < 0) {
                sp = sp + pause(lang);
            }
            boolean onlyPunct = sp.length() == 1 && PUNCT.indexOf(sp.charAt(0)) >= 0;
            if (onlyPunct && out.length() == 0) continue;
            if (out.length() > 0 && !onlyPunct) {
                map = ensure(map, mlen + 1);
                out.append(' ');
                map[mlen++] = s;
            }
            map = ensure(map, mlen + sp.length());
            if (mixed) {
                runS.add(out.length());
                runL.add(tokLang(sp, lang, latinLang));
                runU.add(U_EN.containsKey(bareOf(sp).toLowerCase(Locale.ROOT)));
            }
            for (int k = 0; k < sp.length(); k++) {
                out.append(sp.charAt(k));
                map[mlen++] = s;
            }
        }
        // نهاية المقطع (عنوان أو بند بلا نقطة): نختمه بنقطة ليهبط الصوت ويقف بدل أن يلتصق بما بعده
        if (out.length() > 0 && PUNCT.indexOf(out.charAt(out.length() - 1)) < 0) {
            map = ensure(map, mlen + 1);
            out.append('.');
            map[mlen] = map[Math.max(0, mlen - 1)];
            mlen++;
        }
        List<Run> runs = mixed ? buildRuns(runS, runL, runU, out.length(), lang) : null;
        return new Spoken(out.toString(), Arrays.copyOf(map, mlen), runs);
    }

    private static final ThreadLocal<String> LATIN = new ThreadLocal<>();

    private static boolean isArabicScript(char c) {
        return (c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F);
    }

    /** لغة الكلمة المنطوقة: عربية لو حروفها عربية، وإلا لغة المقطع (أو لغة الملف اللاتينية لو المقطع عربي). null = أرقام/رموز. */
    private static String tokLang(String sp, String chunkLang, String latinLang) {
        int ar = 0, lat = 0;
        for (int i = 0; i < sp.length(); i++) {
            char c = sp.charAt(i);
            if (!Character.isLetter(c)) continue;
            if (isArabicScript(c)) ar++;
            else lat++;
        }
        if (ar == 0 && lat == 0) return null;
        if (ar >= lat) return "ar";
        if ("ar".equals(chunkLang)) return latinLang != null ? latinLang : "en";
        return chunkLang;
    }

    private static List<Run> buildRuns(List<Integer> starts, List<String> langs, List<Boolean> unit, int total, String def) {
        int n = starts.size();
        if (n == 0) return null;
        String[] eff = new String[n];
        for (int i = 0; i < n; i++) {
            String l = langs.get(i);
            if (l == null) {
                if (i + 1 < n && langs.get(i + 1) != null && unit.get(i + 1) && !"ar".equals(langs.get(i + 1))) {
                    l = langs.get(i + 1); // 20 Hz: الرقم مع وحدته
                } else if (i > 0) {
                    l = eff[i - 1];
                } else {
                    for (int k = 1; k < n && l == null; k++) l = langs.get(k);
                }
                if (l == null) l = def;
            }
            eff[i] = l;
        }
        List<Run> runs = new ArrayList<>();
        int runStart = 0;
        String cur = eff[0];
        for (int i = 1; i < n; i++) {
            if (!eff[i].equals(cur)) {
                runs.add(new Run(runStart, starts.get(i), cur));
                runStart = starts.get(i);
                cur = eff[i];
            }
        }
        runs.add(new Run(runStart, total, cur));
        return runs.size() > 1 ? runs : null;
    }

    /** 0 = لا دمج، 1 = دمج عادي (ال / حرف عطف)، 2 = دمج حروف متباعدة. */
    private static int glueMode(String a, String b, boolean inRun) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (!isArabicLetter(a.charAt(a.length() - 1)) || !isArabicLetter(b.charAt(0))) return 0;
        // أداة التعريف منفصلة: ال / وال / فال / بال / كال / لل
        if (AL_ONLY.contains(a)) return 1;
        // "الأ" / "بالإ" منفصلة عن بقية الكلمة
        if (a.length() >= 3 && "\u0623\u0625\u0622".indexOf(a.charAt(a.length() - 1)) >= 0
                && AL_ONLY.contains(a.substring(0, a.length() - 1))) return 1;
        // حرف عطف/جر منفرد قبل كلمة: و علي -> وعلي
        if (a.length() == 1 && "\u0648\u0641\u0628\u0644\u0643".indexOf(a.charAt(0)) >= 0 && b.length() >= 2) return 1;
        // حروف متباعدة: ع ض ل ة
        if (b.length() == 1 && (inRun || (a.length() == 1 && a.charAt(0) != '\u0648'))) return 2;
        return 0;
    }

    private static final Set<String> AL_ONLY = new HashSet<>(Arrays.asList(
            "\u0627\u0644", "\u0648\u0627\u0644", "\u0641\u0627\u0644", "\u0628\u0627\u0644",
            "\u0643\u0627\u0644", "\u0644\u0644", "\u0648\u0644\u0644", "\u0641\u0644\u0644"));

    private static boolean isArabicLetter(char c) {
        return (c >= 0x0621 && c <= 0x064A) || (c >= 0x064B && c <= 0x065F) || c == 0x0671;
    }

    private static int[] ensure(int[] a, int need) {
        if (need <= a.length) return a;
        return Arrays.copyOf(a, Math.max(need, a.length * 2));
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || c == '\u00A0' || c == '\u202F' || c == '\u2007';
    }

    // ------------------------------------------------------------------ الكلمة الواحدة

    private static final Pattern NUMBER = Pattern.compile("^\\d[\\d.,]*(?:[-\u2212\u2013\u2014]\\d[\\d.,]*)?$");
    private static final Pattern CITATION = Pattern.compile("^\\[\\d+(?:[,;\\-\u2013]\\s?\\d+)*\\][.,;:!?\u060C\u061B\u061F]*$");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+\\-]+@[\\w\\-]+(?:\\.[\\w\\-]+)+$");
    private static final Pattern LIST_MARK = Pattern.compile("^[(\\[]?(\\d{1,3})[)\\].\\-\u2013\u2014:]$");
    private static final Pattern SECTION = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){2,4}$");
    private static final Pattern DEG = Pattern.compile("^(\\d+(?:\\.\\d+)?)\u00B0([CFcf])?$");
    private static final Pattern RANGE = Pattern.compile("^(\\d+(?:[.,]\\d+)?)[-\u2212\u2013\u2014](\\d+(?:[.,]\\d+)?)$");
    private static final Pattern RANGE_UNIT = Pattern.compile(
            "^(\\d+(?:[.,]\\d+)?)[-\u2212\u2013\u2014](\\d+(?:[.,]\\d+)?)([A-Za-z\u00B5\u03BC][A-Za-z\u00B5\u03BC/\u00B2\u00B3\\d]*)$");
    private static final Pattern NUMUNIT = Pattern.compile(
            "^(\\d+(?:[.,]\\d+)?)([A-Za-z\u00B5\u03BC][A-Za-z\u00B5\u03BC/\u00B2\u00B3\\d]*)$");
    private static final Pattern UNITPART = Pattern.compile("^([A-Za-z\u00B5]+)([23])?$");

    /**
     * false (الافتراضي) = القراءة الأمينة: لا نضيف أي كلمة غير موجودة في النص (لا "إلى" ولا "أو" ولا "درجة مئوية"...).
     * نحذف فقط ما لا يُنطق (شرطات، نقاط تعداد، مراجع، روابط) ونُصلح ما يُخطئ فيه المحرك.
     * true = يشرح الرموز والوحدات بكلمات (mA -> ملي أمبير، % -> بالمئة، / -> أو ...).
     */
    static final boolean EXPAND_SYMBOLS = false;

    /** رموز نتركها كما هي في النص للمحرك (هو يعرف نطقها) ولا نشرحها نحن. */
    private static final String KEEP_SYM = "+=<>\u00B1\u00D7\u00F7\u2265\u2264%\u00B0";
    private static final Pattern RANGE_ANY = Pattern.compile(
            "^(\\d+(?:[.,]\\d+)?)[-\u2212\u2013\u2014](\\d+(?:[.,]\\d+)?)(.*)$");
    private static final String DASHES = "-\u2010\u2011\u2012\u2013\u2014\u2015\u2212";

    private static String pause(String lang) {
        return "ar".equals(lang) ? "\u060C" : ",";
    }

    private static boolean isDashOnly(String t) {
        for (int i = 0; i < t.length(); i++) {
            if (DASHES.indexOf(t.charAt(i)) < 0) return false;
        }
        return !t.isEmpty();
    }

    private static String keepSyms(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (KEEP_SYM.indexOf(c) >= 0) sb.append(c);
        }
        return sb.toString();
    }

    private static String speakToken(String raw, String lang, boolean first, String prevBare, boolean prevNum) {
        if (EXPAND_SYMBOLS) return speakTokenExpanded(raw, lang, first, prevBare, prevNum);
        final boolean ar = "ar".equals(lang);
        String t = clean(raw);
        if (t.isEmpty()) return "";
        String low = t.toLowerCase(Locale.ROOT);
        if (CITATION.matcher(t).matches()) return lastPunctIn(t);
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("www.")
                || EMAIL.matcher(bareOf(t)).matches()) {
            return lastPunctIn(t); // الروابط والبريد لا تُقرأ حرفًا حرفًا
        }
        if (isDashOnly(t)) return first ? "" : pause(lang); // " - " بين جملتين = وقفة فقط
        if (first) {
            Matcher m = LIST_MARK.matcher(t);
            if (m.matches()) return m.group(1) + pause(lang);
        }
        int a = 0, b = t.length();
        while (a < b && !isWordChar(t.charAt(a))) a++;
        while (b > a && !isWordChar(t.charAt(b - 1))) b--;
        if (a >= b) {
            String k = keepSyms(t);
            return k + lastPunctIn(t);
        }
        String pre = keepSyms(t.substring(0, a));
        String core = t.substring(a, b);
        String tail = t.substring(b);
        String body = speakCore(core, lang, !lastPunctIn(tail).isEmpty());
        String res = pre + body + keepSyms(tail);
        return res + lastPunctIn(tail);
    }

    // ------------------------------------------------------------------ خيارات قابلة للتعديل (من إعدادات القراءة)

    /** تشكيل ذكي للكلمات العربية غير المشكولة (قاموس + وقف بالسكون للنص المشكول). */
    private static volatile boolean assist = true;
    /** نطق الاختصارات اللاتينية (EMG, MRI...) حرفًا حرفًا. */
    private static volatile boolean spellAcronyms = true;
    private static volatile Map<String, String> userLex = new HashMap<>();

    static void setArabicAssist(boolean v) {
        assist = v;
    }

    static void setSpellAcronyms(boolean v) {
        spellAcronyms = v;
    }

    /** أسطر بصيغة: كلمة=نطقها  (أو  كلمة=>نطقها). النطق يمكن أن يكون بحروف عربية لكلمة أجنبية. */
    static void setUserLexicon(String text) {
        Map<String, String> m = new HashMap<>();
        if (text != null) {
            for (String line : text.split("\\r?\\n")) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                int k = l.indexOf("=>");
                int len = 2;
                if (k < 0) {
                    k = l.indexOf('=');
                    len = 1;
                }
                if (k <= 0) continue;
                String key = lexKey(l.substring(0, k).trim());
                String val = l.substring(k + len).trim();
                if (!key.isEmpty() && !val.isEmpty()) m.put(key, val);
            }
        }
        userLex = m;
    }

    private static String lexKey(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (ArabicPhonetics.isMark(c)) continue;
            sb.append(Character.toLowerCase(c));
        }
        return ArabicPhonetics.normalize(sb.toString());
    }

    private static String speakCore(String core, String lang, boolean pausal) {
        String ul = userLex.get(lexKey(core));
        if (ul != null) return ul;
        boolean hasAr = ArabicPhonetics.hasArabic(core);
        if (hasAr && hasLatinOrDigit(core)) {
            String mixedScript = speakMixedScript(core, lang, pausal);
            if (mixedScript != null) return mixedScript;
        }
        return polish(greekWords(faithfulCore(core, lang), "ar".equals(lang)), lang, pausal);
    }

    private static boolean startsLatin(String s) {
        String b = bareOf(s);
        if (b.isEmpty()) return false;
        char c = b.charAt(0);
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean hasLatinOrDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) return true;
        }
        return false;
    }

    /**
     * كلمة تخلط الكتابتين: "الـMRI" / "بالـTENS" / "3جلسات". نفصلها لجزأين حتى ينطق كل جزء بلغته
     * ("ال" تُنطق al لا "ألف لام"). لو الجزء العربي حرف واحد (ج2، م2) نتركها كما هي.
     */
    private static String speakMixedScript(String core, String lang, boolean pausal) {
        List<String> segs = new ArrayList<>();
        List<Boolean> isAr = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        Boolean curAr = null;
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            Boolean a = null;
            if (isArabicScript(c) && (Character.isLetter(c) || ArabicPhonetics.isMark(c))) a = Boolean.TRUE;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) a = Boolean.FALSE;
            if (a != null && curAr != null && a != curAr) {
                segs.add(cur.toString());
                isAr.add(curAr);
                cur.setLength(0);
            }
            if (a != null) curAr = a;
            cur.append(c);
        }
        if (cur.length() > 0 && curAr != null) {
            segs.add(cur.toString());
            isAr.add(curAr);
        }
        if (segs.size() < 2) return null;
        for (int i = 0; i < segs.size(); i++) {
            if (!isAr.get(i)) continue;
            int letters = 0;
            String sg = segs.get(i);
            for (int k = 0; k < sg.length(); k++) if (isArabicScript(sg.charAt(k)) && Character.isLetter(sg.charAt(k))) letters++;
            if (letters < 2) return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            String sg = segs.get(i);
            boolean last = i == segs.size() - 1;
            String part;
            if (isAr.get(i)) {
                String cl = (!last && isPlainArabicWord(sg)) ? ArabicPhonetics.clitic(sg) : null;
                part = cl != null ? cl : polish(faithfulCore(sg, lang), lang, pausal && last);
            } else {
                part = polish(greekWords(faithfulCore(sg, lang), "ar".equals(lang)), lang, false);
            }
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part);
        }
        return sb.toString();
    }

    /** لكل كلمة: عربية -> تشكيل/وقف؛ لاتينية -> اختصارات تُنطق بشكلها الصحيح. */
    private static String polish(String body, String lang, boolean pausal) {
        if (body.isEmpty()) return body;
        String[] parts = body.split(" ");
        StringBuilder sb = new StringBuilder(body.length() + 16);
        for (int k = 0; k < parts.length; k++) {
            if (k > 0) sb.append(' ');
            String w = parts[k];
            if (ArabicPhonetics.hasArabic(w)) {
                w = diacritize(w);
                if (assist && pausal && k == parts.length - 1) w = ArabicPhonetics.pausal(w);
            } else if (spellAcronyms && englishContext(lang)) {
                w = acronym(w);
            }
            sb.append(w);
        }
        return sb.toString();
    }

    private static boolean englishContext(String lang) {
        if ("en".equals(lang)) return true;
        if ("ar".equals(lang)) {
            String l = LATIN.get();
            return l == null || "en".equals(l);
        }
        return false;
    }

    private static final Set<String> ACRO_WORD = new HashSet<>(Arrays.asList(
            "TENS", "NASA", "DOMS", "COVID", "AIDS", "RICE", "PRICE", "LASER", "BOSU", "SARS", "NICE", "SWOT"));
    private static final Set<String> ACRO_SPELL = new HashSet<>(Arrays.asList(
            "EMG", "MRI", "CT", "ROM", "VAS", "ACL", "PCL", "MCL", "LCL", "ASIS", "PNF", "CPM", "ADL", "ICU", "BMI",
            "HIV", "DNA", "RNA", "USA", "UK", "ECG", "EKG", "EEG", "NCV", "TMJ", "SI", "OA", "RA", "MS", "ALS", "ADHD",
            "CPR", "BP", "HR", "PT", "OT", "DVT", "COPD", "WHO", "CNS", "PNS", "IV", "IM", "PRP", "EMS", "NMES", "FES",
            "PDF", "AI", "ER", "OR", "WBC", "RBC", "CRP", "ESR", "SLR", "MMT", "GCS", "TUG", "FIM", "DASH", "ODI"));

    /** EMG -> "E M G"، TENS -> "Tens"، NSAID -> "en said"؛ غير ذلك كما هو. */
    private static String acronym(String w) {
        int n = w.length();
        if (n < 2 || n > 7) return w;
        boolean plural = n >= 3 && w.charAt(n - 1) == 's';
        String base = plural ? w.substring(0, n - 1) : w;
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c < 'A' || c > 'Z') return w;
        }
        if (base.equals("NSAID")) return "en said" + (plural ? "s" : "");
        if (ACRO_WORD.contains(base)) return base.charAt(0) + base.substring(1).toLowerCase(Locale.ROOT) + (plural ? "s" : "");
        boolean vowel = false;
        for (int i = 0; i < base.length(); i++) {
            if ("AEIOUY".indexOf(base.charAt(i)) >= 0) vowel = true;
        }
        if (!ACRO_SPELL.contains(base) && (vowel || base.length() > 5)) return w;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(base.charAt(i));
        }
        if (plural) sb.append('s');
        return sb.toString();
    }

    private static final String GREEK_EN[] = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta",
            "iota", "kappa", "lambda", "mu", "nu", "xi", "omicron", "pi", "rho", "sigma", "sigma", "tau", "upsilon",
            "phi", "chi", "psi", "omega"};
    private static final String GREEK_AR[] = {"\u0623\u0644\u0641\u0627", "\u0628\u064A\u062A\u0627", "\u062C\u0627\u0645\u0627",
            "\u062F\u0644\u062A\u0627", "\u0625\u0628\u0633\u0644\u0648\u0646", "\u0632\u064A\u062A\u0627", "\u0625\u064A\u062A\u0627",
            "\u062B\u064A\u062A\u0627", "\u0623\u064A\u0648\u062A\u0627", "\u0643\u0627\u0628\u0627", "\u0644\u0627\u0645\u062F\u0627",
            "\u0645\u064A\u0648", "\u0646\u064A\u0648", "\u0643\u0633\u064A", "\u0623\u0648\u0645\u064A\u0643\u0631\u0648\u0646",
            "\u0628\u0627\u064A", "\u0631\u0648", "\u0633\u064A\u063A\u0645\u0627", "\u0633\u064A\u063A\u0645\u0627",
            "\u062A\u0648", "\u0623\u0628\u0633\u0644\u0648\u0646", "\u0641\u0627\u064A", "\u0643\u0627\u064A",
            "\u0628\u0633\u0627\u064A", "\u0623\u0648\u0645\u064A\u063A\u0627"};

    /** α β μ Δ Ω ... تُنطق باسمها (المحركات غالبًا تتجاهلها). */
    private static String greekWords(String s, boolean ar) {
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x0391 && c <= 0x03C9) || c == 0x00B5) {
                any = true;
                break;
            }
        }
        if (!any) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = -1;
            if (c == 0x00B5) idx = 11;
            else if (c >= 0x0391 && c <= 0x03A9) idx = c - 0x0391;
            else if (c >= 0x03B1 && c <= 0x03C9) idx = c - 0x03B1;
            if (idx >= 0 && idx < GREEK_EN.length) {
                String name = c == 0x00B5 ? (ar ? "\u0645\u0627\u064A\u0643\u0631\u0648" : "micro") : (ar ? GREEK_AR[idx] : GREEK_EN[idx]);
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
                sb.append(name);
                if (i + 1 < s.length()) sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /** يُبقي حروف الكلمة وأرقامها فقط، والرموز الداخلية (شرطة، شرطة مائلة، أقواس...) تصير فراغًا. */
    private static String faithfulCore(String core, String lang) {
        final boolean ar = "ar".equals(lang);
        Matcher m = RANGE_ANY.matcher(core);
        if (m.matches()) { // 50-100 -> "50، 100" (وقفة بدل كلمة "إلى")
            String rest = m.group(3);
            return m.group(1) + pause(lang) + " " + m.group(2) + (rest.isEmpty() ? "" : faithfulCore(rest, lang));
        }
        String c2 = core;
        if (ar) c2 = c2.replace("\u0648/\u0623\u0648", "\u0648 \u0623\u0648").replace("\u0648/\u0627\u0648", "\u0648 \u0623\u0648");
        if ("en".equals(lang)) c2 = c2.replace("and/or", "and or");
        int len = c2.length();
        StringBuilder sb = new StringBuilder(len + 4);
        for (int k = 0; k < len; k++) {
            char c = c2.charAt(k);
            if (isWordChar(c) || c == '.' || c == ',' || c == '\'' || c == '\u2019' || KEEP_SYM.indexOf(c) >= 0) {
                sb.append(c);
            } else if (c == '/' && k > 0 && k + 1 < len && Character.isDigit(c2.charAt(k - 1))
                    && Character.isDigit(c2.charAt(k + 1))) {
                sb.append('/'); // 1/2 تبقى كسرًا
            } else if ("\"\u00AB\u00BB\u201C\u201D\u201E\u2018\u2039\u203A".indexOf(c) >= 0) {
                // علامات الاقتباس لا تُنطق
            } else {
                sb.append(' ');
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String speakTokenExpanded(String raw, String lang, boolean first, String prevBare, boolean prevNum) {
        final boolean ar = "ar".equals(lang);
        final boolean en = "en".equals(lang);
        String t = clean(raw);
        if (t.isEmpty()) return "";
        String low = t.toLowerCase(Locale.ROOT);

        // مراجع رقمية [12] [3-5]: لا تُقرأ
        if (CITATION.matcher(t).matches()) return lastPunctIn(t);
        // روابط وبريد
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("www.")) {
            return linkWord(lang) + lastPunctIn(t);
        }
        if (EMAIL.matcher(bareOf(t)).matches()) return emailWord(lang) + lastPunctIn(t);
        // بداية بند مرقّم: "1-" أو "1." أو "(1)" -> "1،" بدل "واحد شرطة"
        if (first) {
            Matcher m = LIST_MARK.matcher(t);
            if (m.matches()) return m.group(1) + (ar ? "\u060C" : ",");
        }
        if (en) {
            String ab = EN_ABBR.get(low);
            if (ab != null) return ab;
        }

        int a = 0, b = t.length();
        while (a < b && !isWordChar(t.charAt(a))) a++;
        while (b > a && !isWordChar(t.charAt(b - 1))) b--;
        if (a >= b) return lastPunctIn(t);
        String lead = t.substring(0, a);
        String core = t.substring(a, b);
        String tail = t.substring(b);
        String punct = lastPunctIn(tail);

        // "37 °C"
        if (lead.indexOf('\u00B0') >= 0 && prevNum && (core.equals("C") || core.equals("F"))) {
            return degreeWord(lang, core.charAt(0)) + punct;
        }
        // اختصارات عربية
        if (ar) {
            if (core.equals("\u062F") && tail.indexOf('.') >= 0) return "\u062F\u0643\u062A\u0648\u0631";
            if (core.equals("\u0623.\u062F")) return "\u0623\u0633\u062A\u0627\u0630 \u062F\u0643\u062A\u0648\u0631";
            String ab = AR_ABBR.get(core);
            if (ab != null) return ab + punct;
        }

        boolean minus = false;
        if (!lead.isEmpty() && !prevNum && Character.isDigit(core.charAt(0))) {
            char lc = lead.charAt(lead.length() - 1);
            minus = lc == '-' || lc == '\u2212' || lc == '\u2013' || lc == '\u2014';
        }
        StringBuilder pre = new StringBuilder();
        for (int k = 0; k < lead.length(); k++) {
            String w = symWord(lead.charAt(k), ar, en);
            if (w != null) pre.append(w);
        }

        String body;
        if (ar && core.startsWith("\u062F/") && core.length() > 2) {
            body = "\u062F\u0643\u062A\u0648\u0631 " + coreToSpoken(core.substring(2), lang, false, prevNum);
        } else {
            body = coreToSpoken(core, lang, first, prevNum);
        }
        body = polish(body, lang, false);

        boolean pct = false, deg = false;
        StringBuilder sym = new StringBuilder();
        for (int k = 0; k < tail.length(); k++) {
            char c = tail.charAt(k);
            if (c == '%' || c == '\u066A') pct = true;
            else if (c == '\u00B0') deg = true;
            else if (PUNCT.indexOf(c) < 0 && c != '\u2026') {
                String w = symWord(c, ar, en);
                if (w != null) sym.append(w);
            }
        }

        StringBuilder r = new StringBuilder();
        if (minus) r.append(minusWord(lang)).append(' ');
        if (pct && "tr".equals(lang)) r.append("y\u00FCzde ");
        r.append(pre).append(' ').append(body);
        if (pct) r.append(' ').append(percentWord(lang));
        if (deg) r.append(' ').append(degWord(lang));
        r.append(' ').append(sym);
        String res = r.toString().replaceAll("\\s+", " ").trim();
        return res.isEmpty() ? punct : res + punct;
    }

    // ------------------------------------------------------------------ جسم الكلمة (أرقام، وحدات، رموز داخلية)

    private static String coreToSpoken(String core, String lang, boolean first, boolean prevNum) {
        final boolean ar = "ar".equals(lang);
        final boolean en = "en".equals(lang);
        Matcher m;
        if (first && SECTION.matcher(core).matches()) {
            return core.replace(".", ar ? " \u0646\u0642\u0637\u0629 " : en ? " point " : " ").trim();
        }
        if ((m = DEG.matcher(core)).matches()) {
            String c = m.group(2);
            return m.group(1) + " " + (c == null ? degWord(lang) : degreeWord(lang, c.charAt(0)));
        }
        if ((m = RANGE.matcher(core)).matches()) {
            return m.group(1) + rangeWord(lang) + m.group(2);
        }
        if ((m = RANGE_UNIT.matcher(core)).matches()) {
            String u = unitSpoken(m.group(3), ar, en, true);
            if (u != null) return m.group(1) + rangeWord(lang) + m.group(2) + " " + u;
        }
        if ((m = NUMUNIT.matcher(core)).matches()) {
            String u = unitSpoken(m.group(2), ar, en, true);
            if (u != null) return m.group(1) + " " + u;
        }
        if (prevNum && core.length() >= 2) {
            String u = unitSpoken(core, ar, en, false);
            if (u != null) return u;
        }

        String c2 = core;
        if (ar) c2 = c2.replace("\u0648/\u0623\u0648", "\u0648 \u0623\u0648").replace("\u0648/\u0627\u0648", "\u0648 \u0623\u0648");
        if (en) c2 = c2.replace("and/or", "and or");
        int len = c2.length();
        StringBuilder sb = new StringBuilder(len + 8);
        for (int k = 0; k < len; k++) {
            char c = c2.charAt(k);
            if (isWordChar(c) || c == '.' || c == ',' || c == '\'' || c == '\u2019') {
                sb.append(c);
                continue;
            }
            if (c == '/') {
                char p = k > 0 ? c2.charAt(k - 1) : ' ';
                char q = k + 1 < len ? c2.charAt(k + 1) : ' ';
                if (Character.isLetter(p) && Character.isLetter(q)) {
                    sb.append(ar ? " \u0623\u0648 " : en ? " or " : " ");
                } else if (Character.isDigit(p) && Character.isDigit(q)) {
                    sb.append(ar ? " \u0639\u0644\u0649 " : en ? " over " : " ");
                } else {
                    sb.append(' ');
                }
                continue;
            }
            if ("\"\u00AB\u00BB\u201C\u201D\u201E\u2018\u2039\u203A".indexOf(c) >= 0) continue;
            String w = symWord(c, ar, en);
            sb.append(w != null ? w : " "); // شرطات، شرطة سفلية، أقواس، نجوم... -> فراغ
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ كلمات الرموز

    private static String symWord(char c, boolean ar, boolean en) {
        if (!ar && !en) return null;
        switch (c) {
            case '+':
                return ar ? " \u0632\u0627\u0626\u062F " : " plus ";
            case '=':
                return ar ? " \u064A\u0633\u0627\u0648\u064A " : " equals ";
            case '>':
                return ar ? " \u0623\u0643\u0628\u0631 \u0645\u0646 " : " greater than ";
            case '<':
                return ar ? " \u0623\u0635\u063A\u0631 \u0645\u0646 " : " less than ";
            case '\u2265':
                return ar ? " \u0623\u0643\u0628\u0631 \u0645\u0646 \u0623\u0648 \u064A\u0633\u0627\u0648\u064A " : " greater than or equal to ";
            case '\u2264':
                return ar ? " \u0623\u0635\u063A\u0631 \u0645\u0646 \u0623\u0648 \u064A\u0633\u0627\u0648\u064A " : " less than or equal to ";
            case '\u00B1':
                return ar ? " \u0632\u0627\u0626\u062F \u0623\u0648 \u0646\u0627\u0642\u0635 " : " plus or minus ";
            case '\u00D7':
                return ar ? " \u0636\u0631\u0628 " : " times ";
            case '\u00F7':
                return ar ? " \u0642\u0633\u0645\u0629 " : " divided by ";
            case '\u2248':
            case '~':
                return ar ? " \u062A\u0642\u0631\u064A\u0628\u0627 " : " approximately ";
            case '\u2192':
            case '\u21D2':
            case '\u27F6':
                return ar ? " \u064A\u0624\u062F\u064A \u0625\u0644\u0649 " : " leads to ";
            case '\u2191':
                return ar ? " \u0632\u064A\u0627\u062F\u0629 " : " increase ";
            case '\u2193':
                return ar ? " \u0646\u0642\u0635\u0627\u0646 " : " decrease ";
            case '&':
                return ar ? " \u0648 " : " and ";
            case '%':
            case '\u066A':
                return ar ? " \u0628\u0627\u0644\u0645\u0626\u0629 " : " percent ";
            case '\u00B0':
                return ar ? " \u062F\u0631\u062C\u0629 " : " degrees ";
            default:
                return null;
        }
    }

    private static String minusWord(String l) {
        switch (l) {
            case "ar":
                return "\u0633\u0627\u0644\u0628";
            case "fr":
                return "moins";
            case "tr":
                return "eksi";
            default:
                return "minus";
        }
    }

    private static String percentWord(String l) {
        switch (l) {
            case "ar":
                return "\u0628\u0627\u0644\u0645\u0626\u0629";
            case "fr":
                return "pour cent";
            case "tr":
                return "";
            default:
                return "percent";
        }
    }

    private static String degWord(String l) {
        switch (l) {
            case "ar":
                return "\u062F\u0631\u062C\u0629";
            case "fr":
                return "degr\u00E9s";
            case "tr":
                return "derece";
            default:
                return "degrees";
        }
    }

    private static String degreeWord(String l, char scale) {
        boolean f = scale == 'F' || scale == 'f';
        switch (l) {
            case "ar":
                return f ? "\u062F\u0631\u062C\u0629 \u0641\u0647\u0631\u0646\u0647\u0627\u064A\u062A" : "\u062F\u0631\u062C\u0629 \u0645\u0626\u0648\u064A\u0629";
            case "fr":
                return f ? "degr\u00E9s Fahrenheit" : "degr\u00E9s Celsius";
            case "tr":
                return f ? "derece Fahrenheit" : "derece Celsius";
            default:
                return f ? "degrees Fahrenheit" : "degrees Celsius";
        }
    }

    private static String rangeWord(String l) {
        switch (l) {
            case "ar":
                return " \u0625\u0644\u0649 ";
            case "fr":
                return " \u00E0 ";
            case "tr":
                return " ile ";
            default:
                return " to ";
        }
    }

    private static String linkWord(String l) {
        switch (l) {
            case "ar":
                return "\u0631\u0627\u0628\u0637 \u0625\u0644\u0643\u062A\u0631\u0648\u0646\u064A";
            case "fr":
                return "lien";
            case "tr":
                return "ba\u011Flant\u0131";
            default:
                return "web link";
        }
    }

    private static String emailWord(String l) {
        switch (l) {
            case "ar":
                return "\u0628\u0631\u064A\u062F \u0625\u0644\u0643\u062A\u0631\u0648\u0646\u064A";
            case "fr":
                return "adresse e-mail";
            case "tr":
                return "e-posta adresi";
            default:
                return "email address";
        }
    }

    // ------------------------------------------------------------------ الوحدات

    private static final Map<String, String> U_AR = new HashMap<>();
    private static final Map<String, String> U_EN = new HashMap<>();

    private static void unit(String key, String ar, String en) {
        U_AR.put(key, ar);
        U_EN.put(key, en);
    }

    static {
        unit("mg", "\u0645\u0644\u064A\u063A\u0631\u0627\u0645", "milligrams");
        unit("g", "\u063A\u0631\u0627\u0645", "grams");
        unit("kg", "\u0643\u064A\u0644\u0648\u063A\u0631\u0627\u0645", "kilograms");
        unit("mcg", "\u0645\u064A\u0643\u0631\u0648\u063A\u0631\u0627\u0645", "micrograms");
        unit("\u00B5g", "\u0645\u064A\u0643\u0631\u0648\u063A\u0631\u0627\u0645", "micrograms");
        unit("ml", "\u0645\u0644\u064A\u0644\u062A\u0631", "milliliters");
        unit("l", "\u0644\u062A\u0631", "liters");
        unit("dl", "\u062F\u064A\u0633\u064A\u0644\u062A\u0631", "deciliters");
        unit("cm", "\u0633\u0646\u062A\u064A\u0645\u062A\u0631", "centimeters");
        unit("mm", "\u0645\u0644\u064A\u0645\u062A\u0631", "millimeters");
        unit("m", "\u0645\u062A\u0631", "meters");
        unit("km", "\u0643\u064A\u0644\u0648\u0645\u062A\u0631", "kilometers");
        unit("hz", "\u0647\u0631\u062A\u0632", "hertz");
        unit("khz", "\u0643\u064A\u0644\u0648 \u0647\u0631\u062A\u0632", "kilohertz");
        unit("mhz", "\u0645\u064A\u063A\u0627 \u0647\u0631\u062A\u0632", "megahertz");
        unit("ma", "\u0645\u0644\u064A \u0623\u0645\u0628\u064A\u0631", "milliamps");
        unit("a", "\u0623\u0645\u0628\u064A\u0631", "amps");
        unit("v", "\u0641\u0648\u0644\u062A", "volts");
        unit("mv", "\u0645\u0644\u064A \u0641\u0648\u0644\u062A", "millivolts");
        unit("w", "\u0648\u0627\u0637", "watts");
        unit("mw", "\u0645\u0644\u064A \u0648\u0627\u0637", "milliwatts");
        unit("j", "\u062C\u0648\u0644", "joules");
        unit("kj", "\u0643\u064A\u0644\u0648 \u062C\u0648\u0644", "kilojoules");
        unit("min", "\u062F\u0642\u064A\u0642\u0629", "minutes");
        unit("mins", "\u062F\u0642\u064A\u0642\u0629", "minutes");
        unit("sec", "\u062B\u0627\u0646\u064A\u0629", "seconds");
        unit("secs", "\u062B\u0627\u0646\u064A\u0629", "seconds");
        unit("s", "\u062B\u0627\u0646\u064A\u0629", "seconds");
        unit("h", "\u0633\u0627\u0639\u0629", "hours");
        unit("hr", "\u0633\u0627\u0639\u0629", "hours");
        unit("hrs", "\u0633\u0627\u0639\u0629", "hours");
        unit("bpm", "\u0646\u0628\u0636\u0629 \u0641\u064A \u0627\u0644\u062F\u0642\u064A\u0642\u0629", "beats per minute");
        unit("mmhg", "\u0645\u0644\u064A\u0645\u062A\u0631 \u0632\u0626\u0628\u0642\u064A", "millimeters of mercury");
        unit("kcal", "\u0633\u0639\u0631\u0629 \u062D\u0631\u0627\u0631\u064A\u0629", "kilocalories");
        unit("iu", "\u0648\u062D\u062F\u0629 \u062F\u0648\u0644\u064A\u0629", "international units");
        unit("rpm", "\u062F\u0648\u0631\u0629 \u0641\u064A \u0627\u0644\u062F\u0642\u064A\u0642\u0629", "revolutions per minute");
    }

    /**
     * attached = الوحدة ملتصقة برقم (10mg) فنقبل حرفًا واحدًا (s, m, A)؛ وإلا (10 mg) نشترط حرفين فأكثر
     * حتى لا تتحول "Figure 5 A" إلى "5 أمبير".
     */
    private static String unitSpoken(String u, boolean ar, boolean en, boolean attached) {
        if (!ar && !en) return null;
        String x = u.replace('\u00B2', '2').replace('\u00B3', '3').replace('\u03BC', '\u00B5');
        String[] parts = x.split("/", -1);
        if (parts.length > 2) return null;
        Map<String, String> map = ar ? U_AR : U_EN;
        StringBuilder sb = new StringBuilder();
        for (int idx = 0; idx < parts.length; idx++) {
            Matcher m = UNITPART.matcher(parts[idx]);
            if (!m.matches()) return null;
            String base = m.group(1);
            if (!attached && base.length() < 2 && !(parts.length == 2 && parts[1 - idx].length() >= 2)) return null;
            String v = map.get(base);
            if (v == null) v = map.get(base.toLowerCase(Locale.ROOT));
            if (v == null) return null;
            String pw = m.group(2);
            if (pw != null) v += pw.equals("2") ? (ar ? " \u0645\u0631\u0628\u0639" : " squared") : (ar ? " \u0645\u0643\u0639\u0628" : " cubed");
            if (idx > 0) sb.append(ar ? " \u0644\u0643\u0644 " : " per ");
            sb.append(v);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ اختصارات

    private static final Map<String, String> EN_ABBR = new HashMap<>();
    private static final Map<String, String> AR_ABBR = new HashMap<>();

    static {
        EN_ABBR.put("e.g.", "for example,");
        EN_ABBR.put("i.e.", "that is,");
        EN_ABBR.put("vs.", "versus");
        EN_ABBR.put("vs", "versus");
        EN_ABBR.put("etc.", "et cetera");
        EN_ABBR.put("fig.", "figure");
        EN_ABBR.put("figs.", "figures");
        EN_ABBR.put("dr.", "doctor");
        EN_ABBR.put("mr.", "mister");
        EN_ABBR.put("mrs.", "missus");
        EN_ABBR.put("prof.", "professor");
        EN_ABBR.put("approx.", "approximately");
        AR_ABBR.put("\u0625\u0644\u062E", "\u0625\u0644\u0649 \u0622\u062E\u0631\u0647");
        AR_ABBR.put("\u0627\u0644\u062E", "\u0625\u0644\u0649 \u0622\u062E\u0631\u0647");
        AR_ABBR.put("\u0642.\u0645", "\u0642\u0628\u0644 \u0627\u0644\u0645\u064A\u0644\u0627\u062F");
    }

    // ------------------------------------------------------------------ العربية: تشكيل أدوات الإشارة والموصولات وألفاظ العموم

    private static final Map<String, String> D = new HashMap<>();
    private static final Set<String> P = new HashSet<>(); // كلمات تقبل سوابق ب/ل/ك (بهذا، لذلك، بكل)
    private static final Map<Character, String> PREFIX_V = new HashMap<>();

    private static void d(String plain, String shaped, boolean prefixable) {
        D.put(plain, shaped);
        if (prefixable) P.add(plain);
    }

    static {
        PREFIX_V.put('\u0648', "\u0648\u064E");
        PREFIX_V.put('\u0641', "\u0641\u064E");
        PREFIX_V.put('\u0628', "\u0628\u0650");
        PREFIX_V.put('\u0644', "\u0644\u0650");
        PREFIX_V.put('\u0643', "\u0643\u064E");

        // أدوات الإشارة
        d("هذا", "هَذَا", true);
        d("هذه", "هَذِه", true);
        d("ذلك", "ذَلِك", true);
        d("تلك", "تِلْك", true);
        d("ذاك", "ذَاك", true);
        d("هؤلاء", "هَؤُلَاء", true);
        d("أولئك", "أُولَئِك", true);
        d("اولئك", "أُولَئِك", true);
        d("هذان", "هَذَان", true);
        d("هذين", "هَذَيْن", true);
        d("هاتان", "هَاتَان", true);
        d("هاتين", "هَاتَيْن", true);
        d("هنا", "هُنَا", true);
        d("هناك", "هُنَاك", true);
        d("هنالك", "هُنَالِك", true);
        d("هكذا", "هَكَذَا", true);
        d("كذا", "كَذَا", true);
        // الأسماء الموصولة
        d("الذي", "الَّذِي", false);
        d("التي", "الَّتِي", false);
        d("الذين", "الَّذِين", false);
        d("اللذان", "اللَّذَان", false);
        d("اللتان", "اللَّتَان", false);
        d("اللاتي", "اللَّاتِي", false);
        d("اللواتي", "اللَّوَاتِي", false);
        // أدوات الاستفهام
        d("ماذا", "مَاذَا", false);
        d("لماذا", "لِمَاذَا", false);
        d("كيف", "كَيْف", false);
        d("متى", "مَتَى", false);
        d("أين", "أَيْن", false);
        // ألفاظ العموم والكمّ
        d("كل", "كُلّ", true);
        d("بعض", "بَعْض", true);
        d("جميع", "جَمِيع", true);
        d("كافة", "كَافَّة", true);
        d("معظم", "مُعْظَم", true);
        d("أغلب", "أَغْلَب", true);
        d("أكثر", "أَكْثَر", true);
        d("أقل", "أَقَلّ", true);
        // حروف وظروف يكثر الخطأ في ضبطها (لا نضع حركة إعراب على الآخر حتى لا نفرضها خطأً)
        d("إذا", "إِذَا", false);
        d("اذا", "إِذَا", false);
        d("حيث", "حَيْث", false);
        d("حين", "حِين", false);
        d("بينما", "بَيْنَمَا", false);
        d("لكن", "لَكِنْ", false);
        d("لذا", "لِذَا", false);
        d("إلى", "إِلَى", false);
        d("الى", "إِلَى", false);
        d("على", "عَلَى", false);
        d("عن", "عَنْ", false);
        d("حتى", "حَتَّى", false);
        d("بعد", "بَعْد", false);
        d("قبل", "قَبْل", false);
        d("بين", "بَيْن", false);
        d("خلال", "خِلَال", false);
        d("أثناء", "أَثْنَاء", false);
        d("عند", "عِنْد", false);
        d("لدى", "لَدَى", false);
        d("منذ", "مُنْذ", false);
        d("دون", "دُون", false);
        d("ثم", "ثُمَّ", false);
        d("قد", "قَدْ", false);
        d("لقد", "لَقَدْ", false);
        d("سوف", "سَوْف", false);
        d("لم", "لَمْ", false);
        d("لن", "لَنْ", false);
        d("ليس", "لَيْس", false);
        d("ليست", "لَيْسَتْ", false);
        d("إنما", "إِنَّمَا", false);
        d("أيضا", "أَيْضًا", false);
    }

    private static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0621 && c <= 0x064A) return true;
        }
        return false;
    }

    private static boolean isPlainArabicWord(String p) {
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x0621 || c > 0x064A) return false;
        }
        return true;
    }

    private static String diacritize(String p) {
        if (p.length() < 2 || !isPlainArabicWord(p)) return p;
        String r = p.length() <= 14 ? diacritizeCore(p) : p;
        if (assist && r.equals(p)) {
            String lx = ArabicPhonetics.lookup(p); // مصطلحات طبية/علاجية بتشكيل كامل
            if (lx != null) r = lx;
        }
        return hamzaAfterAl(r);
    }

    /** الأعصاب / الإصابة / بالألم: سكون على لام "ال" ليُنطق الهمز بوضوح (الْأعصاب) لا "ال أ" مفصولة. */
    private static String hamzaAfterAl(String r) {
        if (!isPlainArabicWord(r) || r.length() < 4) return r;
        int i;
        if (r.startsWith("\u0627\u0644")) i = 0;
        else if ("\u0648\u0641\u0628\u0643".indexOf(r.charAt(0)) >= 0 && r.startsWith("\u0627\u0644", 1)) i = 1;
        else return r;
        int h = i + 2;
        if (h >= r.length()) return r;
        char c = r.charAt(h);
        if (c == '\u0623' || c == '\u0625' || c == '\u0622') {
            return r.substring(0, h) + "\u0652" + r.substring(h);
        }
        return r;
    }

    private static String diacritizeCore(String p) {
        String direct = D.get(p);
        if (direct != null) return direct;
        String pre = "";
        String rest = p;
        for (int k = 0; k < 2 && rest.length() > 2; k++) {
            char c = rest.charAt(0);
            String v = PREFIX_V.get(c);
            if (v == null) break;
            String base = rest.substring(1);
            String d2 = D.get(base);
            boolean prefixOk = c == '\u0648' || c == '\u0641' || P.contains(base);
            if (d2 != null && prefixOk) return pre + v + d2;
            if (c == '\u0648' || c == '\u0641') { // و / ف قد تسبق سابقة أخرى: ولذلك، فبهذا
                pre = pre + v;
                rest = base;
                continue;
            }
            break;
        }
        return p;
    }

    // ------------------------------------------------------------------ أدوات

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK;
    }

    private static String bareOf(String s) {
        int a = 0, b = s.length();
        while (a < b && !isWordChar(s.charAt(a))) a++;
        while (b > a && !isWordChar(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /** آخر علامة ترقيم في ذيل الكلمة (تُحفظ لتبقى وقفة الجملة). */
    private static String lastPunctIn(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (isWordChar(c)) break;
            if (c == '\u2026') return ".";
            if (PUNCT.indexOf(c) >= 0) return String.valueOf(c);
        }
        return "";
    }

    /** يحذف الرموز غير المنطوقة والأحرف الخفية، ويوحّد الأرقام (هندية/فارسية -> لاتينية) وأشكال الحروف. */
    private static String clean(String s) {
        boolean presentation = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) {
                presentation = true;
                break;
            }
        }
        String x = presentation ? Normalizer.normalize(s, Normalizer.Form.NFKC) : s;
        StringBuilder sb = new StringBuilder(x.length());
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            if ((c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)
                    || c == 0xFEFF || c == 0x00AD || c == 0x0640 || c == 0x061C) continue;
            if (Character.isSurrogate(c)) continue; // إيموجي
            if (c >= 0xE000 && c <= 0xF8FF) continue; // أيقونات الخطوط
            if ("\u2022\u25CF\u25AA\u25E6\u25A0\u25A1\u25C6\u25C7\u2605\u2606\u2713\u2714\u2717\u2718\u27A2\u27A4\u25BA\u25B6\u00B7\u2023\u2043".indexOf(c) >= 0) continue;
            if (c >= 0x0660 && c <= 0x0669) c = (char) ('0' + (c - 0x0660));
            else if (c >= 0x06F0 && c <= 0x06F9) c = (char) ('0' + (c - 0x06F0));
            else if (c == 0x066B) c = '.';
            else if (c == 0x066C) c = ',';
            sb.append(c);
        }
        return ArabicPhonetics.normalize(sb.toString());
    }
}
