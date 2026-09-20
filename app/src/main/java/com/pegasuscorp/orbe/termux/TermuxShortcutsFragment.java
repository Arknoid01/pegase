package com.pegasuscorp.orbe.termux;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.util.List;

import static com.pegasuscorp.orbe.iface.IfaceUi.C_CYAN;
import static com.pegasuscorp.orbe.iface.IfaceUi.C_MUTED;
import static com.pegasuscorp.orbe.iface.IfaceUi.cardContainer;
import static com.pegasuscorp.orbe.iface.IfaceUi.dp;
import static com.pegasuscorp.orbe.iface.IfaceUi.matchWeight;
import static com.pegasuscorp.orbe.iface.IfaceUi.matchWrap;
import static com.pegasuscorp.orbe.iface.IfaceUi.primaryButton;

/** Onglet Raccourcis Termux — remplace l'ancien onglet Orion. */
public class TermuxShortcutsFragment extends Fragment {

    private LinearLayout listHost;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("Raccourcis Termux");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setTextSize(18f);
        header.addView(title, matchWeight());

        android.widget.Button addButton = primaryButton(ctx, "+ Ajouter");
        addButton.setOnClickListener(v -> showAddShortcut());
        header.addView(addButton);
        root.addView(header, matchWrap());

        TextView subtitle = new TextView(ctx);
        subtitle.setText("Lance une commande enregistrée dans le proot Ubuntu (comme le double-tap sur l'orbe).");
        subtitle.setTextColor(Color.parseColor(C_MUTED));
        subtitle.setTextSize(12f);
        subtitle.setPadding(0, dp(ctx, 4), 0, dp(ctx, 8));
        root.addView(subtitle, matchWrap());

        ScrollView scroll = new ScrollView(ctx);
        listHost = new LinearLayout(ctx);
        listHost.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listHost, matchWrap());
        root.addView(scroll, matchWeight());

        refreshList();
        return root;
    }

    private void refreshList() {
        if (listHost == null) return;
        listHost.removeAllViews();
        Context ctx = requireContext();
        List<TermuxShortcut> shortcuts = TermuxShortcutStore.list(ctx);
        if (shortcuts.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("Aucun raccourci — ajoute-en un avec « + Ajouter ».");
            empty.setTextColor(Color.parseColor(C_MUTED));
            empty.setTextSize(14);
            listHost.addView(empty, matchWrap());
            return;
        }
        for (int i = 0; i < shortcuts.size(); i++) {
            listHost.addView(makeShortcutCard(shortcuts.get(i), i), matchWrap());
        }
    }

    private View makeShortcutCard(TermuxShortcut shortcut, int index) {
        Context ctx = requireContext();
        LinearLayout card = cardContainer(ctx);

        TextView label = new TextView(ctx);
        label.setText(shortcut.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(15f);
        card.addView(label, matchWrap());

        TextView command = new TextView(ctx);
        command.setText(shortcut.command);
        command.setTextColor(Color.parseColor(C_CYAN));
        command.setTextSize(12f);
        command.setTypeface(Typeface.MONOSPACE);
        card.addView(command, matchWrap());

        card.setOnClickListener(v -> TermuxLauncher.runInProotUbuntu(ctx, shortcut.command));
        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(ctx, com.pegasuscorp.orbe.R.style.Theme_Orbe_DarkDialog)
                    .setTitle("Supprimer « " + shortcut.label + " » ?")
                    .setPositiveButton("Supprimer", (d, w) -> {
                        TermuxShortcutStore.remove(ctx, index);
                        refreshList();
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
            return true;
        });
        return card;
    }

    private void showAddShortcut() {
        Context ctx = requireContext();
        int fieldBg = Color.parseColor("#FF252830");
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 16);
        wrap.setPadding(pad, dp(ctx, 8), pad, 0);

        EditText labelField = new EditText(ctx);
        labelField.setHint("Nom du raccourci…");
        labelField.setTextColor(Color.WHITE);
        labelField.setHintTextColor(Color.parseColor(C_MUTED));
        labelField.setBackgroundColor(fieldBg);
        labelField.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        labelField.setSingleLine(true);
        labelField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        wrap.addView(labelField, matchWrap());

        EditText commandField = new EditText(ctx);
        commandField.setHint("Commande shell…");
        commandField.setTextColor(Color.WHITE);
        commandField.setHintTextColor(Color.parseColor(C_MUTED));
        commandField.setBackgroundColor(fieldBg);
        commandField.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        commandField.setSingleLine(true);
        commandField.setInputType(InputType.TYPE_CLASS_TEXT);
        commandField.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams cmdLp = matchWrap();
        cmdLp.topMargin = dp(ctx, 8);
        wrap.addView(commandField, cmdLp);

        new AlertDialog.Builder(ctx, com.pegasuscorp.orbe.R.style.Theme_Orbe_DarkDialog)
                .setTitle("Nouveau raccourci Termux")
                .setView(wrap)
                .setPositiveButton("Ajouter", (d, w) -> {
                    String label = labelField.getText() != null
                            ? labelField.getText().toString().trim() : "";
                    String command = commandField.getText() != null
                            ? commandField.getText().toString().trim() : "";
                    if (label.isEmpty() || command.isEmpty()) return;
                    TermuxShortcutStore.add(ctx, new TermuxShortcut(label, command));
                    refreshList();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }
}
