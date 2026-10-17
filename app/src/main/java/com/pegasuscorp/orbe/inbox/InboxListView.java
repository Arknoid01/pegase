package com.pegasuscorp.orbe.inbox;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.pegasuscorp.orbe.ui.OrbeTokens;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Liste de la Messagerie unifiée (onglet « Tout » de l'écran Messagerie).
 * Un appui ouvre la conversation dans l'appli d'origine (Pégase SMS, WhatsApp, Messenger).
 */
public final class InboxListView extends ScrollView {

    private static final int MAX_ROWS = 80;
    private static final int COLOR_SMS = 0xFF35D0DD;
    private static final int COLOR_WHATSAPP = 0xFF25D366;
    private static final int COLOR_MESSENGER = 0xFF4F8DFF;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout column;
    private final TextView status;
    /** Repli quand l'appli WhatsApp / Messenger n'est pas installée : onglet web. */
    private final Consumer<InboxItem.Source> openWebTab;
    private boolean loading;
    /** Onglet affiché : {@code null} = Tout. */
    private InboxCategoryStore.Category filter;
    private List<InboxItem> lastItems = new java.util.ArrayList<>();

    public InboxListView(Context ctx, Consumer<InboxItem.Source> openWebTab) {
        super(ctx);
        this.openWebTab = openWebTab;
        setBackgroundColor(OrbeTokens.COLOR_BG);
        setFillViewport(true);
        column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), dp(4), dp(12), dp(16));
        addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        status = new TextView(ctx);
        status.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
        status.setTextSize(12f);
        status.setPadding(dp(4), 0, dp(4), dp(8));
        status.setText("Chargement…");
        column.addView(status);
    }

    /** Relit SMS + relevés web (hors thread principal) puis redessine. */
    public void reload() {
        if (loading) return;
        loading = true;
        Context app = getContext().getApplicationContext();
        io.execute(() -> {
            List<InboxItem> items = null;
            try {
                items = UnifiedInbox.load(app);
            } catch (Exception ignored) {
            }
            List<InboxItem> result = items;
            main.post(() -> {
                loading = false;
                if (result != null) render(result);
            });
        });
    }

    public void release() {
        io.shutdownNow();
    }

    private void render(List<InboxItem> all) {
        lastItems = all;
        column.removeAllViews();
        Context ctx = getContext();
        java.util.Map<InboxItem, InboxCategoryStore.Category> cat = new java.util.IdentityHashMap<>();
        int[] unreadBy = new int[3]; // Tout, Perso, Pro
        for (InboxItem it : all) {
            InboxCategoryStore.Category c = InboxCategoryStore.get(ctx, it);
            cat.put(it, c);
            if (!it.isUnread()) continue;
            unreadBy[0]++;
            unreadBy[c == InboxCategoryStore.Category.PRO ? 2 : 1]++;
        }
        column.addView(buildFilterRow(unreadBy));
        List<InboxItem> items = new java.util.ArrayList<>();
        for (InboxItem it : all) if (filter == null || cat.get(it) == filter) items.add(it);
        int unread = filter == null ? unreadBy[0]
                : unreadBy[filter == InboxCategoryStore.Category.PRO ? 2 : 1];
        String head = unread == 0 ? "Tout est lu."
                : unread + (unread > 1 ? " conversations non lues" : " conversation non lue");
        if (!SmsThreads.canRead(ctx)) head += " · SMS non autorisés";
        status.setText(head + " · appui long : ranger en Pro / Perso");
        column.addView(status);
        column.addView(buildTakeOverRow());
        int n = 0;
        for (InboxItem it : items) {
            if (n++ >= MAX_ROWS) break;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            column.addView(buildRow(it), lp);
        }
        if (items.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(filter == InboxCategoryStore.Category.PRO
                    ? "Aucune conversation pro. Appui long sur une conversation pour la ranger ici."
                    : "Aucune conversation. Connecte-toi dans les onglets WhatsApp et Messenger.");
            empty.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
            empty.setTextSize(13f);
            column.addView(empty);
        }
    }

    /** Onglets Tout · Perso · Pro, avec leurs non-lus. */
    private View buildFilterRow(int[] unreadBy) {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(2), 0, dp(8));
        InboxCategoryStore.Category[] tabs = {null, InboxCategoryStore.Category.PERSO,
                InboxCategoryStore.Category.PRO};
        String[] labels = {"Tout", "Perso", "Pro"};
        for (int i = 0; i < tabs.length; i++) {
            InboxCategoryStore.Category tab = tabs[i];
            TextView t = new TextView(getContext());
            t.setText(unreadBy[i] > 0 ? labels[i] + "  " + unreadBy[i] : labels[i]);
            boolean active = tab == filter;
            t.setTextColor(active ? Color.BLACK : Color.WHITE);
            t.setTypeface(OrbeTokens.typeMedium());
            t.setTextSize(13f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(14), dp(7), dp(14), dp(7));
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(16));
            d.setColor(active ? OrbeTokens.COLOR_CYAN : OrbeTokens.COLOR_CHIP_BG);
            d.setStroke(dp(1), OrbeTokens.COLOR_CHIP_STROKE);
            t.setBackground(d);
            t.setOnClickListener(v -> {
                filter = tab;
                render(lastItems);
                scrollTo(0, 0);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(8);
            bar.addView(t, lp);
        }
        return bar;
    }

    private void askCategory(InboxItem it) {
        Context ctx = getContext();
        boolean pro = InboxCategoryStore.get(ctx, it) == InboxCategoryStore.Category.PRO;
        new android.app.AlertDialog.Builder(ctx)
                .setTitle(it.name)
                .setItems(new String[]{pro ? "Ranger dans Perso" : "Ranger dans Pro"}, (d, w) -> {
                    InboxCategoryStore.set(ctx, it, pro ? InboxCategoryStore.Category.PERSO
                            : InboxCategoryStore.Category.PRO);
                    render(lastItems);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Interrupteur : Pégase remplace les notifications WhatsApp / Messenger. */
    private View buildTakeOverRow() {
        Context ctx = getContext();
        android.widget.Switch sw = new android.widget.Switch(ctx);
        sw.setText("Notifications WhatsApp et Messenger par Pégase");
        sw.setTextColor(OrbeTokens.COLOR_MUTED_TEXT);
        sw.setTextSize(12f);
        sw.setPadding(dp(4), 0, dp(4), dp(6));
        sw.setChecked(MessagingNotificationHandler.takesOver(ctx));
        sw.setOnCheckedChangeListener((b, on) -> MessagingNotificationHandler.setTakesOver(ctx, on));
        if (!com.pegasuscorp.orbe.notifications.NotificationAccess.isEnabled(ctx)) {
            sw.setText("Autoriser Pégase à lire les notifications ›");
            sw.setChecked(false);
            sw.setOnCheckedChangeListener(null);
            sw.setOnClickListener(v ->
                    com.pegasuscorp.orbe.notifications.NotificationAccess.openSettings(ctx));
        }
        return sw;
    }

    private View buildRow(InboxItem it) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(12), dp(10));
        row.setBackground(card(it.isUnread() ? 0x1A35D0DD : 0x0FFFFFFF));
        row.setOnClickListener(v -> open(it));
        row.setOnLongClickListener(v -> {
            askCategory(it);
            return true;
        });

        TextView badge = new TextView(ctx);
        badge.setText(sourceLetter(it.source));
        badge.setTextColor(Color.BLACK);
        badge.setTypeface(OrbeTokens.typeMedium());
        badge.setTextSize(11f);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(sourceColor(it.source));
        badge.setBackground(dot);
        row.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);

        LinearLayout line1 = new LinearLayout(ctx);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = new TextView(ctx);
        name.setText(it.group ? "👥 " + it.name : it.name);
        name.setTextColor(Color.WHITE);
        name.setTypeface(it.isUnread() ? OrbeTokens.typeMedium() : null);
        name.setTextSize(14f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        line1.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView when = new TextView(ctx);
        // Libellé du site s'il existe, sinon date exacte (SMS, notifications).
        when.setText(!it.timeLabel.isEmpty() || it.timeMs == 0 ? it.timeLabel
                : DateUtils.getRelativeTimeSpanString(it.timeMs));
        when.setTextColor(it.isUnread() ? OrbeTokens.COLOR_CYAN : OrbeTokens.COLOR_MUTED);
        when.setTextSize(11f);
        when.setPadding(dp(8), 0, 0, 0);
        line1.addView(when);
        texts.addView(line1);

        LinearLayout line2 = new LinearLayout(ctx);
        line2.setOrientation(LinearLayout.HORIZONTAL);
        line2.setGravity(Gravity.CENTER_VERTICAL);
        TextView preview = new TextView(ctx);
        preview.setText(it.preview);
        preview.setTextColor(it.isUnread() ? 0xDDFFFFFF : OrbeTokens.COLOR_MUTED_TEXT);
        preview.setTextSize(12f);
        preview.setMaxLines(2);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        line2.addView(preview, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (it.isUnread()) {
            TextView count = new TextView(ctx);
            count.setText(it.unread > 0 ? String.valueOf(it.unread) : "•");
            count.setTextColor(Color.BLACK);
            count.setTextSize(11f);
            count.setGravity(Gravity.CENTER);
            count.setMinWidth(dp(20));
            count.setPadding(dp(6), dp(1), dp(6), dp(1));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(10));
            pill.setColor(sourceColor(it.source));
            count.setBackground(pill);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(8);
            line2.addView(count, clp);
        }
        texts.addView(line2);

        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void open(InboxItem it) {
        Context ctx = getContext();
        if (it.source == InboxItem.Source.SMS) {
            if (it.address.isEmpty()) return;
            try {
                ctx.startActivity(new Intent(Intent.ACTION_SENDTO,
                        Uri.parse("smsto:" + Uri.encode(it.address))));
            } catch (ActivityNotFoundException ignored) {
            }
            return;
        }
        String pkg = it.source == InboxItem.Source.WHATSAPP ? "com.whatsapp" : "com.facebook.orca";
        if (ctx.getPackageManager().getLaunchIntentForPackage(pkg) == null) {
            if (openWebTab != null) openWebTab.accept(it.source);
            return;
        }
        if (ctx instanceof android.app.Activity) {
            InboxOpenActivity.open((android.app.Activity) ctx, it.source, it.name);
            reload();
        }
    }

    private static String sourceLetter(InboxItem.Source s) {
        switch (s) {
            case WHATSAPP: return "W";
            case MESSENGER: return "M";
            default: return "S";
        }
    }

    private static int sourceColor(InboxItem.Source s) {
        switch (s) {
            case WHATSAPP: return COLOR_WHATSAPP;
            case MESSENGER: return COLOR_MESSENGER;
            default: return COLOR_SMS;
        }
    }

    private GradientDrawable card(int colorArgb) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(OrbeTokens.RADIUS_LG));
        d.setColor(colorArgb);
        d.setStroke(dp(1), Color.parseColor(OrbeTokens.CHIP_STROKE));
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
