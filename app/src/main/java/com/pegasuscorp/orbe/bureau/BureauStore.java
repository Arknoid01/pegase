package com.pegasuscorp.orbe.bureau;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persistance locale du bureau. Écriture hors du thread UI. */
public final class BureauStore {

    private static final String FILE_NAME = "bureau_session.json";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private BureauStore() {}

    public static final class Session {
        public BureauCanvasView.Snapshot snapshot = new BureauCanvasView.Snapshot();
        public final Set<String> processedExprKeys = new HashSet<>();
    }

    /** Appelable depuis le thread UI : la sérialisation part sur un worker. */
    public static void saveAsync(Context context, Session session) {
        if (context == null || session == null) return;
        Context app = context.getApplicationContext();
        IO.execute(() -> save(app, session));
    }

    public static synchronized void save(Context context, Session session) {
        if (context == null || session == null || session.snapshot == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("canvasW", session.snapshot.canvasW);
            root.put("canvasH", session.snapshot.canvasH);
            root.put("userStrokes", strokesToJson(session.snapshot.userStrokes));
            root.put("pegaseItems", pegaseToJson(session.snapshot.pegaseItems));
            JSONArray keys = new JSONArray();
            for (String k : session.processedExprKeys) keys.put(k);
            root.put("processedExprKeys", keys);

            File f = file(context);
            File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {   // écriture atomique : pas de fichier à moitié écrit
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized Session load(Context context) {
        Session session = new Session();
        File f = file(context);
        if (!f.exists()) return session;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            BureauCanvasView.Snapshot snap = new BureauCanvasView.Snapshot();
            snap.canvasW = (float) root.optDouble("canvasW", 0);
            snap.canvasH = (float) root.optDouble("canvasH", 0);
            snap.userStrokes = jsonToStrokes(root.optJSONArray("userStrokes"));
            snap.pegaseItems = jsonToPegase(root.optJSONArray("pegaseItems"));
            session.snapshot = snap;

            JSONArray keys = root.optJSONArray("processedExprKeys");
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    session.processedExprKeys.add(keys.optString(i, ""));
                }
            }
            session.processedExprKeys.remove("");
        } catch (Exception ignored) {
        }
        return session;
    }

    public static synchronized void clear(Context context) {
        File f = file(context);
        if (f.exists()) f.delete();
    }

    private static File file(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "bureau");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE_NAME);
    }

    private static JSONArray strokesToJson(List<BureauCanvasView.Stroke> strokes) throws Exception {
        JSONArray arr = new JSONArray();
        if (strokes == null) return arr;
        for (BureauCanvasView.Stroke stroke : strokes) {
            JSONObject o = new JSONObject();
            o.put("color", stroke.color);
            o.put("width", stroke.width);
            o.put("processed", stroke.processed);
            JSONArray pts = new JSONArray();
            for (BureauCanvasView.Point p : stroke.points) {
                // format compact [x,y] : 2× plus léger que {"x":..,"y":..}
                JSONArray pt = new JSONArray();
                pt.put(round(p.x));
                pt.put(round(p.y));
                pts.put(pt);
            }
            o.put("points", pts);
            arr.put(o);
        }
        return arr;
    }

    private static List<BureauCanvasView.Stroke> jsonToStrokes(JSONArray arr) {
        List<BureauCanvasView.Stroke> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            BureauCanvasView.Stroke stroke = new BureauCanvasView.Stroke();
            stroke.color = o.optInt("color", stroke.color);
            stroke.width = (float) o.optDouble("width", stroke.width);
            stroke.processed = o.optBoolean("processed", false);
            JSONArray pts = o.optJSONArray("points");
            if (pts != null) {
                for (int j = 0; j < pts.length(); j++) {
                    JSONArray pt = pts.optJSONArray(j);
                    if (pt != null && pt.length() >= 2) {
                        stroke.points.add(new BureauCanvasView.Point(
                                (float) pt.optDouble(0), (float) pt.optDouble(1)));
                    } else {
                        // compat ancien format {"x":..,"y":..}
                        JSONObject old = pts.optJSONObject(j);
                        if (old != null) {
                            stroke.points.add(new BureauCanvasView.Point(
                                    (float) old.optDouble("x"), (float) old.optDouble("y")));
                        }
                    }
                }
            }
            if (stroke.points.size() >= 2) out.add(stroke);
        }
        return out;
    }

    private static JSONArray pegaseToJson(List<BureauCanvasView.PegaseItem> items) throws Exception {
        JSONArray arr = new JSONArray();
        if (items == null) return arr;
        for (BureauCanvasView.PegaseItem item : items) {
            JSONObject o = new JSONObject();
            o.put("id", item.id);
            o.put("kind", item.kind == null ? BureauCanvasView.KIND_TEXT : item.kind);
            o.put("text", item.text);
            o.put("x", round(item.x));
            o.put("y", round(item.y));
            o.put("w", round(item.w));
            o.put("h", round(item.h));
            o.put("size", round(item.size));
            arr.put(o);
        }
        return arr;
    }

    private static List<BureauCanvasView.PegaseItem> jsonToPegase(JSONArray arr) {
        List<BureauCanvasView.PegaseItem> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            // on restaure TOUS les types : texte, calcul ET encadrés
            out.add(new BureauCanvasView.PegaseItem(
                    o.optString("id", ""),
                    o.optString("kind", BureauCanvasView.KIND_TEXT),
                    o.optString("text", ""),
                    (float) o.optDouble("x", 80),
                    (float) o.optDouble("y", 120),
                    (float) o.optDouble("w", 0),
                    (float) o.optDouble("h", 0),
                    (float) o.optDouble("size", 44)));
        }
        return out;
    }

    private static double round(float v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
