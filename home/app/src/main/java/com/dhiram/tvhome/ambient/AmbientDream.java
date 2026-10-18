package com.dhiram.tvhome.ambient;

import android.animation.ObjectAnimator;
import android.graphics.Typeface;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dhiram.tvhome.App;
import com.dhiram.tvhome.R;
import com.dhiram.tvhome.data.CalendarRepo;
import com.dhiram.tvhome.data.Feed;
import com.dhiram.tvhome.data.Photos;
import com.dhiram.tvhome.data.Profiles;
import com.dhiram.tvhome.data.Today;
import com.dhiram.tvhome.data.Weather;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The screensaver: a ruled column (time, weather / inbox / to-do, Flent numbers, today, world clocks)
 * beside the day's photo, which bleeds in full-height from the right. Mail and tasks are counts only; the screen is shared.
 * Any key wakes it, and home then asks who's watching.
 */
public class AmbientDream extends DreamService {

    private static final long PHOTO_MS = 60_000L;
    private static final long DRIFT_MS = 3 * 60_000L;
    private static final long REFRESH_MS = 60_000L;
    private static final int MAX_EVENTS = 3;

    private App app;
    private float density;
    private int fg, muted, faint, rule, accent;
    private Typeface inter, mono;
    private ImageView photoA, photoB;
    private TextView caption;
    private boolean showingA;
    private View content;
    private LinearLayout sections;
    private final List<Photos.Photo> photos = new ArrayList<>();
    private int photoIndex;
    private final Random random = new Random();
    private Weather.Report weather;
    private Today today;

    private final Runnable nextPhoto = new Runnable() {
        @Override
        public void run() {
            showNextPhoto();
            app.main.postDelayed(this, PHOTO_MS);
        }
    };
    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            float r = 8 * density;
            content.animate().translationX((random.nextFloat() * 2 - 1) * r)
                    .translationY((random.nextFloat() * 2 - 1) * r).setDuration(4000).start();
            app.main.postDelayed(this, DRIFT_MS);
        }
    };
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshData();
            render();
            app.main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        app = App.get();
        density = getResources().getDisplayMetrics().density;
        fg = getColor(R.color.saver_fg);
        muted = getColor(R.color.saver_muted);
        faint = getColor(R.color.saver_faint);
        rule = getColor(R.color.saver_rule);
        accent = getColor(R.color.saver_accent);
        inter = getResources().getFont(R.font.inter);
        mono = getResources().getFont(R.font.geist_mono);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        setContentView(R.layout.ambient);
        photoA = findViewById(R.id.photoA);
        photoB = findViewById(R.id.photoB);
        caption = findViewById(R.id.aCaption);
        content = findViewById(R.id.content);
        sections = findViewById(R.id.aSections);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        loadPhotoList();
        app.main.post(nextPhoto);
        app.main.postDelayed(drift, DRIFT_MS);
        app.main.post(refresh);
        // Today's photos, if they haven't been fetched yet; picked up from the next photo on.
        app.io.execute(() -> {
            Photos.refreshIfStale(getApplicationContext());
            app.main.post(() -> {
                int before = photos.size();
                loadPhotoList();
                if (before == 0) showNextPhoto();
            });
        });
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        app.main.removeCallbacks(nextPhoto);
        app.main.removeCallbacks(drift);
        app.main.removeCallbacks(refresh);
    }

    // ---- photos ----------------------------------------------------------------------------------

    private void loadPhotoList() {
        photos.clear();
        photos.addAll(Photos.all(this));
        Collections.shuffle(photos, random);
        photoIndex = 0;
    }

    private String shownPath;

    private void showNextPhoto() {
        if (photos.isEmpty()) return;
        Photos.Photo p = photos.get(photoIndex++ % photos.size());
        if (photos.size() == 1 && p.path.equals(shownPath)) return;   // one pinned photo: just let it drift
        shownPath = p.path;
        ImageView in = showingA ? photoB : photoA, out = showingA ? photoA : photoB;
        app.images.load(p.path, 1280, bmp -> {
            if (bmp == null) return;
            Photos.setCurrent(p);
            in.setImageBitmap(bmp);
            // Ken Burns inside the frame: a slow zoom and pan across the full interval.
            in.animate().cancel();
            in.setScaleX(1.02f);
            in.setScaleY(1.02f);
            in.setTranslationX(0f);
            // Separate animator for the fade: ViewPropertyAnimator would merge it into the 60 s pan.
            ObjectAnimator.ofFloat(in, View.ALPHA, 0f, 1f).setDuration(2500).start();
            float dx = (random.nextBoolean() ? 1 : -1) * 12 * density;
            // A single pinned photo gets one long, very slow push instead of a per-minute pan.
            long pan = photos.size() == 1 ? 20 * PHOTO_MS : PHOTO_MS + 3000;
            in.animate().scaleX(1.07f).scaleY(1.07f).translationX(dx)
                    .setDuration(pan).setInterpolator(new LinearInterpolator()).start();
            ObjectAnimator.ofFloat(out, View.ALPHA, out.getAlpha(), 0f).setDuration(2500).start();
            caption.animate().alpha(0f).setDuration(600).withEndAction(() -> {
                caption.setText(p.caption);
                caption.animate().alpha(1f).setDuration(1200).start();
            }).start();
            showingA = !showingA;
        });
    }

    // ---- data ------------------------------------------------------------------------------------

    private void refreshData() {
        Weather.fetch(this, r -> {
            weather = r;
            render();
        });
        Profiles.Profile p = app.profiles.active();
        app.io.execute(() -> {
            Today t = Today.load(getApplicationContext(), app.profiles, p);
            app.main.post(() -> {
                today = t;
                render();
            });
        });
    }

    // ---- layout ----------------------------------------------------------------------------------

    private void render() {
        if (sections == null) return;
        String city = weather != null ? weather.city : getString(R.string.weather_city);
        ((TextView) findViewById(R.id.aLabel)).setText(
                new SimpleDateFormat("EEEE d MMM", Locale.ENGLISH).format(new Date()) + "  ·  " + city);

        sections.removeAllViews();
        Today t = today != null ? today : new Today();

        // Numbers: weather always; inbox and to-do when this profile has feeds, else today's range and air.
        List<String[]> cells = new ArrayList<>();
        if (weather != null) {
            boolean airCell = !t.hasMail() && t.tasks.isEmpty() && weather.aqi >= 0;
            cells.add(new String[]{"Weather", weather.temp + "°", weather.condition()
                    + (weather.aqi >= 0 && !airCell ? "  ·  AQI " + weather.aqi : "")});
        }
        if (t.hasMail()) {
            int imp = t.important();
            cells.add(new String[]{"Inbox", String.valueOf(t.unread()), imp > 0 ? imp + " important" : "nothing important", imp > 0 ? "accent" : ""});
        } else if (weather != null) {
            cells.add(new String[]{"High / low", weather.high + "° / " + weather.low + "°", "feels " + weather.feelsLike + "°"});
        }
        if (!t.tasks.isEmpty()) {
            int due = t.dueToday();
            cells.add(new String[]{"To-do", String.valueOf(t.tasks.size()), due > 0 ? due + " due today" : "none due today"});
        } else if (weather != null && weather.aqi >= 0 && !t.hasMail()) {
            cells.add(new String[]{"Air", String.valueOf(weather.aqi), Weather.aqiLabel(weather.aqi).toLowerCase(Locale.ROOT)});
        }
        if (!cells.isEmpty()) {
            addRule();
            sections.addView(cellRow(cells));
        }

        Feed.Metrics m = t.metrics();
        if (m != null && m.occupancy() >= 0) {
            List<String[]> f = new ArrayList<>();
            // The month's room-day occupancy (as the dashboards show it); rooms let right now is a
            // different number, so it's labelled as such underneath.
            f.add(new String[]{m.occMonth >= 0 ? "Occupancy " + new SimpleDateFormat("MMM", Locale.ENGLISH).format(new Date()) : "Occupied now",
                    String.format(Locale.US, "%.1f%%", m.occupancy()),
                    m.rooms > 0 ? m.occupied + " of " + m.rooms + " rooms now" : ""});
            if (m.winsMonth >= 0) {
                f.add(new String[]{"Wins " + new SimpleDateFormat("MMM", Locale.ENGLISH).format(new Date()),
                        String.valueOf(m.winsMonth), m.winsWeek >= 0 ? m.winsWeek + " this week" : ""});
            }
            if (m.visitors7d >= 0) {
                f.add(new String[]{"Visitors 7d", String.format(Locale.US, "%,d", m.visitors7d), "flent.in"});
            }
            addRule();
            sections.addView(cellRow(f));
        }

        addRule();
        sections.addView(label("Today"));
        addAgenda(t.events);

        LinearLayout strip = findViewById(R.id.aClocks);
        strip.removeAllViews();
        fillClocks(strip);
    }

    private void addRule() {
        View v = new View(this);
        v.setBackgroundColor(rule);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
        lp.topMargin = lp.bottomMargin = (int) (12 * density);
        sections.addView(v, lp);
    }

    /** Up to three equal columns: LABEL / value / note. A fourth element "accent" tints the note. */
    private View cellRow(List<String[]> cells) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            if (i < cells.size()) {
                String[] c = cells.get(i);
                col.addView(label(c[0]));
                TextView v = text(c[1], mono, 21, fg, 300);
                v.setIncludeFontPadding(false);
                v.setPadding(0, (int) (5 * density), 0, (int) (3 * density));
                col.addView(v);
                TextView note = text(c[2], inter, 9, c.length > 3 && c[3].equals("accent") ? accent : muted, 420);
                note.setSingleLine(true);
                note.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(note);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private void addAgenda(List<CalendarRepo.Event> events) {
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long tomorrow = c.getTimeInMillis();
        CalendarRepo.Event next = CalendarRepo.next(events, now);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ENGLISH);

        int shown = 0;
        for (CalendarRepo.Event e : events) {
            if (shown >= MAX_EVENTS) break;
            boolean isTomorrow = e.begin >= tomorrow;
            String when = isTomorrow ? "tmrw" : e.allDay ? "all day" : e.isNow(now) ? "now" : hm.format(new Date(e.begin));
            sections.addView(eventRow(when, e.title, e.calendar.toLowerCase(Locale.ROOT), e == next && !isTomorrow, shown > 0));
            shown++;
        }
        if (shown == 0) sections.addView(eventRow("", "Nothing scheduled", "", false, false));
    }

    private View eventRow(String when, String title, String cal, boolean highlight, boolean divider) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        if (divider) {
            View d = new View(this);
            d.setBackgroundColor(0x14EDE9E3);
            outer.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density)));
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
        row.addView(text(when, mono, 11, highlight ? accent : muted, 400),
                new LinearLayout.LayoutParams((int) (72 * density), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView tv = text(title, mono, 11, fg, 400);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView c = text(cal, mono, 9, faint, 400);
        c.setSingleLine(true);
        c.setEllipsize(TextUtils.TruncateAt.END);
        c.setMaxWidth((int) (110 * density));
        c.setPadding((int) (10 * density), 0, 0, 0);
        row.addView(c);
        outer.addView(row);
        return outer;
    }

    /** World clocks, skipping any that match local time (Mumbai, when the TV is in India). */
    private void fillClocks(LinearLayout strip) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
        ZonedDateTime here = ZonedDateTime.now();
        for (String line : getResources().getStringArray(R.array.world_clocks)) {
            String[] p = line.split("\\|");
            ZonedDateTime t = Instant.now().atZone(ZoneId.of(p[1]));
            if (t.getOffset().equals(here.getOffset())) continue;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            String name = p[0].equals("Los Angeles") ? "L.A." : p[0];
            cell.addView(label(name));
            int dayDiff = t.toLocalDate().compareTo(here.toLocalDate());
            String day = dayDiff > 0 ? " +1" : dayDiff < 0 ? " −1" : "";
            TextView v = text(t.format(fmt) + day, mono, 14, muted, 300);
            v.setPadding(0, (int) (3 * density), 0, 0);
            cell.addView(v);
            strip.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private TextView label(String s) {
        TextView t = text(s.toUpperCase(Locale.ROOT), inter, 8, muted, 500);
        t.setLetterSpacing(0.14f);
        t.setPadding(0, 0, 0, (int) (2 * density));
        return t;
    }

    private TextView text(String s, Typeface face, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }
}
