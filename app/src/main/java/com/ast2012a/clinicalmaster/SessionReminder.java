package com.ast2012a.clinicalmaster;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * تنبيه الصباح: عند بداية يوم جلسات المريض (الساعة 8:00 ص) يصل إشعار بأسماء
 * المرضى الذين لديهم جلسات اليوم (حسب الجدول الأسبوعي أو موعد يدوي في نفس اليوم).
 *
 * - يُفعَّل/يُوقَف من مفتاح "الإشعارات" في الإعدادات (نفس المفتاح المحفوظ).
 * - يستخدم AlarmManager بدون إذن "المنبّهات الدقيقة"؛ قد يتأخر دقائق بحسب توفير الطاقة.
 * - لا يُرسَل إشعار لو لا يوجد مرضى اليوم، ولا يتكرر أكثر من مرة في اليوم.
 * - بعد إعادة تشغيل الجهاز يُعاد الجدولة، ولو فاته موعد اليوم يُرسَل فورًا (حتى 9 مساءً).
 */
final class SessionReminder {

    private SessionReminder() {}

    static final String ACTION_FIRE = "com.ast2012a.clinicalmaster.action.SESSION_REMINDER";
    static final String CHANNEL_ID = "session_reminders";

    static final int REMINDER_HOUR = 8;
    static final int REMINDER_MINUTE = 0;
    private static final int LATE_CUTOFF_HOUR = 21;

    private static final int REQUEST_CODE = 7301;
    private static final int NOTIFICATION_ID = 2001;

    // نفس ملف/مفتاح إعدادات الإشعارات في SettingsActivity
    private static final String PREFS = "settings_prefs";
    private static final String KEY_NOTIF_ENABLED = "notif_enabled";
    private static final String KEY_LAST_DAY = "session_reminder_last_day";

    // ------------------------------------------------------------------ الحالة

    static boolean isEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean(KEY_NOTIF_ENABLED, false)) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // ---------------------------------------------------------------- الجدولة

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, SessionReminderReceiver.class).setAction(ACTION_FIRE);
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** أقرب 8:00 ص قادمة (اليوم لو لم تحن بعد، وإلا غدًا). */
    static long nextTrigger(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, REMINDER_HOUR);
        c.set(Calendar.MINUTE, REMINDER_MINUTE);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    /** يضبط منبّه الغد لو التنبيهات مفعّلة، وإلا يلغيه. */
    static void schedule(Context ctxIn) {
        Context ctx = ctxIn.getApplicationContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (!isEnabled(ctx)) {
            am.cancel(alarmIntent(ctx));
            return;
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    nextTrigger(System.currentTimeMillis()), alarmIntent(ctx));
        } catch (SecurityException ignored) {
        }
    }

    static void cancel(Context ctxIn) {
        Context ctx = ctxIn.getApplicationContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(ctx));
    }

    // ---------------------------------------------------------- مرضى اليوم

    static final class Entry {
        final String name;
        /** دقائق منذ منتصف الليل، أو -1 لو بلا وقت. */
        final int minutes;

        Entry(String name, int minutes) {
            this.name = name;
            this.minutes = minutes;
        }
    }

    static List<Entry> patientsToday(Context ctx, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        long start = Fmt.startOfDay(now);
        long end = start + 24L * 60 * 60 * 1000;

        List<Entry> out = new ArrayList<>();
        for (Patient p : PatientManager.loadPatients(ctx)) {
            boolean manualToday = p.nextAppointment >= start && p.nextAppointment < end;
            boolean scheduledToday = p.hasSessionDay(dow);
            if (!manualToday && !scheduledToday) continue;

            // لو سُجّلت جلسة اليوم فعلًا لا داعي للتذكير
            boolean doneToday = false;
            for (Patient.Session s : p.sessions) {
                if (s.date >= start && s.date < end) {
                    doneToday = true;
                    break;
                }
            }
            if (doneToday) continue;

            int minutes = -1;
            if (manualToday) {
                Calendar m = Calendar.getInstance();
                m.setTimeInMillis(p.nextAppointment);
                minutes = m.get(Calendar.HOUR_OF_DAY) * 60 + m.get(Calendar.MINUTE);
            } else if (p.sessionTimeMin >= 0) {
                minutes = p.sessionTimeMin;
            }
            out.add(new Entry(p.displayName(), minutes));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int ma = a.minutes < 0 ? Integer.MAX_VALUE : a.minutes;
                int mb = b.minutes < 0 ? Integer.MAX_VALUE : b.minutes;
                if (ma != mb) return Integer.compare(ma, mb);
                return a.name.compareTo(b.name);
            }
        });
        return out;
    }

    // -------------------------------------------------------------- الإشعار

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "تذكير جلسات اليوم", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("إشعار صباحي بالمرضى الذين لديهم جلسات اليوم");
        nm.createNotificationChannel(ch);
    }

    private static String joinNames(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(i == list.size() - 1 ? " و" : "، ");
            sb.append(list.get(i).name);
        }
        return sb.toString();
    }

    /**
     * يرسل إشعار جلسات اليوم (مرة واحدة فقط في اليوم، وفقط لو يوجد مرضى).
     */
    static void showToday(Context ctxIn) {
        Context ctx = ctxIn.getApplicationContext();
        if (!isEnabled(ctx)) return;

        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int dayKey = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getInt(KEY_LAST_DAY, -1) == dayKey) return;

        List<Entry> today = patientsToday(ctx, now);
        sp.edit().putInt(KEY_LAST_DAY, dayKey).apply();
        if (today.isEmpty()) return;

        ensureChannel(ctx);

        String title;
        String text;
        if (today.size() == 1) {
            title = "جلسة اليوم";
            text = "المريض " + today.get(0).name + " لديه جلسة اليوم"
                    + (today.get(0).minutes >= 0 ? " الساعة " + Fmt.minutesToTime(today.get(0).minutes) : "") + ".";
        } else {
            title = "جلسات اليوم (" + today.size() + ")";
            text = "المرضى " + joinNames(today) + " لديهم جلسات اليوم.";
        }

        StringBuilder big = new StringBuilder();
        for (Entry e : today) {
            if (big.length() > 0) big.append('\n');
            big.append("• ").append(e.name);
            if (e.minutes >= 0) big.append(" — ").append(Fmt.minutesToTime(e.minutes));
        }

        Intent open = new Intent(ctx, PatientsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sessions)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(today.size() > 1 ? text + "\n\n" + big : text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setWhen(now);

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) {
        }
    }

    /** بعد إعادة تشغيل الجهاز: لو فات موعد 8:00 اليوم ولم يُرسَل الإشعار، أرسله الآن (حتى 9 مساءً). */
    static void catchUpIfMissed(Context ctx) {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        int min = c.get(Calendar.MINUTE);
        boolean afterReminder = h > REMINDER_HOUR || (h == REMINDER_HOUR && min >= REMINDER_MINUTE);
        if (afterReminder && h < LATE_CUTOFF_HOUR) showToday(ctx);
    }
}
