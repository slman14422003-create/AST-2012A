package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * مكتبات الكلمات العربية: تحميلها ودمجها في قاموس التشكيل (TashkeelDict) اختياريًا حسب المستخدم.
 *
 * كل "مكتبة" ملف نصي UTF-8 فيه كلمات عربية مشكولة. مصادر المكتبات:
 *  1) الأساسية: assets/tashkeel_dict.txt (المدمجة مع التطبيق، كما كانت).
 *  2) مدمجة إضافية: أي ملف .txt داخل assets/dict_packs/ (طبّ، أسماء، شرعي...) يظهر تلقائيًا في القائمة.
 *  3) مستورَدة: يضيفها المستخدم من ملف على الجهاز أو من رابط https؛ تُنظَّف وتُحفظ داخل مجلد التطبيق.
 *
 * الصيغ المقبولة في السطر (تُكتشف تلقائيًا، ويمكن خلطها في ملف واحد):
 *   كلمة_بلا_تشكيل TAB كلمة_مشكولة      (صيغة القاموس الأساسي)
 *   كلمة_بلا_تشكيل = كلمة_مشكولة
 *   كلمة_بلا_تشكيل , كلمة_مشكولة         (CSV بعمودين)
 *   كلمة_مشكولة                          (تُستنتج منها الكلمة المجرّدة)
 *   كلمة_عادية_بلا_تشكيل                 (كلمة في كل سطر: قائمة كلمات)
 *   نص عربي متصل، مشكول أو غير مشكول     (تُستخرج منه كل الكلمات العربية)
 * والأسطر التي تبدأ بـ # تعليق. المدخلات المشكولة تمرّ على WordVerifier.acceptEntry فلا تدخل القاموسَ كلمةٌ بحروف مختلفة أو
 * تشكيل فاسد. الكلمات غير المشكولة تُحفظ كمفردات، ويستخرج منها TashkeelDict (في خطوة ثانية بعد دمج كل المكتبات) كل كلمة
 * يمكن تشكيلها بيقين من كلمات القاموس (سوابق، لواحق، جمع مؤنث سالم...) فتُضاف للقاموس وتُدرّب عليها نماذج النطق.
 * الملف .txt بأي ترميز شائع: UTF-8 (مع BOM أو بدونه) أو UTF-16 أو Windows-1256 (يُكتشف تلقائيًا).
 *
 * ترتيب الأولوية عند تعارض كلمة بين مكتبتين: المستورَدة (الأحدث أولًا) ثم المدمجة الإضافية ثم الأساسية.
 * اختيار المستخدم (تفعيل/إيقاف كل مكتبة) محفوظ في SharedPreferences، والمكتبة الجديدة تكون مفعّلة افتراضيًا.
 * بعد أي تغيير يُستدعى TashkeelDict.reloadAsync ليُعاد الدمج في الخلفية دون تجميد الواجهة.
 */
final class DictionaryPacks {

    private DictionaryPacks() {
    }

    static final String BUILTIN_ID = "builtin";
    static final String ASSET_MAIN = "tashkeel_dict.txt";
    static final String ASSET_DIR = "dict_packs";

    static final int KIND_BUILTIN = 0;
    static final int KIND_ASSET = 1;
    static final int KIND_USER = 2;

    private static final String DIR = "dict_packs";
    private static final String PREFS = "dict_packs_prefs";
    private static final String K_DISABLED = "disabled";
    private static final String K_USER_IDS = "user_ids";
    private static final String K_TITLE = "title_";
    private static final String K_PREDICT = "predict_enabled";
    private static final long MAX_PACK_BYTES = 60L << 20;
    private static final int MAX_USER_PACKS = 12;
    private static final Object LOCK = new Object();

    /** إحصاءات آخر دمج لكل مكتبة: {أسطر، مشكولة مقبولة، مرفوضة، تجاوزت كلمات من مكتبة أقل أولوية، كلمات عادية، مشتقة منها}. */
    private static final Map<String, int[]> STATS = new ConcurrentHashMap<>();

    /**
     * مستقبِل للكلمات المقروءة من مكتبة. لو كانت plain تساوي shaped فالكلمة عادية (بلا تشكيل)؛ وإلا فهي مدخل مشكول
     * (plain هي الكلمة بلا تشكيل، وshaped المشكولة).
     */
    interface EntrySink {
        void accept(String plain, String shaped);
    }

    // ------------------------------------------------------------------ النموذج

    static final class Pack {
        final String id;
        final String title;
        final int kind;
        final String assetPath; // لمكتبات assets
        final File file;        // لمكتبات المستخدم
        boolean enabled;
        int lines;
        int accepted;
        int rejected;
        int overrides;
        int plain;   // كلمات عادية (بلا تشكيل) في المكتبة
        int derived; // كلمات عادية أمكن تشكيلها من القاموس فأُضيفت إليه وتدرّبت عليها نماذج النطق
        long bytes;

        Pack(String id, String title, int kind, String assetPath, File file) {
            this.id = id;
            this.title = title;
            this.kind = kind;
            this.assetPath = assetPath;
            this.file = file;
        }

        boolean isUser() {
            return kind == KIND_USER;
        }

        boolean loaded() {
            return lines > 0;
        }
    }

    // ------------------------------------------------------------------ التفضيلات

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    private static List<String> userIds(SharedPreferences p) {
        String s = p.getString(K_USER_IDS, "");
        List<String> l = new ArrayList<>();
        if (s == null || s.isEmpty()) return l;
        for (String id : s.split(",")) if (!id.isEmpty()) l.add(id);
        return l;
    }

    private static void saveUserIds(SharedPreferences.Editor e, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        e.putString(K_USER_IDS, sb.toString());
    }

    private static Set<String> disabledSet(SharedPreferences p) {
        Set<String> s = p.getStringSet(K_DISABLED, null);
        return s == null ? new HashSet<String>() : new HashSet<>(s); // نسخة: لا تُعدَّل المجموعة المخزّنة نفسها
    }

    // ------------------------------------------------------------------ قائمة المكتبات

    /** كل المكتبات بترتيب الأولوية (الأعلى أولًا): مستورَدة، ثم مدمجة إضافية، ثم الأساسية. */
    static List<Pack> list(Context ctx) {
        Context app = ctx.getApplicationContext();
        SharedPreferences p = prefs(app);
        Set<String> off = disabledSet(p);
        List<Pack> out = new ArrayList<>();

        for (String id : userIds(p)) {
            File f = new File(dir(app), id + ".txt");
            if (!f.isFile()) continue;
            Pack k = new Pack(id, p.getString(K_TITLE + id, "مكتبة مستوردة"), KIND_USER, null, f);
            k.bytes = f.length();
            out.add(k);
        }

        String[] names = null;
        try {
            names = app.getAssets().list(ASSET_DIR);
        } catch (IOException | RuntimeException ignored) {
        }
        if (names != null) {
            Arrays.sort(names);
            for (String n : names) {
                if (!n.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) continue;
                String base = n.substring(0, n.length() - 4);
                out.add(new Pack("asset_" + base, base.replace('_', ' '), KIND_ASSET, ASSET_DIR + "/" + n, null));
            }
        }

        boolean hasMain = false;
        try {
            String[] root = app.getAssets().list("");
            if (root != null) for (String n : root) if (ASSET_MAIN.equals(n)) hasMain = true;
        } catch (IOException | RuntimeException ignored) {
        }
        if (hasMain) out.add(new Pack(BUILTIN_ID, "القاموس الأساسي (مدمج)", KIND_BUILTIN, ASSET_MAIN, null));

        for (Pack k : out) {
            k.enabled = !off.contains(k.id);
            int[] st = STATS.get(k.id);
            if (st != null) {
                k.lines = st[0];
                k.accepted = st[1];
                k.rejected = st[2];
                k.overrides = st[3];
                if (st.length > 5) {
                    k.plain = st[4];
                    k.derived = st[5];
                }
            }
        }
        return out;
    }

    /** المكتبات المفعّلة بترتيب الدمج: الأقل أولوية أولًا كي تتغلّب الأعلى أولوية بآخر وضع (put يستبدل). */
    static List<Pack> enabledInMergeOrder(Context ctx) {
        List<Pack> all = list(ctx);
        List<Pack> on = new ArrayList<>();
        for (Pack k : all) if (k.enabled) on.add(k);
        Collections.reverse(on);
        return on;
    }

    static boolean isEnabled(Context ctx, String id) {
        return !disabledSet(prefs(ctx)).contains(id);
    }

    /** تفعيل/إيقاف مكتبة (لا يُعيد الدمج؛ نادِ TashkeelDict.reloadAsync بعده). */
    static void setEnabled(Context ctx, String id, boolean on) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(ctx);
            Set<String> off = disabledSet(p);
            if (on) off.remove(id);
            else off.add(id);
            p.edit().putStringSet(K_DISABLED, off).apply();
        }
    }

    static boolean isPredictEnabled(Context ctx) {
        return prefs(ctx).getBoolean(K_PREDICT, false);
    }

    static void setPredictEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(K_PREDICT, on).apply();
        LetterModel.setEnabled(on);
    }

    /** يطبّق اختيارات المستخدم على النماذج الحية (يُستدعى عند تحميل القاموس). */
    static void applyPrefs(Context ctx) {
        LetterModel.setEnabled(isPredictEnabled(ctx));
    }

    /** يسجّل إحصاءات دمج مكتبة (من TashkeelDict). */
    static void recordStats(String id, int lines, int accepted, int rejected, int overrides, int plain, int derived) {
        STATS.put(id, new int[]{lines, accepted, rejected, overrides, plain, derived});
    }

    /** مكتبة بعينها بإحصاءاتها الحالية (أو null). */
    static Pack find(Context ctx, String id) {
        for (Pack k : list(ctx)) if (k.id.equals(id)) return k;
        return null;
    }

    /** وصف قصير لمحتوى المكتبة للقائمة: كم كلمة مشكولة وكم كلمة عادية. */
    static String describe(Pack p) {
        if (p.accepted <= 0 && p.plain <= 0) return "";
        StringBuilder sb = new StringBuilder("  (");
        if (p.accepted > 0) sb.append(p.accepted).append(" مشكولة");
        if (p.plain > 0) {
            if (p.accepted > 0) sb.append(" + ");
            sb.append(p.plain).append(" عادية");
        }
        return sb.append(")").toString();
    }

    /** سطر ملخّص للإعدادات: كم مكتبة مفعّلة وكم كلمة. */
    static String summary(Context ctx) {
        List<Pack> all = list(ctx);
        int on = 0;
        int words = 0;
        int plain = 0;
        for (Pack k : all) {
            if (k.enabled) {
                on++;
                words += k.accepted;
                plain += k.plain;
            }
        }
        if (all.isEmpty()) return "لا توجد مكتبات";
        String s = on + " من " + all.size() + " مفعّلة";
        if (words > 0) s += " - " + words + " كلمة مشكولة";
        if (plain > 0) s += " + " + plain + " عادية";
        return s;
    }

    // ------------------------------------------------------------------ قراءة المكتبة

    private static InputStream open(Context ctx, Pack k) throws IOException {
        if (k.kind == KIND_USER) return new FileInputStream(k.file);
        return ctx.getApplicationContext().getAssets().open(k.assetPath);
    }

    /** يقرأ كل مدخلات المكتبة ويمرّرها للمستقبِل (بلا تحقق: التحقق شأن المستقبِل). */
    static void read(Context ctx, Pack k, EntrySink sink) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(open(ctx, k), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) parseLine(line, sink);
        }
    }

    // ------------------------------------------------------------------ تحليل السطر

    private static boolean isLetter(char c) {
        return (c >= 0x0621 && c <= 0x064A) || c == 0x0671;
    }

    private static boolean isJoin(char c) {
        return ArabicPhonetics.isMark(c) || c == '\u0640';
    }

    private static boolean hasMark(String s) {
        for (int i = 0; i < s.length(); i++) if (ArabicPhonetics.isMark(s.charAt(i))) return true;
        return false;
    }

    private static String stripMarks(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isJoin(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** يقصّ ما حول الكلمة من علامات ترقيم وأرقام، ويُعيد "" لو لم تكن كلمة عربية نقيّة. */
    private static String wordOf(String s) {
        int a = 0;
        int b = s.length();
        // نقصّ الترقيم والأرقام فقط: أي حرف آخر (لاتيني، فارسي...) يوقف القص فتُرفض الكلمة بدل أن تُبتر إلى كلمة عربية مزيّفة
        while (a < b && !Character.isLetter(s.charAt(a))) a++;
        while (b > a && !Character.isLetter(s.charAt(b - 1)) && !isJoin(s.charAt(b - 1))) b--;
        if (a >= b) return "";
        String w = s.substring(a, b);
        boolean any = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (isLetter(c)) any = true;
            else if (!isJoin(c)) return "";
        }
        return any ? w : "";
    }

    /** يحذف التطويل فقط (يُبقي التشكيل). */
    private static String dropTatweel(String s) {
        if (s.indexOf('\u0640') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != '\u0640') sb.append(s.charAt(i));
        return sb.toString();
    }

    /** هل في السطر أشكال عرض عربية (ملفات منسوخة من PDF)؟ تُوحَّد إلى الحروف الأصلية بـ NFKC. */
    private static boolean hasPresentationForms(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '\uFB50' && c <= '\uFDFF') || (c >= '\uFE70' && c <= '\uFEFF')) return true;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return isLetter(c) || isJoin(c) || Character.isLetterOrDigit(c);
    }

    /** يمرّر للمستقبِل كلمة واحدة: مشكولة (مجرّدة + مشكولة) أو عادية (مجرّدة مرتين). */
    private static void emitWord(String w, EntrySink sink) {
        String clean = dropTatweel(w);
        String plain = stripMarks(clean);
        if (plain.isEmpty()) return;
        sink.accept(plain, hasMark(clean) ? clean : plain);
    }

    /**
     * يحلّل سطرًا بأي من الصيغ المقبولة ويمرّر ما فيه للمستقبِل: أزواج (مجرّدة، مشكولة)، أو كلمات مشكولة، أو كلمات عادية
     * بلا تشكيل (كل كلمة عربية في السطر، وما فيها حروف أجنبية أو أرقام يُتجاوز).
     */
    static void parseLine(String line, EntrySink sink) {
        if (line == null || line.isEmpty()) return;
        String s = line.charAt(0) == '\uFEFF' ? line.substring(1) : line;
        s = s.trim();
        if (s.isEmpty() || s.charAt(0) == '#') return;
        if (hasPresentationForms(s)) s = Normalizer.normalize(s, Normalizer.Form.NFKC);

        int sep = s.indexOf('\t');
        if (sep < 0) sep = s.indexOf('=');
        if (sep < 0) {
            int c = s.indexOf(',');
            if (c > 0 && s.indexOf(',', c + 1) < 0 && s.indexOf(' ') < 0) sep = c;
        }
        if (sep > 0) {
            String left = wordOf(s.substring(0, sep).trim());
            String rest = s.substring(sep + 1);
            int t2 = rest.indexOf('\t');
            if (t2 > 0) rest = rest.substring(0, t2);
            String right = wordOf(rest.trim());
            // زوج (مجرّدة، مشكولة): أحد الطرفين مشكول والآخر لا. غير ذلك يُقرأ كل طرف كلمة مستقلة بالمسح التالي.
            if (!left.isEmpty() && !right.isEmpty() && hasMark(left) != hasMark(right)) {
                String shaped = hasMark(left) ? left : right;
                String plain = hasMark(left) ? right : left;
                sink.accept(stripMarks(plain), dropTatweel(shaped));
                return;
            }
        }
        // كلمات (كلمة في السطر أو نص متصل): مشكولة أو عادية
        int n = s.length();
        int i = 0;
        while (i < n) {
            while (i < n && !isWordChar(s.charAt(i))) i++;
            int st = i;
            while (i < n && isWordChar(s.charAt(i))) i++;
            if (i <= st) break;
            String w = wordOf(s.substring(st, i));
            if (!w.isEmpty()) emitWord(w, sink);
        }
    }

    // ------------------------------------------------------------------ الاستيراد

    /** يستورد مكتبة من ملف اختاره المستخدم (Uri من منتقي الملفات). نادِه من خيط خلفي. */
    static Pack importFromUri(Context ctx, Uri uri, String displayName) throws IOException {
        InputStream in = ctx.getApplicationContext().getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("تعذّر فتح الملف.");
        try (InputStream is = in) {
            return importStream(ctx, is, titleFrom(displayName));
        }
    }

    /** يستورد مكتبة من رابط https. نادِه من خيط خلفي فقط. */
    static Pack importFromUrl(Context ctx, String url, String title) throws IOException {
        String u = url == null ? "" : url.trim();
        if (!u.regionMatches(true, 0, "https://", 0, 8)) throw new IOException("الرابط لازم يبدأ بـ https://");
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("فشل التحميل (رمز " + code + ").");
            long len = c.getContentLengthLong();
            if (len > MAX_PACK_BYTES) throw new IOException("الملف كبير جدًا (الحد 60 ميجابايت).");
            String name = title;
            if (name == null || name.trim().isEmpty()) {
                String path = new URL(u).getPath();
                int sl = path.lastIndexOf('/');
                name = sl >= 0 ? path.substring(sl + 1) : path;
            }
            try (InputStream is = c.getInputStream()) {
                return importStream(ctx, is, titleFrom(name));
            }
        } finally {
            c.disconnect();
        }
    }

    private static String titleFrom(String name) {
        String t = name == null ? "" : name.trim();
        int dot = t.lastIndexOf('.');
        if (dot > 0 && t.length() - dot <= 6) t = t.substring(0, dot);
        t = t.replace('_', ' ').replace('-', ' ').trim();
        if (t.length() > 40) t = t.substring(0, 40);
        return t.isEmpty() ? "مكتبة مستوردة" : t;
    }

    /** مجموعة قيم 64 بت مضغوطة لإسقاط التكرار أثناء الاستيراد دون ذاكرة كبيرة (حتى max عنصر ثم يتوقف الإسقاط). */
    private static final class LongSet {
        private long[] t = new long[1 << 16];
        private int size;
        private final int max;

        LongSet(int max) {
            this.max = max;
        }

        private static int slot(long v, int mask) {
            return (int) (v ^ (v >>> 32)) & mask;
        }

        /** true لو العنصر جديد (أو امتلأت المجموعة فلا يمكن الحكم)، false لو سبق ظهوره. */
        boolean add(long h) {
            if (h == 0L) h = 1L;
            if (size >= max) return true;
            if ((size + 1) * 2 > t.length) grow();
            int mask = t.length - 1;
            int i = slot(h, mask);
            while (t[i] != 0L) {
                if (t[i] == h) return false;
                i = (i + 1) & mask;
            }
            t[i] = h;
            size++;
            return true;
        }

        private void grow() {
            long[] old = t;
            t = new long[old.length << 1];
            int mask = t.length - 1;
            for (long v : old) {
                if (v == 0L) continue;
                int i = slot(v, mask);
                while (t[i] != 0L) i = (i + 1) & mask;
                t[i] = v;
            }
        }
    }

    private static long hash64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static void copyLimited(InputStream in, File dst) throws IOException {
        byte[] buf = new byte[1 << 16];
        long total = 0;
        try (OutputStream os = new FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_PACK_BYTES) throw new IOException("الملف كبير جدًا (الحد 60 ميجابايت).");
                os.write(buf, 0, n);
            }
        }
        if (total == 0) throw new IOException("الملف فارغ.");
    }

    /** يرفض الملفات الثنائية (PDF، Word، Excel، ZIP...) برسالة واضحة بدل أن يخرج \"لا كلمات\" بلا تفسير. */
    private static void checkLooksLikeText(File f) throws IOException {
        byte[] h = new byte[4096];
        int n;
        try (InputStream is = new FileInputStream(f)) {
            n = is.read(h);
        }
        String msg = "هذا ليس ملفًا نصيًا. اختر ملف .txt (من Word أو Notepad: حفظ باسم > نص عادي، ترميز UTF-8).";
        if (n >= 4 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') throw new IOException(msg);
        if (n >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4) throw new IOException(msg);
        boolean bom16 = n >= 2 && (((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xFE)
                || ((h[0] & 0xFF) == 0xFE && (h[1] & 0xFF) == 0xFF));
        if (!bom16) for (int i = 0; i < n; i++) if (h[i] == 0) throw new IOException(msg);
    }

    private static boolean isValidUtf8(File f) {
        try (Reader r = new InputStreamReader(new FileInputStream(f),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT))) {
            char[] buf = new char[1 << 14];
            while (r.read(buf) != -1) {
                // مجرّد تحقق
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** ترميز ملف .txt: UTF-16 بحسب BOM، وإلا UTF-8 لو سليم، وإلا Windows-1256 (ملفات عربية قديمة من Notepad). */
    private static Charset detectCharset(File f) throws IOException {
        byte[] h = new byte[2];
        int n;
        try (InputStream is = new FileInputStream(f)) {
            n = is.read(h);
        }
        if (n == 2 && (((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xFE) || ((h[0] & 0xFF) == 0xFE && (h[1] & 0xFF) == 0xFF))) {
            return StandardCharsets.UTF_16; // يقرأ الـ BOM ويحدّد الترتيب بنفسه
        }
        if (isValidUtf8(f)) return StandardCharsets.UTF_8;
        try {
            return Charset.forName("windows-1256");
        } catch (RuntimeException e) {
            try {
                return Charset.forName("ISO-8859-6");
            } catch (RuntimeException e2) {
                return StandardCharsets.UTF_8;
            }
        }
    }

    /**
     * ينسخ المكتبة بعد تنظيفها: كل مدخل مشكول صالح يُكتب بصيغة (مجرّدة TAB مشكولة)، وكل كلمة عادية (بلا تشكيل) تُكتب
     * وحدها في سطر؛ والمكرّر يُسقَط. فيصغر الملف ويسرع تحميله ولا تصل أي كلمة فاسدة. ملف بلا أي كلمة عربية يُرفض برسالة واضحة.
     * تُمرَّر الكلمات العادية لاحقًا (في TashkeelDict) لتشكيل ما يمكن تشكيله منها بيقين وتدريب نماذج النطق عليه.
     */
    private static Pack importStream(Context ctx, InputStream in, String title) throws IOException {
        Context app = ctx.getApplicationContext();
        SharedPreferences p = prefs(app);
        synchronized (LOCK) {
            if (userIds(p).size() >= MAX_USER_PACKS) {
                throw new IOException("وصلت للحد الأقصى من المكتبات المستوردة (" + MAX_USER_PACKS + "). احذف مكتبة أولًا.");
            }
        }
        final String id = "u" + Long.toHexString(System.nanoTime());
        final File out = new File(dir(app), id + ".txt");
        final File tmp = new File(dir(app), id + ".tmp");
        final File raw = new File(dir(app), id + ".raw");
        final int[] cnt = new int[3]; // مشكولة قُبلت، مشكولة رُفضت، كلمات عادية
        boolean ok = false;
        try {
            copyLimited(in, raw);
            checkLooksLikeText(raw);
            Charset cs = detectCharset(raw);
            final LongSet seen = new LongSet(1_000_000);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(raw), cs), 1 << 16);
                 final BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8), 1 << 16)) {
                EntrySink sink = (plain, shaped) -> {
                    try {
                        if (plain.equals(shaped)) { // كلمة عادية
                            if (!WordVerifier.acceptPlain(plain)) return;
                            if (!seen.add(hash64(plain))) return;
                            w.write(plain);
                            w.write('\n');
                            cnt[2]++;
                            return;
                        }
                        if (!WordVerifier.acceptEntry(plain, shaped)) {
                            cnt[1]++;
                            return;
                        }
                        if (!seen.add(hash64(shaped))) return;
                        w.write(plain);
                        w.write('\t');
                        w.write(shaped);
                        w.write('\n');
                        cnt[0]++;
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                };
                String line;
                while ((line = r.readLine()) != null) parseLine(line, sink);
            }
            ok = true;
        } catch (IllegalStateException e) {
            throw new IOException("تعذّر حفظ المكتبة.", e.getCause() == null ? e : e.getCause());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
            if (!ok) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
        if (cnt[0] == 0 && cnt[2] == 0) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("لم أجد في الملف كلمات عربية. تأكد أنه ملف نصي (.txt) بترميز UTF-8 وفيه كلمات عربية "
                    + "(كلمة في كل سطر، أو نص عادي، أو كلمة بلا تشكيل ثم Tab ثم الكلمة مشكولة).");
        }
        if (!tmp.renameTo(out)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("تعذّر حفظ المكتبة.");
        }
        synchronized (LOCK) {
            List<String> ids = userIds(p);
            ids.add(0, id); // الأحدث أولًا = الأعلى أولوية
            SharedPreferences.Editor e = p.edit();
            saveUserIds(e, ids);
            e.putString(K_TITLE + id, title);
            e.apply();
        }
        Pack k = new Pack(id, title, KIND_USER, null, out);
        k.enabled = true;
        k.bytes = out.length();
        k.accepted = cnt[0];
        k.rejected = cnt[1];
        k.plain = cnt[2];
        return k;
    }

    /** يحذف مكتبة مستوردة (المدمجة لا تُحذف؛ أوقفها فقط). */
    static boolean remove(Context ctx, String id) {
        if (id == null || !id.startsWith("u")) return false;
        Context app = ctx.getApplicationContext();
        SharedPreferences p = prefs(app);
        boolean removed;
        synchronized (LOCK) {
            List<String> ids = userIds(p);
            removed = ids.remove(id);
            if (!removed) return false;
            Set<String> off = disabledSet(p);
            off.remove(id);
            SharedPreferences.Editor e = p.edit();
            saveUserIds(e, ids);
            e.remove(K_TITLE + id);
            e.putStringSet(K_DISABLED, off);
            e.apply();
        }
        //noinspection ResultOfMethodCallIgnored
        new File(dir(app), id + ".txt").delete();
        STATS.remove(id);
        return true;
    }
}
