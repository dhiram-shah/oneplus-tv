package com.dhiram.tvhome.ui;

import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.dhiram.tvhome.App;
import com.dhiram.tvhome.R;

import java.util.ArrayList;
import java.util.List;

/** Cards for one row. All cards in a row share a size. */
public class CardAdapter extends RecyclerView.Adapter<CardAdapter.Holder> {

    public interface Listener {
        void onCardFocused(int row, Card card, View view);
    }

    static final float FOCUS_SCALE = 1.1f;

    private final List<Card> cards = new ArrayList<>();
    private final int row;
    private final int widthPx, heightPx;
    private final Listener listener;
    private boolean compact;

    public CardAdapter(int row, int widthPx, int heightPx, Listener listener) {
        this.row = row;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.listener = listener;
    }

    /** A short row of glyph tiles: the glyph sits beside its label, and less room is kept below. */
    public CardAdapter compact() {
        compact = true;
        return this;
    }

    public void set(List<Card> fresh) {
        cards.clear();
        cards.addAll(fresh);
        notifyDataSetChanged();
    }

    public Card get(int i) {
        return i >= 0 && i < cards.size() ? cards.get(i) : null;
    }

    static View cardOf(View item) {
        return item.findViewById(R.id.card);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_card, parent, false);
        ViewGroup.LayoutParams lp = v.findViewById(R.id.card).getLayoutParams();
        lp.width = widthPx;
        lp.height = heightPx;
        TextView name = v.findViewById(R.id.name);
        name.setMaxWidth((int) (widthPx * 1.3f));
        if (compact) v.setPaddingRelative(v.getPaddingStart(), v.getPaddingTop(), v.getPaddingEnd(), v.getPaddingTop());
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Card c = cards.get(position);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        h.card.setTranslationZ(0f);
        h.name.setAlpha(0f);
        // The shelf above already names the focused card; a caption would repeat it.
        h.name.setText(null);

        if (c.banner != null) {
            h.image.setTag(null);
            h.image.setImageDrawable(c.banner);
            h.icon.setVisibility(View.GONE);
            h.fallback.setVisibility(View.GONE);
        } else if (c.image != null) {
            App.get().images.into(h.image, c.image, widthPx);
            h.icon.setVisibility(View.GONE);
            h.fallback.setVisibility(View.GONE);
        } else {
            // Device, system and banner-less app tiles: a glyph (or app icon) over the label.
            h.image.setTag(null);
            h.image.setImageDrawable(null);
            if (compact) {
                h.icon.setVisibility(View.GONE);
                Drawable d = c.icon;
                if (d != null) {
                    int ih = (int) (16 * h.density);
                    int iw = d.getIntrinsicHeight() > 0 ? ih * d.getIntrinsicWidth() / d.getIntrinsicHeight() : ih;
                    d.setBounds(0, 0, Math.min(iw, 3 * ih), ih);
                }
                h.fallback.setCompoundDrawablesRelative(d, null, null, null);
                h.fallback.setCompoundDrawablePadding((int) (8 * h.density));
                h.fallback.setLayoutParams(compactLabel(h.fallback.getLayoutParams()));
                h.fallback.setPadding(0, 0, 0, 0);
                h.fallback.setMaxWidth(widthPx - (int) (20 * h.density));
                h.fallback.setTextSize(11);
                h.fallback.setText(c.name);
                h.fallback.setVisibility(View.VISIBLE);
                h.scrim.setVisibility(View.GONE);
                h.progress.setVisibility(View.GONE);
                bindClicks(h, c);
                return;
            }
            h.icon.setImageDrawable(c.icon);
            ViewGroup.LayoutParams ilp = h.icon.getLayoutParams();
            ilp.width = ilp.height = (int) ((c.glyph ? 26 : 34) * h.density);
            h.icon.setLayoutParams(ilp);
            h.icon.setVisibility(c.icon != null ? View.VISIBLE : View.GONE);
            h.fallback.setText(c.name);
            h.fallback.setVisibility(View.VISIBLE);
        }

        h.scrim.setVisibility(c.progress >= 0 ? View.VISIBLE : View.GONE);
        h.progress.setVisibility(c.progress >= 0 ? View.VISIBLE : View.GONE);
        if (c.progress >= 0) h.progress.setProgress((int) (c.progress * 1000));

        bindClicks(h, c);
    }

    private static void bindClicks(Holder h, Card c) {
        h.card.setOnClickListener(v -> {
            if (c.onClick != null) c.onClick.run();
        });
        h.card.setOnLongClickListener(v -> {
            if (c.onLongClick == null) return false;
            c.onLongClick.run();
            return true;
        });
    }

    private static ViewGroup.LayoutParams compactLabel(ViewGroup.LayoutParams lp) {
        FrameLayout.LayoutParams f = new FrameLayout.LayoutParams(lp);
        f.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        f.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        f.gravity = Gravity.CENTER;
        return f;
    }

    @Override
    public int getItemCount() {
        return cards.size();
    }

    final class Holder extends RecyclerView.ViewHolder {
        final View card, scrim;
        final ImageView image, icon;
        final TextView name, fallback;
        final ProgressBar progress;
        final float density;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            scrim = v.findViewById(R.id.scrim);
            image = v.findViewById(R.id.image);
            icon = v.findViewById(R.id.icon);
            name = v.findViewById(R.id.name);
            fallback = v.findViewById(R.id.fallbackLabel);
            progress = v.findViewById(R.id.progress);

            density = v.getResources().getDisplayMetrics().density;
            final float radius = 10f * density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            // Lift, no bounce: scale + shadow with a quick ease-out.
            DecelerateInterpolator easeOut = new DecelerateInterpolator(2.2f);
            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1f;
                view.animate().scaleX(s).scaleY(s).translationZ(focused ? 14 * density : 0f)
                        .setDuration(focused ? 200 : 150).setInterpolator(easeOut).start();
                name.animate().alpha(focused ? 1f : 0f).setDuration(180).start();
                int pos = getBindingAdapterPosition();
                if (focused && pos != RecyclerView.NO_POSITION && listener != null) {
                    listener.onCardFocused(row, cards.get(pos), view);
                }
            });
        }
    }
}
