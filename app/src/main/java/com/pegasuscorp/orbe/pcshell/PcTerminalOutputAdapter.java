package com.pegasuscorp.orbe.pcshell;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.method.LinkMovementMethod;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Un TextView par bloc de scrollback — scroll vertical continu virtualisé.
 */
public final class PcTerminalOutputAdapter extends RecyclerView.Adapter<PcTerminalOutputAdapter.ChunkHolder> {

    private static final int C_OUT = Color.parseColor("#E8E8E8");

    private final PcTerminalScrollback scrollback;

    public PcTerminalOutputAdapter(PcTerminalScrollback scrollback) {
        this.scrollback = scrollback;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getItemCount() {
        return scrollback.getChunkCount();
    }

    @NonNull
    @Override
    public ChunkHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView tv = new TextView(parent.getContext());
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tv.setTextColor(C_OUT);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setIncludeFontPadding(false);
        tv.setPadding(0, 0, 0, 0);
        return new ChunkHolder(tv);
    }

    @Override
    public void onBindViewHolder(@NonNull ChunkHolder holder, int position) {
        holder.text.setText(scrollback.getChunk(position));
    }

    static final class ChunkHolder extends RecyclerView.ViewHolder {
        final TextView text;

        ChunkHolder(TextView text) {
            super(text);
            this.text = text;
        }
    }
}
