package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** أدوات واجهة صغيرة مشتركة بين الشاشات المعاد تصميمها. */
public final class Ui {

    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // =====================================================================
    // تحسين الانميشن العام: تفاعل لمسي (press feedback) موحّد لأي عنصر قابل
    // للضغط في التطبيق - نجمة صغيرة، بطاقة حالة، صف مريض، كبسولة تنقّل...
    // بدل ما كانت أغلب العناصر تعتمد فقط على الخلفية (ripple/selector) بدون
    // أي حركة فعلية، فبيحس المستخدم إن الضغط "خفيف" أو غير متفاعل. الحل:
    // تصغير بسيط جدًا (0.94) عند اللمس وارجاع الحجم بانيميشن نابض عند
    // الرفع - يشتغل جنبًا إلى جنب مع أي OnClickListener موجود أصلًا
    // (ما بيستبدلوش، مجرد إضافة حركة بصرية) بدون ما يأثر على منطق الضغط.
    // =====================================================================

    private static final float PRESS_SCALE = 0.94f;
    private static final long PRESS_DOWN_DURATION = 90;
    private static final long PRESS_UP_DURATION = 220;

    /** يضيف حركة "ضغط" بصرية خفيفة (تصغير/تكبير) لأي View قابل للنقر، من
     *  غير ما يمسّ أي OnClickListener مُركّب عليه سواء قبل أو بعد النداء. */
    public static void applyPressFeedback(final View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate()
                            .scaleX(PRESS_SCALE)
                            .scaleY(PRESS_SCALE)
                            .setDuration(PRESS_DOWN_DURATION)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(PRESS_UP_DURATION)
                            .setInterpolator(new OvershootInterpolator(3.5f))
                            .start();
                    break;
                default:
                    break;
            }
            // نرجّع false دايمًا عشان الحدث يكمّل مساره الطبيعي لأي
            // OnClickListener/OnLongClickListener مركّب على نفس الـ View.
            return false;
        });
    }

    /** نبضة صغيرة (pop) تُستخدم عند تبديل حالة (زي تفعيل نجمة المفضلة)
     *  لإبراز التغيير بصريًا بدل ما يتغير الأيقونة فجأة بلا أي حركة. */
    public static void popAnimation(final View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(0.6f);
        view.setScaleY(0.6f);
        view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280)
                .setInterpolator(new OvershootInterpolator(5f))
                .start();
    }
}
