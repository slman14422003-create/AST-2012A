package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * طبقة شفافة فوق صورة صفحة الـ PDF تظلّل الجملة المقروءة حاليًا (لون خفيف)
 * والكلمة المنطوقة الآن (لون أوضح). كل الإحداثيات نسب (0..1) من أبعاد الصفحة،
 * فتبقى صحيحة مهما كان التكبير أو حجم الشاشة.
 */
public class PdfHighlightView extends View {

    private final Paint sentencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wordPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<RectF> sentenceRects = new ArrayList<>();
    private RectF wordRect = null;
    private final RectF tmp = new RectF();

    public PdfHighlightView(Context context) {
        this(context, null);
    }

    public PdfHighlightView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        sentencePaint.setStyle(Paint.Style.FILL);
        sentencePaint.setColor(0x33C96442); // برتقالي هوية التطبيق بشفافية خفيفة
        wordPaint.setStyle(Paint.Style.FILL);
        wordPaint.setColor(0x80E8A317);     // كهرماني أوضح للكلمة الحالية
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** يضبط تظليل الجملة (قائمة مستطيلات، مستطيل لكل سطر) وتظليل الكلمة الحالية (قد يكون null). */
    public void setHighlight(@Nullable List<RectF> sentence, @Nullable RectF word) {
        sentenceRects.clear();
        if (sentence != null) sentenceRects.addAll(sentence);
        wordRect = word;
        invalidate();
    }

    public void clearHighlight() {
        if (sentenceRects.isEmpty() && wordRect == null) return;
        sentenceRects.clear();
        wordRect = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float pad = Math.max(1.5f, w * 0.002f);
        for (RectF r : sentenceRects) {
            tmp.set(r.left * w - pad, r.top * h - pad, r.right * w + pad, r.bottom * h + pad);
            canvas.drawRoundRect(tmp, 4f, 4f, sentencePaint);
        }
        if (wordRect != null) {
            tmp.set(wordRect.left * w - pad, wordRect.top * h - pad, wordRect.right * w + pad, wordRect.bottom * h + pad);
            canvas.drawRoundRect(tmp, 5f, 5f, wordPaint);
        }
    }
}
