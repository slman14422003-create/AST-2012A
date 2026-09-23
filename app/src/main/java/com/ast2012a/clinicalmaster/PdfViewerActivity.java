package com.ast2012a.clinicalmaster;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * قارئ PDF داخل التطبيق (بدون أي مكتبة خارجية - يعتمد على android.graphics.pdf.PdfRenderer):
 * صفحات كبطاقات فوق خلفية التطبيق، مؤشر صفحة عائم، تكبير/تصغير مع تمرير جانبي،
 * انتقال لصفحة معيّنة، وفتح الملف بتطبيق آخر.
 *
 * طرق الفتح:
 *  1) من الملفات السحابية: EXTRA_PATH (مسار ملف محلي داخل الكاش) + EXTRA_TITLE.
 *  2) من تطبيق آخر: ACTION_VIEW برابط content:// لملف PDF (يُنسخ للكاش أولًا).
 *  3) بدون أي بيانات (زر "قارئ PDF" في الشاشة الرئيسية): يفتح منتقي الملفات مباشرة.
 */
public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "pdf_path";
    public static final String EXTRA_TITLE = "pdf_title";

    /** حدود التكبير المتاحة (% من عرض الشاشة) - أدنى 60% وأقصى 300% لتجنّب
     *  استهلاك الذاكرة، وخطوة أزرار +/- الثابتة 50% لكل ضغطة. التكبير نفسه
     *  أصبح قيمة مستمرة (float) بدل درجات ثابتة (100/150/200) حتى يسمح
     *  بالتكبير/التصغير بحركة إصبعين (Pinch) بأي نسبة بينهم، مش قفزات فقط. */
    private static final float MIN_ZOOM_PERCENT = 60f;
    private static final float MAX_ZOOM_PERCENT = 300f;
    private static final float ZOOM_STEP_PERCENT = 50f;
    private static final int MAX_BITMAP_WIDTH = 2400;
    private static final long INDICATOR_HIDE_DELAY_MS = 1400;
    /** نفس المجلد المُعرَّف في update_file_paths.xml حتى تعمل المشاركة عبر FileProvider. */
    private static final String CACHE_DIR = "cloud_files";

    private TextView titleView;
    private TextView subtitleView;
    private HorizontalScrollView hScroll;
    private RecyclerView pages;
    private LinearLayoutManager layoutManager;
    private View loadingBox;
    private TextView loadingText;
    private View emptyBox;
    private TextView pageIndicator;
    private ImageButton zoomOutBtn;
    private ImageButton zoomInBtn;

    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    /** استخراج نص الصفحة (PdfBox) قبل إرسالها لخدمة الترجمة - منفصل عن renderExecutor
     *  حتى لا تنتظر الترجمة دورها خلف رسم الصفحات. */
    private final ExecutorService textExecutor = Executors.newSingleThreadExecutor();
    /** PdfRenderer لا يدعم فتح أكثر من صفحة في نفس الوقت ولا الوصول من عدة خيوط. */
    private final Object renderLock = new Object();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private float[] ratios = new float[0];
    private File currentFile;
    private float zoomPercent = 100f;
    /** آخر نسبة تكبير فعلية اترسمت بيها الصفحات (تتحدّث فقط لما applyZoom
     *  فعليًا يعيد الرسم) - بيُستخدم أثناء حركة القرص (Pinch) لحساب مقياس
     *  عرض مؤقت (scaleX/scaleY) بدون إعادة رسم الـ Bitmaps مع كل حركة
     *  إصبع، فقط عند توقف الحركة. */
    private float renderedZoomPercent = 100f;
    private ScaleGestureDetector pinchDetector;
    /** الوضع الليلي لصفحات الـ PDF: يُفعَّل تلقائياً حسب مظهر النظام، ويمكن للمستخدم تبديله يدوياً من القائمة. */
    private boolean nightPagesEnabled;
    private boolean nightPagesUserOverride = false;
    /** يزيد مع كل تغيير للتكبير؛ أي رسم قديم بجيل مختلف يُتجاهل. */
    private volatile int generation = 0;
    private LruCache<Long, Bitmap> cache;
    private PageAdapter adapter;
    private ActivityResultLauncher<String[]> pickLauncher;
    private boolean translateInProgress = false;
    private static volatile boolean pdfBoxReadyForText = false;

    private static final String[] TRANSLATE_LANG_LABELS = {"العربية", "English", "Français", "Türkçe"};
    private static final String[] TRANSLATE_LANG_CODES = {"ar", "en", "fr", "tr"};

    /** ترجمة الملف كاملاً (كل الصفحات) إلى PDF منسّق - بخلاف "ترجمة الصفحة" أعلاه
     *  التي تعرض نص صفحة واحدة فقط داخل نافذة عابرة. */
    private boolean fullTranslateInProgress = false;
    private volatile boolean fullTranslateCancelled = false;
    private Dialog fullTranslateDialog;
    private TextView fullTranslateStatus;
    private ProgressBar fullTranslateBar;

    /** حفظ نسخة من أي PDF مفتوح حاليًا (الأصلي أو ناتج الترجمة) على الجهاز عبر منتقي حفظ النظام. */
    private ActivityResultLauncher<String> createDocumentLauncher;
    private File pendingSaveSource;

    private final Runnable hideIndicatorRunnable = () -> {
        if (pageIndicator == null) return;
        pageIndicator.animate().alpha(0f).setDuration(250)
                .withEndAction(() -> pageIndicator.setVisibility(View.GONE)).start();
    };

    // ------------------------------------------------------------------ دورة الحياة

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        // تفعيل الوضع الليلي للصفحات تلقائياً إذا كان النظام/التطبيق بالوضع الداكن.
        nightPagesEnabled = isSystemNightMode();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_pdf_viewer);
        // القائمتان مبنيتان بالكامل كـ actionLayout مخصّص (بِسمة التكبير/
        // التصغير وزر "المزيد") بدل عناصر قائمة Toolbar الافتراضية، فمفيش
        // داعي لـ onMenuItemClickListener هنا - كل زر بيوصّل حدثه مباشرة.
        Menu menu = toolbar.getMenu();
        View zoomAction = menu.findItem(R.id.action_pdf_zoom).getActionView();
        if (zoomAction != null) {
            zoomOutBtn = zoomAction.findViewById(R.id.btn_pdf_zoom_out);
            zoomInBtn = zoomAction.findViewById(R.id.btn_pdf_zoom_in);
            if (zoomOutBtn != null) zoomOutBtn.setOnClickListener(v -> changeZoom(-1));
            if (zoomInBtn != null) zoomInBtn.setOnClickListener(v -> changeZoom(+1));
            updateZoomButtonsState();
        }
        View moreAction = menu.findItem(R.id.action_pdf_more).getActionView();
        if (moreAction != null) {
            moreAction.setOnClickListener(this::showMoreMenu);
        }

        titleView = findViewById(R.id.pdf_title);
        subtitleView = findViewById(R.id.pdf_subtitle);
        hScroll = findViewById(R.id.pdf_hscroll);
        pages = findViewById(R.id.pdf_pages);
        loadingBox = findViewById(R.id.pdf_loading);
        loadingText = findViewById(R.id.pdf_loading_text);
        emptyBox = findViewById(R.id.pdf_empty);
        pageIndicator = findViewById(R.id.pdf_page_indicator);

        // ذاكرة مؤقتة للصفحات المرسومة: سدس الذاكرة المتاحة للتطبيق (حد أدنى 24 ميجا)
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<Long, Bitmap>(Math.max(24 * 1024, maxKb / 6)) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        layoutManager = new LinearLayoutManager(this);
        pages.setLayoutManager(layoutManager);
        adapter = new PageAdapter();
        pages.setAdapter(adapter);
        setupPinchZoom();
        pages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy != 0) updateIndicator();
            }
        });
        pageIndicator.setOnClickListener(v -> showGoToPageDialog());
        Ui.applyPressFeedback(pageIndicator);

        pickLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                importAndLoad(uri);
            } else if (renderer == null) {
                finish(); // ألغى الاختيار ولا يوجد ملف معروض
            }
        });

        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/pdf"), this::onSaveDestinationChosen);

        handleIntent(getIntent());
    }

    private void handleIntent(Intent intent) {
        String path = intent.getStringExtra(EXTRA_PATH);
        if (path != null) {
            File f = new File(path);
            String title = intent.getStringExtra(EXTRA_TITLE);
            loadFile(f, title != null && !title.trim().isEmpty() ? title : f.getName());
            return;
        }
        Uri data = intent.getData();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && data != null) {
            importAndLoad(data);
            return;
        }
        launchPicker();
    }

    private void launchPicker() {
        try {
            pickLauncher.launch(new String[]{"application/pdf"});
        } catch (ActivityNotFoundException e) {
            showError("تعذّر اختيار الملف", "لا يوجد تطبيق على جهازك لاختيار الملفات.");
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // تدوير الشاشة: نعيد حساب عرض الصفحات حسب العرض الجديد
        if (ratios.length > 0) hScroll.post(this::applyZoom);
        // إذا لم يتدخّل المستخدم يدوياً، نتابع تلقائياً أي تغيير بمظهر النظام (فاتح/داكن).
        if (!nightPagesUserOverride) {
            boolean night = isSystemNightMode();
            if (night != nightPagesEnabled) {
                nightPagesEnabled = night;
                refreshRenderedPages();
            }
        }
    }

    private boolean isSystemNightMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** يعيد رسم كل الصفحات الظاهرة بعد تبديل الوضع الليلي (يُبطل الذاكرة المؤقتة فقط، بدون تغيير التكبير). */
    private void refreshRenderedPages() {
        if (ratios.length == 0) return;
        generation++;
        cache.evictAll();
        adapter.notifyDataSetChanged();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        loadExecutor.shutdownNow();
        renderExecutor.shutdownNow();
        textExecutor.shutdownNow();
        releaseRenderer();
        cache.evictAll();
    }

    // ------------------------------------------------------------------ القائمة

    private void changeZoom(int direction) {
        if (ratios.length == 0) return;
        setZoomPercent(zoomPercent + direction * ZOOM_STEP_PERCENT);
    }

    /** يضبط نسبة التكبير الفعلية (تُستخدم من أزرار +/- وحركة القرص بإصبعين
     *  الاتنين) - تُحصر بين MIN/MAX_ZOOM_PERCENT ثم يُعاد رسم الصفحات فورًا. */
    private void setZoomPercent(float percent) {
        float clamped = Math.max(MIN_ZOOM_PERCENT, Math.min(MAX_ZOOM_PERCENT, percent));
        if (Math.abs(clamped - zoomPercent) < 0.01f) return;
        zoomPercent = clamped;
        applyZoom();
        updateZoomButtonsState();
    }

    /** يعكس حدود التكبير/التصغير (أقصى/أدنى نسبة) بإطفاء الزر المعني بدل
     *  تركه يبدو فعّالًا وهو بلا تأثير. */
    private void updateZoomButtonsState() {
        if (zoomOutBtn != null) {
            boolean enabled = zoomPercent > MIN_ZOOM_PERCENT + 0.5f;
            zoomOutBtn.setEnabled(enabled);
            zoomOutBtn.setAlpha(enabled ? 1f : 0.35f);
        }
        if (zoomInBtn != null) {
            boolean enabled = zoomPercent < MAX_ZOOM_PERCENT - 0.5f;
            zoomInBtn.setEnabled(enabled);
            zoomInBtn.setAlpha(enabled ? 1f : 0.35f);
        }
    }

    /** يُنشئ كاشف حركة القرص بإصبعين (Pinch-to-zoom) على منطقة عرض الصفحات:
     *  أثناء الحركة نطبّق مقياس عرض مؤقت (scaleX/scaleY) على pages مباشرة
     *  للاستجابة الفورية الناعمة بدون أي إعادة رسم فعلي (رخيص جدًا)، وبس
     *  لما تنتهي حركة الإصبعين (onScaleEnd) نحوّل المقياس المؤقت لنسبة
     *  تكبير حقيقية عبر setZoomPercent() اللي بتعيد رسم الـ Bitmaps بدقة
     *  مناسبة للعرض الجديد - بدل ما نعيد الرسم مع كل حدث لمس (بطيء جدًا
     *  ومكلف للذاكرة مع صفحات PDF عالية الدقة). */
    private void setupPinchZoom() {
        pinchDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                return ratios.length > 0;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float liveScale = pages.getScaleX() * detector.getScaleFactor();
                float minLive = MIN_ZOOM_PERCENT / renderedZoomPercent;
                float maxLive = MAX_ZOOM_PERCENT / renderedZoomPercent;
                liveScale = Math.max(minLive, Math.min(maxLive, liveScale));
                pages.setPivotX(detector.getFocusX());
                pages.setPivotY(detector.getFocusY());
                pages.setScaleX(liveScale);
                pages.setScaleY(liveScale);
                return true;
            }

            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                float finalPercent = renderedZoomPercent * pages.getScaleX();
                pages.setScaleX(1f);
                pages.setScaleY(1f);
                setZoomPercent(finalPercent);
            }
        });
        hScroll.setOnTouchListener((v, event) -> {
            pinchDetector.onTouchEvent(event);
            // إصبعان (Pinch) بيتعامل معاهم الكاشف فقط ولا بيوقف تمرير
            // HorizontalScrollView العادي بإصبع واحد - false يسيب الحدث
            // يكمل مساره الطبيعي للتمرير.
            return false;
        });
    }

    /** يعرض قائمة "المزيد" كـ PopupWindow مخصّص (popup_pdf_more_menu) بنفس
     *  أنماط صفوف الإعدادات (Settings.Row) بدل قائمة Toolbar الافتراضية -
     *  انظر تعليق menu_pdf_viewer.xml. */
    private void showMoreMenu(View anchor) {
        View content = LayoutInflater.from(this).inflate(R.layout.popup_pdf_more_menu, null);

        TextView nightLabel = content.findViewById(R.id.txt_pdf_night_pages);
        if (nightLabel != null) {
            nightLabel.setText(nightPagesEnabled ? "إيقاف الوضع الليلي للصفحات" : "تفعيل الوضع الليلي للصفحات");
        }

        PopupWindow popup = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(Ui.dp(this, 8));

        bindMoreMenuRow(content, popup, R.id.row_pdf_translate_page, this::showTranslateLanguageDialog);
        bindMoreMenuRow(content, popup, R.id.row_pdf_translate_full, this::showFullTranslateLanguageDialog);
        bindMoreMenuRow(content, popup, R.id.row_pdf_save_copy, this::saveCurrentFileCopy);
        bindMoreMenuRow(content, popup, R.id.row_pdf_night_pages, () -> {
            nightPagesUserOverride = true;
            nightPagesEnabled = !nightPagesEnabled;
            refreshRenderedPages();
            Toast.makeText(this, nightPagesEnabled ? "تم تفعيل الوضع الليلي للصفحات" : "تم إيقاف الوضع الليلي للصفحات",
                    Toast.LENGTH_SHORT).show();
        });
        bindMoreMenuRow(content, popup, R.id.row_pdf_goto, this::showGoToPageDialog);
        bindMoreMenuRow(content, popup, R.id.row_pdf_open_external, this::openExternally);
        bindMoreMenuRow(content, popup, R.id.row_pdf_pick_another, this::launchPicker);

        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int xOff = anchor.getWidth() - content.getMeasuredWidth();
        popup.showAsDropDown(anchor, xOff, Ui.dp(this, 4));
    }

    private void bindMoreMenuRow(View root, PopupWindow popup, int rowId, Runnable action) {
        View row = root.findViewById(rowId);
        if (row == null) return;
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
    }

    private void openExternally() {
        if (currentFile == null || !currentFile.exists()) {
            Toast.makeText(this, "لا يوجد ملف مفتوح.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", currentFile);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لا يوجد تطبيق آخر لفتح ملفات PDF.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر فتح الملف بتطبيق آخر.", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ تحميل الملف

    private void importAndLoad(Uri uri) {
        showLoading("جارٍ تجهيز الملف...");
        loadExecutor.execute(() -> {
            try {
                String name = queryDisplayName(uri);
                File dir = new File(getCacheDir(), CACHE_DIR);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                // ننظّف النسخ المستوردة القديمة حتى لا تتراكم في الكاش
                File[] old = dir.listFiles((d, n) -> n.startsWith("imported_"));
                if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                File dest = new File(dir, "imported_" + System.currentTimeMillis() + ".pdf");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dest)) {
                    if (in == null) throw new IOException("تعذّر قراءة الملف المختار.");
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                runOnUiThread(() -> loadFile(dest, name));
            } catch (Exception e) {
                runOnUiThread(() -> showError("تعذّر فتح الملف", errorText(e)));
            }
        });
    }

    private void loadFile(File file, String title) {
        showLoading("جارٍ فتح الملف...");
        titleView.setText(title);
        subtitleView.setVisibility(View.GONE);
        loadExecutor.execute(() -> {
            ParcelFileDescriptor pfd = null;
            PdfRenderer r = null;
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                r = new PdfRenderer(pfd);
                int n = r.getPageCount();
                if (n <= 0) throw new IOException("الملف لا يحتوي على أي صفحات.");
                // نسبة ارتفاع/عرض كل صفحة مسبقًا حتى لا تقفز المواضع أثناء الرسم
                float[] rt = new float[n];
                for (int i = 0; i < n; i++) {
                    PdfRenderer.Page p = r.openPage(i);
                    try {
                        rt[i] = p.getWidth() > 0 ? p.getHeight() / (float) p.getWidth() : 1.414f;
                    } finally {
                        p.close();
                    }
                }
                final PdfRenderer fr = r;
                final ParcelFileDescriptor fpfd = pfd;
                runOnUiThread(() -> onLoaded(fr, fpfd, rt, file));
            } catch (SecurityException e) {
                closeQuietly(r, pfd);
                runOnUiThread(() -> showError("الملف محمي بكلمة مرور",
                        "لا يمكن عرض ملفات PDF المحمية بكلمة مرور داخل التطبيق."));
            } catch (Throwable t) {
                closeQuietly(r, pfd);
                runOnUiThread(() -> showError("تعذّر عرض الملف",
                        "الملف تالف أو ليس PDF صالحًا.\n" + errorText(t)));
            }
        });
    }

    private void onLoaded(PdfRenderer r, ParcelFileDescriptor pfd, float[] rt, File file) {
        if (isFinishing() || isDestroyed()) {
            closeQuietly(r, pfd);
            return;
        }
        releaseRenderer();
        synchronized (renderLock) {
            renderer = r;
            descriptor = pfd;
        }
        currentFile = file;
        ratios = rt;
        zoomPercent = 100f;
        renderedZoomPercent = 100f;
        generation++;
        cache.evictAll();

        subtitleView.setText(BidiText.fix(rt.length == 1 ? "صفحة واحدة" : rt.length + " صفحة"));
        subtitleView.setVisibility(View.VISIBLE);
        loadingBox.setVisibility(View.GONE);
        emptyBox.setVisibility(View.GONE);
        hScroll.setVisibility(View.VISIBLE);
        hScroll.scrollTo(0, 0);
        layoutManager.scrollToPositionWithOffset(0, 0);
        // ننتظر اكتمال قياس الواجهة قبل حساب عرض الصفحات
        hScroll.post(this::applyZoom);
    }

    // ------------------------------------------------------------------ التكبير والرسم

    private void applyZoom() {
        if (isFinishing() || isDestroyed() || ratios.length == 0) return;
        int base = hScroll.getWidth();
        if (base <= 0) {
            hScroll.post(this::applyZoom);
            return;
        }
        int first = layoutManager.findFirstVisibleItemPosition();
        ViewGroup.LayoutParams lp = pages.getLayoutParams();
        lp.width = Math.round(base * zoomPercent / 100f);
        pages.setLayoutParams(lp);
        if (zoomPercent <= 100f) hScroll.scrollTo(0, 0);

        renderedZoomPercent = zoomPercent;
        generation++;
        cache.evictAll();
        adapter.notifyDataSetChanged();
        if (first > 0) layoutManager.scrollToPositionWithOffset(first, 0);
        updateIndicator();
    }

    /** يجمّع نسبة التكبير المستمرة في "دُرجة" صحيحة لاستخدامها كجزء من
     *  مفتاح ذاكرة تخزين الصفحات المؤقتة (cacheKey) - يمنع مفاتيح لا نهائية
     *  مختلفة عند أي تغيّر طفيف، مع بقاء دقة كافية (كل 5%) لإعادة رسم واضحة. */
    private int zoomBucket() {
        return Math.round(zoomPercent / 5f);
    }

    /**
     * عرض الصفحة بالبكسل = عرض المنطقة بعد التكبير - هوامش بطاقة الصفحة الفعلية.
     * إصلاح: كانت القيمة المطروحة 24dp فقط بينما بطاقة item_pdf_page.xml تستهلك
     * فعليًا 48dp (14dp هامش + 10dp حشوة من كل جهة) - الفرق (24dp) كان يخلي عرض
     * البتمَاب المرسوم أوسع من عرض الصورة المعروضة الحقيقي، فيتمدّد/يتشوّه ارتفاع
     * كل صفحة (scaleType="fitXY") بدل ما تناسق حجم الشاشة بشكل سليم.
     */
    private static final int PAGE_CARD_HORIZONTAL_CHROME_DP = 48;

    private int pageWidthPx() {
        int w = Math.round(hScroll.getWidth() * zoomPercent / 100f) - Ui.dp(this, PAGE_CARD_HORIZONTAL_CHROME_DP);
        return Math.max(1, w);
    }

    private static long cacheKey(int page, int zoom) {
        return ((long) page << 8) | zoom;
    }

    /** عرض التصدير الأقصى بالبكسل لخلفية كل صفحة في ملف الترجمة الناتج - أعلى
     *  من عرض شاشة العرض العادي لجودة قراءة أفضل، لكن مقيّد حتى لا يتضخّم
     *  حجم الملف الناتج مع الملفات الكبيرة (كل صفحة بتتضمّن كصورة). */
    private static final int TRANSLATE_EXPORT_WIDTH = 1400;

    /** يرسم صفحة أصلية بجودة تصدير (بدون تحسين/انعكاس الوضع الليلي، فهي
     *  خلفية داخل ملف PDF ناتج ثابت وليست عرضًا حيًا) - تُستخدم فقط أثناء
     *  بناء "ترجمة الملف بالكامل" حتى يحافظ الملف الناتج على تصميم كل صفحة
     *  أصلية (راجع تعليق TranslatedPdfBuilder). يرجّع null لو تعذّر الرسم
     *  (الملف اتقفل أو الصفحة غير موجودة) - النداء المستدعي بيكمل بدونها. */
    private Bitmap renderPageForExportBackground(int page) {
        synchronized (renderLock) {
            if (renderer == null || page < 0 || page >= renderer.getPageCount()) return null;
            PdfRenderer.Page p = null;
            try {
                p = renderer.openPage(page);
                int pw = p.getWidth();
                int ph = p.getHeight();
                if (pw <= 0 || ph <= 0) return null;
                int bw = Math.min(TRANSLATE_EXPORT_WIDTH, MAX_BITMAP_WIDTH);
                int bh = Math.max(1, Math.round(bw * (ph / (float) pw)));
                Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } catch (OutOfMemoryError oom) {
                return null;
            } catch (Exception e) {
                return null;
            } finally {
                if (p != null) p.close();
            }
        }
    }

    /** أبعاد صفحة أصلية بوحدة نقطة PDF (72dpi) - نفس الوحدة اللي PdfDocument
     *  بيستخدمها لحجم الصفحة الناتجة، حتى تطابق أبعاد الصفحة الناتجة الأصل
     *  تمامًا بدل حجم A4 ثابت بغض النظر عن حجم/اتجاه الصفحة الأصلية الفعلي. */
    private float[] pagePointSize(int page) {
        synchronized (renderLock) {
            if (renderer == null || page < 0 || page >= renderer.getPageCount()) return null;
            PdfRenderer.Page p = null;
            try {
                p = renderer.openPage(page);
                return new float[]{p.getWidth(), p.getHeight()};
            } catch (Exception e) {
                return null;
            } finally {
                if (p != null) p.close();
            }
        }
    }

    private Bitmap renderPage(int page, int w, int h) {
        int bw = w;
        int bh = h;
        if (bw > MAX_BITMAP_WIDTH) {
            bh = Math.max(1, (int) ((long) bh * MAX_BITMAP_WIDTH / bw));
            bw = MAX_BITMAP_WIDTH;
        }
        synchronized (renderLock) {
            if (renderer == null || page < 0 || page >= renderer.getPageCount()) return null;
            PdfRenderer.Page p = null;
            try {
                p = renderer.openPage(page);
                Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE); // صفحات PDF شفافة افتراضيًا
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return enhancePage(bmp, nightPagesEnabled);
            } catch (OutOfMemoryError oom) {
                cache.evictAll();
                return null;
            } catch (Exception e) {
                return null;
            } finally {
                if (p != null) p.close();
            }
        }
    }

    /**
     * تحسين تلقائي لجودة النص (تباين أوضح للحروف الرفيعة عند التصغير)، مع قلب الألوان
     * اختيارياً لعرض الصفحة بالوضع الليلي (خلفية داكنة ونص فاتح) بدل الورقة البيضاء الأصلية.
     */
    private static Bitmap enhancePage(Bitmap src, boolean night) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(buildEnhanceMatrix(night)));
        canvas.drawBitmap(src, 0, 0, paint);
        src.recycle();
        return out;
    }

    private static ColorMatrix buildEnhanceMatrix(boolean night) {
        // تباين أعلى قليلاً وتغميق نقطة الأسود: يبرز حروف النص الرفيعة بعد تصغير الصفحة لعرض الشاشة.
        float c = 1.12f;
        float t = -18f * c;
        ColorMatrix matrix = new ColorMatrix(new float[]{
                c, 0, 0, 0, t,
                0, c, 0, 0, t,
                0, 0, c, 0, t,
                0, 0, 0, 1, 0
        });
        if (night) {
            ColorMatrix invert = new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0
            });
            matrix.postConcat(invert);
        }
        return matrix;
    }

    // ------------------------------------------------------------------ مؤشر الصفحة والانتقال

    private void updateIndicator() {
        if (ratios.length == 0) return;
        int pos = layoutManager.findFirstCompletelyVisibleItemPosition();
        if (pos < 0) pos = layoutManager.findFirstVisibleItemPosition();
        if (pos < 0) return;
        pageIndicator.setText(BidiText.fix((pos + 1) + " / " + ratios.length));
        if (pageIndicator.getVisibility() != View.VISIBLE) {
            pageIndicator.setAlpha(0f);
            pageIndicator.setVisibility(View.VISIBLE);
        }
        pageIndicator.animate().cancel();
        pageIndicator.animate().alpha(1f).setDuration(120).start();
        uiHandler.removeCallbacks(hideIndicatorRunnable);
        uiHandler.postDelayed(hideIndicatorRunnable, INDICATOR_HIDE_DELAY_MS);
    }

    private void showGoToPageDialog() {
        final int total = ratios.length;
        if (total == 0) return;
        TextInputEditText input = new TextInputEditText(this);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setGravity(Gravity.START);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        input.setTextSize(15.5f);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_tertiary));
        input.setHint("1 - " + total);

        new ClaudeDialog(this)
                .setTitle("الانتقال إلى صفحة")
                .setView(input)
                .setPositiveButton("انتقال", (dialog, which) -> {
                    String s = input.getText() == null ? "" : input.getText().toString().trim();
                    try {
                        int p = Math.max(1, Math.min(total, Integer.parseInt(s)));
                        layoutManager.scrollToPositionWithOffset(p - 1, 0);
                        updateIndicator();
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ------------------------------------------------------------------ ترجمة الصفحة (Google، مجانًا)

    /** يعرض اختيار اللغة الهدف، ثم يترجم الصفحة المرئية حاليًا إليها. */
    private void showTranslateLanguageDialog() {
        if (ratios.length == 0 || currentFile == null) {
            Toast.makeText(this, "افتح ملف PDF أولًا.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (translateInProgress) {
            Toast.makeText(this, "جارٍ ترجمة صفحة سابقة، يرجى الانتظار...", Toast.LENGTH_SHORT).show();
            return;
        }
        new ClaudeDialog(this)
                .setTitle("ترجمة الصفحة إلى")
                .setItems(TRANSLATE_LANG_LABELS, (dialog, which) ->
                        translateCurrentPage(TRANSLATE_LANG_CODES[which], TRANSLATE_LANG_LABELS[which]))
                .show();
    }

    private void translateCurrentPage(String targetLangCode, String targetLangLabel) {
        int pos = layoutManager.findFirstCompletelyVisibleItemPosition();
        if (pos < 0) pos = layoutManager.findFirstVisibleItemPosition();
        if (pos < 0) pos = 0;
        final int pageIndex = pos;
        final File file = currentFile;

        translateInProgress = true;
        Toast.makeText(this, "جارٍ استخراج نص الصفحة " + (pageIndex + 1) + "...", Toast.LENGTH_SHORT).show();

        textExecutor.execute(() -> {
            String text = extractPageText(file, pageIndex);
            if (text == null || text.trim().isEmpty()) {
                runOnUiThread(() -> {
                    translateInProgress = false;
                    Toast.makeText(this, "لا يوجد نص قابل للاستخراج في هذه الصفحة (قد تكون صورة ممسوحة ضوئيًا).",
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            GoogleTranslateClient.translateAsync(text, targetLangCode, (translated, error) -> {
                translateInProgress = false;
                if (isFinishing() || isDestroyed()) return;
                if (error != null || translated == null || translated.trim().isEmpty()) {
                    Toast.makeText(this, "تعذّر الاتصال بخدمة الترجمة، حاول مرة أخرى بعد قليل.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showTranslationResult(pageIndex + 1, targetLangLabel, translated, "ar".equals(targetLangCode));
            });
        });
    }

    /** استخراج نص صفحة واحدة فعليًا (PdfBox) - وليس OCR، فيعتمد على وجود طبقة نص
     *  حقيقية بالملف (وهذا حال أغلب ملفات PDF المُصدَّرة من Word/برامج التصميم). */
    private String extractPageText(File file, int pageIndex) {
        try {
            if (!pdfBoxReadyForText) {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplicationContext());
                pdfBoxReadyForText = true;
            }
            try (com.tom_roush.pdfbox.pdmodel.PDDocument doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(file)) {
                com.tom_roush.pdfbox.text.PDFTextStripper stripper = new com.tom_roush.pdfbox.text.PDFTextStripper();
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                return stripper.getText(doc);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private void showTranslationResult(int pageNumber, String targetLangLabel, String translatedText, boolean rtl) {
        if (isFinishing() || isDestroyed()) return;

        TextView tv = new TextView(this);
        tv.setText(translatedText);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(15f);
        tv.setLineSpacing(Ui.dp(this, 4), 1f);
        tv.setTextIsSelectable(true);
        tv.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        tv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));
        scroll.addView(tv);

        new ClaudeDialog(this)
                .setTitle("ترجمة الصفحة " + pageNumber + " · " + targetLangLabel)
                .setView(scroll)
                .setPositiveButton("نسخ النص", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("pdf_translation", translatedText));
                        Toast.makeText(this, "تم نسخ النص المترجم.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    // ------------------------------------------------------------------ ترجمة الملف بالكامل (PDF منسّق)

    /** يعرض اختيار اللغة الهدف، ثم يترجم كل صفحات الملف (وليس صفحة واحدة فقط)
     *  ويبني منها ملف PDF منسّق جاهز للعرض والحفظ. */
    private void showFullTranslateLanguageDialog() {
        if (ratios.length == 0 || currentFile == null) {
            Toast.makeText(this, "افتح ملف PDF أولًا.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (translateInProgress || fullTranslateInProgress) {
            Toast.makeText(this, "يوجد عملية ترجمة جارية بالفعل، يرجى الانتظار...", Toast.LENGTH_SHORT).show();
            return;
        }
        new ClaudeDialog(this)
                .setTitle("ترجمة الملف بالكامل إلى")
                .setItems(TRANSLATE_LANG_LABELS, (dialog, which) ->
                        startFullTranslation(TRANSLATE_LANG_CODES[which], TRANSLATE_LANG_LABELS[which]))
                .show();
    }

    private void startFullTranslation(String targetLangCode, String targetLangLabel) {
        final int total = ratios.length;
        final File sourceFile = currentFile;
        final String sourceTitle = titleView.getText() != null ? titleView.getText().toString() : "مستند PDF";

        fullTranslateInProgress = true;
        fullTranslateCancelled = false;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        fullTranslateStatus = new TextView(this);
        fullTranslateStatus.setTextColor(getColor(R.color.text_primary));
        fullTranslateStatus.setTextSize(14f);
        fullTranslateStatus.setTextDirection(View.TEXT_DIRECTION_RTL);
        fullTranslateStatus.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        fullTranslateStatus.setText(BidiText.fix("جارٍ التحضير..."));
        box.addView(fullTranslateStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        fullTranslateBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        barLp.topMargin = Ui.dp(this, 14);
        fullTranslateBar.setLayoutParams(barLp);
        fullTranslateBar.setMax(100);
        fullTranslateBar.setProgress(0);
        fullTranslateBar.setProgressDrawable(getDrawable(R.drawable.bg_upload_progress));
        box.addView(fullTranslateBar);

        fullTranslateDialog = new ClaudeDialog(this)
                .setTitle("ترجمة الملف بالكامل")
                .setView(box)
                .setNegativeButton("إلغاء", (d, w) -> fullTranslateCancelled = true)
                .create();
        fullTranslateDialog.setCancelable(false);
        fullTranslateDialog.show();

        textExecutor.execute(() -> {
            String base = sourceTitle.replaceAll("(?i)\\.pdf$", "").trim();
            boolean rtlOut = "ar".equals(targetLangCode);
            File outFile;
            // إصلاح مهم (طلب المستخدم + خطر تعطّل بالذاكرة): النسخة القديمة
            // كانت بتستخرج النص بس وتبني لوحة ترجمة منفصلة فوق الصفحة (أو
            // صفحة نص بديلة بالكامل في نسخة أقدم)، فتصميم الصفحة الأصلية
            // (صور، ألوان، تخطيط) يفضل زي ما هو لكن الترجمة نفسها بتظهر في
            // لوحة مجمّعة بدل مكان كل سطر أصلي بالظبط. دلوقتي: كل صفحة أصلية
            // بترتسم كخلفية (زي PdfRenderer في القارئ العادي) بأبعادها
            // الحقيقية، وكل سطر نص بيتستخرج بصندوق إحاطة (PdfLineExtractor)
            // ويتترجم مع الحفاظ على محاذاته سطرًا-بسطر (GoogleTranslateClient
            // .translateLinesAsync)، فـ TranslatedPdfBuilder يقدر يمسح كل سطر
            // أصلي ويرسم ترجمته في نفس مكانه بالضبط - استبدال حقيقي بدل لوحة
            // منفصلة. الصفحة بتتضاف فورًا (streaming) لملف الترجمة الناتج
            // بدل ما تتجمّع كل صور الصفحات في الذاكرة أولًا - كان ده هيسبب
            // تعطّل (OutOfMemoryError) على ملفات كبيرة زي كتب العلاج
            // الطبيعي (100+ صفحة). صفحة اتفشل استخراج/ترجمة نصها هتفضل
            // خلفيتها زي ما هي بدون أي إضافة، بدل ما توقف ترجمة باقي الملف.
            try (TranslatedPdfBuilder builder = new TranslatedPdfBuilder(base, targetLangLabel, total, rtlOut)) {
                for (int i = 0; i < total; i++) {
                    if (fullTranslateCancelled) {
                        finishFullTranslateCancelled();
                        return;
                    }
                    int pageNum = i + 1;
                    updateFullTranslateProgress(pageNum, total, "جارٍ تجهيز الصفحة " + pageNum + " من " + total);
                    Bitmap background = renderPageForExportBackground(i);
                    float[] pointSize = pagePointSize(i);
                    float pageWidthPt = pointSize != null ? pointSize[0] : 0f;
                    float pageHeightPt = pointSize != null ? pointSize[1] : 0f;

                    updateFullTranslateProgress(pageNum, total, "جارٍ استخراج نص الصفحة " + pageNum + " من " + total);
                    List<PdfLineExtractor.Line> lines = PdfLineExtractor.extractLines(getApplicationContext(), sourceFile, i);
                    List<String> translatedLines = null;
                    if (!lines.isEmpty()) {
                        if (fullTranslateCancelled) {
                            finishFullTranslateCancelled();
                            return;
                        }
                        updateFullTranslateProgress(pageNum, total, "جارٍ ترجمة الصفحة " + pageNum + " من " + total);
                        List<String> originals = new ArrayList<>(lines.size());
                        for (PdfLineExtractor.Line ln : lines) originals.add(ln.text);
                        // لو فشلت ترجمة هذه الصفحة تحديدًا (بترجع null) نسيبها
                        // null هنا - يعني addPage هتسيب خلفية هذه الصفحة "متل
                        // ما هي" بدون أي إضافة.
                        translatedLines = translateLinesBlockingSync(originals, targetLangCode);
                    }
                    builder.addPage(pageNum, background, pageWidthPt, pageHeightPt, lines, translatedLines);
                }
                if (fullTranslateCancelled) {
                    finishFullTranslateCancelled();
                    return;
                }

                updateFullTranslateProgress(total, total, "جارٍ إنشاء ملف PDF المترجم");
                File dir = new File(getCacheDir(), CACHE_DIR);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                outFile = new File(dir, "translated_" + System.currentTimeMillis() + ".pdf");
                builder.writeTo(outFile);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    fullTranslateInProgress = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (fullTranslateDialog != null) fullTranslateDialog.dismiss();
                    Toast.makeText(this, "تعذّر إنشاء ملف الترجمة: " + errorText(e), Toast.LENGTH_LONG).show();
                });
                return;
            }
            final File finalOutFile = outFile;
            final String finalBase = base;
            runOnUiThread(() -> {
                fullTranslateInProgress = false;
                if (isFinishing() || isDestroyed()) return;
                if (fullTranslateDialog != null) fullTranslateDialog.dismiss();
                openTranslatedPdf(finalOutFile, "ترجمة - " + finalBase);
            });
        });
    }

    private void updateFullTranslateProgress(int current, int total, String message) {
        int pct = total > 0 ? Math.min(100, current * 100 / total) : 0;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (fullTranslateStatus != null) fullTranslateStatus.setText(BidiText.fix(message + " · " + pct + "%"));
            if (fullTranslateBar != null) fullTranslateBar.setProgress(pct);
        });
    }

    private void finishFullTranslateCancelled() {
        runOnUiThread(() -> {
            fullTranslateInProgress = false;
            if (isFinishing() || isDestroyed()) return;
            if (fullTranslateDialog != null) fullTranslateDialog.dismiss();
            Toast.makeText(this, "تم إلغاء ترجمة الملف.", Toast.LENGTH_SHORT).show();
        });
    }

    /** نسخة متزامنة (تحجب خيط الاستدعاء فقط، وليس الخيط الرئيسي) من GoogleTranslateClient
     *  حتى يمكن ترجمة الصفحات الواحدة تلو الأخرى داخل حلقة، مع الاستفادة من نفس
     *  آليات التسلسل/الفاصل الزمني/إعادة المحاولة الموجودة أصلًا في العميل. */
    private String translateBlockingSync(String text, String targetLangCode) {
        final String[] holder = {null};
        CountDownLatch latch = new CountDownLatch(1);
        GoogleTranslateClient.translateAsync(text, targetLangCode, (translated, error) -> {
            holder[0] = (error == null) ? translated : null;
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return holder[0];
    }

    /** نفس فكرة translateBlockingSync لكن لقائمة أسطر مع الحفاظ على محاذاتها
     *  (GoogleTranslateClient.translateLinesAsync) - تُستخدم في بناء ملف
     *  الترجمة الكامل حتى يُستبدل كل سطر بترجمته في مكانه بالضبط. */
    private List<String> translateLinesBlockingSync(List<String> lines, String targetLangCode) {
        final List<String>[] holder = new List[]{null};
        CountDownLatch latch = new CountDownLatch(1);
        GoogleTranslateClient.translateLinesAsync(lines, targetLangCode, (translated, error) -> {
            holder[0] = (error == null) ? translated : null;
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return holder[0];
    }

    /** يفتح ملف الترجمة الناتج مباشرة في نافذة قارئ جديدة (فوق الملف الأصلي)
     *  حتى يظهر للمستخدم فور انتهاء الترجمة، ومنها يمكنه "حفظ نسخة على الجهاز". */
    private void openTranslatedPdf(File file, String title) {
        Intent i = new Intent(this, PdfViewerActivity.class)
                .putExtra(EXTRA_PATH, file.getAbsolutePath())
                .putExtra(EXTRA_TITLE, title);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    // ------------------------------------------------------------------ حفظ/تنزيل نسخة على الجهاز

    /** يفتح منتقي حفظ النظام (Storage Access Framework) ليختار المستخدم مكان
     *  واسم الحفظ بنفسه - يعمل مع أي PDF مفتوح حاليًا، الأصلي أو ناتج الترجمة. */
    private void saveCurrentFileCopy() {
        if (currentFile == null || !currentFile.exists()) {
            Toast.makeText(this, "لا يوجد ملف مفتوح لحفظه.", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingSaveSource = currentFile;
        CharSequence titleText = titleView.getText();
        String suggested = titleText != null && titleText.length() > 0 ? titleText.toString() : currentFile.getName();
        if (!suggested.toLowerCase(Locale.ROOT).endsWith(".pdf")) suggested = suggested + ".pdf";
        try {
            createDocumentLauncher.launch(suggested);
        } catch (Exception e) {
            pendingSaveSource = null;
            Toast.makeText(this, "تعذّر فتح نافذة الحفظ.", Toast.LENGTH_SHORT).show();
        }
    }

    private void onSaveDestinationChosen(Uri destination) {
        if (destination == null || pendingSaveSource == null) return;
        final File src = pendingSaveSource;
        pendingSaveSource = null;
        loadExecutor.execute(() -> {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException("تعذّر فتح الوجهة للكتابة.");
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                runOnUiThread(() -> Toast.makeText(this, "تم حفظ الملف بنجاح.", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "تعذّر حفظ الملف: " + errorText(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ------------------------------------------------------------------ الحالات (تحميل/خطأ)

    private void showLoading(String text) {
        loadingText.setText(text);
        loadingBox.setVisibility(View.VISIBLE);
        emptyBox.setVisibility(View.GONE);
        hScroll.setVisibility(View.GONE);
        pageIndicator.setVisibility(View.GONE);
    }

    private void showError(String title, String body) {
        if (isFinishing() || isDestroyed()) return;
        loadingBox.setVisibility(View.GONE);
        hScroll.setVisibility(View.GONE);
        pageIndicator.setVisibility(View.GONE);
        emptyBox.setVisibility(View.VISIBLE);
        ((ImageView) emptyBox.findViewById(R.id.empty_icon)).setImageResource(R.drawable.ic_pdf);
        ((TextView) emptyBox.findViewById(R.id.empty_title)).setText(title);
        ((TextView) emptyBox.findViewById(R.id.empty_body)).setText(body);
        TextView action = emptyBox.findViewById(R.id.empty_action);
        action.setText("اختيار ملف آخر");
        action.setOnClickListener(v -> launchPicker());
    }

    // ------------------------------------------------------------------ أدوات

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Exception ignored) {
        }
        String path = uri.getLastPathSegment();
        return path != null ? path : "ملف PDF";
    }

    private void releaseRenderer() {
        synchronized (renderLock) {
            closeQuietly(renderer, descriptor);
            renderer = null;
            descriptor = null;
        }
    }

    private static void closeQuietly(PdfRenderer r, ParcelFileDescriptor pfd) {
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String errorText(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------ المحوّل

    private final class PageAdapter extends RecyclerView.Adapter<PageAdapter.PageHolder> {

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pdf_page, parent, false);
            return new PageHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            holder.page = position;
            int w = pageWidthPx();
            int h = Math.max(1, Math.round(w * ratios[position]));
            ViewGroup.LayoutParams lp = holder.image.getLayoutParams();
            if (lp.height != h) {
                lp.height = h;
                holder.image.setLayoutParams(lp);
            }
            Bitmap cached = cache.get(cacheKey(position, zoomBucket()));
            if (cached != null) {
                holder.image.setImageBitmap(cached);
            } else {
                holder.image.setImageDrawable(null);
                requestRender(holder, position, w, h);
            }
        }

        private void requestRender(PageHolder holder, int page, int w, int h) {
            final int gen = generation;
            final int zoom = zoomBucket();
            renderExecutor.execute(() -> {
                // الصفحة خرجت من الشاشة أو تغيّر التكبير قبل دورها: نتجاهلها
                if (gen != generation || holder.page != page) return;
                Bitmap bmp = renderPage(page, w, h);
                if (bmp == null) return;
                if (gen == generation) cache.put(cacheKey(page, zoom), bmp);
                runOnUiThread(() -> {
                    if (gen == generation && holder.page == page) holder.image.setImageBitmap(bmp);
                });
            });
        }

        @Override
        public int getItemCount() {
            return ratios.length;
        }

        final class PageHolder extends RecyclerView.ViewHolder {
            final ImageView image;
            volatile int page = -1;

            PageHolder(@NonNull View itemView) {
                super(itemView);
                image = itemView.findViewById(R.id.pdf_page_image);
            }
        }
    }
}
