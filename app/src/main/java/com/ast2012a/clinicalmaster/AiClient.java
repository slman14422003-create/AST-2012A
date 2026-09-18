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
 * عميل الذكاء الاصطناعي (Phizyo AI). ترتيب الأولوية عند إرسال أي سؤال:
 *
 * 1) رابط Cloudflare Worker الخاص بالمستخدم (لو أضافه من الإعدادات) -
 *    ووركر بسيط بيستخدم Cloudflare Workers AI مجانًا عبر AI Binding
 *    (بدون أي مفتاح API مطلوب داخل التطبيق نفسه - الووركر هو اللي بيحمل
 *    صلاحية الوصول على حساب Cloudflare بتاع المستخدم). يكفي نشر الووركر
 *    مرة واحدة (ملف worker.js المرفق) ولصق رابطه هنا فقط.
 * 2) مفتاح OpenRouter الخاص بالمستخدم - لو أضافه، ومفيش ووركر أو الووركر فشل.
 * 3) السلسلة المجانية الافتراضية بدون أي إعداد (LLM7 ثم Pollinations،
 *    بمحاولة إعادة واحدة لكل مزوّد) - تُستخدم دائمًا كخط رجوع أخير مهما
 *    كان الإعداد، عشان يفضل المساعد شغال قدر الإمكان حتى لو فشل كل شيء
 *    فوقها.
 */
public class AiClient {

    private static final String LLM7_ENDPOINT = "https://api.llm7.io/v1/chat/completions";
    private static final String LLM7_MODEL = "gpt-4o-mini-2024-07-18";

    private static final String POLLINATIONS_ENDPOINT = "https://text.pollinations.ai/";
    private static final String POLLINATIONS_MODEL = "openai-large";

    private static final String OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final String OPENROUTER_MODEL = "openrouter/free";

    public interface Callback {
        void onSuccess(String reply);
        void onError(String message);
    }

    /** التوقيع القديم (بدون رابط ووركر) - مُبقى عليه حتى ما ينكسر أي
     *  استدعاء قديم؛ يكافئ استدعاء التوقيع الجديد بدون رابط ووركر. */
    public static void sendMessage(String apiKey, String systemContext, String userMessage, Callback callback) {
        sendMessage(apiKey, null, systemContext, userMessage, callback);
    }

    /**
     * يُستدعى من Thread خلفية (مش الـ UI Thread).
     * @param apiKey مفتاح OpenRouter الاختياري.
     * @param workerUrl رابط Cloudflare Worker الاختياري - أولوية أعلى من apiKey لو مُضاف.
     */
    public static void sendMessage(String apiKey, String workerUrl, String systemContext,
                                    String userMessage, Callback callback) {
        if (workerUrl != null && !workerUrl.trim().isEmpty()) {
            sendViaWorker(workerUrl.trim(), systemContext, userMessage, new Callback() {
                @Override
                public void onSuccess(String reply) {
                    callback.onSuccess(reply);
                }

                @Override
                public void onError(String workerError) {
                    // الووركر فشل (رابط غلط، الووركر متوقف، أو تجاوز حد
                    // الاستخدام المجاني اليومي على Cloudflare)؛ نكمل تلقائيًا
                    // على باقي السلسلة بدل ما نوقف المحادثة بخطأ نهائي.
                    sendViaKeyOrFreeChain(apiKey, systemContext, userMessage, callback);
                }
            });
        } else {
            sendViaKeyOrFreeChain(apiKey, systemContext, userMessage, callback);
        }
    }

    private static void sendViaKeyOrFreeChain(String apiKey, String systemContext,
                                               String userMessage, Callback callback) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            sendFreeChain(systemContext, userMessage, callback);
        } else {
            // تحسين موثوقية: لو مفتاح OpenRouter الخاص بالمستخدم فشل (مفتاح
            // منتهي/غير صالح، أو تجاوز الحد المسموح على الخطة المجانية من
            // OpenRouter نفسها)، لا نوقف المحادثة بخطأ نهائي؛ بدل كده نكمل
            // تلقائيًا على نفس سلسلة المزوّدين المجانيين الافتراضية بدون
            // مفتاح، عشان يفضل المساعد الذكي شغال دايمًا قدر الإمكان.
            sendChatCompletionJson(OPENROUTER_ENDPOINT, apiKey.trim(), OPENROUTER_MODEL, systemContext, userMessage,
                    null, new Callback() {
                        @Override
                        public void onSuccess(String reply) {
                            callback.onSuccess(reply);
                        }

                        @Override
                        public void onError(String openRouterError) {
                            sendViaLlm7(systemContext, userMessage, new Callback() {
                                @Override
                                public void onSuccess(String reply) {
                                    callback.onSuccess(reply);
                                }

                                @Override
                                public void onError(String llm7Error) {
                                    sendViaPollinations(systemContext, userMessage, new Callback() {
                                        @Override
                                        public void onSuccess(String reply) {
                                            callback.onSuccess(reply);
                                        }

                                        @Override
                                        public void onError(String finalError) {
                                            callback.onError(openRouterError
                                                    + "\n\nℹ️ جرّبنا أيضًا المزوّدين المجانيين الاحتياطيين ولم ينجح أي منهم حاليًا. تحقّق من صلاحية مفتاحك في إعدادات OpenRouter، أو احذفه من شاشة الإعدادات لاستخدام الوضع المجاني الافتراضي.");
                                        }
                                    });
                                }
                            });
                        }
                    });
        }
    }

    /** السلسلة المجانية الافتراضية بدون أي مفتاح ولا ووركر: حتى 4 محاولات
     *  (LLM7 → إعادة محاولة LLM7 → Pollinations → إعادة محاولة Pollinations)
     *  قبل ما تستسلم، عشان تقل حالات "الذكاء الاصطناعي مش شغال" الناتجة عن
     *  ازدحام لحظي في أي مزوّد واحد. */
    private static void sendFreeChain(String systemContext, String userMessage, Callback callback) {
        sendViaLlm7(systemContext, userMessage, new Callback() {
            @Override
            public void onSuccess(String reply) {
                callback.onSuccess(reply);
            }

            @Override
            public void onError(String firstError) {
                sendViaLlm7(systemContext, userMessage, new Callback() {
                    @Override
                    public void onSuccess(String reply) {
                        callback.onSuccess(reply);
                    }

                    @Override
                    public void onError(String retryError) {
                        sendViaPollinations(systemContext, userMessage, new Callback() {
                            @Override
                            public void onSuccess(String reply) {
                                callback.onSuccess(reply);
                            }

                            @Override
                            public void onError(String secondError) {
                                sendViaPollinations(systemContext, userMessage, new Callback() {
                                    @Override
                                    public void onSuccess(String reply) {
                                        callback.onSuccess(reply);
                                    }

                                    @Override
                                    public void onError(String finalError) {
                                        callback.onError("تعذر الوصول لأي من خدمات الذكاء الاصطناعي المجانية حاليًا (قد تكون مزدحمة أو الاتصال بالإنترنت غير مستقر). حاول بعد قليل، أو أضف رابط Cloudflare Worker أو مفتاح OpenRouter الخاص بك من شاشة الإعدادات كبديل أكثر ثباتًا.");
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
    }

    // -----------------------------------------------------------------
    // Cloudflare Worker (رابط المستخدم الخاص - أولوية أولى)
    // -----------------------------------------------------------------

    /**
     * يرسل POST بصيغة JSON بسيطة {"system": "...", "message": "..."}
     * لرابط الووركر، ويتوقع ردًا بصيغة {"reply": "..."} - نفس العقد
     * المستخدم في نموذج الووركر المرفق (worker.js). العقد بسيط عمدًا حتى
     * "لصق الرابط فقط" يشتغل بدون أي إعداد إضافي داخل التطبيق.
     */
    private static void sendViaWorker(String workerUrl, String systemContext, String userMessage, Callback callback) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(workerUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(45000);
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

    /** يقرأ حقل الخطأ من رد الووركر لو موجود بصيغة {"error": "..."} - العقد
     *  المستخدم في worker.js المرفق عند فشل أي خطوة (Binding مفقود، الموديل
     *  متقاعد، JSON غير صالح...). عرض النص ده مباشرة للمستخدم بيوفّر وقت
     *  تشخيص كبير بدل رسالة عامة مبهمة. */
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
     *  ({"choices":[{"message":{"content": "..."}}]}) لو المستخدم عدّل
     *  الووركر بنفسه ليرجّع هذا الشكل بدلًا منه. */
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

    /** فحص اتصال بسيط بالووركر (تُستخدم من زر "اختبار الاتصال" في شاشة
     *  الإعدادات) - يرسل رسالة تجريبية قصيرة ويرجّع نجاح/فشل مباشرة. */
    public static void testWorker(String workerUrl, Callback callback) {
        sendViaWorker(workerUrl, "أجب بكلمة واحدة فقط للتأكد من عمل الاتصال.", "قل: تم الاتصال بنجاح ✅", callback);
    }

    // -----------------------------------------------------------------
    // المزوّد الأول من السلسلة المجانية: LLM7.io
    // -----------------------------------------------------------------

    private static void sendViaLlm7(String systemContext, String userMessage, Callback callback) {
        sendChatCompletionJson(LLM7_ENDPOINT, "unused", LLM7_MODEL, systemContext, userMessage, "LLM7_FAILED", callback);
    }

    // -----------------------------------------------------------------
    // المزوّد الثاني (احتياطي): Pollinations.ai
    // -----------------------------------------------------------------

    private static void sendViaPollinations(String systemContext, String userMessage, Callback callback) {
        HttpURLConnection conn = null;
        try {
            String encodedPrompt = URLEncoder.encode(userMessage, "UTF-8").replace("+", "%20");
            StringBuilder urlStr = new StringBuilder(POLLINATIONS_ENDPOINT).append(encodedPrompt);
            urlStr.append("?model=").append(POLLINATIONS_MODEL);
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

            if (status < 200 || status >= 300 || responseBody == null || responseBody.trim().isEmpty()) {
                callback.onError("POLLINATIONS_FAILED");
                return;
            }
            callback.onSuccess(responseBody.trim());

        } catch (Exception e) {
            callback.onError("POLLINATIONS_FAILED");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // -----------------------------------------------------------------
    // منطق موحّد لأي مزوّد متوافق مع شكل OpenAI (chat/completions) - يُستخدم
    // لكل من LLM7 وOpenRouter.
    // -----------------------------------------------------------------

    private static void sendChatCompletionJson(String endpoint, String apiKey, String model,
                                                String systemContext, String userMessage,
                                                String internalFailureCode, Callback callback) {
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
                callback.onError(internalFailureCode != null ? internalFailureCode : friendlyErrorMessage(status));
                return;
            }

            JSONObject json = new JSONObject(responseBody);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                callback.onError(internalFailureCode != null ? internalFailureCode : "لم يصل رد صالح من الخدمة. حاول مرة أخرى.");
                return;
            }
            String reply = choices.getJSONObject(0).getJSONObject("message").getString("content");
            if (reply == null || reply.trim().isEmpty()) {
                callback.onError(internalFailureCode != null ? internalFailureCode : "وصل رد فارغ من الخدمة. حاول مرة أخرى.");
                return;
            }
            callback.onSuccess(reply.trim());

        } catch (Exception e) {
            callback.onError(internalFailureCode != null ? internalFailureCode : "تعذر الاتصال بالخدمة. حاول مرة أخرى.");
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
