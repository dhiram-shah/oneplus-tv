package com.dhiram.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.TabStopSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.dhiram.tvhome.data.Apps;
import com.dhiram.tvhome.data.CalendarRepo;
import com.dhiram.tvhome.data.ContinueStore;
import com.dhiram.tvhome.data.Devices;
import com.dhiram.tvhome.data.Feed;
import com.dhiram.tvhome.data.MediaWatcher;
import com.dhiram.tvhome.data.Photos;
import com.dhiram.tvhome.data.Profiles;
import com.dhiram.tvhome.data.Tmdb;
import com.dhiram.tvhome.data.Today;
import com.dhiram.tvhome.data.Weather;
import com.dhiram.tvhome.ui.Card;
import com.dhiram.tvhome.ui.CardAdapter;
import com.dhiram.tvhome.ui.ProfilePicker;
import com.dhiram.tvhome.ui.RowView;
import com.dhiram.tvhome.ui.Ui;
import com.dhiram.tvhome.ui.RowsScrollView;
import com.dhiram.tvhome.ui.Shelf;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Home, tvOS-quiet: full-bleed artwork for whatever is focused, one row of apps and devices, one line
 * about your day, and media rows below the fold (Continue Watching, Trending, Popular on each service,
 * All Apps). The only chrome is the profile avatar and the clock.
 */
public class MainActivity extends Activity implements CardAdapter.Listener {

    private static final long MINUTE_MS = 60_000L;
    private static final int APPS_IN_TOP_ROW = 10;
    private static final int DAY_ROW = 2;   // child index of the day strip inside `rows` (apps, inputs, day)

    private App app;
    private Apps apps;
    private Shelf shelf;
    private RowsScrollView scroll;
    private LinearLayout rows;
    private TextView avatar, dayLine;
    private Today today = new Today();
    private Weather.Report weather;
    private float density;
    private int focusedRow = 0;

    private final List<RowView> rowViews = new ArrayList<>();
    private List<Tmdb.Row> tmdbRows = Collections.emptyList();
    private final Map<String, Tmdb.Row> tmdbByPkg = new HashMap<>();

    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            refreshCalendar();
            refreshWeather();
            app.main.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        app = App.get();
        density = getResources().getDisplayMetrics().density;
        apps = new Apps(this);
        shelf = new Shelf(this);
        scroll = findViewById(R.id.rowsScroll);
        rows = findViewById(R.id.rows);
        avatar = findViewById(R.id.avatar);
        avatar.setOnClickListener(v -> pickProfile(false));
        avatar.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!rowViews.isEmpty()) rowViews.get(0).focusRemembered();
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        avatar.setOnFocusChangeListener((v, f) -> {
            if (f) shelf.show(profileHero());
        });
        loadTmdb();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Scanning apps loads every banner, so it runs off the main thread; the rows are rebuilt only
        // if something they show changed (ranking, Continue Watching, TMDB, profile).
        long cursor = app.profiles.usageCursor();
        app.io.execute(() -> {
            Map<String, Apps.AppInfo> found = apps.scan();
            long now = System.currentTimeMillis();
            List<Apps.Use> uses = apps.usageSince(cursor, now, found);
            app.main.post(() -> {
                if (isDestroyed()) return;
                apps.use(found);
                Apps.record(app.profiles, uses, now);
                applyProfile();
            });
        });
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputsChanged, app.main);
        app.main.removeCallbacks(minuteTick);
        minuteTick.run();
        app.io.execute(() -> {
            boolean hadPhotos = Photos.current(this) != null;
            Photos.refreshIfStale(getApplicationContext());
            if (!hadPhotos && Photos.current(this) != null) app.main.post(shelf::refresh);
        });
    }

    /** Home pressed while already home: back to the first tile, like tvOS. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        goTop();
    }

    private void goTop() {
        if (rowViews.isEmpty()) return;
        rowViews.get(0).resetPosition();
        rowViews.get(0).focusRemembered();
    }

    /**
     * D-pad between rows is explicit, not geometric: Up/Down move exactly one row (apps, inputs, the
     * day strip, then each media row), Up from the apps goes to the avatar, and the ends of rows and of the page
     * hold focus instead of jumping somewhere else.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        boolean dpad = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        View f = getCurrentFocus();
        if (!dpad || f == null || f == avatar || rowViews.isEmpty()) return super.dispatchKeyEvent(e);
        RowView row = null;   // null = the day strip
        if (f != dayLine) {
            for (int i = 0; i < rowViews.size() && row == null; i++) {
                if (isInside(f, rowViews.get(i))) row = rowViews.get(i);
            }
            if (row == null) return super.dispatchKeyEvent(e);   // a dialog or popup
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            List<View> stops = stops();
            int at = stops.indexOf(row != null ? row : dayLine);
            int to = at + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (to < 0) avatar.requestFocus();
            else if (to < stops.size()) focusStop(stops.get(to));
            return true;
        }
        if (row == null) return true;
        int pos = row.positionOf(f);
        boolean left = key == KeyEvent.KEYCODE_DPAD_LEFT;
        if (left ? pos <= 0 : pos >= row.count() - 1) return true;
        return super.dispatchKeyEvent(e);
    }

    /** Everything Up/Down steps through, top to bottom: each row, and the day strip between them. */
    private List<View> stops() {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View c = rows.getChildAt(i);
            if (c == dayLine) out.add(dayLine);
            else {
                View list = c.findViewById(R.id.rowList);
                if (list != null) out.add(list);
            }
        }
        return out;
    }

    private void focusStop(View stop) {
        if (stop instanceof RowView) ((RowView) stop).focusRemembered();
        else stop.requestFocus();
    }

    private static boolean isInside(View v, View ancestor) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == ancestor) return true;
        }
        return false;
    }

    /** Plugging something in (or unplugging it) adds or removes its tile while home is on screen. */
    private final TvInputManager.TvInputCallback inputsChanged = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            refreshRows(false);
        }

        @Override
        public void onInputAdded(String inputId) {
            refreshRows(false);
        }

        @Override
        public void onInputRemoved(String inputId) {
            refreshRows(false);
        }

        @Override
        public void onInputUpdated(String inputId) {
            refreshRows(false);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputsChanged);
        app.main.removeCallbacks(minuteTick);
    }

    @Override
    public void onBackPressed() {
        // Home: Back returns to the first row instead of leaving.
        if (focusedRow != 0 && !rowViews.isEmpty()) rowViews.get(0).focusRemembered();
    }

    private void applyProfile() {
        Profiles.Profile p = app.profiles.active();
        Drawable photo = Ui.profilePhoto(this, p.name);
        Drawable face = photo;
        if (face == null) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(p.color);
            face = dot;
        }
        avatar.setBackground(new LayerDrawable(new Drawable[]{face, getDrawable(R.drawable.avatar_ring)}));
        avatar.setText(photo != null ? null : p.initial());
        refreshRows(true);
        refreshCalendar();
    }

    private String builtFrom;   // signature of what the rows were last built from

    /**
     * Rebuilds the rows only when what they show has changed. {@code now} = false defers a rebuild while
     * someone is browsing below the apps row (it happens on the next return home instead).
     */
    private void refreshRows(boolean now) {
        if (!apps.loaded()) return;
        String sig = rowsSignature();
        if (sig.equals(builtFrom) || (!now && focusedRow > DAY_ROW)) return;
        builtFrom = sig;
        buildRows();
    }

    private String rowsSignature() {
        Profiles.Profile p = app.profiles.active();
        StringBuilder sb = new StringBuilder(p.id).append('|');
        for (Apps.AppInfo a : apps.ranked(app.profiles.launches(p))) sb.append(a.pkg).append(',');
        sb.append('|');
        for (ContinueStore.Item it : app.continueStore.forProfile(p.id)) {
            sb.append(it.key).append(':').append(it.position).append(':').append(it.art).append(',');
        }
        sb.append('|').append(System.identityHashCode(tmdbRows)).append('|');
        for (Devices.Device d : Devices.list(this)) sb.append(d.name).append(d.state).append(',');
        return sb.toString();
    }

    // ---- rows ------------------------------------------------------------------------------------

    private void buildRows() {
        Profiles.Profile p = app.profiles.active();
        // A rebuild (an app installed, a device plugged in) keeps your place in the two top rows.
        boolean top = focusedRow < DAY_ROW && rowViews.size() > 1;
        int keepApps = top ? rowViews.get(0).rememberedPosition() : 0;
        int keepInputs = top ? rowViews.get(1).rememberedPosition() : 0;
        rows.removeAllViews();
        rowViews.clear();

        // Row 1: the apps you use most recently.
        List<Apps.AppInfo> ranked = apps.ranked(app.profiles.launches(p));
        List<Card> appsRow = new ArrayList<>();
        for (int i = 0; i < Math.min(APPS_IN_TOP_ROW, ranked.size()); i++) appsRow.add(appCard(ranked.get(i)));
        addRow(null, appsRow, 153, 92, false);
        rowViews.get(0).remember(keepApps);

        // Row 2, smaller: what's plugged in, AirPlay, Inputs, Settings.
        addRow(null, deviceCards(), 112, 44, true);
        rowViews.get(1).remember(keepInputs);

        addDayLine();

        List<Card> cw = new ArrayList<>();
        for (ContinueStore.Item it : app.continueStore.forProfile(p.id)) {
            if (apps.get(it.pkg) == null) continue;   // app disabled or removed
            cw.add(continueCard(it));
            if (cw.size() == 12) break;
        }
        if (!cw.isEmpty()) addRow(getString(R.string.row_continue), cw, 192, 108, false);

        // Trending first, then Popular rows ordered by how recently this profile used each service.
        List<Tmdb.Row> ordered = new ArrayList<>(tmdbRows);
        Map<String, Long> launches = app.profiles.launches(p);
        Collections.sort(ordered, (a, b) -> {
            if (a.pkg.isEmpty() != b.pkg.isEmpty()) return a.pkg.isEmpty() ? -1 : 1;
            return Long.compare(launches.getOrDefault(b.pkg, 0L), launches.getOrDefault(a.pkg, 0L));
        });
        for (Tmdb.Row r : ordered) {
            if (!r.pkg.isEmpty() && apps.get(r.pkg) == null) continue;
            List<Card> cards = new ArrayList<>();
            for (Tmdb.Title t : r.titles) cards.add(titleCard(r, t));
            addRow(r.pkg.isEmpty() ? r.service : "Popular on " + r.service, cards, 192, 108, false);
        }

        List<Card> all = new ArrayList<>();
        for (Apps.AppInfo a : apps.all()) all.add(appCard(a));
        addRow(getString(R.string.row_all_apps), all, 153, 92, false);

        focusedRow = 0;
        scroll.scrollTo(0, 0);
        rows.post(() -> {
            if (!rowViews.isEmpty() && !avatar.hasFocus()) rowViews.get(0).focusRemembered();
        });
    }

    private void addRow(String title, List<Card> cards, int wDp, int hDp, boolean compact) {
        View rowItem = LayoutInflater.from(this).inflate(R.layout.item_row, rows, false);
        TextView t = rowItem.findViewById(R.id.rowTitle);
        if (title == null) t.setVisibility(View.GONE);
        else t.setText(title);
        RowView list = rowItem.findViewById(R.id.rowList);
        CardAdapter adapter = new CardAdapter(rows.getChildCount(), (int) (wDp * density), (int) (hDp * density), this);
        if (compact) adapter.compact();
        list.setAdapter(adapter);
        adapter.set(cards);
        rows.addView(rowItem);
        rowViews.add(list);
    }

    /** Row 3: a quiet strip with what's next today; focusing it puts your whole day in the shelf. */
    private void addDayLine() {
        dayLine = new TextView(this);
        dayLine.setTextSize(11.5f);
        dayLine.setTextColor(0xA6F5F6F8);
        dayLine.setFontVariationSettings("'wght' 450");
        dayLine.setSingleLine(true);
        dayLine.setFocusable(true);
        dayLine.setBackgroundResource(R.drawable.day_line_bg);
        dayLine.setOnFocusChangeListener((v, f) -> {
            dayLine.setTextColor(f ? 0xFFF5F6F8 : 0xA6F5F6F8);
            if (f) onRowFocused(DAY_ROW, dayHero());
        });
        dayLine.setOnClickListener(v -> {
            if (Photos.current(this) != null) startScreensaver();
        });
        dayLine.setPadding((int) (14 * density), (int) (8 * density), (int) (14 * density), (int) (8 * density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart((int) (48 * density));
        lp.setMarginEnd((int) (48 * density));
        lp.topMargin = (int) (2 * density);
        lp.bottomMargin = (int) (22 * density);
        rows.addView(dayLine, lp);
        updateDayLine();
    }

    @Override
    public void onCardFocused(int row, Card card, View view) {
        onRowFocused(row, card.hero);
    }

    private void onRowFocused(int row, Card.Hero hero) {
        boolean browsing = row > DAY_ROW;
        shelf.show(hero);
        if (row == focusedRow && scroll.getScrollY() == targetScroll(row)) return;
        if (browsing != focusedRow > DAY_ROW) shelf.setBrowsing(browsing);
        focusedRow = row;
        scroll.smoothScrollTo(0, targetScroll(row));
        for (int i = 0; i < rows.getChildCount(); i++) {
            // The apps row and day line stay together; deeper rows push the ones above out of view.
            float a = row <= DAY_ROW || i >= row ? 1f : 0f;
            View r = rows.getChildAt(i);
            if (r.getAlpha() != a) r.animate().alpha(a).setDuration(220).start();
        }
    }

    /** The apps row and the day line sit under the shelf; deeper rows rise to where the apps row was. */
    private int targetScroll(int row) {
        if (row <= DAY_ROW || row >= rows.getChildCount()) return 0;
        return Math.max(0, rows.getChildAt(row).getTop() - (int) (330 * density));
    }

    // ---- cards -----------------------------------------------------------------------------------

    private Card appCard(Apps.AppInfo a) {
        Card c = new Card();
        c.name = a.label;
        c.banner = a.banner;
        c.icon = a.icon;
        c.onClick = () -> launch(a);
        c.onLongClick = () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
        c.hero = appHero(a);
        return c;
    }

    /** What the shelf shows for an app: what you last watched there, else what's popular there, else today's landscape. */
    private Card.Hero appHero(Apps.AppInfo a) {
        ContinueStore.Item last = app.continueStore.latest(app.profiles.active().id, a.pkg);
        if (last != null && (last.duration == 0 || last.progress() < 0.95f)) return continueHero(last, a);
        Tmdb.Row r = tmdbByPkg.get(a.pkg);
        if (r != null && !r.titles.isEmpty()) return titleHero(r, r.titles.get(0));
        Card.Hero h = new Card.Hero();
        h.title = a.label;
        return h;
    }

    /** A tile for each port with something plugged in (Fire TV by name, else "HDMI 2"), AirPlay when the receiver is installed, Inputs, Settings. */
    private List<Card> deviceCards() {
        List<Card> out = new ArrayList<>();
        for (Devices.Device d : Devices.plugged(this)) {
            Card c = glyphCard(d.name, d.icon);
            c.onClick = () -> switchInput(d.inputId);
            c.hero.eyebrow = d.named ? d.port
                    : getString(d.state == TvInputManager.INPUT_STATE_CONNECTED ? R.string.connected : R.string.standby);
            c.hero.title = d.name;
            c.hero.subtitle = "Switch to " + d.name;
            out.add(c);
        }
        String airplayPkg = getString(R.string.airplay_package);
        Intent airplay = getPackageManager().getLeanbackLaunchIntentForPackage(airplayPkg);
        if (airplay != null) {
            Card c = glyphCard("AirPlay", R.drawable.logo_airplay);
            c.onClick = () -> open(airplay);
            c.hero.eyebrow = "Screen mirroring";
            c.hero.title = "AirPlay";
            c.hero.subtitle = "On your iPhone or Mac, open Screen Mirroring and choose \u201CLiving Room TV\u201D.";
            out.add(c);
        }
        Card inputs = glyphCard(getString(R.string.inputs), R.drawable.ic_input);
        inputs.onClick = this::showInputs;
        inputs.hero.title = getString(R.string.inputs);
        inputs.hero.subtitle = inputsSummary();
        out.add(inputs);
        Card settings = glyphCard(getString(R.string.settings), R.drawable.ic_settings);
        settings.onClick = () -> open(new Intent(Settings.ACTION_SETTINGS));
        settings.hero.title = getString(R.string.settings);
        out.add(settings);
        return out;
    }

    private Card glyphCard(String name, int iconRes) {
        Card c = new Card();
        c.name = name;
        Drawable d = getDrawable(iconRes);
        if (d != null) {
            d = d.mutate();
            d.setTint(0xFFF5F6F8);
        }
        c.icon = d;
        c.glyph = true;
        c.hero = new Card.Hero();
        return c;
    }

    /** One line per port: the port, then what's on it (CEC name, or its state). */
    private CharSequence inputsSummary() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Devices.Device d : Devices.list(this)) {
            String what = d.named ? d.name
                    : d.state == TvInputManager.INPUT_STATE_CONNECTED ? getString(R.string.connected)
                    : d.state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY ? getString(R.string.standby)
                    : getString(R.string.not_connected);
            line(sb, d.port != null ? d.port : d.name, what);
        }
        return sb;
    }

    private Card continueCard(ContinueStore.Item it) {
        Apps.AppInfo a = apps.get(it.pkg);
        Card c = new Card();
        c.name = it.title;
        c.image = it.art;
        c.icon = a.icon;
        c.progress = it.duration > 0 ? it.progress() : -1f;
        c.hero = continueHero(it, a);
        c.onClick = () -> resume(it, a);
        return c;
    }

    private Card.Hero continueHero(ContinueStore.Item it, Apps.AppInfo a) {
        Card.Hero h = new Card.Hero();
        h.eyebrow = "Continue on " + serviceLabel(it.pkg, a.label);
        h.title = it.title;
        String when = DateUtils.getRelativeTimeSpanString(it.updated, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
        h.subtitle = it.subtitle != null ? it.subtitle + "  ·  " + when : when;
        if (it.duration > 0) {
            h.progress = it.progress();
            long left = Math.max(0, it.duration - it.position) / 60_000L;
            h.progressText = left >= 60 ? (left / 60) + " h " + (left % 60) + " min left" : left + " min left";
        }
        // Session artwork is a small thumbnail; swap in a full-width one (TMDB, YouTube) once it's known.
        String large = Tmdb.cachedBackdrop(this, it.pkg, it.title);
        h.art = large != null ? large : it.art;
        if (large == null && Tmdb.canLookUp(it.pkg)) {
            app.io.execute(() -> {
                String url = Tmdb.backdropFor(getApplicationContext(), it.pkg, it.title, it.subtitle);
                if (url != null) app.main.post(() -> {
                    h.art = url;
                    shelf.refreshIf(h);
                });
            });
        }
        return h;
    }

    private Card titleCard(Tmdb.Row r, Tmdb.Title t) {
        Card c = new Card();
        c.name = t.title;
        c.image = t.backdrop;
        c.hero = titleHero(r, t);
        c.onClick = () -> openTitle(r.pkg, t.title);
        return c;
    }

    private Card.Hero titleHero(Tmdb.Row r, Tmdb.Title t) {
        Card.Hero h = new Card.Hero();
        // The row title already says "Trending" or "Popular on X"; the eyebrow says where it plays.
        h.eyebrow = r.pkg.isEmpty() ? null : r.service;
        h.title = t.title;
        StringBuilder meta = new StringBuilder();
        if (!t.year.isEmpty()) meta.append(t.year).append("  ·  ");
        meta.append(t.isTv ? "Series" : "Film");
        if (t.rating > 0) meta.append("  ·  ").append(String.format(Locale.US, "%.1f", t.rating)).append(" on TMDB");
        h.subtitle = meta + (t.overview.isEmpty() ? "" : "\n" + clip(t.overview, 170));
        h.art = t.backdropLarge;
        return h;
    }

    /** Shortens to the last whole word within max characters. */
    private static String clip(String s, int max) {
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max);
        return s.substring(0, cut > 0 ? cut : max).replaceAll("[,;:.]$", "") + "\u2026";
    }

    private String serviceLabel(String pkg, String fallback) {
        Tmdb.Row r = tmdbByPkg.get(pkg);
        if (r != null) return r.service;
        for (String line : getResources().getStringArray(R.array.services)) {
            String[] p = line.split("\\|");
            if (p[2].equals(pkg)) return p[0];
        }
        return fallback;
    }

    private Card.Hero profileHero() {
        Card.Hero h = new Card.Hero();
        Profiles.Profile p = app.profiles.active();
        h.title = greeting();
        h.subtitle = app.profiles.all().size() > 1 ? "Press OK to switch profile, add one, or set a PIN"
                : "Press OK to add a profile or set a PIN";
        return h;
    }

    // ---- the day ---------------------------------------------------------------------------------

    /** "Makhana Sync at 7:30  ·  26° and cloudy  ·  46 unread" */
    private void updateDayLine() {
        if (dayLine == null) return;
        List<String> parts = new ArrayList<>();
        long now = System.currentTimeMillis();
        CalendarRepo.Event next = CalendarRepo.next(today.events, now);
        if (next != null && Today.dayOf(next.begin) == Today.dayOf(now)) {
            parts.add(next.isNow(now) ? next.title + " now" : next.title + " at " + time(next.begin));
        } else if (!today.events.isEmpty() || today.hasMail()) {
            parts.add("Nothing else today");
        }
        if (today.hasMail()) parts.add(today.unread() + " unread");
        if (!today.tasks.isEmpty() && today.dueToday() > 0) parts.add(today.dueToday() + " due today");
        dayLine.setText(parts.isEmpty() ? greeting() : String.join("  ·  ", parts));
        if (dayLine.isFocused()) shelf.show(dayHero());
    }

    /** The day, typeset: events, then inboxes, then to-dos, then Flent. Details only reach profiles with feeds. */
    private Card.Hero dayHero() {
        Card.Hero h = new Card.Hero();
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        h.eyebrow = DateFormat.format("EEEE d MMMM", cal).toString();
        List<CalendarRepo.Event> left = today.remainingToday();
        h.title = left.isEmpty() ? "Your day is clear" : left.size() == 1 ? "One more thing today"
                : left.size() + " things left today";

        SpannableStringBuilder sb = new SpannableStringBuilder();
        int lines = 0;
        for (CalendarRepo.Event e : today.events) {
            if (lines >= 4) break;
            boolean tomorrow = Today.dayOf(e.begin) > Today.dayOf(now);
            String when = e.allDay ? (tomorrow ? "Tomorrow" : "All day") : (tomorrow ? "Tmrw " : "") + time(e.begin);
            line(sb, when, e.title);
            lines++;
        }
        for (Feed.Account a : today.accounts) {
            if (a.unread < 0) continue;
            String head = a.unread + " unread" + (a.important > 0 ? ", " + a.important + " important" : "");
            line(sb, a.label(), head + (a.top.isEmpty() ? "" : "  ·  " + a.top.get(0).from + ": " + a.top.get(0).subject));
        }
        if (!today.tasks.isEmpty()) {
            int due = today.dueToday();
            line(sb, "To-do", today.tasks.size() + " open" + (due > 0 ? ", " + due + " due today" : "")
                    + "  ·  " + today.tasks.get(0).title);
        }
        Feed.Metrics m = today.metrics();
        if (m != null && m.occupancy() >= 0) {
            line(sb, "Flent", String.format(Locale.US, "%.1f%% occupied", m.occupancy())
                    + (m.winsMonth >= 0 ? "  ·  " + m.winsMonth + " wins this month" : "")
                    + (m.visitors7d >= 0 ? "  ·  " + String.format(Locale.US, "%,d", m.visitors7d) + " visitors this week" : ""));
        }
        h.subtitle = sb.length() > 0 ? sb : "Nothing on the calendar";
        return h;
    }

    /** "7:30 pm ⇥ Makhana Sync": the lead dimmer, the text on a shared tab stop so lines align. */
    private void line(SpannableStringBuilder sb, String lead, String text) {
        if (sb.length() > 0) sb.append('\n');
        int start = sb.length();
        sb.append(lead);
        sb.setSpan(new ForegroundColorSpan(0x80F5F6F8), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\t').append(text);
        sb.setSpan(new TabStopSpan.Standard((int) (78 * density)), start, sb.length(), Spanned.SPAN_PARAGRAPH);
    }

    private String time(long t) {
        return DateFormat.getTimeFormat(this).format(t).toLowerCase(Locale.ROOT);
    }

    private String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        return getString(res) + ", " + app.profiles.active().name;
    }

    // ---- actions ---------------------------------------------------------------------------------

    private void launch(Apps.AppInfo a) {
        try {
            startActivity(a.launchIntent());
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    /** Back into the player if the app's session is still alive, otherwise just open the app. */
    private void resume(ContinueStore.Item it, Apps.AppInfo a) {
        PendingIntent pi = MediaWatcher.SESSION_ACTIVITIES.get(it.pkg);
        if (pi != null) {
            try {
                pi.send();
                return;
            } catch (PendingIntent.CanceledException ignored) {
            }
        }
        launch(a);
    }

    /**
     * Opens a title in its app's own search. Trending titles aren't tied to an app, so they go to the
     * first service (in the order you use them) that accepts a search.
     */
    private void openTitle(String pkg, String title) {
        List<String> candidates = new ArrayList<>();
        if (!pkg.isEmpty()) candidates.add(pkg);
        else for (Apps.AppInfo a : apps.ranked(app.profiles.launches(app.profiles.active()))) candidates.add(a.pkg);
        for (String p : candidates) {
            Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(p).putExtra(SearchManager.QUERY, title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (search.resolveActivity(getPackageManager()) == null) continue;
            try {
                startActivity(search);
                return;
            } catch (Exception ignored) {
            }
        }
        Apps.AppInfo a = pkg.isEmpty() ? null : apps.get(pkg);
        if (a != null) launch(a);
        else Toast.makeText(this, "Search for “" + title + "” in your apps", Toast.LENGTH_SHORT).show();
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private void startScreensaver() {
        open(new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"));
    }

    private void switchInput(String inputId) {
        open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                TvContract.buildChannelUriForPassthroughInput(inputId), "vnd.android.cursor.item/channel"));
    }

    private void pickProfile(boolean required) {
        new ProfilePicker(this, app.profiles, required, p -> app.profiles.setActive(p))
                .onClose(this::applyProfile)
                .show();
    }

    // ---- data ------------------------------------------------------------------------------------

    private void loadTmdb() {
        if (!Tmdb.enabled()) return;
        app.io.execute(() -> {
            List<Tmdb.Row> r = Tmdb.rows(getApplicationContext());
            app.main.post(() -> {
                tmdbRows = r;
                tmdbByPkg.clear();
                for (Tmdb.Row row : r) if (!row.pkg.isEmpty()) tmdbByPkg.put(row.pkg, row);
                if (!isFinishing()) refreshRows(false);
            });
        });
    }

    private void refreshWeather() {
        Weather.fetch(this, r -> {
            weather = r;
            TextView head = findViewById(R.id.headWeather);
            if (r == null) {
                head.setVisibility(View.GONE);
            } else {
                head.setText(r.temp + "°");
                Drawable d = getDrawable(Weather.icon(r.code, r.day));
                if (d != null) {
                    int px = (int) (15 * density);
                    d = d.mutate();
                    d.setTint(0xBFF5F6F8);
                    d.setBounds(0, 0, px, px);
                }
                head.setCompoundDrawablesRelative(d, null, null, null);
                head.setVisibility(View.VISIBLE);
            }
            updateDayLine();
        });
    }

    /** Calendars + inboxes + tasks for the active profile, then the day line. */
    private void refreshCalendar() {
        Profiles.Profile p = app.profiles.active();
        app.io.execute(() -> {
            Today t = Today.load(getApplicationContext(), app.profiles, p);
            app.main.post(() -> {
                if (!p.id.equals(app.profiles.active().id)) return;
                today = t;
                updateDayLine();
            });
        });
    }

    // ---- inputs ----------------------------------------------------------------------------------

    /** A panel at the top right: each port with the device on it (and its logo), then Live TV. */
    private void showInputs() {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, (ViewGroup) findViewById(R.id.root), false);
        LinearLayout list = panel.findViewById(R.id.rows);
        final PopupWindow popup = new PopupWindow(panel, (int) (300 * density), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);

        for (Devices.Device d : Devices.list(this)) {
            String status = d.named ? d.port
                    : d.state == TvInputManager.INPUT_STATE_CONNECTED ? getString(R.string.connected)
                    : d.state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY ? getString(R.string.standby)
                    : getString(R.string.not_connected);
            addInputRow(list, popup, d.icon, d.name, status, d.named || d.state == TvInputManager.INPUT_STATE_CONNECTED,
                    () -> switchInput(d.inputId));
        }
        String liveTvPkg = getString(R.string.live_tv_package).trim();
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) addInputRow(list, popup, R.drawable.ic_tv, getString(R.string.live_tv), null, false, () -> open(liveTv));
        if (list.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        popup.showAtLocation(findViewById(R.id.root), Gravity.END | Gravity.TOP, (int) (48 * density), (int) (60 * density));
        panel.setAlpha(0f);
        panel.setTranslationY(-6 * density);
        panel.animate().alpha(1f).translationY(0f).setDuration(200).start();
        list.getChildAt(0).requestFocus();
    }

    private void addInputRow(LinearLayout list, PopupWindow popup, int iconRes, String title, String status,
                             boolean live, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, list, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView st = row.findViewById(R.id.status);
        st.setText(status);
        st.setVisibility(status == null ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.dot).setVisibility(live ? View.VISIBLE : View.GONE);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        list.addView(row);
    }
}
