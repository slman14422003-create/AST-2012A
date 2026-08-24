package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * إدارة "الحالات المفضلة": نخزن مجموعة عناوين الحالات المفضّلة محليًا في
 * SharedPreferences (يعمل لكل من الحالات المدمجة والمخصصة لأن العنوان
 * فريد داخل قاعدة البيانات). لا حاجة لخادم أو قاعدة بيانات إضافية.
 */
public class FavoritesManager {

    private static final String PREFS = "favorites_prefs";
    private static final String KEY_FAVORITES = "favorite_titles";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isFavorite(Context ctx, String caseTitle) {
        if (caseTitle == null) return false;
        return prefs(ctx).getStringSet(KEY_FAVORITES, new HashSet<>()).contains(caseTitle);
    }

    public static boolean toggleFavorite(Context ctx, String caseTitle) {
        if (caseTitle == null) return false;
        Set<String> current = new HashSet<>(prefs(ctx).getStringSet(KEY_FAVORITES, new HashSet<>()));
        boolean nowFavorite;
        if (current.contains(caseTitle)) {
            current.remove(caseTitle);
            nowFavorite = false;
        } else {
            current.add(caseTitle);
            nowFavorite = true;
        }
        prefs(ctx).edit().putStringSet(KEY_FAVORITES, current).apply();
        return nowFavorite;
    }

    public static Set<String> getFavoriteTitles(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_FAVORITES, new HashSet<>()));
    }

    public static List<CaseItem> getFavoriteCases(Context ctx) {
        Set<String> favTitles = getFavoriteTitles(ctx);
        List<CaseItem> result = new ArrayList<>();
        if (favTitles.isEmpty()) return result;
        for (CaseItem c : DataManager.allCases(ctx)) {
            if (favTitles.contains(c.title)) result.add(c);
        }
        return result;
    }
}
