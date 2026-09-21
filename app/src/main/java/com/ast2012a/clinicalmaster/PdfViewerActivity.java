package com.ast2012a.clinicalmaster;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    /** نسب التكبير المتاحة (% من عرض الشاشة). حد أقصى 200% لتجنّب استهلاك الذاكرة. */
    private static final int[] ZOOM_PERCENT = {100, 150, 200};
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

    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    /** PdfRenderer لا يدعم فتح أكثر من صفحة في نفس الوقت ولا الوصول من عدة خيوط. */
    private final Object renderLock = new Object();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private float[] ratios = new float[0];
    private File currentFile;
    private int zoomIndex = 0;
    /** يزيد مع كل تغيير للتكبير؛ أي رسم قديم بجيل مختلف يُتجاهل. */
    private volatile int generation = 0;
    private LruCache<Long, Bitmap> cache;
    private PageAdapter adapter;
    private ActivityResultLauncher<String[]> pickLauncher;

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

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_pdf_viewer);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

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
        releaseRenderer();
        cache.evictAll();
    }

    // ------------------------------------------------------------------ القائمة

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_pdf_zoom_in) {
            changeZoom(+1);
            return true;
        } else if (id == R.id.action_pdf_zoom_out) {
            changeZoom(-1);
            return true;
        } else if (id == R.id.action_pdf_goto) {
            showGoToPageDialog();
            return true;
        } else if (id == R.id.action_pdf_open_external) {
            openExternally();
            return true;
        } else if (id == R.id.action_pdf_pick_another) {
            launchPicker();
            return true;
        }
        return false;
    }

    private void changeZoom(int delta) {
        if (ratios.length == 0) return;
        int next = zoomIndex + delta;
        if (next < 0 || next >= ZOOM_PERCENT.length) return;
        zoomIndex = next;
        applyZoom();
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
        zoomIndex = 0;
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
        lp.width = base * ZOOM_PERCENT[zoomIndex] / 100;
        pages.setLayoutParams(lp);
        if (zoomIndex == 0) hScroll.scrollTo(0, 0);

        generation++;
        cache.evictAll();
        adapter.notifyDataSetChanged();
        if (first > 0) layoutManager.scrollToPositionWithOffset(first, 0);
        updateIndicator();
    }

    /** عرض الصفحة بالبكسل = عرض المنطقة بعد التكبير - هوامش البطاقة (12dp من كل جهة). */
    private int pageWidthPx() {
        int w = hScroll.getWidth() * ZOOM_PERCENT[zoomIndex] / 100 - Ui.dp(this, 24);
        return Math.max(1, w);
    }

    private static long cacheKey(int page, int zoom) {
        return ((long) page << 8) | zoom;
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
                return bmp;
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
            Bitmap cached = cache.get(cacheKey(position, zoomIndex));
            if (cached != null) {
                holder.image.setImageBitmap(cached);
            } else {
                holder.image.setImageDrawable(null);
                requestRender(holder, position, w, h);
            }
        }

        private void requestRender(PageHolder holder, int page, int w, int h) {
            final int gen = generation;
            final int zoom = zoomIndex;
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
