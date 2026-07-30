package com.pegasuscorp.orbe;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Sélecteur simple d'application pour un emplacement de raccourci.
 */
public class AppPickerActivity extends AppCompatActivity {

    public static final String EXTRA_SLOT_INDEX = "slot_index";
    public static final String EXTRA_PACKAGE = "package_name";

    private final List<AppListCache.AppEntry> apps = new ArrayList<>();
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ListView list = new ListView(this);
        list.setBackgroundColor(Color.parseColor("#0B0E14"));
        list.setDividerHeight(1);
        list.setPadding(0, 24, 0, 24);
        setContentView(list);

        List<AppListCache.AppEntry> cached = AppListCache.getCached();
        if (cached != null) apps.addAll(cached);

        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppListCache.AppEntry entry = apps.get(position);
            Intent data = new Intent();
            data.putExtra(EXTRA_PACKAGE, entry.pkg);
            setResult(RESULT_OK, data);
            finish();
        });

        if (apps.isEmpty()) {
            AppListCache.loadAsync(this, loaded -> runOnUiThread(() -> {
                apps.clear();
                apps.addAll(loaded);
                adapter.notifyDataSetChanged();
            }));
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int p) { return apps.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(parent.getContext());
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                int pad = (int) (16 * parent.getResources().getDisplayMetrics().density);
                tv.setPadding(pad, pad, pad, pad);
                tv.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            tv.setText(apps.get(position).label);
            return tv;
        }
    }
}
