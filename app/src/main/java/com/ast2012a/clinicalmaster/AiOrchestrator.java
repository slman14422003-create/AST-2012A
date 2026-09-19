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
        AiClient.sendMessage(AiPrompts.buildChatSystemPrompt(ctx), text, new AiClient.Callback() {
            @Override public void onSuccess(String reply) { callback.onChatReply(reply); }
            @Override public void onError(String message) { callback.onError(message); }
        });
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
                reply += "\n\nℹ️ توجد " + (direct.items.size() - 1) + " حالة أخرى مطابقة أيضًا في القاعدة يمكن مراجعتها من شاشة البحث الرئيسية.";
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

        AiClient.sendMessage(systemPromptToUse, text, new AiClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                String sourceLabel;
                String sourceUrl = null;
                String externalTitle = physio != null ? physio.title : (wiki != null ? wiki.title : null);
                String externalUrl = physio != null ? physio.sourceUrl : (wiki != null ? wiki.sourceUrl : null);
                String externalName = physio != null ? "Physiopedia" : "ويكيبيديا";
                if (groundedCount > 0 && externalTitle != null) {
                    sourceLabel = "⚠️ إجابة تكميلية عامة (لا يوجد تطابق مباشر) - بروتوكولات قريبة (" + groundedCount + ") + " + externalName + ": " + externalTitle;
                    sourceUrl = externalUrl;
                } else if (groundedCount > 0) {
                    sourceLabel = "⚠️ إجابة تكميلية عامة - أقرب بروتوكولات في القاعدة (" + groundedCount + ")، بدون تطابق مباشر مؤكد";
                } else if (externalTitle != null) {
                    sourceLabel = "⚠️ إجابة عامة من " + externalName + " (خارج قاعدة بيانات الجهاز): " + externalTitle;
                    sourceUrl = externalUrl;
                } else {
                    sourceLabel = "⚠️ إجابة عامة من معرفة النموذج (بدون مصدر موثّق من الجهاز أو المصادر الخارجية)";
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
