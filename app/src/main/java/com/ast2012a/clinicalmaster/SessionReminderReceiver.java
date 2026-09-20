package com.ast2012a.clinicalmaster;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * يستقبل منبّه الصباح اليومي فيرسل إشعار "جلسات اليوم"، ثم يجدول منبّه اليوم التالي.
 * ويستقبل أيضًا إعادة التشغيل / تحديث التطبيق / تغيير الوقت لإعادة ضبط المنبّه.
 */
public class SessionReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        try {
            if (SessionReminder.ACTION_FIRE.equals(action)) {
                SessionReminder.showToday(context);
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                SessionReminder.catchUpIfMissed(context);
            }
        } catch (Throwable ignored) {
            // لا نُسقط التطبيق بسبب تنبيه
        } finally {
            SessionReminder.schedule(context);
        }
    }
}
