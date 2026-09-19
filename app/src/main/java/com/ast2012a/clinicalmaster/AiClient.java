package com.ast2012a.clinicalmaster;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * عميل الذكاء الاصطناعي (Phizyo AI). المزوّد الوحيد المعتمد هو رابط
 * Cloudflare Worker الثابت (FIXED_WORKER_URL) - بدون أي مزوّد بديل ولا
 * مفتاح API. الرابط ده مثبّت داخل التطبيق نفسه ومش قابل للتعديل من شاشة
 * الإعدادات - أي تعديل عليه لازم يكون من الكود مباشرة.
 *
 * أي تعليمات مخصّصة يضيفها المستخدم من شاشة الإعدادات (AiPrompts.
 * getCustomInstructions) بتتحط تلقائيًا جوه systemContext قبل ما توصل
 * هنا، فالووركر بيلتزم بيها في كل رد.
 */
public class AiClient {

    /** الرابط الثابت الوحيد لمساعد Phizyo AI. */
    public static final String FIXED_WORKER_URL = "https://little-flower-2b6f.slman14422003.workers.dev/";

    public interface Callback {
        void onSuccess(String reply);
        void onError(String message);
    }

    /** يُستدعى من Thread خلفية (مش الـ UI Thread). */
    public static void sendMessage(String systemContext, String userMessage, Callback callback) {
        sendViaWorker(FIXED_WORKER_URL, systemContext, userMessage, callback);
    }

    /** فحص اتصال بسيط بالووركر الثابت (تُستخدم من زر "اختبار الاتصال" في
     *  شاشة الإعدادات) - يرسل رسالة تجريبية قصيرة ويرجّع نجاح/فشل مباشرة. */
    public static void testWorker(Callback callback) {
        sendViaWorker(FIXED_WORKER_URL, "أجب بكلمة واحدة فقط للتأكد من عمل الاتصال.", "قل: تم الاتصال بنجاح ✅", callback);
    }

    /**
     * استدعاء تصنيف خفيف (Router) يسبق أي رد فعلي: بيبعت رسالة المستخدم
     * لنفس الووركر مع تعليمات AiPrompts.buildRouterPrompt() بس، ويرجع
     * "SEARCH" أو "CHAT" (أو أي رد غير متوقع بيتعامل معاه الطرف المستدعي
     * كحالة آمنة). القرار هنا من النموذج نفسه - مش قاعدة كلمات مفتاحية
     * ثابتة في الكود - فهو اللي "يقرر" فعلًا هل محتاج يبحث ولا لأ.
     * لازم يُستدعى من Thread خلفية (نفس شرط sendMessage). */
    public static void classifyIntent(String userMessage, Callback callback) {
        sendViaWorker(FIXED_WORKER_URL, AiPrompts.buildRouterPrompt(), userMessage, callback);
    }

    /**
     * يرسل POST بصيغة JSON بسيطة {"system": "...", "message": "..."}
     * لرابط الووركر، ويتوقع ردًا بصيغة {"reply": "..."} - نفس العقد
     * المستخدم في worker.js. عند أي فشل بيتم تمرير رسالة الخطأ الحقيقية
     * القادمة من الووركر نفسه (لو موجودة) بدل رسالة عامة مبهمة، عشان
     * تشخيص أي عطل مستقبلي يبقى سريع من داخل التطبيق نفسه.
     */
    private static void sendViaWorker(String workerUrl, String systemContext, String userMessage, Callback callback) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(workerUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(15000);
            // كانت 45 ثانية بس - كافية لردود قصيرة لكن ممكن تقطع ردود
            // طويلة قبل ما الووركر يخلص كتابتها، فيوصل جزء بس للتطبيق.
            // رفعناها لدقيقتين عشان نضمن وصول الرد كاملًا حتى لو تأخر.
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("system", systemContext == null ? "" : systemContext);
            body.put("message", userMessage);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();

            int status = conn.getResponseCode();
            InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = readStream(is);

            if (status < 200 || status >= 300) {
                String serverError = extractWorkerError(responseBody);
                callback.onError(serverError != null
                        ? "رد الووركر بخطأ: " + serverError
                        : "الووركر رجّع HTTP " + status + " بدون تفاصيل إضافية.");
                return;
            }
            if (responseBody == null || responseBody.trim().isEmpty()) {
                callback.onError("الووركر رجّع ردًا فارغًا.");
                return;
            }

            String reply = extractWorkerReply(responseBody);
            if (reply == null || reply.trim().isEmpty()) {
                callback.onError("تعذّر قراءة رد الووركر (شكل غير متوقع). الرد الخام: " + responseBody);
                return;
            }
            callback.onSuccess(reply.trim());

        } catch (Exception e) {
            callback.onError("تعذر الوصول لرابط الووركر: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** يقرأ حقل الخطأ من رد الووركر لو موجود بصيغة {"error": "..."}. */
    private static String extractWorkerError(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(responseBody);
            if (json.has("error")) return json.optString("error", null);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** يقرأ رد الووركر بأي من الشكلين الشائعين: {"reply": "..."} أو
     *  {"response": "..."} (عقد worker.js المرفق)، أو شكل متوافق مع OpenAI
     *  ({"choices":[{"message":{"content": "..."}}]}) لو الووركر اتعدّل
     *  ليرجّع هذا الشكل بدلًا منه. */
    private static String extractWorkerReply(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            if (json.has("reply")) {
                return json.optString("reply", null);
            }
            if (json.has("response")) {
                return json.optString("response", null);
            }
            JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                if (msg != null) return msg.optString("content", null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        reader.close();
        return sb.toString();
    }
}
