package com.dhiram.tvhome.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** A ScrollView that never scrolls by itself on focus changes; MainActivity positions rows explicitly. */
public class RowsScrollView extends ScrollView {

    public RowsScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setSmoothScrollingEnabled(true);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }
}
