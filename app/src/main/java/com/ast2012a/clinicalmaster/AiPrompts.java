package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * مصدر واحد موحّد لتعليمات المساعد الذكي (System Prompt)، يُستخدم من كل
 * من MainActivity (البحث السريع) وAiAssistantActivity (المحادثة الكاملة)
 * بدل نسختين منفصلتين كانتا بتنكسر عن بعض مع أي تعديل.
 *
 * توسيع مهم: المساعد كان مبرمجًا كمساعد خاص بجهاز AST-2012A فقط. بناءً
 * على طلب المستخدم، بقى نطاقه أوسع بكثير - مساعد علاج طبيعي شامل (تقييم،
 * تشخيص تفريقي، بروتوكولات علاجية عامة، تمارين، تأهيل) - وجهاز AST-2012A
 * يبقى أحد الأدوات المتاحة له وليس كل نطاق معرفته.
 */
public class AiPrompts {

    private static final String PREFS = "settings_prefs";
    private static final String KEY_CUSTOM_INSTRUCTIONS = "ai_custom_instructions";

    private static final String BASE_SYSTEM_PROMPT =
            "أنت مساعد ذكي متخصص في العلاج الطبيعي بشكل عام - مو بس في جهاز " +
            "AST-2012A. تقدر تساعد في: تقييم الحالة الأولي وتوجيه الأسئلة الإكلينيكية " +
            "المناسبة، اقتراح خطط علاج طبيعي متكاملة (تمارين، تأهيل وظيفي، مراحل علاج)، " +
            "شرح مفاهيم تشريحية وحركية، ومقارنة بين طرق علاجية مختلفة - بجانب دعمك " +
            "المتخصص في استخدام جهاز AST-2012A (أنماط TENS وEMS) كأحد أدوات العلاج " +
            "المتاحة وليس محور كل إجابة. أجب بإيجاز ووضوح وبدقة سريرية باللغة العربية، " +
            "واذكر تحذيرات السلامة المهمة عند الحاجة (مثل منظمات ضربات القلب والحمل " +
            "والجروح المفتوحة والأورام والجلطات). إذا زُوّدت ببروتوكولات موثقة من قاعدة " +
            "بيانات الجهاز و/أو خلفية معرفية من ويكيبيديا، اجعلها مرجعك الأساسي عند " +
            "الصلة، ووازن بينها وبين معرفتك العامة الأوسع بوضوح (اتفاق أو اختلاف)، ونبّه " +
            "لو المصدر الخارجي عام ومش متخصص طبيًا بدقة. اقترح خطة علاج مستقرة ومتماسكة " +
            "بناءً على أفضل مصدر متاح، ونظّم إجاباتك الطويلة في نقاط قصيرة وواضحة بدل " +
            "الفقرات المطوّلة. لو السؤال غامض أو ينقصه تفاصيل سريرية مهمة (موضع الألم " +
            "بالضبط، شدة الأعراض، مدة المشكلة، هل توجد حالة طبية مصاحبة)، اسأل سؤالًا " +
            "توضيحيًا واحدًا مختصرًا أولًا بدل تخمين إجابة كاملة قد تكون غير دقيقة. " +
            "أنت أداة دعم قرار سريري لأخصائي علاج طبيعي مؤهل، مش بديلًا عن تقييمه " +
            "المهني المباشر للمريض.";

    /** يبني تعليمات النظام النهائية: النص الأساسي + خلفية سياقية (تأريض
     *  محلي/ويكيبيديا لو وُجد) + أي تعليمات مخصّصة أضافها المستخدم من
     *  شاشة الإعدادات ("علّم" المساعد الذكي حسب طلبه). */
    public static String buildSystemPrompt(Context ctx, String extraContext) {
        StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT);

        String customInstructions = getCustomInstructions(ctx);
        if (!customInstructions.isEmpty()) {
            sb.append("\n\nتعليمات إضافية مخصّصة أضافها مستخدم التطبيق (اتّبعها ما لم تتعارض ")
              .append("مع سلامة المريض أو الدقة الطبية):\n")
              .append(customInstructions);
        }

        if (extraContext != null && !extraContext.trim().isEmpty()) {
            sb.append("\n\n").append(extraContext);
        }
        return sb.toString();
    }

    public static String getCustomInstructions(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String v = prefs.getString(KEY_CUSTOM_INSTRUCTIONS, "");
        return v == null ? "" : v.trim();
    }

    public static void setCustomInstructions(Context ctx, String value) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CUSTOM_INSTRUCTIONS, value == null ? "" : value.trim()).apply();
    }
}
