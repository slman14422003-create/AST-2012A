package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * القراءة الصوتية لملف PDF - مجانية بالكامل وتعمل بدون إنترنت، عبر محرك النطق المدمج
 * في أندرويد (android.speech.tts.TextToSpeech). أفضل جودة: "خدمات Google للكلام"
 * (أصوات عصبية عالية الجودة بالعربية والإنجليزية والفرنسية والتركية)؛ والكلاس يختار
 * تلقائيًا أعلى صوت جودةً مثبّتًا على الجهاز لكل لغة (Voice.getQuality)، ويسمح للمستخدم
 * باختيار صوت آخر.
 *
 * كيف يحقق الدقة:
 *  - نص الصفحة يُستخرج كلمة-كلمة من طبقة النص الحقيقية (PdfSpeechText) وليس OCR.
 *  - يُقسَّم لجمل قصيرة (نبرة صحيحة) وكل جملة بلغتها (عربي/إنجليزي...) بالصوت المناسب.
 *  - onRangeStart (أندرويد 8+) يعطي الكلمة المنطوقة لحظيًا لتظليلها فوق الصفحة.
 *  - الانتقال التلقائي للصفحة التالية، إيقاف مؤقت واستئناف من نفس الكلمة، وتحكّم بالسرعة.
 *  - احترام تركيز الصوت (مكالمة/موسيقى) وإيقاف تلقائي عند مقاطعته.
 *
 * كل ردود Listener تصل على الخيط الرئيسي.
 */
final class PdfSpeaker {

    enum State {IDLE, LOADING, PLAYING, PAUSED}

    interface Listener {
        void onStateChanged(State state);

        /** بدأت قراءة صفحة جديدة (النص جاهز). */
        void onPageStarted(int page, PdfSpeechText.PageText text);

        /** الكلمة/الجملة الجاري نطقها الآن. wordIndex داخل text.words. */
        void onSpeaking(int page, PdfSpeechText.PageText text, int chunkIndex, int wordIndex);

        /** انتهى الملف كله. */
        void onFinished();

        void onError(String message);

        /** لا يوجد صوت مثبّت للغة (lang: ar/en/fr/tr) - يعرض الواجهة خيار التثبيت. */
        void onVoiceMissing(String lang);

        /** محرك النطق نفسه غير متاح/غير مثبّت. */
        void onEngineUnavailable();
    }

    static final class VoiceOption {
        final String name;
        final String label;

        VoiceOption(String name, String label) {
            this.name = name;
            this.label = label;
        }
    }

    private static final String PREFS = "pdf_tts";
    private static final String KEY_RATE = "rate";
    private static final int END = -1;

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final AudioManager audio;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsFailed = false;
    private Runnable pendingAfterInit = null;

    // المصدر (يُستخدم من خيط io فقط)
    private PdfSpeechText.Source source;
    private File sourceFile;
    private volatile int pageCount = 0;
    private PdfSpeechText.PageText prefetched;

    // حالة التشغيل (الخيط الرئيسي فقط)
    private State state = State.IDLE;
    private volatile int session = 0;
    private int currentPage = -1;
    private PdfSpeechText.PageText currentText;
    private int currentChunk = 0;
    private int currentWord = -1;
    private int resumeChunk = 0;
    private int resumeShift = 0;
    private int emptyStreak = 0;
    private boolean anyText = false;
    private float rate;
    private String lastAppliedLang = null;
    private final Set<String> notifiedMissing = new HashSet<>();
    private final Set<String> badVoices = new HashSet<>();
    private final Map<String, Voice> usedVoice = new HashMap<>();

    // تركيز الصوت
    private AudioFocusRequest focusRequest;
    private boolean resumeOnFocusGain = false;

    PdfSpeaker(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        this.rate = Math.max(0.5f, Math.min(2.5f, prefs.getFloat(KEY_RATE, 1.0f)));
        initTts();
    }

    // ------------------------------------------------------------------ المحرك

    private void initTts() {
        try {
            tts = new TextToSpeech(app, status -> {
                if (status != TextToSpeech.SUCCESS) {
                    ttsFailed = true;
                    pendingAfterInit = null;
                    setState(State.IDLE);
                    listener.onEngineUnavailable();
                    return;
                }
                ttsReady = true;
                try {
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                    tts.setSpeechRate(rate);
                    tts.setPitch(1.0f);
                } catch (Throwable ignored) {
                }
                tts.setOnUtteranceProgressListener(progressListener);
                Runnable r = pendingAfterInit;
                pendingAfterInit = null;
                if (r != null) r.run();
            });
        } catch (Throwable t) {
            ttsFailed = true;
            main.post(listener::onEngineUnavailable);
        }
    }

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            final int[] id = parseId(utteranceId);
            if (id == null) return;
            main.post(() -> handleStart(id));
        }

        @Override
        public void onDone(String utteranceId) {
            final int[] id = parseId(utteranceId);
            if (id == null) return;
            main.post(() -> handleDone(id));
        }

        @Override
        public void onError(String utteranceId) {
            onError(utteranceId, TextToSpeech.ERROR);
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            final int[] id = parseId(utteranceId);
            if (id == null) return;
            main.post(() -> handleError(id, errorCode));
        }

        @Override
        public void onRangeStart(String utteranceId, int start, int end, int frame) {
            final int[] id = parseId(utteranceId);
            if (id == null) return;
            main.post(() -> handleRange(id, start, end));
        }
    };

    private static String makeId(int sess, int page, int chunk, int shift) {
        return sess + ":" + page + ":" + chunk + ":" + shift;
    }

    /** [session, page, chunk, shift] */
    private static int[] parseId(String id) {
        if (id == null) return null;
        try {
            String[] p = id.split(":");
            if (p.length != 4) return null;
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]), Integer.parseInt(p[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean stale(int[] id) {
        return id[0] != session || id[1] != currentPage || currentText == null;
    }

    private void handleStart(int[] id) {
        if (stale(id) || id[2] == END) return;
        if (id[2] < 0 || id[2] >= currentText.chunks.size()) return;
        PdfSpeechText.Chunk c = currentText.chunks.get(id[2]);
        currentChunk = id[2];
        currentWord = Math.max(c.firstWord, Math.min(c.lastWord, currentText.wordAtOffset(c.start + id[3])));
        if (state != State.PLAYING) setState(State.PLAYING);
        listener.onSpeaking(currentPage, currentText, currentChunk, currentWord);
    }

    private void handleRange(int[] id, int start, int end) {
        if (stale(id) || id[2] == END) return;
        if (id[2] < 0 || id[2] >= currentText.chunks.size()) return;
        if (end - start > 80) return; // بعض المحركات تعطي نطاق الجملة كلها - نتجاهله
        PdfSpeechText.Chunk c = currentText.chunks.get(id[2]);
        int w = currentText.wordAtOffset(c.start + id[3] + start);
        w = Math.max(c.firstWord, Math.min(c.lastWord, w));
        if (w == currentWord && id[2] == currentChunk) return;
        currentChunk = id[2];
        currentWord = w;
        listener.onSpeaking(currentPage, currentText, currentChunk, currentWord);
    }

    private void handleDone(int[] id) {
        if (stale(id) || id[2] != END) return;
        goToPage(currentPage + 1);
    }

    private void handleError(int[] id, int code) {
        if (id[0] != session) return;
        // صوت شبكة فشل (غالبًا بدون إنترنت): نعتمد صوتًا محليًا ونكمل من نفس الموضع.
        String lang = currentText != null && id[2] >= 0 && id[2] < currentText.chunks.size()
                ? currentText.chunks.get(id[2]).lang : null;
        Voice v = lang != null ? usedVoice.get(lang) : null;
        if (v != null && v.isNetworkConnectionRequired() && !badVoices.contains(v.getName())) {
            badVoices.add(v.getName());
            usedVoice.remove(lang);
            if (currentText != null && id[2] >= 0) {
                resumeChunk = id[2];
                resumeShift = id[3];
                session++;
                try {
                    tts.stop();
                } catch (Throwable ignored) {
                }
                queuePage(currentText, resumeChunk, resumeShift, session);
                return;
            }
        }
        if (id[2] == END) {
            goToPage(currentPage + 1);
            return;
        }
        pause();
        listener.onError("تعذّر نطق النص (رمز الخطأ " + code + "). جرّب تغيير الصوت أو تثبيت بيانات الصوت من إعدادات محرك النطق.");
    }

    // ------------------------------------------------------------------ التحكم العام

    State getState() {
        return state;
    }

    boolean isActive() {
        return state != State.IDLE;
    }

    int getCurrentPage() {
        return currentPage;
    }

    int getPageCount() {
        return pageCount;
    }

    float getRate() {
        return rate;
    }

    /** يبدأ (أو يعيد) القراءة من صفحة معيّنة (0-based) من الملف. */
    void play(File file, int startPage) {
        Runnable go = () -> {
            session++;
            stopEngine();
            notifiedMissing.clear();
            emptyStreak = 0;
            anyText = false;
            lastAppliedLang = null;
            currentText = null;
            currentWord = -1;
            requestFocus();
            setState(State.LOADING);
            final int sess = session;
            io.execute(() -> {
                try {
                    if (source == null || sourceFile == null || !sourceFile.equals(file)) {
                        if (source != null) source.close();
                        source = PdfSpeechText.Source.open(app, file);
                        sourceFile = file;
                        prefetched = null;
                    }
                    pageCount = source.pageCount();
                } catch (Throwable t) {
                    main.post(() -> {
                        if (sess != session) return;
                        setState(State.IDLE);
                        abandonFocus();
                        listener.onError("تعذّر تجهيز نص الملف للقراءة.");
                    });
                    return;
                }
                loadPageOnIo(startPage, sess, 0, 0);
            });
        };
        runWhenReady(go);
    }

    void pause() {
        if (state != State.PLAYING && state != State.LOADING) return;
        captureResumePoint();
        session++;
        stopEngine();
        abandonFocus();
        setState(State.PAUSED);
    }

    void resume() {
        if (state != State.PAUSED) return;
        if (currentText == null) {
            // لم تبدأ صفحة بعد - نعيد التحميل
            if (sourceFile != null) play(sourceFile, Math.max(0, currentPage));
            return;
        }
        session++;
        lastAppliedLang = null;
        requestFocus();
        setState(State.LOADING);
        queuePage(currentText, resumeChunk, resumeShift, session);
    }

    void togglePlayPause() {
        if (state == State.PLAYING || state == State.LOADING) pause();
        else if (state == State.PAUSED) resume();
    }

    void stop() {
        session++;
        stopEngine();
        abandonFocus();
        resumeOnFocusGain = false;
        currentText = null;
        currentPage = -1;
        currentWord = -1;
        setState(State.IDLE);
    }

    void nextPage() {
        if (sourceFile == null || state == State.IDLE) return;
        if (currentPage + 1 >= pageCount) return;
        boolean wasPaused = state == State.PAUSED;
        jumpToPage(currentPage + 1, wasPaused);
    }

    void previousPage() {
        if (sourceFile == null || state == State.IDLE) return;
        boolean wasPaused = state == State.PAUSED;
        // لو تجاوزنا بداية الصفحة نعيدها من أولها، وإلا الصفحة السابقة
        int target = (currentChunk > 1 && currentPage >= 0) ? currentPage : Math.max(0, currentPage - 1);
        jumpToPage(target, wasPaused);
    }

    private void jumpToPage(int page, boolean stayPaused) {
        if (stayPaused) {
            // نجهّز الصفحة ونبقى على الإيقاف المؤقت
            currentPage = page;
            currentText = null;
            currentWord = -1;
            resumeChunk = 0;
            resumeShift = 0;
            session++;
            final int sess = session;
            io.execute(() -> {
                PdfSpeechText.PageText pt = takePage(page);
                main.post(() -> {
                    if (sess != session) return;
                    currentPage = page;
                    currentText = pt;
                    if (pt != null) listener.onPageStarted(page, pt);
                });
            });
            return;
        }
        session++;
        stopEngine();
        currentText = null;
        currentWord = -1;
        setState(State.LOADING);
        final int sess = session;
        io.execute(() -> loadPageOnIo(page, sess, 0, 0));
    }

    void setRate(float newRate) {
        rate = Math.max(0.5f, Math.min(2.5f, newRate));
        prefs.edit().putFloat(KEY_RATE, rate).apply();
        if (!ttsReady) return;
        try {
            tts.setSpeechRate(rate);
        } catch (Throwable ignored) {
        }
        if (state == State.PLAYING) restartFromCurrentWord();
    }

    /** يعيد القراءة من الكلمة الحالية (يُستخدم بعد تغيير السرعة أو الصوت). */
    private void restartFromCurrentWord() {
        if (currentText == null) return;
        captureResumePoint();
        session++;
        stopEngine();
        lastAppliedLang = null;
        queuePage(currentText, resumeChunk, resumeShift, session);
    }

    // ------------------------------------------------------------------ الأصوات

    String getEngineName() {
        try {
            return tts != null ? tts.getDefaultEngine() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    String getPreferredVoice(String lang) {
        return prefs.getString("voice_" + lang, null);
    }

    /** name = null يعني اختيار تلقائي (أعلى جودة). */
    void setPreferredVoice(String lang, String name) {
        SharedPreferences.Editor e = prefs.edit();
        if (name == null) e.remove("voice_" + lang);
        else e.putString("voice_" + lang, name);
        e.apply();
        usedVoice.remove(lang);
        if (state == State.PLAYING) restartFromCurrentWord();
    }

    List<VoiceOption> listVoices(String lang) {
        List<VoiceOption> out = new ArrayList<>();
        if (!ttsReady) return out;
        List<Voice> voices = installedVoices(lang);
        int i = 1;
        for (Voice v : voices) {
            StringBuilder sb = new StringBuilder("صوت ").append(i++).append(" · ");
            sb.append(v.isNetworkConnectionRequired() ? "يحتاج إنترنت" : "بدون إنترنت");
            sb.append(" · ").append(qualityLabel(v.getQuality()));
            String c = v.getLocale() != null ? v.getLocale().getCountry() : "";
            if (c != null && !c.isEmpty()) sb.append(" · ").append(c);
            out.add(new VoiceOption(v.getName(), sb.toString()));
        }
        return out;
    }

    private static String qualityLabel(int q) {
        if (q >= Voice.QUALITY_VERY_HIGH) return "جودة فائقة";
        if (q >= Voice.QUALITY_HIGH) return "جودة عالية";
        if (q >= Voice.QUALITY_NORMAL) return "جودة عادية";
        return "جودة منخفضة";
    }

    private List<Voice> installedVoices(String lang) {
        List<Voice> list = new ArrayList<>();
        try {
            Set<Voice> all = tts.getVoices();
            if (all == null) return list;
            for (Voice v : all) {
                if (v == null || v.getLocale() == null) continue;
                if (!lang.equals(v.getLocale().getLanguage())) continue;
                Set<String> f = v.getFeatures();
                if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                list.add(v);
            }
        } catch (Throwable ignored) {
        }
        final String deviceCountry = Locale.getDefault().getCountry();
        Collections.sort(list, (a, b) -> Integer.compare(voiceScore(b, deviceCountry), voiceScore(a, deviceCountry)));
        return list;
    }

    private int voiceScore(Voice v, String deviceCountry) {
        int s = v.getQuality() * 10;
        if (!v.isNetworkConnectionRequired()) s += 5; // أوثق (يعمل بدون اتصال)
        String c = v.getLocale().getCountry();
        if (c != null && c.equalsIgnoreCase(deviceCountry)) s += 3;
        if ("en".equals(v.getLocale().getLanguage()) && "US".equalsIgnoreCase(c)) s += 1;
        s -= Math.min(4, v.getLatency() / 100);
        return s;
    }

    private Voice chooseVoice(String lang) {
        Voice cached = usedVoice.get(lang);
        if (cached != null) return cached;
        List<Voice> candidates = installedVoices(lang);
        if (candidates.isEmpty()) return null;
        String saved = prefs.getString("voice_" + lang, null);
        Voice pick = null;
        if (saved != null) {
            for (Voice v : candidates) {
                if (v.getName().equals(saved) && !badVoices.contains(v.getName())) {
                    pick = v;
                    break;
                }
            }
        }
        if (pick == null) {
            for (Voice v : candidates) {
                if (!badVoices.contains(v.getName())) {
                    pick = v;
                    break;
                }
            }
        }
        if (pick != null) usedVoice.put(lang, pick);
        return pick;
    }

    private static Locale localeFor(String lang) {
        switch (lang) {
            case "ar":
                return new Locale("ar");
            case "fr":
                return Locale.FRENCH;
            case "tr":
                return new Locale("tr", "TR");
            default:
                return Locale.US;
        }
    }

    /** يضبط صوت اللغة قبل إضافة مقطع للطابور. يرجّع false لو ما في صوت متاح لها. */
    private boolean applyVoice(String lang) {
        if (lang.equals(lastAppliedLang)) return true;
        boolean ok = false;
        try {
            Voice v = chooseVoice(lang);
            if (v != null) {
                ok = tts.setVoice(v) == TextToSpeech.SUCCESS;
            }
            if (!ok) {
                Locale loc = localeFor(lang);
                int avail = tts.isLanguageAvailable(loc);
                if (avail >= TextToSpeech.LANG_AVAILABLE) {
                    ok = tts.setLanguage(loc) >= TextToSpeech.LANG_AVAILABLE;
                }
            }
        } catch (Throwable ignored) {
        }
        if (ok) {
            lastAppliedLang = lang;
        } else if (notifiedMissing.add(lang)) {
            listener.onVoiceMissing(lang);
        }
        return ok;
    }

    // ------------------------------------------------------------------ تحميل الصفحات والطابور

    private void runWhenReady(Runnable r) {
        if (ttsFailed) {
            listener.onEngineUnavailable();
            return;
        }
        if (ttsReady) r.run();
        else {
            setState(State.LOADING);
            pendingAfterInit = r;
        }
    }

    private PdfSpeechText.PageText takePage(int page) {
        if (source == null || page < 0 || page >= pageCount) return null;
        PdfSpeechText.PageText pt = prefetched;
        if (pt != null && pt.pageIndex == page) {
            prefetched = null;
            return pt;
        }
        return source.page(page);
    }

    /** يُنفَّذ على خيط io. */
    private void loadPageOnIo(int page, int sess, int startChunk, int startShift) {
        if (page >= pageCount) {
            main.post(() -> {
                if (sess != session) return;
                finishAll();
            });
            return;
        }
        final PdfSpeechText.PageText pt = takePage(page);
        main.post(() -> beginPage(page, pt, sess, startChunk, startShift));
    }

    private void goToPage(int page) {
        final int sess = session;
        if (page >= pageCount) {
            finishAll();
            return;
        }
        currentText = null;
        io.execute(() -> loadPageOnIo(page, sess, 0, 0));
    }

    private void beginPage(int page, PdfSpeechText.PageText pt, int sess, int startChunk, int startShift) {
        if (sess != session) return;
        currentPage = page;
        if (pt == null || pt.isEmpty()) {
            emptyStreak++;
            if (!anyText && emptyStreak >= 4) {
                stop();
                listener.onError("لا يوجد نص قابل للقراءة في هذه الصفحات - الملف على الأغلب صور ممسوحة ضوئيًا بدون طبقة نص.");
                return;
            }
            goToPage(page + 1);
            return;
        }
        emptyStreak = 0;
        anyText = true;
        currentText = pt;
        currentChunk = 0;
        currentWord = -1;
        resumeChunk = 0;
        resumeShift = 0;
        listener.onPageStarted(page, pt);
        queuePage(pt, startChunk, startShift, sess);
        // تجهيز الصفحة التالية مسبقًا حتى لا يحصل فراغ عند الانتقال
        final int next = page + 1;
        io.execute(() -> {
            if (source != null && next < pageCount) {
                PdfSpeechText.PageText p = source.page(next);
                if (sess == session) prefetched = p;
            }
        });
    }

    private void queuePage(PdfSpeechText.PageText pt, int startChunk, int startShift, int sess) {
        if (!ttsReady || sess != session) return;
        boolean first = true;
        final int page = pt.pageIndex;
        for (int j = Math.max(0, startChunk); j < pt.chunks.size(); j++) {
            PdfSpeechText.Chunk c = pt.chunks.get(j);
            int shift = (j == startChunk) ? Math.max(0, startShift) : 0;
            int s = Math.min(c.end, c.start + shift);
            String text = pt.text.substring(s, c.end);
            if (text.trim().isEmpty()) continue;
            if (!applyVoice(c.lang)) continue;
            Bundle params = new Bundle();
            int r;
            try {
                r = tts.speak(text, first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                        params, makeId(sess, page, j, shift));
            } catch (Throwable t) {
                r = TextToSpeech.ERROR;
            }
            if (r == TextToSpeech.ERROR) {
                listener.onError("تعذّر تشغيل محرك النطق.");
                pause();
                return;
            }
            first = false;
        }
        // علامة نهاية الصفحة: صمت قصير ثم الانتقال التلقائي للصفحة التالية
        try {
            tts.playSilentUtterance(350, first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                    makeId(sess, page, END, 0));
        } catch (Throwable ignored) {
        }
    }

    private void captureResumePoint() {
        if (currentText == null) return;
        if (currentChunk >= 0 && currentChunk < currentText.chunks.size()) {
            PdfSpeechText.Chunk c = currentText.chunks.get(currentChunk);
            int shift = 0;
            if (currentWord >= 0 && currentWord < currentText.words.size()) {
                shift = Math.max(0, currentText.words.get(currentWord).start - c.start);
            }
            resumeChunk = currentChunk;
            resumeShift = shift;
        } else {
            resumeChunk = 0;
            resumeShift = 0;
        }
    }

    private void finishAll() {
        session++;
        stopEngine();
        abandonFocus();
        currentText = null;
        currentWord = -1;
        setState(State.IDLE);
        listener.onFinished();
    }

    private void stopEngine() {
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {
        }
    }

    private void setState(State s) {
        if (state == s) return;
        state = s;
        listener.onStateChanged(s);
    }

    // ------------------------------------------------------------------ تركيز الصوت

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> main.post(() -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (state == State.PLAYING || state == State.LOADING) {
                resumeOnFocusGain = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
                pause();
            }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeOnFocusGain) {
            resumeOnFocusGain = false;
            resume();
        }
    });

    private void requestFocus() {
        if (audio == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener, main)
                        .build();
                audio.requestAudioFocus(focusRequest);
            } else {
                //noinspection deprecation
                audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Throwable ignored) {
        }
    }

    private void abandonFocus() {
        if (audio == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
            } else {
                //noinspection deprecation
                audio.abandonAudioFocus(focusListener);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ الإغلاق

    void shutdown() {
        session++;
        pendingAfterInit = null;
        abandonFocus();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable ignored) {
        }
        tts = null;
        io.execute(() -> {
            if (source != null) source.close();
            source = null;
        });
        io.shutdown();
    }
}
