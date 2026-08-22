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
 * عميل بسيط لواجهة OpenRouter (متوافقة مع OpenAI API) للوصول لنماذج ذكاء
 * اصطناعي مجانية. نستخدم معرّف النموذج "openrouter/free" وهو الموجّه
 * التلقائي الخاص بـ OpenRouter نفسها - بيختار أي نموذج مجاني متاح حاليًا
 * تلقائيًا، فالميزة تفضل شغالة حتى لو تغيّر النموذج المجاني المحدد بمرور
 * الوقت (النماذج المجانية عند مزوّدين زي DeepSeek بتتغيّر بين وقت وآخر).
 *
 * يحتاج المستخدم مفتاح API مجاني خاص بيه من https://openrouter.ai/keys
 * (تسجيل بالإيميل، بدون بطاقة ائتمان) ويُدخله من شاشة الإعدادات.
 */
public class AiClient {

    private static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final String MODEL_ID = "openrouter/free";

    public interface Callback {
        void onSuccess(String reply);
        void onError(String message);
    }

    /**
     * يُستدعى من Thread خلفية (مش الـ UI Thread) - راجع استخدامها في AiAssistantActivity.
     */
    public static void sendMessage(String apiKey, String systemContext, String userMessage, Callback callback) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ENDPOINT);
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
            body.put("model", MODEL_ID);
            body.put("messages", messages);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();

            int status = conn.getResponseCode();
            InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = readStream(is);

            if (status < 200 || status >= 300) {
                callback.onError(friendlyErrorMessage(status, responseBody));
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

    private static String friendlyErrorMessage(int status, String body) {
        if (status == 401) return "مفتاح API غير صحيح. تأكد منه في شاشة الإعدادات.";
        if (status == 429) return "تم تجاوز الحد المسموح للنماذج المجانية حاليًا. حاول بعد قليل.";
        return "فشل الطلب (" + status + "). حاول مرة أخرى لاحقًا.";
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }
}
