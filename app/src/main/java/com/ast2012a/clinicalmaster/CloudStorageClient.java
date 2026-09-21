package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * عميل التخزين السحابي المجاني للملفات (PDF/مستندات) عبر Cloudflare Worker
 * مبني على R2 (مجّاني حتى 10GB - راجع cloudflare-worker/README.md في جذر
 * المستودع لطريقة النشر الكاملة). بنفس أسلوب AiClient.java تمامًا:
 * HttpURLConnection مباشر بدون أي مكتبة شبكة إضافية.
 *
 * عقد الووركر المتوقَّع (routes نسبية لرابط الووركر المحفوظ في الإعدادات):
 *   GET    /files            -> JSON: [{"name","size","uploaded"}, ...]
 *   PUT    /files/{name}     -> body = بايتات الملف الخام، ترفع/تستبدل بنفس الاسم
 *   GET    /files/{name}     -> بايتات الملف الخام (لتنزيله محليًا)
 *   DELETE /files/{name}     -> حذف نهائي
 *   POST   /files/rename     -> body: {"from":"...","to":"..."} إعادة تسمية
 *
 * كل الطلبات تحمل Header التفويض Authorization: Bearer <token> لو محفوظ token
 * في الإعدادات (الووركر نفسه يتحقق منه - راجع storage-worker.js).
 */
final class CloudStorageClient {

    private CloudStorageClient() {}

    private static final String PREFS = "settings_prefs";
    private static final String KEY_URL = "cloud_storage_worker_url";
    private static final String KEY_TOKEN = "cloud_storage_worker_token";

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 60000;
    // مهلة أطول لرفع/تنزيل الملفات الكبيرة نسبيًا (PDF/مستندات).
    private static final int TRANSFER_READ_TIMEOUT = 180000;

    // ------------------------------------------------------------------ الإعدادات

    static boolean isConfigured(Context ctx) {
        return !getBaseUrl(ctx).isEmpty();
    }

    static String getBaseUrl(Context ctx) {
        String url = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, "");
        return url == null ? "" : url.trim();
    }

    static String getToken(Context ctx) {
        String t = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "");
        return t == null ? "" : t.trim();
    }

    static void saveConfig(Context ctx, String url, String token) {
        String normalized = url == null ? "" : url.trim();
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        // ملحوظة إصلاح: لو كتب المستخدم الرابط بدون https:// كان new URL(...) يفشل
        // بـ MalformedURLException ولا تعمل أي عملية سحابية.
        normalized = withScheme(normalized);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_URL, normalized)
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .apply();
    }

    // ------------------------------------------------------------------ عمليات الملفات

    static List<CloudFile> list(Context ctx) throws IOException, JSONException {
        HttpURLConnection conn = open(ctx, "/files", "GET");
        try {
            checkResponse(conn);
            String body = readAll(conn.getInputStream());
            JSONArray arr = new JSONArray(body);
            List<CloudFile> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) out.add(CloudFile.fromJson(arr.getJSONObject(i)));
            return out;
        } finally {
            conn.disconnect();
        }
    }

    /** رفع ملف جديد أو استبدال ملف موجود بنفس الاسم ("تعديل" الملف = رفع نسخة جديدة). */
    static void upload(Context ctx, String name, InputStream data, long length, String mimeType) throws IOException {
        HttpURLConnection conn = open(ctx, "/files/" + encodeSegment(name), "PUT");
        conn.setDoOutput(true);
        conn.setReadTimeout(TRANSFER_READ_TIMEOUT);
        if (mimeType != null && !mimeType.isEmpty()) conn.setRequestProperty("Content-Type", mimeType);
        if (length > 0) {
            conn.setFixedLengthStreamingMode(length);
        } else {
            conn.setChunkedStreamingMode(0);
        }
        try {
            OutputStream os = conn.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = data.read(buf)) != -1) os.write(buf, 0, n);
            os.flush();
            checkResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    static void delete(Context ctx, String name) throws IOException {
        HttpURLConnection conn = open(ctx, "/files/" + encodeSegment(name), "DELETE");
        try {
            checkResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    static void rename(Context ctx, String from, String to) throws IOException, JSONException {
        HttpURLConnection conn = open(ctx, "/files/rename", "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        JSONObject body = new JSONObject();
        body.put("from", from);
        body.put("to", to);
        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
        try {
            checkResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    /** ينزّل الملف بالكامل إلى dest (تُنشأ المجلدات الأب تلقائيًا). */
    static void downloadToFile(Context ctx, String name, File dest) throws IOException {
        HttpURLConnection conn = open(ctx, "/files/" + encodeSegment(name), "GET");
        conn.setReadTimeout(TRANSFER_READ_TIMEOUT);
        try {
            checkResponse(conn);
            File parent = dest.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------ اختبار اتصال

    static void testConnection(Context ctx) throws IOException, JSONException {
        list(ctx);
    }

    // ------------------------------------------------------------------ أدوات داخلية

    private static HttpURLConnection open(Context ctx, String path, String method) throws IOException {
        String base = getBaseUrl(ctx);
        if (base.isEmpty()) throw new IOException("لم يتم ضبط رابط التخزين السحابي بعد.");
        URL url = new URL(withScheme(base) + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        String token = getToken(ctx);
        if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        return conn;
    }

    private static String withScheme(String url) {
        if (url == null || url.isEmpty()) return "";
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? url : "https://" + url;
    }

    private static void checkResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) return;
        String err;
        try {
            err = readAll(conn.getErrorStream());
        } catch (Exception e) {
            err = "";
        }
        String detail;
        switch (code) {
            case 401:
            case 403:
                detail = "رمز الدخول (Token) غير صحيح أو غير مطابق لما هو مضبوط في الووركر.";
                break;
            case 404:
                detail = "الملف غير موجود، أو رابط الووركر خطأ (تأكد من نشره فعليًا).";
                break;
            case 413:
                // حد Workers KV المجاني: 25 ميجا كحد أقصى لكل ملف.
                detail = "الملف أكبر من الحد المسموح به (25 ميجا) على التخزين المجاني.";
                break;
            default:
                detail = "رمز الاستجابة: " + code;
        }
        throw new IOException(detail + (err.isEmpty() ? "" : ("\n" + err)));
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** ترميز اسم الملف كجزء من مسار URL (وليس Query String) - URLEncoder يحوّل
     *  المسافة لـ "+" وهو غير صالح داخل Path، فنستبدلها يدويًا بـ "%20". */
    private static String encodeSegment(String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return segment;
        }
    }
}
