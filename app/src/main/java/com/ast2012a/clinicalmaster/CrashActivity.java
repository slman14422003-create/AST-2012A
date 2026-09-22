package com.ast2012a.clinicalmaster;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * تُفتح تلقائيًا من CrashHandler بعد أي عطل غير متوقع، بدل شاشة النظام
 * السوداء بلا تفاصيل. تعرض نص الخطأ الكامل (قابل للنسخ) وزر إعادة تشغيل.
 */
public class CrashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash);

        SharedPreferences sp = getSharedPreferences(CrashHandler.PREFS, MODE_PRIVATE);
        String trace = sp.getString(CrashHandler.KEY_TRACE, "لا تتوفر تفاصيل عن الخطأ.");
        long time = sp.getLong(CrashHandler.KEY_TIME, 0);

        TextView traceView = findViewById(R.id.crash_trace);
        traceView.setText(trace);

        TextView subtitle = findViewById(R.id.crash_subtitle);
        if (time > 0) {
            subtitle.setText("حدث هذا الخطأ في " + DateFormat.format("d MMM yyyy، HH:mm", time)
                    + ". انسخ التفاصيل بالأسفل وأرسلها للمساعدة في إصلاحه.");
        }

        findViewById(R.id.crash_copy).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("crash_trace", trace));
                Toast.makeText(this, "تم نسخ تفاصيل الخطأ", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.crash_restart).setOnClickListener(v -> {
            Intent intent = new Intent(this, SplashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
}
