package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * شاشة إدارة محرك الصوت البشري: نمط جاهز، منزلقات الإيقاع، مفاتيح التفاصيل، التعلّم من السلوك،
 * تدريب النطق بالذكاء الاصطناعي، وتجربة فورية بالصوت العصبي المختار في قارئ PDF.
 */
public class VoiceEngineSettingsActivity extends AppCompatActivity {

    private static final String DEFAULT_SAMPLE =
            "مقدمة\nمرحبًا بك. هل تسمعني بوضوح؟ هذه تجربة للصوت، وكيف تتغيّر النغمة بين الجمل! "
                    + "(وهذه جملة جانبية قصيرة). أما النتيجة النهائية فهي 120 درجة.";

    private static final int[] PITCH_HZ = {0, 8, 16, -8, -16}; // نفس جدول القارئ

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean binding;
    private MediaPlayer player;
    private int testGen;

    private MaterialSwitch swEngine, swQuestion, swHeading, swParen, swBreath, swLearn;
    private TextView presetValue, learnStats, aiStatus, dictStatus, testStatus;
    private TextView natVal, exVal, pauseVal, rateVal, pitchVal;
    private SeekBar seekNat, seekEx, seekPause, seekRate, seekPitch;
    private EditText sampleInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_engine);

        VoiceEngineConfig.init(this);
        VoiceBehaviorLearner.init(this);
        try {
            SpeechLearner.init(getFilesDir());
        } catch (Throwable ignored) {
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        swEngine = findViewById(R.id.switch_engine_on);
        swQuestion = findViewById(R.id.switch_question);
        swHeading = findViewById(R.id.switch_heading);
        swParen = findViewById(R.id.switch_paren);
        swBreath = findViewById(R.id.switch_breath);
        swLearn = findViewById(R.id.switch_learn);
        presetValue = findViewById(R.id.preset_value);
        learnStats = findViewById(R.id.learn_stats);
        aiStatus = findViewById(R.id.ai_tutor_status);
        dictStatus = findViewById(R.id.dict_status);
        testStatus = findViewById(R.id.test_status);
        natVal = findViewById(R.id.natural_value);
        exVal = findViewById(R.id.express_value);
        pauseVal = findViewById(R.id.pause_value);
        rateVal = findViewById(R.id.rate_value);
        pitchVal = findViewById(R.id.pitch_value);
        seekNat = findViewById(R.id.seek_natural);
        seekEx = findViewById(R.id.seek_express);
        seekPause = findViewById(R.id.seek_pause);
        seekRate = findViewById(R.id.seek_rate);
        seekPitch = findViewById(R.id.seek_pitch);
        sampleInput = findViewById(R.id.sample_input);
        sampleInput.setText(DEFAULT_SAMPLE);

        bindSwitch(R.id.row_engine_on, swEngine, VoiceEngineConfig::setEnabled);
        bindSwitch(R.id.row_question, swQuestion, VoiceEngineConfig::setQuestionRise);
        bindSwitch(R.id.row_heading, swHeading, VoiceEngineConfig::setHeadingStyle);
        bindSwitch(R.id.row_paren, swParen, VoiceEngineConfig::setParentheticalStyle);
        bindSwitch(R.id.row_breath, swBreath, VoiceEngineConfig::setBreathPauses);
        bindSwitch(R.id.row_learn, swLearn, VoiceEngineConfig::setBehaviorLearning);

        bindSeek(seekNat, natVal, 0, "%", VoiceEngineConfig::setNaturalness);
        bindSeek(seekEx, exVal, 0, "%", VoiceEngineConfig::setExpressiveness);
        bindSeek(seekPause, pauseVal, 50, "%", VoiceEngineConfig::setPausePercent);
        bindSeek(seekRate, rateVal, -30, "%", VoiceEngineConfig::setRateOffset);
        bindSeek(seekPitch, pitchVal, -20, " Hz", VoiceEngineConfig::setPitchOffset);

        findViewById(R.id.row_preset).setOnClickListener(v -> showPresetDialog());
        findViewById(R.id.row_ai_tutor).setOnClickListener(v -> runAiTutor());
        findViewById(R.id.row_reset_learning).setOnClickListener(v -> confirmResetLearning());
        findViewById(R.id.row_dicts).setOnClickListener(v -> {
            Intent i = new Intent(this, SettingsActivity.class);
            i.putExtra("open_dict_packs", true);
            startActivity(i);
        });
        findViewById(R.id.btn_test).setOnClickListener(v -> runTest());

        loadFromConfig();
    }

    @Override
    protected void onResume() {
        super.onResume();
        VoiceEngineConfig.reload();
        loadFromConfig();
    }

    @Override
    protected void onPause() {
        super.onPause();
        VoiceBehaviorLearner.flush();
    }

    @Override
    protected void onDestroy() {
        testGen++;
        releasePlayer();
        executor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ ربط العناصر

    private interface BoolSetter {
        void set(boolean v);
    }

    private interface IntSetter {
        void set(int v);
    }

    private void bindSwitch(int rowId, final MaterialSwitch sw, final BoolSetter setter) {
        findViewById(rowId).setOnClickListener(v -> {
            if (binding) return;
            boolean now = !sw.isChecked();
            sw.setChecked(now);
            setter.set(now);
            refreshLabels();
        });
    }

    /** منزلق: يحدّث الرقم أثناء السحب، ويحفظ عند رفع الإصبع فقط (حتى لا يتكرر رفع نسخة الكاش). */
    private void bindSeek(final SeekBar bar, final TextView label, final int offset, final String unit,
                          final IntSetter setter) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                label.setText(format(progress + offset, unit, offset < 0));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (binding) return;
                setter.set(s.getProgress() + offset);
                refreshLabels();
            }
        });
    }

    private static String format(int v, String unit, boolean signed) {
        String n = (signed && v > 0 ? "+" : "") + v + unit;
        return "\u200E" + n + "\u200E"; // اتجاه يسار-لليمين كي لا ينقلب الإشارة والرقم
    }

    private void loadFromConfig() {
        binding = true;
        try {
            swEngine.setChecked(VoiceEngineConfig.isEnabled());
            swQuestion.setChecked(VoiceEngineConfig.questionRise());
            swHeading.setChecked(VoiceEngineConfig.headingStyle());
            swParen.setChecked(VoiceEngineConfig.parentheticalStyle());
            swBreath.setChecked(VoiceEngineConfig.breathPauses());
            swLearn.setChecked(VoiceEngineConfig.behaviorLearning());
            seekNat.setProgress(VoiceEngineConfig.naturalness());
            seekEx.setProgress(VoiceEngineConfig.expressiveness());
            seekPause.setProgress(VoiceEngineConfig.pausePercent() - 50);
            seekRate.setProgress(VoiceEngineConfig.rateOffset() + 30);
            seekPitch.setProgress(VoiceEngineConfig.pitchOffset() + 20);
            natVal.setText(format(VoiceEngineConfig.naturalness(), "%", false));
            exVal.setText(format(VoiceEngineConfig.expressiveness(), "%", false));
            pauseVal.setText(format(VoiceEngineConfig.pausePercent(), "%", false));
            rateVal.setText(format(VoiceEngineConfig.rateOffset(), "%", true));
            pitchVal.setText(format(VoiceEngineConfig.pitchOffset(), " Hz", true));
        } finally {
            binding = false;
        }
        refreshLabels();
    }

    private void refreshLabels() {
        presetValue.setText(VoiceEngineConfig.presetName());
        learnStats.setText(VoiceBehaviorLearner.stats());
        aiStatus.setText("كلمات جاهزة للتدريب: " + VoiceBehaviorLearner.suspectCount()
                + " — تُرسل كلمات مفردة فقط ولا يُحفظ شيء بدون موافقتك");
        try {
            dictStatus.setText(DictionaryPacks.summary(this));
        } catch (Throwable t) {
            dictStatus.setText("القاموس الأساسي");
        }
    }

    // ------------------------------------------------------------------ الأنماط

    private void showPresetDialog() {
        final String[] names = {
                VoiceEngineConfig.PRESET_NAMES[VoiceEngineConfig.PRESET_BALANCED],
                VoiceEngineConfig.PRESET_NAMES[VoiceEngineConfig.PRESET_STORY],
                VoiceEngineConfig.PRESET_NAMES[VoiceEngineConfig.PRESET_STUDY],
                VoiceEngineConfig.PRESET_NAMES[VoiceEngineConfig.PRESET_NEWS]
        };
        int checked = VoiceEngineConfig.preset() < names.length ? VoiceEngineConfig.preset() : -1;
        new AlertDialog.Builder(this)
                .setTitle("نمط القراءة")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    VoiceEngineConfig.applyPreset(which);
                    loadFromConfig();
                    d.dismiss();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ------------------------------------------------------------------ التعلّم

    private void confirmResetLearning() {
        new AlertDialog.Builder(this)
                .setTitle("مسح تعلّم السلوك؟")
                .setMessage("تُصفَّر إحصاءات الرجوع والكلمات المشتبه بها. ما علّمته أنت أو وافقت عليه من نطق الكلمات لا يُمسّ.")
                .setPositiveButton("مسح", (d, w) -> {
                    VoiceBehaviorLearner.resetAll();
                    refreshLabels();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void runAiTutor() {
        final List<String> words = VoiceBehaviorLearner.topSuspects(VoiceAiTutor.MAX_WORDS);
        if (words.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("لا توجد كلمات بعد")
                    .setMessage("أثناء قراءة PDF بالصوت: عندما ترجع لمقطع لأنك لم تفهمه، أسجّل الكلمات التي لا يستطيع "
                            + "القاموس تشكيلها بيقين. بعد أن تتجمّع كلمات يمكنك هنا طلب تشكيلها من الذكاء الاصطناعي.")
                    .setPositiveButton("حسنًا", null)
                    .show();
            return;
        }
        aiStatus.setText("جارٍ سؤال الذكاء الاصطناعي عن " + words.size() + " كلمة…");
        VoiceAiTutor.suggest(words, new VoiceAiTutor.Callback() {
            @Override
            public void onResult(final List<VoiceAiTutor.Suggestion> suggestions, final int requested) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    refreshLabels();
                    showSuggestions(suggestions, requested);
                });
            }

            @Override
            public void onError(final String message) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    refreshLabels();
                    Toast.makeText(VoiceEngineSettingsActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showSuggestions(final List<VoiceAiTutor.Suggestion> list, int requested) {
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("لا اقتراحات موثوقة")
                    .setMessage("سألتُ عن " + requested + " كلمة ولم يصل اقتراح تجاوز التحقق "
                            + "(الحروف مطابقة وفيه تشكيل). لم يُحفظ شيء.")
                    .setPositiveButton("حسنًا", null)
                    .show();
            return;
        }
        final String[] items = new String[list.size()];
        final boolean[] checked = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) {
            items[i] = list.get(i).word + "  \u2190  " + list.get(i).spoken;
            checked[i] = true;
        }
        new AlertDialog.Builder(this)
                .setTitle("اقتراحات النطق (" + list.size() + " من " + requested + ")")
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("حفظ المحدد", (d, w) -> {
                    List<VoiceAiTutor.Suggestion> ok = new ArrayList<>();
                    for (int i = 0; i < list.size(); i++) if (checked[i]) ok.add(list.get(i));
                    int n = VoiceAiTutor.apply(ok);
                    Toast.makeText(this, "تم حفظ نطق " + n + " كلمة في قاموس النطق.", Toast.LENGTH_SHORT).show();
                    refreshLabels();
                })
                .setNegativeButton("تجاهل", null)
                .show();
    }

    // ------------------------------------------------------------------ التجربة الفورية

    /** يقسم النص إلى جمل (حتى 5) على علامات النهاية والأسطر. */
    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                flush(out, cur);
                continue;
            }
            cur.append(c);
            boolean end = ".!?\u061F\u061B\u2026".indexOf(c) >= 0;
            boolean boundary = i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1));
            if (end && boundary) flush(out, cur);
        }
        flush(out, cur);
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        String s = cur.toString().trim();
        cur.setLength(0);
        if (!s.isEmpty()) out.add(s);
    }

    /** الأسلوب الأساسي نفسه الذي يستعمله القارئ (نمط الأداء + طبقة المستخدم) قبل طبقة الإيقاع البشري. */
    private EdgeTtsClient.Style baseStyle() {
        SharedPreferences p = getSharedPreferences("pdf_tts", Context.MODE_PRIVATE);
        int profile = p.getInt("profile", 1);
        int idx = Math.max(0, Math.min(PITCH_HZ.length - 1, p.getInt("pitch_idx", 0)));
        switch (profile) {
            case 0:
                return new EdgeTtsClient.Style(0, PITCH_HZ[idx], 0, 0);
            case 2:
                return new EdgeTtsClient.Style(-12, PITCH_HZ[idx], 350, 140);
            default:
                return new EdgeTtsClient.Style(-5, PITCH_HZ[idx], 150, 60);
        }
    }

    private void runTest() {
        String text = sampleInput.getText() == null ? "" : sampleInput.getText().toString().trim();
        if (text.isEmpty()) text = DEFAULT_SAMPLE;
        final List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) return;

        SharedPreferences p = getSharedPreferences("pdf_tts", Context.MODE_PRIVATE);
        final String voice = p.getString("cvoice_ar", "ar-SA-ZariyahNeural");
        final EdgeTtsClient.Style base = baseStyle();
        final Context app = getApplicationContext();
        final File out = new File(getCacheDir(), "voice_preview.mp3");
        final int gen = ++testGen;

        releasePlayer();
        testStatus.setText("جارٍ تجهيز الصوت…");
        findViewById(R.id.btn_test).setEnabled(false);

        executor.execute(() -> {
            String error = null;
            try {
                if (!TashkeelDict.isReady()) TashkeelDict.load(app); // نفس القاموس الذي يستعمله القارئ
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                for (String s : sentences) {
                    if (gen != testGen) return;
                    SpeechPrep.Spoken spoken = SpeechPrep.prepare(EdgeTtsClient.sanitize(s), "ar", "en", false);
                    if (spoken.text.trim().isEmpty()) continue;
                    EdgeTtsClient.Style st = HumanProsody.shape(s, "ar", false, base);
                    EdgeTtsClient.Result r = EdgeTtsClient.synthesize(spoken.text, voice, st);
                    if (r != null && r.audio != null) all.write(r.audio);
                }
                if (all.size() < 200) {
                    error = "لم يصل صوت من الخادم.";
                } else {
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        fo.write(all.toByteArray());
                    }
                }
            } catch (Throwable t) {
                error = EdgeTtsClient.isNetworkFailure(t) ? "تعذّر الاتصال بالإنترنت." : "تعذّر تجهيز الصوت.";
            }
            final String err = error;
            runOnUiThread(() -> {
                if (isFinishing() || gen != testGen) return;
                findViewById(R.id.btn_test).setEnabled(true);
                if (err != null) {
                    testStatus.setText(err);
                } else {
                    playFile(out);
                }
            });
        });
    }

    private void playFile(File f) {
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> testStatus.setText("انتهت التجربة."));
            player.setOnErrorListener((mp, what, extra) -> {
                testStatus.setText("تعذّر تشغيل الصوت.");
                return true;
            });
            player.setOnPreparedListener(mp -> {
                testStatus.setText("يعمل الصوت بالإعدادات الحالية…");
                mp.start();
            });
            player.prepareAsync();
        } catch (Throwable t) {
            testStatus.setText("تعذّر تشغيل الصوت.");
            releasePlayer();
        }
    }

    private void releasePlayer() {
        if (player == null) return;
        try {
            player.release();
        } catch (Throwable ignored) {
        }
        player = null;
    }
}
