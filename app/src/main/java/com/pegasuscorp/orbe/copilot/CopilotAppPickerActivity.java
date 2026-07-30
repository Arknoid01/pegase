package com.pegasuscorp.orbe.copilot;

import android.content.Intent;
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
import com.pegasuscorp.orbe.iface.IfaceUi;
import com.pegasuscorp.orbe.ui.OrbeTokens;

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
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        target = getIntent().getStringExtra(EXTRA_TARGET);
        if (target == null) target = TARGET_SCREEN;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(OrbeTokens.COLOR_BG);

        TextView header = new TextView(this);
        header.setText(TARGET_NOTIF.equals(target)
                ? "Choisir une app — notifications"
                : "Choisir une app — analyse d'écran");
        header.setTextColor(OrbeTokens.COLOR_CYAN);
        header.setTextSize(14);
        header.setPadding(IfaceUi.dp(this, 16), IfaceUi.dp(this, 20),
                IfaceUi.dp(this, 16), IfaceUi.dp(this, 8));
        root.addView(header, IfaceUi.matchWrap());

        emptyView = new TextView(this);
        emptyView.setText("Chargement des apps…");
        emptyView.setTextColor(OrbeTokens.COLOR_MUTED);
        emptyView.setPadding(IfaceUi.dp(this, 16), IfaceUi.dp(this, 8),
                IfaceUi.dp(this, 16), IfaceUi.dp(this, 8));
        root.addView(emptyView, IfaceUi.matchWrap());

        ListView list = new ListView(this);
        list.setBackgroundColor(OrbeTokens.COLOR_BG);
        list.setDividerHeight(1);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        List<AppListCache.AppEntry> cached = AppListCache.getCached();
        if (cached != null) {
            apps.addAll(cached);
            updateEmptyState();
        }

        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(this::onAppSelected);

        if (apps.isEmpty()) {
            AppListCache.loadAsync(this, loaded -> runOnUiThread(() -> {
                apps.clear();
                apps.addAll(loaded);
                updateEmptyState();
                adapter.notifyDataSetChanged();
            }));
        }
    }

    private void updateEmptyState() {
        if (apps.isEmpty()) {
            emptyView.setText("Aucune app trouvée");
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void onAppSelected(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= apps.size()) return;
        String pkg = apps.get(position).pkg;
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
                tv.setTextColor(OrbeTokens.COLOR_TEXT);
                tv.setTextSize(16);
                int pad = IfaceUi.dp(parent.getContext(), 16);
                tv.setPadding(pad, pad, pad, pad);
                tv.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            tv.setText(apps.get(position).label);
            return tv;
        }
    }
}
