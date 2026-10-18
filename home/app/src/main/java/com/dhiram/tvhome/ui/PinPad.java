package com.dhiram.tvhome.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Predicate;

/**
 * Four-digit PIN entry for a remote with no number keys: a 3×4 on-screen keypad (number keys on
 * remotes that have them work too). The callback returns false to reject the PIN (shake + clear).
 */
public class PinPad {

    private final Activity activity;
    private final String title;
    private final String subtitle;
    private final Predicate<String> onPin;
    private Runnable onCancel;

    private final StringBuilder entered = new StringBuilder();
    private final View[] dots = new View[4];
    private LinearLayout dotRow;
    private Dialog dialog;
    private float d;

    public PinPad(Activity activity, String title, String subtitle, Predicate<String> onPin) {
        this.activity = activity;
        this.title = title;
        this.subtitle = subtitle;
        this.onPin = onPin;
    }

    public PinPad onCancel(Runnable r) {
        onCancel = r;
        return this;
    }

    public void show() {
        d = activity.getResources().getDisplayMetrics().density;
        dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0xFF000000));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.setOnCancelListener(x -> {
            if (onCancel != null) onCancel.run();
        });

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

        root.addView(Ui.text(activity, title, 22, Color.WHITE, 650), Ui.wrap());
        if (subtitle != null) {
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.topMargin = (int) (6 * d);
            root.addView(Ui.text(activity, subtitle, 12, 0x99FFFFFF, 450), lp);
        }

        dotRow = new LinearLayout(activity);
        dotRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            View dot = new View(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (12 * d), (int) (12 * d));
            lp.leftMargin = lp.rightMargin = (int) (7 * d);
            dotRow.addView(dot, lp);
            dots[i] = dot;
        }
        LinearLayout.LayoutParams dotsLp = Ui.wrap();
        dotsLp.topMargin = (int) (24 * d);
        dotsLp.bottomMargin = (int) (24 * d);
        root.addView(dotRow, dotsLp);
        paintDots();

        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕"};
        View five = null;
        for (String k : keys) {
            TextView key = Ui.text(activity, k, 18, Color.WHITE, 500);
            key.setGravity(Gravity.CENTER);
            key.setFocusable(true);
            key.setClickable(true);
            key.setBackground(keyBg());
            key.setOnFocusChangeListener((v, f) -> ((TextView) v).setTextColor(f ? Color.BLACK : Color.WHITE));
            key.setOnClickListener(v -> press(k));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = (int) (64 * d);
            lp.height = (int) (44 * d);
            lp.setMargins((int) (5 * d), (int) (5 * d), (int) (5 * d), (int) (5 * d));
            grid.addView(key, lp);
            if (k.equals("5")) five = key;
        }
        root.addView(grid, Ui.wrap());

        root.setFocusable(false);
        dialog.setOnKeyListener((dlg, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                press(String.valueOf(keyCode - KeyEvent.KEYCODE_0));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                press("⌫");
                return true;
            }
            return false;
        });
        dialog.setContentView(root);
        dialog.show();
        if (five != null) five.requestFocus();
    }

    private void press(String k) {
        if (k.equals("✕")) {
            dialog.cancel();
            return;
        }
        if (k.equals("⌫")) {
            if (entered.length() > 0) entered.setLength(entered.length() - 1);
            paintDots();
            return;
        }
        if (entered.length() >= 4) return;
        entered.append(k);
        paintDots();
        if (entered.length() == 4) {
            String pin = entered.toString();
            dotRow.postDelayed(() -> {
                if (onPin.test(pin)) {
                    dialog.dismiss();
                } else {
                    entered.setLength(0);
                    shake();
                    paintDots();
                }
            }, 120);
        }
    }

    private void shake() {
        dotRow.animate().translationX(14 * d).setDuration(50).withEndAction(() ->
                dotRow.animate().translationX(-12 * d).setDuration(70).withEndAction(() ->
                        dotRow.animate().translationX(6 * d).setDuration(60).withEndAction(() ->
                                dotRow.animate().translationX(0).setDuration(50).start()).start()).start()).start();
    }

    private void paintDots() {
        for (int i = 0; i < 4; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            if (i < entered.length()) g.setColor(Color.WHITE);
            else g.setStroke((int) (1.5f * d), 0x99FFFFFF);
            dots[i].setBackground(g);
        }
    }

    private StateListDrawable keyBg() {
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.WHITE);
        on.setCornerRadius(10 * d);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x1FFFFFFF);
        off.setCornerRadius(10 * d);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }
}
