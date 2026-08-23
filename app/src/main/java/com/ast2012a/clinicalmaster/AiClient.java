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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * عميل الذكاء الاصطناعي - له مزوّدان:
 *
 * 1) Pollinations (الافتراضي، بدون أي مفتاح أو إعداد): منصة مفتوحة المصدر
 *    (pollinations.ai) بتوفر endpoint نصي مجاني تمامًا وبدون تسجيل أو مفتاح
 *    API إطلاقًا - أقرب حل حقيقي وصادق لـ "ذكاء اصطناعي جاهز بدون ما تعمل
 *    شي". هذا هو المزوّد المستخدم افتراضيًا لكل المستخدمين تلقائيًا.
 *
 * 2) OpenRouter (اختياري/متقدم): لو المستخدم عايز يجرب نموذج بديل، يقدر
 *    يضيف مفتاح API مجاني خاص بيه من openrouter.ai في شاشة الإعدادات،
 *    وساعتها التطبيق هيستخدمه بدل Pollinations تلقائيًا.
 */
public class AiClient {

    private static final String POLLINATIONS_ENDPOINT = "https://text.pollinations.ai/";
    private static final String OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final String OPENROUTER_MODEL = "openrouter/free";

    public interface Callback {
        void onSuccess(String reply);
        void onError(String message);
    }

    /**
     * يُستدعى من Thread خلفية (مش الـ UI Thread). لو apiKey فاضي أو null
     * بيستخدم Pollinations (بدون مفتاح) تلقائيًا، وإلا بيستخدم OpenRouter
     * بمفتاح المستخدم.
     */
    public static void sendMessage(String apiKey, String systemContext, String userMessage, Callback callback) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            sendViaPollinations(systemContext, userMessage, callback);
        } else {
            sendViaOpenRouter(apiKey.trim(), systemContext, userMessage, callback);
        }
    }

    // -----------------------------------------------------------------
    // Pollinations - بدون مفتاح إطلاقًا
    // -----------------------------------------------------------------

    private static void sendViaPollinations(String systemContext, String userMessage, Callback callback) {
        HttpURLConnection conn = null;
        try {
            String encodedPrompt = URLEncoder.encode(userMessage, "UTF-8").replace("+", "%20");
            StringBuilder urlStr = new StringBuilder(POLLINATIONS_ENDPOINT).append(encodedPrompt);
            urlStr.append("?model=openai");
            if (systemContext != null && !systemContext.isEmpty()) {
                urlStr.append("&system=").append(URLEncoder.encode(systemContext, "UTF-8").replace("+", "%20"));
            }

            URL url = new URL(urlStr.toString());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(45000);

            int status = conn.getResponseCode();
            InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = readStream(is);

            if (status < 200 || status >= 300) {
                callback.onError("تعذر الوصول لخدمة الذكاء الاصطناعي المجانية حاليًا (قد تكون مزدحمة). حاول بعد قليل، أو أضف مفتاح OpenRouter الخاص بك من الإعدادات كبديل.");
                return;
            }
            if (responseBody == null || responseBody.trim().isEmpty()) {
                callback.onError("لم يصل رد من الخدمة. حاول مرة أخرى.");
                return;
            }
            callback.onSuccess(responseBody.trim());

        } catch (IOException e) {
            callback.onError("تعذر الاتصال بالإنترنت. تأكد من الاتصال وحاول مرة أخرى.");
        } catch (Exception e) {
            callback.onError("حدث خطأ غير متوقع: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // -----------------------------------------------------------------
    // OpenRouter - يحتاج مفتاح المستخدم الخاص (اختياري/متقدم)
    // -----------------------------------------------------------------

    private static void sendViaOpenRouter(String apiKey, String systemContext, String userMessage, Callback callback) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(OPENROUTER_ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            JSONArray messages = new JSONArray();
            if (systemContext != null && !systemContext.isEmpty()) {
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", systemContext);
                messages.put(sys);
            }
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", userMessage);
            messages.put(user);

            JSONObject body = new JSONObject();
            body.put("model", OPENROUTER_MODEL);
            body.put("messages", messages);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();

            int status = conn.getResponseCode();
            InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = readStream(is);

            if (status < 200 || status >= 300) {
                callback.onError(friendlyErrorMessage(status));
                return;
            }

            JSONObject json = new JSONObject(responseBody);
            String reply = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            callback.onSuccess(reply.trim());

        } catch (IOException e) {
            callback.onError("تعذر الاتصال بالإنترنت. تأكد من الاتصال وحاول مرة أخرى.");
        } catch (Exception e) {
            callback.onError("حدث خطأ غير متوقع: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String friendlyErrorMessage(int status) {
        if (status == 401) return "مفتاح OpenRouter غير صحيح. تأكد منه في شاشة الإعدادات، أو امسحه لاستخدام الوضع المجاني الافتراضي بدون مفتاح.";
        if (status == 429) return "تم تجاوز الحد المسموح لهذا المفتاح حاليًا. حاول بعد قليل.";
        return "فشل الطلب (" + status + "). حاول مرة أخرى لاحقًا.";
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
