package com.dhiram.tvhome.data;

import android.content.Context;

import com.dhiram.tvhome.App;
import com.dhiram.tvhome.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Current conditions, next hours and air quality from Open-Meteo (free, no key). Cached 30 min. */
public final class Weather {

    public static final class Hour {
        public String time;   // "14:00"
        public int temp;
        public int code;
        public int rainChance;
    }

    public static final class Report {
        public String city;
        public int temp;
        public int feelsLike;
        public int code;
        public boolean day;
        public int high, low;
        public int humidity;
        public int aqi = -1;
        public final List<Hour> hours = new ArrayList<>();

        public String condition() {
            return Weather.condition(code);
        }

        public String symbol() {
            return Weather.symbol(code, day);
        }
    }

    private static final long TTL_MS = 30 * 60_000L;
    private static Report cached;
    private static long cachedAt;

    private Weather() {
    }

    public static Report cached() {
        return cached;
    }

    /** Calls back on the main thread; with the cached report immediately if fresh. */
    public static void fetch(Context ctx, Consumer<Report> cb) {
        if (cached != null && System.currentTimeMillis() - cachedAt < TTL_MS) {
            cb.accept(cached);
            return;
        }
        App app = App.get();
        app.io.execute(() -> {
            Report r = null;
            String[] loc = location(ctx);
            String city = loc[0], lat = loc[1], lon = loc[2];
            try {
                JSONObject o = Http.json("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day"
                        + "&hourly=temperature_2m,weather_code,precipitation_probability"
                        + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=2");
                r = new Report();
                r.city = city;
                JSONObject cur = o.getJSONObject("current");
                r.temp = (int) Math.round(cur.getDouble("temperature_2m"));
                r.feelsLike = (int) Math.round(cur.getDouble("apparent_temperature"));
                r.humidity = cur.optInt("relative_humidity_2m");
                r.code = cur.getInt("weather_code");
                r.day = cur.optInt("is_day", 1) == 1;
                JSONObject daily = o.getJSONObject("daily");
                r.high = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
                r.low = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
                JSONObject hourly = o.getJSONObject("hourly");
                JSONArray times = hourly.getJSONArray("time");
                String nowHour = cur.getString("time").substring(0, 13);   // yyyy-MM-ddTHH
                int start = 0;
                for (int i = 0; i < times.length(); i++) {
                    if (times.getString(i).startsWith(nowHour)) {
                        start = i + 1;
                        break;
                    }
                }
                for (int i = start; i < Math.min(times.length(), start + 6); i++) {
                    Hour h = new Hour();
                    h.time = times.getString(i).substring(11, 16);
                    h.temp = (int) Math.round(hourly.getJSONArray("temperature_2m").getDouble(i));
                    h.code = hourly.getJSONArray("weather_code").getInt(i);
                    h.rainChance = hourly.getJSONArray("precipitation_probability").optInt(i);
                    r.hours.add(h);
                }
                try {
                    JSONObject aq = Http.json("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat
                            + "&longitude=" + lon + "&current=us_aqi");
                    r.aqi = aq.getJSONObject("current").optInt("us_aqi", -1);
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                r = cached;   // keep showing the last good report when offline
            }
            final Report result = r;
            if (r != null && r != cached) {
                cached = r;
                cachedAt = System.currentTimeMillis();
            }
            app.main.post(() -> cb.accept(result));
        });
    }

    private static String[] place;
    private static long placeAt;

    /** {city, lat, lon} from the connection's IP (ipwho.is, HTTPS, no key), cached a day; config fallback. */
    static synchronized String[] location(Context ctx) {
        if (place != null && System.currentTimeMillis() - placeAt < 24 * 3600_000L) return place;
        try {
            JSONObject o = Http.json("https://ipwho.is/?fields=success,city,latitude,longitude");
            if (o.optBoolean("success") && o.has("latitude")) {
                place = new String[]{o.optString("city", ctx.getString(R.string.weather_city)),
                        String.valueOf(o.getDouble("latitude")), String.valueOf(o.getDouble("longitude"))};
                placeAt = System.currentTimeMillis();
                return place;
            }
        } catch (Exception ignored) {
        }
        return new String[]{ctx.getString(R.string.weather_city), ctx.getString(R.string.weather_lat),
                ctx.getString(R.string.weather_lon)};
    }

    /** WMO weather interpretation codes. */
    public static String condition(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }

    public static String symbol(int code, boolean day) {
        if (code == 0) return day ? "☀︎" : "☾";
        if (code <= 2) return day ? "⛅︎" : "☁︎";
        if (code == 3 || code == 45 || code == 48) return "☁︎";
        if (code >= 95) return "⚡︎";
        if (code >= 71 && code <= 77) return "❄︎";
        return "☂︎";
    }

    public static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.wx_sunny : R.drawable.wx_clear_night;
        if (code <= 2) return day ? R.drawable.wx_partly_cloudy_day : R.drawable.wx_clear_night;
        if (code == 3) return R.drawable.wx_cloud;
        if (code == 45 || code == 48) return R.drawable.wx_foggy;
        if (code >= 95) return R.drawable.wx_thunderstorm;
        if (code >= 71 && code <= 77) return R.drawable.wx_weather_snowy;
        return R.drawable.wx_rainy;
    }

    public static String aqiLabel(int aqi) {
        if (aqi < 0) return "";
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Moderate";
        if (aqi <= 150) return "Unhealthy for some";
        if (aqi <= 200) return "Unhealthy";
        return "Very unhealthy";
    }
}
