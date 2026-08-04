package com.pegasuscorp.orbe.notepad;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bloc-notes local — notes avec échéance (jour) et rappel (instant), tag projet optionnel.
 */
public final class NotepadStore {

    /** Fenêtre « proche » pour la vue / résumé par défaut. */
    public static final long NEAR_REMINDER_WINDOW_MS = 48L * 3600_000L;

    public static final class Item {
        public final String id;
        public final String text;
        public final String created;
        public final String dueDate;
        /** Legacy JSON — ignoré à l'usage (jamais lu à voix haute). */
        public final int priority;
        public final long reminderAt;
        public final boolean done;
        public final String projetTag;

        Item(String id, String text, String created, String dueDate,
                int priority, long reminderAt, boolean done, String projetTag) {
            this.id = id;
            this.text = text;
            this.created = created;
            this.dueDate = dueDate == null ? "" : dueDate;
            this.priority = priority;
            this.reminderAt = reminderAt;
            this.done = done;
            this.projetTag = projetTag == null ? "" : projetTag;
        }

        static Item fromJson(JSONObject o) {
            return new Item(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optString("text", ""),
                    o.optString("created", NotepadDateHelper.today()),
                    o.optString("dueDate", ""),
                    o.optInt("priority", 0),
                    o.optLong("reminderAt", 0),
                    o.optBoolean("done", false),
                    o.optString("projetTag", ""));
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("text", text)
                    .put("created", created)
                    .put("dueDate", dueDate)
                    .put("priority", priority)
                    .put("reminderAt", reminderAt)
                    .put("done", done)
                    .put("projetTag", projetTag);
        }

        public boolean hasUpcomingReminder() {
            return reminderAt > System.currentTimeMillis();
        }
    }

    private static final int MAX_ITEMS = 80;

    private static NotepadStore instance;

    private final Context appContext;
    private final File storeFile;
    private final List<Item> items = new ArrayList<>();

    private NotepadStore(Context context) {
        appContext = context.getApplicationContext();
        File dir = new File(appContext.getFilesDir(), "notepad");
        if (!dir.exists()) dir.mkdirs();
        storeFile = new File(dir, "items.json");
        load();
    }

    public static synchronized NotepadStore getInstance(Context context) {
        if (instance == null) instance = new NotepadStore(context);
        return instance;
    }

    public static synchronized void resetInstanceForTests() {
        instance = null;
    }

    public synchronized List<Item> getActiveItems() {
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            if (!item.done) out.add(item);
        }
        return out;
    }

    /**
     * Actif + proche : sans date, échéance ≤ demain, en retard, ou rappel &lt; 48 h.
     */
    public synchronized List<Item> getNearActive() {
        String tomorrow = NotepadDateHelper.tomorrow();
        long soon = System.currentTimeMillis() + NEAR_REMINDER_WINDOW_MS;
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            if (item.done) continue;
            if (item.dueDate == null || item.dueDate.isEmpty()) {
                out.add(item);
                continue;
            }
            if (item.dueDate.compareTo(tomorrow) <= 0) {
                out.add(item);
                continue;
            }
            if (item.reminderAt > 0 && item.reminderAt <= soon) {
                out.add(item);
            }
        }
        return out;
    }

    /** Historique : faits + actifs hors fenêtre proche. */
    public synchronized List<Item> getHistoryItems() {
        List<Item> near = getNearActive();
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            if (item.done) {
                out.add(item);
                continue;
            }
            boolean inNear = false;
            for (Item n : near) {
                if (n.id.equals(item.id)) {
                    inNear = true;
                    break;
                }
            }
            if (!inNear) out.add(item);
        }
        return out;
    }

    public synchronized List<Item> getActiveForDate(String date) {
        List<Item> out = new ArrayList<>();
        if (date == null || date.isEmpty()) return getActiveItems();
        for (Item item : items) {
            if (!item.done && date.equals(item.dueDate)) out.add(item);
        }
        return out;
    }

    public synchronized boolean add(String text) {
        return add(text, "", 0, "", null) != null;
    }

    /**
     * @param resolution si non null, utilise due/reminder déjà résolus ; sinon pas de défaut auto
     *                   (le caller doit appeler {@link NotepadDateHelper#resolveReminder}).
     */
    public synchronized Item add(String text, String dueDate, long reminderAt,
            String projetTag, NotepadDateHelper.ReminderResolution resolution) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty()) return null;
        String due = dueDate == null ? "" : dueDate;
        long rem = reminderAt;
        if (resolution != null) {
            due = resolution.dueDate;
            rem = resolution.reminderAt;
        }
        String tag = projetTag == null ? "" : projetTag.trim();
        String id = UUID.randomUUID().toString();
        Item item = new Item(id, cleaned, NotepadDateHelper.today(),
                due, 0, rem, false, tag);
        items.add(item);
        trimIfNeeded();
        save();
        if (rem > System.currentTimeMillis()) {
            NotepadReminderScheduler.schedule(appContext, id, cleaned, rem);
        }
        return item;
    }

    /** Compat tool / editor legacy. */
    public synchronized boolean add(String text, String dueDate, int priority, long reminderAt) {
        return add(text, dueDate, reminderAt, "", null) != null;
    }

    public synchronized boolean updateSchedule(String id, String dueDate, long reminderAt) {
        if (id == null || id.isEmpty()) return false;
        boolean changed = false;
        List<Item> next = new ArrayList<>();
        for (Item item : items) {
            if (item.id.equals(id)) {
                NotepadReminderScheduler.cancel(appContext, item.id);
                Item updated = new Item(item.id, item.text, item.created,
                        dueDate == null ? "" : dueDate, item.priority,
                        reminderAt, item.done, item.projetTag);
                next.add(updated);
                if (reminderAt > System.currentTimeMillis() && !item.done) {
                    NotepadReminderScheduler.schedule(appContext, item.id, item.text, reminderAt);
                }
                changed = true;
            } else {
                next.add(item);
            }
        }
        if (changed) {
            items.clear();
            items.addAll(next);
            save();
        }
        return changed;
    }

    public synchronized int removeContaining(String query) {
        String q = fold(query);
        if (q.isEmpty()) return 0;
        int removed = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (fold(items.get(i).text).contains(q)) {
                NotepadReminderScheduler.cancel(appContext, items.get(i).id);
                items.remove(i);
                removed++;
            }
        }
        if (removed > 0) save();
        return removed;
    }

    public synchronized boolean markDoneById(String id) {
        if (id == null || id.isEmpty()) return false;
        boolean changed = false;
        List<Item> next = new ArrayList<>();
        for (Item item : items) {
            if (item.id.equals(id) && !item.done) {
                NotepadReminderScheduler.cancel(appContext, item.id);
                next.add(new Item(item.id, item.text, item.created, item.dueDate,
                        item.priority, item.reminderAt, true, item.projetTag));
                changed = true;
            } else {
                next.add(item);
            }
        }
        if (changed) {
            items.clear();
            items.addAll(next);
            save();
        }
        return changed;
    }

    public synchronized boolean removeById(String id) {
        if (id == null || id.isEmpty()) return false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                NotepadReminderScheduler.cancel(appContext, items.get(i).id);
                items.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean markDoneContaining(String query) {
        String q = fold(query);
        if (q.isEmpty()) return false;
        boolean changed = false;
        List<Item> next = new ArrayList<>();
        for (Item item : items) {
            if (!item.done && fold(item.text).contains(q)) {
                NotepadReminderScheduler.cancel(appContext, item.id);
                next.add(new Item(item.id, item.text, item.created, item.dueDate,
                        item.priority, item.reminderAt, true, item.projetTag));
                changed = true;
            } else {
                next.add(item);
            }
        }
        if (changed) {
            items.clear();
            items.addAll(next);
            save();
        }
        return changed;
    }

    public synchronized void clearActive() {
        boolean changed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (!items.get(i).done) {
                NotepadReminderScheduler.cancel(appContext, items.get(i).id);
                items.remove(i);
                changed = true;
            }
        }
        if (changed) save();
    }

    /** Résumé oral — portée proche, pas un dump technique. */
    public synchronized String formatSummary() {
        return formatSummary(getNearActive());
    }

    public synchronized String formatSummary(List<Item> list) {
        if (list == null || list.isEmpty()) {
            return "Ta liste est vide pour l'instant.";
        }
        List<Item> withReminder = new ArrayList<>();
        List<Item> undated = new ArrayList<>();
        List<Item> dated = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Item item : list) {
            if (item.reminderAt > now) {
                withReminder.add(item);
            } else if (item.dueDate == null || item.dueDate.isEmpty()) {
                undated.add(item);
            } else {
                dated.add(item);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (withReminder.size() == 1) {
            Item r = withReminder.get(0);
            sb.append("T'as un rappel ")
                    .append(NotepadDateHelper.formatSpokenWhen(r.reminderAt, r.dueDate))
                    .append(" (").append(truncate(r.text, 40)).append(")");
        } else if (withReminder.size() > 1) {
            sb.append("T'as ").append(withReminder.size()).append(" rappels");
            Item first = withReminder.get(0);
            sb.append(" (dont « ").append(truncate(first.text, 30)).append(" »)");
        }
        int pending = undated.size() + dated.size();
        if (pending > 0) {
            if (sb.length() > 0) sb.append(", et ");
            else sb.append("T'as ");
            if (undated.size() > 0 && dated.isEmpty()) {
                sb.append(undated.size() == 1
                        ? "un truc en attente sans date"
                        : undated.size() + " trucs en attente sans date");
            } else if (dated.size() > 0 && undated.isEmpty()) {
                sb.append(dated.size() == 1
                        ? "une chose prévue"
                        : dated.size() + " choses prévues");
                if (dated.size() == 1 && !dated.get(0).dueDate.isEmpty()) {
                    sb.append(" ").append(NotepadDateHelper.formatDateLabel(dated.get(0).dueDate));
                }
            } else {
                sb.append(pending).append(" trucs en attente");
            }
        }
        if (sb.length() == 0) {
            return formatList(list, "Ta liste est vide pour l'instant.");
        }
        sb.append(".");
        return sb.toString();
    }

    public synchronized String formatHistorySpeech() {
        List<Item> hist = getHistoryItems();
        if (hist.isEmpty()) {
            return "Pas d'historique pour l'instant.";
        }
        int done = 0;
        for (Item i : hist) {
            if (i.done) done++;
        }
        StringBuilder sb = new StringBuilder("Historique : ");
        sb.append(hist.size()).append(" élément");
        if (hist.size() > 1) sb.append("s");
        if (done > 0) sb.append(", dont ").append(done).append(" fait");
        if (done > 1) sb.append("s");
        sb.append(". ");
        int n = Math.min(5, hist.size());
        for (int i = 0; i < n; i++) {
            Item item = hist.get(i);
            if (i > 0) sb.append(" ; ");
            if (item.done) sb.append("(fait) ");
            sb.append(truncate(item.text, 35));
        }
        if (hist.size() > n) sb.append("…");
        return sb.toString();
    }

    public synchronized String formatForSpeech() {
        return formatSummary();
    }

    public synchronized String formatForDate(String date) {
        List<Item> list = getActiveForDate(date);
        String label = NotepadDateHelper.formatDateLabel(date);
        if (list.isEmpty()) {
            return "Rien de prévu " + label + ".";
        }
        return formatSummary(list);
    }

    public synchronized String formatForTomorrow() {
        return formatForDate(NotepadDateHelper.tomorrow());
    }

    private String formatList(List<Item> active, String emptyMsg) {
        if (active.isEmpty()) return emptyMsg;
        if (active.size() == 1) {
            Item item = active.get(0);
            return "Tu as une chose à faire"
                    + dateSuffix(item) + " : " + item.text + ".";
        }
        StringBuilder sb = new StringBuilder("Tu as ")
                .append(active.size())
                .append(" choses à faire");
        String firstDate = active.get(0).dueDate;
        if (!firstDate.isEmpty() && active.stream().allMatch(i -> firstDate.equals(i.dueDate))) {
            sb.append(" ").append(NotepadDateHelper.formatDateLabel(firstDate));
        }
        sb.append(" : ");
        for (int i = 0; i < active.size(); i++) {
            if (i > 0) sb.append(i == active.size() - 1 ? ", et " : ", ");
            sb.append(active.get(i).text);
        }
        sb.append(".");
        return sb.toString();
    }

    private static String dateSuffix(Item item) {
        if (item.dueDate == null || item.dueDate.isEmpty()) return "";
        return " " + NotepadDateHelper.formatDateLabel(item.dueDate);
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max - 1).trim() + "…";
    }

    private void trimIfNeeded() {
        while (items.size() > MAX_ITEMS) {
            NotepadReminderScheduler.cancel(appContext, items.get(0).id);
            items.remove(0);
        }
    }

    private void load() {
        items.clear();
        if (!storeFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(storeFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                Item item = Item.fromJson(arr.getJSONObject(i));
                if (!item.text.isEmpty()) items.add(item);
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Item item : items) arr.put(item.toJson());
            JSONObject root = new JSONObject().put("items", arr);
            try (FileOutputStream out = new FileOutputStream(storeFile)) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static String fold(String text) {
        if (text == null) return "";
        String n = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }
}
