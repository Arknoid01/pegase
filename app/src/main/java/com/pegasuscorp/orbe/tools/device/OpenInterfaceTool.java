package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;

import org.json.JSONObject;

/**
 * Ouvre l'interface Pégase (conversation, outils, fichiers).
 */
public final class OpenInterfaceTool implements Tool {

    @Override public String id() { return "open_interface"; }

    @Override public ToolTag tag() { return ToolTag.OPEN_INTERFACE; }

    @Override
    public String description() {
        return "open_interface() — Ouvre l'interface Pégase (discussion, outils, fichiers)";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        if (com.pegasuscorp.orbe.PegaseInterfaceState.isOpen()) {
            com.pegasuscorp.orbe.PegaseInterfaceState.openOrBringToFront(ctx);
            cb.onSuccessAndExit(ToolResult.text(""));
            return;
        }
        com.pegasuscorp.orbe.PegaseInterfaceState.openOrBringToFront(ctx);
        cb.onSuccessAndExit(ToolResult.text(""));
    }
}
