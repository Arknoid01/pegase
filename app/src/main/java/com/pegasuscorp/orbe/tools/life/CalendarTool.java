package com.pegasuscorp.orbe.tools.life;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;

import org.json.JSONObject;

/**
 * Alias historique {@code calendar} → délègue à {@link AgendaTool}.
 */
public final class CalendarTool implements Tool {

    private final AgendaTool delegate = new AgendaTool();

    @Override
    public String id() {
        return "calendar";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.AGENDA;
    }

    @Override
    public String description() {
        return "calendar(...) — alias de agenda(...). Préférer tool agenda.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        // Adapter date+time → start si besoin (AgendaTool le gère aussi)
        delegate.execute(ctx, params != null ? params : new JSONObject(), cb);
    }
}
