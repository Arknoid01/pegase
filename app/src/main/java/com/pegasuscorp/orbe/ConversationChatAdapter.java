package com.pegasuscorp.orbe;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.pegasuscorp.orbe.diag.ReasoningCard;
import com.pegasuscorp.orbe.diag.ReasoningStore;
import com.pegasuscorp.orbe.tools.PegaseInterfaceData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Messages Discussion — deux layouts plats (Yannick / Pégase), sans bulles.
 * Le raisonnement en temps réel (outils / « réfléchit ») est porté par
 * {@link com.pegasuscorp.orbe.ui.ThinkingView} au-dessus du champ de saisie,
 * pas dans les bulles. Le détail post-réponse reste via 🔍 / {@link ReasoningCard}.
 */
public final class ConversationChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_PEGASE = 1;

    private final List<PegaseInterfaceData.ChatMessageUi> items = new ArrayList<>();
    private final Set<Integer> expanded = new HashSet<>();

    public void submit(List<PegaseInterfaceData.ChatMessageUi> messages) {
        items.clear();
        expanded.clear();
        if (messages != null) items.addAll(messages);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).fromUser ? TYPE_USER : TYPE_PEGASE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_USER) {
            return new UserHolder(inf.inflate(R.layout.item_message_user, parent, false));
        }
        return new PegaseHolder(inf.inflate(R.layout.item_message_pegase, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        PegaseInterfaceData.ChatMessageUi msg = items.get(position);
        if (holder instanceof UserHolder) {
            ((UserHolder) holder).bind(msg);
        } else if (holder instanceof PegaseHolder) {
            ((PegaseHolder) holder).bind(msg, position, expanded.contains(position),
                    () -> {
                        int pos = holder.getBindingAdapterPosition();
                        if (pos == RecyclerView.NO_POSITION) return;
                        if (expanded.contains(pos)) {
                            expanded.remove(pos);
                        } else {
                            expanded.add(pos);
                        }
                        notifyItemChanged(pos);
                    });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class UserHolder extends RecyclerView.ViewHolder {
        final TextView body;
        final TextView time;

        UserHolder(View itemView) {
            super(itemView);
            body = itemView.findViewById(R.id.message_body);
            time = itemView.findViewById(R.id.message_time);
        }

        void bind(PegaseInterfaceData.ChatMessageUi msg) {
            body.setText(msg.text);
            time.setText(msg.timeLabel);
            time.setVisibility(msg.timeLabel == null || msg.timeLabel.isEmpty()
                    ? View.GONE : View.VISIBLE);
        }
    }

    static final class PegaseHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView body;
        final TextView time;
        final TextView warn;
        final TextView toggle;
        final TextView panel;

        PegaseHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.message_name);
            body = itemView.findViewById(R.id.message_body);
            time = itemView.findViewById(R.id.message_time);
            warn = itemView.findViewById(R.id.reasoning_warn);
            toggle = itemView.findViewById(R.id.reasoning_toggle);
            panel = itemView.findViewById(R.id.reasoning_panel);
        }

        void bind(PegaseInterfaceData.ChatMessageUi msg, int position, boolean isExpanded,
                Runnable onToggle) {
            name.setText(msg.speaker);
            body.setText(msg.text);
            time.setText(msg.timeLabel);
            time.setVisibility(msg.timeLabel == null || msg.timeLabel.isEmpty()
                    ? View.GONE : View.VISIBLE);

            // Re-résoudre au bind : la carte peut arriver juste après le 1er refresh UI.
            ReasoningCard card = msg.reasoning;
            if (card == null && msg.text != null && !msg.text.isEmpty()) {
                card = ReasoningStore.findForReply(msg.text);
            }
            boolean hallu = card != null && card.potentialHallucination;
            warn.setVisibility(hallu ? View.VISIBLE : View.GONE);
            toggle.setText(isExpanded ? "🔍 ▲" : "🔍");
            toggle.setOnClickListener(v -> onToggle.run());
            warn.setOnClickListener(v -> onToggle.run());

            if (isExpanded) {
                panel.setVisibility(View.VISIBLE);
                if (card != null) {
                    panel.setText(card.formatPanel());
                    panel.setTextColor(itemView.getResources().getColor(
                            hallu ? R.color.orbe_warn : R.color.orbe_text_muted, null));
                } else {
                    panel.setText("Pas encore de carte de raisonnement pour ce message.\n"
                            + "Les nouvelles réponses après mise à jour affichent ici "
                            + "le cheminement (outil ✅/❌, sources, LLM).");
                    panel.setTextColor(itemView.getResources().getColor(
                            R.color.orbe_text_muted, null));
                }
            } else {
                panel.setVisibility(View.GONE);
            }
        }
    }
}
