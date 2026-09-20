package com.ast2012a.clinicalmaster;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * شاشة تفاصيل الحالة (البروتوكول) — إعادة تصميم كاملة.
 *
 * الترتيب: عنوان وشارات النوع ← إعدادات الجهاز (النمط/التردد/القناة/المدة)
 * ← وضع الأقطاب كبطاقات قنوات بعلامات (+) و(−) ← الشرح السريري ← الأعراض
 * والخطة والنصيحة ← ملاحظات السلامة ← الإجراءات. النصوص الحرة في قاعدة
 * البيانات تُحلَّل عبر ProtocolParser، وأي نص لا يُفهم بثقة يُعرض كما هو.
 */
public class CaseDetailActivity extends AppCompatActivity {

    private CaseItem currentCase;
    private LinearLayout container;
    private MaterialToolbar toolbar;
    private LayoutInflater inflater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        inflater = LayoutInflater.from(this);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_detail);
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);

        container = findViewById(R.id.detail_container);

        loadCaseFromIntent();
    }

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_favorite) {
            if (currentCase == null) return true;
            boolean nowFavorite = FavoritesManager.toggleFavorite(this, currentCase.title);
            refreshFavoriteIcon(nowFavorite);
            return true;
        }
        if (id == R.id.action_share) {
            shareCase();
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

    // =====================================================================
    // البناء
    // =====================================================================

    private void render() {
        container.removeAllViews();

        addHero();
        addSpecCard();

        if (ProtocolParser.isElectrodeProtocol(currentCase.mode)) {
            addElectrodes();
        } else {
            addPlainDetails();
        }

        if (notEmpty(currentCase.explanation)) {
            addSectionTitle("الشرح السريري");
            addTextCard(null, currentCase.explanation, false);
        }
        addTextCard("الأعراض المرتبطة", currentCase.symptoms, false);
        addTextCard("خطة الجلسات", currentCase.sessionsPlan, false);
        addTextCard("نصيحة عملية", currentCase.tip, true);

        addSafety();
        addActions();
    }

    private boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void addSectionTitle(String title) {
        TextView t = (TextView) inflater.inflate(R.layout.item_section_title, container, false);
        t.setText(title);
        container.addView(t);
    }

    // ---------------------------------------------------------------- الرأس

    private void addHero() {
        View hero = inflater.inflate(R.layout.item_detail_hero, container, false);
        LinearLayout badges = hero.findViewById(R.id.hero_badges);
        TextView titleView = hero.findViewById(R.id.hero_title);
        TextView englishView = hero.findViewById(R.id.hero_english);

        ProtocolParser.TitleParts parts = ProtocolParser.splitTitle(currentCase.title);
        titleView.setText(BidiText.fix(parts.main));
        if (!parts.english.isEmpty()) {
            englishView.setText(parts.english);
            englishView.setVisibility(View.VISIBLE);
        }

        if (ProtocolParser.hasTens(currentCase.mode)) {
            addBadge(badges, "TENS", "TENS");
        }
        if (ProtocolParser.hasEms(currentCase.mode)) {
            addBadge(badges, "EMS", "EMS");
        }
        if (currentCase.custom) {
            TextView custom = addBadge(badges, "حالة مخصصة", "");
            custom.setTextDirection(View.TEXT_DIRECTION_RTL);
            custom.setBackgroundResource(R.drawable.bg_source_chip);
            custom.setTextColor(getColor(R.color.m3_on_primary_container));
        }
        if (badges.getChildCount() == 0) badges.setVisibility(View.GONE);

        container.addView(hero);
    }

    /** يضيف شارة صغيرة؛ type = "TENS" أو "EMS" يحدد اللون، وأي قيمة أخرى تعني لون TENS. */
    private TextView addBadge(LinearLayout parent, String text, String type) {
        TextView badge = (TextView) inflater.inflate(R.layout.item_badge, parent, false);
        badge.setText(text);
        applyBadgeColors(badge, type);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) badge.getLayoutParams();
        lp.setMarginEnd(dp(6));
        parent.addView(badge);
        return badge;
    }

    private void applyBadgeColors(TextView badge, String type) {
        if ("EMS".equals(type)) {
            badge.setBackgroundResource(R.drawable.bg_badge_ems);
            badge.setTextColor(getColor(R.color.m3_on_primary_container));
        } else {
            badge.setBackgroundResource(R.drawable.bg_badge_tens);
            badge.setTextColor(getColor(R.color.m3_on_secondary_container));
        }
    }

    // ------------------------------------------------------- إعدادات الجهاز

    private void addSpecCard() {
        View card = inflater.inflate(R.layout.item_spec_card, container, false);
        LinearLayout rows = card.findViewById(R.id.spec_rows);
        boolean first = true;

        if (notEmpty(currentCase.mode)) {
            LinearLayout values = addSpecRow(rows, "النمط", first);
            first = false;
            ProtocolParser.Modes modes = ProtocolParser.parseModes(currentCase.mode);
            if (modes.chips.isEmpty()) {
                addPlainValue(values, currentCase.mode);
            } else {
                for (ProtocolParser.ModeChip chip : modes.chips) addModeLine(values, chip);
            }
        }

        if (notEmpty(currentCase.freq)) {
            LinearLayout values = addSpecRow(rows, "التردد", first);
            first = false;
            for (ProtocolParser.FreqLine line : ProtocolParser.parseFrequency(currentCase.freq)) {
                addFreqLine(values, line);
            }
        }

        if (notEmpty(currentCase.channel)) {
            LinearLayout values = addSpecRow(rows, "القناة", first);
            first = false;
            addPlainValue(values, currentCase.channel);
        }

        if (notEmpty(currentCase.duration)) {
            LinearLayout values = addSpecRow(rows, "المدة", first);
            first = false;
            addPlainValue(values, currentCase.duration);
        }

        if (rows.getChildCount() > 0) container.addView(card);
    }

    private LinearLayout addSpecRow(LinearLayout rows, String label, boolean first) {
        View row = inflater.inflate(R.layout.item_spec_row, rows, false);
        ((TextView) row.findViewById(R.id.spec_label)).setText(label);
        if (first) row.findViewById(R.id.spec_divider).setVisibility(View.GONE);
        rows.addView(row);
        return row.findViewById(R.id.spec_values);
    }

    private void addPlainValue(LinearLayout values, String text) {
        TextView v = (TextView) inflater.inflate(R.layout.item_plain_value, values, false);
        v.setText(BidiText.fix(text));
        v.setTextSize(15f);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        values.addView(v);
    }

    private void addModeLine(LinearLayout values, ProtocolParser.ModeChip chip) {
        View line = inflater.inflate(R.layout.item_mode_line, values, false);
        TextView badge = line.findViewById(R.id.mode_badge);
        TextView text = line.findViewById(R.id.mode_text);
        TextView purpose = line.findViewById(R.id.mode_purpose);

        if (chip.type.isEmpty()) {
            badge.setVisibility(View.GONE);
        } else {
            badge.setText(chip.type);
            applyBadgeColors(badge, chip.type);
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(BidiText.fix("نمط " + chip.number));
        if (!chip.name.isEmpty()) {
            int start = sb.length();
            sb.append("  ").append(BidiText.fix(chip.name));
            sb.setSpan(new ForegroundColorSpan(getColor(R.color.text_secondary)),
                    start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.setText(sb);

        if (!chip.purpose.isEmpty()) {
            purpose.setText(BidiText.fix(chip.purpose));
            purpose.setVisibility(View.VISIBLE);
        }
        values.addView(line);
    }

    private void addFreqLine(LinearLayout values, ProtocolParser.FreqLine line) {
        View row = inflater.inflate(R.layout.item_freq_line, values, false);
        TextView label = row.findViewById(R.id.freq_label);
        TextView value = row.findViewById(R.id.freq_value);
        TextView note = row.findViewById(R.id.freq_note);

        if (!line.label.isEmpty()) {
            label.setText(line.label);
            applyBadgeColors(label, line.label);
            label.setVisibility(View.VISIBLE);
        }
        if (line.value.isEmpty()) {
            value.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) note.getLayoutParams();
            lp.setMarginStart(0);
            note.setTextSize(14.5f);
            note.setTextColor(getColor(R.color.text_primary));
        } else {
            value.setText(line.value);
        }
        if (line.note.isEmpty()) {
            note.setVisibility(View.GONE);
        } else {
            note.setText(BidiText.fix(line.note));
        }
        values.addView(row);
    }

    // --------------------------------------------------------------- الأقطاب

    private void addElectrodes() {
        List<ProtocolParser.PoleCard> cards = ProtocolParser.parsePoles(currentCase.poles);
        if (cards.isEmpty()) return;
        addSectionTitle("وضع الأقطاب");

        for (ProtocolParser.PoleCard pc : cards) {
            View card = inflater.inflate(R.layout.item_pole_card, container, false);
            TextView label = card.findViewById(R.id.pole_label);
            LinearLayout rows = card.findViewById(R.id.pole_rows);
            LinearLayout notes = card.findViewById(R.id.pole_notes);

            if (!pc.label.isEmpty()) {
                label.setText(BidiText.fix(pc.label));
                label.setVisibility(View.VISIBLE);
            }

            for (ProtocolParser.Terminal t : pc.terminals) {
                View row = inflater.inflate(R.layout.item_pole_terminal, rows, false);
                LinearLayout badgeBox = row.findViewById(R.id.terminal_badges);
                if (t.positive) addPolarityBadge(badgeBox, true);
                if (t.negative) addPolarityBadge(badgeBox, false);
                ((TextView) row.findViewById(R.id.terminal_text)).setText(BidiText.fix(t.text));
                rows.addView(row);
            }

            for (String n : pc.notes) {
                TextView note = (TextView) inflater.inflate(R.layout.item_plain_value, notes, false);
                note.setText(BidiText.fix(n));
                note.setTextColor(getColor(R.color.text_secondary));
                note.setTextSize(13.5f);
                note.setPadding(0, 0, 0, dp(8));
                notes.addView(note);
            }
            container.addView(card);
        }
    }

    private void addPolarityBadge(LinearLayout box, boolean positive) {
        TextView b = (TextView) inflater.inflate(R.layout.item_polarity_badge, box, false);
        b.setText(positive ? "+" : "\u2212");
        b.setBackgroundResource(positive ? R.drawable.bg_polarity_pos : R.drawable.bg_polarity_neg);
        b.setTextColor(getColor(positive ? R.color.m3_on_error_container : R.color.m3_on_secondary_container));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.setMarginEnd(dp(4));
        box.addView(b);
    }

    /** للحالات غير المرتبطة بأقطاب (مواصفات، أمان، استكشاف أخطاء): قائمة نقطية بسيطة. */
    private void addPlainDetails() {
        if (currentCase.poles == null || currentCase.poles.isEmpty()) return;
        addSectionTitle("التفاصيل");

        boolean warning = false;
        for (String line : currentCase.poles) {
            String t = line == null ? "" : line.trim();
            if (t.startsWith("\u26D4") || t.startsWith("\u26A0")) warning = true;
        }

        View card = inflater.inflate(R.layout.item_list_card, container, false);
        if (warning) card.setBackgroundResource(R.drawable.bg_glass_card_warning);
        card.findViewById(R.id.list_title).setVisibility(View.GONE);
        LinearLayout list = card.findViewById(R.id.list_container);
        for (String line : currentCase.poles) {
            if (line == null || line.trim().isEmpty()) continue;
            String clean = line.trim().replaceFirst("^\\((\\+|-)\\)\\s*", "");
            addBullet(list, clean);
        }
        container.addView(card);
    }

    private void addBullet(LinearLayout list, String text) {
        View row = inflater.inflate(R.layout.item_bullet_row, list, false);
        ((TextView) row.findViewById(R.id.bullet_text)).setText(BidiText.fix(text));
        list.addView(row);
    }

    // ------------------------------------------------------ نصوص وسلامة

    private void addTextCard(String label, String body, boolean tip) {
        if (!notEmpty(body)) return;
        View card = inflater.inflate(R.layout.item_text_card, container, false);
        TextView labelView = card.findViewById(R.id.text_card_label);
        TextView bodyView = card.findViewById(R.id.text_card_body);

        if (label == null) {
            labelView.setVisibility(View.GONE);
        } else {
            labelView.setText(label);
        }
        bodyView.setText(BidiText.fix(body.trim()));

        if (tip) {
            card.setBackgroundResource(R.drawable.bg_tip_callout);
            labelView.setTextColor(getColor(R.color.m3_on_tertiary_container));
            bodyView.setTextColor(getColor(R.color.m3_on_tertiary_container));
        }
        container.addView(card);
    }

    private void addSafety() {
        List<String> notes = DataManager.getGeneralSafetyNote(currentCase.mode);
        if (notes.isEmpty()) return;

        View card = inflater.inflate(R.layout.item_list_card, container, false);
        card.setBackgroundResource(R.drawable.bg_glass_card_warning);
        TextView title = card.findViewById(R.id.list_title);
        title.setText("ملاحظات السلامة");
        title.setTextColor(getColor(R.color.accent_red));
        LinearLayout list = card.findViewById(R.id.list_container);
        for (String n : notes) addBullet(list, n);
        container.addView(card);
    }

    // ------------------------------------------------------------- الإجراءات

    private void addActions() {
        View actions = inflater.inflate(R.layout.item_detail_actions, container, false);

        actions.findViewById(R.id.btn_log_session).setOnClickListener(v -> logSessionForPatient());

        actions.findViewById(R.id.btn_ask_ai).setOnClickListener(v -> {
            Intent i = new Intent(this, AiAssistantActivity.class);
            i.putExtra("prefill_query", "أخبرني المزيد عن: " + currentCase.title);
            startActivity(i);
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });

        final String term = ProtocolParser.searchTerm(currentCase.title);
        if (term.isEmpty()) {
            actions.findViewById(R.id.sources_box).setVisibility(View.GONE);
        } else {
            actions.findViewById(R.id.btn_physio).setOnClickListener(v -> openExternalSearch(
                    "https://www.physio-pedia.com/index.php?search=", term));
            actions.findViewById(R.id.btn_pubmed).setOnClickListener(v -> openExternalSearch(
                    "https://pubmed.ncbi.nlm.nih.gov/?term=", term));
        }

        if (currentCase.custom) {
            actions.findViewById(R.id.custom_row).setVisibility(View.VISIBLE);
            actions.findViewById(R.id.btn_edit_case).setOnClickListener(v -> editCase());
            actions.findViewById(R.id.btn_delete_case).setOnClickListener(v -> confirmDelete());
        }
        container.addView(actions);
    }

    private void logSessionForPatient() {
        final List<Patient> patients = PatientManager.loadPatients(this);
        if (patients.isEmpty()) {
            Toast.makeText(this, "أضف مريضًا أولًا من شاشة المرضى.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[patients.size()];
        for (int i = 0; i < names.length; i++) {
            String n = patients.get(i).name;
            names[i] = n.isEmpty() ? "(بدون اسم)" : n;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("لأي مريض؟")
                .setItems(names, (dialog, which) -> {
                    Patient p = patients.get(which);
                    PatientDialogs.showSession(this, p.id, null, currentCase.title,
                            () -> Toast.makeText(this, "تم تسجيل الجلسة في ملف المريض.", Toast.LENGTH_SHORT).show());
                })
                .show();
    }

    private void shareCase() {
        if (currentCase == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, currentCase.title);
        send.putExtra(Intent.EXTRA_TEXT, DataManager.buildLocalAnswer(currentCase));
        startActivity(Intent.createChooser(send, "مشاركة الحالة"));
    }

    private void openExternalSearch(String baseUrl, String term) {
        try {
            Uri uri = Uri.parse(baseUrl + Uri.encode(term));
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لا يوجد متصفح لفتح الرابط.", Toast.LENGTH_SHORT).show();
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
