package com.ast2012a.clinicalmaster;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * قاموس تشكيل محلي بالكامل (بدون إنترنت ولا ذكاء اصطناعي ولا حدود استخدام).
 *
 * يُبنى مرة واحدة على الكمبيوتر من مدوّنة عربية مشكولة عبر tools/build_tashkeel_dict.py ويوضع في
 * app/src/main/assets/tashkeel_dict.txt (كل سطر: كلمة_بلا_تشكيل TAB كلمة_مشكولة).
 *
 * هذا الصنف مخزن بيانات فقط؛ منطق المطابقة (توحيد الهمزات، السوابق، الضمائر، التدقيق) في WordVerifier.
 * SpeechPrep.diacritize ينادي lookup() بعد القاموس اليدوي (D) وقاموس المصطلحات الطبية (ArabicPhonetics.lookup).
 *
 * تحسينات هذه النسخة:
 *  - فهرس ثانٍ موحَّد (الهمزات وغيرها) لمطابقة "الالم" مع "الألم"؛ والكلمات الملتبسة (تشترك في المفتاح الموحّد) تُعلَّم
 *    ملتبسة فلا تُطابَق إلا حرفيًا.
 *  - تنظيف الأسطر عند التحميل (حروف مختلفة بعد إزالة التشكيل، تنوين في وسط الكلمة = كلمتان ملتصقتان، حركتان على
 *    حرف، كلمة بلا أي علامة).
 *  - awaitReady(): يمكن للقارئ انتظار اكتمال التحميل بدل أن تُنطق الجملة الأولى بلا قاموس.
 *  - الفهارس تُبنى مرة واحدة ثم تُنشر دفعة واحدة (لا قراءة لنصف محمَّل).
 */
final class TashkeelDict {

    private static final String ASSET = "tashkeel_dict.txt";
    /** قيمة في الفهرس الموحّد تعني: أكثر من كلمة تشترك في هذا المفتاح (لا نخمّن). */
    private static final String AMBIGUOUS = "\u0000";
    private static final Object LOCK = new Object();
    private static final CountDownLatch READY = new CountDownLatch(1);

    private static volatile Map<String, String> map = null;   // الكلمة بلا تشكيل -> المشكولة
    private static volatile Map<String, String> norm = null;  // المفتاح الموحّد -> المشكولة (أو AMBIGUOUS)
    private static volatile int rejected = 0;

    private TashkeelDict() {
    }

    /** يحمّل القاموس من assets (مرة واحدة). يمكن استدعاؤه من أكثر من خيط؛ اللاحق ينتظر السابق. */
    static void load(Context ctx) {
        if (map != null) return;
        synchronized (LOCK) {
            if (map != null) return;
            Map<String, String> m = new HashMap<>(1 << 18);
            Map<String, String> nm = new HashMap<>(1 << 17);
            int bad = 0;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(ctx.getAssets().open(ASSET), StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.indexOf('\t');
                    if (t <= 0 || t >= line.length() - 1) continue;
                    String plain = line.substring(0, t);
                    String shaped = line.substring(t + 1).trim();
                    if (!WordVerifier.acceptEntry(plain, shaped)) {
                        bad++;
                        continue;
                    }
                    m.put(plain, shaped);
                    String nk = WordVerifier.normKey(plain);
                    if (!nk.equals(plain)) {
                        String old = nm.put(nk, shaped);
                        if (old != null && !old.equals(shaped)) nm.put(nk, AMBIGUOUS);
                    }
                }
            } catch (Throwable ignored) {
                // الملف غير موجود أو تالف: يبقى ما حُمِّل (أو فارغ) ولا يتأثر أي شيء
            }
            rejected = bad;
            norm = nm;
            map = m;
            READY.countDown();
        }
    }

    /** هل اكتمل التحميل (ولو كان القاموس فارغًا لغياب الملف)؟ */
    static boolean isReady() {
        return map != null;
    }

    /** ينتظر اكتمال التحميل حتى المهلة المعطاة (بالمللي ثانية). لا تستدعه من خيط الواجهة. */
    static boolean awaitReady(long millis) {
        if (map != null) return true;
        try {
            return READY.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return map != null;
        }
    }

    static int size() {
        Map<String, String> m = map;
        return m == null ? 0 : m.size();
    }

    /** عدد الأسطر التي رُفضت عند التحميل لفسادها (تشخيص). */
    static int rejectedCount() {
        return rejected;
    }

    /** كل كلمات القاموس بلا تشكيل (للتشخيص والاقتراحات فقط؛ لا تعدّلها). */
    static Collection<String> keys() {
        Map<String, String> m = map;
        return m == null ? Collections.<String>emptySet() : Collections.unmodifiableCollection(m.keySet());
    }

    /** مطابقة حرفية فقط. null لو غير موجودة أو القاموس غير محمَّل بعد. */
    static String exact(String plain) {
        Map<String, String> m = map;
        if (m == null || plain == null) return null;
        return m.get(plain);
    }

    /**
     * مطابقة بالمفتاح الموحّد (WordVerifier.normKey) لكلمة غير موجودة حرفيًا.
     * null لو لا يوجد، أو لو ملتبسة (كلمتان مختلفتان بنفس المفتاح)، فلا نخمّن.
     */
    static String normalized(String normKey) {
        Map<String, String> nm = norm;
        Map<String, String> m = map;
        if (nm == null || m == null || normKey == null) return null;
        String viaNorm = nm.get(normKey);
        String direct = m.get(normKey); // كلمة بلا علامات همزة مطابقة للمفتاح نفسه
        if (viaNorm == null) return direct;
        if (AMBIGUOUS.equals(viaNorm)) return null;
        if (direct != null && !direct.equals(viaNorm)) return null; // كلمتان مختلفتان: ملتبس
        return viaNorm;
    }

    /**
     * تشكيل كلمة عربية مجرّدة (3 أحرف فأكثر) أو null لو لا يوجد ما يوثق به.
     * يعمل عبر WordVerifier: تشكيل متعلَّم، ثم حرفي، ثم موحَّد الهمزات، ثم بسوابق ولواحق - وكلها مدقَّقة الحروف.
     */
    static String lookup(String plain) {
        return WordVerifier.shape(plain);
    }
}
