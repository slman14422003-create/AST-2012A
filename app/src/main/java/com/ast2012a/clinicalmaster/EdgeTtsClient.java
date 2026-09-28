package com.ast2012a.clinicalmaster;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * عميل الأصوات العصبية المجانية (نفس أصوات "القراءة بصوت عالٍ" في متصفح Edge) - بدون مفتاح API
 * وبدون حساب. يرسل جملة واحدة ويستقبل ملف MP3 جاهزًا + توقيت كل كلمة (إن أرسله الخادم)
 * لتظليل الكلمة المنطوقة.
 *
 * ملاحظة: هذه الخدمة غير رسمية (Microsoft لا تضمن ثباتها). لذلك PdfSpeaker يرجع تلقائيًا لصوت
 * الجهاز لو فشل الاتصال. لو توقفت الأصوات فجأة عن العمل (خطأ 403 مستمر) حدّث CHROMIUM_FULL_VERSION
 * أدناه إلى رقم إصدار Edge الحالي.
 */
final class EdgeTtsClient {

    private EdgeTtsClient() {
    }

    // ---- ثوابت البروتوكول (قابلة للتحديث لو غيّرتها مايكروسوفت)
    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    private static final String SEC_MS_GEC_VERSION = "1-" + CHROMIUM_FULL_VERSION;
    private static final String WSS_BASE =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/" + CHROMIUM_FULL_VERSION.split("\\.")[0]
            + ".0.0.0 Safari/537.36 Edg/" + CHROMIUM_FULL_VERSION.split("\\.")[0] + ".0.0.0";
    private static final long WIN_EPOCH_SECONDS = 11644473600L;
    private static final int TIMEOUT_SECONDS = 16;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    /** نتيجة جملة واحدة: صوت MP3 + توقيت الكلمات (بالميلي ثانية) وموضع كل كلمة داخل النص المُرسَل. */
    static final class Result {
        final byte[] audio;
        final int[] wordMs;
        final int[] wordChar;

        Result(byte[] audio, int[] wordMs, int[] wordChar) {
            this.audio = audio;
            this.wordMs = wordMs;
            this.wordChar = wordChar;
        }
    }

    /** أسلوب النطق: سرعة العربي (نسبة)، طبقة الصوت (Hz)، وقفات إضافية بعد الجمل/الفواصل (ms). */
    static final class Style {
        static final Style DEFAULT = new Style(0, 0, 0, 0);
        final int arRatePct;
        final int pitchHz;
        final int sentencePauseMs;
        final int commaPauseMs;

        Style(int arRatePct, int pitchHz, int sentencePauseMs, int commaPauseMs) {
            this.arRatePct = arRatePct;
            this.pitchHz = pitchHz;
            this.sentencePauseMs = sentencePauseMs;
            this.commaPauseMs = commaPauseMs;
        }
    }

    /** جزء من النص (مواضع داخل النص المُرسَل) يُنطق بصوت معيّن - لتبديل الصوت عند الكلمات الأجنبية. */
    static final class Run {
        final int start;
        final int end;
        final String voice;

        Run(int start, int end, String voice) {
            this.start = start;
            this.end = end;
            this.voice = voice;
        }
    }

    /** جودة 96kbps أولًا؛ لو رفضها الخادم نثبّت 48kbps لبقية الجلسة. */
    private static volatile boolean hqBroken = false;
    private static volatile int mixedFails = 0;

    /** يستبدل أي محرف تحكّم بمسافة (بنفس الطول تمامًا حتى تبقى مواضع الكلمات صحيحة). */
    static String sanitize(String s) {
        char[] a = s.toCharArray();
        for (int i = 0; i < a.length; i++) {
            char c = a[i];
            if (c < 0x20 || c == 0x7F || c == 0xFFFE || c == 0xFFFF || c == '\u2028' || c == '\u2029') {
                a[i] = ' ';
            }
        }
        return new String(a);
    }

    /** آخر سبب فشل (للتشخيص فقط - يظهر في رسالة الخطأ للمستخدم). */
    static volatile String lastError = "";

    /** فشل من الخادم نفسه (الصوت غير مدعوم/الاتصال أُغلق بعد الاتصال) وليس انقطاع إنترنت. */
    static final class ServiceException extends IOException {
        final int httpCode;

        ServiceException(String msg, int httpCode) {
            super(msg);
            this.httpCode = httpCode;
        }
    }

    /** true لو الفشل سببه الشبكة (لا إنترنت / انتهاء المهلة) - تبديل الصوت لن يفيد وقتها. */
    static boolean isNetworkFailure(Throwable t) {
        while (t != null) {
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof SocketTimeoutException || t instanceof SSLException
                    || t instanceof SocketException) {
                return true;
            }
            String m = t.getMessage();
            if (m != null) {
                String l = m.toLowerCase(Locale.ROOT);
                if (l.contains("timeout") || l.contains("unable to resolve") || l.contains("failed to connect")
                        || l.contains("network is unreachable")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /** ينطق نصًا واحدًا (جملة/مقطع قصير) بالصوت المحدد. يُستدعى من خيط خلفي فقط (يحجب حتى ينتهي). */
    static Result synthesize(String text, String voice) throws IOException {
        return synthesize(text, voice, Style.DEFAULT);
    }

    static Result synthesize(String text, String voice, Style style) throws IOException {
        return doSynth(text, buildSsml(text, null, voice, style), voice, false);
    }

    /**
     * ينطق نصًا متعدد اللغات بصوت مختلف لكل جزء (عربي بصوت عربي، إنجليزي بصوت إنجليزي) في طلب واحد
     * وبتوقيت كلمات متصل. لو الخادم رفض تعدد الأصوات نرجع تلقائيًا لصوت واحد للنص كله.
     */
    static Result synthesizeRuns(String text, List<Run> runs, String baseVoice, Style style) throws IOException {
        if (runs == null || runs.size() < 2 || mixedFails >= 2) return synthesize(text, baseVoice, style);
        try {
            Result r = doSynth(text, buildSsml(text, runs, baseVoice, style), baseVoice, true);
            mixedFails = 0;
            return r;
        } catch (ServiceException e) {
            if (e.httpCode != 0) throw e;
            mixedFails++;
            return synthesize(text, baseVoice, style);
        }
    }

    private static Result doSynth(String text, String ssml, String voice, boolean mixed) throws IOException {
        long skewMs = 0;
        IOException last = null;
        boolean hq = !hqBroken;
        boolean hqFellBack = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            Attempt a = new Attempt(text, ssml, voice, skewMs, hq);
            try {
                Result r = a.run();
                lastError = "";
                if (hqFellBack) hqBroken = true; // 48kbps نجح بعد فشل 96kbps
                return r;
            } catch (IOException e) {
                last = e;
                lastError = (a.httpCode != 0 ? "HTTP " + a.httpCode + " " : "") + e.getMessage();
                // 403 غالبًا بسبب فرق ساعة الجهاز عن الخادم: نصحّح الفرق ونعيد المحاولة
                if (a.httpCode == 403 && a.serverDateMs > 0) {
                    skewMs = a.serverDateMs - System.currentTimeMillis();
                } else if (e instanceof ServiceException && a.httpCode == 0) {
                    if (hq) { // ربما الصيغة عالية الجودة غير مدعومة: نجرّب العادية فورًا
                        hq = false;
                        hqFellBack = true;
                        continue;
                    }
                    if (mixed) throw e;
                } else if (isNetworkFailure(e) && !(e.getMessage() != null && e.getMessage().contains("timeout"))) {
                    break; // لا إنترنت أصلًا - لا فائدة من التكرار
                }
                try {
                    Thread.sleep(250L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last != null ? last : new IOException("edge tts failed");
    }

    private static final class Attempt {
        final String text;
        final String ssml;
        final String voice;
        final long skewMs;
        final boolean hq;
        volatile int httpCode = 0;
        volatile long serverDateMs = 0;

        Attempt(String text, String ssml, String voice, long skewMs, boolean hq) {
            this.text = text;
            this.ssml = ssml;
            this.voice = voice;
            this.skewMs = skewMs;
            this.hq = hq;
        }

        Result run() throws IOException {
            final String url = WSS_BASE
                    + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                    + "&ConnectionId=" + randomHex()
                    + "&Sec-MS-GEC=" + secMsGec(skewMs)
                    + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;
            Request req = new Request.Builder()
                    .url(url)
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("Origin", ORIGIN)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "muid=" + randomHex().toUpperCase(Locale.ROOT) + ";")
                    .build();

            final ByteArrayOutputStream audio = new ByteArrayOutputStream();
            final List<Integer> ms = new ArrayList<>();
            final List<String> words = new ArrayList<>();
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicBoolean finished = new AtomicBoolean(false);
            final AtomicReference<Throwable> error = new AtomicReference<>();
            final AtomicReference<String> closeInfo = new AtomicReference<>();

            WebSocketListener listener = new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    ws.send(configMessage(hq));
                    ws.send(ssmlMessage(ssml));
                }

                @Override
                public void onMessage(WebSocket ws, String t) {
                    if (t.contains("Path:turn.end")) {
                        finished.set(true);
                        ws.close(1000, null);
                        done.countDown();
                    } else if (t.contains("Path:audio.metadata")) {
                        parseMetadata(t, ms, words);
                    }
                }

                @Override
                public void onMessage(WebSocket ws, ByteString bytes) {
                    byte[] b = bytes.toByteArray();
                    if (b.length < 2) return;
                    int hl = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
                    if (hl < 0 || 2 + hl > b.length) return;
                    String head = new String(b, 2, hl, StandardCharsets.UTF_8);
                    if (!head.contains("Path:audio")) return;
                    int start = 2 + hl;
                    if (b.length > start) audio.write(b, start, b.length - start);
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    if (!finished.get()) closeInfo.set("close=" + code + (reason != null && !reason.isEmpty() ? " " + reason : ""));
                    ws.close(code, null);
                    done.countDown();
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    error.set(t);
                    if (response != null) {
                        httpCode = response.code();
                        String date = response.header("Date");
                        if (date != null) serverDateMs = parseHttpDate(date);
                    }
                    done.countDown();
                }
            };

            WebSocket ws = HTTP.newWebSocket(req, listener);
            boolean ok;
            try {
                ok = done.await(Math.max(TIMEOUT_SECONDS, 12 + text.length() / 50), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                ws.cancel();
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            if (!ok) {
                ws.cancel();
                throw new IOException("timeout");
            }
            if (!finished.get()) {
                Throwable t = error.get();
                String info = "edge tts closed early" + (httpCode != 0 ? " http=" + httpCode : "")
                        + (closeInfo.get() != null ? " " + closeInfo.get() : "")
                        + (t != null ? ": " + t.getMessage() : "");
                if (t != null && httpCode == 0) throw new IOException(info, t); // فشل شبكة (اتصال)
                throw new ServiceException(info, httpCode); // الخادم ردّ لكنه رفض/أغلق
            }
            byte[] data = audio.toByteArray();
            if (data.length < 200 && text.trim().length() > 2) {
                boolean speakable = false;
                for (int i = 0; i < text.length(); i++) {
                    if (Character.isLetterOrDigit(text.charAt(i))) {
                        speakable = true;
                        break;
                    }
                }
                if (speakable) throw new ServiceException("no audio received for voice " + voice, 0);
            }
            int n = ms.size();
            int[] wMs = new int[n];
            int[] wChar = new int[n];
            int cursor = 0;
            int kept = 0;
            for (int i = 0; i < n; i++) {
                String w = words.get(i);
                if (w == null || w.isEmpty()) continue;
                int idx = text.indexOf(w, cursor);
                if (idx < 0) continue;
                wMs[kept] = ms.get(i);
                wChar[kept] = idx;
                kept++;
                cursor = idx + w.length();
            }
            if (kept < n) {
                int[] a1 = new int[kept];
                int[] a2 = new int[kept];
                System.arraycopy(wMs, 0, a1, 0, kept);
                System.arraycopy(wChar, 0, a2, 0, kept);
                wMs = a1;
                wChar = a2;
            }
            return new Result(data, wMs, wChar);
        }
    }

    // ------------------------------------------------------------------ الرسائل

    private static String timestamp() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date()) + " GMT+0000 (Coordinated Universal Time)";
    }

    private static String configMessage(boolean hq) {
        return "X-Timestamp:" + timestamp() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},"
                + "\"outputFormat\":\"" + (hq ? "audio-24khz-96kbitrate-mono-mp3" : "audio-24khz-48kbitrate-mono-mp3")
                + "\"}}}}\r\n";
    }

    private static String ssmlMessage(String ssml) {
        return "X-RequestId:" + randomHex() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + timestamp() + "Z\r\n"
                + "Path:ssml\r\n\r\n" + ssml;
    }

    private static String localeOf(String voice) {
        String[] vp = voice.split("-");
        return vp.length >= 2 ? vp[0] + "-" + vp[1] : "en-US"; // لغة الصوت الفعلية (ar-SA...) لا en-US ثابتة
    }

    private static String buildSsml(String text, List<Run> runs, String baseVoice, Style st) {
        StringBuilder sb = new StringBuilder(text.length() + 256);
        sb.append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='")
                .append(localeOf(baseVoice)).append("'>");
        if (runs == null || runs.size() < 2) {
            sb.append(voiceElement(baseVoice, text, st));
        } else {
            for (Run r : runs) {
                int a = Math.max(0, Math.min(text.length(), r.start));
                int b = Math.max(a, Math.min(text.length(), r.end));
                String piece = text.substring(a, b).trim();
                if (piece.isEmpty()) continue;
                sb.append(voiceElement(r.voice, piece, st));
            }
        }
        return sb.append("</speak>").toString();
    }

    private static String voiceElement(String voice, String piece, Style st) {
        int rate = voice.startsWith("ar-") ? st.arRatePct : 0;
        return "<voice name='" + longVoiceName(voice) + "'><prosody pitch='" + signed(st.pitchHz) + "Hz' rate='"
                + signed(rate) + "%' volume='+0%'>" + withBreaks(piece, st) + "</prosody></voice>";
    }

    private static String signed(int v) {
        return (v >= 0 ? "+" : "") + v;
    }

    /** يهرّب الرموز ويضيف وقفات إضافية بعد نهاية الجمل والفواصل (لا داخل الأرقام العشرية). */
    private static String withBreaks(String s, Style st) {
        if (st.sentencePauseMs <= 0 && st.commaPauseMs <= 0) return xmlEscape(s);
        StringBuilder sb = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(xmlEscape(String.valueOf(c)));
            boolean boundary = i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1));
            if (!boundary) continue;
            if (st.sentencePauseMs > 0 && ".!?\u061F\u061B\u2026".indexOf(c) >= 0) {
                sb.append("<break time='").append(st.sentencePauseMs).append("ms'/>");
            } else if (st.commaPauseMs > 0 && ",\u060C:".indexOf(c) >= 0) {
                sb.append("<break time='").append(st.commaPauseMs).append("ms'/>");
            }
        }
        return sb.toString();
    }

    /** ar-SA-ZariyahNeural -> Microsoft Server Speech Text to Speech Voice (ar-SA, ZariyahNeural) */
    private static String longVoiceName(String shortName) {
        String[] p = shortName.split("-", 3);
        if (p.length == 3) {
            return "Microsoft Server Speech Text to Speech Voice (" + p[0] + "-" + p[1] + ", " + p[2] + ")";
        }
        return shortName;
    }

    private static String xmlEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void parseMetadata(String message, List<Integer> ms, List<String> words) {
        try {
            int i = message.indexOf("\r\n\r\n");
            if (i < 0) return;
            JSONObject o = new JSONObject(message.substring(i + 4));
            JSONArray arr = o.optJSONArray("Metadata");
            if (arr == null) return;
            for (int k = 0; k < arr.length(); k++) {
                JSONObject e = arr.optJSONObject(k);
                if (e == null || !"WordBoundary".equals(e.optString("Type"))) continue;
                JSONObject d = e.optJSONObject("Data");
                if (d == null) continue;
                JSONObject t = d.optJSONObject("text");
                if (t == null) continue;
                long offset = d.optLong("Offset", 0L); // وحدات 100 نانو ثانية
                ms.add((int) (offset / 10000L));
                words.add(t.optString("Text", ""));
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ التوثيق (Sec-MS-GEC)

    private static String secMsGec(long skewMs) {
        try {
            long ticks = (System.currentTimeMillis() + skewMs) / 1000L + WIN_EPOCH_SECONDS;
            ticks -= ticks % 300L;
            long hundredNs = ticks * 10000000L;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((hundredNs + TRUSTED_CLIENT_TOKEN).getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02X", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static long parseHttpDate(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            Date d = f.parse(s);
            return d != null ? d.getTime() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String randomHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
