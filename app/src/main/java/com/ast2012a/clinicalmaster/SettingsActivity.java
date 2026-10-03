package com.ast2012a.clinicalmaster;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * شاشة الإعدادات (بأسلوب تطبيق Claude): مجموعات صفوف، كل صف أيقونة + عنوان +
 * وصف اختياري. مفتاح الإشعارات هنا بيفعّل/يوقف فقط تذكير جلسات اليوم
 * الحقيقي (SessionReminder، AlarmManager محلي بدون سيرفر) - بدون أي
 * إشعار تجريبي وهمي عند التفعيل.
 *
 * Phizyo AI ليس له سوى مزوّد واحد ثابت (AiClient.FIXED_WORKER_URL) مبني
 * داخل التطبيق نفسه - لا يوجد مفتاح API ولا رابط قابل للتعديل من هنا.
 *
 * ملحوظة: حُذفت من هذه الشاشة أوامر "مشاركة التطبيق" و"تقييم التطبيق" و"تواصل
 * معنا" لأنها غير ضرورية.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS = "settings_prefs";
    private static final String KEY_NOTIF_ENABLED = "notif_enabled";

    private TextView themeValue;
    private TextView notifStatus;
    private TextView updateStatus;
    private View updateProgress;
    private android.content.res.ColorStateList updateStatusDefaultColors;
    private android.graphics.Typeface updateStatusDefaultTypeface;
    private TextView instructionsStatus;
    private TextView builtinOverridesStatus;
    private TextView dictPacksStatus;
    private ActivityResultLauncher<String[]> dictImportPicker;
    private MaterialSwitch notifSwitch;
    private MaterialSwitch cloudAutoBackupSwitch;
    private TextView cloudStatus;
    private TextView cloudAccountStatus;
    private TextView cloudBackupSub;
    private TextView cloudRestoreSub;
    private ActivityResultLauncher<String> permissionLauncher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        themeValue = findViewById(R.id.theme_value);
        notifStatus = findViewById(R.id.notif_status);
        instructionsStatus = findViewById(R.id.ai_instructions_status);
        notifSwitch = findViewById(R.id.switch_notif);

        TextView versionLabel = findViewById(R.id.settings_version_label);
        versionLabel.setText("الإصدار " + getVersionLabel());

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        setNotifEnabled(true);
                        Toast.makeText(this, "تم تفعيل تذكير جلسات اليوم - إشعار حقيقي كل صباح الساعة 8:00.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "لم يتم منح إذن الإشعارات.", Toast.LENGTH_SHORT).show();
                    }
                    refreshNotifRow();
                });

        // Phizyo AI
        findViewById(R.id.btn_test_worker).setOnClickListener(v -> testWorkerConnection());
        findViewById(R.id.btn_ai_instructions).setOnClickListener(v -> showInstructionsDialog());
        findViewById(R.id.btn_ai_how).setOnClickListener(v -> showHowItWorksDialog());
        findViewById(R.id.btn_clear_chat_history).setOnClickListener(v -> confirmClearChatHistory());

        // عام
        findViewById(R.id.btn_theme_mode).setOnClickListener(v -> showThemeDialog());
        findViewById(R.id.btn_notif).setOnClickListener(v -> toggleNotifications());

        // البيانات
        findViewById(R.id.btn_export).setOnClickListener(v -> exportBackup());
        findViewById(R.id.btn_clear_all).setOnClickListener(v -> confirmClearAll());
        builtinOverridesStatus = findViewById(R.id.builtin_overrides_status);
        findViewById(R.id.btn_manage_builtin_cases).setOnClickListener(v -> showManageBuiltinCasesDialog());

        // مكتبات الكلمات (قاموس النطق)
        dictPacksStatus = findViewById(R.id.dict_packs_status);
        dictImportPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importDictPackFromUri(uri);
        });
        findViewById(R.id.btn_dict_packs).setOnClickListener(v -> showDictPacksDialog());

        // محرك الصوت البشري (إيقاع، تعلّم، مدرّب ذكي)
        findViewById(R.id.btn_voice_engine).setOnClickListener(
                v -> startActivity(new Intent(this, VoiceEngineSettingsActivity.class)));

        // النسخ السحابي
        cloudAutoBackupSwitch = findViewById(R.id.switch_cloud_auto_backup);
        cloudStatus = findViewById(R.id.cloud_status);
        cloudBackupSub = findViewById(R.id.cloud_backup_sub);
        cloudRestoreSub = findViewById(R.id.cloud_restore_sub);
        cloudAccountStatus = findViewById(R.id.cloud_account_status);
        findViewById(R.id.btn_cloud_account).setOnClickListener(v -> showCloudAccountDialog());
        findViewById(R.id.btn_cloud_auto_backup).setOnClickListener(v -> toggleCloudAutoBackup());
        findViewById(R.id.btn_cloud_backup_now).setOnClickListener(v -> backupNowClicked());
        findViewById(R.id.btn_cloud_restore).setOnClickListener(v -> confirmRestoreFromCloud());

        // الملفات السحابية (PDF/مستندات عبر Cloudflare Worker - منفصل عن Firebase)
        findViewById(R.id.btn_cloud_files).setOnClickListener(v -> {
            startActivity(new Intent(this, CloudStorageActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });

        // عن التطبيق
        updateStatus = findViewById(R.id.update_status);
        updateProgress = findViewById(R.id.update_progress);
        updateStatusDefaultColors = updateStatus.getTextColors();
        updateStatusDefaultTypeface = updateStatus.getTypeface();
        showUpdateStatus(UpdateManager.statusText(this));
        UpdateManager.cleanupTemp(this);
        findViewById(R.id.btn_check_update).setOnClickListener(v ->
                UpdateManager.checkInteractive(this, this::showUpdateStatus));
        findViewById(R.id.btn_about).setOnClickListener(v -> showAboutDialog());
        findViewById(R.id.btn_privacy_policy).setOnClickListener(v -> {
            startActivity(new Intent(this, PrivacyPolicyActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });

        refreshThemeRow();
        refreshNotifRow();
        refreshInstructionsRow();
        refreshCloudRows();
        refreshBuiltinOverridesRow();
        refreshDictPacksRow();

        // يُفتح من شاشة محرك الصوت البشري (صف "القواميس المدمجة ومكتبات الكلمات")
        if (getIntent().getBooleanExtra("open_dict_packs", false)) showDictPacksDialog();
    }

    // -----------------------------------------------------------------
    // المظهر
    // -----------------------------------------------------------------

    private void refreshThemeRow() {
        themeValue.setText(ThemeManager.labelFor(ThemeManager.getCurrentMode(this)));
    }

    private void showThemeDialog() {
        final String[] modes = {ThemeManager.MODE_SYSTEM, ThemeManager.MODE_LIGHT, ThemeManager.MODE_DARK};
        String[] labels = new String[modes.length];
        int checked = 0;
        String current = ThemeManager.getCurrentMode(this);
        for (int i = 0; i < modes.length; i++) {
            labels[i] = ThemeManager.labelFor(modes[i]);
            if (modes[i].equals(current)) checked = i;
        }
        new ClaudeDialog(this)
                .setTitle("المظهر")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (!modes[which].equals(current)) {
                        ThemeManager.setMode(this, modes[which]);
                        recreate();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // -----------------------------------------------------------------
    // Phizyo AI
    // -----------------------------------------------------------------

    private void refreshInstructionsRow() {
        String saved = AiPrompts.getCustomInstructions(this);
        boolean has = saved != null && !saved.trim().isEmpty();
        instructionsStatus.setText(has ? "مفعّلة" : "غير مضبوطة");
    }

    /** تعليمات مخصّصة لـ Phizyo AI: تُضاف تلقائيًا لتعليمات النظام في كل محادثة. */
    private void showInstructionsDialog() {
        TextInputEditText input = new TextInputEditText(this);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        input.setMinLines(5);
        input.setMaxLines(9);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextDirection(View.TEXT_DIRECTION_RTL);
        input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        input.setTextSize(15.5f);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_tertiary));
        input.setHint("مثال: ركّز دايمًا على التمارين المنزلية، أو اجعل الإجابات مختصرة جدًا بنقاط.");
        input.setText(AiPrompts.getCustomInstructions(this));

        new ClaudeDialog(this)
                .setTitle("تعليمات مخصّصة")
                .setMessage("تُضاف هذه التعليمات تلقائيًا لكل محادثة مع Phizyo AI.")
                .setView(input)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    String text = input.getText() == null ? "" : input.getText().toString();
                    AiPrompts.setCustomInstructions(this, text);
                    refreshInstructionsRow();
                    Toast.makeText(this, "تم حفظ التعليمات.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .setNeutralButton("مسح التعليمات", (dialog, which) -> {
                    AiPrompts.setCustomInstructions(this, "");
                    refreshInstructionsRow();
                })
                .show();
    }

    private void showHowItWorksDialog() {
        String message = "1) Phizyo AI نفسه يقرر لكل رسالة هل يحتاج للبحث أم لا. إذا كانت الرسالة دردشة عادية يرد عليك مباشرة بدون أي بحث.\n\n" +
                "2) إذا كانت الرسالة سؤالًا إكلينيكيًا يتحقق أولًا من قاعدة بيانات الجهاز (130 حالة موثقة)، والتطابق المباشر يُجاب فورًا بدون إنترنت.\n\n" +
                "3) غير ذلك يُستخدم بحث Physiopedia حصرًا (مرجع متخصص في العلاج الطبيعي فقط - بدون أي بحث عام آخر). الصياغة النهائية دائمًا عبر Phizyo AI وتلتزم بأي تعليمات مخصّصة تضيفها.\n\n" +
                "4) المساعد يتذكر آخر رسائل نفس المحادثة (ذاكرة قصيرة المدى محلية على جهازك) عشان يفهم الإشارات المختصرة بالرجوع لما قلته قبل شوي، بدل ما يعامل كل رسالة كأنها منفصلة تمامًا.";
        new ClaudeDialog(this)
                .setTitle("كيف يعمل Phizyo AI؟")
                .setMessage(message)
                .setPositiveButton("حسنًا", null)
                .show();
    }

    private void confirmClearChatHistory() {
        new ClaudeDialog(this)
                .setTitle("مسح سجل المحادثة")
                .setMessage("سيتم حذف سجل محادثة Phizyo AI بالكامل. متأكد؟")
                .setPositiveButton("مسح", (dialog, which) -> {
                    AiChatStore.clear(this);
                    Toast.makeText(this, "تم مسح سجل المحادثة.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    /** اختبار اتصال فوري بالووركر الثابت (AiClient.FIXED_WORKER_URL). */
    private void testWorkerConnection() {
        Toast.makeText(this, "جاري اختبار الاتصال بـ Phizyo AI...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> AiClient.testWorker(new AiClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                runOnUiThread(() -> new ClaudeDialog(SettingsActivity.this)
                        .setTitle("Phizyo AI يعمل")
                        .setMessage("رد الخادم:\n\n" + reply)
                        .setPositiveButton("تمام", null)
                        .show());
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> new ClaudeDialog(SettingsActivity.this)
                        .setTitle("تعذر الاتصال بـ Phizyo AI")
                        .setMessage("تفاصيل الخطأ:\n" + message +
                                "\n\nلو الرسالة بتقول \"model deprecated\" أو حاجة شبهها، يبقى Cloudflare قفلوا الموديل المستخدم وتحتاج تحدّث اسم الموديل في worker.js. غير كده تأكد إن الووركر منشور (Deployed) وفعّال، وإن جهازك متصل بالإنترنت.")
                        .setPositiveButton("تمام", null)
                        .show());
            }
        }));
    }

    // -----------------------------------------------------------------
    // الإشعارات
    // -----------------------------------------------------------------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean isNotifEnabled() {
        return prefs().getBoolean(KEY_NOTIF_ENABLED, false) && hasNotificationPermission();
    }

    private void setNotifEnabled(boolean value) {
        prefs().edit().putBoolean(KEY_NOTIF_ENABLED, value).apply();
        // تنبيه الصباح بجلسات اليوم: يُجدوَل عند التفعيل ويُلغى عند الإيقاف
        if (value) SessionReminder.schedule(this);
        else SessionReminder.cancel(this);
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void refreshNotifRow() {
        boolean enabled = isNotifEnabled();
        notifSwitch.setChecked(enabled);
        notifStatus.setText(enabled ? "مفعّلة · تنبيه كل صباح 8:00 بمرضى جلسات اليوم" : "غير مفعّلة");
    }

    /** الصف كله يعمل كمفتاح: تشغيل (مع طلب الإذن لو لزم) أو إيقاف. */
    private void toggleNotifications() {
        if (isNotifEnabled()) {
            setNotifEnabled(false);
            refreshNotifRow();
            return;
        }
        if (!hasNotificationPermission()) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        setNotifEnabled(true);
        Toast.makeText(this, "تم تفعيل تذكير جلسات اليوم - إشعار حقيقي كل صباح الساعة 8:00.", Toast.LENGTH_SHORT).show();
        refreshNotifRow();
    }

    // -----------------------------------------------------------------
    // البيانات
    // -----------------------------------------------------------------

    private void exportBackup() {
        try {
            var cases = DataManager.loadCustomCases(this);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (CaseItem c : cases) arr.put(c.toJson());

            String fileName = "backup_export.json";
            FileOutputStream fos = openFileOutput(fileName, MODE_PRIVATE);
            fos.write(arr.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();

            Toast.makeText(this, "تم الحفظ في تخزين التطبيق: " + fileName, Toast.LENGTH_LONG).show();
        } catch (IOException | org.json.JSONException e) {
            Toast.makeText(this, "تعذر إتمام التصدير.", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearAll() {
        new ClaudeDialog(this)
                .setTitle("مسح كل الحالات المخصصة")
                .setMessage("سيتم حذف كل الحالات المخصصة نهائيًا. متأكد؟")
                .setPositiveButton("مسح الكل", (dialog, which) -> {
                    DataManager.clearAllCustomCases(this);
                    Toast.makeText(this, "تم مسح كل الحالات المخصصة.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // -----------------------------------------------------------------
    // إدارة تعديلات المستخدم على "الحالات الثابتة" (المدمجة من دليل الجهاز)
    // -----------------------------------------------------------------

    private void refreshBuiltinOverridesRow() {
        int count = DataManager.countBuiltinOverrides(this);
        builtinOverridesStatus.setText(count == 0
                ? "لا توجد تعديلات على حالات الجهاز الأساسية"
                : count + (count == 1 ? " حالة معدَّلة" : " حالات معدَّلة") + " عن نص الدليل الأصلي");
    }

    private void showManageBuiltinCasesDialog() {
        List<String> titles = DataManager.listOverriddenTitles(this);
        if (titles.isEmpty()) {
            new ClaudeDialog(this)
                    .setTitle("الحالات الأساسية المعدَّلة")
                    .setMessage("كل حالات الجهاز الأساسية المدمجة مع التطبيق لا تزال بنصها الأصلي من دليل الجهاز الرسمي. لتعديل أي حالة، افتحها من شاشة التفاصيل واضغط \"تعديل\".")
                    .setPositiveButton("حسنًا", null)
                    .show();
            return;
        }
        final String[] items = titles.toArray(new String[0]);
        new ClaudeDialog(this)
                .setTitle("الحالات الأساسية المعدَّلة (" + items.length + ")")
                .setMessage("اضغط على أي حالة لاستعادة نصها الأصلي من دليل الجهاز.")
                .setItems(items, (dialog, which) -> confirmRestoreSingleBuiltinCase(items[which]))
                .setNeutralButton("استعادة الكل", (dialog, which) -> confirmRestoreAllBuiltinCases())
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void confirmRestoreSingleBuiltinCase(String title) {
        new ClaudeDialog(this)
                .setTitle("استعادة النسخة الأصلية")
                .setMessage("استعادة \"" + title + "\" لنصها الأصلي من دليل الجهاز، والتراجع عن تعديلك؟")
                .setPositiveButton("استعادة", (dialog, which) -> {
                    DataManager.resetBuiltinCaseOverride(this, title);
                    refreshBuiltinOverridesRow();
                    Toast.makeText(this, "تم استعادة النص الأصلي.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void confirmRestoreAllBuiltinCases() {
        new ClaudeDialog(this)
                .setTitle("استعادة كل الحالات الأساسية")
                .setMessage("سيتم التراجع عن كل التعديلات على حالات الجهاز الأساسية والعودة لنص الدليل الأصلي في كل واحدة منها. متأكد؟")
                .setPositiveButton("استعادة الكل", (dialog, which) -> {
                    DataManager.resetAllBuiltinOverrides(this);
                    refreshBuiltinOverridesRow();
                    Toast.makeText(this, "تم استعادة كل حالات الجهاز الأساسية.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // -----------------------------------------------------------------
    // مكتبات الكلمات (قاموس النطق): تفعيل/إيقاف، استيراد، حذف، وتنبؤ الذكاء المحلي
    // -----------------------------------------------------------------

    private void refreshDictPacksRow() {
        if (dictPacksStatus != null) dictPacksStatus.setText(DictionaryPacks.summary(this));
    }

    private boolean uiAlive() {
        return !isFinishing() && !isDestroyed();
    }

    private void showDictPacksDialog() {
        final List<DictionaryPacks.Pack> packs = DictionaryPacks.list(this);
        final int n = packs.size();
        final String[] items = new String[n + 1];
        for (int i = 0; i < n; i++) {
            DictionaryPacks.Pack p = packs.get(i);
            items[i] = (p.enabled ? "\u2713  " : "\u25CB  ") + p.title + DictionaryPacks.describe(p);
        }
        final boolean predict = DictionaryPacks.isPredictEnabled(this);
        items[n] = (predict ? "\u2713  " : "\u25CB  ") + "تنبؤ الذكاء المحلي بالكلمات غير الموجودة (تجريبي)";

        new ClaudeDialog(this)
                .setTitle("مكتبات الكلمات")
                .setMessage("اضغط على مكتبة لتفعيلها أو إيقافها ويُعاد دمج القاموس تلقائيًا. الأعلى في القائمة يتقدّم عند تعارض كلمة بين مكتبتين. "
                        + "التنبؤ يشكّل كلمات ليست في أي مكتبة بحسب أنماط الحروف؛ دقته نحو 87% فقط، فهو متوقف ما لم تفعّله.")
                .setItems(items, (dialog, which) -> {
                    if (which == n) {
                        DictionaryPacks.setPredictEnabled(this, !predict);
                    } else {
                        DictionaryPacks.Pack p = packs.get(which);
                        DictionaryPacks.setEnabled(this, p.id, !p.enabled);
                    }
                    applyDictChanges(null);
                    showDictPacksDialog();
                })
                .setPositiveButton("استيراد مكتبة", (dialog, which) -> showDictImportChoiceDialog())
                .setNeutralButton("حذف مكتبة", (dialog, which) -> showDeleteDictPackDialog())
                .setNegativeButton("إغلاق", null)
                .show();
    }

    /** يعيد دمج القاموس في الخلفية بعد أي تغيير، ثم يحدّث سطر الإعدادات. */
    private void applyDictChanges(final String toastWhenDone) {
        TashkeelDict.reloadAsync(getApplicationContext(), () -> runOnUiThread(() -> {
            if (!uiAlive()) return;
            refreshDictPacksRow();
            if (toastWhenDone != null) Toast.makeText(this, toastWhenDone, Toast.LENGTH_LONG).show();
        }));
    }

    private void showDictImportChoiceDialog() {
        final String[] items = {"من ملف على الجهاز", "من رابط (https)"};
        new ClaudeDialog(this)
                .setTitle("استيراد مكتبة كلمات")
                .setMessage("ملف نصي .txt فيه كلمات عربية، مشكولة أو غير مشكولة، كلمة في كل سطر أو نص عادي. "
                        + "لو أردت إضافة تشكيل بنفسك اكتب في السطر: الكلمة بلا تشكيل ثم Tab ثم الكلمة مشكولة. "
                        + "الكلمات المشكولة تدخل القاموس وتدرّب نموذج النطق، والكلمات العادية يُشكَّل منها كل ما أمكن تشكيله بيقين "
                        + "من القاموس (سوابق، ضمائر، جمع...) ويتدرّب عليه النموذج أيضًا. "
                        + "الأسطر الفاسدة تُتجاهل تلقائيًا، والترميز (UTF-8 أو Windows-1256 أو UTF-16) يُكتشف تلقائيًا.")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) dictImportPicker.launch(new String[]{"*/*"});
                    else showDictImportUrlDialog();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void showDictImportUrlDialog() {
        TextInputEditText input = new TextInputEditText(this);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        input.setSingleLine(true);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        input.setTextSize(15f);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_tertiary));
        input.setHint("https://example.com/words.txt");
        new ClaudeDialog(this)
                .setTitle("استيراد من رابط")
                .setMessage("الرابط لازم يبدأ بـ https وحجم الملف حتى 60 ميجابايت.")
                .setView(input)
                .setPositiveButton("تحميل", (dialog, which) -> {
                    String url = input.getText() == null ? "" : input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    importDictPackFromUrl(url);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private String displayNameOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.trim().isEmpty()) return n;
            }
        } catch (RuntimeException ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "مكتبة مستوردة" : last;
    }

    private void importDictPackFromUri(final Uri uri) {
        final String name = displayNameOf(uri);
        Toast.makeText(this, "جارٍ استيراد المكتبة وتدريب النطق عليها... قد يستغرق دقيقة للمكتبات الكبيرة.", Toast.LENGTH_LONG).show();
        final android.content.Context app = getApplicationContext();
        executor.execute(() -> {
            try {
                DictionaryPacks.Pack imported = DictionaryPacks.importFromUri(app, uri, name);
                TashkeelDict.reload(app);
                DictionaryPacks.Pack fresh = DictionaryPacks.find(app, imported.id);
                final DictionaryPacks.Pack p = fresh != null ? fresh : imported;
                runOnUiThread(() -> onDictPackImported(p));
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> onDictImportFailed(e.getMessage()));
            }
        });
    }

    private void importDictPackFromUrl(final String url) {
        Toast.makeText(this, "جارٍ تحميل المكتبة وتدريب النطق عليها... قد يستغرق دقيقة للمكتبات الكبيرة.", Toast.LENGTH_LONG).show();
        final android.content.Context app = getApplicationContext();
        executor.execute(() -> {
            try {
                DictionaryPacks.Pack imported = DictionaryPacks.importFromUrl(app, url, null);
                TashkeelDict.reload(app);
                DictionaryPacks.Pack fresh = DictionaryPacks.find(app, imported.id);
                final DictionaryPacks.Pack p = fresh != null ? fresh : imported;
                runOnUiThread(() -> onDictPackImported(p));
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> onDictImportFailed(e.getMessage()));
            }
        });
    }

    private void onDictPackImported(DictionaryPacks.Pack p) {
        if (!uiAlive()) return;
        refreshDictPacksRow();
        StringBuilder msg = new StringBuilder("تمت إضافة \"").append(p.title).append("\": ");
        if (p.accepted > 0) msg.append(p.accepted).append(" كلمة مشكولة");
        if (p.plain > 0) {
            if (p.accepted > 0) msg.append(" + ");
            msg.append(p.plain).append(" كلمة عادية");
            if (p.derived > 0) msg.append(" (شُكِّلت منها ").append(p.derived).append(" وتدرّب عليها النطق)");
        }
        if (p.rejected > 0) msg.append(" - تم تجاهل ").append(p.rejected).append(" سطرًا فاسدًا");
        Toast.makeText(this, msg.append(".").toString(), Toast.LENGTH_LONG).show();
        showDictPacksDialog();
    }

    private void onDictImportFailed(String message) {
        if (!uiAlive()) return;
        new ClaudeDialog(this)
                .setTitle("تعذّر الاستيراد")
                .setMessage(message == null || message.isEmpty() ? "حدث خطأ غير متوقع." : message)
                .setPositiveButton("حسنًا", null)
                .show();
    }

    private void showDeleteDictPackDialog() {
        final List<DictionaryPacks.Pack> mine = new java.util.ArrayList<>();
        for (DictionaryPacks.Pack p : DictionaryPacks.list(this)) if (p.isUser()) mine.add(p);
        if (mine.isEmpty()) {
            new ClaudeDialog(this)
                    .setTitle("حذف مكتبة")
                    .setMessage("لا توجد مكتبات مستوردة لحذفها. المكتبات المدمجة مع التطبيق لا تُحذف، لكن يمكنك إيقافها من القائمة.")
                    .setPositiveButton("حسنًا", null)
                    .show();
            return;
        }
        final String[] items = new String[mine.size()];
        for (int i = 0; i < items.length; i++) items[i] = mine.get(i).title;
        new ClaudeDialog(this)
                .setTitle("حذف مكتبة مستوردة")
                .setItems(items, (dialog, which) -> {
                    final DictionaryPacks.Pack p = mine.get(which);
                    new ClaudeDialog(this)
                            .setTitle("حذف المكتبة")
                            .setMessage("حذف \"" + p.title + "\" نهائيًا من الجهاز؟")
                            .setPositiveButton("حذف", (d2, w2) -> {
                                DictionaryPacks.remove(this, p.id);
                                applyDictChanges("تم حذف المكتبة.");
                                refreshDictPacksRow();
                            })
                            .setNegativeButton("إلغاء", null)
                            .show();
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    // -----------------------------------------------------------------
    // النسخ الاحتياطي السحابي (Firebase Firestore)
    // -----------------------------------------------------------------

    private void refreshCloudRows() {
        String email = FirebaseSyncManager.currentEmail(this);
        cloudAccountStatus.setText(email != null
                ? email
                : "غير مسجَّل - نسختك لا تُستعاد بعد حذف التطبيق");

        boolean enabled = FirebaseSyncManager.isAutoBackupEnabled(this);
        cloudAutoBackupSwitch.setChecked(enabled);
        cloudStatus.setText(enabled ? "مفعّل - يُرفع تلقائيًا بعد كل تعديل" : "غير مفعّل");

        long lastBackup = FirebaseSyncManager.getLastBackupAt(this);
        cloudBackupSub.setText(lastBackup > 0
                ? "آخر رفع: " + Fmt.relative(lastBackup, System.currentTimeMillis())
                : "لم يتم الرفع بعد");

        long lastRestore = FirebaseSyncManager.getLastRestoreAt(this);
        cloudRestoreSub.setText(lastRestore > 0
                ? "آخر استعادة: " + Fmt.relative(lastRestore, System.currentTimeMillis())
                : "لم تتم الاستعادة بعد");
    }

    // -----------------------------------------------------------------
    // حساب النسخ السحابي (بريد + كلمة مرور)
    // -----------------------------------------------------------------

    private void showCloudAccountDialog() {
        String email = FirebaseSyncManager.currentEmail(this);
        if (email != null) {
            new ClaudeDialog(this)
                    .setTitle("حساب النسخ السحابي")
                    .setMessage("مسجَّل بالحساب:\n" + email + "\n\nبعد حذف التطبيق أو تغيير الجهاز: سجّل الدخول بهذا الحساب ثم اضغط «استعادة من السحابة».")
                    .setPositiveButton("تمام", null)
                    .setNeutralButton("تسجيل الخروج", (d, w) -> {
                        FirebaseSyncManager.signOut(this);
                        refreshCloudRows();
                        Toast.makeText(this, "تم تسجيل الخروج.", Toast.LENGTH_SHORT).show();
                    })
                    .show();
            return;
        }

        final android.view.View view = getLayoutInflater().inflate(R.layout.dialog_cloud_account, null);
        final TextInputEditText emailField = view.findViewById(R.id.acc_email);
        final TextInputEditText passField = view.findViewById(R.id.acc_pass);
        final TextView error = view.findViewById(R.id.acc_error);
        final TextView loginBtn = view.findViewById(R.id.acc_login);
        final TextView registerBtn = view.findViewById(R.id.acc_register);
        final TextView forgot = view.findViewById(R.id.acc_forgot);
        final android.app.Dialog dialog = new ClaudeDialog(this)
                .setTitle("حساب النسخ السحابي")
                .setView(view)
                .create();

        view.findViewById(R.id.acc_cancel).setOnClickListener(v -> dialog.dismiss());

        // callback مشترك: يعرض الخطأ داخل النافذة ويعيد تفعيل الأزرار
        final Runnable[] unlock = new Runnable[1];
        unlock[0] = () -> {
            loginBtn.setEnabled(true);
            registerBtn.setEnabled(true);
        };

        loginBtn.setOnClickListener(v -> {
            String em = textOf(emailField);
            String pw = textOf(passField);
            if (!validCredentials(em, pw, error)) return;
            loginBtn.setEnabled(false);
            registerBtn.setEnabled(false);
            error.setVisibility(android.view.View.GONE);
            FirebaseSyncManager.signIn(this, em, pw, new FirebaseSyncManager.Callback() {
                @Override public void onSuccess(String message) {
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        refreshCloudRows();
                        offerRestoreAfterSignIn();
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        unlock[0].run();
                        error.setText(message);
                        error.setVisibility(android.view.View.VISIBLE);
                    });
                }
            });
        });

        registerBtn.setOnClickListener(v -> {
            String em = textOf(emailField);
            String pw = textOf(passField);
            if (!validCredentials(em, pw, error)) return;
            loginBtn.setEnabled(false);
            registerBtn.setEnabled(false);
            error.setVisibility(android.view.View.GONE);
            FirebaseSyncManager.createAccount(this, em, pw, new FirebaseSyncManager.Callback() {
                @Override public void onSuccess(String message) {
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        refreshCloudRows();
                        Toast.makeText(SettingsActivity.this, message + " جاري رفع النسخة...", Toast.LENGTH_LONG).show();
                        // نرفع نسخة فورًا حتى تكون محمية من حذف التطبيق
                        FirebaseSyncManager.backupNow(SettingsActivity.this, new FirebaseSyncManager.Callback() {
                            @Override public void onSuccess(String m) {
                                runOnUiThread(() -> {
                                    refreshCloudRows();
                                    Toast.makeText(SettingsActivity.this, m, Toast.LENGTH_LONG).show();
                                });
                            }
                            @Override public void onError(String m) {
                                runOnUiThread(() -> new ClaudeDialog(SettingsActivity.this)
                                        .setTitle("تعذّر رفع النسخة")
                                        .setMessage(m)
                                        .setPositiveButton("تمام", null)
                                        .show());
                            }
                        });
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        unlock[0].run();
                        error.setText(message);
                        error.setVisibility(android.view.View.VISIBLE);
                    });
                }
            });
        });

        forgot.setOnClickListener(v -> {
            String em = textOf(emailField);
            if (em.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(em).matches()) {
                error.setText("اكتب بريدك أولًا ثم اضغط «نسيت كلمة المرور».");
                error.setVisibility(android.view.View.VISIBLE);
                return;
            }
            FirebaseSyncManager.sendPasswordReset(this, em, new FirebaseSyncManager.Callback() {
                @Override public void onSuccess(String message) {
                    runOnUiThread(() -> {
                        error.setVisibility(android.view.View.GONE);
                        Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        error.setText(message);
                        error.setVisibility(android.view.View.VISIBLE);
                    });
                }
            });
        });

        dialog.show();
    }

    private static String textOf(TextInputEditText f) {
        return f.getText() == null ? "" : f.getText().toString().trim();
    }

    private boolean validCredentials(String email, String pass, TextView error) {
        String msg = null;
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) msg = "اكتب بريدًا إلكترونيًا صحيحًا.";
        else if (pass.length() < 6) msg = "كلمة المرور يجب أن تكون 6 أحرف على الأقل.";
        if (msg == null) return true;
        error.setText(msg);
        error.setVisibility(android.view.View.VISIBLE);
        return false;
    }

    private void offerRestoreAfterSignIn() {
        new ClaudeDialog(this)
                .setTitle("تم تسجيل الدخول")
                .setMessage("هل تريد استعادة ملفات المرضى المحفوظة على هذا الحساب الآن؟ سيُستبدل ما هو موجود على الجهاز.")
                .setPositiveButton("استعادة", (d, w) -> restoreFromCloud())
                .setNegativeButton("لاحقًا", null)
                .show();
    }

    private void toggleCloudAutoBackup() {
        boolean newValue = !FirebaseSyncManager.isAutoBackupEnabled(this);
        FirebaseSyncManager.setAutoBackupEnabled(this, newValue);
        refreshCloudRows();
        if (newValue) {
            Toast.makeText(this, "سيُرفع نسخة تلقائية بعد كل تعديل في بيانات المرضى.", Toast.LENGTH_SHORT).show();
        }
    }

    private void backupNowClicked() {
        Toast.makeText(this, "جاري رفع نسخة احتياطية للمرضى...", Toast.LENGTH_SHORT).show();
        FirebaseSyncManager.backupNow(this, new FirebaseSyncManager.Callback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    refreshCloudRows();
                    Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> new ClaudeDialog(SettingsActivity.this)
                        .setTitle("تعذّر النسخ الاحتياطي")
                        .setMessage(message)
                        .setPositiveButton("تمام", null)
                        .show());
            }
        });
    }

    private void confirmRestoreFromCloud() {
        new ClaudeDialog(this)
                .setTitle("استعادة من السحابة")
                .setMessage("سيتم استبدال قائمة المرضى المحلية بالكامل بآخر نسخة محفوظة على السحابة. أي تعديلات محلية لم تُرفع بعد ستُفقد. متأكد؟")
                .setPositiveButton("استعادة", (dialog, which) -> restoreFromCloud())
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void restoreFromCloud() {
        Toast.makeText(this, "جاري الاستعادة من السحابة...", Toast.LENGTH_SHORT).show();
        FirebaseSyncManager.restoreNow(this, new FirebaseSyncManager.Callback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    refreshCloudRows();
                    Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> new ClaudeDialog(SettingsActivity.this)
                        .setTitle("تعذّرت الاستعادة")
                        .setMessage(message)
                        .setPositiveButton("تمام", null)
                        .show());
            }
        });
    }

    // -----------------------------------------------------------------
    // عن التطبيق
    // -----------------------------------------------------------------

    /** يعرض حالة صف التحديث: مؤشر أثناء الفحص، ولون مميّز وخط عريض لو يوجد تحديث متاح. */
    private void showUpdateStatus(String text) {
        boolean checking = UpdateManager.STATUS_CHECKING.equals(text);
        boolean available = text != null && text.startsWith("تحديث متاح");
        updateStatus.setText(text);
        updateProgress.setVisibility(checking ? View.VISIBLE : View.GONE);
        if (available) {
            updateStatus.setTextColor(getColor(R.color.primary_cyan));
            updateStatus.setTypeface(updateStatusDefaultTypeface, android.graphics.Typeface.BOLD);
        } else {
            updateStatus.setTextColor(updateStatusDefaultColors);
            updateStatus.setTypeface(updateStatusDefaultTypeface);
        }
    }

    private void showAboutDialog() {
        String message = "Phizyo Studio\nالإصدار " + getVersionLabel() + "\n\n" +
                "دليلك السريري لجهاز AST-2012A: 130 حالة موثّقة، وموسوعة أنماط الجهاز، ومساعد ذكي (Phizyo AI).\n\n" +
                "بياناتك محفوظة على جهازك أولًا، والنسخ السحابي اختياري ولا يعمل إلا بتفعيلك.\n\n" +
                "تطوير ومحتوى سريري: المعالج الفيزيائي سلمان";
        new ClaudeDialog(this)
                .setTitle("عن التطبيق")
                .setMessage(message)
                .setPositiveButton("حسنًا", null)
                .show();
    }

    /** اسم الإصدار الفعلي من معلومات الحزمة بدل رقم ثابت مكتوب باليد، حتى
     *  يفضل دقيقًا تلقائيًا مع كل رفعة جديدة بدون تعديل يدوي في الكود. */
    private String getVersionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName != null ? info.versionName : "1.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
