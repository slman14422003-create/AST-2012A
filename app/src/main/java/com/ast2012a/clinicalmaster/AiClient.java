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
 *
 * اختيار النموذج (AiModelSelector): كل استدعاء ممكن ياخد اسم نموذج
 * اختياري (model) بيتحدد حسب طبيعة الطلب - تصنيف خفيف/دردشة عادية
 * (نموذج سريع) مقابل مسار إكلينيكي/طبي فعلي (أقوى نموذج متاح للدقة).
 * الحقل ده بيتضاف لجسم JSON بجانب "system" و"message" الحاليين. لو
 * تُرك null، ما بيتضافش أي حقل "model" أصلًا فيفضل السلوك القديم زي ما
 * هو تمامًا (توافق كامل مع الووركر الحالي حتى لو مش قاريه). النداءات
 * القديمة اللي مفيهاش النسخة الجديدة (بدون model) اتسابت كما هي كتوافق
 * خلفي، وبتستدعي داخليًا النسخة الجديدة بـmodel = null.
 *
 * تحديث v9 (متانة الاتصال - إعادة محاولة تلقائية واحدة): كان أي فشل
 * شبكة لحظي (Timeout، انقطاع واي فاي/بيانات لحظي، إلخ) بيوصل مباشرة
 * كخطأ نهائي للمستخدم من أول محاولة، رغم إن نسبة كبيرة من هذه الأخطاء
 * عابرة وتنجح لو اتكررت بعد لحظة بسيطة. دلوقتي sendViaWorker بتعيد
 * المحاولة مرة واحدة تلقائيًا (بعد تأخير قصير) لو فشل الاتصال بالشبكة
 * نفسه *قبل* ما يوصل أي رد من السيرفر أصلًا - لو المحاولة التانية فشلت
 * برضو، يرجع الخطأ الحقيقي زي ما كان. مهم: الإعادة دي لا تحصل أبدًا لو
 * فعليًا وصل رد من السيرفر (حتى لو كان خطأ HTTP أو شكل JSON غير متوقع) -
 * ده مش خطأ شبكة، وإعادة نداء نموذج ذكاء اصطناعي كامل تاني في الحالة دي
 * هدر وقت وتكلفة بلا أي فايدة حقيقية.
 */
public class AiClient {

    /** الرابط الثابت الوحيد لمساعد Phizyo AI. */
    public static final String FIXED_WORKER_URL = "https://little-flower-2b6f.slman14422003.workers.dev/";

    public interface Callback {
        void onSuccess(String reply);
        void onError(String message);
    }

    /** يُستدعى من Thread خلفية (مش الـ UI Thread). بدون تحديد نموذج معيّن -
     *  الووركر يستخدم نموذجه الافتراضي. */
    public static void sendMessage(String systemContext, String userMessage, Callback callback) {
        sendMessage(systemContext, userMessage, null, callback);
    }

    /** نفس sendMessage، مع تحديد اسم نموذج مقترح (AiModelSelector) يُرسل
     *  كحقل "model" اختياري - مرّر null لعدم تحديد أي نموذج (سلوك قديم). */
    public static void sendMessage(String systemContext, String userMessage, String model, Callback callback) {
        sendViaWorker(FIXED_WORKER_URL, systemContext, userMessage, model, callback);
    }

    /** فحص اتصال بسيط بالووركر الثابت (تُستخدم من زر "اختبار الاتصال" في
     *  شاشة الإعدادات) - يرسل رسالة تجريبية قصيرة ويرجّع نجاح/فشل مباشرة. */
    public static void testWorker(Callback callback) {
        sendViaWorker(FIXED_WORKER_URL, "أجب بكلمة واحدة فقط للتأكد من عمل الاتصال.", "قل: تم الاتصال بنجاح ✅", null, callback);
    }

    /**
     * استدعاء تصنيف خفيف (Router) يسبق أي رد فعلي: بيبعت رسالة المستخدم
     * لنفس الووركر مع تعليمات AiPrompts.buildRouterPrompt() بس، ويرجع
     * "SEARCH" أو "CHAT" (أو أي رد غير متوقع بيتعامل معاه الطرف المستدعي
     * كحالة آمنة). القرار هنا من النموذج نفسه - مش قاعدة كلمات مفتاحية
     * ثابتة في الكود - فهو اللي "يقرر" فعلًا هل محتاج يبحث ولا لأ.
     * لازم يُستدعى من Thread خلفية (نفس شرط sendMessage). */
    public static void classifyIntent(String userMessage, Callback callback) {
        classifyIntent(userMessage, null, callback);
    }

    /** نفس التصنيف أعلاه، مع إرفاق سياق المحادثة السابقة (ذاكرة قصيرة
     *  المدى) لو موجود، عشان القرار يفهم إشارات مختصرة بترجع لسياق سابق
     *  في نفس الجلسة بدل ما يحكم على الرسالة بمعزل تام عمّا قبلها.
     *  بيستخدم دايمًا أسرع نموذج متاح (AiModelSelector.forRouter) لأن
     *  المطلوب رد قصير جدًا (سطر أو سطرين) بأقل زمن انتظار ممكن. */
    public static void classifyIntent(String userMessage, String historyContext, Callback callback) {
        sendViaWorker(FIXED_WORKER_URL, AiPrompts.buildRouterPrompt(historyContext), userMessage,
                AiModelSelector.forRouter(), callback);
    }

    /**
     * يرسل POST بصيغة JSON بسيطة {"system": "...", "message": "..."}
     * لرابط الووركر، ويتوقع ردًا بصيغة {"reply": "..."} - نفس العقد
     * المستخدم في worker.js. عند أي فشل بيتم تمرير رسالة الخطأ الحقيقية
     * القادمة من الووركر نفسه (لو موجودة) بدل رسالة عامة مبهمة، عشان
     * تشخيص أي عطل مستقبلي يبقى سريع من داخل التطبيق نفسه.
     *
     * حقل "model" اختياري (لو model != null) بيتضاف لنفس جسم الـJSON -
     * اقتراح اسم نموذج (AiModelSelector) للووركر يستخدمه بدل نموذجه
     * الافتراضي الثابت، لو الووركر بيدعم القراءة منه. إضافة حقل JSON غير
     * معروف لا تكسر أي ووركر حالي (بيتجاهله بأمان)، فده توسيع تراكمي بحت.
     */
    private static void sendViaWorker(String workerUrl, String systemContext, String userMessage, String model, Callback callback) {
        sendViaWorker(workerUrl, systemContext, userMessage, model, callback, true);
    }

    /** عدد المللي ثانية اللي بنستناها قبل إعادة المحاولة التلقائية الوحيدة
     *  بعد فشل اتصال شبكة لحظي - كافي لعبور معظم انقطاعات الشبكة العابرة
     *  (تبديل واي فاي/بيانات، رجّة تغطية لحظية) بدون ما نطوّل انتظار
     *  المستخدم بشكل محسوس. */
    private static final int RETRY_DELAY_MS = 800;

    /** نفس sendViaWorker الأصلية، مع allowRetry بيتحكم في إعادة المحاولة
     *  التلقائية الواحدة (v9 - متانة الاتصال، راجع تعليق v9 فوق تعريف
     *  الكلاس): true في أول نداء دايمًا، وبتنادي نفسها بـfalse لو فشلت
     *  المحاولة الأولى بسبب خطأ شبكة (مش رد فعلي من السيرفر) عشان تمنع أي
     *  محاولة تالتة. */
    private static void sendViaWorker(String workerUrl, String systemContext, String userMessage,
            String model, Callback callback, boolean allowRetry) {
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
            if (model != null && !model.trim().isEmpty()) {
                body.put("model", model.trim());
            }

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();

            // من هنا فصاعدًا فعليًا وصل رد من السيرفر (حتى لو خطأ HTTP) -
            // أي فشل بعد النقطة دي مش خطأ شبكة، فممنوع تُعاد المحاولة عليه.
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
            callback.onSuccess(sanitizeMarkdown(reply.trim()));

        } catch (Exception e) {
            // فشل قبل وصول أي رد فعلي من السيرفر (Timeout، تعذّر فتح
            // الاتصال، انقطاع لحظي...) - نعتبره خطأ شبكة عابر ونعيد
            // المحاولة مرة واحدة بس (لو allowRetry لسه true) قبل ما نبلّغ
            // المستخدم بفشل نهائي.
            if (allowRetry) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                sendViaWorker(workerUrl, systemContext, userMessage, model, callback, false);
                return;
            }
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

    /**
     * طبقة أمان أخيرة على أي رد قادم من الووركر: الشاشة تعرض رد المساعد
     * كنص عادي فقط (TextView.setText) بدون أي تفسير Markdown، فكان أي **
     * أو # أو ``` أو --- يظهر للمستخدم حرفيًا كرمز غريب وسط الكلام بدل ما
     * يتفسر كتنسيق - وده أصل شكوى "الرد فيه ** غريبة" اللي وصلت من
     * المستخدم. AiPrompts.FORMAT_RULES بيوجّه النموذج ما يستخدمش Markdown
     * من الأساس، لكن لو خالف التعليمات (بيحصل أحيانًا مع أي نموذج)، الدالة
     * دي نقطة مرور موحّدة (كل رد فعلي، سواء دردشة أو تأريض أو استكمال، بيعدي
     * من هنا) بتشيل أي رمز Markdown فعليًا قبل ما يوصل للواجهة نهائيًا -
     * بدون ما تغيّر أو تحذف أي كلمة من محتوى الرد نفسه.
     */
    private static String sanitizeMarkdown(String s) {
        if (s == null || s.isEmpty()) return s;
        String t = s;
        // كتل كود ```...``` وbackticks مفردة: تشال الأسوار فقط والمحتوى يفضل زي ما هو.
        t = t.replace("```", "");
        t = t.replace("`", "");
        // سطر فاصل بالكامل من نجوم/شرط/underscore (--- أو *** أو ___).
        t = t.replaceAll("(?m)^[ \\t]*([\\-*_])\\1{2,}[ \\t]*$\\n?", "");
        // عناوين Markdown (# أو ## ... إلخ) في أول السطر - يتشال الرمز ويفضل النص.
        t = t.replaceAll("(?m)^#{1,6}\\s*", "");
        // أي نجوم متبقية (Bold/Italic **نص** أو *نص*) - نشيل الرمز بس ونسيب
        // النص جواه زي ما هو، مفيش داعي نحوّل التنسيق لحاجة تانية.
        t = t.replace("*", "");
        t = t.replace("__", "");
        return t;
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
