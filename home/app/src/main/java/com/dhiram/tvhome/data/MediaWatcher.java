package com.dhiram.tvhome.data;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import com.dhiram.tvhome.App;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches every app's media session and records what plays into {@link ContinueStore}.
 * A notification listener is the only non-system way to see other apps' sessions; it is enabled over
 * adb (cmd notification allow_listener). We never read notifications themselves.
 */
public class MediaWatcher extends NotificationListenerService {

    private static final String TAG = "MediaWatcher";
    private static final long MIN_SAVE_INTERVAL_MS = 15_000L;

    /** Session activities of live sessions, so a card can jump straight back into the player. */
    public static final Map<String, PendingIntent> SESSION_ACTIVITIES = new HashMap<>();

    private MediaSessionManager msm;
    private final Map<MediaController, MediaController.Callback> watched = new HashMap<>();
    private final Map<String, Long> lastSave = new HashMap<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::watch;

    @Override
    public void onListenerConnected() {
        msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        ComponentName me = new ComponentName(this, MediaWatcher.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, me);
            watch(msm.getActiveSessions(me));
        } catch (SecurityException e) {
            Log.w(TAG, "no session access", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsChanged);
        for (Map.Entry<MediaController, MediaController.Callback> e : watched.entrySet()) {
            e.getKey().unregisterCallback(e.getValue());
        }
        watched.clear();
    }

    private void watch(List<MediaController> controllers) {
        if (controllers == null) return;
        for (MediaController mc : controllers) {
            if (mc.getPackageName().equals(getPackageName())) continue;
            boolean known = false;
            for (MediaController w : watched.keySet()) {
                if (w.getSessionToken().equals(mc.getSessionToken())) known = true;
            }
            if (known) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    record(mc, true);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    boolean paused = state != null && state.getState() != PlaybackState.STATE_PLAYING;
                    record(mc, paused);   // always save on pause/stop so the position is fresh
                }

                @Override
                public void onSessionDestroyed() {
                    record(mc, true);
                    mc.unregisterCallback(this);
                    watched.remove(mc);
                }
            };
            mc.registerCallback(cb);
            watched.put(mc, cb);
            record(mc, true);
        }
    }

    private void record(MediaController mc, boolean force) {
        MediaMetadata md = mc.getMetadata();
        if (md == null) return;
        String title = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                md.getString(MediaMetadata.METADATA_KEY_TITLE));
        if (title == null) return;
        if (App.get().profiles.active().guest) return;   // Guest viewing isn't remembered
        String pkg = mc.getPackageName();
        PendingIntent sa = mc.getSessionActivity();
        if (sa != null) SESSION_ACTIVITIES.put(pkg, sa);

        String key = (pkg + "|" + title).toLowerCase();
        long now = System.currentTimeMillis();
        Long prev = lastSave.get(key);
        if (!force && prev != null && now - prev < MIN_SAVE_INTERVAL_MS) return;
        lastSave.put(key, now);

        ContinueStore.Item it = new ContinueStore.Item();
        it.key = key;
        it.pkg = pkg;
        it.title = title;
        it.subtitle = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                md.getString(MediaMetadata.METADATA_KEY_ALBUM));
        it.duration = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        PlaybackState ps = mc.getPlaybackState();
        if (ps != null) {
            long pos = ps.getPosition();
            if (ps.getState() == PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0) {
                pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
            }
            it.position = Math.max(0, pos);
        }
        it.updated = now;
        it.profile = App.get().profiles.active().id;
        it.art = saveArt(key, md);
        App.get().continueStore.upsert(it);
        Log.i(TAG, "recorded " + pkg + " '" + title + "' pos=" + it.position + "/" + it.duration
                + " art=" + (it.art != null));
    }

    /** Session artwork as a ≤1280px JPEG, or its web URL when the bitmap is small (the UI loader fetches it). */
    private String saveArt(String key, MediaMetadata md) {
        File existing = new File(new File(getFilesDir(), "art"), Integer.toHexString(key.hashCode()) + ".jpg");
        if (existing.exists()) return existing.getPath();   // don't re-encode every 15 s while playing
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String uri = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ART_URI),
                md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
        boolean web = uri != null && uri.startsWith("http");
        // The shelf is 1280 px wide: a small session bitmap loses to a web URL, which is usually larger.
        if (b == null || (b.getWidth() < 960 && web)) return web ? uri : null;
        try {
            File dir = new File(getFilesDir(), "art");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, Integer.toHexString(key.hashCode()) + ".jpg");
            Bitmap scaled = b.getWidth() > 1280
                    ? Bitmap.createScaledBitmap(b, 1280, Math.max(1, b.getHeight() * 1280 / b.getWidth()), true) : b;
            try (FileOutputStream out = new FileOutputStream(f)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            return f.getPath();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonEmpty(String... s) {
        for (String x : s) if (x != null && !x.trim().isEmpty()) return x.trim();
        return null;
    }
}
