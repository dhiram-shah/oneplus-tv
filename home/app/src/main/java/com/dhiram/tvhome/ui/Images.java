package com.dhiram.tvhome.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import com.dhiram.tvhome.App;
import com.dhiram.tvhome.data.Http;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;

/**
 * Small image loader: memory LRU (RGB_565, ~24 MB) over a disk cache in cacheDir/img.
 * Sources: http(s) URLs or absolute file paths.
 */
public class Images {

    public interface Callback {
        void onLoaded(Bitmap bmp);
    }

    private final File dir;
    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public Images(Context ctx) {
        dir = new File(ctx.getCacheDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** Loads into the view, guarding against recycled views via the view tag. */
    public void into(ImageView view, String src, int maxWidth) {
        view.setTag(src);
        if (src == null) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = mem.get(key(src, maxWidth));
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        load(src, maxWidth, bmp -> {
            if (src.equals(view.getTag()) && bmp != null) {
                view.setAlpha(0f);
                view.setImageBitmap(bmp);
                view.animate().alpha(1f).setDuration(220).start();
            }
        });
    }

    public Bitmap cached(String src, int maxWidth) {
        return src == null ? null : mem.get(key(src, maxWidth));
    }

    public void load(String src, int maxWidth, Callback cb) {
        String k = key(src, maxWidth);
        Bitmap hit = mem.get(k);
        if (hit != null) {
            cb.onLoaded(hit);
            return;
        }
        App app = App.get();
        app.io.execute(() -> {
            Bitmap bmp = null;
            try {
                File f;
                if (src.startsWith("http")) {
                    String diskKey = key(src, 0);   // one download per URL, whatever the decode width
                    f = new File(dir, diskKey);
                    if (!f.exists()) {
                        byte[] data = Http.get(src);
                        File tmp = new File(dir, diskKey + ".tmp");
                        try (FileOutputStream out = new FileOutputStream(tmp)) {
                            out.write(data);
                        }
                        //noinspection ResultOfMethodCallIgnored
                        tmp.renameTo(f);
                    }
                } else {
                    f = new File(src);
                }
                bmp = decode(f, maxWidth);
                // Full-width art (3.7 MB each) isn't kept: it re-decodes from disk in a blink.
                if (bmp != null && maxWidth < 1280) mem.put(k, bmp);
            } catch (Exception ignored) {
            }
            final Bitmap result = bmp;
            app.main.post(() -> cb.onLoaded(result));
        });
    }

    static Bitmap decode(File f, int maxWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        // Full-width art gets 8 bits per channel: RGB_565 bands visibly in dark gradients at that size.
        o.inPreferredConfig = maxWidth >= 1280 ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static String key(String src, int w) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((src + "@" + w).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((src + "@" + w).hashCode());
        }
    }
}
