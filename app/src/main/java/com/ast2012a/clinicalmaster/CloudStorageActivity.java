package com.ast2012a.clinicalmaster;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * شاشة "الملفات السحابية": تخزين مجاني للـ PDF/المستندات على Cloudflare
 * Worker (R2) - رفع/فتح/إعادة تسمية/استبدال/حذف. الرابط ورمز الدخول
 * (Token) يُضبطان من هنا (زر الإعدادات في الشريط العلوي) ويُحفظان محليًا في
 * settings_prefs عبر CloudStorageClient. راجع cloudflare-worker/README.md
 * في جذر المستودع لطريقة نشر الووركر نفسه (خطوة تُعمل مرة واحدة فقط).
 */
public class CloudStorageActivity extends AppCompatActivity implements CloudFileAdapter.Callback {

    /** أنواع الملفات المسموح اختيارها عند الرفع: PDF ومستندات شائعة. */
    private static final String[] ALLOWED_MIME_TYPES = {
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView list;
    private View content;
    private View emptyBox;
    private View statusRow;
    private TextView statusText;
    private View progress;
    private ExtendedFloatingActionButton fab;
    private TextView headerSubtitle;
    private CloudFileAdapter adapter;

    /** اسم الملف المستهدف عند "استبدال" (null = رفع ملف جديد بنفس اسمه الأصلي). */
    private String pendingReplaceTarget;

    private ActivityResultLauncher<String[]> pickDocumentLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud_storage);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_cloud_storage);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_cloud_refresh) {
                reload();
                return true;
            } else if (id == R.id.action_cloud_settings) {
                showSettingsDialog();
                return true;
            }
            return false;
        });

        list = findViewById(R.id.cloud_files_list);
        content = findViewById(R.id.cloud_content);
        emptyBox = findViewById(R.id.empty_hint_container);
        statusRow = findViewById(R.id.cloud_status_row);
        statusText = findViewById(R.id.cloud_status_text);
        progress = findViewById(R.id.cloud_progress);
        fab = findViewById(R.id.fab_upload);
        headerSubtitle = findViewById(R.id.header_subtitle);

        adapter = new CloudFileAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        fab.setOnClickListener(v -> {
            pendingReplaceTarget = null;
            launchPicker();
        });

        pickDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) handlePickedFile(uri); });

        Ui.applyPressFeedback(fab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!CloudStorageClient.isConfigured(this)) {
            showNotConfiguredState();
        } else {
            reload();
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void launchPicker() {
        if (!CloudStorageClient.isConfigured(this)) {
            showSettingsDialog();
            return;
        }
        try {
            pickDocumentLauncher.launch(ALLOWED_MIME_TYPES);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لا يوجد تطبيق لاختيار الملفات على هذا الجهاز.", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ الحالة الفارغة/عدم الضبط

    private void showNotConfiguredState() {
        content.setVisibility(View.GONE);
        fab.setVisibility(View.GONE);
        statusRow.setVisibility(View.GONE);
        headerSubtitle.setVisibility(View.GONE);
        emptyBox.setVisibility(View.VISIBLE);
        ((android.widget.ImageView) emptyBox.findViewById(R.id.empty_icon)).setImageResource(R.drawable.ic_cloud);
        ((TextView) emptyBox.findViewById(R.id.empty_title)).setText("لم يتم ضبط التخزين السحابي بعد");
        ((TextView) emptyBox.findViewById(R.id.empty_body)).setText(
                "اضبط رابط Cloudflare Worker (ورمز الدخول لو مُفعّل) للبدء في تخزين ملفاتك مجانًا. "
                        + "راجع ملف cloudflare-worker/README.md لطريقة النشر.");
        TextView emptyAction = emptyBox.findViewById(R.id.empty_action);
        emptyAction.setText("ضبط الاتصال");
        emptyAction.setOnClickListener(v -> showSettingsDialog());
    }

    private void showEmptyFilesState() {
        content.setVisibility(View.GONE);
        fab.setVisibility(View.VISIBLE);
        emptyBox.setVisibility(View.VISIBLE);
        ((android.widget.ImageView) emptyBox.findViewById(R.id.empty_icon)).setImageResource(R.drawable.ic_folder);
        ((TextView) emptyBox.findViewById(R.id.empty_title)).setText("لا توجد ملفات بعد");
        ((TextView) emptyBox.findViewById(R.id.empty_body)).setText(
                "ارفع أول ملف PDF أو مستند لك وسيظهر هنا، ويمكنك فتحه أو تعديله من أي جهاز.");
        TextView emptyAction = emptyBox.findViewById(R.id.empty_action);
        emptyAction.setText("رفع ملف");
        emptyAction.setOnClickListener(v -> launchPicker());
        headerSubtitle.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ تحميل القائمة

    private void reload() {
        statusRow.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                List<CloudFile> files = CloudStorageClient.list(this);
                runOnUiThread(() -> applyList(files));
            } catch (Exception e) {
                runOnUiThread(() -> showError("تعذّر تحميل قائمة الملفات.\n" + e.getMessage()));
            }
        });
    }

    private void applyList(List<CloudFile> files) {
        if (isFinishing()) return;
        progress.setVisibility(View.GONE);
        adapter.setItems(files);
        boolean empty = files == null || files.isEmpty();
        content.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        fab.setVisibility(View.VISIBLE);
        if (empty) {
            showEmptyFilesState();
        } else {
            headerSubtitle.setText(files.size() == 1 ? "ملف واحد" : files.size() + " ملفات");
            headerSubtitle.setVisibility(View.VISIBLE);
        }
    }

    private void showError(String message) {
        if (isFinishing()) return;
        progress.setVisibility(View.GONE);
        statusText.setText(message);
        statusRow.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ رفع/استبدال

    private void handlePickedFile(Uri uri) {
        String displayName = queryDisplayName(uri);
        long size = queryFileSize(uri);
        String targetName = pendingReplaceTarget != null ? pendingReplaceTarget : displayName;
        if (targetName == null || targetName.trim().isEmpty()) {
            Toast.makeText(this, "تعذّر تحديد اسم الملف المختار.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String finalTarget = targetName;
        String mime = getContentResolver().getType(uri);
        if (mime == null) mime = guessMimeType(finalTarget);

        progress.setVisibility(View.VISIBLE);
        statusRow.setVisibility(View.GONE);
        final String finalMime = mime;
        executor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("تعذّر قراءة الملف المختار.");
                CloudStorageClient.upload(this, finalTarget, in, size, finalMime);
                runOnUiThread(() -> {
                    Toast.makeText(this, "تم رفع \"" + finalTarget + "\" بنجاح.", Toast.LENGTH_SHORT).show();
                    reload();
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("تعذّر رفع الملف.\n" + e.getMessage()));
            }
        });
    }

    private String queryDisplayName(Uri uri) {
        ContentResolver resolver = getContentResolver();
        try (Cursor c = resolver.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Exception ignored) {
        }
        // احتياطي: آخر جزء من مسار الـ Uri نفسه
        String path = uri.getLastPathSegment();
        return path != null ? path : "ملف";
    }

    private long queryFileSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private String guessMimeType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    // ------------------------------------------------------------------ CloudFileAdapter.Callback

    @Override
    public void onOpen(CloudFile file) {
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                File dest = new File(new File(getCacheDir(), "cloud_files"), file.name);
                CloudStorageClient.downloadToFile(this, file.name, dest);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    openLocalFile(dest, file.name);
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("تعذّر تنزيل الملف.\n" + e.getMessage()));
            }
        });
    }

    private void openLocalFile(File file, String name) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", file);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, guessMimeType(name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لا يوجد تطبيق على جهازك لفتح هذا النوع من الملفات.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر فتح الملف.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onReplace(CloudFile file) {
        pendingReplaceTarget = file.name;
        launchPicker();
    }

    @Override
    public void onRename(CloudFile file) {
        TextInputEditText input = new TextInputEditText(this);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        input.setSingleLine(true);
        input.setGravity(Gravity.START);
        input.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        input.setTextSize(15.5f);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_tertiary));
        input.setText(file.name);
        input.setSelection(input.getText() != null ? input.getText().length() : 0);

        new ClaudeDialog(this)
                .setTitle("إعادة تسمية الملف")
                .setView(input)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    String newName = input.getText() == null ? "" : input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(file.name)) return;
                    progress.setVisibility(View.VISIBLE);
                    executor.execute(() -> {
                        try {
                            CloudStorageClient.rename(this, file.name, newName);
                            runOnUiThread(this::reload);
                        } catch (Exception e) {
                            runOnUiThread(() -> showError("تعذّرت إعادة التسمية.\n" + e.getMessage()));
                        }
                    });
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    @Override
    public void onDelete(CloudFile file) {
        new ClaudeDialog(this)
                .setTitle("حذف الملف")
                .setMessage("هل تريد حذف \"" + file.name + "\" نهائيًا من التخزين السحابي؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    progress.setVisibility(View.VISIBLE);
                    executor.execute(() -> {
                        try {
                            CloudStorageClient.delete(this, file.name);
                            runOnUiThread(this::reload);
                        } catch (Exception e) {
                            runOnUiThread(() -> showError("تعذّر حذف الملف.\n" + e.getMessage()));
                        }
                    });
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ------------------------------------------------------------------ إعدادات الاتصال

    private void showSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextInputEditText urlInput = new TextInputEditText(this);
        styleSettingsInput(urlInput, "https://your-worker.workers.dev");
        urlInput.setText(CloudStorageClient.getBaseUrl(this));

        TextInputEditText tokenInput = new TextInputEditText(this);
        styleSettingsInput(tokenInput, "رمز الدخول (Token) - اختياري");
        tokenInput.setText(CloudStorageClient.getToken(this));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 10);
        box.addView(urlInput);
        box.addView(tokenInput, tlp);

        new ClaudeDialog(this)
                .setTitle("إعدادات التخزين السحابي")
                .setMessage("رابط Cloudflare Worker الذي نشرته (راجع cloudflare-worker/README.md)، ورمز الدخول لو ضبطت SHARED_SECRET في الووركر.")
                .setView(box)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    String url = urlInput.getText() == null ? "" : urlInput.getText().toString().trim();
                    String token = tokenInput.getText() == null ? "" : tokenInput.getText().toString().trim();
                    CloudStorageClient.saveConfig(this, url, token);
                    if (url.isEmpty()) {
                        showNotConfiguredState();
                    } else {
                        reload();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void styleSettingsInput(TextInputEditText input, String hint) {
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        input.setSingleLine(true);
        input.setGravity(Gravity.START);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        input.setTextSize(15f);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_tertiary));
        input.setHint(hint);
    }
}
