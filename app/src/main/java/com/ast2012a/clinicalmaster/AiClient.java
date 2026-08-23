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
 * عميل الذكاء الاصطناعي - له مزوّدان، كلاهما بنفس شكل طلب OpenAI القياسي
 * (chat/completions):
 *
 * 1) LLM7.io (الافتراضي، بدون أي مفتاح أو إعداد): endpoint حقيقي موثّق
 *    ومتوافق مع OpenAI، بيقبل استخدام مجهول تمامًا (api_key = "unused")
 *    بدون تسجيل - راجع https://docs.llm7.io/quickstart. هذا هو المزوّد
 *    الافتراضي لكل المستخدمين تلقائيًا.
 *
 *    ملاحظة سابقة مهمة: كنا نستخدم Pollinations.ai بموديل "openai" وده
 *    كان بيتطلب رصيد مدفوع (Pollen) فعليًا رغم إنه بيرجع HTTP 200، فكان
 *    بيعرض رسالة الخطأ/الحد كأنها رد ذكاء اصطناعي حقيقي - وهو سبب المشكلة
 *    اللي واجهتها. LLM7 بيرجع نفس شكل استجابة OpenAI القياسي (choices[0]
 *    .message.content) فمفيش لبس في قراءة الرد.
 *
 * 2) OpenRouter (اختياري/متقدم): لو المستخدم عايز يجرب نموذج بديل، يقدر
 *    يضيف مفتاح API مجاني خاص بيه من openrouter.ai في شاشة الإعدادات.
 */
public class AiClient {

    private static final String LLM7_ENDPOINT = "https://api.llm7.io/v1/chat/completions";
    private static final String LLM7_MODEL = "gpt-4o-mini-2024-07-18";

    private static final String OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final String OPENROUTER_MODEL = "openrouter/free";

    public interface Callback {
        void onSuccess(String reply);
        void onError(String message);
    }

    /**
     * يُستدعى من Thread خلفية (مش الـ UI Thread). لو apiKey فاضي أو null
     * بيستخدم LLM7 (بدون مفتاح حقيقي) تلقائيًا، وإلا بيستخدم OpenRouter
     * بمفتاح المستخدم.
     */
    public static void sendMessage(String apiKey, String systemContext, String userMessage, Callback callback) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            sendChatCompletion(LLM7_ENDPOINT, "unused", LLM7_MODEL, systemContext, userMessage,
                    "تعذر الوصول لخدمة الذكاء الاصطناعي المجانية حاليًا (قد تكون مزدحمة). حاول بعد قليل، أو أضف مفتاح OpenRouter الخاص بك من الإعدادات كبديل.",
                    callback);
        } else {
            sendChatCompletion(OPENROUTER_ENDPOINT, apiKey.trim(), OPENROUTER_MODEL, systemContext, userMessage,
                    null, callback);
        }
    }

    /**
     * منطق موحّد لأي مزوّد متوافق مع شكل OpenAI (chat/completions) -
     * يُستخدم لكل من LLM7 وOpenRouter بنفس الكود بالضبط.
     */
    private static void sendChatCompletion(String endpoint, String apiKey, String model,
                                            String systemContext, String userMessage,
                                            String customFreeProviderErrorMsg, Callback callback) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(45000);
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
            body.put("model", model);
            body.put("messages", messages);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();

            int status = conn.getResponseCode();
            InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = readStream(is);

            if (status < 200 || status >= 300) {
                callback.onError(customFreeProviderErrorMsg != null
                        ? customFreeProviderErrorMsg
                        : friendlyErrorMessage(status));
                return;
            }

            JSONObject json = new JSONObject(responseBody);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                callback.onError("لم يصل رد صالح من الخدمة. حاول مرة أخرى.");
                return;
            }
            String reply = choices.getJSONObject(0).getJSONObject("message").getString("content");
            if (reply == null || reply.trim().isEmpty()) {
                callback.onError("وصل رد فارغ من الخدمة. حاول مرة أخرى.");
                return;
            }
            callback.onSuccess(reply.trim());

        } catch (IOException e) {
            callback.onError("تعذر الاتصال بالإنترنت. تأكد من الاتصال وحاول مرة أخرى.");
        } catch (Exception e) {
            callback.onError("حدث خطأ غير متوقع أثناء قراءة رد الخدمة. حاول مرة أخرى.");
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
