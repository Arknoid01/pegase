package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;



import android.content.Context;

import android.content.Intent;

import android.content.pm.PackageManager;

import android.content.pm.ResolveInfo;



import org.json.JSONObject;



import java.util.HashMap;

import java.util.Locale;

import java.util.Map;



/**

 * Ouvre une application installée par son nom (avec alias FR pour apps système).

 */

public final class OpenAppTool implements Tool {



    /** Nom demandé (FR) → libellés EN / système à matcher. */

    private static final Map<String, String[]> ALIASES = new HashMap<>();

    static {

        ALIASES.put("horloge", new String[]{"horloge", "clock", "alarm clock", "deskclock"});

        ALIASES.put("agenda", new String[]{"agenda", "calendar", "calendrier"});

        ALIASES.put("calendrier", new String[]{"calendrier", "calendar", "agenda"});

        ALIASES.put("clock", new String[]{"clock", "alarm clock", "horloge"});

        ALIASES.put("alarm clock", new String[]{"alarm clock", "clock", "horloge"});

        ALIASES.put("calendar", new String[]{"calendar", "agenda", "calendrier"});

    }



    @Override public String id() { return "open_app"; }



    @Override public ToolTag tag() { return ToolTag.OPEN_APP; }



    @Override

    public String description() {

        return "open_app(name:str) — Ouvre une application (ex: \"Spotify\", \"Chrome\", "

                + "\"WhatsApp\", \"Horloge\", \"Agenda\"). "

                + "Alias FR : Horloge = Clock / Alarm Clock ; Agenda = Calendar.";

    }



    @Override

    public void execute(Context ctx, JSONObject params, ToolCallback cb) {

        String name = params.optString("name", "").trim();

        if (name.isEmpty()) {

            cb.onError("Quelle application veux-tu ouvrir ?");

            return;

        }



        PackageManager pm = ctx.getPackageManager();

        Intent main = new Intent(Intent.ACTION_MAIN, null);

        main.addCategory(Intent.CATEGORY_LAUNCHER);



        String[] needles = resolveNeedles(name);

        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {

            String appName = ri.loadLabel(pm).toString();

            String appLower = appName.toLowerCase(Locale.ROOT);

            for (String needle : needles) {

                if (appLower.contains(needle)) {

                    Intent launch = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);

                    if (launch != null) {

                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                        ctx.startActivity(launch);

                        cb.onSuccessAndExit(ToolResult.text("J'ouvre " + appName + "."));

                        return;

                    }

                }

            }

        }

        cb.onError("Je n'ai pas trouvé l'application " + name + ".");

    }



    private static String[] resolveNeedles(String name) {

        String key = name.toLowerCase(Locale.ROOT).trim();

        String[] aliased = ALIASES.get(key);

        if (aliased != null) return aliased;

        return new String[]{key};

    }

}

