package com.dhiram.tvhome.data;

import android.graphics.Color;
import android.util.Log;

import com.dhiram.tvhome.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-account mail / calendar / tasks published by the Apps Script in home/feed/Code.gs.
 * URLs come from local.properties (feed.&lt;profile id&gt;=url[,url]) and are never logged.
 */
public final class Feed {

    private static final String TAG = "Feed";
    private static final long TTL_MS = 5 * 60_000L;

    public static final class Mail {
        public String from, subject;
        public long at;
    }

    public static final class Task {
        public String title, list;
        public long due;   // 0 = no due date
    }

    /** Flent business numbers, only in the Flent account's feed (Code.gs reads them from Supabase). */
    public static final class Metrics {
        public int occupied = -1, rooms = -1, winsMonth = -1, winsWeek = -1, visitors7d = -1;

        public float occMonth = -1f;   // occupied ÷ available room-days this month (the dashboards' occupancy)
        public String month;           // "2026-10"

        /** The month's occupancy when the feed has it, else rooms occupied right now ÷ rooms. */
        public float occupancy() {
            if (occMonth >= 0) return occMonth;
            return rooms > 0 ? occupied * 100f / rooms : -1f;
        }
    }

    public static final class Account {
        public String email;
        public Metrics metrics;
        public int unread = -1, important = -1;
        public final List<Mail> top = new ArrayList<>();
        public final List<CalendarRepo.Event> events = new ArrayList<>();
        public final List<Task> tasks = new ArrayList<>();

        /** "flent.in" / "gmail.com" style label. */
        public String label() {
            if (email == null) return "Account";
            int at = email.indexOf('@');
            String domain = at >= 0 ? email.substring(at + 1) : email;
            if (domain.equals("gmail.com")) return "Personal";
            String name = domain.contains(".") ? domain.substring(0, domain.indexOf('.')) : domain;   // flent.in → Flent
            return name.isEmpty() ? domain : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    private static final Map<String, Account> cache = new HashMap<>();
    private static final Map<String, Long> cachedAt = new HashMap<>();

    private Feed() {
    }

    public static boolean configured(String profileId) {
        return !urls(profileId).isEmpty();
    }

    static List<String> urls(String profileId) {
        List<String> out = new ArrayList<>();
        for (String entry : BuildConfig.FEEDS.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || !entry.substring(0, eq).equals(profileId)) continue;
            for (String u : entry.substring(eq + 1).split(",")) {
                if (u.trim().startsWith("https://") || u.trim().startsWith("asset:")) out.add(u.trim());
            }
        }
        return out;
    }

    /** Blocking; call off the UI thread. Accounts that fail keep their last good data. */
    public static List<Account> accounts(String profileId) {
        List<Account> out = new ArrayList<>();
        for (String url : urls(profileId)) {
            Account a = cache.get(url);
            Long t = cachedAt.get(url);
            if (a == null || t == null || System.currentTimeMillis() - t > TTL_MS) {
                try {
                    a = parse(url.startsWith("asset:") ? asset(url.substring(6)) : Http.json(url));
                    cache.put(url, a);
                    cachedAt.put(url, System.currentTimeMillis());
                } catch (Exception e) {
                    Log.w(TAG, "feed fetch failed: " + e.getClass().getSimpleName());
                }
            }
            if (a != null) out.add(a);
        }
        return out;
    }

    static Account parse(JSONObject o) throws Exception {
        if (o.has("error")) throw new Exception("feed error");
        Account a = new Account();
        a.email = o.optString("account", null);
        JSONObject mail = o.optJSONObject("mail");
        if (mail != null && !mail.has("error")) {
            a.unread = mail.optInt("unread", -1);
            a.important = mail.optInt("important", -1);
            JSONArray top = mail.optJSONArray("top");
            for (int i = 0; top != null && i < top.length(); i++) {
                JSONObject m = top.getJSONObject(i);
                Mail x = new Mail();
                x.from = m.optString("from");
                x.subject = m.optString("subject");
                x.at = time(m.optString("at"));
                a.top.add(x);
            }
        }
        JSONObject fl = o.optJSONObject("flent");
        if (fl != null && !fl.has("error")) {
            Metrics m = new Metrics();
            m.occupied = fl.optInt("occupied", -1);
            m.rooms = fl.optInt("rooms", -1);
            m.occMonth = (float) fl.optDouble("occupancyMonth", -1);
            m.month = fl.optString("month", null);
            m.winsMonth = fl.optInt("winsMonth", -1);
            m.winsWeek = fl.optInt("winsWeek", -1);
            m.visitors7d = fl.optInt("visitors7d", -1);
            a.metrics = m;
        }
        JSONArray ev = o.optJSONArray("events");
        for (int i = 0; ev != null && i < ev.length(); i++) {
            JSONObject e = ev.getJSONObject(i);
            CalendarRepo.Event x = new CalendarRepo.Event();
            x.title = e.optString("title", "Busy");
            x.begin = time(e.optString("start"));
            x.end = time(e.optString("end"));
            x.allDay = e.optBoolean("allDay");
            x.calendar = e.optString("calendar");
            try {
                x.color = Color.parseColor(e.optString("color", "#8E8E93"));
            } catch (Exception bad) {
                x.color = 0xFF8E8E93;
            }
            a.events.add(x);
        }
        JSONArray ts = o.optJSONArray("tasks");
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.getJSONObject(i);
            Task x = new Task();
            x.title = t.optString("title");
            x.list = t.optString("list");
            x.due = t.isNull("due") ? 0 : time(t.optString("due"));
            a.tasks.add(x);
        }
        return a;
    }

    /** A bundled sample feed (asset:name.json), for previewing the layout without a deployed script. */
    private static JSONObject asset(String name) throws Exception {
        try (java.io.InputStream in = com.dhiram.tvhome.App.get().getAssets().open(name)) {
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            String json = new String(b, 0, n, "UTF-8");
            // Times in the sample are offsets from now ("+5h"), so the preview is always current.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"\\+(-?\\d+)h\"").matcher(json);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                long t = System.currentTimeMillis() + Long.parseLong(m.group(1)) * 3600_000L;
                m.appendReplacement(sb, "\"" + Instant.ofEpochMilli(t - t % 1800_000L) + "\"");
            }
            m.appendTail(sb);
            return new JSONObject(sb.toString());
        }
    }

    static long time(String iso) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}
