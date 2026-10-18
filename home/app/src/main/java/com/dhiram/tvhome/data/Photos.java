package com.dhiram.tvhome.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.dhiram.tvhome.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Photos for the screensaver and the home fallback, fetched by the TV itself once a day from keyless
 * sources: Bing's photo of the day (India market), Google Earth View aerials, and public-domain
 * landscapes from the Art Institute of Chicago. Up to {@link #MAX} JPEGs live in filesDir/photos with
 * captions in photos.json; files in /sdcard/Pictures/Ambient are mixed in when present.
 */
public final class Photos {

    private static final String TAG = "Photos";
    private static final int MAX = 24;
    private static final long DAY_MS = 24 * 3600_000L;
    private static final String EARTH_VIEW = "https://raw.githubusercontent.com/limhenry/earthview/master/earthview.json";
    private static final String[] AIC_TERMS = {"landscape", "seascape", "mountains", "river", "Hokusai", "Monet"};

    public static final class Photo {
        public String path, caption, source;
        long fetched;
    }

    private static final Object lock = new Object();
    private static List<Photo> pool;
    private static Photo current;

    private Photos() {
    }

    /** The photo on screen right now (screensaver) or the first of today's set; may be null. */
    public static Photo current(Context ctx) {
        synchronized (lock) {
            List<Photo> all = all(ctx);
            if (current != null && all.contains(current)) return current;
            return all.isEmpty() ? null : all.get(0);
        }
    }

    public static void setCurrent(Photo p) {
        synchronized (lock) {
            current = p;
        }
    }

    /**
     * Photos pushed to the sdcard folder, plus the fetched pool unless ambient_pin_local is set and the
     * folder has photos (then only those show). Cheap: reads a small JSON file.
     */
    public static List<Photo> all(Context ctx) {
        synchronized (lock) {
            if (pool == null) pool = load(ctx);
            List<Photo> out = new ArrayList<>();
            File[] files = new File(ctx.getString(R.string.ambient_photos_dir)).listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (!(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) continue;
                    Photo p = new Photo();
                    p.path = f.getPath();
                    p.source = "local";
                    p.caption = "";
                    out.add(p);
                }
            }
            if (out.isEmpty() || !ctx.getResources().getBoolean(R.bool.ambient_pin_local)) out.addAll(pool);
            return out;
        }
    }

    /** Blocking; call off the UI thread. Fetches at most once a day; keeps the old set when offline. */
    public static void refreshIfStale(Context ctx) {
        File dir = dir(ctx);
        File index = new File(dir, "photos.json");
        if (index.exists() && System.currentTimeMillis() - index.lastModified() < DAY_MS) return;

        List<String> enabled = Arrays.asList(ctx.getResources().getStringArray(R.array.photo_sources));
        List<Photo> fresh = new ArrayList<>();
        Random rnd = new Random();
        if (enabled.contains("bing")) fresh.addAll(safe(() -> bing(dir)));
        if (enabled.contains("earthview")) fresh.addAll(safe(() -> earthView(ctx, dir, rnd)));
        if (enabled.contains("artic")) fresh.addAll(safe(() -> artic(dir, rnd)));
        if (fresh.isEmpty()) {
            index.setLastModified(System.currentTimeMillis() - DAY_MS + 3600_000L);   // retry in an hour
            return;
        }

        synchronized (lock) {
            List<Photo> merged = new ArrayList<>(fresh);
            Set<String> have = new HashSet<>();
            for (Photo p : fresh) have.add(p.path);
            for (Photo p : load(ctx)) if (have.add(p.path) && new File(p.path).exists()) merged.add(p);
            while (merged.size() > MAX) {
                Photo old = merged.remove(merged.size() - 1);
                //noinspection ResultOfMethodCallIgnored
                new File(old.path).delete();
            }
            Collections.shuffle(merged, rnd);
            pool = merged;
            save(index, merged);
        }
        Log.i(TAG, "pool refreshed: " + fresh.size() + " new");
    }

    // ---- sources ---------------------------------------------------------------------------------

    private static List<Photo> bing(File dir) throws Exception {
        List<Photo> out = new ArrayList<>();
        JSONArray imgs = Http.json("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-IN")
                .getJSONArray("images");
        for (int i = 0; i < imgs.length(); i++) {
            JSONObject o = imgs.getJSONObject(i);
            String caption = o.optString("copyright");
            int c = caption.indexOf(" (©");
            if (c > 0) caption = caption.substring(0, c);
            Photo p = download("https://www.bing.com" + o.getString("urlbase") + "_1920x1080.jpg",
                    new File(dir, "bing_" + o.optString("startdate", String.valueOf(i)) + ".jpg"));
            if (p == null) continue;
            p.caption = caption;
            p.source = "bing";
            out.add(p);
        }
        return out;
    }

    private static List<Photo> earthView(Context ctx, File dir, Random rnd) throws Exception {
        File list = new File(ctx.getCacheDir(), "earthview.json");
        if (!list.exists() || System.currentTimeMillis() - list.lastModified() > 7 * DAY_MS) {
            byte[] b = Http.get(EARTH_VIEW);
            try (FileOutputStream f = new FileOutputStream(list)) {
                f.write(b);
            }
        }
        JSONArray all = new JSONArray(new String(Files.readAllBytes(list.toPath()), StandardCharsets.UTF_8));
        List<Photo> out = new ArrayList<>();
        for (int tries = 0; out.size() < 6 && tries < 12; tries++) {
            JSONObject o = all.getJSONObject(rnd.nextInt(all.length()));
            String url = o.getString("image");
            String id = url.substring(url.lastIndexOf('/') + 1);
            Photo p = download(url, new File(dir, "earth_" + id));
            if (p == null) continue;
            String region = o.optString("region"), country = o.optString("country");
            p.caption = region.isEmpty() ? country : region + ", " + country;
            p.source = "earthview";
            out.add(p);
        }
        return out;
    }

    private static List<Photo> artic(File dir, Random rnd) throws Exception {
        String term = AIC_TERMS[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % AIC_TERMS.length];
        JSONObject r = Http.json("https://api.artic.edu/api/v1/artworks/search?q=" + term
                + "&query[term][is_public_domain]=true&fields=id,title,image_id,artist_title,date_display&limit=30");
        String iiif = r.getJSONObject("config").getString("iiif_url");
        JSONArray data = r.getJSONArray("data");
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) order.add(i);
        Collections.shuffle(order, rnd);
        List<Photo> out = new ArrayList<>();
        for (int i : order) {
            if (out.size() >= 6) break;
            JSONObject o = data.getJSONObject(i);
            if (o.isNull("image_id")) continue;
            String imageId = o.getString("image_id");
            Photo p = download(iiif + "/" + imageId + "/full/1280,/0/default.jpg", new File(dir, "aic_" + o.getInt("id") + ".jpg"));
            if (p == null) continue;
            // Paintings in portrait would be cropped to a sliver in landscape frames.
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(p.path, b);
            if (b.outWidth < b.outHeight) {
                //noinspection ResultOfMethodCallIgnored
                new File(p.path).delete();
                continue;
            }
            String artist = o.optString("artist_title", "");
            String date = o.optString("date_display", "");
            p.caption = (artist.isEmpty() || artist.equals("null") ? "" : artist + ", ") + o.optString("title")
                    + (date.isEmpty() || date.equals("null") ? "" : " (" + date + ")");
            p.source = "artic";
            out.add(p);
        }
        return out;
    }

    // ---- storage ---------------------------------------------------------------------------------

    /** Downloads (or reuses) one image, re-encoded to ≤1280 px wide so decoding stays cheap. */
    private static Photo download(String url, File to) {
        try {
            if (!to.exists()) {
                byte[] b = Http.get(url);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 1;
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(b, 0, b.length, o);
                if (o.outWidth <= 0) return null;
                while (o.outWidth / (o.inSampleSize * 2) >= 1280) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                if (bmp == null) return null;
                if (bmp.getWidth() > 1280) {
                    Bitmap s = Bitmap.createScaledBitmap(bmp, 1280, bmp.getHeight() * 1280 / bmp.getWidth(), true);
                    bmp.recycle();
                    bmp = s;
                }
                try (FileOutputStream f = new FileOutputStream(to)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 86, f);
                }
                bmp.recycle();
            }
            Photo p = new Photo();
            p.path = to.getPath();
            p.fetched = System.currentTimeMillis();
            return p;
        } catch (Exception e) {
            Log.w(TAG, "download failed: " + e.getClass().getSimpleName());
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            return null;
        }
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "photos");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static List<Photo> load(Context ctx) {
        List<Photo> out = new ArrayList<>();
        try {
            File index = new File(dir(ctx), "photos.json");
            if (!index.exists()) return out;
            JSONArray a = new JSONArray(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Photo p = new Photo();
                p.path = o.getString("file");
                p.caption = o.optString("caption");
                p.source = o.optString("source");
                p.fetched = o.optLong("fetched");
                if (new File(p.path).exists()) out.add(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "bad photos.json", e);
        }
        return out;
    }

    private static void save(File index, List<Photo> photos) {
        try {
            JSONArray a = new JSONArray();
            for (Photo p : photos) {
                a.put(new JSONObject().put("file", p.path).put("caption", p.caption).put("source", p.source)
                        .put("fetched", p.fetched));
            }
            try (FileOutputStream f = new FileOutputStream(index)) {
                f.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "save failed", e);
        }
    }

    private interface Source {
        List<Photo> fetch() throws Exception;
    }

    private static List<Photo> safe(Source s) {
        try {
            return s.fetch();
        } catch (Exception e) {
            Log.w(TAG, "source failed: " + e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }
}
