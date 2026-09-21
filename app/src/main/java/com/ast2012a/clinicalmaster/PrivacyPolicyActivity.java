package com.ast2012a.clinicalmaster;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

/**
 * شاشة سياسة الخصوصية. النص وصفي وصادق بحسب سلوك التطبيق الفعلي فقط
 * (لا يوجد سيرفر خاص بالتطبيق، كل شيء محلي على الجهاز، والاستثناء
 * الوحيد هو استدعاءات شبكة مباشرة من جهاز المستخدم نفسه لرابط Cloudflare
 * Worker الثابت الخاص بـ Phizyo AI أو لـ Physiopedia حصرًا عند
 * استخدام المساعد الذكي).
 *
 * النص نفسه صار داخل res/layout/activity_privacy_policy.xml (أقسام في
 * بطاقات بأيقونات)، فلا يبقى هنا سوى شريط الرجوع.
 */
public class PrivacyPolicyActivity extends AppCompatActivity {

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_policy);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }
}
