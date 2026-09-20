package com.ast2012a.clinicalmaster;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * محلّل نصوص البروتوكول (Java خالص بدون أي اعتماد على Android).
 *
 * قاعدة البيانات تخزّن كل شيء كنصوص حرة (النمط، التردد، مواضع الأقطاب...).
 * هذا المحلّل يحوّلها إلى بنية واضحة لتعرضها الشاشة كعناصر منفصلة
 * (شارات أنماط، أسطر ترددات، بطاقات قنوات بأقطاب موجبة/سالبة) بدل جدار
 * نص واحد. أي نص لا يمكن فهمه بثقة يُعاد كما هو (لا يضيع شيء أبدًا).
 */
public final class ProtocolParser {

    private ProtocolParser() {}

    // =====================================================================
    // العنوان
    // =====================================================================

    public static final class TitleParts {
        public final String main;
        public final String english;
        TitleParts(String main, String english) {
            this.main = main;
            this.english = english;
        }
    }

    private static final Pattern TRAILING_PAREN =
            Pattern.compile("^(.*?)\\s*\\(([^()]*[A-Za-z][^()]*)\\)\\s*$", Pattern.DOTALL);
    private static final Pattern ARABIC = Pattern.compile("[\\u0600-\\u06FF]");

    /** يفصل "العنوان العربي (English Term)" إلى جزأين عندما يكون الجزء الإنجليزي نقيًا. */
    public static TitleParts splitTitle(String title) {
        if (title == null) return new TitleParts("", "");
        String t = title.trim();
        Matcher m = TRAILING_PAREN.matcher(t);
        if (m.find()) {
            String main = m.group(1).trim();
            String eng = m.group(2).trim();
            boolean pureLatin = !ARABIC.matcher(eng).find();
            boolean deviceType = eng.equals("TENS") || eng.equals("EMS");
            if (pureLatin && !deviceType && !main.isEmpty()) {
                return new TitleParts(main, eng);
            }
        }
        return new TitleParts(t, "");
    }

    private static final Pattern MODE_TITLE =
            Pattern.compile("النمط\\s*\\d+\\s*:\\s*([A-Za-z][A-Za-z \\-/&']*?)\\s*\\((TENS|EMS)\\)");
    private static final Pattern LATIN_WORDS = Pattern.compile("[A-Za-z][A-Za-z'\\-]+");

    /** مصطلح البحث في Physiopedia/PubMed. فارغ لو مفيش أي كلمة إنجليزية (فالبحث بالعربي بلا فائدة). */
    public static String searchTerm(String title) {
        if (title == null) return "";
        TitleParts tp = splitTitle(title);
        if (!tp.english.isEmpty()) return tp.english;
        Matcher m = MODE_TITLE.matcher(title);
        if (m.find()) return m.group(1).trim() + " " + m.group(2);
        StringBuilder sb = new StringBuilder();
        Matcher w = LATIN_WORDS.matcher(title);
        while (w.find()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.group());
        }
        return sb.toString().trim();
    }

    // =====================================================================
    // النمط
    // =====================================================================

    public static boolean isElectrodeProtocol(String mode) {
        if (mode == null) return false;
        return mode.contains("TENS") || mode.contains("EMS");
    }

    public static boolean hasTens(String mode) { return mode != null && mode.contains("TENS"); }

    public static boolean hasEms(String mode) { return mode != null && mode.contains("EMS"); }

    public static final class ModeChip {
        /** "TENS" أو "EMS" أو "TENS/EMS" أو "" */
        public final String type;
        public final int number;
        /** الاسم الإنجليزي للنمط (مثل Tapping) أو "" */
        public final String name;
        /** غرض/ملاحظة النمط (مثل "للتسكين المبكر" أو "بشدة منخفضة") أو "" */
        public final String purpose;
        ModeChip(String type, int number, String name, String purpose) {
            this.type = type;
            this.number = number;
            this.name = name;
            this.purpose = purpose;
        }
    }

    public static final class Modes {
        public final List<ModeChip> chips = new ArrayList<>();
        public String raw = "";
    }

    /** جدول أنماط الجهاز (رقم النمط -> النوع، الاسم) كما في موسوعة الأنماط داخل قاعدة البيانات. */
    private static final String[][] DEVICE_MODES = {
            {"1", "TENS", "Acupuncture Pushing"}, {"2", "TENS", "Acupuncture"},
            {"3", "TENS", "Acupuncture Kneading"}, {"4", "TENS", "Acupuncture Tapping"},
            {"5", "EMS", "Scraping"}, {"6", "TENS", "Squeezing"}, {"7", "EMS", "Massage"},
            {"8", "EMS", "Pushing Massage"}, {"9", "EMS", "Pushing Squeezing"},
            {"10", "TENS", "Acupuncture Squeezing"}, {"11", "TENS", "Acupuncture Hammering"},
            {"12", "TENS", "Kneading"}, {"13", "TENS", "Thumping"}, {"14", "EMS", "Scraping Pressing"},
            {"15", "TENS", "Cupping"}, {"16", "EMS", "Body Shaping"}, {"17", "TENS", "Hammering"},
            {"18", "TENS", "Massage Tapping"}, {"19", "EMS", "Pushing"},
            {"20", "TENS", "Rolling Pounding"}, {"21", "TENS", "Squeezing II"}, {"22", "EMS", "Stroke"},
            {"23", "TENS", "Acupuncture Therapy"}, {"24", "TENS", "Shiatsu"},
            {"25", "TENS", "Rolling Kneading"}
    };

    private static String[] deviceMode(int number) {
        for (String[] row : DEVICE_MODES) {
            if (row[0].equals(String.valueOf(number))) return row;
        }
        return null;
    }

    private static final Pattern MODE_NUM =
            Pattern.compile("(?:\u0627\u0644\u0646\u0645\u0637|\u0646\u0645\u0637)\\s*(\\d+)(?:\\s*\\(([^)]*)\\))?");
    private static final Pattern TYPE_TOKEN = Pattern.compile("TENS|EMS");
    private static final Pattern EDGE_JUNK = Pattern.compile(
            "^(?:[\\s\\-\u2013\u2014\u060C,+/:()]+|(?:\u0623\u0648|\u0648|\u062B\u0645)\\s+)+"
                    + "|(?:[\\s\\-\u2013\u2014\u060C,+/:()]+|\\s+(?:\u0623\u0648|\u0648|\u062B\u0645|\u0644\u0644|\u0644))+$");
    private static final Pattern LONE_CONNECTOR = Pattern.compile("^(?:\u0623\u0648|\u0648|\u062B\u0645|\u0644\u0644|\u0644)$");

    private static String cleanPurpose(String s) {
        if (s == null) return "";
        String t = TYPE_TOKEN.matcher(s).replaceAll(" ");
        t = t.replaceAll("[()]", " ").replaceAll("\\s+", " ").trim();
        String prev;
        do {
            prev = t;
            t = EDGE_JUNK.matcher(t).replaceAll("").trim();
        } while (!t.equals(prev));
        if (LONE_CONNECTOR.matcher(t).matches()) return "";
        return t;
    }

    public static Modes parseModes(String mode) {
        Modes result = new Modes();
        if (mode == null) return result;
        result.raw = mode.trim();

        List<int[]> spans = new ArrayList<>();
        List<Integer> nums = new ArrayList<>();
        List<String> parens = new ArrayList<>();
        Matcher m = MODE_NUM.matcher(mode);
        while (m.find()) {
            int num;
            try {
                num = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            nums.add(num);
            spans.add(new int[]{m.start(), m.end()});
            parens.add(m.group(2) == null ? "" : m.group(2).trim());
        }
        int n = spans.size();
        if (n == 0) return result;

        String[] pre = new String[n];
        String[] post = new String[n];
        String[] types = new String[n];
        for (int i = 0; i < n; i++) {
            pre[i] = "";
            post[i] = "";
        }
        String prevType = "";
        for (int i = 0; i < n; i++) {
            int gapStart = i == 0 ? 0 : spans.get(i - 1)[1];
            String gap = mode.substring(gapStart, spans.get(i)[0]);

            String explicit = "";
            boolean hasTens = false, hasEms = false;
            int firstTok = -1, lastTokEnd = -1;
            Matcher tm = TYPE_TOKEN.matcher(gap);
            while (tm.find()) {
                if (firstTok < 0) firstTok = tm.start();
                lastTokEnd = tm.end();
                if (tm.group().equals("TENS")) hasTens = true; else hasEms = true;
            }
            if (hasTens && hasEms) explicit = "TENS/EMS";
            else if (hasTens) explicit = "TENS";
            else if (hasEms) explicit = "EMS";

            if (firstTok >= 0) {
                if (i > 0) post[i - 1] = gap.substring(0, firstTok);
                pre[i] = gap.substring(lastTokEnd);
            } else {
                if (i > 0) post[i - 1] = gap;
                pre[i] = "";
            }

            String[] dev = deviceMode(nums.get(i));
            String tableType = dev == null ? "" : dev[1];
            String type;
            if (!explicit.isEmpty()) {
                type = explicit;
            } else if (i == 0) {
                type = tableType;
            } else if (gap.contains("\u0623\u0648") || gap.trim().isEmpty()) {
                type = prevType.isEmpty() ? tableType : prevType;
            } else {
                type = tableType.isEmpty() ? prevType : tableType;
            }
            types[i] = type;
            prevType = type;
        }
        post[n - 1] = mode.substring(spans.get(n - 1)[1]);

        for (int i = 0; i < n; i++) {
            String paren = parens.get(i);
            String name = "";
            String parenNote = "";
            if (!paren.isEmpty()) {
                if (ARABIC.matcher(paren).find()) parenNote = paren; else name = paren;
            }
            if (name.isEmpty()) {
                String[] dev = deviceMode(nums.get(i));
                if (dev != null) name = dev[2];
            }
            StringBuilder purpose = new StringBuilder();
            String a = cleanPurpose(pre[i]);
            String b = cleanPurpose(parenNote);
            String c = cleanPurpose(post[i]);
            for (String part : new String[]{a, b, c}) {
                if (part.isEmpty()) continue;
                if (purpose.length() > 0) purpose.append("\u060C ");
                purpose.append(part);
            }
            result.chips.add(new ModeChip(types[i], nums.get(i), name, purpose.toString()));
        }
        return result;
    }

    // =====================================================================
    // التردد
    // =====================================================================

    public static final class FreqLine {
        /** "TENS" / "EMS" أو "" */
        public final String label;
        /** القيمة الرقمية بصيغة مقروءة مثل "2–10 Hz" أو "" لو مفيش قيمة */
        public final String value;
        /** الوصف/الغرض بجانب القيمة */
        public final String note;
        FreqLine(String label, String value, String note) {
            this.label = label;
            this.value = value;
            this.note = note;
        }
    }

    private static final Pattern HZ = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)(?:\\s*Hz)?(?:\\s*[-\\u2013]\\s*(\\d+(?:\\.\\d+)?))?\\s*(k?Hz)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FREQ_SPLIT = Pattern.compile(
            "\\s+\u0623\u0648\\s+|\\s*[,\\u060C]\\s+|\\s+\\+\\s+|\\s*\\|\\s*|\\s*;\\s*|\\s*/\\s*(?=TENS|EMS)");
    private static final Pattern FREQ_SECOND_SPLIT = Pattern.compile("\\s+(?:\u062B\u0645|\u0625\u0644\u0649)\\s+");
    private static final Pattern FREQ_LABEL = Pattern.compile("^(TENS|EMS)\\s*[:\\uFF1A]\\s*");

    public static List<FreqLine> parseFrequency(String freq) {
        List<FreqLine> out = new ArrayList<>();
        if (freq == null || freq.trim().isEmpty()) return out;
        for (String part : FREQ_SPLIT.split(freq.trim())) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            // تقسيم ثانوي عند "ثم"/"إلى" لو الطرفان فيهما Hz
            boolean split = false;
            Matcher sm = FREQ_SECOND_SPLIT.matcher(p);
            while (sm.find()) {
                String left = p.substring(0, sm.start());
                String right = p.substring(sm.end());
                if (HZ.matcher(left).find() && HZ.matcher(right).find()) {
                    out.add(parseFreqPart(left));
                    out.add(parseFreqPart(right));
                    split = true;
                    break;
                }
            }
            if (!split) out.add(parseFreqPart(p));
        }
        return out;
    }

    private static FreqLine parseFreqPart(String p) {
        String label = "";
        Matcher lm = FREQ_LABEL.matcher(p);
        if (lm.find()) {
            label = lm.group(1);
            p = p.substring(lm.end());
        }
        Matcher m = HZ.matcher(p);
        if (m.find()) {
            String num = m.group(1) + (m.group(2) != null ? "\u2013" + m.group(2) : "");
            String unit = m.group(3).equalsIgnoreCase("khz") ? "kHz" : "Hz";
            String value = num + " " + unit;
            String rest = (p.substring(0, m.start()) + " " + p.substring(m.end())).trim();
            return new FreqLine(label, value, cleanNote(rest));
        }
        return new FreqLine(label, "", cleanNote(p));
    }

    private static String cleanNote(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        // لو الملاحظة كلها بين قوسين نشيل القوسين
        if (t.startsWith("(") && t.endsWith(")") && t.indexOf(')') == t.length() - 1) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    // =====================================================================
    // مواضع الأقطاب
    // =====================================================================

    public static final class Terminal {
        public final boolean positive;
        public final boolean negative;
        public final String text;
        Terminal(boolean positive, boolean negative, String text) {
            this.positive = positive;
            this.negative = negative;
            this.text = text;
        }
    }

    public static final class PoleCard {
        public String label = "";
        public final List<Terminal> terminals = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
    }

    private static final Pattern EXPLICIT_MARK = Pattern.compile("\\((\\+|-|\\u2212|\\u2013)\\)");
    private static final Pattern WORD_MARK = Pattern.compile(
            "\u0642\u0637\u0628\\s+(\u0645\u0648\u062C\u0628|\u0633\u0627\u0644\u0628)"
                    + "|(?<![\\p{L}])\u0627\u0644(\u0645\u0648\u062C\u0628|\u0633\u0627\u0644\u0628)(?![\\p{L}])");
    private static final Pattern DESCRIPTOR = Pattern.compile(
            "^\\s*(?:\u0627\u0644\u0642\u0637\u0628|\u0627\u0644\u0623\u0642\u0637\u0627\u0628)\\s+"
                    + "(?:\u0627\u0644\u0645\u0648\u062C\u0628\u0629?|\u0627\u0644\u0633\u0627\u0644\u0628\u0629?)"
                    + "(?:\\s*\\(([^)]*)\\))?\\s*[:\\uFF1A]\\s*");
    private static final Pattern CHANNEL_TAG = Pattern.compile("^(?:CH\\s?\\d|القناة\\s+\\S+)$");

    /** يحوّل قائمة أسطر الأقطاب إلى بطاقات (قناة/مرحلة/غرض) بأقطاب موجبة وسالبة. */
    public static List<PoleCard> parsePoles(List<String> poles) {
        List<PoleCard> cards = new ArrayList<>();
        if (poles == null) return cards;
        for (String raw : poles) {
            if (raw == null || raw.trim().isEmpty()) continue;
            PoleCard entry = parseEntry(raw.trim());
            PoleCard last = cards.isEmpty() ? null : cards.get(cards.size() - 1);
            // سطر وصفي بدون عنوان ولا أقطاب: يلتحق بالبطاقة السابقة كملاحظة إضافية
            if (last != null && entry.label.isEmpty() && entry.terminals.isEmpty() && !entry.notes.isEmpty()) {
                last.notes.addAll(entry.notes);
                continue;
            }
            boolean mergeable = entry.label.isEmpty() && !entry.terminals.isEmpty() && entry.notes.isEmpty()
                    && last != null && last.label.isEmpty() && last.notes.isEmpty() && !last.terminals.isEmpty();
            if (mergeable) {
                last.terminals.addAll(entry.terminals);
            } else {
                cards.add(entry);
            }
        }
        return cards;
    }

    private static PoleCard parseEntry(String s) {
        PoleCard card = new PoleCard();

        // 1) مواضع العلامات: (+) و(-) الصريحة أولًا، وإلا الكلمات (الموجب/السالب/قطب موجب...)
        List<int[]> marks = new ArrayList<>(); // {start, end, positive?1:0}
        Matcher em = EXPLICIT_MARK.matcher(s);
        while (em.find()) {
            marks.add(new int[]{em.start(), em.end(), em.group(1).equals("+") ? 1 : 0});
        }
        boolean explicit = !marks.isEmpty();
        if (!explicit) {
            Matcher wm = WORD_MARK.matcher(s);
            while (wm.find()) {
                String g = wm.group();
                marks.add(new int[]{wm.start(), wm.end(), g.contains("\u0645\u0648\u062C\u0628") ? 1 : 0});
            }
        }

        int firstMark = marks.isEmpty() ? s.length() : marks.get(0)[0];
        String head = s.substring(0, firstMark);

        // 2) العنوان (label): أول نقطتين على المستوى الأعلى قبل أول علامة
        int colon = topLevelColon(head);
        String label = "";
        String lead;
        if (colon >= 0 && colon <= 60) {
            label = head.substring(0, colon).trim();
            lead = head.substring(colon + 1).trim();
        } else {
            lead = head.trim();
        }

        if (marks.isEmpty()) {
            // نص وصفي بدون علامات
            if (!label.isEmpty()) {
                card.label = label;
                if (!lead.isEmpty()) card.notes.add(lead);
            } else {
                card.notes.add(s);
            }
            return card;
        }

        if (label.isEmpty() && CHANNEL_TAG.matcher(lead).matches()) {
            label = lead;
            lead = "";
        } else if (!label.isEmpty() && CHANNEL_TAG.matcher(lead).matches()) {
            label = label + " \u00B7 " + lead;
            lead = "";
        } else if (lead.length() <= 12) {
            lead = ""; // مثل "يتم وضع" - مجرد فعل تمهيدي
        }
        card.label = label;
        if (!lead.isEmpty()) card.notes.add(lead);

        // 3) الأقطاب
        boolean pendPos = false, pendNeg = false;
        for (int i = 0; i < marks.size(); i++) {
            int[] mk = marks.get(i);
            int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : s.length();
            String seg = s.substring(mk[1], end);
            if (explicit) {
                Matcher dm = DESCRIPTOR.matcher(seg);
                if (dm.find()) {
                    String keep = dm.group(1) == null ? "" : dm.group(1).trim();
                    seg = (keep.isEmpty() ? "" : "(" + keep + ") ") + seg.substring(dm.end());
                }
            }
            String text = cleanTerminal(seg);
            if (mk[2] == 1) pendPos = true; else pendNeg = true;
            if (text.isEmpty() && i + 1 < marks.size()) {
                continue; // "(+) و (-) على ..." -> قطبان معًا
            }
            if (!text.isEmpty()) {
                card.terminals.add(new Terminal(pendPos, pendNeg, text));
            }
            pendPos = false;
            pendNeg = false;
        }
        if (card.terminals.isEmpty() && card.notes.isEmpty()) {
            card.notes.add(s);
        }
        return card;
    }

    private static int topLevelColon(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') { if (depth > 0) depth--; }
            else if ((c == ':' || c == '\uFF1A') && depth == 0) return i;
        }
        return -1;
    }

    private static String cleanTerminal(String seg) {
        String t = seg.trim();
        t = t.replaceAll("^[\\u060C,:\\s]+", "");
        t = t.replaceAll("(?:^|\\s)\u0648\\s*$", "");
        t = t.replaceAll("[\\s\\u060C,.]+$", "");
        return t.trim();
    }

    // =====================================================================
    // ملخصات قصيرة للقوائم
    // =====================================================================

    /** أول قيمة تردد مقروءة (مثل "77.3 Hz") أو "" */
    public static String shortFrequency(String freq) {
        for (FreqLine f : parseFrequency(freq)) {
            if (!f.value.isEmpty()) return f.value;
        }
        return "";
    }
}
