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
 * عميل الذكاء الاصطناعي. المسار المجاني الافتراضي (بدون مفتاح) بيجرب
 * مزوّدين مختلفين تلقائيًا بالترتيب - لو الأول فشل أو كان مزدحمًا، بيجرب
 * الثاني فورًا بدون ما يظهر أي خطأ للمستخدم، فاحتمال "الذكاء الاصطناعي
 * مش شغال خالص" يقل كثيرًا:
 *
 * 1) LLM7.io - endpoint موثّق متوافق مع OpenAI، استخدام مجهول تمامًا
 *    (api_key="unused") - https://docs.llm7.io/quickstart
 * 2) Pollinations.ai - endpoint نصي مجاني بدون مفتاح، موديل "openai-large"
 *    تحديدًا (وليس "openai" العادي المدفوع) - https://text.pollinations.ai
 *
 * لو المستخدم عنده مفتاح OpenRouter خاص بيه (من الإعدادات)، بيُستخدم هو
 * فقط بدل السلسلة المجانية دي بالكامل.
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

    /**
     * يُستدعى من Thread خلفية (مش الـ UI Thread). السلسلة المجانية الافتراضية
     * (بدون مفتاح) بتجرب حتى 4 محاولات قبل ما تستسلم، عشان تقل حالات
     * "الذكاء الاصطناعي مش شغال" الناتجة عن ازدحام لحظي في أي مزوّد واحد:
     * LLM7 → إعادة محاولة LLM7 مرة واحدة (فشل الشبكة غالبًا مؤقت) →
     * Pollinations → إعادة محاولة Pollinations مرة واحدة.
     */
    public static void sendMessage(String apiKey, String systemContext, String userMessage, Callback callback) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            sendViaLlm7(systemContext, userMessage, new Callback() {
                @Override
                public void onSuccess(String reply) {
                    callback.onSuccess(reply);
                }

                @Override
                public void onError(String firstError) {
                    // محاولة ثانية سريعة لنفس المزوّد الأول قبل التبديل - أغلب
                    // أخطاء الشبكة على الموبايل مؤقتة (انقطاع لحظي، تبديل شبكة).
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
                                            callback.onError("تعذر الوصول لأي من خدمات الذكاء الاصطناعي المجانية حاليًا (قد تكون مزدحمة أو الاتصال بالإنترنت غير مستقر). حاول بعد قليل، أو أضف مفتاح OpenRouter الخاص بك من الإعدادات كبديل أكثر ثباتًا.");
                                        }
                                    });
                                }
                            });
                        }
                    });
                }
            });
        } else {
            // تحسين موثوقية: لو مفتاح OpenRouter الخاص بالمستخدم فشل (مفتاح
            // منتهي/غير صالح، أو تجاوز الحد المسموح على الخطة المجانية من
            // OpenRouter نفسها - 20 طلب/دقيقة أو 50 طلب/يوم بدون رصيد
            // مشحون)، لا نوقف المحادثة بخطأ نهائي؛ بدل كده نكمل تلقائيًا
            // على نفس سلسلة المزوّدين المجانيين الافتراضية بدون مفتاح، عشان
            // يفضل المساعد الذكي شغال دايمًا قدر الإمكان.
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

    // -----------------------------------------------------------------
    // المزوّد الأول: LLM7.io
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
