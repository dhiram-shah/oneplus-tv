package com.dhiram.tvhome.data;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Minimal blocking HTTP GET. Call from a background thread. */
public final class Http {

    private Http() {
    }

    public static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/json, image/*");
        c.setRequestProperty("User-Agent", "tvhome/1 (living-room TV launcher)");
        c.setRequestProperty("AIC-User-Agent", "tvhome/1 (living-room TV launcher)");   // artic.edu IIIF 403s without it
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code + " for " + redact(url));
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    public static JSONObject json(String url) throws Exception {
        return new JSONObject(new String(get(url), "UTF-8"));
    }

    /** Never log API keys. */
    static String redact(String url) {
        return url.replaceAll("api_key=[^&]+", "api_key=***");
    }
}
