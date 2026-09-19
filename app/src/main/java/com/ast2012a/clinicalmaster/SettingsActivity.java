package com.ast2012a.clinicalmaster;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * شاشة الإعدادات: إشعارات حقيقية عبر NotificationManager (منسوبة للتطبيق
 * نفسه على أندرويد، وليست منسوبة لأي متصفح أو WebView - لأن هذا تطبيق
 * أندرويد أصلي بالكامل).
 *
 * Phizyo AI ليس له سوى مزوّد واحد ثابت (AiClient.FIXED_WORKER_URL) مبني
 * داخل التطبيق نفسه - لا يوجد مفتاح API ولا رابط قابل للتعديل من هنا.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "clinical_master_channel";
    private static final String PREFS = "settings_prefs";
    private static final String KEY_NOTIF_ENABLED = "notif_enabled";

    // ⚠️ غيّر هذا لبريدك الفعلي قبل النشر - يُستخدم فقط لفتح تطبيق البريد
    // بمستلم مبدئي، المستخدم يقدر يغيّره قبل الإرسال براحته.
    private static final String SUPPORT_EMAIL = "support@phizyostudio.app";

    private Button notifBtn;
    private ActivityResultLauncher<String> permissionLauncher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        createNotificationChannel();

        Button themeBtn = findViewById(R.id.btn_theme_mode);
        refreshThemeLabel(themeBtn);
        themeBtn.setOnClickListener(v -> {
            String newMode = ThemeManager.cycleMode(this);
            Toast.makeText(this, ThemeManager.labelFor(newMode) + " مفعّل", Toast.LENGTH_SHORT).show();
            recreate();
        });

        notifBtn = findViewById(R.id.btn_notif);
        Button testWorkerBtn = findViewById(R.id.btn_test_worker);
        Button clearChatHistoryBtn = findViewById(R.id.btn_clear_chat_history);
        Button exportBtn = findViewById(R.id.btn_export);
        Button clearBtn = findViewById(R.id.btn_clear_all);
        Button aboutBtn = findViewById(R.id.btn_about);
        Button privacyPolicyBtn = findViewById(R.id.btn_privacy_policy);
        Button shareBtn = findViewById(R.id.btn_share_app);
        Button rateBtn = findViewById(R.id.btn_rate_app);
        Button feedbackBtn = findViewById(R.id.btn_feedback);

        TextView workerUrlLabel = findViewById(R.id.worker_url_fixed_label);
        workerUrlLabel.setText(AiClient.FIXED_WORKER_URL);

        testWorkerBtn.setOnClickListener(v -> testWorkerConnection());
        clearChatHistoryBtn.setOnClickListener(v -> confirmClearChatHistory());
        aboutBtn.setOnClickListener(v -> showAboutDialog());
        privacyPolicyBtn.setOnClickListener(v -> startActivity(new Intent(this, PrivacyPolicyActivity.class)));
        shareBtn.setOnClickListener(v -> shareApp());
        rateBtn.setOnClickListener(v -> rateApp());
        feedbackBtn.setOnClickListener(v -> sendFeedback());

        TextInputEditText instructionsField = findViewById(R.id.ai_custom_instructions_field);
        instructionsField.setText(AiPrompts.getCustomInstructions(this));
        Button saveInstructionsBtn = findViewById(R.id.btn_save_instructions);
        saveInstructionsBtn.setOnClickListener(v -> {
            String text = instructionsField.getText() == null ? "" : instructionsField.getText().toString();
            AiPrompts.setCustomInstructions(this, text);
            Toast.makeText(this, "✅ تم حفظ تعليمات Phizyo AI.", Toast.LENGTH_SHORT).show();
        });

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        setNotifEnabled(true);
                        sendTestNotification();
                    } else {
                        Toast.makeText(this, "لم يتم منح إذن الإشعارات.", Toast.LENGTH_SHORT).show();
                    }
                    refreshNotifLabel();
                });

        notifBtn.setOnClickListener(v -> toggleNotifications());
        exportBtn.setOnClickListener(v -> exportBackup());
        clearBtn.setOnClickListener(v -> confirmClearAll());

        refreshNotifLabel();
    }

    private void confirmClearChatHistory() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("تأكيد")
                .setMessage("سيتم حذف سجل محادثة Phizyo AI بالكامل. متأكد؟")
                .setPositiveButton("مسح", (dialog, which) -> {
                    AiChatStore.clear(this);
                    Toast.makeText(this, "تم مسح سجل المحادثة.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void showAboutDialog() {
        String message = "Phizyo Studio\nالإصدار " + getVersionLabel() + "\n\n" +
                "تطبيق أندرويد أصلي مكتوب بالكامل بلغة Java - بدون WebView أو متصفح.\n" +
                "130 حالة سريرية موثقة لجهاز AST-2012A + موسوعة أنماط الجهاز + مساعد ذكي (Phizyo AI).\n\n" +
                "كل بياناتك (الحالات المخصصة، سجل المحادثة، الإعدادات) محفوظة محليًا على جهازك فقط، ولا تُرسل لأي سيرفر خاص بالتطبيق.\n\n" +
                "🩺 تطوير ومحتوى سريري: المعالج الفيزيائي سلمان";
        new MaterialAlertDialogBuilder(this)
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

    /** اختبار اتصال فوري بالووركر الثابت (AiClient.FIXED_WORKER_URL). */
    private void testWorkerConnection() {
        Toast.makeText(this, "🧪 جاري اختبار الاتصال بـ Phizyo AI...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> AiClient.testWorker(new AiClient.Callback() {
            @Override
            public void onSuccess(String reply) {
                runOnUiThread(() -> new MaterialAlertDialogBuilder(SettingsActivity.this)
                        .setTitle("✅ Phizyo AI شغال")
                        .setMessage("رد الووركر:\n\n" + reply)
                        .setPositiveButton("تمام", null)
                        .show());
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> new MaterialAlertDialogBuilder(SettingsActivity.this)
                        .setTitle("❌ تعذر الاتصال بـ Phizyo AI")
                        .setMessage("تفاصيل الخطأ:\n" + message +
                                "\n\nلو الرسالة بتقول \"model deprecated\" أو حاجة شبهها، يبقى Cloudflare قفلوا الموديل المستخدم وتحتاج تحدّث اسم الموديل في worker.js. غير كده تأكد إن الووركر منشور (Deployed) وفعّال، وإن جهازك متصل بالإنترنت.")
                        .setPositiveButton("تمام", null)
                        .show());
            }
        }));
    }

    // -----------------------------------------------------------------
    // ميزات إضافية بأسلوب أي تطبيق تاني: مشاركة، تقييم، تواصل
    // -----------------------------------------------------------------

    private void shareApp() {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT,
                "🩺 جرّب تطبيق Phizyo Studio - دليل استخدام جهاز AST-2012A ومساعد العلاج الطبيعي الذكي Phizyo AI!");
        try {
            startActivity(Intent.createChooser(shareIntent, "مشاركة التطبيق"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "تعذر فتح قائمة المشاركة.", Toast.LENGTH_SHORT).show();
        }
    }

    private void rateApp() {
        String pkg = getPackageName();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "تعذر فتح متجر التطبيقات.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void sendFeedback() {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:" + SUPPORT_EMAIL));
        intent.putExtra(Intent.EXTRA_SUBJECT, "ملاحظات حول تطبيق Phizyo Studio");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لا يوجد تطبيق بريد مثبّت على جهازك.", Toast.LENGTH_SHORT).show();
        }
    }

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
        return prefs().getBoolean(KEY_NOTIF_ENABLED, false);
    }

    private void setNotifEnabled(boolean value) {
        prefs().edit().putBoolean(KEY_NOTIF_ENABLED, value).apply();
    }

    private void refreshThemeLabel(Button themeBtn) {
        themeBtn.setText(ThemeManager.labelFor(ThemeManager.getCurrentMode(this)) + " - اضغط للتبديل");
    }

    private void refreshNotifLabel() {
        if (isNotifEnabled()) {
            notifBtn.setText("🔔 الإشعارات مُفعّلة - اضغط لإرسال إشعار تجريبي");
        } else {
            notifBtn.setText("🔕 الإشعارات غير مفعّلة - اضغط للتفعيل");
        }
    }

    private void toggleNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        setNotifEnabled(true);
        sendTestNotification();
        refreshNotifLabel();
    }

    private void sendTestNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Phizyo Studio")
                .setContentText("✅ الإشعارات تعمل بنجاح - هذا إشعار حقيقي من التطبيق نفسه.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        try {
            NotificationManagerCompat.from(this).notify(1001, builder.build());
            Toast.makeText(this, "🔔 تم إرسال إشعار تجريبي.", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "تعذر إرسال الإشعار - الإذن غير ممنوح.", Toast.LENGTH_SHORT).show();
        }
    }

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
        new MaterialAlertDialogBuilder(this)
                .setTitle("تأكيد")
                .setMessage("سيتم حذف كل الحالات المخصصة نهائيًا. متأكد؟")
                .setPositiveButton("مسح الكل", (dialog, which) -> {
                    DataManager.clearAllCustomCases(this);
                    Toast.makeText(this, "تم مسح كل الحالات المخصصة.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
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
