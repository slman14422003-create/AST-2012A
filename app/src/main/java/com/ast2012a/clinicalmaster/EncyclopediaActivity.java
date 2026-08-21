package com.ast2012a.clinicalmaster;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

public class EncyclopediaActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_encyclopedia);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout container = findViewById(R.id.modes_container);

        for (JSONObject m : DataManager.loadModesEncyclopedia(this)) {
            View card = LayoutInflater.from(this).inflate(R.layout.item_mode_card, container, false);
            TextView nameView = card.findViewById(R.id.mode_name);
            TextView descView = card.findViewById(R.id.mode_desc);

            String name = m.optString("name", m.optString("title", ""));
            String desc = m.optString("desc", m.optString("description", m.optString("explanation", "")));
            String mode = m.optString("mode", "");
            if (!mode.isEmpty()) name = name + "  •  " + mode;

            nameView.setText(name);
            descView.setText(desc);
            container.addView(card);
        }
    }
}
