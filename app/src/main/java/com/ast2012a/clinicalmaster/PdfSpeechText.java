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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * تجهيز نص صفحة PDF للقراءة الصوتية: استخراج كلمة-كلمة (PdfBox) مع موضع كل كلمة
 * كنسبة من أبعاد الصفحة (0..1) حتى يقدر PdfHighlightView يظلّل الكلمة المنطوقة
 * فوق صورة الصفحة بأي تكبير، ثم تقسيم النص إلى مقاطع (جمل) مناسبة للمحرك الصوتي،
 * وتحديد لغة كل مقطع (عربي / إنجليزي / فرنسي / تركي) لاختيار الصوت الأدق.
 *
 * ترتيب القراءة = ترتيب المحتوى داخل الملف (وليس الترتيب حسب الموضع)، لأنه يحافظ
 * على تسلسل الأعمدة والجداول في أغلب الملفات، وهو نفس ما تعتمده قارئات الشاشة.
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

        PageText(int pageIndex, String text, List<Word> words, List<Chunk> chunks) {
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
                WordStripper stripper = new WordStripper();
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                stripper.setSortByPosition(false);
                stripper.getText(doc);
                List<Word> words = stripper.finish();
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

    private static PageText emptyPage(int pageIndex) {
        return new PageText(pageIndex, "", new ArrayList<>(), new ArrayList<>());
    }

    // ------------------------------------------------------------------ الاستخراج

    private static final class RawWord {
        String text;
        RectF box;
        int line;
    }

    private static final class WordStripper extends PDFTextStripper {
        private final List<RawWord> raw = new ArrayList<>();
        private int lineNo = 0;
        private float lastY = Float.NaN;
        private float lastFont = 10f;

        WordStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> tps) {
            if (tps == null || tps.isEmpty() || text == null) return;
            TextPosition first = tps.get(0);
            float pw = first.getPageWidth();
            float ph = first.getPageHeight();
            if (pw <= 0 || ph <= 0) return;

            float y = first.getYDirAdj();
            float font = Math.max(1f, first.getFontSizeInPt());
            if (!Float.isNaN(lastY)) {
                float dy = y - lastY;
                if (Math.abs(dy) > lastFont * 0.55f) {
                    lineNo++;
                }
            }
            lastY = y;
            lastFont = font;

            // حدود كل الأحرف (لصندوق الكلمة أو السطر كله)
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (TextPosition tp : tps) {
                float x = tp.getXDirAdj();
                float w = tp.getWidthDirAdj() > 0 ? tp.getWidthDirAdj() : tp.getWidth();
                float h = tp.getHeightDir() > 0 ? tp.getHeightDir() : tp.getHeight();
                float yy = tp.getYDirAdj();
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + w);
                minY = Math.min(minY, yy - h);
                maxY = Math.max(maxY, yy + h * 0.25f);
            }
            if (minX == Float.MAX_VALUE) return;

            String[] parts = text.trim().split("\\s+");
            if (parts.length == 1) {
                addWord(parts[0], minX, minY, maxX, maxY, pw, ph);
            } else {
                // احتياط: لو رجع PdfBox سطرًا كاملًا بدل كلمة - نوزّع العرض بالتناسب مع عدد الحروف.
                int total = 0;
                for (String p : parts) total += p.length();
                total = Math.max(1, total + parts.length - 1);
                float cursor = minX;
                float span = maxX - minX;
                for (String p : parts) {
                    float wPart = span * (p.length() / (float) total);
                    addWord(p, cursor, minY, cursor + wPart, maxY, pw, ph);
                    cursor += wPart + span * (1f / total);
                }
            }
        }

        private void addWord(String t, float x0, float y0, float x1, float y1, float pw, float ph) {
            String clean = t.replace("\u00AD", "").replace("\u200B", "").trim();
            if (clean.isEmpty()) return;
            boolean hasContent = false;
            for (int i = 0; i < clean.length(); i++) {
                if (Character.isLetterOrDigit(clean.charAt(i))) {
                    hasContent = true;
                    break;
                }
            }
            if (!hasContent) return; // رموز/نقاط تعداد/أيقونات
            RawWord w = new RawWord();
            w.text = clean;
            w.box = new RectF(clamp(x0 / pw), clamp(y0 / ph), clamp(x1 / pw), clamp(y1 / ph));
            w.line = lineNo;
            raw.add(w);
        }

        private static float clamp(float v) {
            return Math.max(0f, Math.min(1f, v));
        }

        List<Word> finish() {
            List<Word> out = new ArrayList<>(raw.size());
            for (RawWord r : raw) out.add(new Word(r.text, r.box, r.line));
            return out;
        }
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
                if (prev.line != w.line && prev.text.endsWith("-") && prev.text.length() > 2
                        && Character.isLowerCase(t.charAt(0)) && isLatin(prev.text)) {
                    glue = true;
                }
            }
            if (i > 0 && !glue) sb.append(paraBefore[i] ? "\n" : " ");
            w.start = sb.length();
            if (i + 1 < words.size()) {
                Word next = words.get(i + 1);
                if (next.line != w.line && t.endsWith("-") && t.length() > 2 && isLatin(t)
                        && Character.isLowerCase(next.text.charAt(0))) {
                    t = t.substring(0, t.length() - 1); // نحذف الشرطة، والكلمة التالية تلتصق بها
                }
            }
            sb.append(t);
            w.end = sb.length();
        }
        String text = sb.toString();

        String latin = src.latinHint;
        if (latin == null) {
            latin = detectLatinLang(text);
            if (countLatinLetters(text) > 200) src.latinHint = latin; // نثبّتها بعد عيّنة كافية
        }

        List<Chunk> chunks = makeChunks(words, text, paraBefore, latin);
        return new PageText(pageIndex, text, words, chunks);
    }

    private static boolean[] inferParagraphs(List<Word> words) {
        boolean[] flags = new boolean[words.size()];
        for (int i = 1; i < words.size(); i++) {
            Word prev = words.get(i - 1);
            Word cur = words.get(i);
            if (cur.line == prev.line) continue;
            float lineH = Math.max(0.004f, prev.box.height());
            float dy = cur.box.top - prev.box.top;
            if (dy > lineH * 2.1f || dy < -lineH * 2.4f) flags[i] = true;
        }
        return flags;
    }

    private static final int MAX_CHUNK_CHARS = 320;
    private static final int MIN_SENTENCE_CHARS = 36;

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
