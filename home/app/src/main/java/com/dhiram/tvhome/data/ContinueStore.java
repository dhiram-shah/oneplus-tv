package com.dhiram.tvhome.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Things that were playing, newest first, persisted as a small JSON file. */
public class ContinueStore {

    public static final class Item {
        public String key;          // pkg + title, lower-cased
        public String pkg;
        public String title;
        public String subtitle;
        public String art;          // absolute file path in filesDir/art, or null
        public long position;       // ms
        public long duration;       // ms, 0 if unknown
        public long updated;        // wall clock ms
        public String profile;

        public float progress() {
            return duration > 0 ? Math.max(0f, Math.min(1f, position / (float) duration)) : 0f;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("key", key).put("pkg", pkg).put("title", title)
                    .put("subtitle", subtitle).put("art", art).put("position", position)
                    .put("duration", duration).put("updated", updated).put("profile", profile);
        }

        static Item fromJson(JSONObject o) {
            Item i = new Item();
            i.key = o.optString("key");
            i.pkg = o.optString("pkg");
            i.title = o.optString("title");
            i.subtitle = o.optString("subtitle", null);
            i.art = o.optString("art", null);
            i.position = o.optLong("position");
            i.duration = o.optLong("duration");
            i.updated = o.optLong("updated");
            i.profile = o.optString("profile");
            return i;
        }
    }

    private static final int MAX = 40;
    private final File file;
    private final List<Item> items = new ArrayList<>();

    public ContinueStore(Context ctx) {
        file = new File(ctx.getFilesDir(), "continue.json");
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) items.add(Item.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
    }

    public synchronized void upsert(Item it) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(it.key)) {
                Item old = items.remove(i);
                if (it.art == null) it.art = old.art;
                if (it.duration == 0) it.duration = old.duration;
                break;
            }
        }
        items.add(0, it);
        while (items.size() > MAX) {
            Item gone = items.remove(items.size() - 1);
            if (gone.art != null && !stillUsed(gone.art)) //noinspection ResultOfMethodCallIgnored
                new File(gone.art).delete();
        }
        save();
    }

    private boolean stillUsed(String art) {
        for (Item i : items) if (art.equals(i.art)) return true;
        return false;
    }

    /** For one profile, newest first, skipping things watched to the end. */
    public synchronized List<Item> forProfile(String profileId) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            if (!profileId.equals(i.profile)) continue;
            if (i.duration > 0 && i.progress() > 0.95f) continue;
            out.add(i);
        }
        return Collections.unmodifiableList(out);
    }

    /** Newest item for an app and profile, or null. */
    public synchronized Item latest(String profileId, String pkg) {
        for (Item i : items) if (profileId.equals(i.profile) && pkg.equals(i.pkg)) return i;
        return null;
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Item i : items) a.put(i.toJson());
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        } catch (Exception ignored) {
        }
    }
}
