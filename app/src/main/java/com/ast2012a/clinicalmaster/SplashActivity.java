package com.ast2012a.clinicalmaster;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.appcompat.app.AppCompatActivity;

/**
 * شاشة splash بأسلوب Claude: نجمة برتقالية + اسم التطبيق في المنتصف، واسم
 * المطوّر (PT Slman) أسفل الشاشة. الحركة هادئة: النجمة تدور وتكبر قليلًا
 * أثناء ظهورها، ثم يظهر الاسم، ثم اسم المطوّر. بعد مهلة قصيرة تنتقل تلقائيًا
 * لـ MainActivity بتلاشي ناعم ولا ترجع لها أي ضغطة رجوع.
 *
 * ألوان الشاشة تتبع الوضع الليلي/النهاري (Theme.ClinicalMaster.Splash).
 */
public class SplashActivity extends AppCompatActivity {

    private static final long AUTO_NAVIGATE_DELAY_MS = 1800;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable navigateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View logo = findViewById(R.id.splash_logo);
        View title = findViewById(R.id.splash_title);
        View credit = findViewById(R.id.splash_credit);

        // النجمة: تدور من -60° إلى 0° مع تكبير 0.8 -> 1 وتلاشي
        logo.setScaleX(0.8f);
        logo.setScaleY(0.8f);
        logo.setRotation(-60f);
        ObjectAnimator logoAlpha = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f);
        ObjectAnimator logoScaleX = ObjectAnimator.ofFloat(logo, "scaleX", 0.8f, 1f);
        ObjectAnimator logoScaleY = ObjectAnimator.ofFloat(logo, "scaleY", 0.8f, 1f);
        ObjectAnimator logoRotate = ObjectAnimator.ofFloat(logo, "rotation", -60f, 0f);
        AnimatorSet entrance = new AnimatorSet();
        entrance.playTogether(logoAlpha, logoScaleX, logoScaleY, logoRotate);
        entrance.setDuration(700);
        entrance.setInterpolator(new DecelerateInterpolator(1.6f));
        entrance.start();

        fadeIn(title, 260, 500);
        fadeIn(credit, 520, 500);

        navigateRunnable = this::goToMain;
        handler.postDelayed(navigateRunnable, AUTO_NAVIGATE_DELAY_MS);
    }

    private void fadeIn(View view, long delay, long duration) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f);
        anim.setStartDelay(delay);
        anim.setDuration(duration);
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
