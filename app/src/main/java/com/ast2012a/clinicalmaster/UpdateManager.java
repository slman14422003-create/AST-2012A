package com.ast2012a.clinicalmaster;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * التحقق من التحديثات من GitHub Releases (المستودع العام) وتنزيل أحدث APK
 * وتثبيته من داخل التطبيق بنفس واجهة ClaudeDialog.
 *
 * - الفحص التلقائي مرة كل 6 ساعات عند فتح الشاشة الرئيسية، والفحص اليدوي من الإعدادات.
 * - يفضّل ملف *-release.apk (الموقّع بمفتاحك الثابت) ثم أي APK آخر.
 * - يتحقق من SHA-256 (لو SHA256SUMS.txt موجود ضمن الإصدار) ومن تطابق توقيع التطبيق
 *   قبل فتح المُثبّت، حتى لا يفشل التثبيت بصمت.
 * - الفحص لا يرسل أي بيانات شخصية؛ هو طلب قراءة عام إلى api.github.com فقط.
 */
final class UpdateManager {

    private UpdateManager() {}

    // مستودع الإصدارات (يجب أن يكون عامًا)
    private static final String OWNER = "slman14422003-create";
    private static final String REPO = "AST-2012A";
    private static final String LATEST_URL =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";

    private static final String PREFS = "update_prefs";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_LATEST_TAG = "latest_tag";
    private static final String KEY_LATEST_AVAILABLE = "latest_available";
    private static final String KEY_SKIPPED_TAG = "skipped_tag";
    private static final String KEY_PENDING_TAG = "pending_tag";
    private static final String KEY_PENDING_TIME = "pending_time";
    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final long PENDING_GRACE_MS = 15L * 60 * 1000;
    private static final int MAX_SEGMENTS = 4;
    private static final long SEGMENT_MIN_BYTES = 2L * 1048576;
    static final String STATUS_CHECKING = "جارٍ التحقق…";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean promptedThisRun = false;
    private static boolean busy = false;
    private static volatile boolean downloading = false;

    // ------------------------------------------------------------------ النتيجة

    static final class Result {
        static final int UP_TO_DATE = 0;
        static final int AVAILABLE = 1;
        static final int ERROR = 2;

        int status = ERROR;
        String message = "";
        String tag = "";
        String versionName = "";
        String currentVersion = "";
        String notes = "";
        String htmlUrl = "";
        String apkName = "";
        String apkUrl = "";
        String sumsUrl = "";
        long apkSize;
    }

    interface Callback {
        void onResult(Result result);
    }

    interface StatusSink {
        void onStatus(String text);
    }

    // ---------------------------------------------------------------- الإصدارات

    static String installedVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "1.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    private static List<Integer> numbers(String v) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+").matcher(v == null ? "" : v);
        while (m.find()) {
            try {
                out.add(Integer.parseInt(m.group()));
            } catch (NumberFormatException e) {
                out.add(0);
            }
        }
        return out;
    }

    /** موجب لو a أحدث من b. */
    static int compareVersions(String a, String b) {
        List<Integer> x = numbers(a);
        List<Integer> y = numbers(b);
        int n = Math.max(x.size(), y.size());
        for (int i = 0; i < n; i++) {
            int xi = i < x.size() ? x.get(i) : 0;
            int yi = i < y.size() ? y.get(i) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** نص حالة صف "التحقق من التحديثات" في الإعدادات. */
    static String statusText(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String current = installedVersion(ctx);
        String latest = sp.getString(KEY_LATEST_TAG, "");
        if (sp.getBoolean(KEY_LATEST_AVAILABLE, false) && compareVersions(latest, current) > 0) {
            return "تحديث متاح: " + stripV(latest);
        }
        return "الإصدار الحالي " + current;
    }

    private static String stripV(String tag) {
        return tag != null && tag.toLowerCase(Locale.ROOT).startsWith("v") ? tag.substring(1) : tag;
    }

    // -------------------------------------------------------------------- الشبكة

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PhizyoStudio-Updater");
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Result fetchLatest(Context ctx) {
        Result r = new Result();
        r.currentVersion = installedVersion(ctx);
        HttpURLConnection c = null;
        try {
            c = open(LATEST_URL);
            int code = c.getResponseCode();
            if (code == 404) {
                r.message = "لا توجد إصدارات منشورة على GitHub بعد.";
                return r;
            }
            if (code == 403 || code == 429) {
                r.message = "تجاوزت حد طلبات GitHub مؤقتًا، حاول بعد قليل.";
                return r;
            }
            if (code != 200) {
                r.message = "تعذّر الفحص (رمز الخادم " + code + ").";
                return r;
            }
            JSONObject o = new JSONObject(readAll(c.getInputStream()));
            r.tag = o.optString("tag_name", "");
            r.versionName = stripV(r.tag);
            r.htmlUrl = o.optString("html_url", "");
            r.notes = parseNotes(o.optString("body", ""));

            JSONArray assets = o.optJSONArray("assets");
            String releaseApk = null, releaseUrl = null, anyApk = null, anyUrl = null;
            long releaseSize = 0, anySize = 0;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    String url = a.optString("browser_download_url", "");
                    if (name.equals("SHA256SUMS.txt")) r.sumsUrl = url;
                    if (!name.endsWith(".apk")) continue;
                    if (name.endsWith("-release.apk")) {
                        releaseApk = name;
                        releaseUrl = url;
                        releaseSize = a.optLong("size", 0);
                    } else if (anyApk == null) {
                        anyApk = name;
                        anyUrl = url;
                        anySize = a.optLong("size", 0);
                    }
                }
            }
            if (releaseApk != null) {
                r.apkName = releaseApk;
                r.apkUrl = releaseUrl;
                r.apkSize = releaseSize;
            } else if (anyApk != null) {
                r.apkName = anyApk;
                r.apkUrl = anyUrl;
                r.apkSize = anySize;
            }

            if (r.tag.isEmpty() || r.apkUrl.isEmpty()) {
                r.message = "الإصدار المنشور لا يحتوي ملف APK.";
                return r;
            }
            r.status = compareVersions(r.versionName, r.currentVersion) > 0
                    ? Result.AVAILABLE : Result.UP_TO_DATE;
            return r;
        } catch (Exception e) {
            r.message = "تعذّر الاتصال بـ GitHub. تأكد من الإنترنت وحاول مرة أخرى.";
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * يستخرج بنود «ما الجديد» من ملاحظات الإصدار. يقرأ قسم «## ما الجديد» فقط (الذي يكتبه
     * tools/generate_release_notes.py في GitHub Actions)، فلا تظهر شروحات release/debug/SHA256.
     * للإصدارات القديمة بلا هذا القسم: يأخذ البنود العامة مع تجاهل أسطر ملفات التحميل.
     */
    private static String parseNotes(String body) {
        if (body == null || body.isEmpty()) return "";
        String[] lines = body.split("\\r?\\n");
        boolean hasSection = false;
        for (String l : lines) {
            if (l.trim().startsWith("#") && l.contains("ما الجديد")) {
                hasSection = true;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        boolean inSection = !hasSection;
        for (String raw : lines) {
            String line = raw.trim();
            if (hasSection && line.startsWith("#")) {
                if (inSection) break;                       // بداية القسم التالي
                inSection = line.contains("ما الجديد");
                continue;
            }
            if (!inSection) continue;
            if (!(line.startsWith("* ") || line.startsWith("- "))) continue;
            line = line.substring(2).trim();
            int by = line.indexOf(" by @");
            if (by > 0) line = line.substring(0, by).trim();
            line = line.replace("**", "").replace("`", "");
            if (line.isEmpty()) continue;
            String low = line.toLowerCase(Locale.ROOT);
            if (low.contains(".apk") || low.contains("sha256")) continue; // شرح ملفات التحميل
            if (line.length() > 110) line = line.substring(0, 108) + "…";
            sb.append("• ").append(line).append('\n');
            if (++count >= 6) break;
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------- الفحص

    static void check(Context ctxIn, Callback cb) {
        final Context ctx = ctxIn.getApplicationContext();
        IO.execute(() -> {
            Result r = fetchLatest(ctx);
            SharedPreferences.Editor e = prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis());
            if (r.status != Result.ERROR) {
                e.putString(KEY_LATEST_TAG, r.tag);
                e.putBoolean(KEY_LATEST_AVAILABLE, r.status == Result.AVAILABLE);
            }
            e.apply();
            MAIN.post(() -> cb.onResult(r));
        });
    }

    /** فحص صامت (كل 6 ساعات) عند فتح التطبيق؛ يعرض نافذة التحديث فقط لو يوجد إصدار أحدث. */
    static void autoCheck(Activity activity) {
        cleanupTemp(activity);
        if (promptedThisRun || busy) return;
        SharedPreferences sp = prefs(activity);
        long last = sp.getLong(KEY_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return;
        busy = true;
        check(activity, r -> {
            busy = false;
            if (r.status != Result.AVAILABLE) return;
            if (r.tag.equals(sp.getString(KEY_SKIPPED_TAG, ""))) return;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            promptedThisRun = true;
            showUpdateDialog(activity, r, true);
        });
    }

    /** فحص يدوي من الإعدادات مع رسالة واضحة لكل حالة. */
    static void checkInteractive(Activity activity, StatusSink sink) {
        if (busy) return;
        busy = true;
        cleanupTemp(activity);
        sink.onStatus(STATUS_CHECKING);
        check(activity, r -> {
            busy = false;
            sink.onStatus(statusText(activity));
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (r.status == Result.AVAILABLE) {
                showUpdateDialog(activity, r, false);
            } else if (r.status == Result.UP_TO_DATE) {
                new ClaudeDialog(activity)
                        .setTitle("أنت على أحدث إصدار")
                        .setMessage("الإصدار الحالي " + r.currentVersion + " هو الأحدث المتاح.")
                        .setPositiveButton("تمام", null)
                        .show();
            } else {
                new ClaudeDialog(activity)
                        .setTitle("تعذّر التحقق من التحديثات")
                        .setMessage(r.message)
                        .setPositiveButton("تمام", null)
                        .show();
            }
        });
    }

    // ------------------------------------------------------------ أدوات الواجهة

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static TextView label(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    private static GradientDrawable shape(int fill, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static TextView chip(Context c, String s, boolean accent) {
        TextView t = label(c, s, 13f, c.getColor(accent ? R.color.white : R.color.text_primary), true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        t.setBackground(shape(c.getColor(accent ? R.color.primary_cyan : R.color.m3_surface_container_high),
                dp(c, 20)));
        return t;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int topDp, Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(c, topDp);
        return p;
    }

    // ------------------------------------------------------------ نافذة التحديث

    /** محتوى نافذة «تحديث جديد»: شارتا الإصدار (الحالي ← الجديد) + الحجم + ما الجديد. */
    private static View buildUpdateView(Context c, Result r) {
        final int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
        final int match = ViewGroup.LayoutParams.MATCH_PARENT;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.addView(chip(c, "الحالي " + r.currentVersion, false));
        TextView arrow = label(c, "←", 18f, c.getColor(R.color.text_secondary), true);
        arrow.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        row.addView(arrow);
        row.addView(chip(c, "الجديد " + r.versionName, true));
        box.addView(row, new LinearLayout.LayoutParams(wrap, wrap));

        if (r.apkSize > 0) {
            box.addView(label(c, "حجم التحديث: " + mb(r.apkSize), 14f, c.getColor(R.color.text_secondary), false),
                    lp(match, wrap, 12, c));
        }
        if (!r.notes.isEmpty()) {
            box.addView(label(c, "ما الجديد", 14f, c.getColor(R.color.text_primary), true),
                    lp(match, wrap, 14, c));
            TextView notes = label(c, r.notes, 13.5f, c.getColor(R.color.text_primary), false);
            notes.setLineSpacing(0f, 1.2f);
            notes.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
            notes.setBackground(shape(c.getColor(R.color.m3_surface_container), dp(c, 14)));
            box.addView(notes, lp(match, wrap, 6, c));
        }
        box.addView(label(c, "يُنزَّل الملف مباشرة ثم تُحذف الملفات المؤقتة تلقائيًا بعد التثبيت.",
                12f, c.getColor(R.color.text_secondary), false), lp(match, wrap, 14, c));
        return box;
    }

    static void showUpdateDialog(Activity activity, Result r, boolean fromAuto) {
        ClaudeDialog d = new ClaudeDialog(activity)
                .setTitle("تحديث جديد متاح")
                .setView(buildUpdateView(activity, r))
                .setPositiveButton("تحديث الآن", (dlg, w) -> startUpdate(activity, r))
                .setNegativeButton("لاحقًا", null);
        if (fromAuto) {
            d.setNeutralButton("تخطي هذا الإصدار", (dlg, w) ->
                    prefs(activity).edit().putString(KEY_SKIPPED_TAG, r.tag).apply());
        }
        d.show();
    }

    // ------------------------------------------------------------ التنزيل والتثبيت

    private static void startUpdate(Activity activity, Result r) {
        if (downloading) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new ClaudeDialog(activity)
                    .setTitle("السماح بالتثبيت")
                    .setMessage("لتثبيت التحديث من داخل التطبيق فعّل خيار «السماح من هذا المصدر» ثم ارجع واضغط «تحديث الآن» مرة أخرى.")
                    .setPositiveButton("فتح الإعدادات", (dlg, w) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (ActivityNotFoundException ignored) {
                        }
                    })
                    .setNegativeButton("إلغاء", null)
                    .show();
            return;
        }
        downloadAndInstall(activity, r);
    }

    /** واجهة تقدّم التنزيل: المرحلة + النسبة + شريط + الحجم + السرعة والوقت المتبقي. */
    private static final class ProgressUi {
        final LinearLayout root;
        final TextView stage, percent, sizes, speed;
        final LinearProgressIndicator bar;
        private boolean indeterminate = true;
        private long lastT = 0, lastDone = 0;
        private float ema = 0f;

        ProgressUi(Context c) {
            final int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
            final int match = ViewGroup.LayoutParams.MATCH_PARENT;
            final int secondary = c.getColor(R.color.text_secondary);
            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(0, dp(c, 4), 0, dp(c, 4));

            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            stage = label(c, "جارٍ تجهيز الملف…", 14f, secondary, true);
            percent = label(c, "", 24f, c.getColor(R.color.primary_cyan), true);
            top.addView(stage, new LinearLayout.LayoutParams(0, wrap, 1f));
            top.addView(percent, new LinearLayout.LayoutParams(wrap, wrap));
            root.addView(top, new LinearLayout.LayoutParams(match, wrap));

            bar = new LinearProgressIndicator(c);
            bar.setIndeterminate(true);
            bar.setTrackThickness(dp(c, 8));
            bar.setTrackCornerRadius(dp(c, 4));
            bar.setIndicatorColor(c.getColor(R.color.primary_cyan));
            bar.setTrackColor(c.getColor(R.color.primary_soft));
            root.addView(bar, lp(match, wrap, 12, c));

            LinearLayout bottom = new LinearLayout(c);
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            sizes = label(c, "", 12.5f, secondary, false);
            speed = label(c, "", 12.5f, secondary, false);
            speed.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
            bottom.addView(sizes, new LinearLayout.LayoutParams(0, wrap, 1f));
            bottom.addView(speed, new LinearLayout.LayoutParams(wrap, wrap));
            root.addView(bottom, lp(match, wrap, 10, c));
        }

        private void setIndeterminate(boolean on) {
            if (indeterminate == on) return;
            indeterminate = on;
            bar.setIndeterminate(on);
        }

        void update(int st, long done, long total) {
            if (st == STAGE_PREPARE) {
                stage.setText("جارٍ تجهيز الملف…");
                setIndeterminate(true);
                return;
            }
            if (st == STAGE_VERIFY) {
                stage.setText("جارٍ التحقق من سلامة الملف…");
                percent.setText("100%");
                speed.setText("");
                setIndeterminate(true);
                return;
            }
            int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
            stage.setText("جارٍ التنزيل");
            percent.setText(pct + "%");
            setIndeterminate(false);
            bar.setProgressCompat(pct, true);
            sizes.setText(mb(done) + (total > 0 ? " / " + mb(total) : ""));

            long now = SystemClock.uptimeMillis();
            if (lastT == 0) {
                lastT = now;
                lastDone = done;
            } else if (now - lastT >= 500) {
                float inst = (done - lastDone) * 1000f / (now - lastT);
                ema = ema == 0f ? inst : ema * 0.7f + inst * 0.3f;
                lastT = now;
                lastDone = done;
            }
            if (ema > 1024f) {
                StringBuilder s = new StringBuilder(String.format(Locale.US, "%.1f MB/s", ema / 1048576f));
                if (total > done) {
                    long eta = (long) ((total - done) / ema);
                    s.append("  ·  متبقي ").append(eta / 60).append(':')
                            .append(String.format(Locale.US, "%02d", eta % 60));
                }
                speed.setText(s.toString());
            }
        }
    }

    private static void downloadAndInstall(Activity activity, Result r) {
        final Context app = activity.getApplicationContext();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ProgressUi ui = new ProgressUi(activity);

        final Dialog progress = new ClaudeDialog(activity)
                .setTitle("تنزيل التحديث " + r.versionName)
                .setView(ui.root)
                .setNegativeButton("إلغاء", (dlg, w) -> cancelled.set(true))
                .create();
        progress.setCancelable(false);
        progress.show();
        downloading = true;

        IO.execute(() -> {
            try {
                File apk = download(app, r, cancelled,
                        (st, done, total) -> MAIN.post(() -> ui.update(st, done, total)));
                MAIN.post(() -> {
                    downloading = false;
                    dismissQuietly(progress);
                    if (apk != null) install(activity, app, apk, r);
                });
            } catch (Exception e) {
                clearDir(updatesDir(app));
                final String reason = e.getMessage() == null ? "خطأ غير معروف" : e.getMessage();
                MAIN.post(() -> {
                    downloading = false;
                    dismissQuietly(progress);
                    if (cancelled.get() || activity.isFinishing() || activity.isDestroyed()) return;
                    new ClaudeDialog(activity)
                            .setTitle("تعذّر تنزيل التحديث")
                            .setMessage(reason)
                            .setPositiveButton("تمام", null)
                            .show();
                });
            }
        });
    }

    private static void dismissQuietly(Dialog d) {
        try {
            d.dismiss();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ الملفات المؤقتة

    private static File updatesDir(Context ctx) {
        return new File(ctx.getCacheDir(), "updates");
    }

    private static void clearDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /**
     * حذف ملفات التحديث المؤقتة (update.apk / .part). يُستدعى عند فتح التطبيق والإعدادات:
     * بعد نجاح التثبيت تبدأ العملية من جديد فيُمسح الملف فورًا، ونترك الملف فقط لو كان
     * التثبيت ما زال جاريًا (أقل من 15 دقيقة) حتى لا نحذف الملف من تحت مُثبّت النظام.
     */
    static void cleanupTemp(Context ctxIn) {
        final Context ctx = ctxIn.getApplicationContext();
        IO.execute(() -> {
            try {
                File dir = updatesDir(ctx);
                File[] files = dir.listFiles();
                SharedPreferences sp = prefs(ctx);
                String pending = sp.getString(KEY_PENDING_TAG, "");
                if (files == null || files.length == 0) {
                    if (!pending.isEmpty()) clearPending(sp);
                    return;
                }
                long when = sp.getLong(KEY_PENDING_TIME, 0);
                boolean installed = pending.isEmpty()
                        || compareVersions(installedVersion(ctx), stripV(pending)) >= 0;
                boolean installerBusy = !installed
                        && System.currentTimeMillis() - when < PENDING_GRACE_MS;
                if (installerBusy) return;
                clearDir(dir);
                clearPending(sp);
            } catch (Exception ignored) {
            }
        });
    }

    private static void clearPending(SharedPreferences sp) {
        sp.edit().remove(KEY_PENDING_TAG).remove(KEY_PENDING_TIME).apply();
    }

    // ------------------------------------------------------------ منطق التنزيل

    static final int STAGE_PREPARE = 0;
    static final int STAGE_DOWNLOAD = 1;
    static final int STAGE_VERIFY = 2;

    private interface Progress {
        void onProgress(int stage, long done, long total);
    }

    private static final class Probe {
        long total;
        boolean ranged;
    }

    /** طلب صغير (بايت واحد) لمعرفة الحجم الفعلي ومعرفة هل الخادم يدعم التنزيل المجزّأ. */
    private static Probe probe(Result r) throws IOException {
        HttpURLConnection c = open(r.apkUrl);
        c.setRequestProperty("Range", "bytes=0-0");
        try {
            int code = c.getResponseCode();
            Probe p = new Probe();
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range"); // bytes 0-0/12345
                int slash = cr == null ? -1 : cr.lastIndexOf('/');
                if (slash > 0) {
                    try {
                        p.total = Long.parseLong(cr.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                p.ranged = p.total > 0;
            } else if (code == 200) {
                p.total = c.getContentLengthLong();
            } else {
                throw new IOException("تعذّر تنزيل الملف (رمز الخادم " + code + ").");
            }
            return p;
        } finally {
            c.disconnect();
        }
    }

    /**
     * ينشئ ملفًا فارغًا بحجم الـ APK كاملًا ثم يملؤه: الحجز المسبق يكشف نقص المساحة قبل البدء،
     * ويمنع تجزّؤ الملف أثناء الكتابة، ويسمح بتنزيل عدة أجزاء بالتوازي كل جزء في موضعه مباشرة.
     * بعد الاكتمال يتحقق من الحجم والبصمة. يرجع null لو أُلغي (ويحذف الملف المؤقت).
     */
    private static File download(Context ctx, Result r, AtomicBoolean cancelled, Progress p) throws Exception {
        File dir = updatesDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("تعذّر إنشاء مجلد التنزيل.");
        clearDir(dir);

        p.onProgress(STAGE_PREPARE, 0, r.apkSize);
        String expectedHash = fetchExpectedHash(r);
        Probe probe = probe(r);
        final long total = probe.total > 0 ? probe.total : r.apkSize;
        if (total <= 0) throw new IOException("تعذّر تحديد حجم ملف التحديث.");
        if (dir.getUsableSpace() < total + 8L * 1048576) {
            throw new IOException("مساحة التخزين لا تكفي لتنزيل التحديث (المطلوب " + mb(total)
                    + "). حرّر بعض المساحة وحاول مرة أخرى.");
        }

        File part = new File(dir, "update.apk.part");
        File out = new File(dir, "update.apk");
        boolean ok = false;
        try {
            // 1) إنشاء ملف فارغ بالحجم الكامل
            try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                raf.setLength(total);
            }
            if (cancelled.get()) return null;

            // 2) ملء الملف (أجزاء متوازية لو مدعوم وإلا مجرى واحد)
            final int segs = (probe.ranged && total >= SEGMENT_MIN_BYTES) ? MAX_SEGMENTS : 1;
            final AtomicLong done = new AtomicLong();
            final AtomicBoolean failed = new AtomicBoolean(false);
            final AtomicReference<IOException> error = new AtomicReference<>();
            final ExecutorService pool = Executors.newFixedThreadPool(segs);
            final List<Future<?>> futures = new ArrayList<>();
            final long chunk = total / segs;
            for (int i = 0; i < segs; i++) {
                final long start = i * chunk;
                final long end = (i == segs - 1) ? total - 1 : start + chunk - 1;
                futures.add(pool.submit(() -> {
                    try {
                        fetchSegment(r.apkUrl, part, start, end, probe.ranged, cancelled, failed, done);
                    } catch (IOException e) {
                        error.compareAndSet(null, e);
                        failed.set(true);
                    }
                    return null;
                }));
            }
            try {
                while (!cancelled.get() && !failed.get()) {
                    boolean all = true;
                    for (Future<?> f : futures) if (!f.isDone()) all = false;
                    p.onProgress(STAGE_DOWNLOAD, Math.min(done.get(), total), total);
                    if (all) break;
                    Thread.sleep(150);
                }
            } finally {
                pool.shutdown();
                if (!pool.awaitTermination(15, TimeUnit.SECONDS)) pool.shutdownNow();
            }
            if (cancelled.get()) return null;
            if (error.get() != null) {
                throw new IOException("انقطع التنزيل (" + error.get().getMessage() + ")، حاول مرة أخرى.");
            }
            if (done.get() != total || part.length() != total) {
                throw new IOException("الملف المنزَّل غير مكتمل، حاول مرة أخرى.");
            }
            if (r.apkSize > 0 && total != r.apkSize) {
                throw new IOException("حجم الملف لا يطابق الإصدار المنشور، حاول مرة أخرى.");
            }
            p.onProgress(STAGE_DOWNLOAD, total, total);

            // 3) التحقق من البصمة
            if (expectedHash != null) {
                p.onProgress(STAGE_VERIFY, total, total);
                if (!expectedHash.equalsIgnoreCase(sha256(part))) {
                    throw new IOException("فشل التحقق من سلامة الملف (SHA-256)، لم يتم التثبيت.");
                }
            }
            if (cancelled.get()) return null;
            if (!part.renameTo(out)) throw new IOException("تعذّر حفظ ملف التحديث.");
            ok = true;
            return out;
        } finally {
            if (!ok) //noinspection ResultOfMethodCallIgnored
                part.delete();
        }
    }

    /** ينزّل الجزء [start..end] ويكتبه في موضعه داخل الملف المحجوز، مع إعادة محاولة واستئناف. */
    private static void fetchSegment(String url, File part, long start, long end, boolean ranged,
                                     AtomicBoolean cancelled, AtomicBoolean failed, AtomicLong done)
            throws IOException {
        long pos = start;
        int attempts = 0;
        while (pos <= end) {
            if (cancelled.get() || failed.get()) return;
            if (!ranged && pos != start) { // بدون دعم الاستئناف: نبدأ الجزء من أوله
                done.addAndGet(-(pos - start));
                pos = start;
            }
            HttpURLConnection c = null;
            try {
                c = open(url);
                if (ranged) c.setRequestProperty("Range", "bytes=" + pos + "-" + end);
                int code = c.getResponseCode();
                if (code != (ranged ? 206 : 200)) throw new IOException("رمز الخادم " + code);
                try (InputStream in = c.getInputStream();
                     RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                    raf.seek(pos);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while (pos <= end
                            && (n = in.read(buf, 0, (int) Math.min(buf.length, end - pos + 1))) > 0) {
                        if (cancelled.get() || failed.get()) return;
                        raf.write(buf, 0, n);
                        pos += n;
                        done.addAndGet(n);
                    }
                }
                if (pos <= end) throw new IOException("انقطع الاتصال");
            } catch (IOException e) {
                if (++attempts >= 4) throw e;
                try {
                    Thread.sleep(700L * attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("توقف التنزيل");
                }
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) sha.update(buf, 0, n);
        }
        return hex(sha.digest());
    }

    /** بصمة SHA-256 المتوقعة للـ APK من SHA256SUMS.txt (أو null لو غير متاحة). */
    private static String fetchExpectedHash(Result r) {
        if (r.sumsUrl == null || r.sumsUrl.isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            c = open(r.sumsUrl);
            if (c.getResponseCode() != 200) return null;
            for (String line : readAll(c.getInputStream()).split("\\r?\\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2 && parts[parts.length - 1].replace("*", "").equals(r.apkName)) {
                    return parts[0];
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return null;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    // ------------------------------------------------------------------ التوقيع

    @SuppressWarnings("deprecation")
    private static Set<String> signerHashes(PackageInfo pi) throws Exception {
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo si = pi.signingInfo;
            if (si != null) {
                sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
            }
        } else {
            sigs = pi.signatures;
        }
        Set<String> out = new HashSet<>();
        if (sigs != null) {
            for (Signature s : sigs) {
                out.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
            }
        }
        return out;
    }

    /** true = نفس التوقيع، false = مختلف، null = تعذّر التحديد. */
    @SuppressWarnings("deprecation")
    private static Boolean sameSigner(Context ctx, File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo installed = pm.getPackageInfo(ctx.getPackageName(), flags);
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (archive == null) return null;
            Set<String> a = signerHashes(installed);
            Set<String> b = signerHashes(archive);
            if (a.isEmpty() || b.isEmpty()) return null;
            for (String h : b) if (a.contains(h)) return true;
            return false;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ التثبيت

    private static void install(Activity activity, Context app, File apk, Result r) {
        Boolean same = sameSigner(app, apk);
        if (same != null && !same) {
            clearDir(updatesDir(app)); // ملف لن يُقبل: لا داعي لإبقائه
            if (activity.isFinishing() || activity.isDestroyed()) return;
            new ClaudeDialog(activity)
                    .setTitle("لا يمكن التثبيت فوق النسخة الحالية")
                    .setMessage("ملف التحديث موقّع بمفتاح مختلف عن نسخة التطبيق المثبّتة عندك، لذلك سيرفضه أندرويد.\n\n"
                            + "لتثبيته: خذ نسخة احتياطية سحابية لملفات المرضى أولًا، ثم احذف التطبيق وثبّت الإصدار الجديد من صفحة الإصدارات. "
                            + "التحديثات القادمة ستُثبَّت مباشرة بعد ذلك.")
                    .setPositiveButton("فتح صفحة الإصدار", (dlg, w) -> {
                        try {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                                    r.htmlUrl.isEmpty() ? "https://github.com/" + OWNER + "/" + REPO + "/releases" : r.htmlUrl)));
                        } catch (ActivityNotFoundException ignored) {
                        }
                    })
                    .setNegativeButton("إغلاق", null)
                    .show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".updates", apk);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            // نسجّل أن تثبيتًا جاريًا: يُحذف الملف المؤقت تلقائيًا في أول تشغيل بعد نجاح التثبيت
            prefs(app).edit().putString(KEY_PENDING_TAG, r.tag)
                    .putLong(KEY_PENDING_TIME, System.currentTimeMillis()).apply();
            app.startActivity(i);
        } catch (Exception e) {
            clearDir(updatesDir(app));
            clearPending(prefs(app));
            if (activity.isFinishing() || activity.isDestroyed()) return;
            new ClaudeDialog(activity)
                    .setTitle("تعذّر فتح المُثبّت")
                    .setMessage("تم تنزيل التحديث لكن تعذّر فتح مُثبّت النظام. جرّب من صفحة الإصدارات على GitHub.")
                    .setPositiveButton("تمام", null)
                    .show();
        }
    }
}
