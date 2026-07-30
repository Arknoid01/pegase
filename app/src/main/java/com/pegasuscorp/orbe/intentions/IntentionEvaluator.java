package com.pegasuscorp.orbe.intentions;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.util.Log;

import com.pegasuscorp.orbe.f1companion.F1DebriefOffer;
import com.pegasuscorp.orbe.f1companion.F1LiveEvent;
import com.pegasuscorp.orbe.f1companion.F1LivePipeline;
import com.pegasuscorp.orbe.f1companion.F1LiveStore;
import com.pegasuscorp.orbe.f1companion.F1NewsFilter;
import com.pegasuscorp.orbe.f1companion.F1NewsPipeline;
import com.pegasuscorp.orbe.f1companion.F1NewsStore;
import com.pegasuscorp.orbe.f1companion.WeekendSnapshot;
import com.pegasuscorp.orbe.intentions.rules.BatteryLowRule;
import com.pegasuscorp.orbe.intentions.rules.BriefReadyRule;
import com.pegasuscorp.orbe.intentions.rules.CalendarSoonRule;
import com.pegasuscorp.orbe.intentions.rules.DriveBluetoothRule;
import com.pegasuscorp.orbe.intentions.rules.F1DebriefReadyRule;
import com.pegasuscorp.orbe.intentions.rules.F1LiveRule;
import com.pegasuscorp.orbe.intentions.rules.F1NewsRule;
import com.pegasuscorp.orbe.intentions.rules.IntentionRule;
import com.pegasuscorp.orbe.intentions.rules.LifePatternRule;
import com.pegasuscorp.orbe.intentions.rules.LifePatternSoonRule;
import com.pegasuscorp.orbe.intentions.rules.ProjectObjectRule;
import com.pegasuscorp.orbe.intentions.rules.WorkWifiRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Orchestre snapshot → règles → policy → notifier. Met à jour last_seen après éval.
 */
public final class IntentionEvaluator {

    private static final String TAG = "IntentionEvaluator";

    private static final List<IntentionRule> TICK_RULES = Arrays.asList(
            new BatteryLowRule(),
            new WorkWifiRule()
    );

    private static final IntentionRule BRIEF_RULE = new BriefReadyRule();

    private IntentionEvaluator() {}

    /** Prefetch terminé avec succès. */
    public static void onBriefReady(Context ctx) {
        evaluate(ctx, true);
        checkF1Debrief(ctx);
        checkF1News(ctx);
        checkF1Live(ctx);
    }

    /** Tick capteurs (~90 min) — re-check F1 si la fiche a changé. */
    public static void evaluateSensors(Context ctx) {
        evaluate(ctx, false);
        checkF1Debrief(ctx);
        checkF1News(ctx);
        checkF1Live(ctx);
    }

    /**
     * Propose une notif si un nouveau Grand Prix est détecté (1× par session).
     */
    public static void checkF1Debrief(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        try {
            WeekendSnapshot snap = F1DebriefOffer.pollForNewRace(app);
            if (snap == null) return;
            IntentionCandidate c = F1DebriefReadyRule.candidateFor(snap);
            if (c == null) return;
            if (!IntentionPolicy.canFire(app, c)) return;
            IntentionPrefs.markFired(app, c.id);
            IntentionNotifier.show(app, c);
        } catch (Exception e) {
            Log.w(TAG, "checkF1Debrief", e);
        }
    }

    /**
     * RSS F1 filtré (équipes favorites) — au plus 1 notif pertinente (policy).
     */
    public static void checkF1News(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        try {
            IntentionCandidate probe = new IntentionCandidate(
                    IntentionIds.F1_NEWS, "Pégase · F1", "probe", "f1_news");
            if (!IntentionPolicy.canFire(app, probe)) return;

            F1NewsFilter.Match match = F1NewsPipeline.findBestUnseen(app);
            if (match == null) return;

            F1NewsPipeline.preparePending(app, match);
            IntentionCandidate c = F1NewsRule.candidateFor(app, match);
            if (c == null) {
                F1NewsStore.acknowledgePending(app);
                return;
            }
            F1NewsStore.markSeen(app, match.item.id());
            IntentionPrefs.markFired(app, c.id);
            IntentionNotifier.show(app, c);
        } catch (Exception e) {
            Log.w(TAG, "checkF1News", e);
        }
    }

    /**
     * Live GP — Safety Car / VSC / pénalités équipes / fin de course (cooldown 8 min).
     */
    public static void checkF1Live(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        try {
            IntentionCandidate probe = new IntentionCandidate(
                    IntentionIds.F1_LIVE, "Pégase · Live F1", "probe", "f1_live");
            if (!IntentionPolicy.canFire(app, probe)) return;
            if (F1LiveStore.tooSoonForAnother(app)) return;

            F1LiveEvent event = F1LivePipeline.pollForOffer(app);
            if (event == null) return;

            IntentionCandidate c = F1LiveRule.candidateFor(app, event);
            if (c == null) {
                F1LiveStore.clearPending(app);
                return;
            }
            F1LiveStore.markEventNotified(app, event.id);
            // Ne pas compter dans le quota quotidien « 2 / jour » (markFired le ferait)
            IntentionNotifier.show(app, c);
        } catch (Exception e) {
            Log.w(TAG, "checkF1Live", e);
        }
    }

    static void evaluate(Context ctx, boolean briefReadyEvent) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        try {
            ContextSnapshot snap = buildSnapshot(app, briefReadyEvent);
            IntentionCandidate picked = null;
            List<IntentionRule> rules;
            if (briefReadyEvent) {
                rules = Arrays.asList(BRIEF_RULE);
            } else {
                rules = new ArrayList<>(TICK_RULES);
                rules.add(new DriveBluetoothRule());
                rules.add(new ProjectObjectRule(app));
                rules.add(new LifePatternSoonRule(app));
                rules.add(new LifePatternRule(app));
                rules.add(new CalendarSoonRule(app));
            }
            for (IntentionRule rule : rules) {
                IntentionCandidate c = rule.evaluate(snap);
                if (c == null) continue;
                if (IntentionPolicy.canFire(app, c)) {
                    picked = c;
                    break;
                }
            }
            // Toujours mémoriser l'état pour les edges suivants
            if (snap.batteryPercent >= 0) {
                IntentionPrefs.setLastSeenBatteryPercent(app, snap.batteryPercent);
            }
            if (snap.ssid != null) {
                IntentionPrefs.setLastSeenSsid(app, snap.ssid);
            }
            IntentionPrefs.setLastSeenCarBtConnected(app, snap.carBtConnected);
            if (picked != null) {
                IntentionPrefs.markFired(app, picked.id);
                IntentionNotifier.show(app, picked);
            }
        } catch (Exception e) {
            Log.w(TAG, "evaluate", e);
        }
        if (!briefReadyEvent) {
            try {
                com.pegasuscorp.orbe.learning.LearningEngine.maybeRunDetectors(app);
            } catch (Exception ignored) {}
        }
    }

    public static ContextSnapshot buildSnapshot(Context ctx, boolean briefReadyEvent) {
        BatteryReading bat = readBattery(ctx);
        String ssid = readSsid(ctx);
        boolean carBt = IntentionPrefs.isCarBtConnected(ctx);
        return new ContextSnapshot(
                bat.percent,
                bat.charging,
                ssid,
                IntentionPrefs.getLastSeenBatteryPercent(ctx),
                IntentionPrefs.getLastSeenSsid(ctx),
                IntentionPrefs.getWorkWifiSsid(ctx),
                carBt,
                IntentionPrefs.getLastSeenCarBtConnected(ctx),
                briefReadyEvent,
                System.currentTimeMillis()
        );
    }

    static BatteryReading readBattery(Context ctx) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = ctx.registerReceiver(null, filter);
        if (sticky == null) return new BatteryReading(-1, false);
        int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
        int status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        return new BatteryReading(pct, charging);
    }

    /** SSID courant (vide si unknown / indispo). Public pour learning Wi‑Fi. */
    public static String readCurrentSsid(Context ctx) {
        return readSsid(ctx);
    }

    static String readSsid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return "";
            String ssid = info.getSSID();
            if (ssid == null) return "";
            ssid = ssid.replace("\"", "").trim();
            if (WorkWifiRuleSafe.isUnknown(ssid)) return "";
            return ssid;
        } catch (Exception e) {
            return "";
        }
    }

    /** Évite dépendance package rules depuis static helper package. */
    private static final class WorkWifiRuleSafe {
        static boolean isUnknown(String s) {
            return WorkWifiRule.isUnknown(s);
        }
    }

    static final class BatteryReading {
        final int percent;
        final boolean charging;

        BatteryReading(int percent, boolean charging) {
            this.percent = percent;
            this.charging = charging;
        }
    }
}
