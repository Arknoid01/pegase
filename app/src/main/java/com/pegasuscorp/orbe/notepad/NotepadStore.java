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
 * Liste locale des choses à faire (bloc-notes vocal) avec dates, priorités et rappels.
 */
public final class NotepadStore {

    public static final class Item {
        public final String id;
        public final String text;
        public final String created;
        public final String dueDate;
        public final int priority;
        public final long reminderAt;
        public final boolean done;

        Item(String id, String text, String created, String dueDate,
             int priority, long reminderAt, boolean done) {
            this.id = id;
            this.text = text;
            this.created = created;
            this.dueDate = dueDate == null ? "" : dueDate;
            this.priority = priority;
            this.reminderAt = reminderAt;
            this.done = done;
        }

        static Item fromJson(JSONObject o) {
            return new Item(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optString("text", ""),
                    o.optString("created", NotepadDateHelper.today()),
                    o.optString("dueDate", ""),
                    o.optInt("priority", 0),
                    o.optLong("reminderAt", 0),
                    o.optBoolean("done", false));
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("text", text)
                    .put("created", created)
                    .put("dueDate", dueDate)
                    .put("priority", priority)
                    .put("reminderAt", reminderAt)
                    .put("done", done);
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

    public synchronized List<Item> getActiveItems() {
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            if (!item.done) out.add(item);
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
        return add(text, "", 0, 0);
    }

    public synchronized boolean add(String text, String dueDate, int priority, long reminderAt) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty()) return false;
        String id = UUID.randomUUID().toString();
        items.add(new Item(id, cleaned, NotepadDateHelper.today(),
                dueDate == null ? "" : dueDate, priority, reminderAt, false));
        trimIfNeeded();
        save();
        if (reminderAt > System.currentTimeMillis()) {
            NotepadReminderScheduler.schedule(appContext, id, cleaned, reminderAt);
        }
        return true;
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
                        item.priority, item.reminderAt, true));
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
                        item.priority, item.reminderAt, true));
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

    public synchronized String formatForSpeech() {
        return formatList(getActiveItems(), "Ta liste est vide pour l'instant.");
    }

    public synchronized String formatForDate(String date) {
        List<Item> list = getActiveForDate(date);
        String label = NotepadDateHelper.formatDateLabel(date);
        if (list.isEmpty()) {
            return "Rien de prévu " + label + ".";
        }
        return formatList(list, "Rien de prévu " + label + ".");
    }

    public synchronized String formatForTomorrow() {
        return formatForDate(NotepadDateHelper.tomorrow());
    }

    private String formatList(List<Item> active, String emptyMsg) {
        if (active.isEmpty()) return emptyMsg;
        active.sort((a, b) -> Integer.compare(b.priority, a.priority));
        if (active.size() == 1) {
            Item item = active.get(0);
            return "Tu as une chose à faire"
                    + dateSuffix(item) + prioritySuffix(item) + " : " + item.text + ".";
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
            Item item = active.get(i);
            if (item.priority > 0) sb.append("(").append(NotepadDateHelper.priorityLabel(item.priority)).append(") ");
            sb.append(item.text);
        }
        sb.append(".");
        return sb.toString();
    }

    private static String dateSuffix(Item item) {
        if (item.dueDate == null || item.dueDate.isEmpty()) return "";
        return " " + NotepadDateHelper.formatDateLabel(item.dueDate);
    }

    private static String prioritySuffix(Item item) {
        if (item.priority <= 0) return "";
        return " (" + NotepadDateHelper.priorityLabel(item.priority) + ")";
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
