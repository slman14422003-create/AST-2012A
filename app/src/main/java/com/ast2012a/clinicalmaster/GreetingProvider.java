package com.ast2012a.clinicalmaster;

import java.util.Calendar;
import java.util.Random;

/**
 * يبني عبارة ترحيب قصيرة تظهر أسفل عنوان الشاشة الرئيسية مباشرة، بدل
 * النص الثابت القديم ("دليل جهاز AST-2012A · مساعد العلاج الطبيعي
 * الشامل") اللي كان بيربك المستخدم لأنه بيوحي إن التطبيق مرتبط حصريًا
 * بجهاز معيّن باسم غريب، رغم إن اسم المنتج الفعلي هو Phizyo بس.
 *
 * العبارة تتغيّر حسب وقت اليوم (صباح/بعد الظهر/مساء/ليل) وتُختار عشوائيًا
 * من مجموعة صياغات مختلفة لكل فترة - فتبان مختلفة غالبًا في كل مرة يُفتح
 * فيها التطبيق بدل نص جامد ثابت مكرر.
 */
public final class GreetingProvider {

    private GreetingProvider() {}

    private static final String[] MORNING = {
            "صباح الخير! جاهزين نبدأ يوم علاجي مثمر.",
            "صباح النشاط - بماذا تفكر اليوم؟",
            "صباح الخير، Phizyo AI جاهز يساعدك.",
            "يوم جديد، جلسات جديدة - يلا نبدأ.",
    };

    private static final String[] AFTERNOON = {
            "نهارك سعيد! كيف نقدر نساعدك دلوقتي؟",
            "وقت مثالي لمراجعة حالة أو خطة علاج.",
            "أهلًا بيك - جاهزين لأي استفسار.",
            "منتصف اليوم وإحنا لسه هنا لخدمتك.",
    };

    private static final String[] EVENING = {
            "مساء الخير! بماذا تفكر؟",
            "مساء النشاط - جلسات اليوم قربت تخلص؟",
            "أهلًا بيك من جديد، إزاي نقدر نساعدك؟",
            "مساء الخير - جاهزين لأي حالة جديدة.",
    };

    private static final String[] NIGHT = {
            "سهرة هادئة؟ Phizyo AI موجود لو احتجت حاجة.",
            "حتى في الليل إحنا جاهزين لمساعدتك.",
            "بماذا تفكر؟ إحنا هنا في أي وقت.",
            "قبل ما تنام، خلّينا نراجع أي حالة معلّقة؟",
    };

    /** يختار عبارة عشوائية مناسبة لوقت اليوم الحالي على جهاز المستخدم. */
    public static String randomGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String[] pool;
        if (hour >= 5 && hour < 12) pool = MORNING;
        else if (hour >= 12 && hour < 17) pool = AFTERNOON;
        else if (hour >= 17 && hour < 21) pool = EVENING;
        else pool = NIGHT;
        return pool[new Random().nextInt(pool.length)];
    }
}
