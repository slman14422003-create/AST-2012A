package com.ast2012a.clinicalmaster;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * يبني ملف PDF لناتج "ترجمة الملف بالكامل" - بمراعاة طلب صريح من المستخدم:
 * الحفاظ على تصميم/هيكل كل صفحة أصلية بدل استبدالها بصفحة نص عادي فاضية.
 *
 * النسخة القديمة كانت بتتجاهل شكل الصفحة الأصلي تمامًا (خلفية، صور، ألوان،
 * تخطيط) وبتبني صفحة نص خام بديلة بالكامل. التعديل هنا:
 *  1) كل صفحة ناتجة بترسم فوقها *صورة الصفحة الأصلية بالكامل* كخلفية (نفس
 *     الصورة اللي PdfRenderer بيعرضها في شاشة القراءة العادية) - فالتصميم
 *     والصور والألوان والتخطيط الأصلي بيفضلوا زي ما هم تمامًا.
 *  2) لو فشل استخراج/ترجمة نص صفحة معيّنة (translatedText فاضي/null) -
 *     الصفحة الناتجة بتبقى نفس صورة الصفحة الأصلية *بدون أي إضافة* - يعني
 *     "متل ما هي" فعليًا، مش نسخة معدَّلة أو ملاحظة بديلة.
 *  3) لو نجحت الترجمة - النص المترجم بيتحط في لوحة نصف شفافة فوق نفس صورة
 *     الصفحة الأصلية (نفس التصميم في الخلفية، بدل ما يتشال ويتستبدل بصفحة
 *     فاضية).
 *
 * ملاحظة مهمة وصادقة عن حدود الحل: استبدال حروف النص الأجنبي *بالحرف* في
 * نفس بيكسلات مكانه بالظبط (مسح الأصلي وكتابة العربي مكانه تمامًا) يحتاج
 * مكتبة تحرير PDF على مستوى عناصر النص نفسها (مش متوفرة هنا) - فالتقريب
 * العملي المتاح هو overlay فوق نفس خلفية التصميم الأصلي، مش استبدال حرفي
 * لمكان النص. لو النص المترجم طويل ومش هيتسع في اللوحة فوق نفس الصفحة،
 * بيكمل تلقائيًا على صفحة/صفحات إضافية "تابع" بعد صفحة الأصل مباشرة - بلا
 * أي قصّ أو فقدان لأي جزء من الترجمة.
 *
 * تصميم تدفقي (streaming) مقصود: الكلاس ده بيتفتح مرة واحدة وبتتضاف الصفحات
 * وحدة وحدة عبر addPage() بدل ما يستقبل List فيها كل صور خلفيات الصفحات
 * محمّلة في الذاكرة مرة واحدة - ملف من 100+ صفحة زي كتب العلاج الطبيعي
 * الشائعة كان ممكن يستهلك مئات الميجابايت لو اتجمّعوا كلهم قبل الكتابة،
 * ويعطّل التطبيق (OutOfMemoryError) قبل ما توصل لمرحلة البناء أصلًا. هنا كل
 * صورة خلفية بتتحمّل وترسم وتتحرّر (recycle) فورًا بعد ما تخلص صفحتها -
 * صورة وحدة بس في الذاكرة في أي لحظة، بغض النظر عن عدد صفحات الملف.
 */
final class TranslatedPdfBuilder implements AutoCloseable {

    private static final int FALLBACK_WIDTH = 595;   // A4 عند 72dpi - يُستخدم فقط لو تعذّر قراءة أبعاد الصفحة الأصلية
    private static final int FALLBACK_HEIGHT = 842;
    private static final int MARGIN = 28;
    /** أقصى ارتفاع للوحة الترجمة فوق نفس صورة الصفحة الأصلية (نسبة من ارتفاع الصفحة) -
     *  باقي الصفحة (فوق اللوحة) يفضل يعرض تصميم الصفحة الأصلية زي ما هو. */
    private static final float PANEL_MAX_HEIGHT_FRACTION = 0.60f;
    private static final int PANEL_BG_COLOR = Color.argb(240, 255, 255, 255);
    private static final int PANEL_BORDER_COLOR = Color.rgb(15, 110, 130);

    private final PdfDocument doc = new PdfDocument();
    private final boolean rtl;
    private final TextPaint labelPaint = paint(11f, true, PANEL_BORDER_COLOR);
    private final TextPaint bodyPaint = paint(12.5f, false, Color.rgb(30, 30, 30));

    /** يفتح البناء ويرسم صفحة غلاف فورًا (عنوان + بيانات الترجمة). */
    TranslatedPdfBuilder(String sourceTitle, String targetLangLabel, int totalPages, boolean rtl) {
        this.rtl = rtl;
        drawCoverPage(sourceTitle, targetLangLabel, totalPages);
    }

    /**
     * يضيف صفحة واحدة للملف الناتج ويحرّر صورة الخلفية فورًا بعد رسمها -
     * لازم يُستدعى بترتيب أرقام الصفحات. background ممكن يكون null (تعذّر
     * رسم الصفحة الأصلية)، وtranslatedText ممكن يكون null/فاضي (فشلت
     * الترجمة لهذه الصفحة تحديدًا - هتفضل الخلفية زي ما هي بدون أي إضافة).
     */
    void addPage(int pageNumber, Bitmap background, float pageWidthPt, float pageHeightPt, String translatedText) {
        int pageW = pageWidthPt > 0 ? Math.round(pageWidthPt) : FALLBACK_WIDTH;
        int pageH = pageHeightPt > 0 ? Math.round(pageHeightPt) : FALLBACK_HEIGHT;

        PdfDocument.Page page = startPage(pageW, pageH);
        Canvas canvas = page.getCanvas();
        canvas.drawColor(Color.WHITE);
        if (background != null) {
            canvas.drawBitmap(background, null, new RectF(0, 0, pageW, pageH), new Paint(Paint.FILTER_BITMAP_FLAG));
        }

        boolean hasTranslation = translatedText != null && !translatedText.trim().isEmpty();
        String overflowText = null;
        if (hasTranslation) {
            overflowText = drawTranslationPanel(canvas, translatedText.trim(), pageW, pageH);
        }
        doc.finishPage(page);
        // بعد finishPage() محتوى الصفحة (بما فيها صورة الخلفية) بقى محفوظ
        // جوه بنية الملف الناتج نفسها - الصورة الخام مش محتاجة تفضل في
        // الذاكرة بعد كده، فبنحرّرها فورًا بدل ما تتراكم مع كل صفحة جديدة.
        if (background != null && !background.isRecycled()) {
            background.recycle();
        }

        if (overflowText != null && !overflowText.isEmpty()) {
            drawContinuationPages(pageNumber, overflowText, pageW, pageH);
        }
    }

    /** يكتب الملف النهائي إلى outFile - يُستدعى مرة واحدة بعد إضافة كل الصفحات. */
    void writeTo(File outFile) throws IOException {
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            doc.writeTo(out);
        }
    }

    @Override
    public void close() {
        doc.close();
    }

    private PdfDocument.Page startPage(int w, int h) {
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(Math.max(1, w), Math.max(1, h), 1).create();
        return doc.startPage(info);
    }

    /** يرسم لوحة الترجمة فوق خلفية الصفحة الحالية. يرجّع أي نص فائض لم
     *  يتّسع في اللوحة (لصفحات "تابع")، أو null لو اتسع النص بالكامل. */
    private String drawTranslationPanel(Canvas canvas, String body, int pageW, int pageH) {
        float panelMaxHeight = pageH * PANEL_MAX_HEIGHT_FRACTION;
        float panelTop = pageH - MARGIN - panelMaxHeight;
        float panelLeft = MARGIN;
        float panelRight = pageW - MARGIN;
        float panelBottom = pageH - MARGIN;

        StaticLayout layout = StaticLayout.Builder
                .obtain(body, 0, body.length(), bodyPaint, (int) (panelRight - panelLeft - 20))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(rtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR)
                .setLineSpacing(3f, 1f)
                .setIncludePad(false)
                .build();

        Paint panelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        panelBg.setColor(PANEL_BG_COLOR);
        canvas.drawRoundRect(new RectF(panelLeft, panelTop, panelRight, panelBottom), 10f, 10f, panelBg);
        Paint panelBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        panelBorder.setStyle(Paint.Style.STROKE);
        panelBorder.setStrokeWidth(1.4f);
        panelBorder.setColor(PANEL_BORDER_COLOR);
        canvas.drawRoundRect(new RectF(panelLeft, panelTop, panelRight, panelBottom), 10f, 10f, panelBorder);

        float textLeft = panelLeft + 10;
        float y = panelTop + 8;
        drawLabel(canvas, "ترجمة", labelPaint, panelLeft + 10, panelRight - 10, y + 10, rtl);
        y += 18;

        int lineCount = layout.getLineCount();
        int cutLineIndex = lineCount;
        for (int i = 0; i < lineCount; i++) {
            int lineTop = layout.getLineTop(i);
            int lineBottom = layout.getLineBottom(i);
            if (y + (lineBottom - lineTop) > panelBottom - 6) {
                cutLineIndex = i;
                break;
            }
            canvas.save();
            canvas.translate(textLeft, y - lineTop);
            canvas.clipRect(0, lineTop, (int) (panelRight - panelLeft - 20), lineBottom);
            layout.draw(canvas);
            canvas.restore();
            y += (lineBottom - lineTop);
        }

        if (cutLineIndex < lineCount) {
            int overflowStart = layout.getLineStart(cutLineIndex);
            return body.substring(overflowStart).trim();
        }
        return null;
    }

    /** صفحات "تابع" لأي فائض نص لم يتّسع في لوحة الصفحة الأصلية - نص عادي
     *  على خلفية بيضاء (بلا صورة خلفية، لأنها ليست جزءًا من الصفحة الأصلية)،
     *  بعنوان صغير يوضّح إنها استكمال لأي صفحة. بلا حد أقصى لعدد الصفحات. */
    private void drawContinuationPages(int sourcePageNumber, String text, int pageW, int pageH) {
        int contentWidth = pageW - 2 * MARGIN;
        PdfDocument.Page page = startPage(pageW, pageH);
        Canvas canvas = page.getCanvas();
        canvas.drawColor(Color.WHITE);
        float y = MARGIN;
        drawLabel(canvas, "تابع ترجمة الصفحة " + sourcePageNumber, labelPaint, MARGIN, pageW - MARGIN, y + 10, rtl);
        y += 22;

        StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), bodyPaint, contentWidth)
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
            if (y + lineHeight > pageH - MARGIN) {
                doc.finishPage(page);
                page = startPage(pageW, pageH);
                canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);
                y = MARGIN;
                drawLabel(canvas, "تابع ترجمة الصفحة " + sourcePageNumber + " (تكملة)", labelPaint,
                        MARGIN, pageW - MARGIN, y + 10, rtl);
                y += 22;
            }
            canvas.save();
            canvas.translate(MARGIN, y - lineTop);
            canvas.clipRect(0, lineTop, contentWidth, lineBottom);
            layout.draw(canvas);
            canvas.restore();
            y += lineHeight;
        }
        doc.finishPage(page);
    }

    private void drawCoverPage(String sourceTitle, String targetLangLabel, int pageCount) {
        PdfDocument.Page page = startPage(FALLBACK_WIDTH, FALLBACK_HEIGHT);
        Canvas canvas = page.getCanvas();
        canvas.drawColor(Color.WHITE);

        TextPaint titlePaint = paint(20f, true, Color.BLACK);
        TextPaint metaPaint = paint(11.5f, false, Color.rgb(110, 110, 110));

        String title = sourceTitle == null || sourceTitle.trim().isEmpty() ? "ترجمة ملف PDF" : sourceTitle.trim();
        String meta = String.format(Locale.US, "ترجمة تلقائية إلى %s · %d صفحة - كل صفحة تحافظ على تصميمها الأصلي، والترجمة تظهر فوقه",
                targetLangLabel, pageCount);

        float y = MARGIN + 20;
        y = drawWrapped(canvas, title, titlePaint, MARGIN, y, FALLBACK_WIDTH - 2 * MARGIN) + 10;
        drawWrapped(canvas, meta, metaPaint, MARGIN, y, FALLBACK_WIDTH - 2 * MARGIN);

        doc.finishPage(page);
    }

    private float drawWrapped(Canvas canvas, String text, TextPaint paint, float left, float top, int width) {
        StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(rtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR)
                .setLineSpacing(4f, 1f)
                .setIncludePad(false)
                .build();
        canvas.save();
        canvas.translate(left, top);
        layout.draw(canvas);
        canvas.restore();
        return top + layout.getHeight();
    }

    /** يرسم سطر تسمية قصير (زي "ترجمة") محاذى لبداية النص حسب اتجاه اللغة
     *  الهدف (يمين لـ RTL، يسار لـ LTR) بدل ما يتثبّت شمال دايمًا. */
    private static void drawLabel(Canvas canvas, String text, TextPaint paint, float left, float right,
                                   float baselineY, boolean rtl) {
        Paint.Align original = paint.getTextAlign();
        if (rtl) {
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(text, right, baselineY, paint);
        } else {
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(text, left, baselineY, paint);
        }
        paint.setTextAlign(original);
    }

    private static TextPaint paint(float sizePt, boolean bold, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(sizePt);
        p.setColor(color);
        p.setFakeBoldText(bold);
        return p;
    }
}
