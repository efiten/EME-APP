// Reads a horizon survey of horizon-survey (https://horizon.dl6zg.de/, github.com/on6zg/horizon-survey):
// horizon_profile.json ({ "points": [{ "az", "el", "source"? }] }) or its CSV
// (azimuth_deg,elevation_deg,timestamp,is_calibration,source), and turns the points into obstacles.
// Same rules as horizon/HorizonSurvey.kt; shared reference: app/src/test/resources/expected/horizon_import.csv.
import { KINDS, normalizeAz } from './horizon.js';

/** A gap between two measured points that is larger is not filled (nothing was measured there). */
export const MAX_GAP_DEG = 60;

/** { status: 'OK', points, obstacles } | { status: 'NOT_A_SURVEY' } | { status: 'TOO_FEW_POINTS' } */
export function parseSurvey(text) {
  const points = readPoints(text);
  if (points === null) return { status: 'NOT_A_SURVEY' };
  if (points.length < 2) return { status: 'TOO_FEW_POINTS' };
  return { status: 'OK', points: points.length, obstacles: toObstacles(points) };
}

const finite = (v) => typeof v === 'number' && Number.isFinite(v);

/** The measured points [az, el], or null when this is not a survey. */
export function readPoints(text) {
  const t = String(text).trim().replace(/^﻿/, '');
  let raw;
  if (t.startsWith('{')) {
    let obj;
    try {
      obj = JSON.parse(t);
    } catch {
      return null;
    }
    if (!obj || !Array.isArray(obj.points)) return null;
    raw = obj.points.filter((p) => p && finite(p.az) && finite(p.el)).map((p) => [p.az, p.el]);
  } else if (t.toLowerCase().startsWith('azimuth_deg,elevation_deg')) {
    raw = t
      .split(/\r?\n/)
      .slice(1)
      .map((line) => line.split(',').map((c) => c.trim()))
      .filter((c) => c.length >= 2 && c[3] !== '1') // is_calibration = 1: not horizon data
      .map((c) => [c[0] === '' ? NaN : Number(c[0]), c[1] === '' ? NaN : Number(c[1])])
      .filter(([az, el]) => Number.isFinite(az) && Number.isFinite(el));
  } else {
    return null;
  }
  // Same azimuth (to 0.01 deg) measured twice: the highest elevation counts
  const byAz = new Map();
  for (const [az, el] of raw) {
    const k = Math.round(normalizeAz(az) * 100);
    byAz.set(k, Math.max(byAz.get(k) ?? -Infinity, el));
  }
  return [...byAz.entries()]
    .map(([k, el]) => [k / 100 >= 360 ? 0 : k / 100, Math.min(Math.max(el, 0), 90)])
    .sort((a, b) => a[0] - b[0]);
}

/**
 * Between two consecutive points (also across north) a blocked obstacle up to the higher of the two
 * elevations; gaps larger than MAX_GAP_DEG and stretches at 0 deg stay open. Adjacent stretches of
 * the same height are merged.
 */
export function toObstacles(points) {
  const n = points.length;
  const segs = [];
  for (let i = 0; i < n; i++) {
    const [az1, el1] = points[i];
    const [az2, el2] = points[(i + 1) % n];
    const gap = (((az2 - az1) % 360) + 360) % 360;
    if (gap === 0 || gap > MAX_GAP_DEG) continue;
    const el = Math.max(el1, el2);
    if (el <= 0) continue;
    segs.push({ from: az1, to: az2, el, span: gap });
  }
  const merged = [];
  for (const s of segs) {
    const last = merged.at(-1);
    if (last && last.to === s.from && last.el === s.el) merged[merged.length - 1] = { ...last, to: s.to, span: last.span + s.span };
    else merged.push(s);
  }
  if (merged.length > 1) {
    const first = merged[0];
    const last = merged.at(-1);
    if (last.to === first.from && last.el === first.el) {
      merged[merged.length - 1] = { ...last, to: first.to, span: last.span + first.span };
      merged.shift();
    }
  }
  // Same height all round: the whole circle (0 to 360), not "from 0 to 0"
  return merged.map((s) =>
    s.span >= 360
      ? { fromAzDeg: 0, toAzDeg: 360, maxElDeg: s.el, kind: KINDS.BLOCKED }
      : { fromAzDeg: s.from, toAzDeg: s.to, maxElDeg: s.el, kind: KINDS.BLOCKED },
  );
}
