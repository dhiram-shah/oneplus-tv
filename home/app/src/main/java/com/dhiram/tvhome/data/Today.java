package com.dhiram.tvhome.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Everything a profile needs to know about its day: merged calendars, inboxes and tasks. */
public final class Today {

    public final List<CalendarRepo.Event> events = new ArrayList<>();
    public final List<Feed.Account> accounts = new ArrayList<>();
    public final List<Feed.Task> tasks = new ArrayList<>();

    /** Blocking; call off the UI thread. */
    public static Today load(Context ctx, Profiles profiles, Profiles.Profile p) {
        Today t = new Today();
        t.accounts.addAll(Feed.accounts(p.id));

        // TV-synced calendars plus each feed's calendars; the same event can arrive both ways.
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, 2);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        long horizon = c.getTimeInMillis();
        Set<String> seen = new HashSet<>();
        List<CalendarRepo.Event> all = new ArrayList<>(CalendarRepo.upcoming(ctx, profiles, p));
        for (Feed.Account a : t.accounts) all.addAll(a.events);
        for (CalendarRepo.Event e : all) {
            if (e.begin >= horizon || (!e.allDay && e.end <= now)) continue;
            String key = e.title.trim().toLowerCase(Locale.ROOT) + "|" + (e.allDay ? dayOf(e.begin) : e.begin / 60_000L);
            if (seen.add(key)) t.events.add(e);
        }
        Collections.sort(t.events, (a, b) -> {
            if (a.allDay != b.allDay && dayOf(a.begin) == dayOf(b.begin)) return a.allDay ? -1 : 1;
            return Long.compare(a.begin, b.begin);
        });

        for (Feed.Account a : t.accounts) t.tasks.addAll(a.tasks);
        Collections.sort(t.tasks, (a, b) -> {
            if (a.due == 0) return b.due == 0 ? 0 : 1;
            if (b.due == 0) return -1;
            return Long.compare(a.due, b.due);
        });
        return t;
    }

    public int unread() {
        int n = 0;
        for (Feed.Account a : accounts) n += Math.max(0, a.unread);
        return n;
    }

    public int important() {
        int n = 0;
        for (Feed.Account a : accounts) n += Math.max(0, a.important);
        return n;
    }

    /** Flent numbers from whichever account publishes them, or null. */
    public Feed.Metrics metrics() {
        for (Feed.Account a : accounts) if (a.metrics != null) return a.metrics;
        return null;
    }

    public boolean hasMail() {
        for (Feed.Account a : accounts) if (a.unread >= 0) return true;
        return false;
    }

    /** Timed events still to come (or in progress) today. */
    public List<CalendarRepo.Event> remainingToday() {
        List<CalendarRepo.Event> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        int today = dayOf(now);
        for (CalendarRepo.Event e : events) if (!e.allDay && e.end > now && dayOf(e.begin) == today) out.add(e);
        return out;
    }

    /** Tasks due today or overdue (Google Tasks due dates are midnight UTC of the day). */
    public int dueToday() {
        int n = 0;
        long endOfToday = System.currentTimeMillis() + 24 * 3600_000L;
        for (Feed.Task t : tasks) if (t.due > 0 && t.due < endOfToday) n++;
        return n;
    }

    public static int dayOf(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }
}
