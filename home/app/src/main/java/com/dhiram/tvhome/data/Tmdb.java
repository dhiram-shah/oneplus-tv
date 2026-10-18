package com.dhiram.tvhome.data;

import android.content.Context;
import android.util.Log;

import com.dhiram.tvhome.BuildConfig;
import com.dhiram.tvhome.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Popular on <service>" rows from TMDB, using its JustWatch-backed availability for India.
 * Key comes from local.properties (tmdb.key) via BuildConfig. Results cached on disk for 6 hours.
 */
public final class Tmdb {

    private static final String TAG = "Tmdb";
    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMG = "https://image.tmdb.org/t/p/";
    private static final long TTL_MS = 6 * 3600_000L;
    /** Service label of the row that isn't tied to one app. */
    public static final String TRENDING = "Trending this week";

    public static final class Title {
        public String title;
        public String overview;
        public String year;
        public double rating;
        public String backdrop;   // w780 URL, for cards
        public String backdropLarge;   // w1280 URL, for the full-width shelf
        public String poster;     // w342 URL
        public boolean isTv;
    }

    public static final class Row {
        public String service;    // display label, e.g. "Netflix"
        public String pkg;        // app that plays it; "" for the trending row
        public final List<Title> titles = new ArrayList<>();
    }

    private Tmdb() {
    }

    public static boolean enabled() {
        return !BuildConfig.TMDB_KEY.isEmpty();
    }

    /** Blocking. One row per configured service, in config order. Never throws. */
    public static List<Row> rows(Context ctx) {
        List<Row> out = new ArrayList<>();
        if (!enabled()) return out;
        File cache = new File(ctx.getFilesDir(), "tmdb.json");
        try {
            if (System.currentTimeMillis() - cache.lastModified() < TTL_MS && cache.exists()) {
                return parse(new JSONArray(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
        }
        try {
            Map<String, Integer> providers = providerIds();
            JSONArray rowsJson = new JSONArray();
            // Trending first: not tied to one service, so pkg is empty and the UI searches for it.
            JSONArray trending = new JSONArray();
            JSONArray tr = Http.json(API + "/trending/all/week?api_key=" + BuildConfig.TMDB_KEY + "&language=en-IN")
                    .getJSONArray("results");
            for (int i = 0; i < tr.length() && trending.length() < 14; i++) {
                JSONObject t = tr.getJSONObject(i);
                String type = t.optString("media_type");
                if (!(type.equals("tv") || type.equals("movie")) || t.isNull("backdrop_path")) continue;
                t.put("_tv", type.equals("tv"));
                trending.put(t);
            }
            if (trending.length() > 0) rowsJson.put(new JSONObject().put("service", TRENDING).put("pkg", "").put("titles", trending));
            for (String line : ctx.getResources().getStringArray(R.array.services)) {
                String[] p = line.split("\\|");
                Integer id = match(providers, p[1]);
                if (id == null) {
                    Log.w(TAG, "no TMDB provider for " + p[1]);
                    continue;
                }
                JSONArray titles = new JSONArray();
                List<JSONObject> merged = new ArrayList<>();
                for (String kind : new String[]{"tv", "movie"}) {
                    JSONObject r = Http.json(API + "/discover/" + kind + "?api_key=" + BuildConfig.TMDB_KEY
                            + "&watch_region=IN&with_watch_providers=" + id
                            + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&language=en-IN&page=1");
                    JSONArray res = r.getJSONArray("results");
                    for (int i = 0; i < res.length(); i++) {
                        JSONObject t = res.getJSONObject(i);
                        if (t.optString("backdrop_path", "").isEmpty() || t.isNull("backdrop_path")) continue;
                        t.put("_tv", kind.equals("tv"));
                        merged.add(t);
                    }
                }
                Collections.sort(merged, (a, b) -> Double.compare(b.optDouble("popularity"), a.optDouble("popularity")));
                for (int i = 0; i < Math.min(14, merged.size()); i++) titles.put(merged.get(i));
                rowsJson.put(new JSONObject().put("service", p[0]).put("pkg", p[2]).put("titles", titles));
            }
            try (FileOutputStream f = new FileOutputStream(cache)) {
                f.write(rowsJson.toString().getBytes(StandardCharsets.UTF_8));
            }
            return parse(rowsJson);
        } catch (Exception e) {
            Log.w(TAG, "fetch failed: " + Http.redact(String.valueOf(e.getMessage())));
            try {
                return parse(new JSONArray(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8)));
            } catch (Exception ignored) {
                return out;
            }
        }
    }

    // ---- backdrops for Continue watching ---------------------------------------------------------

    /** Title → w1280 backdrop URL, or "" when TMDB has none. Persisted in filesDir/tmdb-art.json. */
    private static final Map<String, String> art = new HashMap<>();
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static boolean artLoaded;

    /** A large backdrop already known for this title, without network; null if unknown or none. */
    public static synchronized String cachedBackdrop(Context ctx, String pkg, String title) {
        loadArt(ctx);
        String url = art.get(key(pkg, title));
        return url == null || url.isEmpty() ? null : url;
    }

    /** Whether {@link #backdropFor} can look anything up for this app. */
    public static boolean canLookUp(String pkg) {
        return YOUTUBE.equals(pkg) || enabled();
    }

    /**
     * Blocking. Media sessions hand over small thumbnails, too soft for the full-width shelf, so the
     * shelf looks the title up: YouTube videos by a keyless search for their 1280 px thumbnail,
     * everything else on TMDB (title first, then the subtitle, which some apps use for the show name).
     * Each title is searched once; misses are remembered too.
     */
    public static String backdropFor(Context ctx, String pkg, String title, String subtitle) {
        if (!canLookUp(pkg) || title == null || title.isEmpty()) return null;
        String k = key(pkg, title);
        synchronized (Tmdb.class) {
            loadArt(ctx);
            String known = art.get(k);
            if (known != null) return known.isEmpty() ? null : known;
        }
        String url;
        try {
            url = YOUTUBE.equals(pkg) ? youtubeThumb(title, subtitle) : tmdbBackdrop(title, subtitle);
        } catch (Exception e) {
            Log.w(TAG, "art lookup failed: " + Http.redact(String.valueOf(e.getMessage())));
            return null;   // offline: try again next time
        }
        synchronized (Tmdb.class) {
            art.put(k, url == null ? "" : url);
            try (FileOutputStream f = new FileOutputStream(new File(ctx.getFilesDir(), "tmdb-art.json"))) {
                f.write(new JSONObject(art).toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "art cache save failed", e);
            }
        }
        return url;
    }

    /** First search result's video id → its 1280×720 thumbnail (hq 480×360 when there's no max-res). */
    private static String youtubeThumb(String title, String channel) throws Exception {
        String q = channel == null || channel.isEmpty() ? title : title + " " + channel;
        String html = new String(Http.get("https://www.youtube.com/results?search_query="
                + java.net.URLEncoder.encode(q, "UTF-8")), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(html);
        if (!m.find()) return null;
        String max = "https://i.ytimg.com/vi/" + m.group(1) + "/maxresdefault.jpg";
        try {
            Http.get(max);
            return max;
        } catch (java.io.IOException e) {
            return "https://i.ytimg.com/vi/" + m.group(1) + "/hqdefault.jpg";
        }
    }

    private static String tmdbBackdrop(String title, String subtitle) throws Exception {
        String url = null;
        for (String q : new String[]{title, subtitle}) {
            if (q == null || q.isEmpty() || url != null) continue;
            JSONArray res = Http.json(API + "/search/multi?api_key=" + BuildConfig.TMDB_KEY + "&language=en-IN&query="
                    + java.net.URLEncoder.encode(q, "UTF-8")).getJSONArray("results");
            for (int i = 0; i < res.length() && url == null; i++) {
                JSONObject t = res.getJSONObject(i);
                if (!t.isNull("backdrop_path") && !t.optString("backdrop_path").isEmpty()) {
                    url = IMG + "w1280" + t.getString("backdrop_path");
                }
            }
        }
        return url;
    }

    private static void loadArt(Context ctx) {
        if (artLoaded) return;
        artLoaded = true;
        try {
            File f = new File(ctx.getFilesDir(), "tmdb-art.json");
            if (!f.exists()) return;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                art.put(k, o.getString(k));
            }
        } catch (Exception e) {
            Log.w(TAG, "bad tmdb-art.json", e);
        }
    }

    private static String key(String pkg, String title) {
        String t = title.trim().toLowerCase(Locale.ROOT);
        return YOUTUBE.equals(pkg) ? "yt:" + t : t;
    }

    private static Map<String, Integer> providerIds() throws Exception {
        Map<String, Integer> m = new HashMap<>();
        for (String kind : new String[]{"tv", "movie"}) {
            JSONArray res = Http.json(API + "/watch/providers/" + kind + "?api_key=" + BuildConfig.TMDB_KEY
                    + "&watch_region=IN").getJSONArray("results");
            for (int i = 0; i < res.length(); i++) {
                JSONObject p = res.getJSONObject(i);
                m.put(p.getString("provider_name").toLowerCase(Locale.ROOT), p.getInt("provider_id"));
            }
        }
        return m;
    }

    /** Exact name first, then the shortest provider name containing it (avoids "… Channel" add-ons). */
    private static Integer match(Map<String, Integer> providers, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (providers.containsKey(n)) return providers.get(n);
        String best = null;
        for (String k : providers.keySet()) {
            if (k.contains(n) && (best == null || k.length() < best.length())) best = k;
        }
        return best == null ? null : providers.get(best);
    }

    private static List<Row> parse(JSONArray a) throws Exception {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            Row row = new Row();
            row.service = r.getString("service");
            row.pkg = r.getString("pkg");
            JSONArray ts = r.getJSONArray("titles");
            for (int j = 0; j < ts.length(); j++) {
                JSONObject t = ts.getJSONObject(j);
                Title x = new Title();
                x.isTv = t.optBoolean("_tv");
                x.title = t.optString(x.isTv ? "name" : "title");
                x.overview = t.optString("overview");
                String date = t.optString(x.isTv ? "first_air_date" : "release_date", "");
                x.year = date.length() >= 4 ? date.substring(0, 4) : "";
                x.rating = t.optDouble("vote_average", 0);
                x.backdrop = IMG + "w780" + t.getString("backdrop_path");
                x.backdropLarge = IMG + "w1280" + t.getString("backdrop_path");
                x.poster = t.isNull("poster_path") ? null : IMG + "w342" + t.getString("poster_path");
                row.titles.add(x);
            }
            if (!row.titles.isEmpty()) out.add(row);
        }
        return out;
    }
}
