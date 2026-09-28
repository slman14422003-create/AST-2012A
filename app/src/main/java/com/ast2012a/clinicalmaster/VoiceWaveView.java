package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * فقاعة (كبسولة) بداخلها موجة صوتية انسيابية تتحرك بحسب مستوى الصوت الفعلي أثناء القراءة.
 *
 * الفكرة: نحتفظ بسجلّ قصير لمستوى الصوت الأخير، ونرسم الموجة بحيث يكون أحدث مستوى في
 * منتصف الفقاعة وتنتشر القيم الأقدم نحو الطرفين، فتبدو الموجة وكأنها تخرج من المنتصف مع
 * كل كلمة. ثلاث طبقات بألوان هوية التطبيق (برتقالي طوبي) وسرعات مختلفة تعطي عمقًا.
 * عند الإيقاف المؤقت أو الصمت تهدأ الموجة وتتنفس بلطف.
 */
public class VoiceWaveView extends View {

    /** مصدر مستوى الصوت (0..1) - يُستدعى كل إطار من الخيط الرئيسي. */
    public interface LevelSource {
        float getLevel();
    }

    private static final int HIST = 44;              // عدد عيّنات السجلّ
    private static final float HIST_STEP = 0.030f;   // ثانية بين عيّنتين

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] linePaints = new Paint[]{
            new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG)};
    private final int[] lineColors = new int[3];
    private final Path path = new Path();
    private final RectF rect = new RectF();

    // معاملات الطبقات: تردد (دورات على العرض) / سرعة الطور / سعة نسبية / سماكة (dp) / شفافية
    private static final float[] FREQ = {2.1f, 3.0f, 1.5f};
    private static final float[] SPEED = {1.0f, -1.35f, 0.65f};
    private static final float[] AMP = {1.0f, 0.68f, 0.48f};
    private static final float[] WIDTH_DP = {2.6f, 1.7f, 1.3f};
    private static final int[] ALPHA = {255, 170, 120};

    private final float[] hist = new float[HIST];
    private float[] xs = new float[0];
    private float[] win = new float[0];

    @Nullable
    private LevelSource source;
    private boolean paused = false;
    private boolean running = false;
    private float cur = 0f;
    private float dim = 1f;
    private float phase = 0f;
    private float clock = 0f;
    private float accum = 0f;
    private long lastFrame = 0L;
    private float density = 1f;

    public VoiceWaveView(Context context) {
        this(context, null);
    }

    public VoiceWaveView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        bgPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(density);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < linePaints.length; i++) {
            linePaints[i].setStyle(Paint.Style.STROKE);
            linePaints[i].setStrokeCap(Paint.Cap.ROUND);
            linePaints[i].setStrokeJoin(Paint.Join.ROUND);
            linePaints[i].setStrokeWidth(WIDTH_DP[i] * density);
        }
        glowPaint.setStrokeWidth(7f * density);
        refreshColors();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setLevelSource(@Nullable LevelSource s) {
        this.source = s;
    }

    /** في وضع الإيقاف المؤقت تخفّ الموجة وتهدأ. */
    public void setPaused(boolean p) {
        this.paused = p;
    }

    private void refreshColors() {
        Context c = getContext();
        bgPaint.setColor(c.getColor(R.color.primary_soft));
        int stroke = c.getColor(R.color.primary_cyan);
        borderPaint.setColor((stroke & 0x00FFFFFF) | 0x38000000);
        lineColors[0] = c.getColor(R.color.primary_cyan);
        lineColors[1] = c.getColor(R.color.primary_cyan_light);
        lineColors[2] = c.getColor(R.color.primary_cyan_dark);
        glowPaint.setColor(lineColors[0]);
    }

    // ------------------------------------------------------------------ دورة الحياة

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshColors();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateRunning();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateRunning();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshColors();
    }

    private void updateRunning() {
        boolean should = isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE;
        if (should && !running) {
            running = true;
            lastFrame = 0L;
            postOnAnimation(frame);
        } else if (!should && running) {
            stop();
        }
    }

    private void stop() {
        running = false;
        removeCallbacks(frame);
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            step();
            invalidate();
            postOnAnimation(this);
        }
    };

    // ------------------------------------------------------------------ المحاكاة

    private void step() {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0L ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        clock += dt;

        float target = 0f;
        if (!paused && source != null) {
            try {
                target = Math.max(0f, Math.min(1f, source.getLevel()));
            } catch (Throwable ignored) {
            }
        }
        // صعود سريع (يلتقط بداية الكلمة) وهبوط أبطأ (ينساب بدل أن يرتجف)
        float k = target > cur ? 1f - (float) Math.exp(-dt * 30f) : 1f - (float) Math.exp(-dt * 9f);
        cur += (target - cur) * k;

        float dimTarget = paused ? 0.45f : 1f;
        dim += (dimTarget - dim) * (1f - (float) Math.exp(-dt * 8f));

        phase += dt * (2.2f + 6.5f * cur);

        accum += dt;
        while (accum >= HIST_STEP) {
            accum -= HIST_STEP;
            System.arraycopy(hist, 0, hist, 1, HIST - 1);
            hist[0] = cur;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int n = Math.max(32, Math.round(w / (3f * density)));
        xs = new float[n + 1];
        win = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            float u = i / (float) n;
            xs[i] = u;
            // نافذة تُنهي الموجة بهدوء عند طرفي الكبسولة فلا تخرج عن حدودها
            win[i] = (float) Math.pow(Math.sin(Math.PI * u), 1.3);
        }
    }

    private float histAt(float u) {
        float d = Math.abs(u - 0.5f) * 2f;          // 0 في المنتصف .. 1 عند الطرفين
        float f = d * (HIST - 1);
        int i0 = (int) f;
        int i1 = Math.min(HIST - 1, i0 + 1);
        float fr = f - i0;
        return hist[i0] + (hist[i1] - hist[i0]) * fr;
    }

    // ------------------------------------------------------------------ الرسم

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0 || xs.length == 0) return;

        float half = borderPaint.getStrokeWidth() / 2f;
        rect.set(half, half, w - half, h - half);
        float r = rect.height() / 2f;
        canvas.drawRoundRect(rect, r, r, bgPaint);
        canvas.drawRoundRect(rect, r, r, borderPaint);

        final float cy = h / 2f;
        final float maxAmp = h / 2f - 7f * density;
        final float idle = 0.07f + 0.035f * (float) Math.sin(clock * 2.0f); // تنفّس خفيف عند الهدوء
        final float padX = r * 0.55f;                                          // هامش من كل طرف
        final float span = Math.max(1f, w - 2f * padX);
        final int n = xs.length - 1;

        for (int layer = 2; layer >= 0; layer--) {
            final float freq = FREQ[layer];
            final float ph = phase * SPEED[layer] + layer * 1.7f;
            path.reset();
            for (int i = 0; i <= n; i++) {
                float u = xs[i];
                float a = idle + (1f - idle) * histAt(u);
                float y = cy + maxAmp * AMP[layer] * win[i] * a
                        * (float) Math.sin(u * freq * 2f * (float) Math.PI + ph);
                float x = padX + u * span;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            if (layer == 0) {
                // توهّج ناعم خلف الطبقة الرئيسية
                glowPaint.setColor(lineColors[0]);
                glowPaint.setAlpha((int) (46 * dim));
                canvas.drawPath(path, glowPaint);
            }
            Paint p = linePaints[layer];
            p.setColor(lineColors[layer]);
            p.setAlpha((int) (ALPHA[layer] * dim));
            canvas.drawPath(path, p);
        }
    }
}
