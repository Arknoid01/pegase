package com.pegasuscorp.orbe.copilot;

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

import com.pegasuscorp.orbe.AppListCache;

import java.util.ArrayList;
import java.util.List;

/**
 * Sélecteur d'app installée pour les listes blanches copilote.
 */
public class CopilotAppPickerActivity extends AppCompatActivity {

    public static final String EXTRA_TARGET = "whitelist_target";
    public static final String TARGET_SCREEN = "screen";
    public static final String TARGET_NOTIF = "notif";

    private final List<AppListCache.AppEntry> apps = new ArrayList<>();
    private AppAdapter adapter;
    private String target;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        target = getIntent().getStringExtra(EXTRA_TARGET);
        if (target == null) target = TARGET_SCREEN;

        ListView list = new ListView(this);
        list.setBackgroundColor(Color.parseColor("#0B0E14"));
        list.setDividerHeight(1);
        list.setPadding(0, 24, 0, 24);
        setContentView(list);

        TextView header = new TextView(this);
        header.setText(TARGET_NOTIF.equals(target)
                ? "Choisir une app — notifications"
                : "Choisir une app — analyse d'écran");
        header.setTextColor(Color.parseColor("#35D0DD"));
        header.setTextSize(14);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        header.setPadding(pad, pad, pad, pad);
        list.addHeaderView(header);

        List<AppListCache.AppEntry> cached = AppListCache.getCached();
        if (cached != null) apps.addAll(cached);

        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(this::onAppSelected);

        if (apps.isEmpty()) {
            AppListCache.loadAsync(this, loaded -> runOnUiThread(() -> {
                apps.clear();
                apps.addAll(loaded);
                adapter.notifyDataSetChanged();
            }));
        }
    }

    private void onAppSelected(AdapterView<?> parent, View view, int position, long id) {
        int index = position - 1;
        if (index < 0 || index >= apps.size()) return;
        String pkg = apps.get(index).pkg;
        if (TARGET_NOTIF.equals(target)) {
            CopilotPrefs.addToNotificationWhitelist(this, pkg);
            CopilotPrefs.setNotificationCopilotEnabled(this, true);
        } else {
            CopilotPrefs.addToWhitelist(this, pkg);
            CopilotPrefs.setScreenAnalysisEnabled(this, true);
            CopilotClient.get().sync(this);
        }
        setResult(RESULT_OK);
        finish();
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
            AppListCache.AppEntry entry = apps.get(position);
            tv.setText(entry.label);
            return tv;
        }
    }
}
