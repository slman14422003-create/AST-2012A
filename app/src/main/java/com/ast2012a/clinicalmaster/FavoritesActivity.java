package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

/**
 * شاشة "المفضلة": منفصلة تمامًا عن الشاشة الرئيسية (زر رجوع في الشريط
 * العلوي)، بدل الأسلوب القديم اللي كان بيبدّل نتائج شاشة البحث الرئيسية في
 * مكانها بدون أي طريقة واضحة للرجوع لواجهة الترحيب الأصلية.
 */
public class FavoritesActivity extends AppCompatActivity {

    private RecyclerView list;
    private View emptyBox;
    private TextView headerSubtitle;
    private CaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favorites);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        list = findViewById(R.id.favorites_list);
        emptyBox = findViewById(R.id.empty_hint_container);
        headerSubtitle = findViewById(R.id.header_subtitle);

        ((android.widget.ImageView) emptyBox.findViewById(R.id.empty_icon)).setImageResource(R.drawable.ic_star_stroke);
        ((TextView) emptyBox.findViewById(R.id.empty_title)).setText("لا توجد حالات مفضّلة بعد");
        ((TextView) emptyBox.findViewById(R.id.empty_body)).setText(
                "اضغط على النجمة بجانب أي حالة لإضافتها هنا.");
        emptyBox.findViewById(R.id.empty_action).setVisibility(View.GONE);

        adapter = new CaseAdapter(this::openDetail);
        adapter.setOnFavoriteToggleListener((item, nowFavorite) -> {
            if (!nowFavorite) reload(); // إزالة العنصر فورًا من القائمة عند إلغاء التفضيل من هنا
        });
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setLayoutAnimation(
                android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_stagger));
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<CaseItem> favorites = FavoritesManager.getFavoriteCases(this);
        boolean empty = favorites.isEmpty();
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            headerSubtitle.setVisibility(View.GONE);
        } else {
            headerSubtitle.setText(favorites.size() == 1 ? "حالة واحدة" : favorites.size() + " حالة");
            headerSubtitle.setVisibility(View.VISIBLE);
        }
        adapter.setItems(favorites);
        list.scheduleLayoutAnimation();
    }

    private void openDetail(CaseItem item) {
        Intent i = new Intent(this, CaseDetailActivity.class);
        i.putExtra("case_id", item.id);
        i.putExtra("is_custom", item.custom);
        i.putExtra("case_title", item.title);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
