package com.dhiram.tvhome.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dhiram.tvhome.R;

/** Small helpers for views built in code. */
public final class Ui {

    private Ui() {
    }

    public static TextView text(Context ctx, String s, int sp, int color, int weight) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(ctx.getResources().getFont(R.font.inter));
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    /** The profile picture for {@code name} from config.xml (profile_photos), or null. */
    public static Drawable profilePhoto(Context ctx, String name) {
        String[] names = ctx.getResources().getStringArray(R.array.profile_photo_names);
        TypedArray photos = ctx.getResources().obtainTypedArray(R.array.profile_photos);
        try {
            for (int i = 0; i < names.length && i < photos.length(); i++) {
                if (names[i].trim().equalsIgnoreCase(name)) return photos.getDrawable(i);
            }
            return null;
        } finally {
            photos.recycle();
        }
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
