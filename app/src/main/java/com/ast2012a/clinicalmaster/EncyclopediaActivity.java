package com.ast2012a.clinicalmaster;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

import java.util.List;
import java.util.Set;

public class EncyclopediaActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_encyclopedia);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout container = findViewById(R.id.modes_container);
        container.setLayoutAnimation(
                android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_stagger));

        for (JSONObject m : DataManager.loadModesEncyclopedia(this)) {
            View card = LayoutInflater.from(this).inflate(R.layout.item_mode_card, container, false);
            TextView badgeView = card.findViewById(R.id.mode_badge);
            TextView nameView = card.findViewById(R.id.mode_name);
            TextView rangeView = card.findViewById(R.id.mode_range);
            TextView descView = card.findViewById(R.id.mode_desc);
            TextView safetyView = card.findViewById(R.id.mode_safety);

            String name = m.optString("name", m.optString("title", ""));
            String desc = m.optString("desc", m.optString("description", m.optString("explanation", "")));
            String modeRange = m.optString("mode", "");

            // ربط حقيقي بقاعدة الحالات: نحسب كام حالة موثقة فعليًا تستخدم
            // نفس رقم/أرقام النمط دي - بيانات مُستخرجة من القاعدة نفسها.
            Set<Integer> modeNumbers = DataManager.parseModeNumbers(modeRange);
            int caseCount = DataManager.countCasesForModeNumbers(this, modeNumbers);

            badgeView.setText("📊 " + caseCount + " حالة موثقة");
            nameView.setText(name);
            rangeView.setText("النمط: " + modeRange);
            descView.setText(desc);

            List<String> safetyNotes = DataManager.getGeneralSafetyNote(name);
            if (!safetyNotes.isEmpty()) {
                StringBuilder sb = new StringBuilder("⚠️ ");
                for (String n : safetyNotes) sb.append(n).append(" ");
                safetyView.setText(sb.toString().trim());
                safetyView.setVisibility(View.VISIBLE);
            }

            container.addView(card);
        }
        container.scheduleLayoutAnimation();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
