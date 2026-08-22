package com.ast2012a.clinicalmaster;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * شاشة الإعدادات: إشعارات حقيقية عبر NotificationManager (منسوبة للتطبيق
 * نفسه على أندرويد، وليست منسوبة لأي متصفح أو WebView - لأن هذا تطبيق
 * أندرويد أصلي بالكامل).
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "clinical_master_channel";
    private static final String PREFS = "settings_prefs";
    private static final String KEY_NOTIF_ENABLED = "notif_enabled";
    private static final String KEY_AI_API_KEY = "ai_api_key";

    private Button notifBtn;
    private TextInputEditText aiKeyField;
    private ActivityResultLauncher<String> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        createNotificationChannel();

        notifBtn = findViewById(R.id.btn_notif);
        aiKeyField = findViewById(R.id.ai_key_field);
        Button saveKeyBtn = findViewById(R.id.btn_save_key);
        Button exportBtn = findViewById(R.id.btn_export);
        Button clearBtn = findViewById(R.id.btn_clear_all);

        aiKeyField.setText(prefs().getString(KEY_AI_API_KEY, ""));
        saveKeyBtn.setOnClickListener(v -> saveAiKey());

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

    private void saveAiKey() {
        String key = aiKeyField.getText() == null ? "" : aiKeyField.getText().toString().trim();
        prefs().edit().putString(KEY_AI_API_KEY, key).apply();
        Toast.makeText(this, key.isEmpty() ? "تم مسح المفتاح." : "✅ تم حفظ المفتاح.", Toast.LENGTH_SHORT).show();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID, "تنبيهات AST-2012A", android.app.NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("إشعارات عامة من تطبيق AST-2012A Clinical Master");
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
                .setContentTitle("AST-2012A Clinical Master")
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
        new AlertDialog.Builder(this)
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
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
