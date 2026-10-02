package com.ast2012a.clinicalmaster;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * قاموس التاء المربوطة (ة) والهاء (ه) للقراءة الصوتية، مبني من قاموس التدقيق الإملائي (ar_SA.dic) بعد تنقيته
 * ودمجه مع قاموس التشكيل: tools/build_taa_lexicon.py -> assets/taa_haa_lexicon.txt.
 *
 *  T   كلمات مؤكّدة تنتهي بـ ة (نحو 27 ألف كلمة): تُصحَّح "الحركه" إلى "الحركة" فيُنطق آخرها تاءً/هاءً صحيحة.
 *  H   كلمات هاؤها أصلية (الله، وجه، فقه، تنبيه...): لا تُحوَّل إلى ة مهما كان.
 *  FIX أخطاء كتابة ة بدل ه (هذة، اللة...): تُصحَّح إلى هذه/الله قبل التشكيل.
 *
 * التحميل اختياري: قبل اكتماله (أو لو فُقد الملف) تعمل ArabicPhonetics بقوائمها الداخلية الصغيرة كما كانت.
 * آمن للاستدعاء من أي خيط.
 */
final class TaaLexicon {

    private static final Object LOCK = new Object();
    private static volatile Set<String> taa = null;
    private static volatile Set<String> haa = null;
    private static volatile Map<String, String> fix = null;

    private TaaLexicon() {
    }

    /** يحمّل الملف من assets مرة واحدة (يُنادى من TashkeelDict.load). أي فشل يُترك بصمت. */
    static void load(Context ctx) {
        if (taa != null) return;
        synchronized (LOCK) {
            if (taa != null) return;
            try (InputStream in = ctx.getAssets().open("taa_haa_lexicon.txt")) {
                loadFrom(in);
            } catch (Throwable ignored) {
            }
        }
    }

    /** قراءة من أي مجرى (للاختبار على الكمبيوتر أيضًا). */
    static void loadFrom(InputStream in) throws java.io.IOException {
        Set<String> t = new HashSet<>(1 << 15);
        Set<String> h = new HashSet<>(1 << 10);
        Map<String, String> f = new HashMap<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        char sec = 0;
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.charAt(0) == '#') {
                sec = line.length() > 1 ? line.charAt(1) : 0;
                if (line.startsWith("#FIX")) sec = 'F';
                continue;
            }
            if (sec == 'T') t.add(line);
            else if (sec == 'H') h.add(line);
            else if (sec == 'F') {
                int k = line.indexOf('=');
                if (k > 0) f.put(line.substring(0, k), line.substring(k + 1));
            }
        }
        haa = h;
        fix = f;
        taa = t; // آخرًا: taa != null تعني أن كل شيء جاهز
    }

    static boolean isLoaded() {
        return taa != null;
    }

    /** كلمة بلا تشكيل تنتهي بـ ة وموجودة في القاموس (بلا "ال"). */
    static boolean isTaa(String plain) {
        Set<String> t = taa;
        return t != null && plain != null && t.contains(plain);
    }

    /** كلمة هاؤها أصلية (بلا "ال"). */
    static boolean isHaa(String plain) {
        Set<String> h = haa;
        return h != null && plain != null && h.contains(plain);
    }

    private static final String FIX_PREFIX = "\u0648\u0641\u0628\u0644\u0643\u062A"; // و ف ب ل ك ت

    /**
     * تصحيح كتابة ة بدل ه في كلمات وظيفية: هذة -> هذه، واللة -> والله، لة -> له.
     * السوابق (و ف ب ل ك ت) مسموحة حتى حرفين قبل الجذر؛ وبعض الأخطاء (لة، بة) لا تُصحَّح إلا منفردة.
     * يُرجع null لو لا تصحيح.
     */
    static String fixTaaToHaa(String plain) {
        Map<String, String> f = fix;
        if (f == null || plain == null || plain.length() < 2 || plain.charAt(plain.length() - 1) != '\u0629') return null;
        String direct = f.get(plain);
        if (direct != null) return direct;
        for (int cut = 1; cut <= 2 && cut < plain.length() - 2; cut++) {
            String pre = plain.substring(0, cut);
            boolean ok = true;
            for (int i = 0; i < pre.length(); i++) {
                if (FIX_PREFIX.indexOf(pre.charAt(i)) < 0) ok = false;
            }
            if (!ok) continue;
            String rest = plain.substring(cut);
            if (rest.length() < 3) continue; // لة/بة المفردة فقط بلا سوابق
            String r = f.get(rest);
            if (r != null) return pre + r;
        }
        return null;
    }
}
