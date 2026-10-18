package com.dhiram.tvhome.data;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Today's and tomorrow's events from the calendars the TV's Google account already syncs.
 * Needs READ_CALENDAR (granted over adb). Returns an empty list without it.
 */
public final class CalendarRepo {

    public static final class Event {
        public String title;
        public String calendar;
        public int color;
        public long begin, end;
        public boolean allDay;

        public boolean isNow(long now) {
            return !allDay && begin <= now && now < end;
        }
    }

    private CalendarRepo() {
    }

    /** Events from now until the end of tomorrow, for the profile's calendars. Blocking; call off the UI thread. */
    public static List<Event> upcoming(Context ctx, Profiles profiles, Profiles.Profile p) {
        List<Event> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        Calendar end = Calendar.getInstance();
        end.add(Calendar.DAY_OF_YEAR, 1);
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        Calendar startOfDay = Calendar.getInstance();
        startOfDay.set(Calendar.HOUR_OF_DAY, 0);
        startOfDay.set(Calendar.MINUTE, 0);

        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, startOfDay.getTimeInMillis());
        ContentUris.appendId(b, end.getTimeInMillis());
        String[] proj = {
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        };
        try (Cursor c = ctx.getContentResolver().query(b.build(), proj,
                CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue;
                Event e = new Event();
                e.title = c.getString(0);
                e.calendar = c.getString(1);
                e.color = c.getInt(2) | 0xFF000000;
                e.begin = c.getLong(3);
                e.end = c.getLong(4);
                e.allDay = c.getInt(5) == 1;
                if (e.allDay) {
                    // All-day instances are UTC midnights; re-anchor them to local midnights of the same
                    // dates, otherwise yesterday's all-day event overlaps this morning in IST.
                    e.begin = localMidnightOfUtcDate(e.begin);
                    e.end = localMidnightOfUtcDate(e.end);
                    if (e.end <= startOfDay.getTimeInMillis()) continue;
                }
                if (e.end <= now && !e.allDay) continue;   // already over
                if (!profiles.showsCalendar(p, e.calendar)) continue;
                if (e.title == null || e.title.trim().isEmpty()) e.title = "Busy";
                out.add(e);
            }
        } catch (SecurityException ignored) {
        }
        return out;
    }

    static long localMidnightOfUtcDate(long utcMillis) {
        java.time.LocalDate d = java.time.Instant.ofEpochMilli(utcMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** The next timed event that hasn't ended, or null. */
    public static Event next(List<Event> events, long now) {
        for (Event e : events) if (!e.allDay && e.end > now) return e;
        return null;
    }
}
