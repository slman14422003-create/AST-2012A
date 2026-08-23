package com.ast2012a.clinicalmaster;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * طبقة البيانات: تحميل قاعدة البيانات السريرية المدمجة مع التطبيق (أصول
 * assets)، إدارة الحالات المخصصة (تُحفظ في ملف JSON داخل تخزين التطبيق
 * الخاص)، ومحرك بحث مطابق تمامًا لخوارزمية البحث الأصلية (نفس الأوزان،
 * نفس المرادفات، نفس التطابق الضبابي لتصحيح الأخطاء الإملائية).
 */
public class DataManager {

    private static List<CaseItem> builtinCache;
    private static List<JSONObject> modesCache;

    private static final String CUSTOM_FILE = "custom_cases.json";

    // -----------------------------------------------------------------
    // تحميل / حفظ
    // -----------------------------------------------------------------

    public static List<CaseItem> loadBuiltinDatabase(Context ctx) {
        if (builtinCache != null) return builtinCache;
        builtinCache = new ArrayList<>();
        try {
            String json = readAsset(ctx, "clinical_database.json");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                builtinCache.add(CaseItem.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return builtinCache;
    }

    public static List<JSONObject> loadModesEncyclopedia(Context ctx) {
        if (modesCache != null) return modesCache;
        modesCache = new ArrayList<>();
        try {
            String json = readAsset(ctx, "modes_encyclopedia.json");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                modesCache.add(arr.getJSONObject(i));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return modesCache;
    }

    private static String readAsset(Context ctx, String name) throws IOException {
        InputStream is = ctx.getAssets().open(name);
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        reader.close();
        return sb.toString();
    }

    private static File customFile(Context ctx) {
        return new File(ctx.getFilesDir(), CUSTOM_FILE);
    }

    public static List<CaseItem> loadCustomCases(Context ctx) {
        List<CaseItem> list = new ArrayList<>();
        File f = customFile(ctx);
        if (!f.exists()) return list;
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    ctx.openFileInput(CUSTOM_FILE), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                list.add(CaseItem.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public static void saveCustomCases(Context ctx, List<CaseItem> cases) {
        try {
            JSONArray arr = new JSONArray();
            for (CaseItem c : cases) arr.put(c.toJson());
            FileOutputStream fos = ctx.openFileOutput(CUSTOM_FILE, Context.MODE_PRIVATE);
            fos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
    }

    public static List<CaseItem> allCases(Context ctx) {
        List<CaseItem> all = new ArrayList<>(loadBuiltinDatabase(ctx));
        all.addAll(loadCustomCases(ctx));
        return all;
    }

    // -----------------------------------------------------------------
    // إدارة الحالات المخصصة (إضافة / تعديل / حذف)
    // -----------------------------------------------------------------

    public static CaseItem addCustomCase(Context ctx, CaseItem fields) {
        List<CaseItem> cases = loadCustomCases(ctx);
        fields.id = UUID.randomUUID().toString().substring(0, 12);
        fields.custom = true;
        cases.add(fields);
        saveCustomCases(ctx, cases);
        return fields;
    }

    public static CaseItem updateCustomCase(Context ctx, String id, CaseItem fields) {
        List<CaseItem> cases = loadCustomCases(ctx);
        for (int i = 0; i < cases.size(); i++) {
            if (id.equals(cases.get(i).id)) {
                fields.id = id;
                fields.custom = true;
                cases.set(i, fields);
                saveCustomCases(ctx, cases);
                return fields;
            }
        }
        return null;
    }

    public static void deleteCustomCase(Context ctx, String id) {
        List<CaseItem> cases = loadCustomCases(ctx);
        List<CaseItem> filtered = new ArrayList<>();
        for (CaseItem c : cases) if (!id.equals(c.id)) filtered.add(c);
        saveCustomCases(ctx, filtered);
    }

    public static void clearAllCustomCases(Context ctx) {
        saveCustomCases(ctx, new ArrayList<>());
    }

    // -----------------------------------------------------------------
    // محرك البحث (منقول بالكامل من منطق JS الأصلي في التطبيق)
    // -----------------------------------------------------------------

    private static final Map<String, String[]> SYNONYMS = new HashMap<>();
    static {
        SYNONYMS.put("خشونه", new String[]{"التهاب المفصل", "استيوارثرايتس", "osteoarthritis", "oa"});
        SYNONYMS.put("الركبه", new String[]{"ركبه", "knee"});
        SYNONYMS.put("الرقبه", new String[]{"رقبه", "عنق", "neck", "cervical"});
        SYNONYMS.put("الظهر", new String[]{"ظهر", "عمود فقري", "back", "spine"});
        SYNONYMS.put("الكتف", new String[]{"كتف", "shoulder"});
        SYNONYMS.put("عرق النسا", new String[]{"نسا", "sciatica", "وجع النسا"});
        SYNONYMS.put("انزلاق غضروفي", new String[]{"ديسك", "disc", "غضروف"});
        SYNONYMS.put("شلل", new String[]{"فالج", "paralysis", "palsy"});
        SYNONYMS.put("جلطه", new String[]{"سكته دماغيه", "stroke", "cva"});
        SYNONYMS.put("تنميل", new String[]{"خدر", "numbness", "tingling"});
        SYNONYMS.put("الم", new String[]{"وجع", "الآم", "pain", "وجعا"});
        SYNONYMS.put("تورم", new String[]{"انتفاخ", "swelling", "edema"});
        SYNONYMS.put("تشنج", new String[]{"تقلص", "spasm", "cramp"});
        SYNONYMS.put("ضعف", new String[]{"ضمور", "weakness", "atrophy"});
        SYNONYMS.put("التهاب", new String[]{"الم مزمن", "inflammation", "itis"});
        SYNONYMS.put("كسر", new String[]{"فراكشر", "fracture"});
        SYNONYMS.put("سكري", new String[]{"سكر", "diabetes", "diabetic"});
        SYNONYMS.put("بعد الولاده", new String[]{"نفاس", "postpartum", "postnatal"});
        SYNONYMS.put("الكوع", new String[]{"مرفق", "elbow", "تنس البو"});
        SYNONYMS.put("القدم", new String[]{"كف القدم", "foot"});
        SYNONYMS.put("الكاحل", new String[]{"ankle", "التواء"});
        SYNONYMS.put("الفخذ", new String[]{"thigh", "hip femoral"});
        SYNONYMS.put("الورك", new String[]{"مفصل الحوض", "hip"});
    }

    public static String normalize(String text) {
        if (text == null) return "";
        text = text.toLowerCase();
        text = text.replaceAll("[أإآ]", "ا");
        text = text.replace('ة', 'ه');
        text = text.replace('ى', 'ي');
        return text;
    }

    private static Set<String> expandWithSynonyms(String term) {
        Set<String> expanded = new HashSet<>();
        expanded.add(term);
        for (Map.Entry<String, String[]> e : SYNONYMS.entrySet()) {
            String key = e.getKey();
            if (key.contains(term) || term.contains(key)) {
                expanded.add(key);
                for (String s : e.getValue()) expanded.add(s);
            }
            for (String s : e.getValue()) {
                if (s.contains(term) || term.contains(s)) {
                    expanded.add(key);
                    for (String s2 : e.getValue()) expanded.add(s2);
                }
            }
        }
        return expanded;
    }

    private static int levenshtein(String a, String b) {
        if (a.equals(b)) return 0;
        int al = a.length(), bl = b.length();
        if (al == 0) return bl;
        if (bl == 0) return al;
        int[][] dp = new int[al + 1][bl + 1];
        for (int i = 0; i <= al; i++) dp[i][0] = i;
        for (int j = 0; j <= bl; j++) dp[0][j] = j;
        for (int i = 1; i <= al; i++) {
            for (int j = 1; j <= bl; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[al][bl];
    }

    private static boolean fuzzyIncludes(String[] haystackWords, String term) {
        if (term.length() <= 3) return false;
        int maxDist = term.length() > 6 ? 2 : 1;
        for (String w : haystackWords) {
            if (Math.abs(w.length() - term.length()) <= maxDist && levenshtein(w, term) <= maxDist) {
                return true;
            }
        }
        return false;
    }

    public static List<String> getGeneralSafetyNote(String modeStr) {
        List<String> notes = new ArrayList<>();
        if (modeStr == null) modeStr = "";
        boolean isEms = modeStr.contains("EMS");
        boolean isTens = modeStr.contains("TENS");
        if (isEms || isTens) {
            notes.add("لا يُستخدم فوق منظم ضربات القلب (Pacemaker) أو أي جهاز كهربائي مزروع.");
            notes.add("يُمنع وضع الأقطاب على الجزء الأمامي من الرقبة (الجيب السباتي) أو الصدر بشكل متقاطع، أو منطقة الرأس والعينين.");
            notes.add("يُمنع الاستخدام فوق الجروح المفتوحة، الجلد المصاب، أو مناطق الأورام، أو أثناء الحمل فوق منطقة البطن والحوض دون استشارة.");
        }
        if (isEms) {
            notes.add("EMS يُحدث انقباضاً عضلياً فعلياً؛ ابدأ بشدة منخفضة وزدها تدريجياً حسب تحمل المريض.");
        }
        return notes;
    }

    private static class Scored {
        CaseItem item;
        int score;
        int matchedTerms;
    }

    private static Scored scoreItem(CaseItem item, String rawKeyword, String[] rawTerms) {
        String normTitle = normalize(item.title);
        String normMode = normalize(item.mode);
        String normExplanation = normalize(item.explanation);
        List<String> normKeywords = new ArrayList<>();
        for (String k : item.keywords) normKeywords.add(normalize(k));
        String[] titleWords = normTitle.split("\\s+");
        List<String> keywordWordsList = new ArrayList<>();
        for (String k : normKeywords) for (String w : k.split("\\s+")) keywordWordsList.add(w);
        String[] keywordWords = keywordWordsList.toArray(new String[0]);

        int score = 0;
        int matchedTerms = 0;

        if (normKeywords.contains(rawKeyword)) score += 1000;

        for (String rawTerm : rawTerms) {
            Set<String> variants = new HashSet<>();
            for (String v : expandWithSynonyms(rawTerm)) variants.add(normalize(v));

            boolean termMatched = false;
            for (String term : variants) {
                boolean inKeywords = false;
                for (String k : normKeywords) if (k.contains(term)) { inKeywords = true; break; }
                if (inKeywords) { score += 50; termMatched = true; }
                if (normTitle.contains(term)) { score += 20; termMatched = true; }
                if (normMode.contains(term)) { score += 10; termMatched = true; }
                if (normExplanation.contains(term)) { score += 3; termMatched = true; }
            }
            if (!termMatched) {
                if (fuzzyIncludes(keywordWords, rawTerm)) { score += 25; termMatched = true; }
                else if (fuzzyIncludes(titleWords, rawTerm)) { score += 12; termMatched = true; }
            }
            if (termMatched) matchedTerms++;
        }

        Scored s = new Scored();
        s.item = item;
        s.score = score;
        s.matchedTerms = matchedTerms;
        return s;
    }

    public static class SearchResult {
        public List<CaseItem> items;
        public boolean isFallback;
    }

    public static SearchResult search(String rawKeyword, List<CaseItem> cases) {
        SearchResult result = new SearchResult();
        result.items = new ArrayList<>();
        result.isFallback = false;

        if (rawKeyword == null || rawKeyword.trim().isEmpty()) return result;

        String normalizedKeyword = normalize(rawKeyword.trim());
        String[] rawTerms = normalizedKeyword.split("\\s+");
        List<String> termsList = new ArrayList<>();
        for (String t : rawTerms) if (t.length() > 1) termsList.add(t);
        if (termsList.isEmpty()) termsList.add(normalizedKeyword);
        String[] terms = termsList.toArray(new String[0]);

        List<Scored> scored = new ArrayList<>();
        for (CaseItem item : cases) scored.add(scoreItem(item, normalizedKeyword, terms));

        List<Scored> candidates = new ArrayList<>();
        for (Scored s : scored) if (s.matchedTerms == terms.length && s.score > 0) candidates.add(s);

        if (candidates.isEmpty()) {
            result.isFallback = true;
            int minTermsCovered = Math.max(1, (int) Math.ceil(terms.length * 0.6));
            for (Scored s : scored) if (s.matchedTerms >= minTermsCovered && s.score >= 15) candidates.add(s);
        }

        candidates.sort((a, b) -> b.score - a.score);
        if (candidates.isEmpty()) return result;

        int topScore = candidates.get(0).score;
        for (Scored s : candidates) if (s.score == topScore) result.items.add(s.item);
        return result;
    }

    public static String extractEnglishTerm(String title) {
        if (title == null) return null;
        Pattern p = Pattern.compile("\\(([^)]+)\\)\\s*$");
        Matcher m = p.matcher(title);
        if (m.find()) return m.group(1);
        return null;
    }

    // -----------------------------------------------------------------
    // "التأريض" (Grounding): تزويد المساعد الذكي بالبروتوكولات الموثقة ذات
    // الصلة من قاعدة بيانات الجهاز الرسمية، ليقارن إجابته معها بدل الاعتماد
    // على معرفته العامة فقط - يقلل الهلوسة ويحافظ على الاتساق مع الجهاز.
    // -----------------------------------------------------------------

    public static class GroundingResult {
        public String contextText;
        public int caseCount;
    }

    public static GroundingResult buildGroundingContext(Context ctx, String userQuery, int maxCases) {
        List<CaseItem> builtin = loadBuiltinDatabase(ctx);
        SearchResult result = search(userQuery, builtin);
        if (result.items.isEmpty()) return null;

        GroundingResult g = new GroundingResult();
        int count = Math.min(maxCases, result.items.size());
        g.caseCount = count;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            CaseItem c = result.items.get(i);
            sb.append("• ").append(c.title)
              .append(" | النمط: ").append(c.mode)
              .append(" | التردد: ").append(c.freq)
              .append(" | القناة: ").append(c.channel)
              .append(" | المدة: ").append(c.duration);
            if (c.explanation != null && !c.explanation.isEmpty()) {
                sb.append(" | الشرح السريري: ").append(c.explanation);
            }
            if (c.sessionsPlan != null && !c.sessionsPlan.isEmpty()) {
                sb.append(" | خطة الجلسات الموصى بها: ").append(c.sessionsPlan);
            }
            sb.append("\n");
        }
        g.contextText = sb.toString();
        return g;
    }

    // -----------------------------------------------------------------
    // ربط موسوعة الأنماط بقاعدة الحالات الفعلية: نستخرج أرقام الأنماط من
    // نص الموسوعة (مثال: "1 إلى 4" أو "10 و 11")، ونعدّ كم حالة سريرية
    // موثقة فعليًا تستخدم أي من هذه الأنماط - بيانات حقيقية من القاعدة
    // نفسها، مش أرقام مُختلقة.
    // -----------------------------------------------------------------

    public static Set<Integer> parseModeNumbers(String rangeText) {
        Set<Integer> numbers = new HashSet<>();
        if (rangeText == null) return numbers;

        Matcher rangeMatcher = Pattern.compile("(\\d+)\\s*(?:إلى|-|to)\\s*(\\d+)").matcher(rangeText);
        if (rangeMatcher.find()) {
            int start = Integer.parseInt(rangeMatcher.group(1));
            int end = Integer.parseInt(rangeMatcher.group(2));
            for (int i = start; i <= end; i++) numbers.add(i);
            return numbers;
        }

        Matcher singleMatcher = Pattern.compile("\\d+").matcher(rangeText);
        while (singleMatcher.find()) {
            numbers.add(Integer.parseInt(singleMatcher.group()));
        }
        return numbers;
    }

    private static Set<Integer> extractModeNumbersFromCase(String caseMode) {
        Set<Integer> numbers = new HashSet<>();
        if (caseMode == null) return numbers;
        Matcher m = Pattern.compile("النمط\\s*(\\d+)").matcher(caseMode);
        while (m.find()) numbers.add(Integer.parseInt(m.group(1)));
        return numbers;
    }

    public static int countCasesForModeNumbers(Context ctx, Set<Integer> modeNumbers) {
        if (modeNumbers.isEmpty()) return 0;
        int count = 0;
        for (CaseItem c : loadBuiltinDatabase(ctx)) {
            Set<Integer> caseNumbers = extractModeNumbersFromCase(c.mode);
            caseNumbers.retainAll(modeNumbers);
            if (!caseNumbers.isEmpty()) count++;
        }
        return count;
    }
}
