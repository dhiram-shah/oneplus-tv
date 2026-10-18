/**
 * TV feed: publishes this Google account's unread mail, today's + tomorrow's events and open tasks
 * as JSON for the living-room TV. Runs inside your own account; the TV only sees this JSON.
 *
 * Setup (once per account, ~2 min). Settings below can be script properties or a Config.gs (see prop()):
 *   1. script.google.com → New project → paste this file.
 *   2. Services (+) → add "Google Tasks API".
 *   3. Project Settings → Script properties → TOKEN = a long random string.
 *   4. Deploy → New deployment → Web app → Execute as: Me, Who has access: Anyone → Deploy.
 *   5. Give the Web app URL + "?k=<TOKEN>" to the TV (home/local.properties, feed.<profile>=…).
 *
 * Flent account only: also set script properties SUPABASE_URL (https://<project>.supabase.co),
 * SUPABASE_SERVICE_KEY (the secret key; RLS hides rows from the anon key) and the three PostgREST
 * queries, kept out of this file:
 *   OCCUPANCY_PATH  one row whose `data.counts` has `rooms` and `occupied`
 *   WINS_PATH       one row per win, ending in `<date column>=gte.` (the start date is appended)
 *   VISITORS_PATH   daily rows with a `visitors` column, ending in `<date column>=gte.`
 *   OCCUPANCY_MONTH_PATH  one row with `cur_month` ("2026-10") and `units[].months[]` of
 *                   {m, occ_rd, avail_rd}: occupancy as the dashboards define it, occupied
 *                   room-days / available room-days this month (optional)
 * The feed then adds "flent": occupancy, wins this month/week and website visitors. The key stays
 * in this script; the TV only ever receives the five numbers.
 */

var CACHE_SECONDS = 240;
var TOP_MAIL = 5;

function doGet(e) {
  var token = prop('TOKEN');
  if (!token || !e || !e.parameter || e.parameter.k !== token) {
    return json({ error: 'unauthorized' });
  }
  var cache = CacheService.getScriptCache();
  var hit = cache.get('feed');
  if (hit) return ContentService.createTextOutput(hit).setMimeType(ContentService.MimeType.JSON);

  var out = {
    account: Session.getEffectiveUser().getEmail(),
    generated: new Date().toISOString(),
    mail: safe(mail),
    events: safe(events),
    tasks: safe(tasks)
  };
  if (prop('SUPABASE_SERVICE_KEY')) out.flent = safe(flent);
  var body = JSON.stringify(out);
  cache.put('feed', body, CACHE_SECONDS);
  return ContentService.createTextOutput(body).setMimeType(ContentService.MimeType.JSON);
}

function mail() {
  var important = GmailApp.search('in:inbox is:unread is:important', 0, TOP_MAIL);
  var top = important.map(function (t) {
    var m = t.getMessages()[t.getMessageCount() - 1];
    return {
      from: senderName(m.getFrom()),
      subject: t.getFirstMessageSubject() || '(no subject)',
      at: t.getLastMessageDate().toISOString()
    };
  });
  return {
    unread: GmailApp.getInboxUnreadCount(),
    important: GmailApp.search('in:inbox is:unread is:important', 0, 100).length,
    top: top
  };
}

function events() {
  var start = new Date();
  start.setHours(0, 0, 0, 0);
  var end = new Date(start.getTime() + 2 * 24 * 3600 * 1000);
  var out = [];
  CalendarApp.getAllCalendars().forEach(function (cal) {
    if (cal.isHidden() || !cal.isSelected()) return;
    cal.getEvents(start, end).forEach(function (ev) {
      var status = ev.getMyStatus();
      if (status === CalendarApp.GuestStatus.NO) return;
      out.push({
        title: ev.getTitle() || 'Busy',
        start: ev.getStartTime().toISOString(),
        end: ev.getEndTime().toISOString(),
        allDay: ev.isAllDayEvent(),
        calendar: cal.getName(),
        color: cal.getColor()
      });
    });
  });
  out.sort(function (a, b) { return a.start < b.start ? -1 : 1; });
  return out;
}

function tasks() {
  if (typeof Tasks === 'undefined') return [];   // Tasks API service not added
  var out = [];
  var lists = Tasks.Tasklists.list({ maxResults: 20 }).items || [];
  lists.forEach(function (l) {
    var items = Tasks.Tasks.list(l.id, { showCompleted: false, showHidden: false, maxResults: 50 }).items || [];
    items.forEach(function (t) {
      if (!t.title) return;
      out.push({ title: t.title, due: t.due || null, list: l.title });
    });
  });
  out.sort(function (a, b) {
    if (!a.due) return 1;
    if (!b.due) return -1;
    return a.due < b.due ? -1 : 1;
  });
  return out.slice(0, 20);
}

/** Flent numbers via Supabase REST. Days are IST days, matching the Flent dashboards. */
function flent() {
  var cache = CacheService.getScriptCache();
  var hit = cache.get('flent-v2');
  if (hit) return JSON.parse(hit);

  var snap = supabase(prop('OCCUPANCY_PATH'))[0];
  var counts = (snap && snap.data && snap.data.counts) || {};
  var rooms = counts.rooms;
  var occupied = counts.occupied;

  var today = istDate(0);
  var monthStart = today.substring(0, 8) + '01';
  var weekStart = istDate(-6);
  var month = null;
  try { month = monthOccupancy(); } catch (err) { /* optional: keep the other numbers */ }
  var out = {
    occupied: occupied,
    rooms: rooms,
    occupancyMonth: month && month.pct,
    month: month && month.month,
    winsMonth: count(prop('WINS_PATH') + monthStart),
    winsWeek: count(prop('WINS_PATH') + weekStart),
    visitors7d: supabase(prop('VISITORS_PATH') + weekStart)
        .reduce(function (s, r) { return s + (r.visitors || 0); }, 0)
  };
  cache.put('flent-v2', JSON.stringify(out), 900);
  return out;
}

/** This month's occupancy, occupied ÷ available room-days across all units, as the dashboards show it. */
function monthOccupancy() {
  var path = prop('OCCUPANCY_MONTH_PATH');
  if (!path) return null;
  var row = supabase(path)[0];
  if (!row || !row.units) return null;
  var occ = 0, avail = 0;
  row.units.forEach(function (u) {
    (u.months || []).forEach(function (m) {
      if (m.m !== row.cur_month) return;
      occ += Number(m.occ_rd) || 0;
      avail += Number(m.avail_rd) || 0;
    });
  });
  return avail > 0 ? { pct: Math.round(occ / avail * 1000) / 10, month: row.cur_month } : null;
}

function supabase(path, headers) {
  var key = prop('SUPABASE_SERVICE_KEY');
  var res = UrlFetchApp.fetch(prop('SUPABASE_URL') + '/rest/v1/' + path, {
    headers: Object.assign({ apikey: key, Authorization: 'Bearer ' + key }, headers || {}),
    muteHttpExceptions: true
  });
  if (res.getResponseCode() >= 300) throw new Error('supabase ' + res.getResponseCode() + ' on ' + path.split('?')[0]);
  return headers ? res : JSON.parse(res.getContentText());
}

/** Exact row count without fetching rows (Content-Range: 0-0/N). */
function count(path) {
  var res = supabase(path + '&limit=1', { Prefer: 'count=exact' });
  var range = res.getHeaders()['Content-Range'] || res.getHeaders()['content-range'] || '';
  return Number(range.split('/')[1] || 0);
}

function istDate(offsetDays) {
  return Utilities.formatDate(new Date(Date.now() + offsetDays * 86400000), 'Asia/Kolkata', 'yyyy-MM-dd');
}

function senderName(from) {
  var m = /^"?([^"<]+?)"?\s*<.*>$/.exec(from);
  return (m ? m[1] : from).trim();
}

/**
 * A setting: the script property if set, else CONFIG from a Config.gs file kept only in the Apps
 * Script project (never in this repo), e.g.  var CONFIG = { TOKEN: '…', SUPABASE_URL: '…' };
 */
function prop(name) {
  var v = PropertiesService.getScriptProperties().getProperty(name);
  if (v) return v;
  return typeof CONFIG !== 'undefined' && CONFIG[name] ? CONFIG[name] : null;
}

function safe(fn) {
  try { return fn(); } catch (err) { return { error: String(err) }; }
}

function json(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
