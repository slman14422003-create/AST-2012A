package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.graphics.RectF;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * يستخرج نص صفحة PDF واحدة مقسّمًا إلى "أسطر" (Line) مع صندوق إحاطة
 * (Bounding Box) بوحدة نقطة PDF لكل سطر - بخلاف DocumentTextExtractor
 * اللي بيرجّع نص الصفحة كتلة واحدة فاضية من أي معلومات عن الموضع.
 *
 * الهدف: تمكين TranslatedPdfBuilder من *مسح* منطقة كل سطر نص أصلي بالضبط
 * ورسم ترجمته في نفس مكانه (استبدال حقيقي في مكانه) بدل تجميع كل نص
 * الصفحة في لوحة منفصلة أسفلها فوق كل شيء.
 *
 * الاعتماد على PDFTextStripper.writeString(...): PdfBox بينادي عليها مرة
 * واحدة لكل سطر مُجمَّع طبيعيًا من مواضع الحروف المتجاورة على نفس السطر
 * (بعد تفعيل setSortByPosition حتى يكون الترتيب مطابقًا لترتيب القراءة
 * البصري) - فمفيش حاجة لتجميع يدوي لحروف/كلمات منفصلة هنا.
 *
 * ملاحظة صادقة عن حدود الحل: صندوق الإحاطة تقريبي (مبني على خط الأساس
 * وارتفاع كل حرف من PdfBox، مش على القياس الحقيقي للحبر المرسوم)، فبيتضاف
 * هامش أمان بسيط حوله عند الاستخدام (TranslatedPdfBuilder) حتى يغطي
 * الأصل بالكامل قبل رسم الترجمة مكانه.
 */
final class PdfLineExtractor {

    private PdfLineExtractor() {
    }

    static final class Line {
        /** بوحدة نقطة PDF، Y من أعلى الصفحة لأسفل - نفس نظام إحداثيات
         *  pagePointSize/TranslatedPdfBuilder، فتُستخدم مباشرة بلا تحويل. */
        final RectF box;
        final String text;

        Line(RectF box, String text) {
            this.box = box;
            this.text = text;
        }
    }

    private static volatile boolean initialized = false;

    private static synchronized void ensureInit(Context ctx) {
        if (!initialized) {
            PDFBoxResourceLoader.init(ctx.getApplicationContext());
            initialized = true;
        }
    }

    /** يرجّع أسطر صفحة واحدة (فهرسها 0-based) بترتيب القراءة، أو قائمة فاضية
     *  لو تعذّر الاستخراج أو كانت الصفحة بلا طبقة نص حقيقية (صورة ممسوحة
     *  ضوئيًا مثلًا) - أي خطأ بيُبتلع هنا عشان فشل صفحة واحدة ما يوقفش باقي
     *  ترجمة الملف. */
    static List<Line> extractLines(Context ctx, File file, int pageIndex) {
        List<Line> lines = new ArrayList<>();
        try {
            ensureInit(ctx);
            try (PDDocument doc = PDDocument.load(file)) {
                if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) return lines;
                LineCollectingStripper stripper = new LineCollectingStripper(lines);
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                stripper.setSortByPosition(true);
                stripper.getText(doc); // النص المرجّع مش محتاجينه؛ المهم الأسطر المجمّعة جانبيًا في writeString
            }
        } catch (Throwable t) {
            return new ArrayList<>();
        }
        return lines;
    }

    private static final class LineCollectingStripper extends PDFTextStripper {
        private final List<Line> out;

        LineCollectingStripper(List<Line> out) throws IOException {
            super();
            this.out = out;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (text == null || text.trim().isEmpty() || textPositions == null || textPositions.isEmpty()) return;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (TextPosition tp : textPositions) {
                float x = tp.getXDirAdj();
                float y = tp.getYDirAdj();
                float w = tp.getWidthDirAdj() > 0 ? tp.getWidthDirAdj() : tp.getWidth();
                float h = tp.getHeightDir() > 0 ? tp.getHeightDir() : tp.getHeight();
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + w);
                // y هنا خط أساس الحرف (baseline) - جسم الحرف يمتد لأعلى تقريبًا
                // بارتفاعه الكامل، ولأسفل بمقدار بسيط (الذيول/التشكيل).
                minY = Math.min(minY, y - h);
                maxY = Math.max(maxY, y + h * 0.25f);
            }
            if (minX == Float.MAX_VALUE) return;
            out.add(new Line(new RectF(minX, minY, maxX, maxY), text));
        }
    }
}
