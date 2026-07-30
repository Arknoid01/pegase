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
            doc.put("source", "a11y");
            JSONArray nodes = new JSONArray();
            collectNodes(root, nodes, 0);
            doc.put("nodes", nodes);
            writeDoc(ctx, doc);
        } catch (Exception ignored) {}
    }

    /** Fusionne des blocs OCR dans le snapshot quand l'arbre a11y est vide. */
    public static void mergeOcrBlocks(android.content.Context ctx, String packageName,
            java.util.List<ScreenTextExtractor.TextBlock> blocks) {
        if (ctx == null || blocks == null || blocks.isEmpty()) return;
        try {
            JSONObject doc = new JSONObject();
            doc.put("package", packageName != null ? packageName : "");
            doc.put("ts", System.currentTimeMillis());
            doc.put("source", "ocr");
            JSONArray nodes = new JSONArray();
            for (ScreenTextExtractor.TextBlock block : blocks) {
                if (block == null || block.text.isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("text", block.text.length() > MAX_TEXT_LEN
                        ? block.text.substring(0, MAX_TEXT_LEN) + "…" : block.text);
                o.put("class", "ocr");
                o.put("clickable", false);
                o.put("left", block.left);
                o.put("top", block.top);
                o.put("right", block.right);
                o.put("bottom", block.bottom);
                nodes.put(o);
                if (nodes.length() >= MAX_NODES) break;
            }
            doc.put("nodes", nodes);
            writeDoc(ctx, doc);
        } catch (Exception ignored) {}
    }

    private static void writeDoc(android.content.Context ctx, JSONObject doc) throws Exception {
        File dir = snapshotDir(ctx);
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "a11y_snapshot.json");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(doc.toString().getBytes(StandardCharsets.UTF_8));
        }
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
                JSONObject node = nodes.optJSONObject(i);
                if (node == null) continue;
                String text = node.optString("text", "").trim();
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
                try {
                    appendNode(node, out);
                } catch (Exception ignored) {
                }
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
