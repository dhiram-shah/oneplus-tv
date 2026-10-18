package com.dhiram.tvhome.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dhiram.tvhome.R;
import com.dhiram.tvhome.data.Profiles;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Who's watching?": one avatar per profile, a lock on PIN-protected ones, and "Add Profile" while
 * there's room (max 4). Hold OK (or press Menu) on a profile to rename it, set or remove its PIN,
 * or delete it. When {@code required}, Back can't dismiss it, so a locked profile can't be skipped.
 */
public class ProfilePicker {

    private static final int INK = 0xFFF5F6F8;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    private final Activity activity;
    private final Profiles profiles;
    private final Consumer<Profiles.Profile> onPick;
    private final boolean required;
    private Dialog dialog;
    private float d;
    private Runnable onClose;

    /** Called however the picker closes (profiles may have been renamed or deleted meanwhile). */
    public ProfilePicker onClose(Runnable r) {
        onClose = r;
        return this;
    }

    public ProfilePicker(Activity activity, Profiles profiles, boolean required, Consumer<Profiles.Profile> onPick) {
        this.activity = activity;
        this.profiles = profiles;
        this.required = required;
        this.onPick = onPick;
    }

    public void show() {
        d = activity.getResources().getDisplayMetrics().density;
        dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setCancelable(!required);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(activity.getColor(R.color.bg)));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.setOnDismissListener(x -> {
            if (onClose != null) onClose.run();
        });
        build();
        dialog.show();
    }

    private void build() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.addView(Ui.text(activity, activity.getString(R.string.whos_watching), 28, INK, 600), Ui.wrap());

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = Ui.wrap();
        rowLp.topMargin = (int) (40 * d);
        root.addView(row, rowLp);

        View focus = null;
        Profiles.Profile active = profiles.active();
        for (Profiles.Profile p : profiles.all()) {
            View cell = cell(p.initial(), p.name, p.color, p.locked(), false, Ui.profilePhoto(activity, p.name));
            cell.setOnClickListener(v -> choose(p));
            cell.setOnLongClickListener(v -> {
                manage(p);
                return true;
            });
            cell.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                    manage(p);
                    return true;
                }
                return false;
            });
            row.addView(cell, cellLp());
            if (p.id.equals(active.id)) focus = cell;
        }
        if (profiles.canAdd()) {
            View add = cell("+", "Add Profile", 0, false, true, null);
            add.setOnClickListener(v -> addProfile());
            row.addView(add, cellLp());
        }

        LinearLayout.LayoutParams hintLp = Ui.wrap();
        hintLp.topMargin = (int) (48 * d);
        root.addView(Ui.text(activity, "Hold OK on a profile to rename it, lock it with a PIN, or remove it",
                12, 0x73F5F6F8, 450), hintLp);

        dialog.setContentView(root);
        if (focus != null) focus.requestFocus();
    }

    private LinearLayout.LayoutParams cellLp() {
        // Fixed width: every face the same size and every name on the same line, whatever its length.
        return new LinearLayout.LayoutParams((int) (136 * d), LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private View cell(String initial, String name, int color, boolean locked, boolean isAdd, Drawable photo) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);   // the focused face grows 10% past the padding
        int pad = (int) (10 * d);
        cell.setPadding(0, pad, 0, pad);

        // A 96dp face inside a ring that only shows on focus; the ring sits 4dp out so it never
        // changes the face's size.
        TextView face = Ui.text(activity, photo != null ? "" : initial, isAdd ? 36 : 34, isAdd ? 0xB3F5F6F8 : INK, isAdd ? 300 : 600);
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        if (isAdd) fill.setStroke((int) (1.5f * d), 0x59F5F6F8);
        else fill.setColor(color);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke((int) (2.5f * d), INK);
        ring.setAlpha(0);
        LayerDrawable bg = new LayerDrawable(new Drawable[]{photo != null ? photo : fill, ring});
        int inset = (int) (6 * d);
        bg.setLayerInset(0, inset, inset, inset, inset);
        face.setBackground(bg);
        face.setGravity(Gravity.CENTER);
        int size = (int) (96 * d) + 2 * inset;
        cell.addView(face, new LinearLayout.LayoutParams(size, size));

        TextView label = Ui.text(activity, name, 14, 0x99F5F6F8, 500);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * d);
        cell.addView(label, lp);

        // Same height whether or not the profile is locked, so names stay on one line.
        ImageView lock = new ImageView(activity);
        lock.setImageResource(R.drawable.ic_lock);
        lock.setAlpha(0.5f);
        lock.setVisibility(locked ? View.VISIBLE : View.INVISIBLE);
        int ls = (int) (12 * d);
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(ls, ls);
        lockLp.topMargin = (int) (6 * d);
        cell.addView(lock, lockLp);

        cell.setOnFocusChangeListener((v, f) -> {
            float s = f ? 1.1f : 1f;
            face.animate().scaleX(s).scaleY(s).setDuration(180).setInterpolator(EASE).start();
            ring.setAlpha(f ? 255 : 0);
            label.setTextColor(f ? INK : 0x99F5F6F8);
            if (isAdd) fill.setStroke((int) (1.5f * d), f ? INK : 0x59F5F6F8);
        });
        return cell;
    }

    // ---- actions ---------------------------------------------------------------------------------

    private void choose(Profiles.Profile p) {
        withPin(p, "Enter PIN for " + p.name, () -> {
            dialog.dismiss();
            onPick.accept(p);
        });
    }

    /** Runs {@code then} immediately for unlocked profiles, or after the right PIN. */
    private void withPin(Profiles.Profile p, String title, Runnable then) {
        if (!p.locked()) {
            then.run();
            return;
        }
        new PinPad(activity, title, null, pin -> {
            if (!profiles.checkPin(p, pin)) return false;
            then.run();
            return true;
        }).show();
    }

    private void manage(Profiles.Profile p) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!p.guest) {
            labels.add("Rename");
            actions.add(() -> askName("Rename profile", p.name, name -> {
                profiles.rename(p, name);
                build();
            }));
            labels.add(p.locked() ? "Change PIN" : "Lock with a PIN");
            actions.add(() -> setPin(p));
            if (p.locked()) {
                labels.add("Remove PIN");
                actions.add(() -> {
                    profiles.setPin(p, null);
                    build();
                });
            }
        }
        if (profiles.canDelete(p)) {
            labels.add("Delete profile");
            actions.add(() -> confirm("Delete " + p.name + "?", "Its app history and Continue Watching go with it.", () -> {
                profiles.delete(p);
                build();
            }));
        }
        if (labels.isEmpty()) return;
        withPin(p, "Enter PIN to manage " + p.name, () ->
                new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(p.name)
                        .setItems(labels.toArray(new String[0]), (dlg, which) -> actions.get(which).run())
                        .show());
    }

    private void setPin(Profiles.Profile p) {
        new PinPad(activity, "Choose a 4-digit PIN", "for " + p.name, first -> {
            new PinPad(activity, "Enter it again", null, second -> {
                if (!second.equals(first)) return false;
                profiles.setPin(p, second);
                build();
                return true;
            }).show();
            return true;
        }).show();
    }

    private void addProfile() {
        askName("New profile", "", name -> {
            Profiles.Profile p = profiles.add(name);
            if (p == null) return;
            build();
            confirm("Lock " + p.name + " with a PIN?", "You can change this later by holding OK on the profile.",
                    () -> setPin(p));
        });
    }

    // ---- small dialogs ---------------------------------------------------------------------------

    private void askName(String title, String current, Consumer<String> onName) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setSingleLine(true);
        input.setText(current);
        input.setSelection(current.length());
        AlertDialog dlg = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Save", (x, y) -> {
                    String n = input.getText().toString().trim();
                    if (!n.isEmpty()) onName.accept(n.length() > 16 ? n.substring(0, 16) : n);
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnShowListener(x -> {
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            input.postDelayed(() -> imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT), 200);
        });
        dlg.show();
    }

    private void confirm(String title, String message, Runnable yes) {
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Yes", (x, y) -> yes.run())
                .setNegativeButton("Not now", null)
                .show();
    }
}
