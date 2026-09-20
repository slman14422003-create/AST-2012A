package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * مخطط خطي بسيط لتطور شدة الألم (0-10) عبر الجلسات: خط "قبل الجلسة" وخط "بعد
 * الجلسة". يتبع اتجاه الواجهة: في RTL تكون أقدم جلسة على اليمين وأحدثها على
 * اليسار، والأرقام على يمين المحور.
 */
public class PainTrendView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beforeLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint afterLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beforeDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint afterDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    /** قيم الألم لكل جلسة؛ -1 = غير متوفرة */
    private float[] before = new float[0];
    private float[] after = new float[0];

    public PainTrendView(Context context) {
        super(context);
        init();
    }

    public PainTrendView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PainTrendView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        int red = getContext().getColor(R.color.accent_red);
        int green = getContext().getColor(R.color.accent_green);

        gridPaint.setColor(getContext().getColor(R.color.glass_border_soft));
        gridPaint.setStrokeWidth(density);
        gridPaint.setStyle(Paint.Style.STROKE);

        textPaint.setColor(getContext().getColor(R.color.text_tertiary));
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f,
                getResources().getDisplayMetrics()));

        beforeLine.setColor(red);
        beforeLine.setStyle(Paint.Style.STROKE);
        beforeLine.setStrokeWidth(2.2f * density);
        beforeLine.setStrokeCap(Paint.Cap.ROUND);
        beforeLine.setStrokeJoin(Paint.Join.ROUND);

        afterLine.setColor(green);
        afterLine.setStyle(Paint.Style.STROKE);
        afterLine.setStrokeWidth(2.2f * density);
        afterLine.setStrokeCap(Paint.Cap.ROUND);
        afterLine.setStrokeJoin(Paint.Join.ROUND);

        beforeDot.setColor(red);
        beforeDot.setStyle(Paint.Style.FILL);
        afterDot.setColor(green);
        afterDot.setStyle(Paint.Style.FILL);
    }

    /** يمرّر القيم بترتيب زمني (الأقدم أولًا). */
    public void setData(float[] beforeValues, float[] afterValues) {
        this.before = beforeValues == null ? new float[0] : beforeValues.clone();
        this.after = afterValues == null ? new float[0] : afterValues.clone();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        float labelWidth = 22f * density;
        float pad = 6f * density;
        float plotLeft = rtl ? pad : labelWidth;
        float plotRight = rtl ? getWidth() - labelWidth : getWidth() - pad;
        float plotTop = 10f * density;
        float plotBottom = getHeight() - 10f * density;
        if (plotRight <= plotLeft || plotBottom <= plotTop) return;

        // خطوط الشبكة والأرقام: 0 و5 و10
        for (int v = 0; v <= 10; v += 5) {
            float y = plotBottom - (plotBottom - plotTop) * v / 10f;
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint);
            float tx = rtl ? plotRight + 6f * density : 2f * density;
            canvas.drawText(String.valueOf(v), tx, y + textPaint.getTextSize() / 3f, textPaint);
        }

        int n = Math.min(before.length, after.length);
        if (n == 0) return;

        float inset = 10f * density;
        float usable = (plotRight - plotLeft) - 2f * inset;
        float step = n > 1 ? usable / (n - 1) : 0f;

        drawSeries(canvas, before, n, plotLeft, plotRight, plotTop, plotBottom, inset, step, rtl,
                beforeLine, beforeDot, density);
        drawSeries(canvas, after, n, plotLeft, plotRight, plotTop, plotBottom, inset, step, rtl,
                afterLine, afterDot, density);
    }

    private void drawSeries(Canvas canvas, float[] values, int n, float plotLeft, float plotRight,
                            float plotTop, float plotBottom, float inset, float step, boolean rtl,
                            Paint line, Paint dot, float density) {
        path.reset();
        boolean started = false;
        for (int i = 0; i < n; i++) {
            float value = values[i];
            if (value < 0) {
                started = false;
                continue;
            }
            float x = xAt(i, n, plotLeft, plotRight, inset, step, rtl);
            float y = plotBottom - (plotBottom - plotTop) * Math.min(10f, value) / 10f;
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, line);
        for (int i = 0; i < n; i++) {
            float value = values[i];
            if (value < 0) continue;
            float x = xAt(i, n, plotLeft, plotRight, inset, step, rtl);
            float y = plotBottom - (plotBottom - plotTop) * Math.min(10f, value) / 10f;
            canvas.drawCircle(x, y, 3.6f * density, dot);
        }
    }

    private float xAt(int i, int n, float plotLeft, float plotRight, float inset, float step, boolean rtl) {
        if (n == 1) return (plotLeft + plotRight) / 2f;
        return rtl ? plotRight - inset - i * step : plotLeft + inset + i * step;
    }
}
