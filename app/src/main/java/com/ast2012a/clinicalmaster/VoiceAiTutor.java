package com.ast2012a.clinicalmaster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * المدرّب الذكي للنطق: يأخذ الكلمات التي يشتبه المتعلّم المحلي في نطقها (VoiceBehaviorLearner) ويطلب من
 * Phizyo AI تشكيلها، ثم يتحقق من كل اقتراح قبل أن يعرضه على المستخدم:
 *
 *  - الحروف يجب أن تطابق الكلمة الأصلية حرفًا بحرف (لا يُقبل أن يغيّر النموذج الكلمة أو يضيف أخرى).
 *  - يجب أن يحتوي الاقتراح على حركة واحدة على الأقل (وإلا لا فائدة منه).
 *  - لا يُحفظ شيء في قاموس النطق (SpeechLearner) إلا بعد موافقة المستخدم (apply).
 *
 * الخصوصية: تُرسَل كلمات مفردة فقط (حتى 25 كلمة)، لا جمل ولا أسماء ملفات ولا بيانات مرضى.
 */
final class VoiceAiTutor {

    static final int MAX_WORDS = 25;

    static final class Suggestion {
        final String word;   // بلا تشكيل
        final String spoken; // مشكولة

        Suggestion(String word, String spoken) {
            this.word = word;
            this.spoken = spoken;
        }
    }

    interface Callback {
        /** تُستدعى من خيط خلفي. */
        void onResult(List<Suggestion> suggestions, int requested);

        void onError(String message);
    }

    private static final String SYSTEM = "أنت خبير في اللغة العربية والتشكيل الدقيق. المستخدم يقرأ نصوصًا في العلاج "
            + "الطبيعي والطب والعلوم. سأعطيك كلمات عربية بلا تشكيل، سطرًا لكل كلمة. أعد لكل كلمة نطقها الصحيح "
            + "الأشهر في الفصحى المعاصرة مع التشكيل الكامل (فتحة، ضمة، كسرة، سكون، شدّة، تنوين). "
            + "التزم حرفيًا بالصيغة: الكلمة|الكلمة_مع_التشكيل ، سطر واحد لكل كلمة. "
            + "لا تغيّر أي حرف، لا تضف كلمات، لا شرح، لا ترقيم، لا Markdown. "
            + "إن لم تكن متأكدًا من نطق كلمة فلا تكتب لها سطرًا.";

    private VoiceAiTutor() {
    }

    /** يطلب الاقتراحات في خيط خلفي ويستدعي cb عند الانتهاء (من نفس الخيط الخلفي). */
    static void suggest(final List<String> words, final Callback cb) {
        if (words == null || words.isEmpty()) {
            cb.onError("لا توجد كلمات مشتبه بها بعد. استمع إلى ملف PDF وارجع للمقاطع غير الواضحة وسأتعلّم منها.");
            return;
        }
        final List<String> batch = new ArrayList<>();
        for (String w : words) {
            if (batch.size() >= MAX_WORDS) break;
            String b = VoiceBehaviorLearner.bare(w);
            if (b.length() >= 3 && !batch.contains(b)) batch.add(b);
        }
        if (batch.isEmpty()) {
            cb.onError("لا توجد كلمات صالحة للتدريب.");
            return;
        }
        final StringBuilder msg = new StringBuilder();
        for (String w : batch) msg.append(w).append('\n');

        Thread t = new Thread(() -> AiClient.sendMessage(SYSTEM, msg.toString(), new AiClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                try {
                    cb.onResult(parse(reply, batch), batch.size());
                } catch (Throwable e) {
                    cb.onError("تعذّر قراءة رد الذكاء الاصطناعي.");
                }
            }

            @Override
            public void onError(String message) {
                cb.onError(message == null ? "تعذّر الاتصال بالذكاء الاصطناعي." : message);
            }
        }), "voice-ai-tutor");
        t.setDaemon(true);
        t.start();
    }

    /** يحلّل الرد ويُبقي الاقتراحات الصالحة فقط. */
    static List<Suggestion> parse(String reply, List<String> requested) {
        List<Suggestion> out = new ArrayList<>();
        if (reply == null) return out;
        Set<String> want = new HashSet<>(requested);
        Set<String> done = new HashSet<>();
        for (String raw : reply.split("\\r?\\n")) {
            String line = cleanLine(raw);
            if (line.isEmpty()) continue;
            int cut = firstSeparator(line);
            if (cut <= 0 || cut >= line.length() - 1) continue;
            String left = VoiceBehaviorLearner.bare(line.substring(0, cut));
            String right = stripTatweel(line.substring(cut + 1).trim());
            if (!want.contains(left) || done.contains(left)) continue;
            if (!isValidDiacritized(left, right)) continue;
            done.add(left);
            out.add(new Suggestion(left, right));
        }
        return out;
    }

    /** الحروف مطابقة تمامًا + فيها حركة + كلمة واحدة بلا فراغات. */
    static boolean isValidDiacritized(String bareWord, String spoken) {
        if (spoken == null || spoken.isEmpty()) return false;
        for (int i = 0; i < spoken.length(); i++) {
            if (Character.isWhitespace(spoken.charAt(i))) return false;
        }
        if (!VoiceBehaviorLearner.bare(spoken).equals(bareWord)) return false;
        boolean mark = false;
        for (int i = 0; i < spoken.length(); i++) {
            char c = spoken.charAt(i);
            if (c >= '\u064B' && c <= '\u0652') {
                mark = true;
                break;
            }
        }
        return mark;
    }

    private static String stripTatweel(String s) {
        return s.replace("\u0640", "");
    }

    private static String cleanLine(String raw) {
        String s = raw == null ? "" : raw.trim();
        s = s.replace("*", "").replace("`", "").trim();
        // قوائم مرقّمة أو نقطية: "1." "2)" "-" "•"
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == ')'
                || s.charAt(i) == '-' || s.charAt(i) == '\u2022' || s.charAt(i) == ' ')) {
            // لا نأكل أول حرف عربي أبدًا؛ نتوقف عند أول محرف ليس رقمًا/رمز قائمة
            i++;
        }
        return s.substring(i).trim();
    }

    private static int firstSeparator(String line) {
        int best = -1;
        String seps = "|:=\u2192\uFF1A";
        for (int i = 0; i < line.length(); i++) {
            if (seps.indexOf(line.charAt(i)) >= 0) {
                best = i;
                break;
            }
        }
        return best;
    }

    /** يحفظ الاقتراحات التي وافق عليها المستخدم في قاموس النطق المتعلَّم ويشطبها من المشتبه بها. */
    static int apply(List<Suggestion> accepted) {
        int n = 0;
        if (accepted == null) return 0;
        for (Suggestion s : accepted) {
            try {
                SpeechLearner.teach(s.word, s.spoken);
                VoiceBehaviorLearner.removeSuspect(s.word);
                n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }
}
