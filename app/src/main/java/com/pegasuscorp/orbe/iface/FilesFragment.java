package com.pegasuscorp.orbe.iface;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.pegasuscorp.orbe.bureau.BureauSessionStore;
import com.pegasuscorp.orbe.contextstore.ContextualFileStore;
import com.pegasuscorp.orbe.orion.GeneratedFiles;
import com.pegasuscorp.orbe.orion.OrionProjectStore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.pegasuscorp.orbe.iface.IfaceUi.C_BTN;
import static com.pegasuscorp.orbe.iface.IfaceUi.C_CYAN;
import static com.pegasuscorp.orbe.iface.IfaceUi.C_MUTED;
import static com.pegasuscorp.orbe.iface.IfaceUi.cardContainer;
import static com.pegasuscorp.orbe.iface.IfaceUi.dp;
import static com.pegasuscorp.orbe.iface.IfaceUi.makeIconButton;
import static com.pegasuscorp.orbe.iface.IfaceUi.matchWrap;
import static com.pegasuscorp.orbe.iface.IfaceUi.showBottomSheet;

/**
 * Onglet Fichiers : projets Orion · contextes · bureau · générés (cache),
 * filtrés par chips de catégorie.
 */
public class FilesFragment extends Fragment {

    private static final long REFRESH_MS = 1500;

    private PegaseInterfaceHost host;
    private LinearLayout rootColumn;
    private LinearLayout chipsHost;
    private LinearLayout filesListHost;
    private FilesCatalog.Category selected = FilesCatalog.Category.ALL;
    private String lastFilesSignature = "";
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (!isResumed()) return;
            refreshFilesIfNeeded();
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
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root, matchWrap());
        rootColumn = root;

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(ctx, 8));
        root.addView(header, matchWrap());

        TextView title = new TextView(ctx);
        title.setText("📁 Fichiers");
        title.setTextColor(Color.parseColor(C_CYAN));
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button menu = makeIconButton(ctx, "⋮");
        menu.setOnClickListener(v -> showFilesMenu());
        header.addView(menu);

        HorizontalScrollView chipScroll = new HorizontalScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipsHost = new LinearLayout(ctx);
        chipsHost.setOrientation(LinearLayout.HORIZONTAL);
        chipsHost.setGravity(Gravity.CENTER_VERTICAL);
        chipsHost.setPadding(0, 0, 0, dp(ctx, 10));
        chipScroll.addView(chipsHost);
        root.addView(chipScroll, matchWrap());

        filesListHost = new LinearLayout(ctx);
        filesListHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(filesListHost, matchWrap());

        rebuildChips();
        List<FilesCatalog.Entry> entries = FilesCatalog.listCategory(ctx, selected);
        lastFilesSignature = entriesSignature(entries) + "|" + selected.name();
        populateFilesList(entries);
        return scroll;
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

    public void forceRefresh() {
        lastFilesSignature = "";
        if (filesListHost != null && getContext() != null) {
            rebuildChips();
            List<FilesCatalog.Entry> entries =
                    FilesCatalog.listCategory(requireContext(), selected);
            populateFilesList(entries);
            lastFilesSignature = entriesSignature(entries) + "|" + selected.name();
        }
    }

    private void refreshFilesIfNeeded() {
        if (filesListHost == null || getContext() == null) return;
        List<FilesCatalog.Entry> entries =
                FilesCatalog.listCategory(requireContext(), selected);
        String signature = entriesSignature(entries) + "|" + selected.name();
        if (signature.equals(lastFilesSignature)) return;
        lastFilesSignature = signature;
        rebuildChips();
        populateFilesList(entries);
    }

    private void selectCategory(FilesCatalog.Category category) {
        if (category == null) category = FilesCatalog.Category.ALL;
        if (category == selected) return;
        selected = category;
        forceRefresh();
    }

    private void rebuildChips() {
        if (chipsHost == null || getContext() == null) return;
        Context ctx = requireContext();
        chipsHost.removeAllViews();
        for (FilesCatalog.Category cat : FilesCatalog.Category.values()) {
            int count = FilesCatalog.countCategory(ctx, cat);
            chipsHost.addView(makeChip(cat, count));
        }
    }

    private View makeChip(FilesCatalog.Category cat, int count) {
        Context ctx = requireContext();
        boolean on = cat == selected;
        TextView chip = new TextView(ctx);
        chip.setText(cat.label + (count > 0 ? " · " + count : ""));
        chip.setTextSize(12);
        chip.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        chip.setTextColor(Color.parseColor(on ? "#0B0F14" : C_CYAN));
        chip.setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 16));
        bg.setColor(Color.parseColor(on ? C_CYAN : C_BTN));
        if (!on) {
            bg.setStroke(dp(ctx, 1), Color.parseColor(C_CYAN));
        }
        chip.setBackground(bg);
        chip.setOnClickListener(v -> selectCategory(cat));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(ctx, 8), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    private static String entriesSignature(List<FilesCatalog.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (FilesCatalog.Entry e : entries) {
            sb.append(e.kind).append('|').append(e.name).append('|').append(e.modified).append('|')
                    .append(e.children.size()).append(';');
            for (FilesCatalog.Item c : e.children) {
                sb.append(c.name).append('@').append(c.modified).append(',');
            }
            sb.append(';');
        }
        return sb.toString();
    }

    private void populateFilesList(List<FilesCatalog.Entry> entries) {
        Context ctx = requireContext();
        filesListHost.removeAllViews();

        if (entries.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(FilesCatalog.emptyMessage(selected));
            empty.setTextColor(Color.parseColor(C_MUTED));
            empty.setTextSize(14);
            filesListHost.addView(empty, matchWrap());
            return;
        }

        if (selected == FilesCatalog.Category.ALL) {
            addSection("Projets Orion", filterKind(entries, FilesCatalog.KIND_PROJECT));
            addSection("Contextes .md", filterKind(entries, FilesCatalog.KIND_CONTEXT));
            addSection("Bureau .md", filterKind(entries, FilesCatalog.KIND_BUREAU));
            addSection("Générés", filterKind(entries, FilesCatalog.KIND_GENERATED));
        } else {
            for (FilesCatalog.Entry entry : entries) {
                if (entry.bundle) {
                    filesListHost.addView(makeBundleCard(entry), matchWrap());
                } else if (!entry.children.isEmpty()) {
                    filesListHost.addView(makeFileCard(entry.children.get(0), entry.title),
                            matchWrap());
                }
            }
        }
    }

    private static List<FilesCatalog.Entry> filterKind(List<FilesCatalog.Entry> entries,
            String kind) {
        List<FilesCatalog.Entry> out = new ArrayList<>();
        for (FilesCatalog.Entry e : entries) {
            if (kind.equals(e.kind)) out.add(e);
        }
        return out;
    }

    private void addSection(String label, List<FilesCatalog.Entry> section) {
        if (section.isEmpty()) return;
        Context ctx = requireContext();
        TextView h = new TextView(ctx);
        h.setText(label + " · " + section.size());
        h.setTextColor(Color.parseColor(C_CYAN));
        h.setTextSize(12);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, dp(ctx, 10), 0, dp(ctx, 6));
        filesListHost.addView(h, matchWrap());

        for (FilesCatalog.Entry entry : section) {
            if (entry.bundle) {
                filesListHost.addView(makeBundleCard(entry), matchWrap());
            } else if (!entry.children.isEmpty()) {
                filesListHost.addView(makeFileCard(entry.children.get(0), entry.title),
                        matchWrap());
            }
        }
    }

    private void showFilesMenu() {
        showBottomSheet(requireContext(), "Fichiers", new String[]{
                "Actualiser",
                "Exporter la catégorie en ZIP",
                "Effacer les générés (cache)"
        }, which -> {
            if (which == 0) forceRefresh();
            else if (which == 1) exportCategoryZip();
            else if (which == 2) confirmClearGenerated();
        });
    }

    private void exportCategoryZip() {
        Context ctx = requireContext();
        List<FilesCatalog.Entry> entries = FilesCatalog.listCategory(ctx, selected);
        List<File> files = new ArrayList<>();
        for (FilesCatalog.Entry e : entries) {
            for (FilesCatalog.Item c : e.children) {
                if (c.file != null && c.file.isFile()) files.add(c.file);
            }
        }
        if (files.isEmpty()) {
            Toast.makeText(ctx, "Rien à exporter", Toast.LENGTH_SHORT).show();
            return;
        }
        String prefix = "pegase_" + selected.name().toLowerCase(Locale.ROOT);
        GeneratedFiles.shareAsZip(requireActivity(), files, prefix);
    }

    private void confirmClearGenerated() {
        confirmDelete("Effacer les générés ?",
                "Supprime uniquement le cache cache/generated (packs éphémères).\n"
                        + "Les projets Orion, contextes et bureau sont conservés.",
                () -> {
                    GeneratedFiles.clearAll(requireContext());
                    forceRefresh();
                });
    }

    private View makeBundleCard(FilesCatalog.Entry entry) {
        Context ctx = requireContext();
        LinearLayout card = cardContainer(ctx);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(head, matchWrap());

        TextView title = new TextView(ctx);
        String emoji = FilesCatalog.KIND_PROJECT.equals(entry.kind) ? "🗂 " : "📦 ";
        title.setText(emoji + entry.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (FilesCatalog.KIND_PROJECT.equals(entry.kind)) {
            Button open = makeIconButton(ctx, "↗");
            open.setOnClickListener(v -> openOrionProject(entry.name));
            head.addView(open);
        } else if (FilesCatalog.KIND_GENERATED.equals(entry.kind)) {
            Button del = makeIconButton(ctx, "🗑");
            del.setOnClickListener(v -> confirmDelete("Supprimer ce pack ?",
                    entry.title, () -> {
                        GeneratedFiles.deleteRecursive(entry.fileOrDir);
                        forceRefresh();
                    }));
            head.addView(del);
        }

        if (entry.children.isEmpty() && FilesCatalog.KIND_PROJECT.equals(entry.kind)) {
            TextView empty = new TextView(ctx);
            empty.setText("Projet vide");
            empty.setTextColor(Color.parseColor(C_MUTED));
            empty.setTextSize(12);
            empty.setPadding(dp(ctx, 8), dp(ctx, 6), 0, 0);
            card.addView(empty, matchWrap());
        }
        for (FilesCatalog.Item child : entry.children) {
            card.addView(makeFileRow(child, true), matchWrap());
        }
        return card;
    }

    private void openOrionProject(String projectName) {
        Context ctx = requireContext();
        try {
            OrionProjectStore.get(ctx).setActive(projectName);
            Toast.makeText(ctx, "Projet actif : " + projectName, Toast.LENGTH_SHORT).show();
            if (host != null) {
                host.showTab(PegaseInterfaceHost.TAB_ORION);
            }
        } catch (Exception e) {
            Toast.makeText(ctx, "Impossible d'ouvrir le projet", Toast.LENGTH_SHORT).show();
        }
        forceRefresh();
    }

    private View makeFileCard(FilesCatalog.Item gf, String subtitle) {
        LinearLayout card = cardContainer(requireContext());
        if (subtitle != null && !subtitle.isEmpty() && !subtitle.equals(gf.name)) {
            TextView sub = new TextView(requireContext());
            sub.setText(subtitle);
            sub.setTextColor(Color.parseColor(C_MUTED));
            sub.setTextSize(11);
            sub.setPadding(0, 0, 0, dp(requireContext(), 4));
            card.addView(sub, matchWrap());
        }
        card.addView(makeFileRow(gf, false), matchWrap());
        return card;
    }

    private View makeFileRow(FilesCatalog.Item gf, boolean nested) {
        Context ctx = requireContext();
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        if (nested) {
            block.setPadding(dp(ctx, 8), dp(ctx, 6), 0, 0);
        }

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        block.addView(head, matchWrap());

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        head.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(ctx);
        name.setText((nested ? "↳ " : "📄 ") + gf.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(nested ? 13 : 14);
        name.setTypeface(null, nested ? Typeface.NORMAL : Typeface.BOLD);
        info.addView(name, matchWrap());

        String date = new SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH)
                .format(new Date(gf.modified));
        String size = gf.file != null
                ? GeneratedFiles.formatSize(gf.file.length()) : "";
        TextView meta = new TextView(ctx);
        meta.setText(size.isEmpty() ? date : size + " · " + date);
        meta.setTextColor(Color.parseColor(C_MUTED));
        meta.setTextSize(11);
        info.addView(meta, matchWrap());

        Button eye = makeIconButton(ctx, "👁");
        eye.setOnClickListener(v -> showFileContent(gf));
        head.addView(eye);

        Button share = makeIconButton(ctx, "↗");
        share.setOnClickListener(v -> shareFile(gf.file));
        head.addView(share);

        Button menu = makeIconButton(ctx, "⋮");
        menu.setOnClickListener(v -> showBottomSheet(ctx, gf.name, new String[]{
                "Lire",
                "Partager",
                "Supprimer"
        }, which -> {
            if (which == 0) showFileContent(gf);
            else if (which == 1) shareFile(gf.file);
            else if (which == 2) confirmDeleteFile(gf);
        }));
        head.addView(menu);

        return block;
    }

    private void confirmDeleteFile(FilesCatalog.Item gf) {
        String msg = gf.name;
        if (FilesCatalog.KIND_CONTEXT.equals(gf.kind)) {
            msg = gf.name + "\n\nRetire aussi ce contexte de la session et de l'index.";
        } else if (FilesCatalog.KIND_BUREAU.equals(gf.kind)) {
            msg = gf.name + "\n\nSupprime aussi le fil Pégase lié, s'il existe.";
        } else if (FilesCatalog.KIND_PROJECT.equals(gf.kind)) {
            msg = gf.name + "\n\nSupprime ce fichier du projet Orion.";
        }
        confirmDelete("Supprimer ?", msg, () -> {
            deleteManagedFile(gf);
            forceRefresh();
        });
    }

    private void deleteManagedFile(FilesCatalog.Item gf) {
        Context ctx = requireContext();
        if (gf == null || gf.file == null) return;
        if (FilesCatalog.KIND_CONTEXT.equals(gf.kind)) {
            String msg = ContextualFileStore.getInstance(ctx).deleteFile(gf.name);
            Toast.makeText(ctx, msg != null ? msg : "Suppression impossible",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (FilesCatalog.KIND_BUREAU.equals(gf.kind)) {
            boolean ok = BureauSessionStore.deleteMarkdownFile(ctx, gf.name);
            Toast.makeText(ctx, ok ? "Supprimé : " + gf.name : "Suppression impossible",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (FilesCatalog.KIND_PROJECT.equals(gf.kind)) {
            boolean ok = gf.file.delete();
            Toast.makeText(ctx, ok ? "Supprimé : " + gf.name : "Suppression impossible",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        GeneratedFiles.deleteFile(gf.file);
        File parent = gf.file.getParentFile();
        if (parent != null && parent.getName().startsWith("bundle_")) {
            File[] left = parent.listFiles();
            if (left == null || left.length == 0) {
                GeneratedFiles.deleteRecursive(parent);
            }
        }
    }

    private void confirmDelete(String title, String message, Runnable onYes) {
        IfaceUi.darkDialog(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Supprimer", (d, w) -> {
                    if (onYes != null) onYes.run();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showFileContent(FilesCatalog.Item gf) {
        if (gf == null || gf.file == null) return;
        Context ctx = requireContext();
        String content = FilesCatalog.readFilePreview(gf.file, 8000);
        IfaceUi.darkDialog(ctx)
                .setTitle(gf.file.getName())
                .setView(IfaceUi.darkTextScroll(ctx, content))
                .setPositiveButton("Partager", (d, w) -> shareFile(gf.file))
                .setNeutralButton("Supprimer", (d, w) -> confirmDeleteFile(gf))
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void shareFile(File file) {
        android.app.Activity act = getActivity();
        if (act == null) {
            Toast.makeText(requireContext(), "Partage indisponible", Toast.LENGTH_SHORT).show();
            return;
        }
        GeneratedFiles.share(act, file);
    }
}
