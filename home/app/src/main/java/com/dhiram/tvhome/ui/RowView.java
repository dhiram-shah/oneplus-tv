package com.dhiram.tvhome.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

/**
 * A horizontal row that remembers its last focused card: moving Up/Down into the row lands on that
 * card instead of whichever card is geometrically closest.
 */
public class RowView extends RecyclerView {

    private int remembered = 0;

    public RowView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutManager(new LinearLayoutManager(context, HORIZONTAL, false));
        setItemAnimator(null);
        setHasFixedSize(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        int pos = getChildAdapterPosition(child);
        if (pos != NO_POSITION) remembered = pos;
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (!hasFocus() && (direction == FOCUS_UP || direction == FOCUS_DOWN)) {
            ViewHolder vh = findViewHolderForAdapterPosition(remembered);
            if (vh == null) vh = findViewHolderForAdapterPosition(0);
            if (vh != null) {
                View card = CardAdapter.cardOf(vh.itemView);
                if (card != null && card.isFocusable()) {
                    views.add(card);
                    return;
                }
            }
        }
        super.addFocusables(views, direction, focusableMode);
    }

    /** Focuses the remembered card, after the next layout if it isn't laid out yet (e.g. just reset). */
    public void focusRemembered() {
        if (tryFocus()) return;
        scrollToPosition(remembered);
        getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                getViewTreeObserver().removeOnGlobalLayoutListener(this);
                tryFocus();
            }
        });
    }

    private boolean tryFocus() {
        ViewHolder vh = findViewHolderForAdapterPosition(remembered);
        if (vh == null) return false;
        View card = CardAdapter.cardOf(vh.itemView);
        return card != null && card.requestFocus();
    }

    /** Adapter position of the card holding {@code focused}, or NO_POSITION. */
    public int positionOf(View focused) {
        View item = findContainingItemView(focused);
        return item == null ? NO_POSITION : getChildAdapterPosition(item);
    }

    public int count() {
        Adapter<?> a = getAdapter();
        return a == null ? 0 : a.getItemCount();
    }

    public int rememberedPosition() {
        return remembered;
    }

    /** Lands on {@code pos} next time (clamped to the row), without scrolling now. */
    public void remember(int pos) {
        remembered = Math.max(0, Math.min(pos, count() - 1));
    }

    public void resetPosition() {
        remembered = 0;
        scrollToPosition(0);
    }
}
