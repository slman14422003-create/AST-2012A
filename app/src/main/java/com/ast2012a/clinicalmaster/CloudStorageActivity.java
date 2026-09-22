package com.ast2012a.clinicalmaster;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
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
    // خيط منفصل للرفع: عشان تحميل/تحديث القائمة ما ينتظرش خلف رفع ملف كبير.
    private final ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();

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
    private ActivityResultLauncher<String> notifPermissionLauncher;

    // ---- بطاقة الرفع (شريط تقدّم حقيقي) + إشعار الانتهاء ----
    private static final String UPLOAD_CHANNEL_ID = "cloud_uploads";
    private static final int UPLOAD_NOTIFICATION_ID = 2002;
    private static final String PREFS = "settings_prefs";
    private static final String KEY_NOTIF_ASKED = "cloud_upload_notif_asked";
    private static final long UPLOAD_CARD_AUTOHIDE_MS = 4000;

    private View uploadCard;
    private ImageView uploadIcon;
    private TextView uploadName;
    private TextView uploadStatus;
    private ImageButton uploadCancel;
    private ProgressBar uploadBar;
    private volatile boolean uploading;
    private volatile boolean uploadCancelled;
    /** نفس بطاقة الرفع تُستخدم أيضاً لعرض تقدّم تنزيل الملف عند الضغط على "فتح". */
    private volatile boolean downloading;
    private volatile boolean downloadCancelled;
    private boolean resumed;
    private final Runnable hideUploadCardRunnable = this::hideUploadCard;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

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

        uploadCard = findViewById(R.id.upload_card);
        uploadIcon = findViewById(R.id.upload_icon);
        uploadName = findViewById(R.id.upload_name);
        uploadStatus = findViewById(R.id.upload_status);
        uploadCancel = findViewById(R.id.upload_cancel);
        uploadBar = findViewById(R.id.upload_bar);
        uploadCancel.setOnClickListener(v -> {
            if (uploading) {
                uploadCancelled = true;
                uploadStatus.setText("جارٍ الإلغاء...");
            } else if (downloading) {
                downloadCancelled = true;
                uploadStatus.setText("جارٍ الإلغاء...");
            } else {
                hideUploadCard();
            }
        });
        Ui.applyPressFeedback(uploadCancel);

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

        // طلب إذن الإشعارات (أندرويد 13+) مرة واحدة فقط عند أول رفع، عشان يوصل إشعار
        // الانتهاء لو خرج المستخدم من التطبيق أثناء الرفع. الرفع نفسه لا ينتظر القرار.
        notifPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> { });

        Ui.applyPressFeedback(fab);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
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
        uiHandler.removeCallbacks(hideUploadCardRunnable);
        executor.shutdownNow();
        // لو فيه رفع شغّال والمستخدم خرج من الشاشة نتركه يكمل (shutdown بدل
        // shutdownNow) ويوصل إشعار الانتهاء، بدل ما ينقطع الرفع في المنتصف.
        if (uploading) uploadExecutor.shutdown();
        else uploadExecutor.shutdownNow();
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
        progress.setVisibility(View.GONE);
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
                runOnUiThread(() -> showError("تعذّر تحميل قائمة الملفات.\n" + errorText(e)));
            }
        });
    }

    private void applyList(List<CloudFile> files) {
        progress.setVisibility(View.GONE);
        if (isFinishing()) return;
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
        // ملحوظة إصلاح: لو لا توجد قائمة ملفات معروضة (مثلًا أول اتصال فاشل بعد ضبط
        // الرابط) كانت الشاشة تبقى على "لم يتم الضبط" بلا أي إشارة للفشل. الآن
        // نعرض حالة خطأ واضحة مع زر "إعادة المحاولة".
        if (adapter.getItemCount() == 0) showConnectionErrorState();
    }

    private void showConnectionErrorState() {
        content.setVisibility(View.GONE);
        fab.setVisibility(View.GONE);
        headerSubtitle.setVisibility(View.GONE);
        emptyBox.setVisibility(View.VISIBLE);
        ((android.widget.ImageView) emptyBox.findViewById(R.id.empty_icon)).setImageResource(R.drawable.ic_cloud);
        ((TextView) emptyBox.findViewById(R.id.empty_title)).setText("تعذّر الاتصال بالتخزين السحابي");
        ((TextView) emptyBox.findViewById(R.id.empty_body)).setText(
                "تأكد من رابط الووركر ورمز الدخول ومن اتصال الإنترنت، ثم أعد المحاولة. "
                        + "يمكنك تعديل الرابط من أيقونة الإعدادات في الأعلى.");
        TextView emptyAction = emptyBox.findViewById(R.id.empty_action);
        emptyAction.setText("إعادة المحاولة");
        emptyAction.setOnClickListener(v -> reload());
    }

    /** رسالة الخطأ لو null (بعض الاستثناءات بلا رسالة) نعرض اسم النوع بدل "null". */
    private static String errorText(Throwable e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------ رفع/استبدال

    private void handlePickedFile(Uri uri) {
        if (uploading) {
            Toast.makeText(this, "يوجد رفع جارٍ حاليًا، انتظر حتى ينتهي.", Toast.LENGTH_SHORT).show();
            return;
        }
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
        final String finalMime = mime;

        askNotificationPermissionOnce();
        statusRow.setVisibility(View.GONE);
        uploading = true;
        uploadCancelled = false;
        showUploadCard(finalTarget, size);

        final long[] lastUiUpdate = {0};
        uploadExecutor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("تعذّر قراءة الملف المختار.");
                CloudStorageClient.upload(this, finalTarget, in, size, finalMime,
                        new CloudStorageClient.UploadListener() {
                            @Override
                            public void onProgress(long sent, long total) {
                                // نخفّف تحديثات الواجهة (~10 مرات في الثانية) بدل تحديث لكل دفعة
                                long now = SystemClock.uptimeMillis();
                                boolean done = total > 0 && sent >= total;
                                if (!done && now - lastUiUpdate[0] < 100) return;
                                lastUiUpdate[0] = now;
                                runOnUiThread(() -> updateUploadProgress(sent, total));
                            }

                            @Override
                            public boolean isCancelled() {
                                return uploadCancelled;
                            }
                        });
                runOnUiThread(() -> onUploadSucceeded(finalTarget, size));
            } catch (CloudStorageClient.UploadCancelledException e) {
                runOnUiThread(this::onUploadCancelled);
            } catch (Throwable t) {
                // Throwable (وليس Exception فقط) عشان أي خطأ غير متوقع ما يترك بطاقة
                // الرفع عالقة للأبد بدون نجاح ولا فشل.
                runOnUiThread(() -> onUploadFailed(finalTarget, errorText(t)));
            }
        });
    }

    // ------------------------------------------------------------------ بطاقة الرفع

    private void showUploadCard(String name, long size) {
        uiHandler.removeCallbacks(hideUploadCardRunnable);
        uploadIcon.setImageResource(R.drawable.ic_folder);
        uploadIcon.setImageTintList(ColorStateList.valueOf(getColor(R.color.primary_cyan)));
        uploadBar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.primary_cyan)));
        uploadBar.setIndeterminate(size <= 0);
        uploadBar.setProgress(0);
        uploadCancel.setContentDescription("إلغاء الرفع");
        uploadName.setText(name);
        uploadStatus.setText("جارٍ الرفع... 0%");
        uploadCard.setVisibility(View.VISIBLE);
    }

    private void updateUploadProgress(long sent, long total) {
        if (isDestroyed() || !uploading || uploadCancelled) return;
        if (total <= 0) {
            // حجم الملف غير معروف: نعرض المرسل فقط بدون نسبة
            uploadStatus.setText(BidiText.fix("جارٍ الرفع... " + Formatter.formatShortFileSize(this, sent)));
            return;
        }
        int pct = (int) Math.min(100, (sent * 100) / total);
        uploadBar.setIndeterminate(false);
        uploadBar.setProgress(pct);
        if (sent >= total) {
            uploadStatus.setText("جارٍ إنهاء الرفع...");
        } else {
            uploadStatus.setText(BidiText.fix("جارٍ الرفع... " + pct + "%  ·  "
                    + Formatter.formatShortFileSize(this, sent) + " من "
                    + Formatter.formatShortFileSize(this, total)));
        }
    }

    private void hideUploadCard() {
        if (uploadCard == null) return;
        uiHandler.removeCallbacks(hideUploadCardRunnable);
        uploadCard.setVisibility(View.GONE);
    }

    private void onUploadSucceeded(String name, long size) {
        uploading = false;
        notifyUploadResult("تم رفع الملف", name);
        if (isFinishing() || isDestroyed()) return;

        int green = getColor(R.color.accent_green);
        uploadBar.setIndeterminate(false);
        uploadBar.setProgress(100);
        uploadBar.setProgressTintList(ColorStateList.valueOf(green));
        uploadIcon.setImageResource(R.drawable.ic_check);
        uploadIcon.setImageTintList(ColorStateList.valueOf(green));
        uploadName.setText(name);
        uploadStatus.setText(size > 0
                ? BidiText.fix("تم الرفع بنجاح  ·  " + Formatter.formatShortFileSize(this, size))
                : "تم الرفع بنجاح");
        uploadCancel.setContentDescription("إغلاق");
        uiHandler.postDelayed(hideUploadCardRunnable, UPLOAD_CARD_AUTOHIDE_MS);
        reload();
    }


    private void onUploadCancelled() {
        uploading = false;
        uploadCancelled = false;
        if (isFinishing() || isDestroyed()) return;
        uploadCard.setVisibility(View.GONE);
        Toast.makeText(this, "أُلغي رفع الملف.", Toast.LENGTH_SHORT).show();
    }

    private void onUploadFailed(String name, String message) {
        uploading = false;
        notifyUploadResult("تعذّر رفع الملف", name);
        if (isFinishing() || isDestroyed()) return;
        uploadCard.setVisibility(View.GONE);
        showError("تعذّر رفع الملف.\n" + message);
    }

    // ------------------------------------------------------------------ إشعار الانتهاء

    private void askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (sp.getBoolean(KEY_NOTIF_ASKED, false)) return;
        sp.edit().putBoolean(KEY_NOTIF_ASKED, true).apply();
        try {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } catch (Exception ignored) {
        }
    }

    /** إشعار نظام عند انتهاء الرفع (نجاح/فشل) — فقط لو المستخدم خارج هذه الشاشة، لأنه
     *  لو كان يشوفها فبطاقة الرفع نفسها كافية ولا داعي لتكرار الإشعار. */
    private void notifyUploadResult(String title, String fileName) {
        if (resumed) return;
        try {
            android.content.Context app = getApplicationContext();
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = app.getSystemService(NotificationManager.class);
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(
                            UPLOAD_CHANNEL_ID, "رفع الملفات السحابية", NotificationManager.IMPORTANCE_DEFAULT);
                    ch.setDescription("إشعار عند انتهاء رفع ملف إلى التخزين السحابي");
                    nm.createNotificationChannel(ch);
                }
            }

            Intent open = new Intent(app, CloudStorageActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent content = PendingIntent.getActivity(app, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(app, UPLOAD_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_pulse)
                    .setContentTitle(title)
                    .setContentText(BidiText.fix(fileName))
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(content)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis());
            NotificationManagerCompat.from(app).notify(UPLOAD_NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) {
        }
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
        if (uploading) {
            Toast.makeText(this, "يوجد رفع جارٍ حاليًا، انتظر حتى ينتهي.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (downloading) {
            Toast.makeText(this, "يوجد تنزيل جارٍ حاليًا، انتظر حتى ينتهي.", Toast.LENGTH_SHORT).show();
            return;
        }
        downloading = true;
        downloadCancelled = false;
        showDownloadCard(file.name, file.size);

        final long[] lastUiUpdate = {0};
        uploadExecutor.execute(() -> {
            try {
                // اسم الملف قد يحوي "/" فيُنشئ مسارات فرعية أو يخرج من مجلد الكاش
                File dest = new File(new File(getCacheDir(), "cloud_files"), file.name.replace('/', '_').replace('\\', '_'));
                CloudStorageClient.downloadToFile(this, file.name, dest, new CloudStorageClient.UploadListener() {
                    @Override
                    public void onProgress(long sent, long total) {
                        long now = SystemClock.uptimeMillis();
                        boolean done = total > 0 && sent >= total;
                        if (!done && now - lastUiUpdate[0] < 100) return;
                        lastUiUpdate[0] = now;
                        runOnUiThread(() -> updateDownloadProgress(sent, total));
                    }

                    @Override
                    public boolean isCancelled() {
                        return downloadCancelled;
                    }
                });
                runOnUiThread(() -> onDownloadSucceeded(dest, file.name));
            } catch (CloudStorageClient.UploadCancelledException e) {
                runOnUiThread(this::onDownloadCancelled);
            } catch (Throwable t) {
                runOnUiThread(() -> onDownloadFailed(file.name, errorText(t)));
            }
        });
    }

    // ------------------------------------------------------------------ بطاقة التنزيل (نفس بطاقة الرفع)

    private void showDownloadCard(String name, long size) {
        uiHandler.removeCallbacks(hideUploadCardRunnable);
        uploadIcon.setImageResource(R.drawable.ic_download);
        uploadIcon.setImageTintList(ColorStateList.valueOf(getColor(R.color.primary_cyan)));
        uploadBar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.primary_cyan)));
        uploadBar.setIndeterminate(size <= 0);
        uploadBar.setProgress(0);
        uploadCancel.setContentDescription("إلغاء التنزيل");
        uploadName.setText(name);
        uploadStatus.setText("جارٍ التنزيل... 0%");
        uploadCard.setVisibility(View.VISIBLE);
    }

    private void updateDownloadProgress(long received, long total) {
        if (isDestroyed() || !downloading || downloadCancelled) return;
        if (total <= 0) {
            uploadStatus.setText(BidiText.fix("جارٍ التنزيل... " + Formatter.formatShortFileSize(this, received)));
            return;
        }
        int pct = (int) Math.min(100, (received * 100) / total);
        uploadBar.setIndeterminate(false);
        uploadBar.setProgress(pct);
        if (received >= total) {
            uploadStatus.setText("جارٍ إنهاء التنزيل...");
        } else {
            uploadStatus.setText(BidiText.fix("جارٍ التنزيل... " + pct + "%  ·  "
                    + Formatter.formatShortFileSize(this, received) + " من "
                    + Formatter.formatShortFileSize(this, total)));
        }
    }

    private void onDownloadSucceeded(File dest, String name) {
        downloading = false;
        if (isFinishing() || isDestroyed()) return;

        int green = getColor(R.color.accent_green);
        uploadBar.setIndeterminate(false);
        uploadBar.setProgress(100);
        uploadBar.setProgressTintList(ColorStateList.valueOf(green));
        uploadIcon.setImageResource(R.drawable.ic_check);
        uploadIcon.setImageTintList(ColorStateList.valueOf(green));
        uploadName.setText(name);
        uploadStatus.setText("تم التنزيل بنجاح");
        uploadCancel.setContentDescription("إغلاق");
        uiHandler.postDelayed(hideUploadCardRunnable, UPLOAD_CARD_AUTOHIDE_MS);

        if ("pdf".equals(extensionOf(name))) {
            openPdfViewer(dest, name);
        } else {
            openLocalFile(dest, name);
        }
    }

    /** نفس منطق CloudFile.extension() لكن من اسم الملف مباشرة (بعد التنزيل قد لا يتوفر كائن CloudFile). */
    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private void onDownloadCancelled() {
        downloading = false;
        downloadCancelled = false;
        if (isFinishing() || isDestroyed()) return;
        uploadCard.setVisibility(View.GONE);
        Toast.makeText(this, "أُلغي تنزيل الملف.", Toast.LENGTH_SHORT).show();
    }

    private void onDownloadFailed(String name, String message) {
        downloading = false;
        if (isFinishing() || isDestroyed()) return;
        uploadCard.setVisibility(View.GONE);
        showError("تعذّر تنزيل الملف.\n" + message);
    }

    /** ملفات PDF تُعرض في قارئ التطبيق (منسّق مع الواجهة) بدل تطبيق خارجي. */
    private void openPdfViewer(File file, String name) {
        if (isFinishing() || isDestroyed()) return;
        Intent i = new Intent(this, PdfViewerActivity.class)
                .putExtra(PdfViewerActivity.EXTRA_PATH, file.getAbsolutePath())
                .putExtra(PdfViewerActivity.EXTRA_TITLE, name);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
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
                            runOnUiThread(() -> showError("تعذّرت إعادة التسمية.\n" + errorText(e)));
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
                            runOnUiThread(() -> showError("تعذّر حذف الملف.\n" + errorText(e)));
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
