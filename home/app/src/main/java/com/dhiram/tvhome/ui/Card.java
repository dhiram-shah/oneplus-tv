package com.dhiram.tvhome.ui;

import android.graphics.drawable.Drawable;

/** One tile in a row, plus what the top shelf shows while it is focused. */
public class Card {

    /** Top shelf content. */
    public static class Hero {
        public String eyebrow;
        public String title;
        public CharSequence subtitle;
        public float progress = -1f;      // 0..1, or -1 for none
        public String progressText;
        public String art;                // URL or file path; null = today's landscape, dimmed
    }

    public String name;                   // label under the focused card
    public String image;                  // URL or file path
    public Drawable banner;               // app banner (preferred for app tiles)
    public Drawable icon;                 // app icon, or a white glyph for device/system tiles
    public boolean glyph;                 // icon is a monochrome glyph: smaller, with fallbackLabel
    public float progress = -1f;
    public Runnable onClick;
    public Runnable onLongClick;
    public Hero hero;
}
