package com.dhiram.tvhome.data;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import com.dhiram.tvhome.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HDMI ports and what is plugged into them. Devices announce their name over HDMI-CEC
 * ("Amazon FireTV", "Xbox Series X"); the TV exposes each as a child input of its port.
 */
public final class Devices {

    public static final class Device {
        public String inputId;     // CEC child input when known (switching to it also wakes the device)
        public String name;        // "Fire TV", or "HDMI 2" when nothing announced itself
        public String port;        // "HDMI 1"
        public int icon;
        public boolean named;      // true when the name came from the device
        public int state;          // TvInputManager.INPUT_STATE_*
        public boolean hdmi;
    }

    private Devices() {
    }

    public static List<Device> list(Context ctx) {
        List<Device> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> all = tim.getTvInputList();
        Map<String, TvInputInfo> childByParent = new HashMap<>();
        for (TvInputInfo i : all) if (i.getParentId() != null) childByParent.put(i.getParentId(), i);

        for (TvInputInfo i : all) {
            if (!i.isPassthroughInput() || i.getParentId() != null) continue;
            Device d = new Device();
            d.hdmi = i.getType() == TvInputInfo.TYPE_HDMI;
            CharSequence l = i.loadLabel(ctx);
            String port = l == null ? i.getId() : l.toString();
            if (d.hdmi && !port.toUpperCase(Locale.ROOT).contains("HDMI")) port = "HDMI " + port;
            d.port = port;
            d.state = tim.getInputState(i.getId());
            TvInputInfo child = childByParent.get(i.getId());
            CharSequence cl = child != null ? child.loadLabel(ctx) : null;
            if (cl != null && cl.length() > 0) {
                d.inputId = child.getId();
                d.name = prettyName(cl.toString());
                d.named = true;
                d.state = TvInputManager.INPUT_STATE_CONNECTED;
            } else {
                d.inputId = i.getId();
                d.name = port;
            }
            d.icon = iconFor(d.named ? cl.toString() : "", d.hdmi);
            out.add(d);
        }
        Collections.sort(out, (a, b) -> {
            if (a.hdmi != b.hdmi) return a.hdmi ? -1 : 1;
            return a.port.compareToIgnoreCase(b.port);
        });
        return out;
    }

    /** Ports with something on them: a device that announced its name, or one the TV sees (on or asleep). */
    public static List<Device> plugged(Context ctx) {
        List<Device> out = new ArrayList<>();
        for (Device d : list(ctx)) {
            if (d.named || d.state == TvInputManager.INPUT_STATE_CONNECTED
                    || d.state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) out.add(d);
        }
        return out;
    }

    static String prettyName(String cec) {
        String n = cec.toLowerCase(Locale.ROOT);
        if (n.contains("fire")) return "Fire TV";
        if (n.contains("xbox")) return "Xbox";
        if (n.contains("playstation") || n.startsWith("ps")) return "PlayStation";
        if (n.contains("apple")) return "Apple TV";
        if (n.contains("chromecast")) return "Chromecast";
        if (n.contains("switch") || n.contains("nintendo")) return "Nintendo Switch";
        if (n.contains("roku")) return "Roku";
        return cec;
    }

    static int iconFor(String cec, boolean hdmi) {
        String n = cec.toLowerCase(Locale.ROOT);
        if (n.contains("fire") || n.contains("amazon")) return R.drawable.logo_amazonfiretv;
        if (n.contains("xbox")) return R.drawable.logo_xbox;
        if (n.contains("playstation") || n.startsWith("ps")) return R.drawable.logo_playstation;
        if (n.contains("apple")) return R.drawable.logo_appletv;
        if (n.contains("chromecast") || n.contains("google")) return R.drawable.logo_chromecast;
        if (n.contains("switch") || n.contains("nintendo")) return R.drawable.logo_nintendoswitch;
        if (n.contains("roku")) return R.drawable.logo_roku;
        return hdmi ? R.drawable.ic_input : R.drawable.ic_av;
    }
}
