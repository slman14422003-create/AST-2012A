package com.ast2012a.clinicalmaster;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * شاشة الإعدادات (بأسلوب تطبيق Claude): مجموعات صفوف، كل صف أيقونة + عنوان +
 * وصف اختياري. الإشعارات الحقيقية تعمل عبر NotificationManager (منسوبة للتطبيق
 * نفسه على أندرويد، وليست منسوبة لأي متصفح أو WebView).
 *
 * Phizyo AI ليس له سوى مزوّد واحد ثابت (AiClient.FIXED_WORKER_URL) مبني
 * داخل التطبيق نفسه - لا يوجد مفتاح API ولا رابط قابل للتعديل من هنا.
 *
 * ملحوظة: حُذفت من هذه الشاشة أوامر "مشاركة التطبيق" و"تقييم التطبيق" و"تواصل
 * معنا" لأنها غير ضرورية.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "clinical_master_channel";
    private static final String PREFS = "settings_prefs";
    private static final String KEY_NOTIF_ENABLED = "notif_enabled";

    private TextView themeValue;
    private TextView notifStatus;
    private TextView instructionsStatus;
    private MaterialSwitch notifSwitch;
    private ActivityResultLauncher<String> permissionLauncher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        createNotificationChannel();

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
                        sendTestNotification();
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

        // عن التطبيق
        findViewById(R.id.btn_about).setOnClickListener(v -> showAboutDialog());
        findViewById(R.id.btn_privacy_policy).setOnClickListener(v ->
                startActivity(new Intent(this, PrivacyPolicyActivity.class)));

        refreshThemeRow();
        refreshNotifRow();
        refreshInstructionsRow();
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
                "3) غير ذلك يُستخدم بحث Physiopedia أولًا (مرجع متخصص في العلاج الطبيعي)، ثم ويكيبيديا كخلفية عامة تكميلية إذا لم توجد نتيجة. الصياغة النهائية دائمًا عبر Phizyo AI وتلتزم بأي تعليمات مخصّصة تضيفها.";
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID, "تنبيهات Phizyo Studio", android.app.NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("إشعارات عامة من تطبيق Phizyo Studio");
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean isNotifEnabled() {
        return prefs().getBoolean(KEY_NOTIF_ENABLED, false) && hasNotificationPermission();
    }

    private void setNotifEnabled(boolean value) {
        prefs().edit().putBoolean(KEY_NOTIF_ENABLED, value).apply();
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
        notifStatus.setText(enabled ? "مفعّلة" : "غير مفعّلة");
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
        sendTestNotification();
        refreshNotifRow();
    }

    private void sendTestNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Phizyo Studio")
                .setContentText("الإشعارات تعمل بنجاح - هذا إشعار حقيقي من التطبيق نفسه.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        try {
            NotificationManagerCompat.from(this).notify(1001, builder.build());
            Toast.makeText(this, "تم إرسال إشعار تجريبي.", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "تعذر إرسال الإشعار - الإذن غير ممنوح.", Toast.LENGTH_SHORT).show();
        }
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
    // عن التطبيق
    // -----------------------------------------------------------------

    private void showAboutDialog() {
        String message = "Phizyo Studio\nالإصدار " + getVersionLabel() + "\n\n" +
                "تطبيق أندرويد أصلي مكتوب بالكامل بلغة Java - بدون WebView أو متصفح.\n" +
                "130 حالة سريرية موثقة لجهاز AST-2012A + موسوعة أنماط الجهاز + مساعد ذكي (Phizyo AI).\n\n" +
                "كل بياناتك (الحالات المخصصة، سجل المحادثة، الإعدادات) محفوظة محليًا على جهازك فقط، ولا تُرسل لأي سيرفر خاص بالتطبيق.\n\n" +
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
