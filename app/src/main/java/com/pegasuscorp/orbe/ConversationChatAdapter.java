package com.pegasuscorp.orbe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

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

    /** Vrai si le dernier élément est la réponse de Pégase en cours d'écriture. */
    private boolean liveLast = false;

    public void submit(List<PegaseInterfaceData.ChatMessageUi> messages) {
        submit(messages, false);
    }

    /** @param live le dernier message est la réponse en cours (mise à jour légère possible). */
    public void submit(List<PegaseInterfaceData.ChatMessageUi> messages, boolean live) {
        items.clear();
        expanded.clear();
        if (messages != null) items.addAll(messages);
        liveLast = live && !items.isEmpty();
        notifyDataSetChanged();
    }

    /**
     * Mise à jour légère du texte en cours d'écriture : ne remet à jour que ce seul message,
     * au lieu de reconstruire toute la liste à chaque morceau. False si ce n'est pas possible.
     */
    public boolean updateLive(String text) {
        int last = items.size() - 1;
        if (!liveLast || last < 0) return false;
        PegaseInterfaceData.ChatMessageUi old = items.get(last);
        items.set(last, new PegaseInterfaceData.ChatMessageUi(false, old.speaker,
                text == null ? "" : text, old.timeLabel));
        notifyItemChanged(last);
        return true;
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
            PegaseHolder ph = (PegaseHolder) holder;
            ph.setLive(liveLast && position == items.size() - 1);
            ph.bind(msg, position, expanded.contains(position),
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

    /** Copie un texte dans le presse-papiers avec un retour visuel. */
    static void copyToClipboard(Context ctx, String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("Pégase", text));
        Toast.makeText(ctx, "Copié", Toast.LENGTH_SHORT).show();
    }

    /**
     * Résumé d'une ligne des outils d'un tour : « 🔧 météo ✅ · agenda ✅ · 2,1 s ».
     * Vide s'il n'y a pas d'outil.
     */
    /** « 📱 local · Qwen3.5-2B » ou « ☁️ Groq » — d'où vient la réponse. */
    static String sourceLabel(ReasoningCard card) {
        if (card == null || card.backend == null || card.backend.isEmpty()) return "";
        String b = card.backend;
        if (b.startsWith("Local/")) return "📱 local · " + b.substring(6);
        int slash = b.indexOf('/');
        return "☁️ " + (slash > 0 ? b.substring(0, slash) : b);
    }

    static String toolsSummary(ReasoningCard card) {
        String source = sourceLabel(card);
        String tools = toolsOnlySummary(card);
        if (source.isEmpty()) return tools;
        return tools.isEmpty() ? source : source + " · " + tools;
    }

    static String toolsOnlySummary(ReasoningCard card) {
        if (card == null || card.toolsUsed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("🔧 ");
        long total = 0;
        int shown = 0;
        for (ReasoningCard.ToolStep step : card.toolsUsed) {
            total += step.latencyMs;
            if (shown++ >= 4) continue;
            if (shown > 1) sb.append(" · ");
            sb.append(step.name).append(step.ok ? " ✅" : " ❌");
        }
        if (card.toolsUsed.size() > 4) sb.append(" · +").append(card.toolsUsed.size() - 4);
        if (total > 0) {
            sb.append(" · ").append(String.format(java.util.Locale.FRANCE, "%.1f s", total / 1000.0));
        }
        return sb.toString();
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
            body.setOnLongClickListener(v -> {
                copyToClipboard(v.getContext(), msg.text);
                return true;
            });
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
        final TextView toolsSummary;
        final TextView copy;

        PegaseHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.message_name);
            body = itemView.findViewById(R.id.message_body);
            time = itemView.findViewById(R.id.message_time);
            warn = itemView.findViewById(R.id.reasoning_warn);
            toggle = itemView.findViewById(R.id.reasoning_toggle);
            panel = itemView.findViewById(R.id.reasoning_panel);
            toolsSummary = itemView.findViewById(R.id.tools_summary);
            copy = itemView.findViewById(R.id.message_copy);
        }

        /** Texte réellement affiché. Pendant la génération (live), seuls les mots entiers sont montrés. */
        private String shownFull = "";
        private int shownLen = 0;
        private float shownF = 0f;
        private boolean live = false;
        /** Réception des morceaux : {instant (ms), longueur utile}. Sert au décalage. */
        private final ArrayList<long[]> arrivals = new ArrayList<>();
        private static final int REVEAL_FRAME_MS = 16;
        private static final long LIVE_DELAY_MS = 500L;

        void setLive(boolean isLive) { live = isLive; }

        /** Longueur déjà « mature » : arrivée il y a au moins LIVE_DELAY_MS. */
        private int matureLength(long now) {
            int len = 0;
            for (long[] a : arrivals) {
                if (a[0] <= now - LIVE_DELAY_MS) len = (int) a[1];
                else break;
            }
            return len;
        }

        private final Runnable revealTick = new Runnable() {
            @Override public void run() {
                int target = live
                        ? Math.min(shownFull.length(), matureLength(System.currentTimeMillis()))
                        : shownFull.length();
                if (shownLen < target) {
                    float backlog = target - shownF;
                    // rythme doux : ~45 car./s minimum, rattrapage étalé
                    float step = Math.max(0.045f * REVEAL_FRAME_MS, backlog * REVEAL_FRAME_MS / 180f);
                    shownF = Math.min(target, shownF + step);
                    shownLen = (int) shownF;
                    body.setText(shownFull.substring(0, shownLen));
                    if (live) {
                        // suivre le texte qui grandit : garder le bas du message visible
                        body.post(() -> {
                            if (!(itemView.getParent() instanceof RecyclerView)) return;
                            RecyclerView rv = (RecyclerView) itemView.getParent();
                            int over = itemView.getBottom() - (rv.getHeight() - rv.getPaddingBottom());
                            if (over > 0) rv.scrollBy(0, over);
                        });
                    }
                }
                // on continue tant que du texte attend son tour (décalage ou rattrapage)
                if (shownLen < shownFull.length()) body.postDelayed(this, REVEAL_FRAME_MS);
            }
        };

        /** Coupe au dernier espace : un mot en cours d'écriture n'est pas montré. */
        private static String wholeWords(String s) {
            if (s.isEmpty() || Character.isWhitespace(s.charAt(s.length() - 1))) return s;
            int sp = -1;
            for (int i = s.length() - 1; i >= 0; i--) {
                if (Character.isWhitespace(s.charAt(i))) { sp = i; break; }
            }
            return sp < 0 ? "" : s.substring(0, sp + 1);
        }

        /**
         * Affiche le texte. En génération (live) : mots entiers, avec un décalage de 500 ms.
         * Hors génération (réponse finale, historique) : affichage immédiat.
         */
        void showText(String text) {
            String full = text == null ? "" : text;
            body.removeCallbacks(revealTick);
            if (!live) {
                arrivals.clear();
                shownFull = full;
                shownLen = full.length();
                shownF = shownLen;
                body.setText(full);
                return;
            }
            String cut = wholeWords(full);
            if (!cut.startsWith(shownFull)) {
                // nouveau message dans cette vue : repartir de zéro
                arrivals.clear();
                shownLen = 0;
                shownF = 0f;
                body.setText("");
            }
            shownFull = cut;
            long now = System.currentTimeMillis();
            if (arrivals.isEmpty() || cut.length() > arrivals.get(arrivals.size() - 1)[1]) {
                arrivals.add(new long[] {now, cut.length()});
            }
            body.post(revealTick);
        }

        void bind(PegaseInterfaceData.ChatMessageUi msg, int position, boolean isExpanded,
                Runnable onToggle) {
            name.setText(msg.speaker);
            showText(msg.text);
            time.setText(msg.timeLabel);
            time.setVisibility(msg.timeLabel == null || msg.timeLabel.isEmpty()
                    ? View.GONE : View.VISIBLE);

            // Re-résoudre au bind : la carte peut arriver juste après le 1er refresh UI.
            ReasoningCard card = msg.reasoning;
            if (card == null && msg.text != null && !msg.text.isEmpty()) {
                card = ReasoningStore.findForReply(msg.text);
            }
            copy.setOnClickListener(v -> copyToClipboard(v.getContext(), msg.text));
            body.setOnLongClickListener(v -> {
                copyToClipboard(v.getContext(), msg.text);
                return true;
            });
            String summary = toolsSummary(card);
            toolsSummary.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
            toolsSummary.setText(summary);
            toolsSummary.setOnClickListener(v -> onToggle.run());
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
