package com.dhiram.tvhome.data;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import com.dhiram.tvhome.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installed TV apps, ranked per profile by when they were last in the foreground. */
public class Apps {

    public static final class AppInfo {
        public final String label;
        public final String pkg;
        public final ComponentName component;
        public final Drawable banner;
        public final Drawable icon;
        private int ambient;

        AppInfo(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }

        public Intent launchIntent() {
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }

        /** A vivid colour sampled from the banner (or icon), cached. */
        public int ambientColor() {
            if (ambient == 0) ambient = vividColor(banner != null ? banner : icon);
            return ambient;
        }
    }

    private final Context ctx;
    private final Set<String> hidden;
    private final List<String> defaults;
    private volatile Map<String, AppInfo> byPkg = new HashMap<>();
    private volatile boolean loaded;

    /** A foreground event: {@code pkg} came to the front at {@code at}. */
    public static final class Use {
        final String pkg;
        final long at;

        Use(String pkg, long at) {
            this.pkg = pkg;
            this.at = at;
        }
    }

    public Apps(Context ctx) {
        this.ctx = ctx;
        hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        defaults = Arrays.asList(ctx.getResources().getStringArray(R.array.default_apps));
    }

    /** Installed TV apps with their banners; slow (loads every banner), so call it off the main thread. */
    public Map<String, AppInfo> scan() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, AppInfo> found = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || found.containsKey(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            found.put(pkg, new AppInfo(String.valueOf(ri.loadLabel(pm)),
                    new ComponentName(pkg, ri.activityInfo.name), banner, ri.loadIcon(pm)));
        }
        return found;
    }

    /** Main thread: switch to a fresh {@link #scan()}. */
    public void use(Map<String, AppInfo> found) {
        byPkg = found;
        loaded = true;
    }

    public boolean loaded() {
        return loaded;
    }

    public AppInfo get(String pkg) {
        return byPkg.get(pkg);
    }

    /** Alphabetical. */
    public List<AppInfo> all() {
        List<AppInfo> l = new ArrayList<>(byPkg.values());
        Collections.sort(l, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return l;
    }

    /** Most recently used first; apps never used fall back to the default order, then A–Z. */
    public List<AppInfo> ranked(Map<String, Long> launches) {
        List<AppInfo> l = all();
        Collections.sort(l, (a, b) -> {
            long ta = launches.getOrDefault(a.pkg, 0L), tb = launches.getOrDefault(b.pkg, 0L);
            if (ta != tb) return Long.compare(tb, ta);
            int da = defaults.indexOf(a.pkg), db = defaults.indexOf(b.pkg);
            if (da >= 0 || db >= 0) {
                if (da < 0) return 1;
                if (db < 0) return -1;
                return Integer.compare(da, db);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        return l;
    }

    /**
     * Apps in {@code known} that came to the foreground between {@code from} and {@code now}, including
     * launches from the remote's Netflix/Prime/YouTube buttons. Safe off the main thread. Needs
     * GET_USAGE_STATS (granted over adb); null without it.
     */
    public List<Use> usageSince(long from, long now, Map<String, AppInfo> known) {
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;
        List<Use> out = new ArrayList<>();
        try {
            UsageEvents ev = usm.queryEvents(Math.max(from, now - 14L * 24 * 3600 * 1000), now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev != null && ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && known.containsKey(e.getPackageName())) {
                    out.add(new Use(e.getPackageName(), e.getTimeStamp()));
                }
            }
        } catch (SecurityException ignored) {
            return null;
        }
        return out;
    }

    /** Main thread: attributes {@link #usageSince} results to the active profile. */
    public static void record(Profiles profiles, List<Use> uses, long now) {
        if (uses == null) return;
        for (Use u : uses) profiles.recordLaunch(u.pkg, u.at);
        profiles.setUsageCursor(now);
    }

    static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }
}
