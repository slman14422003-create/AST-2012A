package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.graphics.RectF;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * تجهيز نص صفحة PDF للقراءة الصوتية: استخراج كلمة-كلمة (PdfBox) مع موضع كل كلمة
 * كنسبة من أبعاد الصفحة (0..1) حتى يقدر PdfHighlightView يظلّل الكلمة المنطوقة
 * فوق صورة الصفحة بأي تكبير، ثم تقسيم النص إلى مقاطع (جمل) مناسبة للمحرك الصوتي،
 * وتحديد لغة كل مقطع (عربي / إنجليزي / فرنسي / تركي) لاختيار الصوت الأدق.
 *
 * ترتيب القراءة = ترتيب بصري من مواضع الأحرف الفعلية (أسطر كاملة من اليمين لليسار للعربي، ثم السطر
 * التالي، مع دعم الأعمدة والعناوين العريضة والجداول) - لا يعتمد على ترتيب تخزين النص داخل الملف.
 * الصفحات الممسوحة ضوئيًا (صور بدون طبقة نص) ما فيها نص لتُقرأ - تُرجَع صفحة فاضية.
 */
final class PdfSpeechText {

    private PdfSpeechText() {
    }

    // ------------------------------------------------------------------ النماذج

    static final class Word {
        final String text;
        /** موضع الكلمة كنسبة من عرض/ارتفاع الصفحة (Y من الأعلى). */
        final RectF box;
        final int line;
        /** رقم الجملة داخل الصفحة (يُستخدم لتظليل الجملة الحالية فقط وليس المقطع كله). */
        int sent;
        /** بداية/نهاية الكلمة داخل PageText.text (تُملأ عند بناء النص). */
        int start;
        int end;

        Word(String text, RectF box, int line) {
            this.text = text;
            this.box = box;
            this.line = line;
        }
    }

    static final class Chunk {
        final int firstWord;
        final int lastWord; // شامل
        final int start;    // داخل PageText.text
        final int end;
        final String lang;  // ar / en / fr / tr

        Chunk(int firstWord, int lastWord, int start, int end, String lang) {
            this.firstWord = firstWord;
            this.lastWord = lastWord;
            this.start = start;
            this.end = end;
            this.lang = lang;
        }
    }

    static final class PageText {
        final int pageIndex;
        final String text;
        final List<Word> words;
        final List<Chunk> chunks;
        /** لغة الكلمات اللاتينية في الملف (en/fr/tr) - تُستخدم لصوت الكلمات الأجنبية داخل المقاطع العربية. */
        final String latin;

        PageText(int pageIndex, String text, List<Word> words, List<Chunk> chunks, String latin) {
            this.latin = latin;
            this.pageIndex = pageIndex;
            this.text = text;
            this.words = words;
            this.chunks = chunks;
        }

        boolean isEmpty() {
            return chunks.isEmpty();
        }

        /** فهرس الكلمة التي تحتوي هذا الموضع داخل النص (أو أقرب كلمة سابقة). */
        int wordAtOffset(int offset) {
            int lo = 0, hi = words.size() - 1, ans = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (words.get(mid).start <= offset) {
                    ans = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return ans;
        }
    }

    // ------------------------------------------------------------------ المصدر (ملف مفتوح)

    /** يفتح المستند مرة واحدة ويستخرج صفحاته عند الطلب. غير آمن للاستخدام من أكثر من خيط. */
    static final class Source implements Closeable {
        private final PDDocument doc;
        private String latinHint = null;

        private Source(PDDocument doc) {
            this.doc = doc;
        }

        static Source open(Context ctx, File file) throws IOException {
            PDFBoxResourceLoader.init(ctx.getApplicationContext());
            return new Source(PDDocument.load(file));
        }

        int pageCount() {
            return doc.getNumberOfPages();
        }

        PageText page(int pageIndex) {
            try {
                if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) return emptyPage(pageIndex);
                GlyphCollector stripper = new GlyphCollector();
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                stripper.setSortByPosition(false);
                stripper.getText(doc);
                List<Word> words = dropRunningHeadersFooters(assemble(stripper.glyphs, stripper.pw, stripper.ph));
                if (words.isEmpty()) return emptyPage(pageIndex);
                return build(pageIndex, words, this);
            } catch (Throwable t) {
                return emptyPage(pageIndex);
            }
        }

        @Override
        public void close() {
            try {
                doc.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** حدود هامش رأس/تذييل الصفحة (نسبة من ارتفاع الصفحة). */
    private static final float HEADER_BOTTOM = 0.078f;
    private static final float FOOTER_TOP = 0.91f;
    private static final int MIN_BODY_WORDS = 25;

    /**
     * يحذف رأس الصفحة المتكرر ("الفصل الأول"، عنوان الملف) وتذييلها (رقم الصفحة) من القراءة.
     * يُطبَّق فقط لو في الصفحة نص أساسي كافٍ، حتى لا تضيع صفحات الغلاف/العناوين القليلة النص.
     */
    private static List<Word> dropRunningHeadersFooters(List<Word> words) {
        int body = 0;
        for (Word w : words) {
            if (!(w.box.bottom < HEADER_BOTTOM || w.box.top > FOOTER_TOP)) body++;
        }
        if (body < MIN_BODY_WORDS) return words;
        List<Word> out = new ArrayList<>(words.size());
        for (Word w : words) {
            if (w.box.bottom < HEADER_BOTTOM || w.box.top > FOOTER_TOP) continue;
            out.add(w);
        }
        return out;
    }

    private static PageText emptyPage(int pageIndex) {
        return new PageText(pageIndex, "", new ArrayList<>(), new ArrayList<>(), "en");
    }

    // ------------------------------------------------------------------ الاستخراج
    //
    // الاستخراج هنا يعتمد على *مواضع الأحرف الفعلية* في الصفحة فقط، لا على ترتيب ورود النص في
    // الملف ولا على تجميع PdfBox للأسطر (الذي يعيد ترتيب/عكس الحروف العربية أحيانًا ويجعل القراءة
    // تقفز بين طرفي السطر). الخطوات:
    //   1) نجمع كل حرف بموضعه (Glyph) ونطبّع أشكال العرض العربية (ﻻ / ﻣ ...) إلى حروفها الأصلية.
    //   2) نجمع الأحرف في "أسطر" بحسب خط الأساس، ثم نقطّع السطر إلى مقاطع (Seg) عند الفجوات الكبيرة
    //      (عمود آخر / خلية جدول).
    //   3) داخل كل مقطع نبني الكلمات من الفجوات الأفقية، ونحدد ترتيب القراءة (يمين->يسار للعربي
    //      مع إبقاء الكلمات اللاتينية/الأرقام المتتالية بترتيبها الطبيعي).
    //   4) نرتّب المقاطع على مستوى الصفحة بخوارزمية XY-cut: أعمدة (العربي من اليمين) ثم أسطر من الأعلى،
    //      وبدون تحويل الجداول إلى أعمدة (الجدول يُقرأ صفًا صفًا).

    /** حرف واحد من الصفحة بموضعه الفعلي (بالنقطة، Y من الأعلى). */
    private static final class Glyph {
        String u;
        float x0, x1, base, top, bottom, font;
        boolean mark;
        boolean space;

        float cx() {
            return (x0 + x1) / 2f;
        }
    }

    private static final class RawWord {
        String text;
        RectF box;
        int line;
        /** L = لاتيني، R = عربي/عبري، N = أرقام/رموز فقط. */
        char cls;
    }

    /** مقطع سطر متصل (سطر كامل، أو جزء منه إذا كان في الصفحة أعمدة/خلايا). */
    private static final class Seg {
        final List<RawWord> words = new ArrayList<>();
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        float base;
        float font = 10f;
        boolean rtl;

        float cx() {
            return (x0 + x1) / 2f;
        }

        float width() {
            return x1 - x0;
        }
    }

    /** فجوة (كمضاعف لحجم الخط) أكبر منها = نهاية كلمة. */
    private static final float WORD_GAP_EM = 0.20f;
    /** فجوة (كمضاعف لحجم الخط) أكبر منها = عمود/خلية أخرى. */
    private static final float SEG_GAP_EM = 2.5f;

    private static final class GlyphCollector extends PDFTextStripper {
        final List<Glyph> glyphs = new ArrayList<>();
        float pw = 0f;
        float ph = 0f;

        GlyphCollector() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> tps) {
            if (tps == null) return;
            for (TextPosition tp : tps) {
                if (tp == null) continue;
                if (pw <= 0f || ph <= 0f) {
                    pw = tp.getPageWidth();
                    ph = tp.getPageHeight();
                }
                String u = cleanGlyph(tp.getUnicode());
                if (u == null) continue;
                Glyph g = new Glyph();
                g.u = u;
                g.space = isBlank(u);
                float w = tp.getWidthDirAdj();
                if (w <= 0f) w = tp.getWidth();
                if (w < 0f) w = 0f;
                float h = tp.getHeightDir();
                if (h <= 0f) h = tp.getHeight();
                g.font = Math.max(1f, tp.getFontSizeInPt());
                if (h <= 0f) h = g.font;
                g.x0 = tp.getXDirAdj();
                g.x1 = g.x0 + w;
                g.base = tp.getYDirAdj();
                g.top = g.base - h;
                g.bottom = g.base + h * 0.25f;
                if (!g.space) {
                    int t = Character.getType(g.u.codePointAt(0));
                    g.mark = t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK
                            || t == Character.COMBINING_SPACING_MARK;
                }
                // نص خارج حدود الصفحة (مخفي) لا يُقرأ
                if (pw > 0f && (g.x1 < -2f || g.x0 > pw + 2f)) continue;
                if (ph > 0f && (g.base < -2f || g.base > ph + 2f)) continue;
                glyphs.add(g);
            }
        }
    }

    private static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
        }
        return true;
    }

    /** ينظّف نص حرف واحد: يحذف محارف التحكم/التطويل ويفكّ أشكال العرض العربية واللاتينية المركّبة. */
    private static String cleanGlyph(String u) {
        if (u == null || u.isEmpty()) return null;
        StringBuilder sb = null;
        boolean needNorm = false;
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            boolean drop = (c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E)
                    || (c >= 0x2066 && c <= 0x2069) || c == 0xFEFF || c == 0x00AD || c == 0x0640
                    || c == 0x0000 || c == 0xFFFD || (c < 0x20 && c != '\t');
            if ((c >= 0xFB00 && c <= 0xFB06) || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) {
                needNorm = true;
            }
            if (drop) {
                if (sb == null) {
                    sb = new StringBuilder(u.length());
                    sb.append(u, 0, i);
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
        String r = sb != null ? sb.toString() : u;
        if (needNorm) {
            try {
                r = Normalizer.normalize(r, Normalizer.Form.NFKC);
            } catch (Throwable ignored) {
            }
        }
        return r.isEmpty() ? null : r;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ---- 1) من الأحرف إلى مقاطع

    /** يدمج علامات التشكيل/الحركات مع حرفها الأساسي (لا تدخل في ترتيب الكلمات). */
    private static List<Glyph> attachMarks(List<Glyph> all) {
        List<Glyph> bases = new ArrayList<>(all.size());
        List<Glyph> marks = new ArrayList<>();
        for (Glyph g : all) {
            if (g.mark) marks.add(g);
            else bases.add(g);
        }
        for (Glyph m : marks) {
            Glyph best = null;
            float bestD = Float.MAX_VALUE;
            float mc = m.cx();
            for (Glyph b : bases) {
                if (b.space) continue;
                if (Math.abs(b.base - m.base) > b.font * 1.2f) continue;
                float d;
                if (mc >= b.x0 && mc <= b.x1) d = 0f;
                else d = Math.min(Math.abs(mc - b.x0), Math.abs(mc - b.x1));
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            if (best != null && bestD <= best.font * 0.6f) best.u = best.u + m.u;
        }
        return bases;
    }

    private static List<Seg> buildSegments(List<Glyph> bases, float pw, float ph) {
        List<Seg> segs = new ArrayList<>();
        Collections.sort(bases, (a, b) -> Float.compare(a.base, b.base));
        List<List<Glyph>> bands = new ArrayList<>();
        List<Glyph> cur = null;
        float mean = 0f;
        float bandFont = 0f;
        for (Glyph g : bases) {
            if (cur != null && Math.abs(g.base - mean) <= 0.5f * Math.max(bandFont, g.font)) {
                cur.add(g);
                mean += (g.base - mean) / cur.size();
                bandFont = Math.max(bandFont, g.font);
            } else {
                cur = new ArrayList<>();
                cur.add(g);
                mean = g.base;
                bandFont = g.font;
                bands.add(cur);
            }
        }
        for (List<Glyph> band : bands) {
            Collections.sort(band, (a, b) -> Float.compare(a.cx(), b.cx()));
            List<Glyph> part = new ArrayList<>();
            float maxR = -Float.MAX_VALUE;
            float prevFont = 0f;
            for (Glyph g : band) {
                if (g.space) {
                    if (!part.isEmpty()) part.add(g);
                    continue;
                }
                if (!part.isEmpty()) {
                    float gap = g.x0 - maxR;
                    if (gap > Math.max(g.font, prevFont) * SEG_GAP_EM) {
                        addSegment(segs, part, pw, ph);
                        part = new ArrayList<>();
                    }
                }
                maxR = part.isEmpty() ? g.x1 : Math.max(maxR, g.x1);
                part.add(g);
                prevFont = g.font;
            }
            addSegment(segs, part, pw, ph);
        }
        return segs;
    }

    private static void addSegment(List<Seg> segs, List<Glyph> glyphs, float pw, float ph) {
        if (glyphs.isEmpty()) return;
        List<List<Glyph>> ws = splitWords(glyphs);
        Seg seg = new Seg();
        List<RawWord> words = new ArrayList<>();
        float fontSum = 0f;
        float baseSum = 0f;
        int gc = 0;
        for (List<Glyph> wg : ws) {
            String text = logicalWord(wg).trim();
            if (text.isEmpty()) continue;
            boolean has = false;
            for (int i = 0; i < text.length(); i++) {
                if (Character.isLetterOrDigit(text.charAt(i))) {
                    has = true;
                    break;
                }
            }
            if (!has) continue; // رموز/نقاط تعداد/خطوط نقطية/أيقونات
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (Glyph g : wg) {
                x0 = Math.min(x0, g.x0);
                x1 = Math.max(x1, g.x1);
                y0 = Math.min(y0, g.top);
                y1 = Math.max(y1, g.bottom);
                fontSum += g.font;
                baseSum += g.base;
                gc++;
            }
            RawWord rw = new RawWord();
            rw.text = text;
            rw.box = new RectF(clamp01(x0 / pw), clamp01(y0 / ph), clamp01(x1 / pw), clamp01(y1 / ph));
            rw.cls = wordClass(text);
            words.add(rw);
            seg.x0 = Math.min(seg.x0, x0);
            seg.x1 = Math.max(seg.x1, x1);
            seg.y0 = Math.min(seg.y0, y0);
            seg.y1 = Math.max(seg.y1, y1);
        }
        if (words.isEmpty() || gc == 0) return;
        seg.font = fontSum / gc;
        seg.base = baseSum / gc;
        int ar = 0, lat = 0;
        for (RawWord w : words) {
            for (int i = 0; i < w.text.length(); i++) {
                char c = w.text.charAt(i);
                if (!Character.isLetter(c)) continue;
                if (isRtlLetterChar(c)) ar++;
                else lat++;
            }
        }
        seg.rtl = ar > 0 && ar >= lat;
        seg.words.addAll(orderWords(words, seg.rtl));
        segs.add(seg);
    }

    /** يقسّم أحرف المقطع (مرتبة بصريًا) إلى كلمات: عند الفراغ الفعلي أو عند فجوة أفقية بعرض مسافة. */
    private static List<List<Glyph>> splitWords(List<Glyph> glyphs) {
        List<List<Glyph>> out = new ArrayList<>();
        List<Glyph> w = new ArrayList<>();
        float maxR = -Float.MAX_VALUE;
        float prevFont = 0f;
        for (Glyph g : glyphs) {
            if (g.space) {
                if (!w.isEmpty()) {
                    out.add(w);
                    w = new ArrayList<>();
                }
                continue;
            }
            if (!w.isEmpty()) {
                float gap = g.x0 - maxR;
                if (gap > Math.max(g.font, prevFont) * WORD_GAP_EM) {
                    out.add(w);
                    w = new ArrayList<>();
                }
            }
            maxR = w.isEmpty() ? g.x1 : Math.max(maxR, g.x1);
            w.add(g);
            prevFont = g.font;
        }
        if (!w.isEmpty()) out.add(w);
        return out;
    }

    /**
     * يحوّل أحرف كلمة مرتّبة بصريًا (يسار->يمين) إلى نصها المنطقي. الكلمة العربية تُقرأ من اليمين،
     * لكن مقاطع الأرقام/الحروف اللاتينية داخلها تبقى بترتيبها الطبيعي (مثل "ج2" أو "mA").
     */
    private static String logicalWord(List<Glyph> gs) {
        boolean anyRtl = false;
        for (Glyph g : gs) {
            if (hasRtlLetter(g.u)) {
                anyRtl = true;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!anyRtl) {
            for (Glyph g : gs) sb.append(g.u);
            return sb.toString();
        }
        List<String> units = new ArrayList<>();
        int i = 0;
        while (i < gs.size()) {
            if (isLtrCell(gs.get(i).u)) {
                StringBuilder run = new StringBuilder();
                while (i < gs.size() && isLtrCell(gs.get(i).u)) {
                    run.append(gs.get(i).u);
                    i++;
                }
                units.add(run.toString());
            } else {
                units.add(gs.get(i).u);
                i++;
            }
        }
        for (int k = units.size() - 1; k >= 0; k--) sb.append(units.get(k));
        return sb.toString();
    }

    private static boolean isRtlLetterChar(char c) {
        return Character.isLetter(c) && (isArabicChar(c) || (c >= 0x0590 && c <= 0x05FF));
    }

    private static boolean hasRtlLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isRtlLetterChar(s.charAt(i))) return true;
        }
        return false;
    }

    /** خلية (حرف) لاتينية/رقمية: تُقرأ من اليسار لليمين حتى داخل الكلمة العربية. */
    private static boolean isLtrCell(String u) {
        boolean ltr = false;
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (isRtlLetterChar(c)) return false;
            if (Character.isLetterOrDigit(c)) ltr = true;
        }
        return ltr;
    }

    private static char wordClass(String t) {
        boolean lat = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!Character.isLetter(c)) continue;
            if (isRtlLetterChar(c)) return 'R';
            lat = true;
        }
        return lat ? 'L' : 'N';
    }

    private static final String[] UNIT_TOKENS = {
            "hz", "khz", "mhz", "ma", "a", "v", "mv", "ms", "us", "\u00B5s", "\u03BCs", "s", "sec", "min",
            "mm", "cm", "m", "kg", "g", "ohm", "\u03A9", "\u00B0c", "c"
    };

    private static boolean isUnitToken(String t) {
        String l = t.toLowerCase(Locale.ROOT);
        for (String u : UNIT_TOKENS) {
            if (l.equals(u)) return true;
        }
        return false;
    }

    /**
     * يرتّب كلمات مقطع واحد (تصل بترتيبها البصري يسار->يمين) إلى ترتيب القراءة.
     * سطر عربي: من اليمين لليسار، ومجموعات الكلمات اللاتينية المتتالية تبقى بترتيبها الطبيعي.
     * سطر لاتيني: من اليسار لليمين، ومجموعات الكلمات العربية المتتالية تُقرأ من اليمين.
     */
    private static List<RawWord> orderWords(List<RawWord> v, boolean rtl) {
        int n = v.size();
        if (n < 2) return v;
        final char runCls = rtl ? 'L' : 'R';
        List<List<RawWord>> units = new ArrayList<>();
        int i = 0;
        while (i < n) {
            RawWord w = v.get(i);
            List<RawWord> u = new ArrayList<>(2);
            u.add(w);
            int j = i + 1;
            if (w.cls == runCls) {
                while (j < n) {
                    RawWord x = v.get(j);
                    if (x.cls == runCls) {
                        u.add(x);
                        j++;
                        continue;
                    }
                    if (x.cls == 'N') {
                        boolean between = j + 1 < n && v.get(j + 1).cls == runCls;
                        // رقم يلي كلمة لاتينية في سطر عربي (TENS 2) يتبعها، إلا لو كانت وحدة قياس (20 Hz)
                        boolean trailing = rtl && v.get(j - 1).cls == 'L' && !isUnitToken(v.get(j - 1).text);
                        if (between || trailing) {
                            u.add(x);
                            j++;
                            continue;
                        }
                    }
                    break;
                }
            }
            units.add(u);
            i = j;
        }
        List<RawWord> out = new ArrayList<>(n);
        if (rtl) {
            for (int k = units.size() - 1; k >= 0; k--) out.addAll(units.get(k));
        } else {
            for (List<RawWord> u : units) {
                if (u.size() > 1 && u.get(0).cls == 'R') Collections.reverse(u);
                out.addAll(u);
            }
        }
        return out;
    }

    // ---- 2) ترتيب المقاطع على مستوى الصفحة (XY-cut)

    private static boolean blockRtl(List<Seg> segs) {
        int r = 0, l = 0;
        for (Seg s : segs) {
            if (s.rtl) r += s.words.size();
            else l += s.words.size();
        }
        return r > 0 && r >= l;
    }

    private static void xyCut(List<Seg> segs, float pw, List<Seg> out, int depth) {
        if (segs.size() <= 1) {
            out.addAll(segs);
            return;
        }
        if (depth < 8) {
            List<List<Seg>> parts = splitColumns(segs, pw);
            if (parts != null) {
                for (List<Seg> p : parts) xyCut(p, pw, out, depth + 1);
                return;
            }
        }
        sortRows(segs, out);
    }

    /** أسطر بترتيب من الأعلى للأسفل، وداخل كل سطر مقاطعه بحسب اتجاه السطر. */
    private static void sortRows(List<Seg> segs, List<Seg> out) {
        List<Seg> s = new ArrayList<>(segs);
        Collections.sort(s, (a, b) -> Float.compare(a.base, b.base));
        List<List<Seg>> rows = new ArrayList<>();
        List<Seg> row = null;
        float mean = 0f;
        float rowFont = 0f;
        for (Seg g : s) {
            if (row != null && Math.abs(g.base - mean) <= 0.5f * Math.max(rowFont, g.font)) {
                row.add(g);
                mean += (g.base - mean) / row.size();
                rowFont = Math.max(rowFont, g.font);
            } else {
                row = new ArrayList<>();
                row.add(g);
                mean = g.base;
                rowFont = g.font;
                rows.add(row);
            }
        }
        for (List<Seg> r : rows) {
            final boolean rtl = blockRtl(r);
            Collections.sort(r, (a, b) -> rtl ? Float.compare(b.cx(), a.cx()) : Float.compare(a.cx(), b.cx()));
            out.addAll(r);
        }
    }

    /**
     * يكتشف أعمدة نص حقيقية: ممر عمودي فارغ (تقريبًا) بين مقاطع الصفحة. المقاطع التي تعبر الممر
     * (عناوين/تذييلات بعرض الصفحة) تبقى كتلًا مستقلة بترتيبها الرأسي؛ وبين الكتل تُقرأ الأعمدة عمودًا
     * عمودًا (العربي من اليمين). لو بدا التخطيط جدولًا (خلايا قصيرة متحاذية) لا نقطّعه لأعمدة، فيُقرأ صفًا صفًا.
     * يرجع null لو لا توجد أعمدة.
     */
    private static List<List<Seg>> splitColumns(List<Seg> segs, float pw) {
        int n = segs.size();
        if (n < 6) return null;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, fontSum = 0f;
        for (Seg s : segs) {
            minX = Math.min(minX, s.x0);
            maxX = Math.max(maxX, s.x1);
            fontSum += s.font;
        }
        float avgFont = fontSum / n;
        float minGutter = Math.max(1.2f * avgFont, 8f);
        int bins = (int) Math.ceil(maxX - minX) + 1;
        if (bins < 20 || bins > 5000) return null;
        int[] cover = new int[bins];
        for (Seg s : segs) {
            int a = Math.max(0, Math.min(bins - 1, (int) Math.floor(s.x0 - minX)));
            int b = Math.max(0, Math.min(bins - 1, (int) Math.ceil(s.x1 - minX)));
            for (int k = a; k <= b; k++) cover[k]++;
        }
        int maxSpan = (int) Math.floor(n * 0.12f);
        int bestStart = -1, bestLen = 0;
        int k = 0;
        while (k < bins) {
            if (cover[k] <= maxSpan) {
                int st = k;
                while (k < bins && cover[k] <= maxSpan) k++;
                int len = k - st;
                if (st > 0 && k < bins && len >= minGutter && len > bestLen) {
                    bestStart = st;
                    bestLen = len;
                }
            } else {
                k++;
            }
        }
        if (bestStart < 0) return null;
        float gs = minX + bestStart;
        float ge = minX + bestStart + bestLen;
        float mid = (gs + ge) / 2f;

        List<Seg> left = new ArrayList<>();
        List<Seg> right = new ArrayList<>();
        for (Seg s : segs) {
            if (s.x0 < gs && s.x1 > ge) continue; // يعبر الممر
            if (s.cx() < mid) left.add(s);
            else right.add(s);
        }
        if (left.size() < 3 || right.size() < 3) return null;
        if (looksLikeTable(left, right, pw)) return null;

        final boolean rtl = blockRtl(segs);
        List<Seg> sorted = new ArrayList<>(segs);
        Collections.sort(sorted, (a, b) -> Float.compare(a.y0, b.y0));
        List<List<Seg>> parts = new ArrayList<>();
        List<Seg> full = new ArrayList<>();
        List<Seg> colL = new ArrayList<>();
        List<Seg> colR = new ArrayList<>();
        boolean inCols = false;
        for (Seg s : sorted) {
            boolean span = s.x0 < gs && s.x1 > ge;
            if (span) {
                if (inCols) {
                    flushColumns(parts, colL, colR, rtl);
                    colL = new ArrayList<>();
                    colR = new ArrayList<>();
                    inCols = false;
                }
                full.add(s);
            } else {
                if (!inCols) {
                    if (!full.isEmpty()) {
                        parts.add(full);
                        full = new ArrayList<>();
                    }
                    inCols = true;
                }
                if (s.cx() < mid) colL.add(s);
                else colR.add(s);
            }
        }
        if (inCols) flushColumns(parts, colL, colR, rtl);
        if (!full.isEmpty()) parts.add(full);
        return parts.size() >= 2 ? parts : null;
    }

    private static void flushColumns(List<List<Seg>> parts, List<Seg> colL, List<Seg> colR, boolean rtl) {
        if (rtl) {
            if (!colR.isEmpty()) parts.add(colR);
            if (!colL.isEmpty()) parts.add(colL);
        } else {
            if (!colL.isEmpty()) parts.add(colL);
            if (!colR.isEmpty()) parts.add(colR);
        }
    }

    /** جدول: خلايا قصيرة تتحاذى أفقيًا مع خلايا العمود الآخر (صفوف). النص المتعدد الأعمدة أسطره طويلة. */
    private static boolean looksLikeTable(List<Seg> left, List<Seg> right, float pw) {
        List<Seg> small = left.size() <= right.size() ? left : right;
        List<Seg> other = small == left ? right : left;
        int aligned = 0;
        for (Seg s : small) {
            for (Seg o : other) {
                if (Math.abs(s.base - o.base) <= 0.6f * Math.max(s.font, o.font)) {
                    aligned++;
                    break;
                }
            }
        }
        float frac = aligned / (float) small.size();
        List<Float> widths = new ArrayList<>(left.size() + right.size());
        for (Seg s : left) widths.add(s.width());
        for (Seg s : right) widths.add(s.width());
        Collections.sort(widths);
        float medianW = widths.get(widths.size() / 2);
        return frac >= 0.7f && medianW < 0.25f * pw;
    }

    /** يجمع كل شيء: أحرف الصفحة -> كلمات مرتبة بترتيب القراءة (line = رقم المقطع). */
    private static List<Word> assemble(List<Glyph> all, float pw, float ph) {
        List<Word> out = new ArrayList<>();
        if (all == null || all.isEmpty() || pw <= 0f || ph <= 0f) return out;
        List<Glyph> bases = attachMarks(all);
        List<Seg> segs = buildSegments(bases, pw, ph);
        if (segs.isEmpty()) return out;
        List<Seg> ordered = new ArrayList<>(segs.size());
        xyCut(segs, pw, ordered, 0);
        int line = 0;
        for (Seg s : ordered) {
            for (RawWord w : s.words) out.add(new Word(w.text, w.box, line));
            line++;
        }
        return out;
    }

    // ------------------------------------------------------------------ بناء النص والمقاطع

    private static PageText build(int pageIndex, List<Word> words, Source src) {
        // بداية الفقرات: تُستنتج من الفجوات الرأسية بين الأسطر (وقفزة الأعمدة للأعلى).
        boolean[] paraBefore = inferParagraphs(words);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            String t = w.text;
            boolean glue = false;
            if (i > 0) {
                Word prev = words.get(i - 1);
                // كلمة مقسومة بشرطة في آخر السطر (لاتيني فقط): ندمجها مع تاليتها
                if (!paraBefore[i] && prev.line != w.line && prev.text.endsWith("-") && prev.text.length() > 2
                        && Character.isLowerCase(t.charAt(0)) && isLatin(prev.text)) {
                    glue = true;
                }
            }
            if (i > 0 && !glue) {
                if (paraBefore[i]) {
                    // بداية فقرة/عنوان/خلية: لو ما قبلها علامة ترقيم نضيف نقطة كي يقف الصوت وقفة طبيعية
                    // بدل ما يلصق العنوان بأول جملة (الإزاحات محسوبة للكلمات فلا تتأثر).
                    if (!endsWithPunct(words.get(i - 1).text)) sb.append('.');
                    sb.append('\n');
                } else {
                    sb.append(' ');
                }
            }
            w.start = sb.length();
            if (i + 1 < words.size()) {
                Word next = words.get(i + 1);
                if (!paraBefore[i + 1] && next.line != w.line && t.endsWith("-") && t.length() > 2 && isLatin(t)
                        && Character.isLowerCase(next.text.charAt(0))) {
                    t = t.substring(0, t.length() - 1); // نحذف الشرطة، والكلمة التالية تلتصق بها
                }
            }
            sb.append(t);
            w.end = sb.length();
        }
        String text = sb.toString();

        int sentNo = 0;
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                Word prev = words.get(i - 1);
                Word cur = words.get(i);
                if (paraBefore[i]
                        || (endsSentence(prev.text) && !isAbbreviation(prev.text) && !startsLowerLatin(cur.text))) {
                    sentNo++;
                }
            }
            words.get(i).sent = sentNo;
        }

        String latin = src.latinHint;
        if (latin == null) {
            latin = detectLatinLang(text);
            if (countLatinLetters(text) > 200) src.latinHint = latin; // نثبّتها بعد عيّنة كافية
        }

        List<Chunk> chunks = mergeChunks(makeChunks(words, text, paraBefore, latin), text);
        return new PageText(pageIndex, text, words, chunks, latin);
    }

    private static boolean[] inferParagraphs(List<Word> words) {
        boolean[] flags = new boolean[words.size()];
        // المسافة المعتادة بين سطرين في هذه الصفحة (الوسيط). الاعتماد على ارتفاع الحرف كان يعتبر
        // كل سطر فقرة مستقلة في الملفات ذات التباعد المزدوج (وهذا كان يقطع الجملة عند نهاية كل سطر).
        List<Float> pitches = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            Word prev = words.get(i - 1);
            Word cur = words.get(i);
            if (cur.line == prev.line) continue;
            float dy = cur.box.top - prev.box.top;
            if (dy > 0.002f) pitches.add(dy);
        }
        float median = -1f;
        if (pitches.size() >= 3) {
            java.util.Collections.sort(pitches);
            median = pitches.get(pitches.size() / 2);
        }
        for (int i = 1; i < words.size(); i++) {
            Word prev = words.get(i - 1);
            Word cur = words.get(i);
            if (cur.line == prev.line) continue;
            float lineH = Math.max(0.004f, prev.box.height());
            float dy = cur.box.top - prev.box.top;
            // مقطع آخر على نفس الارتفاع (خلية جدول/عمود مجاور): وقفة بدل وصل الجملتين ببعض
            if (Math.abs(dy) < lineH * 0.35f) {
                flags[i] = true;
                continue;
            }
            float gap = median > 0 ? Math.max(median * 1.7f, lineH * 1.6f) : lineH * 2.1f;
            if (dy > gap || dy < -lineH * 2.4f) flags[i] = true;
        }
        return flags;
    }

    // مقاطع أطول = فجوات أقل بين الجمل ونبرة أكثر سلاسة (الجمل القصيرة تُدمج مع التي تليها)
    private static final int MAX_CHUNK_CHARS = 420;
    private static final int MIN_SENTENCE_CHARS = 90;

    private static List<Chunk> makeChunks(List<Word> words, String text, boolean[] para, String latin) {
        List<Chunk> out = new ArrayList<>();
        int n = words.size();
        int first = 0;
        int i = 0;
        while (i < n) {
            Word w = words.get(i);
            int len = w.end - words.get(first).start;
            boolean last = i == n - 1;
            boolean nextPara = !last && para[i + 1];
            boolean sentenceEnd = endsSentence(w.text) && len >= MIN_SENTENCE_CHARS
                    && (last || !startsLowerLatin(words.get(i + 1).text) || nextPara)
                    && !isAbbreviation(w.text);
            boolean tooLong = len >= MAX_CHUNK_CHARS;
            // عند تجاوز الحد نفضّل الكسر بعد فاصلة قريبة
            if (tooLong && !sentenceEnd) {
                int cut = i;
                for (int k = i; k > first && words.get(i).end - words.get(k).start < 140; k--) {
                    String t = words.get(k).text;
                    if (t.endsWith(",") || t.endsWith("،") || t.endsWith(";") || t.endsWith("؛") || t.endsWith(":")) {
                        cut = k;
                        break;
                    }
                }
                emit(out, words, text, first, cut, latin);
                first = cut + 1;
                i = cut + 1;
                continue;
            }
            if (last || nextPara || sentenceEnd) {
                emit(out, words, text, first, i, latin);
                first = i + 1;
            }
            i++;
        }
        return out;
    }

    /**
     * يدمج الجمل المتتالية بنفس اللغة في مقطع واحد كبير (الصفحة كلها في الغالب) بدل تجهيز صوت كل جملة
     * أو فقرة على حدة: صوت واحد متصل بنبرة ثابتة وبلا فجوات انتظار بين الجمل. الحد الأقصى يحفظ الطلب
     * ضمن سعة الصوت العصبي (SSML ~4KB) ومحرك الجهاز (~4000 حرف). تظليل الجملة الحالية يتم بحسب
     * Word.sent فلا يتأثر بحجم المقطع. مقطع قصير بلغة مختلفة (عنوان/مصطلح لاتيني) يُدمج بالمجاور.
     */
    private static final int MERGE_MAX_CHARS = 1400;
    private static final int SHORT_LANG_SWITCH_CHARS = 40;

    private static List<Chunk> mergeChunks(List<Chunk> in, String text) {
        if (in.size() < 2) return in;
        List<Chunk> out = new ArrayList<>();
        Chunk cur = in.get(0);
        for (int i = 1; i < in.size(); i++) {
            Chunk nx = in.get(i);
            int curLen = cur.end - cur.start;
            int nxLen = nx.end - nx.start;
            boolean sameLang = cur.lang.equals(nx.lang);
            boolean fits = nx.end - cur.start <= MERGE_MAX_CHARS;
            boolean shortSwitch = !sameLang && Math.min(curLen, nxLen) < SHORT_LANG_SWITCH_CHARS;
            if (fits && (sameLang || shortSwitch)) {
                String lang = sameLang || curLen >= nxLen ? cur.lang : nx.lang;
                cur = new Chunk(cur.firstWord, nx.lastWord, cur.start, nx.end, lang);
            } else {
                out.add(cur);
                cur = nx;
            }
        }
        out.add(cur);
        return out;
    }

    private static boolean endsWithPunct(String w) {
        if (w == null || w.isEmpty()) return true;
        char c = w.charAt(w.length() - 1);
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ':' || c == ';' || c == '\u060C'
                || c == '\u061B' || c == '\u061F' || c == '\u2026' || c == ')' || c == '"' || c == '\u201D'
                || c == '\u00BB' || c == ']';
    }

    private static void emit(List<Chunk> out, List<Word> words, String text, int first, int last, String latin) {
        if (first > last || first >= words.size()) return;
        int s = words.get(first).start;
        int e = words.get(last).end;
        String piece = text.substring(s, e);
        out.add(new Chunk(first, last, s, e, detectLang(piece, latin)));
    }

    private static boolean endsSentence(String w) {
        if (w.isEmpty()) return false;
        char c = w.charAt(w.length() - 1);
        if (c == ')' || c == '"' || c == '\u201D' || c == '\u00BB' || c == ']') {
            if (w.length() < 2) return false;
            c = w.charAt(w.length() - 2);
        }
        return c == '.' || c == '!' || c == '?' || c == '\u061F' || c == '\u061B' || c == '\u2026' || c == '\u3002';
    }

    private static final String[] ABBREVIATIONS = {
            "dr.", "mr.", "mrs.", "ms.", "prof.", "fig.", "figs.", "vs.", "et", "al.", "e.g.", "i.e.",
            "no.", "eq.", "ref.", "approx.", "etc.", "cf.", "vol.", "pp.", "p.", "st.", "min.", "sec.",
            "د.", "أ.", "م."
    };

    private static boolean isAbbreviation(String w) {
        String l = w.toLowerCase(Locale.ROOT);
        for (String a : ABBREVIATIONS) {
            if (l.equals(a)) return true;
        }
        // حرف واحد + نقطة (اختصار اسم)
        return l.length() == 2 && l.charAt(1) == '.' && Character.isLetter(l.charAt(0));
    }

    private static boolean startsLowerLatin(String w) {
        return !w.isEmpty() && Character.isLowerCase(w.charAt(0)) && isLatin(w);
    }

    // ------------------------------------------------------------------ اكتشاف اللغة

    private static boolean isArabicChar(char c) {
        return (c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF);
    }

    private static boolean isLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c) && isArabicChar(c)) return false;
        }
        return true;
    }

    private static int countLatinLetters(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c) && !isArabicChar(c)) n++;
        }
        return n;
    }

    /** لغة مقطع واحد: عربي لو حروفه العربية أكثر من اللاتينية، وإلا اللغة اللاتينية السائدة بالملف. */
    static String detectLang(String s, String latinDefault) {
        int ar = 0, lat = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetter(c)) continue;
            if (isArabicChar(c)) ar++;
            else lat++;
        }
        if (ar == 0 && lat == 0) return latinDefault != null ? latinDefault : "en";
        return ar > lat ? "ar" : (latinDefault != null ? latinDefault : "en");
    }

    /** إنجليزي / فرنسي / تركي بناءً على علامات مميّزة وكلمات شائعة. */
    static String detectLatinLang(String s) {
        String l = " " + s.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\n]", " ") + " ";
        int letters = Math.max(1, countLatinLetters(s));
        int tr = 0, fr = 0, en = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("ğĞşŞıİ".indexOf(c) >= 0) tr += 3;
            if ("éèêàçùûôîëïœÉÈÊÀÇ".indexOf(c) >= 0) fr++;
        }
        String[] enWords = {" the ", " and ", " of ", " to ", " is ", " in ", " that ", " with ", " for ", " are "};
        String[] frWords = {" le ", " la ", " les ", " des ", " est ", " une ", " et ", " du ", " pour ", " dans ", " que "};
        String[] trWords = {" ve ", " bir ", " bu ", " için ", " ile ", " olan ", " da ", " de "};
        for (String w : enWords) en += count(l, w);
        for (String w : frWords) fr += count(l, w) * 2;
        for (String w : trWords) tr += count(l, w) * 2;
        if (tr > en && tr > fr && tr * 400 > letters) return "tr";
        if (fr > en && fr * 400 > letters) return "fr";
        return "en";
    }

    private static int count(String hay, String needle) {
        int c = 0, idx = 0;
        while ((idx = hay.indexOf(needle, idx)) >= 0) {
            c++;
            idx += needle.length() - 1;
        }
        return c;
    }
}
