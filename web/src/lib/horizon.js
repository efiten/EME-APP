// Own horizon of a station. Same rules and storage format as horizon/Horizon.kt and
// horizon/MoonWindows.kt in the app; shared reference values in app/src/test/resources/expected/horizon_*.csv.
import { position } from './moon-calculator.js';

export const FORMAT_VERSION = '1';
export const KINDS = { BLOCKED: 'B', PARTIAL: 'P' };
export const VISIBILITY = { CLEAR: 'CLEAR', PARTIAL: 'PARTIAL', BLOCKED: 'BLOCKED' };

/** Default: no minimum and no obstacles, i.e. usable as soon as the moon is above the horizon. */
export const defaultHorizon = () => ({ minElDeg: 0, obstacles: [] });

/** Azimuth in [0, 360). */
export function normalizeAz(deg) {
  return ((deg % 360) + 360) % 360;
}

/** From fromAzDeg to toAzDeg clockwise, both included; may cross north (350 to 10). */
export function containsAz(obstacle, azDeg) {
  if (obstacle.toAzDeg - obstacle.fromAzDeg >= 360) return true; // the whole circle, e.g. 0 to 360
  const az = normalizeAz(azDeg);
  const from = normalizeAz(obstacle.fromAzDeg);
  const to = normalizeAz(obstacle.toAzDeg);
  return from <= to ? az >= from && az <= to : az >= from || az <= to;
}

/**
 * CLEAR, PARTIAL or BLOCKED for a point in the sky. marginDeg is subtracted from the elevation
 * (e.g. half the beamwidth). Exactly on the edge counts as clear. BLOCKED wins over PARTIAL.
 */
export function visibility(horizon, azDeg, elDeg, marginDeg = 0) {
  const el = elDeg - marginDeg;
  if (elDeg <= 0 || el < horizon.minElDeg) return VISIBILITY.BLOCKED;
  let partial = false;
  for (const o of horizon.obstacles) {
    if (el < o.maxElDeg && containsAz(o, azDeg)) {
      if (o.kind === KINDS.BLOCKED) return VISIBILITY.BLOCKED;
      partial = true;
    }
  }
  return partial ? VISIBILITY.PARTIAL : VISIBILITY.CLEAR;
}

/** Number with at most 2 decimals and no trailing ".0", same as Horizon.kt. */
function fmt(v) {
  return String(Math.round(v * 100) / 100);
}

/** `1;<min>;<kind> <from> <to> <el>;...`, e.g. `1;5;B 60 110 25;P 180 200 20`. */
export function encode(horizon) {
  return [FORMAT_VERSION, fmt(horizon.minElDeg), ...horizon.obstacles.map((o) => `${o.kind} ${fmt(o.fromAzDeg)} ${fmt(o.toAzDeg)} ${fmt(o.maxElDeg)}`)].join(';');
}

function num(text, lo, hi) {
  if (!/^-?\d+(\.\d+)?$/.test(text)) return null;
  const v = Number(text);
  return v >= lo && v <= hi ? v : null;
}

/** Reads encode() back; null for an unknown version or rubbish. */
export function decode(text) {
  if (typeof text !== 'string') return null;
  const parts = text.trim().split(';');
  if (parts.length < 2 || parts[0] !== FORMAT_VERSION) return null;
  const minElDeg = num(parts[1].trim(), 0, 90);
  if (minElDeg === null) return null;
  const obstacles = [];
  for (const p of parts.slice(2)) {
    const f = p.trim().split(' ').filter((x) => x !== '');
    if (f.length !== 4 || !Object.values(KINDS).includes(f[0])) return null;
    const fromAzDeg = num(f[1], 0, 360);
    const toAzDeg = num(f[2], 0, 360);
    const maxElDeg = num(f[3], 0, 90);
    if (fromAzDeg === null || toAzDeg === null || maxElDeg === null) return null;
    obstacles.push({ fromAzDeg, toAzDeg, maxElDeg, kind: f[0] });
  }
  return { minElDeg, obstacles };
}

/** Half the -3 dB beamwidth of a dish in degrees (70·λ/D for the full width). */
export function halfBeamwidthDeg(freqMhz, dishDiameterM) {
  return (35 * (299.792458 / freqMhz)) / dishDiameterM;
}

const STEP_MS = 60_000;

/** Stretches of time with the same visibility, minute by minute; endMillis is not included. */
export function moonWindows(horizon, latDeg, lonDeg, startMillis, endMillis, marginDeg = 0) {
  const out = [];
  let segStart = startMillis;
  let current = null;
  for (let t = startMillis; t < endMillis; t += STEP_MS) {
    const p = position(latDeg, lonDeg, t);
    const v = visibility(horizon, p.azimuthDeg, p.elevationDeg, marginDeg);
    if (v !== current) {
      if (current !== null) out.push({ startMillis: segStart, endMillis: t, visibility: current });
      segStart = t;
      current = v;
    }
  }
  if (current !== null) out.push({ startMillis: segStart, endMillis, visibility: current });
  return out;
}

/** The stretches where EME is possible (clear or partial), adjacent ones merged; partial if any part is. */
export function usableWindows(horizon, latDeg, lonDeg, startMillis, endMillis, marginDeg = 0) {
  const out = [];
  for (const w of moonWindows(horizon, latDeg, lonDeg, startMillis, endMillis, marginDeg)) {
    if (w.visibility === VISIBILITY.BLOCKED) continue;
    const last = out[out.length - 1];
    if (last && last.endMillis === w.startMillis) {
      const v = last.visibility === VISIBILITY.PARTIAL || w.visibility === VISIBILITY.PARTIAL ? VISIBILITY.PARTIAL : VISIBILITY.CLEAR;
      out[out.length - 1] = { startMillis: last.startMillis, endMillis: w.endMillis, visibility: v };
    } else {
      out.push({ ...w });
    }
  }
  return out;
}
