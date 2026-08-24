package com.ast2012a.clinicalmaster;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

public class MyCasesActivity extends AppCompatActivity implements CaseRowAdapter.Callback {

    private RecyclerView list;
    private TextView emptyHint;
    private CaseRowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_cases);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        list = findViewById(R.id.cases_list);
        emptyHint = findViewById(R.id.empty_hint_container);

        adapter = new CaseRowAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setLayoutAnimation(
                android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_stagger));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        var cases = DataManager.loadCustomCases(this);
        adapter.setItems(cases);
        emptyHint.setVisibility(cases.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(cases.isEmpty() ? View.GONE : View.VISIBLE);
        list.scheduleLayoutAnimation();
    }

    @Override
    public void onEdit(CaseItem item) {
        Intent i = new Intent(this, AddEditCaseActivity.class);
        i.putExtra("edit_case_id", item.id);
        startActivity(i);
        overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
    }

    @Override
    public void onDelete(CaseItem item) {
        new AlertDialog.Builder(this)
                .setTitle("تأكيد")
                .setMessage("هل تريد حذف \"" + item.title + "\" نهائيًا؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    DataManager.deleteCustomCase(this, item.id);
                    refresh();
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
