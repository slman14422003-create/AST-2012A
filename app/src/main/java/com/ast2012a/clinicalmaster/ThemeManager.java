package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * يدير الوضع الليلي/النهاري للتطبيق كله من مكان واحد.
 * ثلاث حالات: "light" (نهاري دائمًا) - "dark" (ليلي دائمًا) - "system" (حسب إعداد الجهاز).
 * القيمة تُحفظ في SharedPreferences وتُطبَّق فورًا عند بدء التطبيق (من ClinicalMasterApp)
 * وأيضًا عند أي تبديل يدوي من المستخدم (من شاشة الإعدادات أو زر التبديل السريع).
 */
public class ThemeManager {

    private static final String PREFS = "settings_prefs";
    private static final String KEY_THEME_MODE = "theme_mode";

    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";
    public static final String MODE_SYSTEM = "system";

    /** يُستدعى مرة واحدة من Application.onCreate() قبل ما تُفتح أي شاشة. */
    public static void applySavedTheme(Context context) {
        AppCompatDelegate.setDefaultNightMode(modeToConstant(getCurrentMode(context)));
    }

    public static String getCurrentMode(Context context) {
        return prefs(context).getString(KEY_THEME_MODE, MODE_SYSTEM);
    }

    public static void setMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(modeToConstant(mode));
    }

    /** يدور بين الأوضاع الثلاثة بترتيب منطقي: نهاري ← ليلي ← حسب الجهاز ← نهاري... */
    public static String cycleMode(Context context) {
        String next;
        switch (getCurrentMode(context)) {
            case MODE_LIGHT:
                next = MODE_DARK;
                break;
            case MODE_DARK:
                next = MODE_SYSTEM;
                break;
            default:
                next = MODE_LIGHT;
        }
        setMode(context, next);
        return next;
    }

    public static String labelFor(String mode) {
        switch (mode) {
            case MODE_LIGHT: return "☀️ الوضع النهاري";
            case MODE_DARK: return "🌙 الوضع الليلي";
            default: return "🌓 حسب إعداد الجهاز";
        }
    }

    private static int modeToConstant(String mode) {
        if (MODE_LIGHT.equals(mode)) return AppCompatDelegate.MODE_NIGHT_NO;
        if (MODE_DARK.equals(mode)) return AppCompatDelegate.MODE_NIGHT_YES;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
