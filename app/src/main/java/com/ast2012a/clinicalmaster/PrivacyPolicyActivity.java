package com.ast2012a.clinicalmaster;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

/**
 * شاشة سياسة الخصوصية. النص وصفي وصادق بحسب سلوك التطبيق الفعلي فقط
 * (لا يوجد سيرفر خاص بالتطبيق، كل شيء محلي على الجهاز، والاستثناء
 * الوحيد هو استدعاءات شبكة مباشرة من جهاز المستخدم نفسه لرابط Cloudflare
 * Worker الثابت الخاص بـ Phizyo AI أو لـ Physiopedia/ويكيبيديا عند
 * استخدام المساعد الذكي).
 */
public class PrivacyPolicyActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_policy);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView text = findViewById(R.id.privacy_policy_text);
        String body =
                "نظرة عامة\n" +
                "Phizyo Studio تطبيق يعمل بالكامل محليًا على جهازك. لا يوجد " +
                "أي سيرفر خاص بالتطبيق يجمع بياناتك أو يخزّنها - كل حالة، مريض، برنامج علاج، " +
                "أو محادثة تحفظها تبقى على جهازك فقط، ولا تُرسل لأي جهة، ولا يمكن للمطوّر " +
                "الوصول إليها.\n\n" +

                "البيانات المحفوظة محليًا\n" +
                "• الحالات السريرية المخصّصة التي تضيفها.\n" +
                "• بيانات المرضى الأساسية (اسم، تواصل، ملاحظات) اللي تدخلها بنفسك.\n" +
                "• برامج العلاج الفيزيائي المحفوظة.\n" +
                "• سجل محادثة Phizyo AI.\n" +
                "• تفضيلاتك (الوضع الليلي/النهاري، المفضّلة، تعليمات Phizyo AI المخصّصة).\n" +
                "كل ما سبق مخزَّن في تخزين التطبيق الخاص على جهازك، ويُحذف نهائيًا لو حذفت " +
                "التطبيق أو مسحت بياناته من إعدادات النظام.\n\n" +

                "متى يتصل التطبيق بالإنترنت؟\n" +
                "فقط عند استخدامك لـ Phizyo AI أو البحث عن خلفية معرفية إضافية. في هذه " +
                "الحالة، جهازك يتصل مباشرة (بدون وسيط من عندنا) بواحد من اثنين:\n" +
                "• Physiopedia وويكيبيديا (بحث معرفي عام - بدون أي مفتاح أو تسجيل).\n" +
                "• رابط Cloudflare Worker واحد ثابت خاص بمساعد Phizyo AI، مبني داخل " +
                "التطبيق نفسه وغير قابل للتغيير من الإعدادات.\n" +
                "نص سؤالك فقط هو اللي يُرسل لهذه الجهات لتوليد الرد؛ لا تُرسل أي بيانات " +
                "مريض أو ملف شخصي بشكل تلقائي في الخلفية.\n\n" +

                "رابط Cloudflare Worker الثابت\n" +
                "مساعد Phizyo AI يعتمد على ووركر واحد فقط تم نشره على Cloudflare من قِبل " +
                "فريق التطبيق، ولا يوجد أي مفتاح API أو مزوّد بديل. لا نملك وصولًا لأي " +
                "بيانات شخصية تخصّك عبر هذا الووركر - فقط نص سؤالك والتعليمات المخصّصة " +
                "اللي أضفتها بنفسك (لو وُجدت) وقت إرسال كل رسالة.\n\n" +

                "لا إعلانات ولا تتبّع\n" +
                "التطبيق لا يعرض إعلانات، ولا يحتوي على أي مكتبة تتبّع أو تحليلات سلوك " +
                "المستخدم (Analytics/Ads SDK).\n\n" +

                "التواصل\n" +
                "لأي استفسار حول هذه السياسة أو التطبيق، تواصل مع فريق تطوير التطبيق " +
                "مباشرة عبر قنوات الدعم المرفقة مع التطبيق.";

        // العناوين بخط عريض وأكبر قليلًا (بدل الإيموجي القديمة)
        String[] headings = {"نظرة عامة", "البيانات المحفوظة محليًا", "متى يتصل التطبيق بالإنترنت؟", "رابط Cloudflare Worker الثابت", "لا إعلانات ولا تتبّع", "التواصل"};
        SpannableString styled = new SpannableString(body);
        for (String h : headings) {
            int start = body.indexOf(h + "\n");
            if (start < 0) continue;
            styled.setSpan(new StyleSpan(Typeface.BOLD), start, start + h.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new RelativeSizeSpan(1.15f), start, start + h.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.setText(styled);
    }
}
