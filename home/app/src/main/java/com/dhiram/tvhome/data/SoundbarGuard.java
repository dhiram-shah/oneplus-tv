package com.dhiram.tvhome.data;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.dhiram.tvhome.R;

/**
 * Keeps the TV speakers silent while the optical soundbar plays. The optical output ignores the TV
 * volume, so music volume 0 silences only the panel. Volume keys are swallowed (they could only wake
 * the TV speakers; the soundbar has its own remote) and any other volume change, e.g. over HDMI-CEC
 * from the Fire TV, is put back to 0. Off when config soundbar_optical is false.
 * Enabled over adb (build-and-install.sh): settings put secure enabled_accessibility_services …
 */
public class SoundbarGuard extends AccessibilityService {

    private static final String TAG = "SoundbarGuard";
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";

    private AudioManager audio;
    private final BroadcastReceiver volumeChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            silence();
        }
    };

    @Override
    protected void onServiceConnected() {
        if (!getResources().getBoolean(R.bool.soundbar_optical)) {
            disableSelf();
            return;
        }
        audio = getSystemService(AudioManager.class);
        registerReceiver(volumeChanged, new IntentFilter(VOLUME_CHANGED));
        silence();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return true;
            default:
                return false;
        }
    }

    private void silence() {
        if (audio != null && audio.getStreamVolume(AudioManager.STREAM_MUSIC) != 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            Log.i(TAG, "TV speakers back to 0");
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (audio != null) unregisterReceiver(volumeChanged);
        super.onDestroy();
    }
}
