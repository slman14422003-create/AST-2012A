package com.ast2012a.clinicalmaster;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

/**
 * دليل التشريح: مرجع سريع وثابت (offline بالكامل) للمسارات العصبية
 * والعضلية الشائعة ومواضع الأقطاب المقترحة لكل منها، ليكمّل بروتوكولات
 * قاعدة البيانات السريرية بمعلومة تشريحية عملية قبل بدء الجلسة.
 */
public class AnatomyActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_anatomy);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout container = findViewById(R.id.anatomy_container);
        container.setLayoutAnimation(
                android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_stagger));

        for (JSONObject a : DataManager.loadAnatomyReference(this)) {
            View card = LayoutInflater.from(this).inflate(R.layout.item_anatomy_card, container, false);
            TextView regionView = card.findViewById(R.id.anatomy_region);
            TextView nameView = card.findViewById(R.id.anatomy_name);
            TextView nerveView = card.findViewById(R.id.anatomy_nerve);
            TextView landmarksView = card.findViewById(R.id.anatomy_landmarks);
            TextView notesView = card.findViewById(R.id.anatomy_notes);

            regionView.setText(a.optString("region", ""));
            nameView.setText(a.optString("name", ""));
            String nerve = a.optString("nerve", "");
            if (!nerve.isEmpty()) {
                nerveView.setText("العصب: " + nerve);
                nerveView.setVisibility(View.VISIBLE);
            } else {
                nerveView.setVisibility(View.GONE);
            }
            landmarksView.setText(a.optString("landmarks", ""));

            String notes = a.optString("notes", "");
            if (!notes.isEmpty()) {
                notesView.setText("تنبيه: " + notes);
                notesView.setVisibility(View.VISIBLE);
            } else {
                notesView.setVisibility(View.GONE);
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
