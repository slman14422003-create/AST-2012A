package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Process;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * ملتقط أعطال شامل: بدل شاشة النظام السوداء "توقف التطبيق" (بدون أي تفاصيل
 * مفيدة)، يحفظ نص الخطأ الكامل (Stack Trace) في SharedPreferences ثم يفتح
 * CrashActivity التي تعرضه بشكل قابل للنسخ (لإرساله هنا للمساعدة في تشخيصه)
 * مع زر "إعادة تشغيل التطبيق". يُسجَّل مرة واحدة في ClinicalMasterApp.onCreate.
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    static final String PREFS = "crash_prefs";
    static final String KEY_TRACE = "last_crash_trace";
    static final String KEY_TIME = "last_crash_time";

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(Context ctx, Thread.UncaughtExceptionHandler previous) {
        this.appContext = ctx.getApplicationContext();
        this.previous = previous;
    }

    /** يُستدعى مرة واحدة عند بدء التطبيق. آمن الاستدعاء المتكرر (لن يضاعف المعالج). */
    static void install(Context ctx) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(ctx, current));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            SharedPreferences.Editor editor = appContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            editor.putString(KEY_TRACE, sw.toString());
            editor.putLong(KEY_TIME, System.currentTimeMillis());
            editor.commit(); // commit متزامن مهم هنا: العملية على وشك الإنهاء

            Intent intent = new Intent(appContext, CrashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            appContext.startActivity(intent);
        } catch (Throwable ignored) {
            // لو فشل حتى هذا، نكمل لإنهاء العملية بالأسفل بدل ما تعلّق
        }
        Process.killProcess(Process.myPid());
        System.exit(10);
    }
}
