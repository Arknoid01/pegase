package com.pegasuscorp.orbe.copilot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Nœud texte + bounds lu depuis le snapshot a11y partagé. */
public final class A11ySnapshot {

    public static final class Node {
        public final String text;
        public final String viewId;
        public final String className;
        public final boolean clickable;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Node(String text, int left, int top, int right, int bottom) {
            this(text, "", "", false, left, top, right, bottom);
        }

        public Node(String text, boolean clickable, int left, int top, int right, int bottom) {
            this(text, "", "", clickable, left, top, right, bottom);
        }

        public Node(String text, String viewId, String className, boolean clickable,
                int left, int top, int right, int bottom) {
            this.text = text != null ? text : "";
            this.viewId = viewId != null ? viewId : "";
            this.className = className != null ? className : "";
            this.clickable = clickable;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() {
            return Math.max(0, right - left);
        }

        public int height() {
            return Math.max(0, bottom - top);
        }

        public boolean hasBounds() {
            return width() > 0 && height() > 0;
        }
    }

    private A11ySnapshot() {}

    public static List<Node> loadNodes(android.content.Context ctx) {
        List<Node> out = new ArrayList<>();
        File f = new File(ctx.getApplicationContext().getFilesDir(), "copilot/a11y_snapshot.json");
        if (!f.isFile()) return out;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 512_000)];
            int n = in.read(buf);
            if (n <= 0) return out;
            JSONObject doc = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            JSONArray nodes = doc.optJSONArray("nodes");
            if (nodes == null) return out;
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject o = nodes.optJSONObject(i);
                if (o == null) continue;
                String text = o.optString("text", "").trim();
                String viewId = o.optString("viewId", "");
                if (text.isEmpty() && viewId.isEmpty()) continue;
                out.add(new Node(
                        text,
                        o.optString("viewId", ""),
                        o.optString("class", ""),
                        o.optBoolean("clickable", false),
                        o.optInt("left", 0),
                        o.optInt("top", 0),
                        o.optInt("right", 0),
                        o.optInt("bottom", 0)));
            }
        } catch (Exception ignored) {}
        return out;
    }
}
