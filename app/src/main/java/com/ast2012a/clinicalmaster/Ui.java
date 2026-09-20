package com.ast2012a.clinicalmaster;

import android.content.Context;

/** أدوات واجهة صغيرة مشتركة بين الشاشات المعاد تصميمها. */
public final class Ui {

    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
