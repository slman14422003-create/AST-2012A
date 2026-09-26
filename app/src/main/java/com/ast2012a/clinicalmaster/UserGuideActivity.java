package com.ast2012a.clinicalmaster;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * دليل استخدام الجهاز: مرجع ثابت (offline بالكامل، بدون إنترنت) مبني بالكامل
 * من نص دليل المستخدم الرسمي المرفق مع جهاز AST-2012A (User Manual، الإصدار
 * V1.0، Shenzhen OSTO Technology Company Limited) - يغطي دواعي الاستعمال
 * والسلامة ومكونات الجهاز ولوحة الأزرار وخطوات التشغيل والتنظيف والتخزين
 * واستكشاف الأخطاء والضمان والمواصفات الفنية الكاملة.
 *
 * نفس بنية شاشتي "موسوعة الأنماط" (EncyclopediaActivity) و"دليل التشريح"
 * (AnatomyActivity) بالضبط: قائمة أقسام تُبنى ديناميكيًا من ملف أصول واحد
 * (user_guide.json عبر DataManager)، وتُعرض ببطاقات التطبيق القياسية نفسها
 * (عنوان قسم + بطاقة نص/نصيحة + بطاقة نقطية عادية أو بتنبيه أحمر).
 */
public class UserGuideActivity extends AppCompatActivity {

    private LayoutInflater inflater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_guide);
        inflater = LayoutInflater.from(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout container = findViewById(R.id.guide_container);
        container.setLayoutAnimation(
                android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_stagger));

        List<JSONObject> sections = DataManager.loadUserGuide(this);
        for (JSONObject section : sections) {
            String title = section.optString("title", "");
            String icon = section.optString("icon", "");
            if (!title.isEmpty()) addSectionTitle(container, title, icon);

            String body = section.optString("body", "");
            if (!body.isEmpty()) {
                addTextCard(container, body, section.optBoolean("tip", false));
            }

            JSONArray bullets = section.optJSONArray("bullets");
            if (bullets != null && bullets.length() > 0) {
                addBulletCard(container, bullets, section.optBoolean("warning", false));
            }
        }
        container.scheduleLayoutAnimation();
    }

    // ------------------------------------------------------------------

    private void addSectionTitle(LinearLayout container, String title, String iconName) {
        TextView t = (TextView) inflater.inflate(R.layout.item_section_title, container, false);
        t.setText(title);
        int iconRes = iconFor(iconName);
        if (iconRes != 0) {
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
            t.setCompoundDrawablePadding(Ui.dp(this, 8));
            t.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.primary_cyan)));
        }
        container.addView(t);
    }

    private void addTextCard(LinearLayout container, String body, boolean tip) {
        View card = inflater.inflate(R.layout.item_text_card, container, false);
        card.findViewById(R.id.text_card_label).setVisibility(View.GONE);
        TextView bodyView = card.findViewById(R.id.text_card_body);
        bodyView.setText(BidiText.fix(body));
        if (tip) {
            card.setBackgroundResource(R.drawable.bg_tip_callout);
            bodyView.setTextColor(getColor(R.color.m3_on_tertiary_container));
        }
        container.addView(card);
    }

    private void addBulletCard(LinearLayout container, JSONArray bullets, boolean warning) {
        View card = inflater.inflate(R.layout.item_list_card, container, false);
        if (warning) card.setBackgroundResource(R.drawable.bg_glass_card_warning);
        card.findViewById(R.id.list_title).setVisibility(View.GONE);
        LinearLayout list = card.findViewById(R.id.list_container);
        for (int i = 0; i < bullets.length(); i++) {
            String line = bullets.optString(i, "");
            if (line.isEmpty()) continue;
            View row = inflater.inflate(R.layout.item_bullet_row, list, false);
            TextView bulletText = row.findViewById(R.id.bullet_text);
            bulletText.setText(BidiText.fix(line));
            list.addView(row);
        }
        container.addView(card);
    }

    /** يحوّل اسم الأيقونة النصي في user_guide.json إلى drawable فعلي؛ كل الأيقونات موجودة مسبقًا في التطبيق. */
    private int iconFor(String name) {
        switch (name) {
            case "smartphone": return R.drawable.ic_smartphone;
            case "zap": return R.drawable.ic_zap;
            case "ban": return R.drawable.ic_ban;
            case "alert": return R.drawable.ic_alert;
            case "shield": return R.drawable.ic_shield;
            case "info": return R.drawable.ic_info;
            case "folder": return R.drawable.ic_folder;
            case "sliders": return R.drawable.ic_sliders;
            case "book": return R.drawable.ic_book;
            case "check": return R.drawable.ic_check;
            case "refresh": return R.drawable.ic_refresh;
            case "lock": return R.drawable.ic_lock;
            case "help": return R.drawable.ic_help;
            case "trash": return R.drawable.ic_trash;
            case "clipboard": return R.drawable.ic_clipboard;
            case "globe": return R.drawable.ic_globe;
            case "pulse": return R.drawable.ic_pulse;
            case "phone": return R.drawable.ic_phone;
            default: return 0;
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
