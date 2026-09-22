package com.ast2012a.clinicalmaster;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * يبني ملف PDF منسّق لنتيجة "ترجمة الملف بالكامل" (كل صفحات الأصل مترجمة)
 * بدل نافذة نص عابرة لصفحة واحدة كما في ترجمة الصفحة المفردة: عنوان الملف،
 * بيانات الترجمة (اللغة الهدف والتاريخ)، ثم فقرة مترجمة مرقّمة لكل صفحة أصلية.
 *
 * التنسيق يدعم النصوص الطويلة بلا أي قصّ: كل فقرة تُقاس عبر StaticLayout ثم
 * تُرسم سطرًا سطرًا (Canvas.clipRect لكل سطر على حدة) بدل رسم التخطيط كاملاً
 * دفعة واحدة، فإذا لم يتّسع السطر التالي في الصفحة الحالية تُفتح صفحة PDF
 * جديدة تلقائيًا ويُكمَل الرسم فيها - بلا حد أقصى لعدد الصفحات الناتجة.
 * الاتجاه (RTL/LTR) والمحاذاة يُضبطان حسب لغة الهدف (نفس منطق عرض الترجمة
 * داخل PdfViewerActivity).
 */
final class TranslatedPdfBuilder {

    /** نص صفحة أصلية واحدة بعد الترجمة (أو ملاحظة بديلة إن تعذّر استخراج نصها). */
    static final class PageEntry {
        final int pageNumber;
        final String text;

        PageEntry(int pageNumber, String text) {
            this.pageNumber = pageNumber;
            this.text = text;
        }
    }

    private static final int PAGE_WIDTH = 595;   // A4 عند 72dpi، بنفس وحدة PdfDocument
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 48;
    private static final int CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;

    private TranslatedPdfBuilder() {
    }

    /** يبني الملف الناتج ويكتبه مباشرة إلى outFile. يُنفَّذ فقط من خيط خلفية. */
    static void build(File outFile, String sourceTitle, String targetLangLabel, boolean rtl,
                       List<PageEntry> pages) throws IOException {
        PdfDocument doc = new PdfDocument();
        try {
            Cursor cursor = new Cursor(doc, rtl);
            cursor.newPage();

            TextPaint titlePaint = paint(20f, true, Color.BLACK);
            TextPaint metaPaint = paint(11.5f, false, Color.rgb(110, 110, 110));
            TextPaint sectionPaint = paint(13.5f, true, Color.rgb(15, 110, 130));
            TextPaint bodyPaint = paint(12.5f, false, Color.rgb(35, 35, 35));

            cursor.drawParagraph(sourceTitle == null || sourceTitle.trim().isEmpty()
                    ? "ترجمة ملف PDF" : sourceTitle.trim(), titlePaint, 6f);
            cursor.drawParagraph(String.format(Locale.US, "ترجمة تلقائية إلى %s · %d صفحة",
                    targetLangLabel, pages.size()), metaPaint, 20f);

            for (PageEntry entry : pages) {
                cursor.ensureSpace(32f);
                cursor.drawParagraph("الصفحة " + entry.pageNumber, sectionPaint, 6f);
                String body = entry.text == null || entry.text.trim().isEmpty()
                        ? "(لا يوجد نص قابل للاستخراج في هذه الصفحة، قد تكون صورة ممسوحة ضوئيًا)"
                        : entry.text.trim();
                cursor.drawParagraph(body, bodyPaint, 22f);
            }

            cursor.finish();
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                doc.writeTo(out);
            }
        } finally {
            doc.close();
        }
    }

    private static TextPaint paint(float sizePt, boolean bold, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(sizePt);
        p.setColor(color);
        p.setFakeBoldText(bold);
        return p;
    }

    /** يتتبّع صفحة PdfDocument الحالية وموضع الرسم الرأسي، ويقسّم أي فقرة طويلة
     *  تلقائيًا على عدة صفحات - سطرًا بسطر - بلا حد أقصى للطول. */
    private static final class Cursor {
        private final PdfDocument doc;
        private final boolean rtl;
        private PdfDocument.Page page;
        private Canvas canvas;
        private float y;
        private int pageIndex;

        Cursor(PdfDocument doc, boolean rtl) {
            this.doc = doc;
            this.rtl = rtl;
        }

        void newPage() {
            if (page != null) doc.finishPage(page);
            pageIndex++;
            PdfDocument.PageInfo info =
                    new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex).create();
            page = doc.startPage(info);
            canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);
            y = MARGIN;
        }

        void ensureSpace(float height) {
            if (y + height > PAGE_HEIGHT - MARGIN) newPage();
        }

        void drawParagraph(String text, TextPaint paint, float spacingAfter) {
            StaticLayout layout = StaticLayout.Builder
                    .obtain(text, 0, text.length(), paint, CONTENT_WIDTH)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setTextDirection(rtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR)
                    .setLineSpacing(4f, 1f)
                    .setIncludePad(false)
                    .build();

            int lineCount = layout.getLineCount();
            for (int i = 0; i < lineCount; i++) {
                int lineTop = layout.getLineTop(i);
                int lineBottom = layout.getLineBottom(i);
                float lineHeight = lineBottom - lineTop;
                if (y + lineHeight > PAGE_HEIGHT - MARGIN) newPage();
                canvas.save();
                canvas.translate(MARGIN, y - lineTop);
                canvas.clipRect(0, lineTop, CONTENT_WIDTH, lineBottom);
                layout.draw(canvas);
                canvas.restore();
                y += lineHeight;
            }
            y += spacingAfter;
        }

        void finish() {
            if (page != null) doc.finishPage(page);
        }
    }
}
