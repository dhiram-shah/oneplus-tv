package com.dhiram.tvhome.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import com.dhiram.tvhome.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Household profiles, up to {@link #MAX}. Seeded from res/values/config.xml (profiles) on first run,
 * then managed on the TV: add, rename, delete, and an optional 4-digit PIN lock. A Guest profile is
 * always present: no lock, no calendar or mail, and nothing it watches is remembered.
 * Each profile keeps its own app history (orders its apps row) and calendar selection.
 */
public class Profiles {

    public static final int MAX = 4;
    private static final int[] PALETTE = {0xFF0A84FF, 0xFFFF375F, 0xFF30D158, 0xFFFF9F0A, 0xFFBF5AF2, 0xFF64D2FF};

    public static final class Profile {
        public String id;
        public String name;
        public int color;
        /** null = every calendar on the TV, empty = none, else calendar display names. */
        public List<String> calendars;
        public boolean guest;
        String pinSalt, pinHash;

        public String initial() {
            return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        }

        public boolean locked() {
            return pinHash != null;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject().put("id", id).put("name", name).put("color", color).put("guest", guest);
            if (calendars != null) o.put("calendars", new JSONArray(calendars));
            if (pinHash != null) o.put("salt", pinSalt).put("pin", pinHash);
            return o;
        }

        static Profile fromJson(JSONObject o) {
            Profile p = new Profile();
            p.id = o.optString("id");
            p.name = o.optString("name");
            p.color = o.optInt("color", PALETTE[0]);
            p.guest = o.optBoolean("guest");
            JSONArray c = o.optJSONArray("calendars");
            if (c != null) {
                p.calendars = new ArrayList<>();
                for (int i = 0; i < c.length(); i++) p.calendars.add(c.optString(i));
            }
            p.pinSalt = o.optString("salt", null);
            p.pinHash = o.optString("pin", null);
            return p;
        }
    }

    private final SharedPreferences prefs;
    private final List<Profile> all = new ArrayList<>();

    public Profiles(Context ctx) {
        prefs = ctx.getSharedPreferences("profiles", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(prefs.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) all.add(Profile.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        if (all.isEmpty()) seed(ctx);
    }

    private void seed(Context ctx) {
        for (String line : ctx.getResources().getStringArray(R.array.profiles)) {
            String[] f = line.split("\\|");
            Profile p = new Profile();
            p.name = f[0].trim();
            p.id = idFor(p.name);
            p.color = f.length > 1 ? Color.parseColor(f[1].trim()) : PALETTE[all.size() % PALETTE.length];
            if (f.length > 2 && !f[2].trim().equalsIgnoreCase("all")) {
                p.calendars = new ArrayList<>();
                if (!f[2].trim().equalsIgnoreCase("none")) for (String c : f[2].split(",")) p.calendars.add(c.trim());
            }
            p.guest = p.name.equalsIgnoreCase("guest");
            all.add(p);
            if (all.size() == MAX) break;
        }
        ensureGuest();
        save();
    }

    private void ensureGuest() {
        for (Profile p : all) if (p.guest) return;
        Profile g = new Profile();
        g.name = "Guest";
        g.id = "guest";
        g.color = 0xFF8E8E93;
        g.guest = true;
        g.calendars = new ArrayList<>();
        if (all.size() >= MAX) all.remove(all.size() - 1);
        all.add(g);
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Profile p : all) a.put(p.toJson());
        } catch (Exception ignored) {
        }
        prefs.edit().putString("list", a.toString()).apply();
    }

    private String idFor(String name) {
        String base = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (base.isEmpty()) base = "profile";
        String id = base;
        for (int n = 2; byId(id) != null; n++) id = base + n;
        return id;
    }

    private Profile byId(String id) {
        for (Profile p : all) if (p.id.equals(id)) return p;
        return null;
    }

    public List<Profile> all() {
        return Collections.unmodifiableList(all);
    }

    public Profile active() {
        Profile p = byId(prefs.getString("active", ""));
        return p != null ? p : all.get(0);
    }

    public void setActive(Profile p) {
        prefs.edit().putString("active", p.id).apply();
    }

    // ---- management -----------------------------------------------------------------------------

    public boolean canAdd() {
        return all.size() < MAX;
    }

    /** New profiles see no TV calendars (those are Dhiram's Google account); their own feed can add some. */
    public Profile add(String name) {
        if (!canAdd()) return null;
        Profile p = new Profile();
        p.name = name.trim();
        p.id = idFor(p.name);
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (Profile q : all) used.add(q.color);
        p.color = PALETTE[0];
        for (int c : PALETTE) if (!used.contains(c)) {
            p.color = c;
            break;
        }
        p.calendars = new ArrayList<>();
        int guestAt = all.size();
        for (int i = 0; i < all.size(); i++) if (all.get(i).guest) guestAt = i;
        all.add(guestAt, p);   // Guest stays last
        save();
        return p;
    }

    public void rename(Profile p, String name) {
        p.name = name.trim();
        save();
    }

    /** Guest and the last non-guest profile can't be removed. */
    public boolean canDelete(Profile p) {
        if (p.guest) return false;
        int people = 0;
        for (Profile q : all) if (!q.guest) people++;
        return people > 1;
    }

    public void delete(Profile p) {
        if (!canDelete(p)) return;
        all.remove(p);
        prefs.edit().remove("launches_" + p.id).apply();
        if (active() == p || byId(prefs.getString("active", "")) == null) setActive(all.get(0));
        save();
    }

    // ---- PIN ------------------------------------------------------------------------------------

    public void setPin(Profile p, String pin) {
        if (p.guest) return;
        if (pin == null) {
            p.pinSalt = p.pinHash = null;
        } else {
            byte[] salt = new byte[12];
            new SecureRandom().nextBytes(salt);
            p.pinSalt = hex(salt);
            p.pinHash = hash(p.pinSalt, pin);
        }
        save();
    }

    public boolean checkPin(Profile p, String pin) {
        return !p.locked() || p.pinHash.equals(hash(p.pinSalt, pin));
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest((salt + ":" + pin).getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---- per-profile app history ----------------------------------------------------------------

    /** Records that {@code pkg} came to the foreground at {@code when} while the active profile was on. */
    public void recordLaunch(String pkg, long when) {
        Profile p = active();
        if (p.guest) return;
        Map<String, Long> m = launches(p);
        Long prev = m.get(pkg);
        if (prev != null && prev >= when) return;
        m.put(pkg, when);
        prefs.edit().putString("launches_" + p.id, new JSONObject(m).toString()).apply();
    }

    public Map<String, Long> launches(Profile p) {
        Map<String, Long> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(prefs.getString("launches_" + p.id, "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, o.getLong(k));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    /** Last time we scanned UsageStats; events after this are attributed to the active profile. */
    public long usageCursor() {
        return prefs.getLong("usage_cursor", 0L);
    }

    public void setUsageCursor(long t) {
        prefs.edit().putLong("usage_cursor", t).apply();
    }

    public boolean showsCalendar(Profile p, String calendarName) {
        return p.calendars == null || p.calendars.contains(calendarName);
    }
}
