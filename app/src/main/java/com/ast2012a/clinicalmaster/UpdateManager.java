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
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean promptedThisRun = false;
    private static boolean busy = false;

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

    /** يستخرج بنود "What's Changed" من ملاحظات الإصدار المولّدة تلقائيًا. */
    private static String parseNotes(String body) {
        if (body == null || body.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (!(line.startsWith("* ") || line.startsWith("- "))) continue;
            line = line.substring(2).trim();
            int by = line.indexOf(" by @");
            if (by > 0) line = line.substring(0, by).trim();
            line = line.replace("**", "").replace("`", "");
            if (line.isEmpty()) continue;
            if (line.length() > 90) line = line.substring(0, 88) + "…";
            sb.append("• ").append(line).append('\n');
            if (++count >= 5) break;
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
        sink.onStatus("جارٍ التحقق…");
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

    // ------------------------------------------------------------ نافذة التحديث

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    static void showUpdateDialog(Activity activity, Result r, boolean fromAuto) {
        StringBuilder msg = new StringBuilder();
        msg.append("الإصدار ").append(r.versionName).append(" جاهز (الحالي ")
                .append(r.currentVersion).append(")");
        if (r.apkSize > 0) msg.append("\nالحجم: ").append(mb(r.apkSize));
        if (!r.notes.isEmpty()) msg.append("\n\nما الجديد:\n").append(r.notes);

        ClaudeDialog d = new ClaudeDialog(activity)
                .setTitle("تحديث جديد متاح")
                .setMessage(msg.toString())
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

    private static void downloadAndInstall(Activity activity, Result r) {
        final Context app = activity.getApplicationContext();
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        // واجهة التقدم داخل ClaudeDialog
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(8 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(0, pad, 0, pad);
        final TextView label = new TextView(activity);
        label.setText("جارٍ التنزيل… 0%");
        label.setTextColor(activity.getColor(R.color.text_secondary));
        label.setTextSize(14f);
        label.setTextDirection(View.TEXT_DIRECTION_RTL);
        label.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        label.setGravity(Gravity.START);
        final LinearProgressIndicator bar = new LinearProgressIndicator(activity);
        bar.setMax(100);
        bar.setProgress(0);
        box.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = pad * 2;
        box.addView(bar, barLp);

        final Dialog progress = new ClaudeDialog(activity)
                .setTitle("تنزيل التحديث " + r.versionName)
                .setView(box)
                .setNegativeButton("إلغاء", (dlg, w) -> cancelled.set(true))
                .create();
        progress.setCancelable(false);
        progress.show();

        IO.execute(() -> {
            try {
                File apk = download(app, r, cancelled, (done, total) -> MAIN.post(() -> {
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    bar.setProgress(pct);
                    label.setText("جارٍ التنزيل… " + pct + "%  (" + mb(done)
                            + (total > 0 ? " / " + mb(total) : "") + ")");
                }));
                MAIN.post(() -> {
                    dismissQuietly(progress);
                    if (apk != null) install(activity, app, apk, r);
                });
            } catch (Exception e) {
                final String reason = e.getMessage() == null ? "خطأ غير معروف" : e.getMessage();
                MAIN.post(() -> {
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

    private interface Progress {
        void onProgress(long done, long total);
    }

    /** ينزّل الـ APK إلى الكاش ويتحقق من الحجم والبصمة. يرجع null لو أُلغي. */
    private static File download(Context ctx, Result r, AtomicBoolean cancelled, Progress p) throws Exception {
        File dir = new File(ctx.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("تعذّر إنشاء مجلد التنزيل.");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();

        String expectedHash = fetchExpectedHash(r);

        File part = new File(dir, "update.apk.part");
        File out = new File(dir, "update.apk");
        HttpURLConnection c = open(r.apkUrl);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("تعذّر تنزيل الملف (رمز الخادم " + code + ").");
            long total = c.getContentLengthLong();
            if (total <= 0) total = r.apkSize;

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long done = 0;
            long lastReport = 0;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(part)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.get()) {
                        os.close();
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        return null;
                    }
                    os.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 120) {
                        lastReport = now;
                        p.onProgress(done, total);
                    }
                }
            }
            p.onProgress(done, total);

            if (r.apkSize > 0 && done != r.apkSize) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("الملف المنزَّل غير مكتمل، حاول مرة أخرى.");
            }
            if (expectedHash != null && !expectedHash.equalsIgnoreCase(hex(sha.digest()))) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("فشل التحقق من سلامة الملف (SHA-256)، لم يتم التثبيت.");
            }
            if (!part.renameTo(out)) throw new IOException("تعذّر حفظ ملف التحديث.");
            return out;
        } finally {
            c.disconnect();
        }
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
            app.startActivity(i);
        } catch (Exception e) {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            new ClaudeDialog(activity)
                    .setTitle("تعذّر فتح المُثبّت")
                    .setMessage("تم تنزيل التحديث لكن تعذّر فتح مُثبّت النظام. جرّب من صفحة الإصدارات على GitHub.")
                    .setPositiveButton("تمام", null)
                    .show();
        }
    }
}
