// Port of LiveCqParser.kt, N6nuParser.kt and LiveCqSpot.kt (Band, LiveCqSource):
// W3SZ CSV and N6NU JSON parsing, merge and select. All timestamps are UTC.

import { toLatLon } from './maidenhead.js';

/** Band buttons as on the W3SZ Live CQ site. Frequencies in MHz. */
export const BANDS = [
  { id: 'B50', label: '50', minMhz: 50.0, maxMhz: 54.0 },
  { id: 'B144', label: '144', minMhz: 144.0, maxMhz: 148.0 },
  { id: 'B222', label: '222', minMhz: 219.0, maxMhz: 225.0 },
  { id: 'B432', label: '432', minMhz: 420.0, maxMhz: 450.0 },
  { id: 'B902', label: '902', minMhz: 902.0, maxMhz: 928.0 },
  { id: 'B23CM', label: '23cm', minMhz: 1240.0, maxMhz: 1300.0 },
  { id: 'B13CM', label: '13cm', minMhz: 2300.0, maxMhz: 2450.0 },
  { id: 'B9CM', label: '9cm', minMhz: 3300.0, maxMhz: 3500.0 },
  { id: 'B6CM', label: '6cm', minMhz: 5650.0, maxMhz: 5925.0 },
  { id: 'B3CM', label: '3cm', minMhz: 10000.0, maxMhz: 10500.0 },
  { id: 'B1CM', label: '1.2cm/up', minMhz: 24000.0, maxMhz: Infinity },
];

export const SOURCES = ['W3SZ', 'N6NU'];

export function bandForFrequency(mhz) {
  const b = BANDS.find((x) => mhz >= x.minMhz && mhz <= x.maxMhz);
  return b ? b.id : null;
}

/** "W3SZ", "N6NU" or "W3SZ+N6NU". */
export function sourceLabel(spot) {
  return SOURCES.filter((s) => spot.sources.has(s)).join('+');
}

// Same checks as the W3SZ page itself
export const CALL_REGEX = /^(?:[A-Z0-9]{1,3}\/)?[A-Z0-9]{1,3}[0-9][A-Z]{1,4}$/;
export const GRID_REGEX = /^[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2}([A-X]{2})?)?)?$/i;

const DECIMAL = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;
const toDoubleOrNull = (s) => (DECIMAL.test(s) ? Number(s) : null);

function utcMillisOf(y, mo, d, h, mi, s, ms, offsetMinutes) {
  if (y < 1 || mo < 1 || mo > 12 || d < 1 || h > 23 || mi > 59 || s > 59) return null;
  const dt = new Date(0);
  dt.setUTCFullYear(y, mo - 1, d);
  dt.setUTCHours(h, mi, s, ms);
  if (dt.getUTCMonth() !== mo - 1 || dt.getUTCDate() !== d) return null;
  return dt.getTime() - offsetMinutes * 60_000;
}

const TIME_FORMAT = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/;

/** "yyyy-MM-dd HH:mm:ss" as UTC, or null. */
function parseTime(text) {
  const m = TIME_FORMAT.exec(text);
  if (!m) return null;
  const [y, mo, d, h, mi, s] = m.slice(1).map(Number);
  return utcMillisOf(y, mo, d, h, mi, s, 0, 0);
}

const INSTANT = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(?:Z|([+-])(\d{2}):(\d{2}))$/;

/** ISO-8601 instant ("2026-11-14T10:00:00.000Z"), or null. */
function parseInstant(text) {
  const m = INSTANT.exec(text);
  if (!m) return null;
  const [y, mo, d, h, mi, s] = m.slice(1, 7).map(Number);
  const ms = m[7] ? Number(m[7].padEnd(3, '0').slice(0, 3)) : 0;
  let offset = 0;
  if (m[8]) {
    const oh = Number(m[9]);
    const om = Number(m[10]);
    if (oh > 18 || om > 59) return null;
    offset = (m[8] === '-' ? -1 : 1) * (oh * 60 + om);
  }
  return utcMillisOf(y, mo, d, h, mi, s, ms, offset);
}

/** Splits one CSV line; handles fields between "quotes". */
export function splitCsvLine(line) {
  const out = [];
  let sb = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"' && inQuotes && i + 1 < line.length && line[i + 1] === '"') {
      sb += '"';
      i++;
    } else if (c === '"') {
      inQuotes = !inQuotes;
    } else if (c === ',' && !inQuotes) {
      out.push(sb);
      sb = '';
    } else {
      sb += c;
    }
  }
  out.push(sb);
  return out;
}

/** All valid CQ spots from the W3SZ CSV text. Unreadable lines are skipped. */
export function parseW3sz(csv) {
  const lines = csv.split(/\r\n|\n|\r/).filter((l) => l.trim() !== '');
  if (lines.length === 0) return [];
  const header = splitCsvLine(lines[0]).map((h) => h.trim().toLowerCase());
  const idx = (name) => header.indexOf(name);
  const iFreq = idx('skedfreq');
  const iCall = idx('callsign');
  const iGrid = idx('grid');
  const iMode = idx('mode');
  const iTime = idx('utcdatetime');
  const iDb = idx('db');
  const iSpotter = idx('spotter');
  const iSpGrid = idx('spottergrid');
  const iType = idx('msgtype');
  const iUpload = idx('uploadtime');
  const iDt = idx('dt');
  const iApp = idx('apptype');
  if (!(iFreq >= 0 && iCall >= 0 && iTime >= 0)) {
    throw new Error('Onverwacht formaat van de Live CQ-gegevens');
  }

  const out = [];
  for (const line of lines.slice(1)) {
    const f = splitCsvLine(line);
    const col = (i) => (i >= 0 && i < f.length ? f[i].trim() : '');
    const call = col(iCall).toUpperCase();
    if (!CALL_REGEX.test(call)) continue;
    if (iType >= 0 && col(iType) !== '' && col(iType).toLowerCase() !== 'cq') continue;
    const freq = toDoubleOrNull(col(iFreq));
    if (freq === null) continue;
    // Some spotters (e.g. EME65) send "0000-00-00 00:00:00": use the upload time then
    const time = parseTime(col(iTime)) ?? parseTime(col(iUpload));
    if (time === null) continue;
    out.push({
      utcMillis: time,
      callsign: call,
      grid: GRID_REGEX.test(col(iGrid)) ? col(iGrid) : '',
      freqMhz: freq,
      band: bandForFrequency(freq),
      mode: col(iMode),
      db: col(iDb),
      spotter: col(iSpotter).toUpperCase(),
      spotterGrid: col(iSpGrid),
      sources: new Set(['W3SZ']),
      dt: col(iDt),
      app: col(iApp),
    });
  }
  return out;
}

/** All valid CQ spots from the N6NU JSON. Unreadable spots are skipped. */
export function parseN6nu(json) {
  const spots = JSON.parse(json)?.spots;
  if (!Array.isArray(spots)) return [];
  const out = [];
  for (const o of spots) {
    if (o === null || typeof o !== 'object' || Array.isArray(o)) continue;
    const text = (name) => (o[name] === null || o[name] === undefined ? '' : String(o[name]).trim());

    if (text('msgtype').toLowerCase() !== 'cq') continue;
    const call = text('callsign').toUpperCase();
    if (!CALL_REGEX.test(call)) continue;
    const freq = toDoubleOrNull(text('skedfreq'));
    if (freq === null || !(freq > 0)) continue;
    const time = parseInstant(text('utc')) ?? parseInstant(text('createdAt'));
    if (time === null) continue;
    out.push({
      utcMillis: time,
      callsign: call,
      grid: GRID_REGEX.test(text('grid')) ? text('grid') : '',
      freqMhz: freq,
      band: bandForFrequency(freq),
      mode: text('mode'),
      db: text('dB'),
      spotter: text('spotter').toUpperCase(),
      spotterGrid: GRID_REGEX.test(text('spottergrid')) ? text('spottergrid') : '',
      sources: new Set(['N6NU']),
      dt: text('dt'),
      app: text('apptype'),
    });
  }
  return out;
}

const orElse = (a, b) => (a === '' ? b : a);

function combine(a, b) {
  return {
    ...a,
    grid: orElse(a.grid, b.grid),
    db: orElse(a.db, b.db),
    spotterGrid: orElse(a.spotterGrid, b.spotterGrid),
    sources: new Set([...a.sources, ...b.sources]),
    dt: orElse(a.dt, b.dt),
    app: orElse(a.app, b.app),
  };
}

/**
 * Merges the spots of W3SZ and N6NU. The same decode (same station, band, spotter and
 * minute) on both sites becomes one spot with both sources; what is missing on one
 * (locator, DT, program) is filled in from the other.
 */
export function mergeSpots(w3sz, n6nu) {
  const out = new Map();
  for (const spot of [...w3sz, ...n6nu]) {
    const key = JSON.stringify([spot.callsign, spot.band, spot.spotter, Math.trunc(spot.utcMillis / 60_000)]);
    const prev = out.get(key);
    out.set(key, prev === undefined ? spot : combine(prev, spot));
  }
  return [...out.values()];
}

/**
 * What the app shows: only the chosen bands (a Set of band ids), not older than
 * maxAgeMinutes, without spots where spotter = the station itself, and per callsign +
 * band only the newest spot. Newest first. That spot gets all sources of the group, and
 * a locator from an older spot when the newest has none.
 */
export function selectSpots(spots, bandIds, nowMs, maxAgeMinutes) {
  const oldest = nowMs - maxAgeMinutes * 60_000;
  const groups = new Map();
  for (const s of spots) {
    if (!(s.band !== null && bandIds.has(s.band) && s.utcMillis >= oldest && s.spotter !== s.callsign)) continue;
    const key = JSON.stringify([s.callsign, s.band]);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(s);
  }
  const result = [];
  for (const group of groups.values()) {
    const newest = [...group].sort((a, b) => b.utcMillis - a.utcMillis);
    const withGrid = newest.find((s) => s.grid !== '');
    result.push({
      ...newest[0],
      grid: withGrid ? withGrid.grid : '',
      sources: new Set(group.flatMap((s) => [...s.sources])),
    });
  }
  return result.sort((a, b) => b.utcMillis - a.utcMillis);
}

/** Port of CqScreen.kt stationLatLon: 6-character locator, else the 4-character one, else null. */
export function stationLatLon(grid) {
  return toLatLon(grid.slice(0, 6)) ?? toLatLon(grid.slice(0, 4));
}
