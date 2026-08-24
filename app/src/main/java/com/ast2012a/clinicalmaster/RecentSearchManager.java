package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * سجل آخر عمليات البحث (محليًا فقط) - يُعرض كاقتراحات سريعة في الشاشة
 * الرئيسية قبل ما المستخدم يكتب أي حاجة، لتسريع الوصول لأكثر الحالات
 * التي يبحث عنها المستخدم تكرارًا.
 */
public class RecentSearchManager {

    private static final String PREFS = "recent_search_prefs";
    private static final String KEY_RECENT = "recent_queries";
    private static final int MAX_ITEMS = 6;

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void addQuery(Context ctx, String query) {
        if (query == null) return;
        String trimmed = query.trim();
        if (trimmed.length() < 2) return;

        List<String> current = getRecent(ctx);
        current.remove(trimmed);
        current.add(0, trimmed);
        while (current.size() > MAX_ITEMS) current.remove(current.size() - 1);

        StringBuilder sb = new StringBuilder();
        for (String q : current) sb.append(q).append('\n');
        prefs(ctx).edit().putString(KEY_RECENT, sb.toString()).apply();
    }

    public static List<String> getRecent(Context ctx) {
        String raw = prefs(ctx).getString(KEY_RECENT, "");
        List<String> list = new ArrayList<>(new LinkedHashSet<>());
        if (raw == null || raw.isEmpty()) return list;
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) list.add(line.trim());
        }
        return list;
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY_RECENT).apply();
    }
}
