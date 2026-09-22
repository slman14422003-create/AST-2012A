package com.ast2012a.clinicalmaster;

import android.app.Application;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

/**
 * 1) يطبّق وضع الليلي/النهاري المحفوظ قبل رسم أي شاشة، لمنع أي وميض بصري.
 *    ملحوظة إصلاح: هذا الكلاس كان موجودًا لكنه لم يكن مسجّلًا في
 *    AndroidManifest.xml (بدون android:name على وسم application)، فكانت
 *    applySavedTheme() لا تُستدعى إطلاقًا عند فتح التطبيق من جديد - يعني
 *    اختيار "دائمًا ليلي/نهاري" من الإعدادات كان يُفقد فور إغلاق التطبيق
 *    وإعادة فتحه (يرجع لوضع النظام تلقائيًا). تم تسجيله الآن في المانفست.
 *
 * 2) يفعّل "Material You" الحقيقي (الألوان الديناميكية المستخرجة من خلفية
 *    شاشة المستخدم) على أندرويد 12 فأحدث. على الأجهزة الأقدم أو لو النظام
 *    لا يدعمها، يبقى التطبيق على هويته اللونية الطوبية الدافئة المعرّفة
 *    يدويًا في themes.xml بدون أي تغيير.
 */
public class ClinicalMasterApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        ThemeManager.applySavedTheme(this);

        // إعادة ضبط منبّه "جلسات اليوم" مع كل تشغيل (يغطي إيقاف التطبيق بالقوة)
        try {
            SessionReminder.schedule(this);
        } catch (Throwable ignored) {
        }

        DynamicColorsOptions options = new DynamicColorsOptions.Builder()
                .setPrecondition((activity, theme) -> DynamicColors.isDynamicColorAvailable()
                        // شاشة splash مستثناة عمدًا: لازم تفضل بنفس لون علامة
                        // Claude الثابت (claude_orange) دايمًا، مش لون ديناميكي
                        // مستخرج من خلفية جهاز المستخدم.
                        && !(activity instanceof SplashActivity))
                .build();
        DynamicColors.applyToActivitiesIfAvailable(this, options);
    }
}
