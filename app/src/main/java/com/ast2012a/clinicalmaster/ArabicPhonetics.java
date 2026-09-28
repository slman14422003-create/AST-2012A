package com.ast2012a.clinicalmaster;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * فهم التشكيل العربي وتحسين اللفظ الأصيل قبل إرسال النص للمحرك الصوتي:
 *
 *  1) normalize: توحيد الحروف الدخيلة (ک ی ہ)، ألف الوصل (ٱ)، الألف الخنجرية (ٰ) تُحوَّل لألف مدّ،
 *     حذف علامات الوقف القرآنية الصغيرة، تركيب الهمزات (أ إ ؤ ئ آ)، وترتيب العلامات فوق/تحت الحرف
 *     على ترتيب ثابت: الشدّة أولًا ثم الحركة/التنوين (بعض المحركات تسقط الشدّة لو جاءت بعد الفتحة).
 *  2) pausal: النص المشكول عند الوقف (قبل نقطة/فاصلة) يُنطق بالسكون: "العِلْمُ." -> "العِلْمْ"،
 *     "كِتَابًا." -> "كِتَابَا"، "حَقٌّ." -> "حَقّ" - كما يقرأ القارئ العربي الفصيح.
 *  3) lookup: قاموس مصطلحات (طب/علاج طبيعي/دراسة) بتشكيل كامل بلا حركة إعراب، مع دعم السوابق
 *     (و/ف + ب/ك/ل + ال) وإدغام اللام الشمسية بالشدّة: "والتمارين" -> "وَالتَّمَارِين".
 */
final class ArabicPhonetics {

    private ArabicPhonetics() {
    }

    static final char SHADDA = '\u0651';
    static final char SUKUN = '\u0652';
    static final char FATHA = '\u064E';
    static final char DAMMA = '\u064F';
    static final char KASRA = '\u0650';
    static final char FATHATAN = '\u064B';
    static final char DAMMATAN = '\u064C';
    static final char KASRATAN = '\u064D';

    static boolean isMark(char c) {
        return (c >= 0x064B && c <= 0x065F) || c == 0x0670;
    }

    static boolean isArabicLetter(char c) {
        return (c >= 0x0621 && c <= 0x064A) || c == 0x0671 || (c >= 0x066E && c <= 0x06D3);
    }

    static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 1) التوحيد

    static String normalize(String s) {
        if (s == null || s.isEmpty() || !hasArabic(s)) return s;
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\u0671':
                    sb.append('\u0627');
                    continue;
                case '\u06A9':
                    sb.append('\u0643');
                    continue;
                case '\u06CC':
                    sb.append('\u064A');
                    continue;
                case '\u06C1':
                case '\u06BE':
                case '\u06D5':
                case '\u06C0':
                    sb.append('\u0647');
                    continue;
                case '\u06E1':
                    sb.append(SUKUN);
                    continue;
                case '\u0670': { // ألف خنجرية: تُنطق مدًّا. بعد ى/ي تكون المدّ نفسه فلا نكرّره
                    char base = lastBase(sb);
                    if (base != '\u0649' && base != '\u064A' && base != '\u0627') sb.append('\u0627');
                    continue;
                }
                default:
                    break;
            }
            // علامات القراءة القرآنية الصغيرة وأرقام الآيات: لا تُنطق
            if ((c >= 0x06D6 && c <= 0x06DE) || c == 0x06DF || c == 0x06E0 || (c >= 0x06E2 && c <= 0x06E8)
                    || (c >= 0x06EA && c <= 0x06ED) || c == 0x08F0) {
                continue;
            }
            sb.append(c);
        }
        String x = sb.toString();
        try {
            x = Normalizer.normalize(x, Normalizer.Form.NFC); // أ إ ؤ ئ آ
        } catch (Throwable ignored) {
        }
        return orderMarks(x);
    }

    private static char lastBase(StringBuilder sb) {
        for (int i = sb.length() - 1; i >= 0; i--) {
            char c = sb.charAt(i);
            if (!isMark(c)) return c;
        }
        return ' ';
    }

    /** الشدّة أولًا ثم بقية العلامات (بلا تكرار) لكل حرف. */
    static String orderMarks(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (!isMark(c)) {
                sb.append(c);
                i++;
                continue;
            }
            int j = i;
            boolean shadda = false;
            char vowel = 0;
            StringBuilder other = new StringBuilder();
            while (j < n && isMark(s.charAt(j))) {
                char m = s.charAt(j);
                if (m == SHADDA) shadda = true;
                else if (m >= 0x064B && m <= 0x0652) vowel = m; // آخر حركة تغلب
                else if (other.indexOf(String.valueOf(m)) < 0) other.append(m);
                j++;
            }
            if (shadda) sb.append(SHADDA);
            if (vowel != 0) sb.append(vowel);
            sb.append(other);
            i = j;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 2) الوقف

    /** كلمة مشكولة قبل علامة وقف: تُحذف حركة الإعراب الأخيرة والتنوين كما في القراءة الفصيحة. */
    static String pausal(String w) {
        if (w == null || w.length() < 2 || !hasArabic(w)) return w;
        int idx = w.length() - 1;
        while (idx >= 0 && isMark(w.charAt(idx))) idx--;
        if (idx < 0) return w;
        char base = w.charAt(idx);
        String marks = w.substring(idx + 1);
        String head = w.substring(0, idx);

        if (marks.isEmpty()) {
            // "بًا" : تنوين الفتح على الحرف قبل الألف -> فتحة (مدّ)
            if ((base == '\u0627') && head.length() >= 2) {
                int p = head.length() - 1;
                int e = p;
                while (p >= 0 && isMark(head.charAt(p))) p--;
                if (p >= 0 && e > p && head.indexOf(FATHATAN, p + 1) >= 0) {
                    String pm = head.substring(p + 1).replace(String.valueOf(FATHATAN), String.valueOf(FATHA));
                    return head.substring(0, p + 1) + pm + base;
                }
            }
            return w;
        }
        boolean shadda = marks.indexOf(SHADDA) >= 0;
        boolean longBase = base == '\u0627' || base == '\u0649' || base == '\u0648' || base == '\u064A';
        boolean taa = base == '\u0629';
        StringBuilder nm = new StringBuilder();
        if (shadda) nm.append(SHADDA);
        boolean changed = false;
        for (int i = 0; i < marks.length(); i++) {
            char m = marks.charAt(i);
            if (m == SHADDA) continue;
            if (m == DAMMATAN || m == KASRATAN) {
                changed = true;
                continue;
            }
            if (m == FATHATAN) {
                if (base == '\u0627' || base == '\u0649' || taa) {
                    changed = true;
                    continue;
                }
                nm.append(m);
                continue;
            }
            if (m == FATHA || m == DAMMA || m == KASRA) {
                if (longBase) {
                    nm.append(m);
                    continue;
                }
                changed = true;
                continue; // نحذف الحركة، ونضع سكونًا أدناه لو لم تكن شدّة
            }
            nm.append(m);
        }
        if (!changed) return w;
        boolean hasVowelLeft = false;
        for (int i = 0; i < nm.length(); i++) {
            char m = nm.charAt(i);
            if (m != SHADDA) hasVowelLeft = true;
        }
        if (!shadda && !hasVowelLeft && !taa && !longBase) nm.append(SUKUN);
        return head + base + nm;
    }

    // ------------------------------------------------------------------ 2b) واو الجماعة + الألف الفارقة

    /** أفعال ناقصة ماضية واوها من أصل الفعل (دَعَوْا): تُنطق بفتحة ثم واو ساكنة لا بمدّ. */
    private static final Map<String, String> DEFECTIVE_PAST = new HashMap<>();
    /** كلمات تنتهي بـ "وا" وليست واو جماعة (الألف فيها تنوين أو جزء من الكلمة) فلا نمسّها. */
    private static final java.util.Set<String> KEEP_WAW_ALIF = new java.util.HashSet<>(Arrays.asList(
            "\u0639\u0641\u0648\u0627", "\u0644\u0647\u0648\u0627", "\u0639\u0644\u0648\u0627", "\u063A\u0644\u0648\u0627",
            "\u0633\u0645\u0648\u0627", "\u0645\u0627\u0648\u0627"));

    static {
        DEFECTIVE_PAST.put("\u062F\u0639\u0648\u0627", "\u062F\u064E\u0639\u064E\u0648\u0652");   // دعوا
        DEFECTIVE_PAST.put("\u0633\u0639\u0648\u0627", "\u0633\u064E\u0639\u064E\u0648\u0652");   // سعوا
        DEFECTIVE_PAST.put("\u063A\u0632\u0648\u0627", "\u063A\u064E\u0632\u064E\u0648\u0652");   // غزوا
        DEFECTIVE_PAST.put("\u0631\u062C\u0648\u0627", "\u0631\u064E\u062C\u064E\u0648\u0652");   // رجوا
        DEFECTIVE_PAST.put("\u0646\u062C\u0648\u0627", "\u0646\u064E\u062C\u064E\u0648\u0652");   // نجوا
        DEFECTIVE_PAST.put("\u0628\u062F\u0648\u0627", "\u0628\u064E\u062F\u064E\u0648\u0652");   // بدوا
    }

    private static String stripMarks(String w) {
        StringBuilder sb = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!isMark(c)) sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isBaseLetter(char c) {
        return c >= 0x0621 && c <= 0x064A;
    }

    /**
     * واو الجماعة تُكتب بألف بعدها لا تُنطق (تَجَمَّعُوا = tajammaʿū). المحركات الصوتية تقرأ "وا" كـ wā
     * فيخرج "تجمّعْوَا". نحذف الألف الفارقة ونضمّ ما قبل الواو (لو بلا حركة) فتُنطق مدًّا صافيًا "ــوا" -> "ــُو".
     * لا تُمَسّ الكلمات التي تنتهي بتنوين على الواو (عَفْوًا) ولا القصيرة (سوا/هوا).
     */
    static String fixJamaa(String w) {
        if (w == null || w.length() < 4) return w;
        final int n = w.length();
        if (w.charAt(n - 1) != '\u0627') return w;
        int j = n - 2;
        while (j >= 0 && isMark(w.charAt(j))) j--;
        if (j < 1 || w.charAt(j) != '\u0648') return w;
        String wawMarks = w.substring(j + 1, n - 1);
        boolean wawSukun = false;
        for (int i = 0; i < wawMarks.length(); i++) {
            char m = wawMarks.charAt(i);
            if (m == SUKUN) wawSukun = true;
            else if (m != DAMMA) return w; // فتحة/تنوين/شدّة/كسرة: ليست واو جماعة
        }
        int k = j - 1;
        while (k >= 0 && isMark(w.charAt(k))) k--;
        if (k < 0) return w;
        char prev = w.charAt(k);
        if (!isBaseLetter(prev) || prev == '\u0627' || prev == '\u0648' || prev == '\u0649') return w;
        String plain = stripMarks(w);
        if (plain.length() < 4) return w;
        if (KEEP_WAW_ALIF.contains(plain)) return w;
        if (w.equals(plain)) {
            String def = DEFECTIVE_PAST.get(plain);
            if (def != null) return def;
        }
        String prevMarks = w.substring(k + 1, j);
        boolean prevHasVowel = false;
        for (int i = 0; i < prevMarks.length(); i++) {
            char m = prevMarks.charAt(i);
            if (m >= 0x064B && m <= 0x0652 && m != SHADDA) prevHasVowel = true;
        }
        StringBuilder out = new StringBuilder(n);
        out.append(w, 0, k + 1).append(prevMarks);
        if (!prevHasVowel && !wawSukun) out.append(DAMMA); // ...عُو : مدّ الضمّ
        out.append('\u0648');
        if (wawSukun) out.append(SUKUN);
        return orderMarks(out.toString());
    }

    // ------------------------------------------------------------------ 3) القاموس

    private static final Map<String, String> LEX = new HashMap<>();
    private static final String SUN = "\u062A\u062B\u062F\u0630\u0631\u0632\u0633\u0634\u0635\u0636\u0637\u0638\u0644\u0646";

    private static void lx(String... pairs) {
        for (String p : pairs) {
            int k = p.indexOf('=');
            LEX.put(p.substring(0, k), p.substring(k + 1));
        }
    }

    static {
        // تشريح
        lx("عضلة=عَضَلَة", "عضلات=عَضَلَات", "عضلي=عَضَلِي", "عضلية=عَضَلِيَّة",
                "مفصل=مَفْصِل", "مفاصل=مَفَاصِل", "عظم=عَظْم", "عظام=عِظَام", "عظمي=عَظْمِي",
                "وتر=وَتَر", "أوتار=أَوْتَار", "رباط=رِبَاط", "أربطة=أَرْبِطَة", "رباطي=رِبَاطِي",
                "غضروف=غُضْرُوف", "غضاريف=غَضَارِيف", "عصب=عَصَب", "أعصاب=أَعْصَاب", "عصبي=عَصَبِي",
                "عصبية=عَصَبِيَّة", "فقرة=فَقْرَة", "فقرات=فَقَرَات", "فقري=فَقَرِي", "فقارية=فَقَارِيَّة",
                "عمود=عَمُود", "ركبة=رُكْبَة", "كتف=كَتِف", "أكتاف=أَكْتَاف", "كاحل=كَاحِل", "مرفق=مِرْفَق",
                "رسغ=رُسْغ", "ورك=وَرِك", "فخذ=فَخِذ", "ساق=سَاق", "قدم=قَدَم", "يد=يَد", "ذراع=ذِرَاع",
                "عنق=عُنُق", "ظهر=ظَهْر", "صدر=صَدْر", "بطن=بَطْن", "حوض=حَوْض", "جمجمة=جُمْجُمَة",
                "قلب=قَلْب", "رئة=رِئَة", "دم=دَم", "شريان=شِرْيَان", "شرايين=شَرَايِين", "وريد=وَرِيد",
                "أوردة=أَوْرِدَة", "جلد=جِلْد", "نسيج=نَسِيج", "أنسجة=أَنْسِجَة", "دماغ=دِمَاغ",
                "نخاع=نُخَاع", "شوكي=شَوْكِي", "حبل=حَبْل");
        // حالات وأعراض وعلاج
        lx("ألم=أَلَم", "آلام=آلَام", "التهاب=اِلْتِهَاب", "التهابات=اِلْتِهَابَات", "التواء=اِلْتِوَاء",
                "التصاق=اِلْتِصَاق", "التصاقات=اِلْتِصَاقَات", "تمزق=تَمَزُّق", "تمزقات=تَمَزُّقَات",
                "تورم=تَوَرُّم", "تشنج=تَشَنُّج", "تشنجات=تَشَنُّجَات", "تيبس=تَيَبُّس", "ضمور=ضُمُور",
                "شلل=شَلَل", "كسر=كَسْر", "كسور=كُسُور", "خلع=خَلْع", "إصابة=إِصَابَة", "إصابات=إِصَابَات",
                "علاج=عِلَاج", "علاجي=عِلَاجِي", "علاجية=عِلَاجِيَّة", "تأهيل=تَأْهِيل", "تشخيص=تَشْخِيص",
                "فحص=فَحْص", "أعراض=أَعْرَاض", "مرض=مَرَض", "مريض=مَرِيض", "مرضى=مَرْضَى", "جراحة=جِرَاحَة",
                "دواء=دَوَاء", "أدوية=أَدْوِيَة", "جرعة=جُرْعَة", "تدليك=تَدْلِيك", "شد=شَدّ");
        // حركة وتمرين
        lx("حركة=حَرَكَة", "حركات=حَرَكَات", "مدى=مَدَى", "قوة=قُوَّة", "توازن=تَوَازُن", "مرونة=مُرُونَة",
                "تمرين=تَمْرِين", "تمارين=تَمَارِين", "تقوية=تَقْوِيَة", "إطالة=إِطَالَة", "تمدد=تَمَدُّد",
                "انقباض=اِنْقِبَاض", "انبساط=اِنْبِسَاط", "ثني=ثَنْي", "بسط=بَسْط", "تقريب=تَقْرِيب",
                "تبعيد=تَبْعِيد", "دوران=دَوَرَان", "مشي=مَشْي", "وقوف=وُقُوف", "جلوس=جُلُوس",
                "استلقاء=اِسْتِلْقَاء", "وضعية=وَضْعِيَّة", "وضعيات=وَضْعِيَّات", "تحمل=تَحَمُّل",
                "ثبات=ثَبَات", "تناسق=تَنَاسُق");
        // أجهزة وأدوات ومصطلحات عامة
        lx("جهاز=جِهَاز", "أجهزة=أَجْهِزَة", "تيار=تَيَّار", "تردد=تَرَدُّد", "شدة=شِدَّة", "مدة=مُدَّة",
                "جلسة=جَلْسَة", "جلسات=جَلَسَات", "مرحلة=مَرْحَلَة", "مراحل=مَرَاحِل", "درجة=دَرَجَة",
                "حرارة=حَرَارَة", "برودة=بُرُودَة", "ضغط=ضَغْط", "علم=عِلْم", "دراسة=دِرَاسَة",
                "دراسات=دِرَاسَات", "بحث=بَحْث", "نتيجة=نَتِيجَة", "نتائج=نَتَائِج", "طريقة=طَرِيقَة",
                "طرق=طُرُق", "هدف=هَدَف", "أهداف=أَهْدَاف", "مثال=مِثَال", "أمثلة=أَمْثِلَة", "خطوة=خُطْوَة",
                "خطوات=خُطُوَات", "فائدة=فَائِدَة", "فوائد=فَوَائِد", "سبب=سَبَب", "أسباب=أَسْبَاب",
                "عوامل=عَوَامِل", "نوع=نَوْع", "أنواع=أَنْوَاع", "وظيفة=وَظِيفَة", "وظائف=وَظَائِف",
                "تعريف=تَعْرِيف", "مقدمة=مُقَدِّمَة", "خاتمة=خَاتِمَة", "ملخص=مُلَخَّص", "فصل=فَصْل",
                "صفحة=صَفْحَة", "جدول=جَدْوَل", "شكل=شَكْل", "صورة=صُورَة", "مصدر=مَصْدَر", "مراجع=مَرَاجِع",
                "مرجع=مَرْجِع");
    }


    /** كلمات تُطابَق بحروفها فقط (بلا سوابق) حتى لا تُخطَّأ: ظروف منصوبة بالتنوين وكلمات كثيرة الاستعمال. */
    private static final Map<String, String> EXACT = new HashMap<>();

    private static void ex(String... pairs) {
        for (String p : pairs) {
            int k = p.indexOf('=');
            EXACT.put(p.substring(0, k), p.substring(k + 1));
        }
    }

    static {
        // ظروف وأحوال منصوبة بالتنوين (تُنطق "ـًا" لا "ـا")
        ex("جدا=جِدًّا", "دائما=دَائِمًا", "غالبا=غَالِبًا", "أحيانا=أَحْيَانًا", "احيانا=أَحْيَانًا",
                "تقريبا=تَقْرِيبًا", "مثلا=مَثَلًا", "خصوصا=خُصُوصًا", "عموما=عُمُومًا", "أولا=أَوَّلًا",
                "اولا=أَوَّلًا", "ثانيا=ثَانِيًا", "ثالثا=ثَالِثًا", "رابعا=رَابِعًا", "خامسا=خَامِسًا",
                "أخيرا=أَخِيرًا", "اخيرا=أَخِيرًا", "حاليا=حَالِيًّا", "سابقا=سَابِقًا", "لاحقا=لَاحِقًا",
                "فورا=فَوْرًا", "تماما=تَمَامًا", "كثيرا=كَثِيرًا", "قليلا=قَلِيلًا", "نادرا=نَادِرًا",
                "أساسا=أَسَاسًا", "اساسا=أَسَاسًا", "فعلا=فِعْلًا", "طبعا=طَبْعًا", "شكرا=شُكْرًا",
                "عفوا=عَفْوًا", "أبدا=أَبَدًا", "ابدا=أَبَدًا", "معا=مَعًا", "حقا=حَقًّا", "ايضا=أَيْضًا",
                "سريعا=سَرِيعًا", "بطيئا=بَطِيئًا", "جيدا=جَيِّدًا", "كاملا=كَامِلًا", "مؤقتا=مُؤَقَّتًا",
                "إطلاقا=إِطْلَاقًا", "اطلاقا=إِطْلَاقًا", "نهائيا=نِهَائِيًّا", "تلقائيا=تِلْقَائِيًّا",
                "مبدئيا=مَبْدَئِيًّا", "نسبيا=نِسْبِيًّا", "تدريجيا=تَدْرِيجِيًّا", "جزئيا=جُزْئِيًّا",
                "كليا=كُلِّيًّا", "عمليا=عَمَلِيًّا", "نظريا=نَظَرِيًّا", "سريريا=سَرِيرِيًّا", "طبيا=طِبِّيًّا",
                "علاجيا=عِلَاجِيًّا", "جراحيا=جِرَاحِيًّا", "وظيفيا=وَظِيفِيًّا", "يوميا=يَوْمِيًّا",
                "أسبوعيا=أُسْبُوعِيًّا", "اسبوعيا=أُسْبُوعِيًّا", "شهريا=شَهْرِيًّا", "سنويا=سَنَوِيًّا",
                "مرحبا=مَرْحَبًا", "أهلا=أَهْلًا", "سهلا=سَهْلًا", "صباحا=صَبَاحًا", "فقط=فَقَطْ",
                "ربما=رُبَّمَا", "مائة=مِئَة", "مئة=مِئَة", "مئات=مِئَات", "ألف=أَلْف", "آلاف=آلَاف",
                "مليون=مِلْيُون", "ملايين=مَلَايِين");
        // أفعال وأدوات كثيرة الورود في النصوص العلمية (بلا حركة إعراب على الآخر)
        ex("يمكن=يُمْكِن", "يجب=يَجِب", "ينبغي=يَنْبَغِي", "يؤدي=يُؤَدِّي", "تؤدي=تُؤَدِّي", "يساعد=يُسَاعِد",
                "تساعد=تُسَاعِد", "يسبب=يُسَبِّب", "تسبب=تُسَبِّب", "يعتمد=يَعْتَمِد", "يختلف=يَخْتَلِف",
                "تختلف=تَخْتَلِف", "يتراوح=يَتَرَاوَح", "تتراوح=تَتَرَاوَح", "يعمل=يَعْمَل", "تعمل=تَعْمَل",
                "يحدث=يَحْدُث", "تحدث=تَحْدُث", "يظهر=يَظْهَر", "تظهر=تَظْهَر", "يزيد=يَزِيد", "تزيد=تَزِيد",
                "يقلل=يُقَلِّل", "تقلل=تُقَلِّل", "يؤثر=يُؤَثِّر", "تؤثر=تُؤَثِّر", "يتم=يَتِمّ", "تتم=تَتِمّ",
                "يكون=يَكُون", "تكون=تَكُون", "كان=كَانَ", "كانت=كَانَتْ", "كانوا=كَانُو", "يوجد=يُوجَد",
                "توجد=تُوجَد", "نحتاج=نَحْتَاج", "يحتاج=يَحْتَاج", "تحتاج=تَحْتَاج", "لاحظ=لَاحِظ",
                "تذكر=تَذَكَّر", "ملاحظة=مُلَاحَظَة", "تنبيه=تَنْبِيه",
                "تحذير=تَحْذِير", "احتياطات=اِحْتِيَاطَات", "موانع=مَوَانِع", "استطباب=اِسْتِطْبَاب",
                "استطبابات=اِسْتِطْبَابَات", "مضاد=مُضَاد", "مضادات=مُضَادَّات", "آثار=آثَار",
                "جانبية=جَانِبِيَّة", "تأثير=تَأْثِير", "تأثيرات=تَأْثِيرَات", "فعالية=فَعَّالِيَّة",
                "فعال=فَعَّال", "آمن=آمِن", "آمنة=آمِنَة", "خطر=خَطَر", "مخاطر=مَخَاطِر", "خطير=خَطِير");
    }

    private static final String[] CONJ = {"", "\u0648", "\u0641"};
    private static final String[] PREP = {"", "\u0628", "\u0643", "\u0644"};

    /** تشكيل كلمة عربية مجرّدة من القاموس (مع سوابقها)، أو null لو غير موجودة. */
    static String lookup(String plain) {
        if (plain == null || plain.length() < 2) return null;
        String ex = EXACT.get(plain);
        if (ex != null) return ex;
        String v = lookupCore(plain);
        if (v != null) return v;
        // كلمة + ضمير متصل (عضلاتها، أعصابهم): نشكّل الجذع من القاموس ونلصق الضمير
        for (int i = 0; i < SUF_PLAIN.length; i++) {
            String suf = SUF_PLAIN[i];
            if (plain.length() - suf.length() < 3 || !plain.endsWith(suf)) continue;
            String stem = lookupCore(plain.substring(0, plain.length() - suf.length()));
            if (stem != null) return orderMarks(stem + SUF_SHAPED[i]);
        }
        return null;
    }

    private static final String[] SUF_PLAIN = {"\u0647\u0645\u0627", "\u0647\u0645", "\u0647\u0646", "\u0647\u0627", "\u0643\u0645"};
    private static final String[] SUF_SHAPED = {"\u0647\u064F\u0645\u064E\u0627", "\u0647\u064F\u0645", "\u0647\u064F\u0646\u0651\u064E",
            "\u0647\u064E\u0627", "\u0643\u064F\u0645"};

    private static String lookupCore(String plain) {
        if (plain == null || plain.length() < 2) return null;
        for (String cj : CONJ) {
            if (!cj.isEmpty() && !plain.startsWith(cj)) continue;
            String r1 = plain.substring(cj.length());
            for (String pp : PREP) {
                if (!pp.isEmpty() && !r1.startsWith(pp)) continue;
                String r2 = r1.substring(pp.length());
                if (r2.isEmpty()) continue;
                boolean article = false;
                String stem = r2;
                if (pp.equals("\u0644") && r2.startsWith("\u0644") && LEX.containsKey(r2.substring(1))) {
                    article = true;
                    stem = r2.substring(1);
                } else if (r2.startsWith("\u0627\u0644") && r2.length() > 3 && LEX.containsKey(r2.substring(2))) {
                    article = true;
                    stem = r2.substring(2);
                }
                String v = LEX.get(stem);
                if (v == null) continue;
                if (cj.isEmpty() && pp.isEmpty() && !article) return v;
                if (article && v.charAt(0) == '\u0627') continue; // ألف وصل بعد ال: نتركها للمحرك
                StringBuilder out = new StringBuilder();
                if (!cj.isEmpty()) out.append(cj).append(cj.equals("\u0648") ? FATHA : FATHA);
                if (!pp.isEmpty()) {
                    out.append(pp).append(pp.equals("\u0628") || pp.equals("\u0644") ? KASRA : FATHA);
                }
                if (article) {
                    boolean sun = SUN.indexOf(v.charAt(0)) >= 0;
                    if (pp.equals("\u0644")) {
                        out.append('\u0644'); // لل
                    } else {
                        out.append("\u0627\u0644");
                    }
                    if (sun) {
                        out.append(v.charAt(0)).append(SHADDA).append(v, 1, v.length());
                    } else {
                        out.append(SUKUN).append(v);
                    }
                } else {
                    out.append(v);
                }
                return orderMarks(out.toString());
            }
        }
        return null;
    }

    /** لاصقة "ال" منفصلة قبل كلمة لاتينية: بالـ MRI -> بِالْ ... تُنطق "al" لا "الف لام". */
    static String clitic(String plainArabic) {
        String p = plainArabic;
        String pre = "";
        if (p.startsWith("\u0648") || p.startsWith("\u0641")) {
            pre += p.charAt(0) + "" + FATHA;
            p = p.substring(1);
        }
        if (p.equals("\u0644\u0644")) return pre + "\u0644" + KASRA + "\u0644" + SUKUN;
        if (p.startsWith("\u0628") || p.startsWith("\u0643") || p.startsWith("\u0644")) {
            char c = p.charAt(0);
            pre += c + "" + (c == '\u0643' ? FATHA : KASRA);
            p = p.substring(1);
        }
        if (p.equals("\u0627\u0644")) return pre + "\u0627\u0644" + SUKUN;
        return null;
    }

    static List<String> words(String s) {
        return new ArrayList<>(Arrays.asList(s.split(" ")));
    }
}
