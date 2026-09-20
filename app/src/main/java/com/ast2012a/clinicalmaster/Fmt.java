package com.ast2012a.clinicalmaster;

import java.util.Calendar;
import java.util.Locale;

/**
 * تنسيقات التاريخ/الوقت/المبالغ (Java خالص). كل الأرقام لاتينية (0-9) عمدًا
 * لتبقى متسقة مع باقي التطبيق، ولا نعتمد على Locale الجهاز (الذي قد يحوّل
 * الأرقام إلى هندية "٠-٩" في بعض الأجهزة).
 */
public final class Fmt {

    private Fmt() {}

    private static final String[] DAYS = {
            "الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"
    };

    /** اسم اليوم من Calendar.DAY_OF_WEEK (1 = الأحد ... 7 = السبت). */
    public static String dayName(int calendarDayOfWeek) {
        return DAYS[calendarDayOfWeek - 1];
    }

    /** وقت اليوم من عدد الدقائق منذ منتصف الليل: 5:30 م */
    public static String minutesToTime(int minutesOfDay) {
        int h24 = (minutesOfDay / 60) % 24;
        int m = minutesOfDay % 60;
        int h = h24 % 12;
        if (h == 0) h = 12;
        return String.format(Locale.US, "%d:%02d", h, m) + " " + (h24 < 12 ? "ص" : "م");
    }

    private static Calendar cal(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c;
    }

    /** 21/09/2026 */
    public static String date(long millis) {
        Calendar c = cal(millis);
        return String.format(Locale.US, "%02d/%02d/%04d",
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
    }

    /** الأحد 21/09 */
    public static String dayDate(long millis) {
        Calendar c = cal(millis);
        return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " "
                + String.format(Locale.US, "%02d/%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1);
    }

    /** 3:30 م */
    public static String time(long millis) {
        Calendar c = cal(millis);
        int h = c.get(Calendar.HOUR);
        if (h == 0) h = 12;
        String ampm = c.get(Calendar.AM_PM) == Calendar.AM ? "ص" : "م";
        return String.format(Locale.US, "%d:%02d", h, c.get(Calendar.MINUTE)) + " " + ampm;
    }

    /** الأحد 21/09/2026، 3:30 م */
    public static String dateTime(long millis) {
        Calendar c = cal(millis);
        return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + date(millis) + "، " + time(millis);
    }

    public static long startOfDay(long millis) {
        Calendar c = cal(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** عدد الأيام (تقويمية) من then إلى now؛ موجب لو then في الماضي. */
    public static int daysBetween(long then, long now) {
        long a = startOfDay(then);
        long b = startOfDay(now);
        return (int) Math.round((b - a) / 86400000.0);
    }

    /** اليوم / أمس / قبل N أيام / قبل N أسابيع / تاريخ كامل. */
    public static String relative(long then, long now) {
        int d = daysBetween(then, now);
        if (d == 0) return "اليوم";
        if (d == 1) return "أمس";
        if (d == 2) return "قبل يومين";
        if (d > 2 && d < 11) return "قبل " + d + " أيام";
        if (d >= 11 && d < 14) return "قبل " + d + " يومًا";
        if (d >= 14 && d < 21) return "قبل أسبوعين";
        if (d >= 21 && d < 30) return "قبل " + (d / 7) + " أسابيع";
        if (d >= 30) return date(then);
        // مستقبل
        int f = -d;
        if (f == 1) return "غدًا";
        if (f == 2) return "بعد يومين";
        if (f < 11) return "بعد " + f + " أيام";
        return date(then);
    }

    /** مبلغ مع رمز العملة (اختياري): 1,250 أو 12.50 */
    public static String money(double value, String currency) {
        double v = Math.round(value * 100.0) / 100.0;
        String num;
        if (Math.abs(v - Math.rint(v)) < 0.005) {
            num = String.format(Locale.US, "%,.0f", v);
        } else {
            num = String.format(Locale.US, "%,.2f", v);
        }
        if (currency != null && !currency.trim().isEmpty()) {
            return num + " " + currency.trim();
        }
        return num;
    }

    /** أول حرف من الاسم للصورة الرمزية. */
    public static String initial(String name) {
        if (name == null) return "؟";
        String t = name.trim();
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            if (Character.isLetter(cp)) {
                return new String(Character.toChars(cp)).toUpperCase(Locale.US);
            }
            i += Character.charCount(cp);
        }
        return "؟";
    }

    /** مدة بالدقائق: 30 دقيقة */
    public static String minutes(int min) {
        return min + " دقيقة";
    }

    /** عدد الجلسات بصيغة عربية سليمة: بلا جلسات / جلسة واحدة / جلستان / 5 جلسات / 12 جلسة */
    public static String sessionsLabel(int n) {
        if (n <= 0) return "بلا جلسات";
        if (n == 1) return "جلسة واحدة";
        if (n == 2) return "جلستان";
        if (n <= 10) return n + " جلسات";
        return n + " جلسة";
    }
}
