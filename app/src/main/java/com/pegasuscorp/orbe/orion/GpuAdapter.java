package com.pegasuscorp.orbe.orion;

import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Liste des GPU RunPod — corrige le recyclage des checkboxes :
 * détache le listener avant {@code setChecked}, rattache après ;
 * l'état vient toujours de {@link GpuOption#isAllowed}.
 */
public final class GpuAdapter extends RecyclerView.Adapter<GpuAdapter.Holder> {

    public interface Listener {
        void onAllowedChanged(GpuOption option, boolean allowed);
    }

    private final List<GpuOption> items = new ArrayList<>();
    private final Listener listener;

    public GpuAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<GpuOption> options) {
        items.clear();
        if (options != null) items.addAll(options);
        notifyDataSetChanged();
    }

    public List<GpuOption> getItems() {
        return new ArrayList<>(items);
    }

    public List<String> allowedIds() {
        List<String> out = new ArrayList<>();
        for (GpuOption o : items) {
            if (o != null && o.isAllowed && o.offer != null) {
                out.add(o.offer.id);
            }
        }
        return out;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        CheckBox cb = new CheckBox(parent.getContext());
        cb.setTextColor(Color.WHITE);
        cb.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Holder(cb);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        GpuOption option = items.get(position);
        CheckBox cb = holder.checkBox;

        // 1) Détacher — évite que setChecked ne fire l'ancien listener (vue recyclée)
        cb.setOnCheckedChangeListener(null);

        // 2) État visuel depuis le modèle uniquement
        cb.setText(option.label());
        cb.setChecked(option.isAllowed);
        holder.bound = option;

        // 3) Rattacher — met à jour le modèle, pas l'inverse
        cb.setOnCheckedChangeListener(holder.changeListener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    final class Holder extends RecyclerView.ViewHolder {
        final CheckBox checkBox;
        GpuOption bound;
        final CompoundButton.OnCheckedChangeListener changeListener =
                (buttonView, isChecked) -> {
                    if (bound == null) return;
                    bound.isAllowed = isChecked;
                    if (listener != null) {
                        listener.onAllowedChanged(bound, isChecked);
                    }
                };

        Holder(@NonNull CheckBox itemView) {
            super(itemView);
            this.checkBox = itemView;
        }
    }
}
