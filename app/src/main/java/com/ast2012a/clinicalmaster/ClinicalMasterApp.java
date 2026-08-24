package com.ast2012a.clinicalmaster;

import android.app.Application;

/** يطبّق وضع الليلي/النهاري المحفوظ قبل رسم أي شاشة، لمنع أي وميض بصري. */
public class ClinicalMasterApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.applySavedTheme(this);
    }
}
