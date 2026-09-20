package com.ast2012a.clinicalmaster;

import android.content.Context;

import java.util.Locale;

/**
 * المسار الموحّد لأي سؤال يُرسل لـ Phizyo AI، سواء من شاشة المحادثة
 * الكاملة (AiAssistantActivity) أو من "اسأل الذكاء الاصطناعي" السريع في
 * شاشة البحث الرئيسية (MainActivity). كان في السابق نسختين شبه متطابقتين
 * من نفس منطق البحث/التأريض مكرّرتين في الملفين - دُمجتا هنا في مكان واحد.
 *
 * التغيير الجوهري (v2): "طريقة البحث القديمة" كانت تشغّل قاعدة بيانات
 * الجهاز + Physiopedia + ويكيبيديا تلقائيًا مع كل رسالة بدون استثناء -
 * حتى لو كانت مجرد تحية أو دردشة عادية. دلوقتي في خطوة تصنيف أولى وخفيفة
 * (AiClient.classifyIntent) يقرر فيها النموذج نفسه - مش قاعدة كلمات
 * مفتاحية ثابتة بالكود - هل الرسالة محتاجة بحث/تأريض فعلًا ولا لأ:
 * - لو قرر "CHAT": يرد مباشرة بأسلوب طبيعي بدون أي بحث ولا شارة مصدر.
 * - لو قرر "SEARCH" (أو فشل التصنيف نفسه): يكمل بنفس خطوات التأريض
 *   الأصلية (قاعدة بيانات الجهاز أولًا، ثم Physiopedia/ويكيبيديا كخلفية).
 */
public final class AiOrchestrator {

    private AiOrchestrator() {}

    /** يُستدعى دايمًا من Thread خلفية (نفس شرط AiClient.sendMessage). */
    public interface ResultCallback {
        /** رد دردشة طبيعية - بدون مصدر، بدون بحث. */
        void onChatReply(String reply);
        /** رد مبني على تأريض (قاعدة الجهاز و/أو Physiopedia/ويكيبيديا). */
        void onGroundedReply(String reply, String sourceLabel, String sourceUrl);
        void onError(String message);
    }

    /** تحديثات اختيارية لمراحل التقدّم (لعرضها كمؤشر "يكتب..." في الواجهة). */
    public interface StageListener {
        /** بيفهم قصد الرسالة (تصنيف خفيف قبل أي رد). */
        void onClassifying();
        /** بيبحث في قاعدة بيانات الجهاز/Physiopedia/ويكيبيديا. */
        void onSearching();
        /** بيصيغ الرد النهائي. */
        void onThinking();
    }

    public static void answer(Context ctx, String text, StageListener stages, ResultCallback callback) {
        if (stages != null) stages.onClassifying();

        AiClient.classifyIntent(text, new AiClient.Callback() {
            @Override
            public void onSuccess(String decision) {
                if (needsSearch(decision)) {
                    runGrounded(ctx, text, stages, callback);
                } else {
                    runChat(ctx, text, stages, callback);
                }
            }

            @Override
            public void onError(String message) {
                // فشل التصنيف نفسه (مشكلة شبكة مثلًا) - نرجع للسلوك الآمن
                // الأصلي (تأريض كامل) بدل ما نوقف الرد على المستخدم.
                runGrounded(ctx, text, stages, callback);
            }
        });
    }

    private static boolean needsSearch(String decision) {
        if (decision == null) return true;
        String d = decision.trim().toUpperCase(Locale.ROOT);
        if (d.contains("CHAT") && !d.contains("SEARCH")) return false;
        return true;
    }

    private static void runChat(Context ctx, String text, StageListener stages, ResultCallback callback) {
        if (stages != null) stages.onThinking();
        sendWithAutoContinue(AiPrompts.buildChatSystemPrompt(ctx), text, text, "",
                0, new AiClient.Callback() {
            @Override public void onSuccess(String reply) { callback.onChatReply(reply); }
            @Override public void onError(String message) { callback.onError(message); }
        });
    }

    // ================================================================
    // إكمال تلقائي للردود المقطوعة: أحيانًا الووركر/النموذج بيوقف رده في
    // المنتصف (حد أقصى لعدد الكلمات في الطرف التاني، أو انقطاع شبكة لحظي)
    // فيوصل للمستخدم نصف إجابة بس. بدل ما نعرض الرد الناقص زي ما هو،
    // بنفحصه بفحص بسيط (looksTruncated) ولو بان ناقص بنطلب من نفس النموذج
    // يكمّل بالضبط من حيث وقف - وبندمج النتيجتين قبل ما نرجّع الرد النهائي
    // للواجهة. الحد الأقصى MAX_CONTINUATIONS محاولات إضافية بس عشان ما
    // يفضلش يلف لو ظل الرد "يبدو" ناقصًا بسبب طبيعة المحتوى نفسه.
    // ================================================================
    private static final int MAX_CONTINUATIONS = 2;

    private static void sendWithAutoContinue(String systemContext, String userMessage,
            String originalQuestion, String accumulated, int attempt, AiClient.Callback finalCallback) {
        AiClient.sendMessage(systemContext, userMessage, new AiClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                String combined = accumulated.isEmpty() ? reply : accumulated + reply;
                if (attempt < MAX_CONTINUATIONS && looksTruncated(combined)) {
                    String continueSystem = systemContext + "\n\n---\nملحوظة مهمة: هذا استكمال " +
                            "لرد سابق على نفس السؤال الأصلي (\"" + originalQuestion + "\") انقطع " +
                            "في المنتصف. فيما يلي آخر جزء منه فعلًا، أكمل منه مباشرة بدون تكرار " +
                            "أي كلمة منه ولا أي مقدمة جديدة، فقط الجزء الناقص لحد ما تخلص الفكرة " +
                            "بالكامل:\n\"\"\"\n" + lastChars(combined, 700) + "\n\"\"\"";
                    sendWithAutoContinue(continueSystem, "أكمل من حيث توقفت بالضبط.",
                            originalQuestion, combined, attempt + 1, finalCallback);
                } else {
                    finalCallback.onSuccess(combined);
                }
            }

            @Override
            public void onError(String message) {
                // لو فشلت محاولة الإكمال بعد ما نجح جزء أول، الأفضل نرجّع
                // الجزء المتاح بدل ما نضيّع رد جزئي مفيد على المستخدم.
                if (!accumulated.isEmpty()) {
                    finalCallback.onSuccess(accumulated);
                } else {
                    finalCallback.onError(message);
                }
            }
        });
    }

    /** فحص تقريبي بسيط: رد طويل نسبيًا (فوق 200 حرف) وينتهي بحرف/رقم عادي
     *  بدون أي علامة ترقيم ختامية، أو فيه عدد فردي من ``` (كتلة كود
     *  مفتوحة ولم تُغلق) - في الحالتين الأرجح إن الرد اتقطع في نص الكلام. */
    private static boolean looksTruncated(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < 200) return false;
        int fenceCount = 0;
        int idx = 0;
        while ((idx = t.indexOf("```", idx)) != -1) { fenceCount++; idx += 3; }
        if (fenceCount % 2 != 0) return true;
        char last = t.charAt(t.length() - 1);
        return Character.isLetterOrDigit(last);
    }

    private static String lastChars(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private static void runGrounded(Context ctx, String text, StageListener stages, ResultCallback callback) {
        if (stages != null) stages.onSearching();

        // المرحلة صفر: هل يوجد تطابق مباشر وواثق في قاعدة بيانات الجهاز؟
        // لو أه، نجاوب فورًا من البيانات الموثقة نفسها - بدون إنترنت وبدون
        // أي نموذج ذكاء اصطناعي خارجي - إجابة مضمونة الدقة 100%.
        DataManager.SearchResult direct = DataManager.search(text, DataManager.loadBuiltinDatabase(ctx));
        if (!direct.items.isEmpty() && !direct.isFallback) {
            CaseItem top = direct.items.get(0);
            String reply = DataManager.buildLocalAnswer(top);
            if (direct.items.size() > 1) {
                reply += "\n\nتوجد " + (direct.items.size() - 1) + " حالة أخرى مطابقة أيضًا في القاعدة يمكن مراجعتها من شاشة البحث الرئيسية.";
            }
            callback.onGroundedReply(reply, "قاعدة بيانات الجهاز الموثقة (إجابة فورية بدون إنترنت)", null);
            return;
        }

        // المرحلة الأولى: تأريض محلي أوسع (تطابق جزئي/تقريبي) - بروتوكولات
        // موثقة من قاعدة بيانات الجهاز تُستخدم كخلفية للنموذج بدل إجابة مباشرة.
        DataManager.GroundingResult grounding = DataManager.buildGroundingContext(ctx, text, 3);

        // المرحلة الثانية: تأريض خارجي من مصادر موثوقة - نجرّب أولًا
        // Physiopedia (مرجع متخصص في العلاج الطبيعي، مراجَع من أخصائيين)،
        // ولو مفيش نتيجة نرجع تلقائيًا لموسوعة ويكيبيديا العامة كبديل.
        PhysiopediaClient.Result physio = PhysiopediaClient.search(text);
        WikipediaClient.Result wiki = physio == null ? WikipediaClient.search(text) : null;

        StringBuilder extraContext = new StringBuilder();
        if (grounding != null) {
            extraContext.append("بروتوكولات موثقة ذات صلة من قاعدة بيانات الجهاز:\n")
                    .append(grounding.contextText).append("\n\n");
        }
        if (physio != null) {
            extraContext.append("خلفية معرفية متخصصة من Physiopedia (مرجع علاج طبيعي، مقالة: ")
                    .append(physio.title).append("):\n").append(physio.extract);
        } else if (wiki != null) {
            extraContext.append("خلفية معرفية عامة من ويكيبيديا (مقالة: ").append(wiki.title).append("):\n")
                    .append(wiki.extract);
        }

        final String systemPromptToUse = AiPrompts.buildSystemPrompt(ctx,
                extraContext.length() > 0 ? extraContext.toString() : null);
        final int groundedCount = grounding != null ? grounding.caseCount : 0;

        if (stages != null) stages.onThinking();

        sendWithAutoContinue(systemPromptToUse, text, text, "", 0, new AiClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                String sourceLabel;
                String sourceUrl = null;
                String externalTitle = physio != null ? physio.title : (wiki != null ? wiki.title : null);
                String externalUrl = physio != null ? physio.sourceUrl : (wiki != null ? wiki.sourceUrl : null);
                String externalName = physio != null ? "Physiopedia" : "ويكيبيديا";
                if (groundedCount > 0 && externalTitle != null) {
                    sourceLabel = "إجابة تكميلية عامة (لا يوجد تطابق مباشر) - بروتوكولات قريبة (" + groundedCount + ") + " + externalName + ": " + externalTitle;
                    sourceUrl = externalUrl;
                } else if (groundedCount > 0) {
                    sourceLabel = "إجابة تكميلية عامة - أقرب بروتوكولات في القاعدة (" + groundedCount + ")، بدون تطابق مباشر مؤكد";
                } else if (externalTitle != null) {
                    sourceLabel = "إجابة عامة من " + externalName + " (خارج قاعدة بيانات الجهاز): " + externalTitle;
                    sourceUrl = externalUrl;
                } else {
                    sourceLabel = "إجابة عامة من معرفة النموذج (بدون مصدر موثّق من الجهاز أو المصادر الخارجية)";
                }
                callback.onGroundedReply(reply, sourceLabel, sourceUrl);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }
}
