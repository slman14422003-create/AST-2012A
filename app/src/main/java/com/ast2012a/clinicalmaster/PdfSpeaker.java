package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.io.FileOutputStream;
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
import java.util.concurrent.RejectedExecutionException;

/**
 * القراءة الصوتية لملف PDF - مجانية بالكامل، بمحرّكين:
 *
 *  1) صوت عصبي أونلاين (الافتراضي): أصوات Microsoft Neural (عربية بعدة لهجات + إنجليزي + فرنسي + تركي)
 *     عبر EdgeTtsClient - جودة قريبة جدًا من الصوت البشري، بدون مفتاح ولا حساب. الجمل التالية
 *     تُجهَّز مسبقًا أثناء نطق الحالية فلا يوجد فراغ بين الجمل.
 *  2) صوت الجهاز (android.speech.tts.TextToSpeech): يعمل بدون إنترنت، وهو الاحتياطي التلقائي
 *     لو فشل الصوت الأونلاين.
 *
 * تسلسل القراءة: جملة واحدة في كل مرة (مقطع = Chunk)، وعند انتهائها ننتقل للتي بعدها. كل عملية نطق
 * تحمل "رمزًا" (speakToken) وأي ردّ متأخر أو مكرّر من محرك سابق يُتجاهل تمامًا - وهذا يمنع إعادة
 * قراءة السطر التالي. ولا نطلب تركيز الصوت (AudioFocus) مع صوت الجهاز لأن محرك النطق يديره بنفسه؛
 * تنافس التطبيق مع المحرك كان يسبّب إيقافًا/استئنافًا وهميًا بين الجمل وبالتالي إعادة القراءة.
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
    private static final String KEY_ENGINE = "engine"; // cloud | device
    private static final int POLL_MS = 40;
    /** تأخير الصوت الفعلي عن موضع التشغيل (مخزن المخرج/البلوتوث): نؤخّر التظليل بمقداره حتى لا يسبق الكلمة. */
    private static final int CLOUD_LAG_MS = 190;
    private static final int DEVICE_LAG_MS = 140;

    /** أصوات الأونلاين: {اللغة, اسم الصوت, الوصف}. الأول لكل لغة هو الافتراضي. */
    private static final String[][] CLOUD_VOICES = {
            {"ar", "ar-SA-ZariyahNeural", "زارية · سعودية · أنثى"},
            {"ar", "ar-SA-HamedNeural", "حامد · سعودي · ذكر"},
            {"ar", "ar-SY-AmanyNeural", "أماني · سورية · أنثى"},
            {"ar", "ar-SY-LaithNeural", "ليث · سوري · ذكر"},
            {"ar", "ar-EG-SalmaNeural", "سلمى · مصرية · أنثى"},
            {"ar", "ar-EG-ShakirNeural", "شاكر · مصري · ذكر"},
            {"ar", "ar-JO-SanaNeural", "سناء · أردنية · أنثى"},
            {"ar", "ar-JO-TaimNeural", "تيم · أردني · ذكر"},
            {"ar", "ar-LB-LaylaNeural", "ليلى · لبنانية · أنثى"},
            {"ar", "ar-LB-RamiNeural", "رامي · لبناني · ذكر"},
            {"en", "en-US-EmmaMultilingualNeural", "Emma · أمريكية · أنثى"},
            {"en", "en-US-AndrewMultilingualNeural", "Andrew · أمريكي · ذكر"},
            {"en", "en-US-AvaMultilingualNeural", "Ava · أمريكية · أنثى"},
            {"en", "en-US-BrianMultilingualNeural", "Brian · أمريكي · ذكر"},
            {"en", "en-US-AriaNeural", "Aria · أمريكية · أنثى"},
            {"en", "en-US-GuyNeural", "Guy · أمريكي · ذكر"},
            {"en", "en-GB-SoniaNeural", "Sonia · بريطانية · أنثى"},
            {"en", "en-GB-RyanNeural", "Ryan · بريطاني · ذكر"},
            {"fr", "fr-FR-VivienneMultilingualNeural", "Vivienne · أنثى"},
            {"fr", "fr-FR-RemyMultilingualNeural", "Rémy · ذكر"},
            {"fr", "fr-FR-DeniseNeural", "Denise · أنثى"},
            {"fr", "fr-FR-HenriNeural", "Henri · ذكر"},
            {"tr", "tr-TR-EmelNeural", "Emel · أنثى"},
            {"tr", "tr-TR-AhmetNeural", "Ahmet · ذكر"},
    };

    /** صوت جملة واحدة جاهز للتشغيل (أو null عند الفشل). */
    private static final class CloudAudio {
        final byte[] data;
        final int[] wordMs;
        final int[] wordChar;
        /** النص المنطوق الفعلي (بعد تنظيفه) وخريطته إلى مواضع النص الأصلي - لسلامة التظليل. */
        final SpeechPrep.Spoken spoken;

        CloudAudio(EdgeTtsClient.Result r, SpeechPrep.Spoken spoken) {
            this.data = r.audio;
            this.wordMs = r.wordMs;
            this.wordChar = r.wordChar;
            this.spoken = spoken;
        }
    }

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService synthPool = Executors.newFixedThreadPool(2);
    private final SharedPreferences prefs;
    private final AudioManager audio;
    private final File cacheDir;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsFailed = false;
    private Runnable pendingAfterInit = null;

    // المصدر (يُستخدم من خيط io فقط)
    private PdfSpeechText.Source source;
    private File sourceFile;
    private volatile int pageCount = 0;
    private volatile PdfSpeechText.PageText prefetched;

    // حالة التشغيل (الخيط الرئيسي فقط)
    private State state = State.IDLE;
    private volatile int session = 0;   // تحميل الصفحات
    private int speakToken = 0;         // كل عملية نطق (مقطع) لها رمز؛ الردود القديمة تُهمَل
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
    private int deviceErrStreak = 0;
    private final Set<String> notifiedMissing = new HashSet<>();
    private final Set<String> badVoices = new HashSet<>();
    private final Map<String, Voice> usedVoice = new HashMap<>();

    // الصوت الأونلاين
    private boolean cloudBroken = false;   // فشل خلال هذه الجلسة -> نستخدم صوت الجهاز
    private boolean cloudActive = false;   // المقطع الحالي يُنطق عبر الأونلاين
    private int cloudGen = 0;
    private int cloudPlayErrStreak = 0;
    private final Map<String, CloudAudio> cloudReady = new HashMap<>();
    private final Set<String> cloudPending = new HashSet<>();
    private final Set<String> cloudRetried = new HashSet<>();
    private final Set<String> badCloudVoices = new HashSet<>(); // أصوات فشلت في هذه الجلسة (نتجاوزها لصوت بديل)
    private int cloudVoiceSwitches = 0;
    /** النص المنطوق على صوت الجهاز (بعد التنظيف) وخريطته، لربط onRangeStart بالكلمة الأصلية. */
    private volatile SpeechPrep.Spoken deviceSpoken = null;
    // مشغّل الجملة التالية: يُجهَّز (prepare) أثناء نطق الحالية فيبدأ فور انتهائها بدون فجوة
    private MediaPlayer nextPlayer = null;
    private File nextFile = null;
    private String nextKey = null;
    private CloudAudio nextAudio = null;
    private boolean nextPrepared = false;
    private volatile int deviceSpokenToken = -1;
    private String playerDiag = "";   // آخر خطأ من MediaPlayer (للتشخيص)
    private boolean playerFdMode = false; // المحاولة الثانية: تشغيل عبر FileDescriptor
    private String awaitingKey = null;
    private int awaitingToken = 0;
    private int awaitingChunk = 0;
    private MediaPlayer player;
    private File playerFile;
    private CloudAudio playerAudio;
    private boolean playerPrepared = false;
    private boolean playerPaused = false;

    // تركيز الصوت (للصوت الأونلاين فقط)
    private AudioFocusRequest focusRequest;
    private boolean focusHeld = false;
    private boolean resumeOnFocusGain = false;

    PdfSpeaker(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        this.rate = Math.max(0.5f, Math.min(2.5f, prefs.getFloat(KEY_RATE, 1.0f)));
        this.cacheDir = new File(app.getCacheDir(), "tts_cloud");
        cleanCacheDir();
        initTts();
    }

    // ------------------------------------------------------------------ محرك الجهاز

    private void initTts() {
        try {
            tts = new TextToSpeech(app, status -> main.post(() -> {
                if (status != TextToSpeech.SUCCESS) {
                    ttsFailed = true;
                    if (pendingAfterInit != null) {
                        pendingAfterInit = null;
                        setState(State.IDLE);
                        listener.onEngineUnavailable();
                    }
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
            }));
        } catch (Throwable t) {
            ttsFailed = true;
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
            main.postDelayed(() -> handleRange(id, start, end), DEVICE_LAG_MS);
        }
    };

    private static String makeId(int token, int page, int chunk, int shift) {
        return token + ":" + page + ":" + chunk + ":" + shift;
    }

    /** [token, page, chunk, shift] */
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
        return id[0] != speakToken || id[1] != currentPage || currentText == null || cloudActive;
    }

    private void handleStart(int[] id) {
        if (stale(id)) return;
        if (id[2] < 0 || id[2] >= currentText.chunks.size()) return;
        deviceErrStreak = 0;
        PdfSpeechText.Chunk c = currentText.chunks.get(id[2]);
        currentChunk = id[2];
        currentWord = Math.max(c.firstWord, Math.min(c.lastWord, currentText.wordAtOffset(c.start + id[3])));
        if (state != State.PLAYING) setState(State.PLAYING);
        listener.onSpeaking(currentPage, currentText, currentChunk, currentWord);
    }

    private void handleRange(int[] id, int start, int end) {
        if (stale(id)) return;
        if (id[2] < 0 || id[2] >= currentText.chunks.size()) return;
        if (end - start > 80) return; // بعض المحركات تعطي نطاق الجملة كلها - نتجاهله
        PdfSpeechText.Chunk c = currentText.chunks.get(id[2]);
        int off = start;
        SpeechPrep.Spoken ds = deviceSpoken;
        if (ds != null && deviceSpokenToken == id[0]) off = ds.toOriginal(start);
        int w = currentText.wordAtOffset(c.start + id[3] + off);
        w = Math.max(c.firstWord, Math.min(c.lastWord, w));
        if (w == currentWord && id[2] == currentChunk) return;
        currentChunk = id[2];
        currentWord = w;
        listener.onSpeaking(currentPage, currentText, currentChunk, currentWord);
    }

    private void handleDone(int[] id) {
        if (stale(id)) return;
        if (id[2] != currentChunk) return;
        advance();
    }

    private void handleError(int[] id, int code) {
        if (stale(id)) return;
        // صوت شبكة فشل (غالبًا بدون إنترنت): نعتمد صوتًا محليًا ونكمل من نفس المقطع.
        String lang = id[2] >= 0 && id[2] < currentText.chunks.size()
                ? currentText.chunks.get(id[2]).lang : null;
        Voice v = lang != null ? usedVoice.get(lang) : null;
        if (v != null && v.isNetworkConnectionRequired() && !badVoices.contains(v.getName())) {
            badVoices.add(v.getName());
            usedVoice.remove(lang);
            lastAppliedLang = null;
            speakChunk(id[2], id[3]);
            return;
        }
        deviceErrStreak++;
        if (deviceErrStreak >= 3) {
            deviceErrStreak = 0;
            pause();
            listener.onError("تعذّر نطق النص (رمز الخطأ " + code + "). جرّب تغيير الصوت أو تثبيت بيانات الصوت من إعدادات محرك النطق.");
            return;
        }
        advance(); // نتخطى المقطع المشكل ونكمل
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
            hardStopOutputs();
            resetCloud();
            cloudBroken = false;
            badCloudVoices.clear();
            cloudVoiceSwitches = 0;
            deviceErrStreak = 0;
            notifiedMissing.clear();
            emptyStreak = 0;
            anyText = false;
            lastAppliedLang = null;
            currentText = null;
            currentWord = -1;
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
        if (isCloudEngine()) go.run();
        else runWhenReady(go);
    }

    void pause() {
        pauseInternal(true);
    }

    private void pauseInternal(boolean abandon) {
        if (state != State.PLAYING && state != State.LOADING) return;
        captureResumePoint();
        if (cloudActive && player != null && playerPrepared && !playerPaused) {
            try {
                player.pause();
                playerPaused = true;
            } catch (Throwable t) {
                releasePlayer();
            }
            main.removeCallbacks(poll);
        } else {
            hardStopOutputs();
        }
        if (abandon) abandonFocus();
        setState(State.PAUSED);
    }

    void resume() {
        if (state != State.PAUSED) return;
        if (player != null && playerPrepared && playerPaused) {
            requestFocus();
            playerPaused = false;
            try {
                player.start();
                applySpeed(player);
            } catch (Throwable t) {
                releasePlayer();
                setState(State.LOADING);
                speakChunk(currentChunk, 0);
                return;
            }
            setState(State.PLAYING);
            startPoll();
            return;
        }
        if (currentText == null) {
            // لم تبدأ صفحة بعد - نعيد التحميل
            if (sourceFile != null) play(sourceFile, Math.max(0, currentPage));
            return;
        }
        lastAppliedLang = null;
        setState(State.LOADING);
        speakChunk(resumeChunk, resumeShift);
    }

    void togglePlayPause() {
        if (state == State.PLAYING || state == State.LOADING) pause();
        else if (state == State.PAUSED) resume();
    }

    void stop() {
        session++;
        pendingAfterInit = null;
        hardStopOutputs();
        resetCloud();
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
        int target = ((currentChunk > 0 || currentWord > 15) && currentPage >= 0) ? currentPage : Math.max(0, currentPage - 1);
        jumpToPage(target, wasPaused);
    }

    /** انتقال المستخدم لصفحة أخرى أثناء القراءة: نعيد تجهيز القراءة من تلك الصفحة. */
    void seekToPage(int page) {
        if (sourceFile == null || state == State.IDLE || pageCount <= 0) return;
        int target = Math.max(0, Math.min(pageCount - 1, page));
        if (target == currentPage && currentText != null) return;
        jumpToPage(target, state == State.PAUSED);
    }

    private void jumpToPage(int page, boolean stayPaused) {
        session++;
        hardStopOutputs();
        resetCloud();
        currentText = null;
        currentWord = -1;
        currentChunk = 0;
        resumeChunk = 0;
        resumeShift = 0;
        final int sess = session;
        if (stayPaused) {
            // نجهّز الصفحة ونبقى على الإيقاف المؤقت
            currentPage = page;
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
        setState(State.LOADING);
        io.execute(() -> loadPageOnIo(page, sess, 0, 0));
    }

    void setRate(float newRate) {
        rate = Math.max(0.5f, Math.min(2.5f, newRate));
        prefs.edit().putFloat(KEY_RATE, rate).apply();
        if (ttsReady) {
            try {
                tts.setSpeechRate(rate);
            } catch (Throwable ignored) {
            }
        }
        if (player != null && playerPrepared && !playerPaused) {
            applySpeed(player);
        } else if (state == State.PLAYING && !cloudActive) {
            restartFromCurrentPoint();
        }
    }

    /** يعيد القراءة من الموضع الحالي (بعد تغيير السرعة/الصوت/المحرك). */
    private void restartFromCurrentPoint() {
        if (currentText == null || state == State.IDLE) return;
        captureResumePoint();
        hardStopOutputs();
        lastAppliedLang = null;
        if (state == State.PLAYING || state == State.LOADING) {
            setState(State.LOADING);
            speakChunk(resumeChunk, resumeShift);
        }
    }

    // ------------------------------------------------------------------ اختيار المحرك والأصوات

    /** true = صوت عصبي أونلاين (الافتراضي)، false = صوت الجهاز. */
    boolean isCloudEngine() {
        return "cloud".equals(prefs.getString(KEY_ENGINE, "cloud"));
    }

    void setCloudEngine(boolean cloud) {
        prefs.edit().putString(KEY_ENGINE, cloud ? "cloud" : "device").apply();
        cloudBroken = false;
        badCloudVoices.clear();
        cloudVoiceSwitches = 0;
        resetCloud();
        if (state != State.IDLE) restartFromCurrentPoint();
    }

    List<VoiceOption> listCloudVoices(String lang) {
        List<VoiceOption> out = new ArrayList<>();
        for (String[] v : CLOUD_VOICES) {
            if (v[0].equals(lang)) out.add(new VoiceOption(v[1], v[2]));
        }
        return out;
    }

    String getPreferredCloudVoice(String lang) {
        return prefs.getString("cvoice_" + lang, null);
    }

    /** name = null يعني الصوت الافتراضي للغة. */
    void setPreferredCloudVoice(String lang, String name) {
        SharedPreferences.Editor e = prefs.edit();
        if (name == null) e.remove("cvoice_" + lang);
        else e.putString("cvoice_" + lang, name);
        e.apply();
        badCloudVoices.clear();
        cloudVoiceSwitches = 0;
        resetCloud();
        if (state != State.IDLE) restartFromCurrentPoint();
    }

    /** الصوت المختار للغة، مع تجاوز الأصوات التي فشلت؛ null لو فشلت كل أصوات اللغة. */
    private String pickCloudVoice(String lang) {
        String saved = prefs.getString("cvoice_" + lang, null);
        if (saved != null && !badCloudVoices.contains(saved)) {
            for (String[] v : CLOUD_VOICES) {
                if (v[0].equals(lang) && v[1].equals(saved)) return saved;
            }
        }
        for (String[] v : CLOUD_VOICES) {
            if (v[0].equals(lang) && !badCloudVoices.contains(v[1])) return v[1];
        }
        return null;
    }

    private String cloudVoiceFor(String lang) {
        String v = pickCloudVoice(lang);
        if (v != null) return v;
        for (String[] cv : CLOUD_VOICES) {
            if (cv[0].equals(lang)) return cv[1];
        }
        return "en-US-EmmaMultilingualNeural";
    }

    private boolean useCloud() {
        return isCloudEngine() && !cloudBroken;
    }

    // ------------------------------------------------------------------ أصوات الجهاز

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
        lastAppliedLang = null;
        if (state == State.PLAYING && !cloudActive) restartFromCurrentPoint();
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

    /** يضبط صوت اللغة قبل نطق مقطع. يرجّع false لو ما في صوت متاح لها. */
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

    // ------------------------------------------------------------------ تحميل الصفحات

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
        speakToken++;
        releasePlayer();
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
        speakChunk(startChunk, startShift);
        // تجهيز الصفحة التالية مسبقًا (نصها، وصوت أول جملة فيها) حتى لا يحصل فراغ عند الانتقال
        final int next = page + 1;
        io.execute(() -> {
            if (source != null && next < pageCount) {
                final PdfSpeechText.PageText p = source.page(next);
                if (sess == session) {
                    prefetched = p;
                    if (p != null && !p.isEmpty()) {
                        main.post(() -> {
                            if (sess == session && useCloud()) requestCloud(p, 0);
                        });
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ نطق المقاطع (جملة بجملة)

    /** يبدأ نطق المقطع idx (وما بعده لو تعذّر)، وعند نهاية الصفحة ينتقل للتالية. */
    private void speakChunk(int idx, int shift) {
        if (currentText == null) return;
        if (!useCloud() && !ttsReady) {
            if (ttsFailed) {
                stop();
                listener.onEngineUnavailable();
                return;
            }
            setState(State.LOADING);
            final int i = idx;
            final int s = shift;
            final int tok = speakToken;
            pendingAfterInit = () -> {
                if (tok == speakToken) speakChunk(i, s);
            };
            return;
        }
        int n = currentText.chunks.size();
        while (idx < n) {
            if (startChunk(idx, shift)) return;
            idx++;
            shift = 0;
        }
        goToPage(currentPage + 1);
    }

    /** true = بدأ النطق (أو ينتظر تجهيز الصوت)، false = يجب تخطي هذا المقطع. */
    private boolean startChunk(int idx, int shift) {
        PdfSpeechText.Chunk c = currentText.chunks.get(idx);
        if (currentText.text.substring(c.start, c.end).trim().isEmpty()) return false;
        currentChunk = idx;
        currentWord = -1;
        final int tok = ++speakToken;
        releasePlayer();

        if (useCloud()) {
            cloudActive = true;
            String key = requestCloud(currentText, idx);
            prefetchAhead(idx);
            if (nextPlayer != null && !key.equals(nextKey)) releaseNext();
            if (nextPlayer != null && nextPrepared && adoptPreloaded(idx, tok, key)) return true;
            CloudAudio a = cloudReady.remove(key);
            if (a != null) {
                startPlayer(a, idx, tok);
            } else {
                awaitingKey = key;
                awaitingToken = tok;
                awaitingChunk = idx;
                if (state != State.LOADING) setState(State.LOADING);
            }
            return true;
        }

        // صوت الجهاز
        cloudActive = false;
        awaitingKey = null;
        if (!applyVoice(c.lang)) return false;
        int s = Math.min(c.end, c.start + Math.max(0, shift));
        String rawText = currentText.text.substring(s, c.end);
        if (rawText.trim().isEmpty()) return false;
        SpeechPrep.Spoken spoken = SpeechPrep.prepare(EdgeTtsClient.sanitize(rawText), c.lang);
        String text = spoken.text;
        if (text.trim().isEmpty()) return false; // رموز فقط
        deviceSpoken = spoken;
        deviceSpokenToken = tok;
        int r;
        try {
            r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), makeId(tok, currentPage, idx, shift));
        } catch (Throwable t) {
            r = TextToSpeech.ERROR;
        }
        if (r == TextToSpeech.ERROR) {
            pause();
            listener.onError("تعذّر تشغيل محرك النطق.");
        }
        return true;
    }

    private void advance() {
        if (currentText == null) return;
        int next = currentChunk + 1;
        if (next < currentText.chunks.size()) speakChunk(next, 0);
        else goToPage(currentPage + 1);
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
        hardStopOutputs();
        resetCloud();
        abandonFocus();
        currentText = null;
        currentWord = -1;
        setState(State.IDLE);
        listener.onFinished();
    }

    /** يوقف كل ما يُنطق الآن (المحرك والمشغّل) ويُبطل أي ردود متأخرة. */
    private void hardStopOutputs() {
        speakToken++;
        releaseNext();
        awaitingKey = null;
        stopEngine();
        releasePlayer();
    }

    private void stopEngine() {
        try {
            if (tts != null && ttsReady) tts.stop();
        } catch (Throwable ignored) {
        }
    }

    private void setState(State s) {
        if (state == s) return;
        state = s;
        listener.onStateChanged(s);
    }

    // ------------------------------------------------------------------ الصوت الأونلاين

    private String cloudKey(String voice, int page, int chunk) {
        return voice + "#" + page + "#" + chunk;
    }

    /** يطلب تجهيز صوت المقطع (لو لم يكن جاهزًا أو قيد التجهيز) ويرجّع مفتاحه. */
    private String requestCloud(PdfSpeechText.PageText pt, int idx) {
        PdfSpeechText.Chunk c = pt.chunks.get(idx);
        final String voice = cloudVoiceFor(c.lang);
        final String key = cloudKey(voice, pt.pageIndex, idx);
        if (cloudReady.containsKey(key) || cloudPending.contains(key)) return key;
        final SpeechPrep.Spoken spoken = SpeechPrep.prepare(
                EdgeTtsClient.sanitize(pt.text.substring(c.start, c.end)), c.lang);
        final String sent = spoken.text;
        final int gen = cloudGen;
        cloudPending.add(key);
        try {
            synthPool.execute(() -> {
                EdgeTtsClient.Result r = null;
                Throwable err = null;
                try {
                    if (sent.trim().isEmpty()) { // مقطع كله رموز: لا يوجد ما يُنطق - نتخطاه بدون اتصال
                        r = new EdgeTtsClient.Result(new byte[0], new int[0], new int[0]);
                    } else {
                        r = EdgeTtsClient.synthesize(sent, voice);
                    }
                } catch (Throwable t) {
                    err = t;
                }
                final EdgeTtsClient.Result rr = r;
                final Throwable ee = err;
                main.post(() -> onCloudResult(key, rr == null ? null : new CloudAudio(rr, spoken), gen, ee));
            });
        } catch (RejectedExecutionException e) {
            cloudPending.remove(key);
        }
        return key;
    }

    private void prefetchAhead(int idx) {
        if (currentText == null) return;
        int n = currentText.chunks.size();
        for (int k = idx + 1; k <= idx + 3 && k < n; k++) requestCloud(currentText, k);
        // المقطع الأخير في الصفحة: نجهّز صوت أول مقطع في الصفحة التالية فورًا حتى لا يحصل انتظار عند الانتقال
        PdfSpeechText.PageText nx = prefetched;
        if (idx + 1 >= n && nx != null && nx.pageIndex == currentText.pageIndex + 1 && !nx.isEmpty()) requestCloud(nx, 0);
    }

    private void onCloudResult(String key, CloudAudio a, int gen, Throwable err) {
        if (gen != cloudGen) return;
        cloudPending.remove(key);
        boolean waiting = key.equals(awaitingKey) && awaitingToken == speakToken;
        if (a == null) {
            if (!waiting) return; // فشل تجهيز مسبق: سنعيد الطلب عند الحاجة
            if (currentText == null) return;
            final String why = EdgeTtsClient.lastError == null || EdgeTtsClient.lastError.isEmpty()
                    ? "" : " (" + EdgeTtsClient.lastError + ")";
            // مشكلة شبكة أو رفض الاتصال نفسه (403/503...) -> تبديل الصوت لا يفيد: صوت الجهاز فورًا
            boolean voiceProblem = err instanceof EdgeTtsClient.ServiceException
                    && ((EdgeTtsClient.ServiceException) err).httpCode == 0;
            if (!voiceProblem) {
                awaitingKey = null;
                fallbackToDevice((EdgeTtsClient.isNetworkFailure(err)
                        ? "تعذّر الاتصال بالصوت العصبي (تأكد من الإنترنت)، تم التحويل لصوت الجهاز تلقائيًا."
                        : "الصوت العصبي غير متاح حاليًا من الخادم، تم التحويل لصوت الجهاز تلقائيًا.") + why);
                return;
            }
            // الخادم اتصل لكن هذا الصوت بالذات فشل: نجرّب صوتًا بديلًا بنفس اللغة (حتى مرتين) قبل صوت الجهاز
            PdfSpeechText.Chunk c = currentText.chunks.get(Math.max(0, Math.min(awaitingChunk, currentText.chunks.size() - 1)));
            String badVoice = key.substring(0, key.indexOf('#'));
            badCloudVoices.add(badVoice);
            String alt = pickCloudVoice(c.lang);
            if (alt != null && cloudVoiceSwitches < 2) {
                cloudVoiceSwitches++;
                awaitingKey = requestCloud(currentText, awaitingChunk);
                prefetchAhead(awaitingChunk);
            } else {
                awaitingKey = null;
                fallbackToDevice("تعذّر تشغيل الصوت العصبي، تم التحويل لصوت الجهاز تلقائيًا." + why);
            }
            return;
        }
        if (waiting) {
            awaitingKey = null;
            startPlayer(a, awaitingChunk, awaitingToken);
        } else {
            cloudReady.put(key, a);
            preloadNext();
        }
    }

    private void fallbackToDevice(String message) {
        cloudBroken = true;
        cloudActive = false;
        listener.onError(message);
        if (currentText == null) return;
        lastAppliedLang = null;
        speakChunk(currentChunk, 0);
    }

    private void startPlayer(CloudAudio a, int idx, int tok) {
        playerFdMode = false;
        startPlayerInternal(a, idx, tok, false);
    }

    private void startPlayerInternal(CloudAudio a, int idx, int tok, boolean useFd) {
        if (tok != speakToken || currentText == null) return;
        if (a.data == null || a.data.length < 200) { // لا يوجد ما يُنطق (رموز فقط) - نتخطاه
            advance();
            return;
        }
        try {
            if (!cacheDir.exists()) //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            File f = File.createTempFile("tts_", ".mp3", cacheDir);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(a.data);
            }
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            if (useFd) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    p.setDataSource(in.getFD());
                }
            } else {
                p.setDataSource(f.getAbsolutePath());
            }
            p.setOnPreparedListener(mp -> onPlayerPrepared(mp, tok));
            p.setOnCompletionListener(mp -> {
                if (tok == speakToken && mp == player) advance();
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (tok == speakToken && mp == player) {
                    playerDiag = "mp=" + what + "/" + extra + " bytes=" + a.data.length + " head=" + headHex(a.data);
                    retryOrFail(a, idx, tok);
                }
                return true;
            });
            player = p;
            playerFile = f;
            playerAudio = a;
            playerPrepared = false;
            playerPaused = false;
            p.prepareAsync();
        } catch (Throwable t) {
            playerDiag = "ex=" + t.getClass().getSimpleName() + ":" + t.getMessage()
                    + " bytes=" + (a.data == null ? -1 : a.data.length) + " head=" + headHex(a.data);
            retryOrFail(a, idx, tok);
        }
    }

    private static String headHex(byte[] d) {
        if (d == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, d.length); i++) sb.append(String.format(Locale.ROOT, "%02X", d[i]));
        return sb.toString();
    }

    /** أول فشل تشغيل لنفس الجملة: نعيد بمشغّل جديد عبر FileDescriptor؛ الفشل الثاني يُحتسب. */
    private void retryOrFail(CloudAudio a, int idx, int tok) {
        releasePlayer();
        if (!playerFdMode) {
            playerFdMode = true;
            startPlayerInternal(a, idx, tok, true);
            return;
        }
        playerFdMode = false;
        onPlayerError();
    }

    private void onPlayerPrepared(MediaPlayer mp, int tok) {
        if (tok != speakToken || mp != player || currentText == null) return;
        playerPrepared = true;
        cloudPlayErrStreak = 0;
        cloudVoiceSwitches = 0;
        requestFocus();
        try {
            mp.start();
        } catch (Throwable t) {
            playerDiag = "start:" + t.getClass().getSimpleName();
            onPlayerError();
            return;
        }
        applySpeed(mp);
        if (state != State.PLAYING) setState(State.PLAYING);
        if (currentChunk >= 0 && currentChunk < currentText.chunks.size()) {
            PdfSpeechText.Chunk c = currentText.chunks.get(currentChunk);
            currentWord = c.firstWord;
            listener.onSpeaking(currentPage, currentText, currentChunk, currentWord);
        }
        startPoll();
        preloadNext();
    }

    private void onPlayerError() {
        releasePlayer();
        cloudPlayErrStreak++;
        if (cloudPlayErrStreak >= 3) {
            cloudPlayErrStreak = 0;
            fallbackToDevice("تعذّر تشغيل الصوت العصبي، تم التحويل لصوت الجهاز تلقائيًا. [" + playerDiag + "]");
        } else {
            advance(); // نتخطى هذه الجملة ونكمل
        }
    }

    private void applySpeed(MediaPlayer mp) {
        try {
            PlaybackParams pp = mp.getPlaybackParams();
            pp.setSpeed(rate);
            mp.setPlaybackParams(pp);
        } catch (Throwable ignored) {
        }
    }

    private void startPoll() {
        main.removeCallbacks(poll);
        main.postDelayed(poll, POLL_MS);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (player == null || !playerPrepared || playerPaused || state != State.PLAYING) return;
            cloudProgress();
            main.postDelayed(this, POLL_MS);
        }
    };

    /** يحدّد الكلمة المنطوقة من موضع التشغيل (بتوقيت الخادم لو متوفر، وإلا بالتناسب مع طول الصوت). */
    private void cloudProgress() {
        MediaPlayer p = player;
        CloudAudio a = playerAudio;
        PdfSpeechText.PageText t = currentText;
        if (p == null || a == null || t == null) return;
        if (currentChunk < 0 || currentChunk >= t.chunks.size()) return;
        int pos;
        try {
            pos = p.getCurrentPosition();
        } catch (Throwable e) {
            return;
        }
        pos -= (int) (CLOUD_LAG_MS * Math.max(0.5f, rate)); // زمن الوسائط لا الزمن الحقيقي
        PdfSpeechText.Chunk c = t.chunks.get(currentChunk);
        final boolean mapped = a.spoken != null && a.spoken.text.length() > 0;
        int len = Math.max(1, mapped ? a.spoken.text.length() : c.end - c.start);
        int off;
        if (a.wordMs != null && a.wordMs.length > 0) {
            int found = -1;
            for (int k = 0; k < a.wordMs.length; k++) {
                if (a.wordMs[k] <= pos) found = k;
                else break;
            }
            off = found < 0 ? 0 : a.wordChar[found];
        } else {
            int dur = 0;
            try {
                dur = p.getDuration();
            } catch (Throwable ignored) {
            }
            off = dur > 0 ? (int) (len * Math.min(1f, pos / (float) dur)) : 0;
        }
        off = Math.max(0, Math.min(len - 1, off));
        if (mapped) off = a.spoken.toOriginal(off);
        int w = Math.max(c.firstWord, Math.min(c.lastWord, t.wordAtOffset(c.start + off)));
        if (w != currentWord) {
            currentWord = w;
            listener.onSpeaking(currentPage, t, currentChunk, w);
        }
    }

    /** يجهّز مشغّل الجملة التالية (لو صوتها جاهز) أثناء نطق الحالية. */
    private void preloadNext() {
        if (!cloudActive || currentText == null || player == null) return;
        int idx = currentChunk + 1;
        if (idx >= currentText.chunks.size()) return;
        PdfSpeechText.Chunk c = currentText.chunks.get(idx);
        String key = cloudKey(cloudVoiceFor(c.lang), currentText.pageIndex, idx);
        if (nextPlayer != null) {
            if (key.equals(nextKey)) return;
            releaseNext();
        }
        CloudAudio a = cloudReady.get(key);
        if (a == null || a.data == null || a.data.length < 200) return;
        try {
            if (!cacheDir.exists()) //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            File f = File.createTempFile("tts_", ".mp3", cacheDir);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(a.data);
            }
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            p.setDataSource(f.getAbsolutePath());
            p.setOnPreparedListener(mp -> {
                if (mp == nextPlayer) nextPrepared = true;
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (mp == nextPlayer) releaseNext();
                return true;
            });
            nextPlayer = p;
            nextFile = f;
            nextKey = key;
            nextAudio = a;
            nextPrepared = false;
            p.prepareAsync();
        } catch (Throwable t) {
            releaseNext();
        }
    }

    /** يبدأ الجملة الجاهزة مسبقًا فورًا (بدون كتابة ملف ولا prepare). false = غير ممكن فنكمل بالطريقة العادية. */
    private boolean adoptPreloaded(int idx, int tok, String key) {
        MediaPlayer p = nextPlayer;
        File f = nextFile;
        CloudAudio a = nextAudio;
        nextPlayer = null;
        nextFile = null;
        nextKey = null;
        nextAudio = null;
        nextPrepared = false;
        if (p == null || a == null) return false;
        try {
            p.setOnPreparedListener(null);
            p.setOnCompletionListener(mp -> {
                if (tok == speakToken && mp == player) advance();
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (tok == speakToken && mp == player) {
                    playerDiag = "mp=" + what + "/" + extra + " bytes=" + a.data.length + " (preloaded)";
                    retryOrFail(a, idx, tok);
                }
                return true;
            });
            cloudReady.remove(key);
            player = p;
            playerFile = f;
            playerAudio = a;
            playerPrepared = true;
            playerPaused = false;
            onPlayerPrepared(p, tok);
            return true;
        } catch (Throwable t) {
            try {
                p.release();
            } catch (Throwable ignored) {
            }
            if (f != null) //noinspection ResultOfMethodCallIgnored
                f.delete();
            player = null;
            playerFile = null;
            playerAudio = null;
            playerPrepared = false;
            return false;
        }
    }

    private void releaseNext() {
        MediaPlayer p = nextPlayer;
        nextPlayer = null;
        if (p != null) {
            try {
                p.setOnPreparedListener(null);
                p.setOnCompletionListener(null);
                p.setOnErrorListener(null);
            } catch (Throwable ignored) {
            }
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
        if (nextFile != null) {
            //noinspection ResultOfMethodCallIgnored
            nextFile.delete();
            nextFile = null;
        }
        nextKey = null;
        nextAudio = null;
        nextPrepared = false;
    }

    private void releasePlayer() {
        main.removeCallbacks(poll);
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try {
                p.setOnPreparedListener(null);
                p.setOnCompletionListener(null);
                p.setOnErrorListener(null);
            } catch (Throwable ignored) {
            }
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
        if (playerFile != null) {
            //noinspection ResultOfMethodCallIgnored
            playerFile.delete();
            playerFile = null;
        }
        playerAudio = null;
        playerPrepared = false;
        playerPaused = false;
    }

    /** يمسح كل الأصوات المجهّزة/الجارية ويُبطل نتائج الطلبات المتأخرة. */
    private void resetCloud() {
        releaseNext();
        cloudGen++;
        cloudReady.clear();
        cloudPending.clear();
        cloudRetried.clear();
        awaitingKey = null;
        cloudPlayErrStreak = 0;
    }

    private void cleanCacheDir() {
        try {
            File[] files = cacheDir.listFiles();
            if (files == null) return;
            for (File f : files) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ تركيز الصوت (للصوت الأونلاين فقط)

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> main.post(() -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            focusHeld = false;
            resumeOnFocusGain = false;
            if (state == State.PLAYING || state == State.LOADING) pauseInternal(false);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (state == State.PLAYING || state == State.LOADING) {
                resumeOnFocusGain = true;
                pauseInternal(false); // نُبقي الطلب حتى يصلنا GAIN
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            try {
                if (player != null) player.setVolume(0.25f, 0.25f);
            } catch (Throwable ignored) {
            }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            try {
                if (player != null) player.setVolume(1f, 1f);
            } catch (Throwable ignored) {
            }
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false;
                resume();
            }
        }
    });

    private void requestFocus() {
        if (audio == null || focusHeld) return;
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
            focusHeld = true;
        } catch (Throwable ignored) {
        }
    }

    private void abandonFocus() {
        focusHeld = false;
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
        speakToken++;
        releasePlayer();
        resetCloud();
        abandonFocus();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable ignored) {
        }
        tts = null;
        synthPool.shutdownNow();
        releaseNext();
        io.execute(() -> {
            if (source != null) source.close();
            source = null;
        });
        io.shutdown();
        cleanCacheDir();
    }
}
