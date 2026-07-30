package com.pegasuscorp.orbe.iface;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.pegasuscorp.orbe.notepad.NotepadDateHelper;
import com.pegasuscorp.orbe.notepad.NotepadStore;
import com.pegasuscorp.orbe.ui.PegaseSheets;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.pegasuscorp.orbe.iface.IfaceUi.C_CYAN;
import static com.pegasuscorp.orbe.iface.IfaceUi.C_MUTED;
import static com.pegasuscorp.orbe.iface.IfaceUi.attachSwipeLeft;
import static com.pegasuscorp.orbe.iface.IfaceUi.cardContainer;
import static com.pegasuscorp.orbe.iface.IfaceUi.dp;
import static com.pegasuscorp.orbe.iface.IfaceUi.makeIconButton;
import static com.pegasuscorp.orbe.iface.IfaceUi.matchWrap;
import static com.pegasuscorp.orbe.iface.IfaceUi.matchWeight;
import static com.pegasuscorp.orbe.iface.IfaceUi.padded;
import static com.pegasuscorp.orbe.iface.IfaceUi.showBottomSheet;

/** Onglet Bloc-notes. */
public class NotepadFragment extends Fragment {

    private static final long REFRESH_MS = 1500;

    private PegaseInterfaceHost host;
    private LinearLayout notepadListHost;
    private String lastNotepadSignature = "";
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (!isResumed()) return;
            refreshNotepadIfNeeded();
            refreshHandler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof PegaseInterfaceHost)) {
            throw new IllegalStateException("Host must implement PegaseInterfaceHost");
        }
        host = (PegaseInterfaceHost) context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(ctx, 8));
        root.addView(header, matchWrap());

        TextView title = new TextView(ctx);
        title.setText("📝 Bloc-notes");
        title.setTextColor(Color.parseColor(C_CYAN));
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button add = makeIconButton(ctx, "+");
        add.setOnClickListener(v -> showAddNotepadItem());
        header.addView(add);

        Button menu = makeIconButton(ctx, "⋮");
        menu.setOnClickListener(v -> showNotepadMenu());
        header.addView(menu);

        ScrollView scroll = new ScrollView(ctx);
        root.addView(scroll, matchWeight());

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, matchWrap());
        notepadListHost = list;

        TextView spacer = new TextView(ctx);
        spacer.setVisibility(View.GONE);
        list.addView(spacer, matchWrap());

        NotepadStore store = NotepadStore.getInstance(ctx);
        List<NotepadStore.Item> items = store.getActiveItems();
        lastNotepadSignature = notepadSignature(items);
        populateNotepadItems(list, store, items);

        TextView tomorrow = new TextView(ctx);
        tomorrow.setText("Voir demain →");
        tomorrow.setTextColor(Color.parseColor(C_MUTED));
        tomorrow.setTextSize(12);
        tomorrow.setPadding(0, dp(ctx, 12), 0, dp(ctx, 4));
        tomorrow.setOnClickListener(v -> showNotepadDayFilter(
                NotepadDateHelper.tomorrow(), "demain"));
        root.addView(tomorrow, matchWrap());

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshHandler.removeCallbacks(refreshTick);
        refreshHandler.post(refreshTick);
    }

    @Override
    public void onPause() {
        refreshHandler.removeCallbacks(refreshTick);
        super.onPause();
    }

    @Override
    public void onDetach() {
        host = null;
        super.onDetach();
    }

    private void refreshNotepadIfNeeded() {
        if (notepadListHost == null || getContext() == null) return;
        NotepadStore store = NotepadStore.getInstance(requireContext());
        List<NotepadStore.Item> items = store.getActiveItems();
        String signature = notepadSignature(items);
        if (signature.equals(lastNotepadSignature)) return;
        lastNotepadSignature = signature;
        populateNotepadItems(notepadListHost, store, items);
    }

    private static String notepadSignature(List<NotepadStore.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (NotepadStore.Item item : items) {
            sb.append(item.id).append('|')
                    .append(item.text).append('|')
                    .append(item.dueDate).append('|')
                    .append(item.priority).append('|')
                    .append(item.done).append(';');
        }
        return sb.toString();
    }

    private void populateNotepadItems(LinearLayout list, NotepadStore store,
                                      List<NotepadStore.Item> items) {
        Context ctx = requireContext();
        while (list.getChildCount() > 1) {
            list.removeViewAt(1);
        }
        if (items.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("Liste vide.\nDis « ajoute à ma liste… » ou appuie sur +.");
            empty.setTextColor(Color.parseColor(C_MUTED));
            empty.setTextSize(14);
            list.addView(empty, matchWrap());
            return;
        }
        items.sort((a, b) -> Integer.compare(b.priority, a.priority));
        for (NotepadStore.Item item : items) {
            list.addView(makeNotepadCard(store, item), matchWrap());
        }
    }

    private void showAddNotepadItem() {
        Context ctx = requireContext();
        EditText field = new EditText(ctx);
        field.setHint("Nouvelle chose à faire…");
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.parseColor(C_MUTED));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(ctx)
                .setTitle("Ajouter")
                .setView(padded(ctx, field))
                .setPositiveButton("OK", (d, w) -> {
                    String t = field.getText() != null
                            ? field.getText().toString().trim() : "";
                    if (t.isEmpty()) return;
                    NotepadStore.getInstance(ctx).add(t);
                    lastNotepadSignature = "";
                    refreshNotepadIfNeeded();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showNotepadMenu() {
        showBottomSheet(requireContext(), "Bloc-notes", new String[]{
                "Actualiser",
                "Tout effacer",
                "Voir demain"
        }, which -> {
            if (which == 0) {
                lastNotepadSignature = "";
                refreshNotepadIfNeeded();
            } else if (which == 1) {
                new AlertDialog.Builder(requireContext())
                        .setTitle("Tout effacer ?")
                        .setMessage("Supprime tous les éléments actifs.")
                        .setPositiveButton("Effacer", (d, w) -> {
                            NotepadStore.getInstance(requireContext()).clearActive();
                            lastNotepadSignature = "";
                            refreshNotepadIfNeeded();
                        })
                        .setNegativeButton("Annuler", null)
                        .show();
            } else if (which == 2) {
                showNotepadDayFilter(NotepadDateHelper.tomorrow(), "demain");
            }
        });
    }

    private void showNotepadDayFilter(String date, String label) {
        List<NotepadStore.Item> items =
                NotepadStore.getInstance(requireContext()).getActiveForDate(date);
        StringBuilder msg = new StringBuilder();
        if (items.isEmpty()) {
            msg.append("Rien de prévu ").append(label).append(".");
        } else {
            msg.append("Pour ").append(label).append(" :\n\n");
            for (int i = 0; i < items.size(); i++) {
                NotepadStore.Item item = items.get(i);
                if (i > 0) msg.append("\n");
                if (item.priority > 0) {
                    msg.append("(").append(NotepadDateHelper.priorityLabel(item.priority))
                            .append(") ");
                }
                msg.append("• ").append(item.text);
            }
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Bloc-notes — " + label)
                .setMessage(msg.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private View makeNotepadCard(NotepadStore store, NotepadStore.Item item) {
        Context ctx = requireContext();
        LinearLayout card = cardContainer(ctx);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row, matchWrap());

        TextView bullet = new TextView(ctx);
        bullet.setText("○  ");
        bullet.setTextColor(Color.parseColor(C_CYAN));
        bullet.setTextSize(16);
        row.addView(bullet);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        row.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView text = new TextView(ctx);
        text.setText(item.text);
        text.setTextColor(Color.WHITE);
        text.setTextSize(14);
        body.addView(text, matchWrap());

        StringBuilder meta = new StringBuilder();
        if (item.dueDate != null && !item.dueDate.isEmpty()) {
            meta.append(NotepadDateHelper.formatDateLabel(item.dueDate));
        }
        if (item.priority > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(NotepadDateHelper.priorityLabel(item.priority));
        }
        if (item.reminderAt > System.currentTimeMillis()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append("rappel ").append(new SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH)
                    .format(new Date(item.reminderAt)));
        }
        if (meta.length() > 0) {
            TextView metaTv = new TextView(ctx);
            metaTv.setText(meta.toString());
            metaTv.setTextColor(Color.parseColor(C_MUTED));
            metaTv.setTextSize(11);
            metaTv.setPadding(0, dp(ctx, 2), 0, 0);
            body.addView(metaTv, matchWrap());
        }

        Runnable markDone = () -> {
            if (store.markDoneById(item.id)) {
                PegaseSheets.hapticConfirm(card);
                Toast.makeText(ctx, "Fait ✓", Toast.LENGTH_SHORT).show();
                lastNotepadSignature = "";
                refreshNotepadIfNeeded();
            }
        };
        Runnable delete = () -> {
            if (store.removeById(item.id)) {
                Toast.makeText(ctx, "Supprimé", Toast.LENGTH_SHORT).show();
                lastNotepadSignature = "";
                refreshNotepadIfNeeded();
            }
        };

        card.setOnClickListener(v -> markDone.run());
        attachSwipeLeft(ctx, card, delete);
        card.setOnLongClickListener(v -> {
            showBottomSheet(ctx, item.text, new String[]{"Cocher fait", "Supprimer"}, which -> {
                if (which == 0) markDone.run();
                else if (which == 1) delete.run();
            });
            return true;
        });

        return card;
    }
}
