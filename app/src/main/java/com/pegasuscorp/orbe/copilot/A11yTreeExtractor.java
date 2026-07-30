package com.pegasuscorp.orbe.copilot;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Extrait texte + position depuis l'arbre d'accessibilité (prioritaire sur l'OCR).
 * Snapshot JSON partagé entre processus via filesDir.
 */
public final class A11yTreeExtractor {

    private static final int MAX_NODES = 120;
    private static final int MAX_TEXT_LEN = 500;

    private A11yTreeExtractor() {}

    public static void writeSnapshot(android.content.Context ctx,
            AccessibilityNodeInfo root, String packageName) {
        if (ctx == null || root == null) return;
        try {
            JSONObject doc = new JSONObject();
            doc.put("package", packageName != null ? packageName : "");
            doc.put("ts", System.currentTimeMillis());
            JSONArray nodes = new JSONArray();
            collectNodes(root, nodes, 0);
            doc.put("nodes", nodes);
            File dir = snapshotDir(ctx);
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "a11y_snapshot.json");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(doc.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    public static String extractPlainText(android.content.Context ctx) {
        File f = new File(snapshotDir(ctx), "a11y_snapshot.json");
        if (!f.isFile()) return "";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 256_000)];
            int n = in.read(buf);
            if (n <= 0) return "";
            JSONObject doc = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            JSONArray nodes = doc.optJSONArray("nodes");
            if (nodes == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.optJSONObject(i);
                if (n == null) continue;
                String text = n.optString("text", "").trim();
                if (text.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(text);
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void collectNodes(AccessibilityNodeInfo root, JSONArray out, int depth) {
        if (root == null || out.length() >= MAX_NODES) return;
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(AccessibilityNodeInfo.obtain(root), depth));
        while (!queue.isEmpty() && out.length() < MAX_NODES) {
            NodeDepth item = queue.removeFirst();
            AccessibilityNodeInfo node = item.node;
            try {
                appendNode(node, out);
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(new NodeDepth(child, item.depth + 1));
                    }
                }
            } finally {
                node.recycle();
            }
        }
    }

    private static void appendNode(AccessibilityNodeInfo node, JSONArray out) throws Exception {
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String combined = "";
        if (text != null && text.length() > 0) combined = text.toString().trim();
        else if (desc != null && desc.length() > 0) combined = desc.toString().trim();
        if (combined.isEmpty()) return;
        if (combined.length() > MAX_TEXT_LEN) {
            combined = combined.substring(0, MAX_TEXT_LEN) + "…";
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        JSONObject o = new JSONObject();
        o.put("text", combined);
        o.put("class", node.getClassName() != null ? node.getClassName().toString() : "");
        o.put("clickable", node.isClickable());
        o.put("left", bounds.left);
        o.put("top", bounds.top);
        o.put("right", bounds.right);
        o.put("bottom", bounds.bottom);
        out.put(o);
    }

    private static File snapshotDir(android.content.Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "copilot");
    }

    private static final class NodeDepth {
        final AccessibilityNodeInfo node;
        final int depth;

        NodeDepth(AccessibilityNodeInfo node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }
}
