package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

public class CaseDetailActivity extends AppCompatActivity {

    private CaseItem currentCase;
    private LinearLayout container;
    private MaterialToolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_detail);
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);

        container = findViewById(R.id.detail_container);

        loadCaseFromIntent();
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_favorite) {
            if (currentCase == null) return true;
            boolean nowFavorite = FavoritesManager.toggleFavorite(this, currentCase.title);
            refreshFavoriteIcon(nowFavorite);
            return true;
        }
        return false;
    }

    private void refreshFavoriteIcon(boolean isFavorite) {
        MenuItem favItem = toolbar.getMenu().findItem(R.id.action_favorite);
        if (favItem == null) return;
        favItem.setIcon(isFavorite ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
        favItem.setTitle(isFavorite ? "إزالة من المفضلة" : "إضافة للمفضلة");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // إعادة التحميل عند الرجوع من شاشة التعديل حتى تنعكس التغييرات فورًا
        loadCaseFromIntent();
    }

    private void loadCaseFromIntent() {
        String caseId = getIntent().getStringExtra("case_id");
        boolean isCustom = getIntent().getBooleanExtra("is_custom", false);
        String caseTitle = getIntent().getStringExtra("case_title");

        CaseItem found = null;
        if (isCustom && caseId != null) {
            for (CaseItem c : DataManager.loadCustomCases(this)) {
                if (caseId.equals(c.id)) { found = c; break; }
            }
        } else {
            for (CaseItem c : DataManager.loadBuiltinDatabase(this)) {
                if (c.title.equals(caseTitle)) { found = c; break; }
            }
        }

        if (found == null) {
            finish();
            return;
        }
        currentCase = found;
        refreshFavoriteIcon(FavoritesManager.isFavorite(this, currentCase.title));
        render();
    }

    private void render() {
        container.removeAllViews();

        TextView titleView = new TextView(this);
        titleView.setText(currentCase.title);
        titleView.setTextColor(getColor(R.color.text_primary));
        titleView.setTextSize(19);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setGravity(android.view.Gravity.END);
        titleView.setTextDirection(View.TEXT_DIRECTION_RTL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = 24;
        container.addView(titleView, tp);

        addStatGrid();

        if (!currentCase.poles.isEmpty()) {
            addField("⚡ الأقطاب", String.join("\n", currentCase.poles));
        }
        addField("📋 الشرح الإكلينيكي", currentCase.explanation);
        if (currentCase.symptoms != null && !currentCase.symptoms.isEmpty()) {
            addField("🩺 الأعراض", currentCase.symptoms);
        }
        if (currentCase.sessionsPlan != null && !currentCase.sessionsPlan.isEmpty()) {
            addField("📅 خطة الجلسات", currentCase.sessionsPlan);
        }
        if (currentCase.tip != null && !currentCase.tip.isEmpty()) {
            addField("💡 نصيحة", currentCase.tip);
        }

        List<String> safetyNotes = DataManager.getGeneralSafetyNote(currentCase.mode);
        if (!safetyNotes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String n : safetyNotes) sb.append("• ").append(n).append("\n");
            addField("⚠️ ملاحظات سلامة عامة", sb.toString().trim(), true);
        }

        String term = currentCase.title.startsWith("بروتوكول")
                ? DataManager.extractEnglishTerm(currentCase.title) : null;
        if (term == null) {
            // نستخدم عنوان الحالة نفسه (بدون الإيموجي) كبديل، حتى تفضل
            // أزرار المصادر متاحة لأي حالة، مش بس اللي فيها مصطلح إنجليزي بين قوسين.
            term = currentCase.title
                    .replaceAll("[\\p{So}\\p{Cn}\\p{Mn}]", "")
                    .replaceAll("\\s+", " ")
                    .trim();
        }
        if (!term.isEmpty()) {
            final String searchTerm = term;
            LinearLayout sourcesRow = new LinearLayout(this);
            sourcesRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            srp.topMargin = 8;
            sourcesRow.setLayoutParams(srp);
            LinearLayout.LayoutParams halfParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

            Button physioBtn = new Button(this);
            physioBtn.setText("🔗 Physiopedia");
            physioBtn.setOnClickListener(v -> openExternalSearch(
                    "https://www.physio-pedia.com/index.php?search=", searchTerm));

            Button pubmedBtn = new Button(this);
            pubmedBtn.setText("🔗 PubMed");
            pubmedBtn.setOnClickListener(v -> openExternalSearch(
                    "https://pubmed.ncbi.nlm.nih.gov/?term=", searchTerm));

            sourcesRow.addView(physioBtn, halfParams);
            sourcesRow.addView(pubmedBtn, halfParams);
            container.addView(sourcesRow);
        }

        Button askAiBtn = new Button(this);
        askAiBtn.setText("✨ اسأل Phizyo AI عن هذه الحالة");
        LinearLayout.LayoutParams askAiParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        askAiParams.topMargin = 10;
        askAiBtn.setLayoutParams(askAiParams);
        askAiBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, AiAssistantActivity.class);
            i.putExtra("prefill_query", "أخبرني المزيد عن: " + currentCase.title);
            startActivity(i);
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });
        container.addView(askAiBtn);

        if (currentCase.custom) {
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ap.topMargin = 20;
            actions.setLayoutParams(ap);

            Button editBtn = new Button(this);
            editBtn.setText("✏️ تعديل");
            editBtn.setOnClickListener(v -> editCase());

            Button deleteBtn = new Button(this);
            deleteBtn.setText("🗑️ حذف");
            deleteBtn.setOnClickListener(v -> confirmDelete());

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            actions.addView(editBtn, btnParams);
            actions.addView(deleteBtn, btnParams);
            container.addView(actions);
        }
    }

    /** شبكة 2×2 مدمجة لإعدادات الجهاز الأساسية (النمط/التردد/القناة/المدة)
     *  أعلى الشاشة، بدل 4 بطاقات منفصلة كاملة العرض بنفس شكل أي حقل وصفي
     *  تاني - تجميعها في رقائق صغيرة يخليها تُقرأ كمجموعة واحدة متجانسة
     *  وأسرع في المسح البصري من أعلى تفاصيل الحالة. */
    private void addStatGrid() {
        String[][] stats = {
                {"🎯 النمط", currentCase.mode},
                {"📡 التردد", currentCase.freq},
                {"🔌 القناة", currentCase.channel},
                {"⏱️ المدة", currentCase.duration},
        };

        LinearLayout row = null;
        for (String[] stat : stats) {
            String value = stat[1];
            if (value == null || value.isEmpty()) continue;

            if (row == null) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = 8;
                row.setLayoutParams(rp);
                container.addView(row);
            }

            View chip = LayoutInflater.from(this).inflate(R.layout.item_stat_chip, row, false);
            ((TextView) chip.findViewById(R.id.stat_label)).setText(stat[0]);
            ((TextView) chip.findViewById(R.id.stat_value)).setText(value);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) chip.getLayoutParams();
            if (row.getChildCount() == 0) {
                lp.setMarginEnd(4);
            } else {
                lp.setMarginStart(4);
            }
            row.addView(chip);

            if (row.getChildCount() == 2) row = null;
        }
    }

    private void addField(String label, String value) {
        addField(label, value, false);
    }

    /** @param warning لو true، تُعرض البطاقة بتلوين تنبيه (طوبي فاتح) بدل
     *  الخلفية المحايدة العادية - تُستخدم لملاحظات السلامة العامة حتى تبرز
     *  بصريًا عن باقي الحقول الوصفية. */
    private void addField(String label, String value, boolean warning) {
        if (value == null || value.isEmpty()) value = "-";
        View block = LayoutInflater.from(this).inflate(R.layout.item_field_block, container, false);
        if (warning) {
            block.setBackgroundResource(R.drawable.bg_glass_card_warning);
        }
        TextView labelView = block.findViewById(R.id.field_label);
        TextView valueView = block.findViewById(R.id.field_value);
        labelView.setText(label);
        valueView.setText(value);
        container.addView(block);
    }

    private void openExternalSearch(String baseUrl, String term) {
        try {
            Uri uri = Uri.parse(baseUrl + Uri.encode(term));
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void editCase() {
        Intent i = new Intent(this, AddEditCaseActivity.class);
        i.putExtra("edit_case_id", currentCase.id);
        startActivity(i);
        overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("تأكيد الحذف")
                .setMessage("هل تريد حذف \"" + currentCase.title + "\" نهائيًا؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    DataManager.deleteCustomCase(this, currentCase.id);
                    finish();
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
