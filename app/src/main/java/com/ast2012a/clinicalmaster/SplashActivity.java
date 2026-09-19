package com.ast2012a.clinicalmaster;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.appcompat.app.AppCompatActivity;

/**
 * شاشة splash متقدمة: العلامة ("نبضة" بيضاء) بتدخل بحركة تكبير + تلاشي مع
 * ارتداد خفيف (Overshoot)، وبعدها نبضة مستمرة هادئة على نفس العلامة
 * (إحساس "قلب بينبض" بدل ما تفضل جامدة ثابتة) - نفس روح شعار جهاز
 * التحفيز الكهربائي بس بحركة حقيقية. خلفية برتقالية مصمتة بنفس لون Claude
 * تمامًا (claude_orange)، ثابتة بغض النظر عن الوضع الليلي/النهاري
 * (Theme.ClinicalMaster.Splash في themes.xml).
 *
 * بعد مهلة قصيرة، تنتقل تلقائيًا لـ MainActivity بتلاشي ناعم ولا ترجع لها
 * أي ضغطة رجوع (finish() فورًا بعد بدء الشاشة الرئيسية).
 */
public class SplashActivity extends AppCompatActivity {

    private static final long AUTO_NAVIGATE_DELAY_MS = 1700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable navigateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View logo = findViewById(R.id.splash_logo);
        View title = findViewById(R.id.splash_title);
        View subtitle = findViewById(R.id.splash_subtitle);

        logo.setScaleX(0.5f);
        logo.setScaleY(0.5f);

        ObjectAnimator logoAlpha = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f);
        ObjectAnimator logoScaleX = ObjectAnimator.ofFloat(logo, "scaleX", 0.5f, 1f);
        ObjectAnimator logoScaleY = ObjectAnimator.ofFloat(logo, "scaleY", 0.5f, 1f);
        logoScaleX.setInterpolator(new OvershootInterpolator(2.4f));
        logoScaleY.setInterpolator(new OvershootInterpolator(2.4f));

        AnimatorSet entrance = new AnimatorSet();
        entrance.playTogether(logoAlpha, logoScaleX, logoScaleY);
        entrance.setDuration(620);
        entrance.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                startHeartbeatLoop(logo);
            }
        });
        entrance.start();

        ObjectAnimator titleAlpha = ObjectAnimator.ofFloat(title, "alpha", 0f, 1f);
        titleAlpha.setDuration(420);
        titleAlpha.setStartDelay(280);
        titleAlpha.start();

        ObjectAnimator subtitleAlpha = ObjectAnimator.ofFloat(subtitle, "alpha", 0f, 1f);
        subtitleAlpha.setDuration(420);
        subtitleAlpha.setStartDelay(380);
        subtitleAlpha.start();

        startDotPulse(findViewById(R.id.splash_dot_1), 0);
        startDotPulse(findViewById(R.id.splash_dot_2), 150);
        startDotPulse(findViewById(R.id.splash_dot_3), 300);

        navigateRunnable = this::goToMain;
        handler.postDelayed(navigateRunnable, AUTO_NAVIGATE_DELAY_MS);
    }

    /** نبضة مستمرة هادئة على الشعار بعد ما تخلص حركة الدخول - تكبير بسيط
     *  ورجوع بشكل متكرر، إحساس "قلب بينبض" حقيقي بدل علامة جامدة ثابتة. */
    private void startHeartbeatLoop(View logo) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(logo, "scaleX", 1f, 1.09f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(logo, "scaleY", 1f, 1.09f, 1f);
        scaleX.setDuration(900);
        scaleY.setDuration(900);
        scaleX.setRepeatCount(ObjectAnimator.INFINITE);
        scaleY.setRepeatCount(ObjectAnimator.INFINITE);
        scaleX.start();
        scaleY.start();
    }

    /** نفس نمط نبضة نقاط الكتابة المستخدم في شاشة المحادثة (AiAssistantActivity)
     *  للاتساق البصري بين شاشات التطبيق. */
    private void startDotPulse(View dot, long startDelay) {
        if (dot == null) return;
        ObjectAnimator anim = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f);
        anim.setDuration(500);
        anim.setStartDelay(startDelay);
        anim.setRepeatMode(ObjectAnimator.REVERSE);
        anim.setRepeatCount(ObjectAnimator.INFINITE);
        anim.start();
    }

    private void goToMain() {
        if (isFinishing()) return;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(R.anim.splash_fade_in, R.anim.splash_fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (navigateRunnable != null) handler.removeCallbacks(navigateRunnable);
    }
}
