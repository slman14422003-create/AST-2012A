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
 *   الأصلية (قاعدة بيانات الجهاز أولًا، ثم Physiopedia كخلفية).
 *
 * تحديث v3: اتشالت ويكيبيديا نهائيًا من مسار البحث - المصدر الخارجي
 * الوحيد المسموح بيه دلوقتي هو Physiopedia (مرجع علاج طبيعي متخصص فقط)،
 * وبدون أي بحث احتياطي عام خارج نطاق العلاج الطبيعي لو مفيش نتيجة منه.
 *
 * تحديث v4 (ذاكرة المحادثة): answer() بقى بياخد كمان سجل آخر رسائل نفس
 * الجلسة (List&lt;ChatMessage&gt;)، بيتحول لسياق نصي (AiPrompts.
 * buildHistoryContext) ويترفق مع كل استدعاء (تصنيف، دردشة، أو تأريض)
 * عشان النموذج يفهم إشارات مختصرة زي "بدي مريض" بالرجوع لآخر ما قيل في
 * نفس المحادثة، بدل ما يعامل كل رسالة كأنها معزولة تمامًا.
 */
public final class AiOrchestrator {

    private AiOrchestrator() {}

    /** يُستدعى دايمًا من Thread خلفية (نفس شرط AiClient.sendMessage). */
    public interface ResultCallback {
        /** رد دردشة طبيعية - بدون مصدر، بدون بحث. */
        void onChatReply(String reply);
        /** رد مبني على تأريض (قاعدة الجهاز و/أو Physiopedia). */
        void onGroundedReply(String reply, String sourceLabel, String sourceUrl);
        void onError(String message);
    }

    /** تحديثات اختيارية لمراحل التقدّم (لعرضها كمؤشر "يكتب..." في الواجهة). */
    public interface StageListener {
        /** بيفهم قصد الرسالة (تصنيف خفيف قبل أي رد). */
        void onClassifying();
        /** بيبحث في قاعدة بيانات الجهاز/Physiopedia. */
        void onSearching();
        /** بيصيغ الرد النهائي. */
        void onThinking();
    }

    /** توافقًا مع النداءات القديمة اللي مالهاش سياق مريض محدد ولا سجل
     *  محادثة (بحث الشاشة الرئيسية، أو محادثة عامة مش منطلقة من ملف مريض). */
    public static void answer(Context ctx, String text, StageListener stages, ResultCallback callback) {
        answer(ctx, text, null, null, stages, callback);
    }

    /** توافقًا مع النداءات اللي فيها مريض محدد بس بدون سجل محادثة سابق. */
    public static void answer(Context ctx, String text, String patientId,
            StageListener stages, ResultCallback callback) {
        answer(ctx, text, patientId, null, stages, callback);
    }

    /**
     * @param patientId لو السؤال منطلق من ملف مريض محدد (زر "اسأل Phizyo AI
     *                  عن هذا المريض")، مرّر معرّفه هنا عشان يُرفَق ملخصه
     *                  المُعرَّف (بدون اسم أو هاتف) كخلفية معرفية مباشرة -
     *                  بدل ما يعتمد المساعد على تخمين أو سؤال المستخدم عن
     *                  تفاصيل موجودة بالفعل في ملف المريض. مرّر null لو
     *                  السؤال عام (غير مرتبط بمريض بعينه).
     * @param history   آخر رسائل نفس جلسة المحادثة (بترتيبها الزمني، قبل
     *                  الرسالة الحالية) عشان تُستخدم كذاكرة قصيرة المدى -
     *                  مرّر null أو قائمة فاضية لو مفيش سجل سابق (محادثة
     *                  جديدة أو مسار بلا ذاكرة زي البحث السريع).
     */
    public static void answer(Context ctx, String text, String patientId,
            java.util.List<ChatMessage> history, StageListener stages, ResultCallback callback) {
        if (stages != null) stages.onClassifying();
        final String historyContext = AiPrompts.buildHistoryContext(history);

        AiClient.classifyIntent(text, historyContext, new AiClient.Callback() {
            @Override
            public void onSuccess(String decision) {
                if (needsSearch(decision)) {
                    // v5: المصنّف بقى ممكن يرجع مع SEARCH مصطلح بحث إنجليزي
                    // جاهز لـ Physiopedia (AiPrompts.extractSearchTerm) - لو
                    // مش موجود (رد قديم الشكل أو فضّل يسيبه فاضي)، runGrounded
                    // بترجع تلقائيًا لنص السؤال الأصلي زي السلوك القديم.
                    String physioTermHint = AiPrompts.extractSearchTerm(decision);
                    runGrounded(ctx, text, patientId, historyContext, physioTermHint, stages, callback);
                } else {
                    runChat(ctx, text, historyContext, stages, callback);
                }
            }

            @Override
            public void onError(String message) {
                // فشل التصنيف نفسه (مشكلة شبكة مثلًا) - نرجع للسلوك الآمن
                // الأصلي (تأريض كامل) بدل ما نوقف الرد على المستخدم. مفيش
                // مصطلح بحث جاهز هنا فـrunGrounded هتستخدم نص السؤال كما هو.
                runGrounded(ctx, text, patientId, historyContext, null, stages, callback);
            }
        });
    }

    private static boolean needsSearch(String decision) {
        if (decision == null) return true;
        String d = decision.trim().toUpperCase(Locale.ROOT);
        if (d.contains("CHAT") && !d.contains("SEARCH")) return false;
        return true;
    }

    private static void runChat(Context ctx, String text, String historyContext,
            StageListener stages, ResultCallback callback) {
        if (stages != null) stages.onThinking();
        // فلتر اختيار النموذج: سريع افتراضيًا للدردشة العادية، إلا لو
        // الرسالة نفسها فيها مضمون طبي/علاجي فعلي رغم تصنيفها CHAT - طبقة
        // أمان إضافية لو أخطأ التصنيف (راجع AiModelSelector.forChat).
        String chatModel = AiModelSelector.forChat(text);
        sendWithAutoContinue(AiPrompts.buildChatSystemPrompt(ctx, historyContext), text, text, "",
                chatModel, 0, new AiClient.Callback() {
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
    //
    // إصلاح مهم (تكرار الرد بالكامل أو جزء منه): كان بيحصل إن الرد يوصل
    // للمستخدم فيه فقرات/عناوين مكررة. السبب: 1) looksTruncated القديمة
    // كانت بتعتبر أي رد عربي عادي "ناقص" لمجرد إنه بينتهي بحرف عادي (مفيش
    // نقطة إجبارية بنهاية الجملة العربية زي الإنجليزي) - فكانت بتطلب
    // استكمال حتى لو الرد كان مكتمل فعلًا. 2) الأهم: النموذج، لما يُطلب
    // منه "أكمل من حيث توقفت" مع إرفاق آخر جزء من الرد السابق كسياق، كان
    // غالبًا بيبدأ رده الجديد بإعادة نفس الجزء المُرفَق (صدى/echo) قبل ما
    // يكمّل فعليًا - أحيانًا القسم الأخير بالكامل (عنوان + نقاطه) مش
    // بالضرورة الرد كله من أوله. الكشف القديم (looksLikeDuplicateRestart)
    // كان بيقارن بداية محاولة الاستكمال ببداية *الرد الأصلي كله* بس - فكان
    // يمسك حالة "إعادة الرد من الصفر" فقط، ومش يمسك حالة "إعادة القسم
    // الأخير اللي اتبعت كسياق" اللي هي فعليًا اللي كانت بتحصل. الإصلاح
    // الحالي بيغطي الحالتين معًا:
    // (أ) لسه بنرفض المحاولة بالكامل لو بدأت بنفس افتتاحية الرد الأصلي
    //     (looksLikeDuplicateRestart) - إعادة صياغة كاملة من الصفر.
    // (ب) الجديد: findEchoOverlapRawLength بتقارن بداية رد الاستكمال بآخر
    //     جزء فعليًا اتبعت للنموذج كسياق (contextTailGiven) - لو لقت تطابق
    //     (بعد تطبيع المسافات) بطول ذو دلالة، بتشيل التداخل المكرر ده من
    //     أول الرد الجديد قبل ما تلزقه على accumulated، بدل ما تلزق نسخة
    //     تانية من نفس الكلام. لو بعد الشيل مفيش محتوى جديد فعلي، نوقف
    //     بآخر رد مكتمل بدل ما نطلب استكمال تاني مالوش لازمة.
    //
    // إصلاح إضافي مهم (كلمة مقطوعة + تكرار مُعاد الصياغة بكلام مختلف):
    // ظهرت فعليًا حالة أوضح من مجرد تكرار حرفي: 1) القطع كان بيحصل أحيانًا
    // في نص كلمة عربية (lastChars القديمة بتقطع بعدد حروف خام من غير أي
    // وعي بحدود الكلمة) - فالنموذج التاني كان بياخد كسياق كلمة مبتورة
    // ("مست" بدل "مستقل" مثلًا)، وبدل ما يكملها كان أحيانًا يتجاهلها
    // ويبدأ كلام جديد يُلزق عليها مباشرة بلا مسافة (زي "توجيهالفرق")، أو
    // حتى ينتج كلمة بلغة تانية بالغلط بدل تكملة الكلمة العربية المقطوعة.
    // 2) فحص الصدى (findEchoOverlapRawLength) بيقارن بس مع آخر جزء اتبعت
    //    (contextTailGiven) - فلو النموذج في محاولة استكمال أعاد فكرة/جملة
    //    سبق قالها في جزء أبكر من الرد (مش عند حد التداخل المباشر)، بصياغة
    //    مطابقة أو شبه مطابقة، الفحص القديم ما كانش يمسكها فتوصل للمستخدم
    //    فقرة متكررة فعليًا (زي "الفرق الرئيسي بين TENS و EMS هو الغرض من
    //    كل منهما" اللي اتكررت حرفيًا تقريبًا مرتين في نفس الرد).
    // الإصلاح: (أ) lastCharsAtWordBoundary بتقتطع دايمًا من أول حد كلمة
    // كامل (مسافة)، مش من نص كلمة، فسياق الاستكمال يوصل نظيف. (ب)
    // trimTrailingPartialWord بتشيل أي كلمة مبتورة من نهاية accumulated
    // نفسها (مش بس من نسخة السياق) قبل أي استكمال أو أي رد نهائي - فالنص
    // اللي يوصل للمستخدم فعليًا ما بينتهيش أبدًا في نص كلمة مقطوعة، حتى لو
    // خلصت محاولات الاستكمال. (ج) stripAlreadyCoveredContent بتفحص أي
    // سطر/جملة في محتوى الاستكمال الجديد مقابل الرد المتراكم *كله* (مش بس
    // آخر جزء اتبعت كسياق) - لو لقت نفس الجملة تقريبًا موجودة فعلًا، بتشيلها
    // قبل ما تتلزق، بدل ما توصل للمستخدم فقرة معادة.
    // ================================================================
    // إصلاح مهم (رد بيتقطع نهائي وما يكملش): كان الحد الأقصى 2 محاولة
    // استكمال بس (يعني 3 نداءات شبكة كحد أقصى للسؤال الواحد) - كافي لرد
    // قصير/متوسط، لكن مش كافي لبروتوكول إكلينيكي مفصّل (مقارنة كاملة بين
    // TENS/EMS بكل تفاصيلها مثلًا) لو كل نداء بيرجع جزء محدود بسبب حد
    // طول الرد من السيرفر - فكان الرد يوصل للمستخدم ناقص وواقف في نص
    // الفكرة رغم إن كل الدفاعات (منع التكرار/الصدى) شغالة صح. رفعنا الحد
    // لـ 6 (يعني لحد 7 نداءات) عشان تدي مساحة كافية لردود طويلة فعلًا
    // توصل لنهاية طبيعية - مع إن دفاعات التكرار (findEchoOverlapRawLength +
    // stripAlreadyCoveredContent) هي اللي بتوقف الاستكمال فعليًا وطبيعيًا
    // أول ما مفيش محتوى جديد حقيقي يتضاف (newContent فاضي)، مش عدد
    // المحاولات نفسه - فرفع الحد مش بيخلي الرد "يلف" أكتر لو خلص فعلًا،
    // بس بيدي مساحة كافية لو لسه فيه فكرة ناقصة محتاجة تتقال.
    private static final int MAX_CONTINUATIONS = 6;

    // ================================================================
    // إصلاح مهم (جملة أخيرة بتتكرر مرتين حتى بدون أي استكمال): كل دفاعات
    // التكرار فوق (findEchoOverlapRawLength / stripAlreadyCoveredContent)
    // بتتفعّل بس لو attempt > 0 - يعني بتقارن بس بين جولات استكمال منفصلة.
    // لكن ظهرت حالات فعلية إن النموذج بيكرر جملة/فقرة كاملة جوه *نفس* الرد
    // الواحد من نفس النداء (attempt == 0 نفسه)، من غير ما يحصل أي استكمال
    // أصلًا - زي جملة "يجب أن يتم استخدام نمط... تحت إشراف أخصائي علاج
    // طبيعي مؤهل..." اللي كانت بتتكرر حرفيًا مرتين متتاليتين آخر الرد. ده
    // مش سببه منطق الاستكمال هنا - النموذج نفسه بيكرر جزء من كلامه أحيانًا
    // - فبنعمل تمشيطة أخيرة على الرد النهائي بالكامل (مهما كان عدد جولات
    // الاستكمال، حتى لو صفر) قبل ما يوصل لأي finalCallback.onSuccess، بتشيل
    // أي سطر/جملة سبق ظهورها فعليًا بنفس الصياغة تقريبًا في مكان أبكر من
    // نفس الرد - بنفس منطق stripDuplicateSentencesInLine لكن ماشي على كل
    // الرد مرة واحدة من الأول للآخر بدل حدود جولات الاستكمال بس.
    // ================================================================
    private static String finalizeReply(String text) {
        if (text == null || text.trim().isEmpty()) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        StringBuilder seenSoFar = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String kept = stripDuplicateSentencesInLine(lines[i], normalizeForCompare(seenSoFar.toString()));
            if (i > 0) result.append('\n');
            result.append(kept);
            if (!kept.trim().isEmpty()) seenSoFar.append(' ').append(kept);
        }
        return result.toString().trim();
    }

    private static void sendWithAutoContinue(String systemContext, String userMessage,
            String originalQuestion, String accumulated, String model, int attempt, AiClient.Callback finalCallback) {
        sendWithAutoContinue(systemContext, userMessage, originalQuestion, accumulated, model, null, null, attempt, finalCallback);
    }

    private static void sendWithAutoContinue(String systemContext, String userMessage,
            String originalQuestion, String accumulated, String model, String firstChunk, String contextTailGiven,
            int attempt, AiClient.Callback finalCallback) {
        AiClient.sendMessage(systemContext, userMessage, model, new AiClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                String effectiveFirstChunk = firstChunk != null ? firstChunk : reply;

                // دفاع أول: رفض المحاولة بالكامل لو النموذج أعاد الإجابة من
                // الصفر بنفس افتتاحية الرد الأصلي (إعادة صياغة كاملة).
                if (attempt > 0 && looksLikeDuplicateRestart(effectiveFirstChunk, reply)) {
                    finalCallback.onSuccess(finalizeReply(accumulated));
                    return;
                }

                // دفاع تاني: شيل أي صدى/تكرار لآخر جزء اتبعت كسياق استكمال
                // من أول الرد الجديد، قبل ما نلزقه على accumulated.
                String newContent = reply;
                if (attempt > 0 && contextTailGiven != null) {
                    int overlapRawEnd = findEchoOverlapRawLength(contextTailGiven, reply);
                    if (overlapRawEnd > 0) {
                        newContent = reply.substring(overlapRawEnd).trim();
                    }
                }

                // دفاع تالت: شيل أي سطر/جملة في المحتوى الجديد سبق فعليًا
                // ذكرها في أي جزء من الرد المتراكم كله - مش بس عند حد
                // التداخل المباشر زي الدفاع التاني. ده اللي بيمسك حالة
                // "إعادة فكرة سبق قولها بصياغة قريبة" اللي كانت بتفلت من
                // الفحصين التانيين.
                if (attempt > 0) {
                    newContent = stripAlreadyCoveredContent(accumulated, newContent).trim();
                }

                // لو بعد شيل الصدى والتكرار مفيش أي محتوى جديد فعليًا،
                // النموذج فعليًا كرر نفس الكلام من غير ما يضيف حاجة - نوقف
                // هنا بدل ما نطلب استكمال تاني لنفس الحاجة اللي هتتكرر
                // تاني الأرجح.
                if (attempt > 0 && newContent.trim().isEmpty()) {
                    finalCallback.onSuccess(finalizeReply(accumulated));
                    return;
                }

                // إصلاح مهم (كلمتين بيتلزقوا في بعض من غير مسافة): accumulated
                // بيوصل هنا من الجولة اللي فاتت بعد ما trimTrailingPartialWord
                // قصّته لحد آخر حد كلمة كامل و.trim() شالت أي مسافة زيادة في
                // آخره، وnewContent كمان بيتعمله .trim() في أكتر من مكان فوق -
                // يعني التلزيق المباشر (من غير فاصل) كان بيلزق آخر كلمة في
                // accumulated بأول كلمة في newContent مباشرة بلا مسافة بينهم
                // (زي "العضليةهذه" بدل "العضلية هذه") - ده أصل شكوى "الرد فيه
                // كلمات ملزوقة في بعض غريبة" اللي وصلت من المستخدم. إضافة
                // مسافة واحدة بينهم هنا كافية لأن الطرفين اتقصّوا لحد كلمة
                // كاملة بالفعل، فمفيش خطر تكرار مسافة أو قطع كلمة.
                String combined = accumulated.isEmpty() ? newContent
                        : (newContent.isEmpty() ? accumulated : accumulated + " " + newContent);

                boolean appearsTruncated = looksTruncated(combined);
                boolean willContinue = attempt < MAX_CONTINUATIONS && appearsTruncated;

                // نشيل أي كلمة مبتورة من نهاية الرد المتراكم بس لو فعلًا
                // هنطلب استكمال تاني - عشان النموذج التالي ياخد سياق نظيف
                // يبدأ بكلمة كاملة. لو ده آخر شيء (خلصت المحاولات المتاحة
                // أو الرد مش ناقص أصلًا)، ما نمسحش آخر كلمة ممكن تكون
                // فعلًا كاملة - إصلاح سابق كان بيمسحها هنا كمان، فكان بيخلي
                // الرد النهائي يوصل للمستخدم أقصر مما هو فعلًا في الحالات
                // النادرة اللي المحاولات بتخلص فيها - عكس المطلوب تمامًا
                // (رد كامل من غير أي قطع أو حذف).
                if (willContinue) {
                    combined = trimTrailingPartialWord(combined);
                }

                if (willContinue) {
                    String tailForNext = lastCharsAtWordBoundary(combined, 700);
                    String continueSystem = systemContext + "\n\n---\nملحوظة مهمة: هذا استكمال " +
                            "لرد سابق على نفس السؤال الأصلي (\"" + originalQuestion + "\") انقطع " +
                            "في المنتصف. فيما يلي آخر جزء منه فعلًا، أكمل منه مباشرة بدون تكرار " +
                            "أي كلمة منه ولا أي مقدمة جديدة، فقط الجزء الناقص لحد ما تخلص الفكرة " +
                            "بالكامل. ممنوع تعيد كتابة أي كلمة من المقطع المقتبس ده تاني في ردك، " +
                            "وممنوع كمان تعيد أي فكرة أو نقطة سبق ذكرها في الإجابة كلها من أولها " +
                            "(مش بس المقطع المقتبس ده) حتى لو بصياغة مختلفة أو ترتيب مختلف - لو " +
                            "حسّيت إنك هتكرر فكرة سبق قولها، انتقل مباشرة للنقطة الجديدة اللي لسه " +
                            "ما اتقالتش، أو اختم ردك لو مفيش حاجة جديدة فعلًا تضيفها:" +
                            "\n\"\"\"\n" + tailForNext + "\n\"\"\"";
                    sendWithAutoContinue(continueSystem, "أكمل من حيث توقفت بالضبط، بدون إعادة أي جزء سابق.",
                            originalQuestion, combined, model, effectiveFirstChunk, tailForNext, attempt + 1, finalCallback);
                } else {
                    finalCallback.onSuccess(finalizeReply(combined));
                }
            }

            @Override
            public void onError(String message) {
                // لو فشلت محاولة الإكمال بعد ما نجح جزء أول، الأفضل نرجّع
                // الجزء المتاح بدل ما نضيّع رد جزئي مفيد على المستخدم.
                if (!accumulated.isEmpty()) {
                    finalCallback.onSuccess(finalizeReply(accumulated));
                } else {
                    finalCallback.onError(message);
                }
            }
        });
    }

    /** بيدور على أطول "صدى" ممكن في بداية reply لآخر جزء من contextTail
     *  اللي اتبعت للنموذج كسياق استكمال - عشان نشيله بدل ما نلزقه مكرر
     *  فوق accumulated. المقارنة بعد تطبيع المسافات فقط (مش حساسة لفروق
     *  بسيطة في المسافات/الأسطر بين النسختين). يرجّع الفهرس في نص reply
     *  الخام (مش المطبّع) اللي بعده يبدأ المحتوى الجديد الفعلي، أو 0 لو
     *  ملقاش أي تداخل ذو دلالة (أقل من 18 حرف مطبّع). */
    private static int findEchoOverlapRawLength(String contextTail, String reply) {
        if (contextTail == null || reply == null) return 0;
        String normTail = normalizeForCompare(contextTail);
        if (normTail.isEmpty() || reply.trim().isEmpty()) return 0;

        int rawScanLimit = Math.min(reply.length(), contextTail.length() + 200);
        StringBuilder normReplyStart = new StringBuilder();
        java.util.List<Integer> rawIndexAfter = new java.util.ArrayList<>();
        boolean lastWasSpace = true; // يتجاهل أي مسافات بادئة (زي trim())
        for (int i = 0; i < rawScanLimit; i++) {
            char c = reply.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace && normReplyStart.length() > 0) {
                    normReplyStart.append(' ');
                    rawIndexAfter.add(i + 1);
                }
                lastWasSpace = true;
            } else {
                normReplyStart.append(c);
                rawIndexAfter.add(i + 1);
                lastWasSpace = false;
            }
        }
        String normStart = normReplyStart.toString();
        if (normStart.isEmpty()) return 0;

        int maxLen = Math.min(normTail.length(), normStart.length());
        for (int len = maxLen; len >= 18; len--) {
            if (normTail.regionMatches(normTail.length() - len, normStart, 0, len)) {
                return rawIndexAfter.get(len - 1);
            }
        }
        return 0;
    }

    /** فحص تقريبي بسيط: رد طويل نسبيًا (فوق 200 حرف) وفيه عدد فردي من
     *  ``` (كتلة كود مفتوحة ولم تُغلق) - الأرجح إن الرد اتقطع في نص
     *  الكلام. لو الرد بينتهي بعلامة ترقيم أو رمز بيدل على نهاية واضحة
     *  (زي . ! ؟ : أو إيموجي ختامي)، ما بنعتبروش ناقص حتى لو آخر حرف
     *  عادي - لأن الجملة العربية غالبًا مالهاش نقطة إجبارية أصلًا.
     *
     *  إصلاح مهم (بلاغ: ردود طويلة لسه بتتقطع أحيانًا رغم منطق الاستكمال
     *  التلقائي كامل تحت): السطر الأخير القديم كان `return
     *  Character.isLetterOrDigit(last)` - يعني الرد يُعتبر "ناقص" فقط لو
     *  انتهى بحرف/رقم عادي، وأي حاجة تانية (شرطة "-"، فاصلة "،"، نقطتين
     *  ":"، أو أي رمز مش موجود أصلًا في CLEAR_ENDING_CHARS) كانت تتحسب
     *  تلقائيًا "نهاية واضحة" بمجرد إنها مش حرف/رقم - حتى لو مش من
     *  العلامات المعروفة فعليًا كنهاية جملة. المشكلة إن أسلوب الكتابة
     *  المستخدم فعليًا في كل تعليمات النظام ورد النموذج نفسه بيستخدم
     *  الشرطة "-" باستمرار كفاصل بين شقّي جملة ("...العلاج الطبيعي -
     *  بدون أي بحث عام")، مش كنهاية جملة - ونفس الكلام على الفاصلة "،"
     *  والنقطتين ":" (اللي هي فعليًا علامة "لسه فيه كلام جاي" حسب
     *  FORMAT_RULES نفسها، مش نهاية). فكان أي رد يتقطع من السيرفر مباشرة
     *  بعد واحدة من العلامات دي بيوصل للمستخدم ناقص من غير ما تتطلب أي
     *  محاولة استكمال أصلًا - عكس هدف الدالة دي بالظبط. الإصلاح: بدل
     *  الاعتماد على "حرف/رقم = ناقص وأي حاجة تانية = مكتمل"، بقى المعيار
     *  الوحيد للاكتمال هو إن آخر حرف من علامات CLEAR_ENDING_CHARS الصريحة
     *  فقط - أي حاجة تانية (حرف، رقم، شرطة، فاصلة، نقطتين، أو أي رمز غير
     *  متوقع) تُعتبر ناقصة ويتطلب استكمال. */
    private static boolean looksTruncated(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < 200) return false;
        int fenceCount = 0;
        int idx = 0;
        while ((idx = t.indexOf("```", idx)) != -1) { fenceCount++; idx += 3; }
        if (fenceCount % 2 != 0) return true;
        char last = t.charAt(t.length() - 1);
        return CLEAR_ENDING_CHARS.indexOf(last) < 0;
    }

    private static final String CLEAR_ENDING_CHARS =
            ".!?؟؛;)]”\"»…✅⚠️•";

    /** بيقارن أول جزء من محاولة الاستكمال بأول الرد الأصلي (المحفوظ في
     *  firstChunk) - لو النموذج بدأ كلامه بنفس الفتحة تقريبًا (إعادة
     *  صياغة من الأول بدل استكمال فعلي من حيث توقف)، نعتبرها محاولة
     *  فاشلة/مكررة. المقارنة على أول 60 حرف بعد تطبيع المسافات فقط -
     *  كفاية للإمساك بإعادة صياغة كاملة بدون حساسية زيادة لاختلافات
     *  بسيطة في نص طويل. */
    private static boolean looksLikeDuplicateRestart(String firstChunk, String newReply) {
        if (firstChunk == null || newReply == null) return false;
        String a = normalizeForCompare(firstChunk);
        String b = normalizeForCompare(newReply);
        int len = Math.min(60, Math.min(a.length(), b.length()));
        if (len < 20) return false; // نص قصير جدًا ملهوش دلالة كافية للمقارنة
        return a.substring(0, len).equals(b.substring(0, len));
    }

    private static String normalizeForCompare(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    private static String lastChars(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    /** زي lastChars، لكن بتتأكد إن أول حرف في النص المرجّع يبدأ من أول
     *  كلمة كاملة (بعد مسافة)، مش من نص كلمة اتقطعت بسبب القطع الخام بعدد
     *  حروف ثابت. النموذج اللي بيستقبل السياق ده عشان "يكمل منه" بيحتاج
     *  نص نظيف يبدأ بكلمة كاملة - سياق يبدأ بنص كلمة كان بيربكه أحيانًا
     *  (بيتجاهل تكملتها أو حتى ينتج كلمة غريبة بدالها). */
    private static String lastCharsAtWordBoundary(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        String tail = text.substring(text.length() - max);
        int firstSpace = -1;
        for (int i = 0; i < tail.length(); i++) {
            if (Character.isWhitespace(tail.charAt(i))) { firstSpace = i; break; }
        }
        if (firstSpace >= 0 && firstSpace < tail.length() - 1) {
            return tail.substring(firstSpace + 1);
        }
        return tail; // مفيش مسافة قريبة (نادر جدًا) - أفضل من إضاعة كل السياق
    }

    /** لو النص بينتهي فعليًا في نص كلمة (سبب looksTruncated يعتبره ناقص -
     *  آخر حرف عادي مش علامة ترقيم واضحة)، بترجع نسخة منه لحد آخر حد كلمة
     *  كامل (آخر مسافة قريبة من النهاية) بدل ما تسيب كلمة مبتورة ظاهرة في
     *  النص النهائي. لو مفيش مسافة قريبة كفاية (نادر)، بترجع النص زي ما
     *  هو بدل ما تقص جزء كبير بلا داعي. */
    private static String trimTrailingPartialWord(String text) {
        if (text == null || text.isEmpty()) return text;
        if (Character.isWhitespace(text.charAt(text.length() - 1))) return text;
        int searchFrom = Math.max(0, text.length() - 40);
        int lastSpace = -1;
        for (int i = text.length() - 1; i >= searchFrom; i--) {
            if (Character.isWhitespace(text.charAt(i))) { lastSpace = i; break; }
        }
        if (lastSpace < 0) return text;
        return text.substring(0, lastSpace).trim();
    }

    /** بتفحص محتوى استكمال جديد (newContent) مقابل الرد المتراكم *كله*
     *  (accumulated) - مش بس آخر جزء اتبعت كسياق زي findEchoOverlapRawLength
     *  - وبتشيل أي سطر أو جملة داخله سبق فعليًا ذكرها بنفس الصياغة تقريبًا
     *  في مكان أبكر من الرد. المقارنة سطر بسطر (الشاشة أصلًا بتعرض كل
     *  نقطة/عنوان في سطر منفصل حسب FORMAT_RULES)، ولو السطر نفسه مش مكرر
     *  بالكامل بتفحص جمل السطر منفردة كمان (لحالة جملة مكررة جوه سطر أطول). */
    private static String stripAlreadyCoveredContent(String accumulated, String newContent) {
        if (newContent == null || newContent.trim().isEmpty()) return newContent;
        if (accumulated == null || accumulated.trim().isEmpty()) return newContent;
        String normAccumulated = normalizeForCompare(accumulated);

        String[] lines = newContent.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String keptLine = stripDuplicateSentencesInLine(lines[i], normAccumulated);
            if (i > 0) result.append('\n');
            result.append(keptLine);
        }
        return result.toString();
    }

    private static final int MIN_DUPLICATE_UNIT_LEN = 18;

    /** سطر قائمة مرقّمة ("1. نص" أو "2) نص"): بيتعامل معاها كوحدة واحدة أدناه
     *  بدل التقسيم لجمل - راجع تعليق الإصلاح فوق stripDuplicateSentencesInLine. */
    private static final java.util.regex.Pattern NUMBERED_LIST_ITEM =
            java.util.regex.Pattern.compile("^\\s*\\d{1,2}[.)]\\s+.+");

    private static String stripDuplicateSentencesInLine(String line, String normAccumulated) {
        if (line == null || line.trim().isEmpty()) return line;
        String normLine = normalizeForCompare(line);
        if (normLine.length() >= MIN_DUPLICATE_UNIT_LEN && normAccumulated.contains(normLine)) {
            return "";
        }
        // إصلاح مهم (بند رقم "1." يفضل فاضي من غير محتواه): التقسيم لجمل تحت
        // بيعتبر "." بعد أي رقم في أول السطر "نهاية جملة" (نفس معاملة نقطة
        // آخر الكلام العادية) - فكان بيفصل رقم الترقيم ("1.") عن نص البند
        // نفسه كجملتين منفصلتين. لو نص البند (الجملة التانية) طلع "مكرر"
        // (نفس الفكرة سبق ذكرها بصياغة قريبة في مكان أبكر من الرد)، كان
        // بيتشال هو بس ويفضل رقم الترقيم لوحده - فيظهر للمستخدم "1." فاضية
        // بلا أي نص جنبها (زي ما وصل فعليًا: "1.\n2.\n3.\n4." من غير محتوى).
        // الإصلاح: سطر القائمة المرقّمة بيتفحص ككتلة واحدة (رقم + محتواه
        // مع بعض) - لو الكتلة كلها مكررة تتشال كلها، ولو مش مكررة تفضل زي
        // ما هي، من غير ما يتفصل الرقم عن محتواه أبدًا.
        if (NUMBERED_LIST_ITEM.matcher(line).matches()) {
            return line;
        }
        String[] sentences = line.split("(?<=[.!؟?])\\s+");
        if (sentences.length <= 1) return line;
        StringBuilder kept = new StringBuilder();
        // إصلاح مهم (جملتين متطابقتين حرفيًا جنب بعض في نفس السطر، زي بلاغ
        // فعلي وصل: "...يؤدي إلى تقليل الألم والتهاب. ...يؤدي إلى تقليل
        // الألم والتهاب." اتكررت مرتين متتاليتين في نفس السطر): normAccumulated
        // فوق ده لقطة ثابتة من الأسطر *السابقة* بس (اتاخدت قبل ما نبدأ نعالج
        // السطر الحالي - راجع finalizeReply/stripAlreadyCoveredContent اللي
        // بيستدعوا الدالة دي). يعني لو نفس السطر فيه جملتين متطابقتين، كل
        // جملة كانت بتتفحص لوحدها مقابل normAccumulated الثابت ده بس - مش
        // مقابل الجملة الأولى اللي اتقررت فعلًا إنها تتحفظ في kept قبل شوية
        // - فالجملة التانية المطابقة كانت بتعدي الفحص وتتضاف تاني كتكرار
        // حرفي جوه نفس السطر بالظبط. الإصلاح: نضيف كل جملة نحتفظ بيها في
        // kept لمجموعة seenInThisLine كمان أول ما نقررها، ونفحص كل جملة
        // جديدة مقابل الاتنين (normAccumulated من الأسطر السابقة + seenInThisLine
        // من نفس السطر) مش مقابل واحد بس.
        String seenInThisLine = "";
        for (String sentence : sentences) {
            String normSentence = normalizeForCompare(sentence);
            boolean dupFromEarlierLines = normSentence.length() >= MIN_DUPLICATE_UNIT_LEN
                    && normAccumulated.contains(normSentence);
            boolean dupWithinSameLine = normSentence.length() >= MIN_DUPLICATE_UNIT_LEN
                    && seenInThisLine.contains(normSentence);
            if (dupFromEarlierLines || dupWithinSameLine) {
                continue;
            }
            if (kept.length() > 0) kept.append(' ');
            kept.append(sentence);
            seenInThisLine = seenInThisLine.isEmpty() ? normSentence : seenInThisLine + " " + normSentence;
        }
        return kept.toString();
    }

    /** كلمات دالة على إن السؤال يتكلم عن "مرضى" المستخدم بشكل عام (مش
     *  سؤال طبي عام مستقل) - تُستخدم فقط لتقرير هل نرفق نظرة عامة مجهولة
     *  الهوية على كل المرضى ولا لأ، بدون أي تأثير على مسار SEARCH/CHAT
     *  نفسه (ده قرار AiClient.classifyIntent وحده). */
    private static final String[] PATIENT_OVERVIEW_HINTS = {
            "مرضاي", "مرضى", "المرضى", "مريضي", "حالاتي مع", "عيادتي", "مرضى العيادة"
    };

    private static boolean mentionsPatientsGenerally(String text) {
        if (text == null) return false;
        String t = text.trim();
        for (String hint : PATIENT_OVERVIEW_HINTS) {
            if (t.contains(hint)) return true;
        }
        return false;
    }

    private static void runGrounded(Context ctx, String text, String patientId,
            String historyContext, String physioTermHint, StageListener stages, ResultCallback callback) {
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

        // المرحلة الثانية: تأريض خارجي - Physiopedia حصرًا (مرجع متخصص في
        // العلاج الطبيعي فقط، مراجَع من أخصائيين). لا يوجد أي بحث احتياطي
        // عام (زي ويكيبيديا أو أي محرك بحث آخر) لو مفيش نتيجة منه - بدل
        // كده بيكمل بمعرفة النموذج العامة فقط، موضّح صراحة في شارة المصدر.
        //
        // إصلاح مهم (v5): Physiopedia موقع إنجليزي بالكامل، وكان البحث
        // بيتم دايمًا بنص سؤال المستخدم كما هو (غالبًا عربي) - فكان بيرجع
        // صفر نتائج فعليًا في أغلب الحالات، يعني "التدقيق/البحث" كان شبه
        // معطّل بصمت رغم وجود الكود. physioTermHint هو مصطلح إنجليزي جاهز
        // جاء من المصنّف (AiClient.classifyIntent + AiPrompts.ROUTER_PROMPT)
        // مترجم من جوهر السؤال - لو موجود بنستخدمه، وإلا نرجع لنص السؤال
        // الأصلي زي السلوك القديم (بدل ما نمنع البحث تمامًا لو مفيش مصطلح).
        String physioQuery = (physioTermHint != null && !physioTermHint.trim().isEmpty())
                ? physioTermHint.trim() : text;
        PhysiopediaClient.Result physio = PhysiopediaClient.search(physioQuery);

        StringBuilder extraContext = new StringBuilder();
        if (grounding != null) {
            extraContext.append("بروتوكولات موثقة ذات صلة من قاعدة بيانات الجهاز:\n")
                    .append(grounding.contextText).append("\n\n");
        }

        // موسوعة الأنماط + دليل التشريح: مرفقان دايمًا في المسار الإكلينيكي
        // (مصدر موثق ثابت داخل التطبيق، حجمه صغير فمفيش تكلفة تُذكر).
        String encyclopediaContext = DataManager.buildEncyclopediaContext(ctx);
        if (encyclopediaContext != null) {
            extraContext.append(encyclopediaContext).append("\n\n");
        }

        // مكتبة مستندات المستخدم على التخزين السحابي (PDF/مستندات مرفوعة
        // بنفسه من شاشة "الملفات السحابية"): مزامنة بأقل تكلفة ممكنة (بحد
        // أقصى مرة كل فترة قصيرة، بدون ما توقف الرد لو فشلت الشبكة)، ثم
        // إرفاق أقرب مقتطفات لسؤال المستخدم الحالي من الكاش المحلي - ده
        // اللي بيخلي المساعد فعليًا "يقرأ" ملفات المستخدم ويستخدمها.
        CloudKnowledgeManager.syncIfNeeded(ctx);
        String cloudDocsContext = CloudKnowledgeManager.buildCloudDocumentsContext(ctx, text);
        if (cloudDocsContext != null) {
            extraContext.append(cloudDocsContext).append("\n\n");
        }

        // ملف المريض (مُعرَّف الهوية - بدون اسم أو هاتف إطلاقًا): يُرفق فقط
        // لو السؤال منطلق فعليًا من ملف مريض محدد (patientId)، أو لو
        // المستخدم بيسأل عن "مرضاه" بشكل عام فنرفق نظرة عامة مجهولة الهوية
        // بدل ما نرفق كل بيانات العيادة مع كل سؤال إكلينيكي عادي.
        if (patientId != null && !patientId.trim().isEmpty()) {
            String patientContext = PatientManager.buildRedactedPatientContext(ctx, patientId);
            if (patientContext != null && !patientContext.isEmpty()) {
                extraContext.append(patientContext).append("\n\n");
            }
        } else if (mentionsPatientsGenerally(text)) {
            String overview = PatientManager.buildAllPatientsOverviewContext(ctx);
            if (overview != null) {
                extraContext.append(overview).append("\n\n");
            }
        }

        if (physio != null) {
            extraContext.append("خلفية معرفية متخصصة من Physiopedia (مرجع علاج طبيعي، مقالة: ")
                    .append(physio.title).append("):\n").append(physio.extract);
        }

        final String systemPromptToUse = AiPrompts.buildSystemPrompt(ctx,
                extraContext.length() > 0 ? extraContext.toString() : null, historyContext);
        final int groundedCount = grounding != null ? grounding.caseCount : 0;

        if (stages != null) stages.onThinking();

        // فلتر اختيار النموذج: المسار الإكلينيكي (تأريض + توصيات/موانع
        // استطباب فعلية) بيستخدم دايمًا أقوى نموذج متاح لتقليل احتمال
        // الهلوسة في سياق طبي حسّاس (راجع AiModelSelector.forClinical).
        String clinicalModel = AiModelSelector.forClinical();
        sendWithAutoContinue(systemPromptToUse, text, text, "", clinicalModel, 0, new AiClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                String sourceLabel;
                String sourceUrl = null;
                String externalTitle = physio != null ? physio.title : null;
                String externalUrl = physio != null ? physio.sourceUrl : null;
                String externalName = "Physiopedia";
                String cloudSuffix = cloudDocsContext != null ? " + مستندات سحابية للمستخدم" : "";
                if (groundedCount > 0 && externalTitle != null) {
                    sourceLabel = "إجابة تكميلية عامة (لا يوجد تطابق مباشر) - بروتوكولات قريبة (" + groundedCount + ") + " + externalName + ": " + externalTitle + cloudSuffix;
                    sourceUrl = externalUrl;
                } else if (groundedCount > 0) {
                    sourceLabel = "إجابة تكميلية عامة - أقرب بروتوكولات في القاعدة (" + groundedCount + ")، بدون تطابق مباشر مؤكد" + cloudSuffix;
                } else if (externalTitle != null) {
                    sourceLabel = "إجابة عامة من " + externalName + " (خارج قاعدة بيانات الجهاز): " + externalTitle + cloudSuffix;
                    sourceUrl = externalUrl;
                } else if (cloudDocsContext != null) {
                    sourceLabel = "إجابة عامة بالاستناد لمستندات سحابية رفعها المستخدم (بدون تطابق في قاعدة الجهاز أو Physiopedia)";
                } else {
                    sourceLabel = "إجابة عامة من معرفة النموذج (بدون مصدر موثّق من الجهاز أو Physiopedia)";
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
