package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.k2fsa.sherpa.onnx.GeneratedAudio;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * الصوت العصبي المحلي الحقيقي: نموذج Piper (VITS) عربي يعمل على الجهاز بلا إنترنت عبر sherpa-onnx.
 *
 * النموذج لا يأتي داخل الـ APK؛ يُنزَّل مرة واحدة (أرشيف tar.bz2 من إصدارات sherpa-onnx على GitHub) ويُفكّ
 * في التخزين الداخلي للتطبيق، ثم يعمل بعدها بلا اتصال. التنزيل يمرّ على مجلد مؤقت ولا يُعتمد إلا بعد التحقق من
 * وجود ملفاته كلها، فلا يبقى نموذج نصف منزَّل.
 *
 * كل الدوال آمنة للاستدعاء من أي خيط؛ ردود التنزيل تصل على الخيط الرئيسي. أي عطل في المكتبة الأصلية يُسجَّل
 * ({@link #isBroken()}) ويرجع القارئ تلقائيًا إلى المحرك المحلي الصيغي بدل أن يتوقف.
 */
final class NeuralVoiceManager {

    /** نموذج قابل للتنزيل. */
    static final class Model {
        final String id;
        final String label;
        final String url;
        final String folder;    // اسم المجلد داخل الأرشيف
        final String onnxName;

        Model(String id, String label, String url, String folder, String onnxName) {
            this.id = id;
            this.label = label;
            this.url = url;
            this.folder = folder;
            this.onnxName = onnxName;
        }
    }

    private static final String BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/";

    static final Model[] MODELS = {
            new Model("kareem-medium", "كريم · جودة عالية",
                    BASE + "vits-piper-ar_JO-kareem-medium.tar.bz2",
                    "vits-piper-ar_JO-kareem-medium", "ar_JO-kareem-medium.onnx"),
            new Model("kareem-low", "كريم · خفيف وأسرع",
                    BASE + "vits-piper-ar_JO-kareem-low.tar.bz2",
                    "vits-piper-ar_JO-kareem-low", "ar_JO-kareem-low.onnx"),
    };

    interface Progress {
        /** total = -1 لو الحجم غير معروف. */
        void onProgress(long done, long total);

        void onDone();

        void onError(String message);
    }

    private static final String PREFS = "neural_voice";
    private static final String K_SELECTED = "model";
    private static final int THREADS = 2;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();   // OfflineTts غير مضمون الأمان بين الخيوط

    private static volatile boolean broken;
    private static volatile boolean downloading;
    private static volatile boolean cancel;
    private static OfflineTts tts;
    private static String loadedId;

    private NeuralVoiceManager() {
    }

    // ------------------------------------------------------------------ اختيار وحالة

    static Model byId(String id) {
        if (id != null) {
            for (Model m : MODELS) if (m.id.equals(id)) return m;
        }
        return MODELS[0];
    }

    static Model selected(Context ctx) {
        SharedPreferences p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return byId(p.getString(K_SELECTED, null));
    }

    static void select(Context ctx, Model m) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(K_SELECTED, m.id).apply();
    }

    private static File root(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "neural_tts");
    }

    private static File dirOf(Context ctx, Model m) {
        return new File(root(ctx), m.id);
    }

    static boolean isInstalled(Context ctx, Model m) {
        File d = dirOf(ctx, m);
        return new File(d, m.onnxName).isFile() && new File(d, "tokens.txt").isFile()
                && new File(d, "espeak-ng-data").isDirectory() && new File(d, ".ok").isFile();
    }

    /** النموذج المختار منزَّل والمكتبة سليمة. */
    static boolean isReady(Context ctx) {
        return !broken && isInstalled(ctx, selected(ctx));
    }

    static boolean isBroken() {
        return broken;
    }

    static boolean isDownloading() {
        return downloading;
    }

    static void cancelDownload() {
        cancel = true;
    }

    /** يحذف نموذجًا منزَّلًا (ويحرّر الذاكرة لو كان محمَّلًا). */
    static void delete(Context ctx, Model m) {
        synchronized (LOCK) {
            if (tts != null && m.id.equals(loadedId)) releaseLocked();
        }
        deleteTree(dirOf(ctx, m));
    }

    // ------------------------------------------------------------------ التنزيل

    /** عدّاد بايتات للتقدّم (البايتات المضغوطة المقروءة من الشبكة). */
    private static final class Counting extends FilterInputStream {
        long count;

        Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) count += n;
            return n;
        }
    }

    /** ينزّل النموذج ويفكّه (خيط خلفي). ردود {@code cb} (تقدّم/انتهاء/خطأ) على الخيط الرئيسي. */
    static void download(final Context ctx, final Model m, final Progress cb) {
        if (downloading) {
            post(() -> cb.onError("التنزيل جارٍ بالفعل."));
            return;
        }
        downloading = true;
        cancel = false;
        current = cb;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            File tmp = new File(root(app), m.id + ".tmp");
            try {
                File base = root(app);
                if (!base.exists() && !base.mkdirs()) throw new IOException("تعذّر إنشاء مجلد التخزين");
                deleteTree(tmp);
                if (!tmp.mkdirs()) throw new IOException("تعذّر إنشاء مجلد مؤقت");

                OkHttpClient http = new OkHttpClient.Builder()
                        .connectTimeout(20, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build();
                Request req = new Request.Builder().url(m.url).build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) throw new IOException("رفض الخادم الطلب (HTTP " + resp.code() + ")");
                    ResponseBody body = resp.body();
                    if (body == null) throw new IOException("استجابة فارغة من الخادم");
                    final long total = body.contentLength();
                    final Counting counting = new Counting(body.byteStream());
                    extract(counting, tmp, total);
                }
                if (cancel) throw new IOException("أُلغي التنزيل");

                File src = new File(tmp, m.folder);
                if (!src.isDirectory()) { // اسم المجلد داخل الأرشيف اختلف: نأخذ المجلد الوحيد
                    File[] kids = tmp.listFiles(File::isDirectory);
                    if (kids != null && kids.length == 1) src = kids[0];
                }
                if (!new File(src, m.onnxName).isFile() || !new File(src, "tokens.txt").isFile()
                        || !new File(src, "espeak-ng-data").isDirectory()) {
                    throw new IOException("ملفات النموذج غير مكتملة في الأرشيف");
                }
                File dst = dirOf(app, m);
                deleteTree(dst);
                if (!src.renameTo(dst)) throw new IOException("تعذّر اعتماد النموذج");
                if (!new File(dst, ".ok").createNewFile()) throw new IOException("تعذّر إنهاء التثبيت");
                deleteTree(tmp);
                select(app, m);
                broken = false;
                synchronized (LOCK) {
                    releaseLocked(); // لو كان نموذج آخر محمَّلًا
                }
                post(() -> {
                    current = null;
                    cb.onDone();
                });
            } catch (Throwable e) {
                deleteTree(tmp);
                final String msg = cancel ? "أُلغي التنزيل."
                        : (e instanceof IOException && e.getMessage() != null && !isNetwork(e))
                        ? e.getMessage() : "تعذّر التنزيل، تأكد من الإنترنت وأعد المحاولة.";
                post(() -> {
                    current = null;
                    cb.onError(msg);
                });
            } finally {
                downloading = false;
                cancel = false;
            }
        }, "neural-model-download");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isNetwork(Throwable e) {
        return e instanceof java.net.UnknownHostException || e instanceof java.net.SocketException
                || e instanceof java.net.SocketTimeoutException || e instanceof javax.net.ssl.SSLException;
    }

    private static void extract(Counting in, File dest, long total) throws IOException {
        final String destPath = dest.getCanonicalPath() + File.separator;
        long lastPost = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new BZip2CompressorInputStream(new BufferedInputStream(in, 64 * 1024)))) {
            TarArchiveEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = tar.getNextTarEntry()) != null) {
                if (cancel) throw new IOException("أُلغي التنزيل");
                if (e.isSymbolicLink() || e.isLink()) continue;
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(destPath)) { // حماية من مسارات خبيثة (../)
                    throw new IOException("أرشيف غير صالح");
                }
                if (e.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("تعذّر إنشاء مجلد");
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("تعذّر إنشاء مجلد");
                }
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    int n;
                    while ((n = tar.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        long now = System.currentTimeMillis();
                        if (now - lastPost > 150) {
                            lastPost = now;
                            final long d = in.count;
                            post(() -> notifyProgress(d, total));
                        }
                        if (cancel) throw new IOException("أُلغي التنزيل");
                    }
                }
            }
        }
        post(() -> notifyProgress(in.count, total));
    }

    // المستمع الحالي للتقدّم (يُضبط عبر download؛ نمرّره كمرجع ثابت بسيط)
    private static volatile Progress current;

    private static void notifyProgress(long done, long total) {
        Progress p = current;
        if (p != null) p.onProgress(done, total);
    }

    private static void post(Runnable r) {
        MAIN.post(r);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ------------------------------------------------------------------ التحميل والتركيب

    private static void releaseLocked() {
        if (tts != null) {
            try {
                tts.release();
            } catch (Throwable ignored) {
            }
            tts = null;
            loadedId = null;
        }
    }

    private static OfflineTts engineLocked(Context ctx) {
        Model m = selected(ctx);
        if (tts != null && m.id.equals(loadedId)) return tts;
        releaseLocked();
        File d = dirOf(ctx, m);
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(new File(d, m.onnxName).getAbsolutePath());
        vits.setTokens(new File(d, "tokens.txt").getAbsolutePath());
        vits.setDataDir(new File(d, "espeak-ng-data").getAbsolutePath());
        OfflineTtsModelConfig mc = new OfflineTtsModelConfig();
        mc.setVits(vits);
        mc.setNumThreads(THREADS);
        mc.setDebug(false);
        mc.setProvider("cpu");
        OfflineTtsConfig cfg = new OfflineTtsConfig();
        cfg.setModel(mc);
        cfg.setMaxNumSentences(1);
        tts = new OfflineTts(null, cfg);
        loadedId = m.id;
        return tts;
    }

    /** يحمّل النموذج في الخلفية قبل أول جملة (التحميل يأخذ ثواني). */
    static void warmUpAsync(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!isReady(app)) return;
        Thread t = new Thread(() -> {
            synchronized (LOCK) {
                try {
                    engineLocked(app);
                } catch (Throwable e) {
                    broken = true;
                }
            }
        }, "neural-warmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * يركّب النص صوتًا (WAV 16 بت أحادي). أي فشل يرمي استثناءً فيرجع المستدعي للمحرك الصيغي.
     * التوقيت بين الكلمات غير متاح من النموذج، فتُرجَع مصفوفات فارغة ويقدّر القارئ التقدّم نسبيًا.
     */
    static EdgeTtsClient.Result synthesize(Context ctx, String text, EdgeTtsClient.Style style) throws IOException {
        final Context app = ctx.getApplicationContext();
        if (text == null || text.trim().isEmpty()) return new EdgeTtsClient.Result(new byte[0], new int[0], new int[0]);
        float speed = 1f;
        int tailMs = 0;
        if (style != null) {
            speed = Math.max(0.6f, Math.min(1.6f, 1f + style.arRatePct / 100f));
            tailMs = Math.max(0, Math.min(500, style.sentencePauseMs));
        }
        float[] samples;
        int rate;
        synchronized (LOCK) {
            try {
                OfflineTts e = engineLocked(app);
                GeneratedAudio a = e.generate(text, 0, speed);
                samples = a.getSamples();
                rate = a.getSampleRate();
            } catch (Throwable t) {
                broken = true;
                releaseLocked();
                throw new IOException("تعذّر تشغيل النموذج العصبي المحلي: " + t.getClass().getSimpleName());
            }
        }
        if (samples == null || samples.length < 400 || rate < 8000) {
            return new EdgeTtsClient.Result(new byte[0], new int[0], new int[0]);
        }
        byte[] wav = toWav(samples, rate, tailMs);
        int ms = (int) (samples.length * 1000L / rate) + tailMs;
        return new EdgeTtsClient.Result(wav, new int[0], new int[0], ms);
    }

    private static byte[] toWav(float[] x, int rate, int tailMs) {
        int tail = (int) ((long) rate * tailMs / 1000);
        int n = x.length + tail;
        int dataLen = n * 2;
        byte[] wav = new byte[44 + dataLen];
        putStr(wav, 0, "RIFF");
        putInt(wav, 4, 36 + dataLen);
        putStr(wav, 8, "WAVE");
        putStr(wav, 12, "fmt ");
        putInt(wav, 16, 16);
        putShort(wav, 20, 1);
        putShort(wav, 22, 1);
        putInt(wav, 24, rate);
        putInt(wav, 28, rate * 2);
        putShort(wav, 32, 2);
        putShort(wav, 34, 16);
        putStr(wav, 36, "data");
        putInt(wav, 40, dataLen);
        int fade = Math.max(1, rate / 400); // 2.5ms: يمنع طقطقة بداية/نهاية الجملة
        for (int i = 0; i < x.length; i++) {
            float v = x[i];
            if (i < fade) v *= i / (float) fade;
            else if (i >= x.length - fade) v *= (x.length - 1 - i) / (float) fade;
            int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
            wav[44 + i * 2] = (byte) (s & 0xFF);
            wav[45 + i * 2] = (byte) ((s >> 8) & 0xFF);
        }
        return wav; // الذيل أصفار أصلًا (صمت)
    }

    private static void putStr(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i);
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
        b[o + 2] = (byte) ((v >> 16) & 0xFF);
        b[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShort(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
