package com.dhiram.tvhome.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.dhiram.tvhome.App;
import com.dhiram.tvhome.R;
import com.dhiram.tvhome.data.Photos;

import java.util.Objects;

/**
 * The top shelf: full-bleed artwork with the focused item's text over its lower left. Items without
 * artwork show today's landscape, dimmed, so the screen never falls back to a flat colour. Updates are
 * debounced so scrolling quickly through a row doesn't decode every backdrop on the way.
 */
public class Shelf {

    private static final long DEBOUNCE_MS = 160L;
    private static final float FALLBACK_DIM = 0.45f;

    private final Activity activity;
    private final ImageView art;
    private final View artFrame, dim, cut, browseScrim;
    private final View meta;
    private final TextView eyebrow, title, subtitle, progressText;
    private final View progressRow;
    private final ProgressBar progress;
    private final float density;

    private Card.Hero shown;
    private String shownSrc;
    private Card.Hero pending;
    private final Runnable apply = this::applyPending;

    public Shelf(Activity a) {
        activity = a;
        density = a.getResources().getDisplayMetrics().density;
        art = a.findViewById(R.id.heroArt);
        cut = a.findViewById(R.id.heroCut);
        browseScrim = a.findViewById(R.id.heroScrimBrowse);
        artFrame = a.findViewById(R.id.heroFrame);
        dim = a.findViewById(R.id.heroDim);
        meta = a.findViewById(R.id.heroMeta);
        eyebrow = a.findViewById(R.id.heroEyebrow);
        title = a.findViewById(R.id.heroTitle);
        subtitle = a.findViewById(R.id.heroSubtitle);
        progressRow = a.findViewById(R.id.heroProgressRow);
        progress = a.findViewById(R.id.heroProgress);
        progressText = a.findViewById(R.id.heroProgressText);
    }

    public void show(Card.Hero h) {
        if (h == null) return;
        pending = h;
        App.get().main.removeCallbacks(apply);
        App.get().main.postDelayed(apply, shown == null ? 0 : DEBOUNCE_MS);
    }

    /** Re-shows the current item, e.g. after today's photos arrive. */
    public void refresh() {
        Card.Hero h = shown;
        shown = null;
        shownSrc = null;
        if (h != null) show(h);
    }

    /** Deeper rows: the art takes the whole screen behind them. Top rows: it ends above the apps. */
    public void setBrowsing(boolean browsing) {
        cut.animate().alpha(browsing ? 0f : 1f).setDuration(420).start();
        browseScrim.animate().alpha(browsing ? 1f : 0f).setDuration(420).start();
    }

    /** Re-shows h if it is the item on screen, e.g. after its larger artwork arrives. */
    public void refreshIf(Card.Hero h) {
        if (h == shown) refresh();
    }

    private void applyPending() {
        Card.Hero h = pending;
        if (h == null || h == shown) return;
        shown = h;

        meta.animate().alpha(0f).translationX(-6 * density).setDuration(110).withEndAction(() -> {
            if (shown != h) return;
            setText(eyebrow, h.eyebrow);
            setText(title, h.title);
            setText(subtitle, h.subtitle);
            progressRow.setVisibility(h.progress >= 0 ? View.VISIBLE : View.GONE);
            if (h.progress >= 0) {
                progress.setProgress((int) (h.progress * 1000));
                progressText.setText(h.progressText);
            }
            meta.setTranslationX(6 * density);
            meta.animate().alpha(1f).translationX(0f).setDuration(220).start();
        }).start();

        boolean fallback = h.art == null;
        String src = h.art;
        if (fallback) {
            Photos.Photo p = Photos.current(activity);
            src = p != null ? p.path : null;
        }
        dim.animate().alpha(fallback ? FALLBACK_DIM : 0f).setDuration(300).start();
        if (Objects.equals(src, shownSrc) && src != null) return;
        shownSrc = src;
        if (src == null) {
            artFrame.animate().alpha(0f).setDuration(220).start();
            return;
        }
        artFrame.animate().alpha(1f).setDuration(220).start();
        final String want = src;
        // Full-width bitmaps aren't cached (Images), so each one is ours to free once it's off screen.
        App.get().images.load(want, 1280, bmp -> {
            if (!want.equals(shownSrc)) {
                if (bmp != null) bmp.recycle();
                return;
            }
            if (bmp == null) {
                art.animate().alpha(0f).setDuration(200).start();
                return;
            }
            art.animate().alpha(0f).setDuration(art.getDrawable() == null ? 0 : 140).withEndAction(() -> {
                if (!want.equals(shownSrc)) {
                    bmp.recycle();
                    return;
                }
                Bitmap old = art.getDrawable() instanceof BitmapDrawable ? ((BitmapDrawable) art.getDrawable()).getBitmap() : null;
                art.setImageBitmap(bmp);
                if (old != null && old != bmp) old.recycle();
                art.animate().alpha(1f).setDuration(380).start();
            }).start();
        });
    }

    private static void setText(TextView v, CharSequence s) {
        v.setText(s);
        v.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
    }
}
