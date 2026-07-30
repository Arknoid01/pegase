package com.pegasuscorp.orbe.bureau;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Brouillon d'interview : {@code files/bureau/planning-drafts/{id}.json}.
 */
public final class BureauPlanningDraftStore {

    private static final String TAG = "BureauPlanningDraft";

    public static final class Draft {
        public String id = "";
        public String titleHint = "";
        public String phase = "NEED_INFO"; // NEED_INFO | READY
        public String summary = "";
        public final List<BureauChatStore.Turn> turns = new ArrayList<>();
        public long createdAt;
        public long updatedAt;
    }

    private BureauPlanningDraftStore() {}

    public static File dir(Context ctx) {
        File d = new File(BureauSessionStore.dir(ctx), "planning-drafts");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static Draft create(Context ctx, String titleHint) {
        Draft d = new Draft();
        d.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        d.titleHint = titleHint == null ? "" : titleHint.trim();
        d.phase = "NEED_INFO";
        long now = System.currentTimeMillis();
        d.createdAt = now;
        d.updatedAt = now;
        save(ctx, d);
        return d;
    }

    public static synchronized boolean save(Context ctx, Draft draft) {
        if (ctx == null || draft == null || draft.id == null || draft.id.isEmpty()) return false;
        try {
            JSONObject o = new JSONObject();
            o.put("id", draft.id);
            o.put("titleHint", draft.titleHint == null ? "" : draft.titleHint);
            o.put("phase", draft.phase == null ? "NEED_INFO" : draft.phase);
            o.put("summary", draft.summary == null ? "" : draft.summary);
            o.put("createdAt", draft.createdAt);
            o.put("updatedAt", System.currentTimeMillis());
            JSONArray turns = new JSONArray();
            for (BureauChatStore.Turn t : draft.turns) {
                if (t == null) continue;
                JSONObject x = new JSONObject();
                x.put("fromUser", t.fromUser);
                x.put("text", t.text == null ? "" : t.text);
                x.put("atMs", t.atMs);
                turns.put(x);
            }
            o.put("turns", turns);
            File target = new File(dir(ctx), draft.id + ".json");
            File tmp = new File(dir(ctx), draft.id + ".json.tmp");
            byte[] bytes = o.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(bytes);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "save", e);
            return false;
        }
    }

    public static synchronized Draft load(Context ctx, String id) {
        if (ctx == null || id == null) return null;
        File f = new File(dir(ctx), id + ".json");
        if (!f.isFile()) return null;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            Draft d = new Draft();
            d.id = o.optString("id", id);
            d.titleHint = o.optString("titleHint", "");
            d.phase = o.optString("phase", "NEED_INFO");
            d.summary = o.optString("summary", "");
            d.createdAt = o.optLong("createdAt", 0);
            d.updatedAt = o.optLong("updatedAt", 0);
            JSONArray turns = o.optJSONArray("turns");
            if (turns != null) {
                for (int i = 0; i < turns.length(); i++) {
                    JSONObject x = turns.optJSONObject(i);
                    if (x == null) continue;
                    d.turns.add(new BureauChatStore.Turn(
                            x.optBoolean("fromUser", false),
                            x.optString("text", ""),
                            x.optLong("atMs", 0)));
                }
            }
            return d;
        } catch (Exception e) {
            Log.w(TAG, "load", e);
            return null;
        }
    }

    public static synchronized boolean delete(Context ctx, String id) {
        if (ctx == null || id == null) return false;
        File f = new File(dir(ctx), id + ".json");
        return !f.exists() || f.delete();
    }

    public static void appendTurn(Context ctx, Draft draft, boolean fromUser, String text) {
        if (draft == null) return;
        draft.turns.add(new BureauChatStore.Turn(fromUser, text, System.currentTimeMillis()));
        draft.updatedAt = System.currentTimeMillis();
        save(ctx, draft);
    }
}
