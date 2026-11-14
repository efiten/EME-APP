// Port of WorldMap.kt: equirectangular world map with the moon footprint (EL > 0), the moon with its phase,
// the own position and CQ stations. Geometry is in pure functions (tested); drawWorldMap only paints.
// Sizes: 1dp = 1sp = 1 CSS px (a CSS px is the dp of a 160 dpi baseline); the canvas scales by devicePixelRatio.

import { placeLabels } from './label-placer.js';
import { subLunarPoint, quickElevationDeg } from './moon-calculator.js';
import { phaseAt } from './moon-phase.js';

/** Grid size (degrees) for the moon footprint. */
const CELL_DEG = 1.5;
const MOON_RADIUS = 11;
const ME_TEXT_PX = 9;
const STATION_TEXT_PX = 8;
const LINE_HEIGHT = 1.2;

export const lonToX = (lon, width) => ((lon + 180.0) / 360.0) * width;
export const latToY = (lat, height) => ((90.0 - lat) / 180.0) * height;

/** One ring per line: "lon,lat lon,lat ...". Lines starting with # are comments. -> number[][] (lon, lat, lon, lat, ...). */
export function parseLand(text) {
  return text
    .split('\n')
    .filter((line) => line.trim() !== '' && !line.startsWith('#'))
    .map((line) => line.trim().split(' ').flatMap((p) => p.split(',').map(Number)));
}

/** Edge of the footprint (EL = 0): per longitude the latitude(s) where the elevation changes sign. -> [[lat, lon]] */
export function horizonEdge(sub) {
  const pts = [];
  for (let lon = -180.0; lon <= 180.0; lon += 1.0) {
    let lat = -90.0;
    let prev = quickElevationDeg(sub, lat, lon);
    while (lat < 90.0) {
      const next = lat + 0.5;
      const e = quickElevationDeg(sub, next, lon);
      if (prev > 0 !== e > 0) pts.push([next, lon]);
      prev = e;
      lat = next;
    }
  }
  return pts;
}

/** Per row of CELL_DEG degrees: the stretches where the moon is above the horizon. -> [{ latTop, lonFrom, lonTo }] */
export function footprintRuns(sub) {
  const runs = [];
  for (let latTop = 90.0; latTop > -90.0; latTop -= CELL_DEG) {
    const latMid = latTop - CELL_DEG / 2;
    let runStart = null;
    for (let lon = -180.0; lon < 180.0; lon += CELL_DEG) {
      const up = quickElevationDeg(sub, latMid, lon + CELL_DEG / 2) > 0;
      if (up && runStart === null) runStart = lon;
      if (!up && runStart !== null) {
        runs.push({ latTop, lonFrom: runStart, lonTo: lon });
        runStart = null;
      }
    }
    if (runStart !== null) runs.push({ latTop, lonFrom: runStart, lonTo: 180.0 });
  }
  return runs;
}

/**
 * Shape of the lit part of the moon (as seen from the northern hemisphere): rim arc plus terminator half-ellipse.
 * `a` is the half width of the ellipse as a fraction of the radius.
 */
export function moonPhaseShape(phase) {
  const k = phase?.illuminated ?? 1.0;
  if (k >= 0.99) return { kind: 'full' };
  if (k <= 0.01) return { kind: 'none' };
  const waxing = phase?.waxing ?? true;
  const crescent = k < 0.5;
  return { kind: 'arcs', waxing, a: Math.abs(1 - 2 * k), viaRight: waxing ? crescent : !crescent };
}

const rad = (deg) => (deg * Math.PI) / 180;

function drawMoon(ctx, c, r, phase, col) {
  ctx.fillStyle = col.black;
  ctx.beginPath();
  ctx.arc(c.x, c.y, r + 1.2, 0, 2 * Math.PI);
  ctx.fill();
  ctx.fillStyle = col.moonDark;
  ctx.beginPath();
  ctx.arc(c.x, c.y, r, 0, 2 * Math.PI);
  ctx.fill();
  const shape = moonPhaseShape(phase);
  ctx.fillStyle = col.moon;
  if (shape.kind === 'full') {
    ctx.beginPath();
    ctx.arc(c.x, c.y, r, 0, 2 * Math.PI);
    ctx.fill();
  } else if (shape.kind === 'arcs') {
    // Compose arcTo (clockwise from 3 o'clock, sweep sign = direction) maps 1:1 to canvas angles
    ctx.beginPath();
    ctx.moveTo(c.x, c.y - r);
    ctx.arc(c.x, c.y, r, rad(-90), rad(shape.waxing ? 90 : -270), !shape.waxing);
    ctx.ellipse(c.x, c.y, shape.a * r, r, 0, rad(90), rad(shape.viaRight ? -90 : 270), shape.viaRight);
    ctx.closePath();
    ctx.fill();
  }
  ctx.strokeStyle = col.moonRim;
  ctx.lineWidth = 0.8;
  ctx.beginPath();
  ctx.arc(c.x, c.y, r, 0, 2 * Math.PI);
  ctx.stroke();
}

const css = (style, name) => style.getPropertyValue(name).trim();

/** CSS variable of a band colour: B23CM -> --band-23cm, B50 -> --band-50. */
export const bandVar = (bandId) => `--band-${bandId.slice(1).toLowerCase()}`;

/**
 * Paints the map on `canvas` (size from its CSS width, 2:1).
 * home: { latDeg, lonDeg, callsign } or null; spots: [{ callsign, latDeg, lonDeg, band }] already selected, band = id like 'B23CM'.
 */
export function drawWorldMap(canvas, { nowMs, home = null, spots = [], land = [] }) {
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const width = canvas.clientWidth || 360;
  const height = width / 2;
  const dpr = globalThis.devicePixelRatio || 1;
  canvas.width = Math.round(width * dpr);
  canvas.height = Math.round(height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

  const style = getComputedStyle(canvas);
  const col = {
    ocean: css(style, '--map-ocean'), land: css(style, '--map-land'), landEdge: css(style, '--map-land-edge'),
    grid: css(style, '--map-grid'), footprint: css(style, '--map-footprint'), footprintEdge: css(style, '--map-footprint-edge'),
    moon: css(style, '--map-moon'), moonDark: css(style, '--map-moon-dark'), moonRim: css(style, '--map-moon-rim'),
    me: css(style, '--map-me'), leader: css(style, '--map-leader'), black: css(style, '--map-black'),
  };
  const family = style.fontFamily || 'sans-serif';
  const sub = subLunarPoint(nowMs);
  const phase = phaseAt(nowMs);
  const X = (lon) => lonToX(lon, width);
  const Y = (lat) => latToY(lat, height);

  ctx.save();
  ctx.beginPath();
  ctx.rect(0, 0, width, height);
  ctx.clip();
  ctx.fillStyle = col.ocean;
  ctx.fillRect(0, 0, width, height);

  // Land
  ctx.lineWidth = 0.6;
  for (const ring of land) {
    ctx.beginPath();
    for (let i = 0; i < ring.length; i += 2) {
      const x = X(ring[i]);
      const y = Y(ring[i + 1]);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.closePath();
    ctx.fillStyle = col.land;
    ctx.fill();
    ctx.strokeStyle = col.landEdge;
    ctx.stroke();
  }

  // Grid
  ctx.strokeStyle = col.grid;
  for (let lon = -150; lon <= 150; lon += 30) {
    ctx.lineWidth = 0.6;
    ctx.beginPath();
    ctx.moveTo(X(lon), 0);
    ctx.lineTo(X(lon), height);
    ctx.stroke();
  }
  for (let lat = -60; lat <= 60; lat += 30) {
    ctx.lineWidth = lat === 0 ? 1 : 0.6;
    ctx.beginPath();
    ctx.moveTo(0, Y(lat));
    ctx.lineTo(width, Y(lat));
    ctx.stroke();
  }

  // Footprint: rows rounded to whole pixels so they join without stripes
  ctx.fillStyle = col.footprint;
  for (const run of footprintRuns(sub)) {
    const x0 = Math.round(X(run.lonFrom));
    const x1 = Math.round(X(run.lonTo));
    const y0 = Math.round(Y(run.latTop));
    const y1 = Math.round(Y(run.latTop - CELL_DEG));
    ctx.fillRect(x0, y0, x1 - x0, y1 - y0);
  }
  ctx.fillStyle = col.footprintEdge;
  for (const [lat, lon] of horizonEdge(sub)) {
    ctx.beginPath();
    ctx.arc(X(lon), Y(lat), 0.9, 0, 2 * Math.PI);
    ctx.fill();
  }

  // Fixed obstacles: the moon, the own dot and its label
  const moonC = { x: X(sub.lonDeg), y: Y(sub.latDeg) };
  const moonR = MOON_RADIUS + 1;
  const obstacles = [{ left: moonC.x - moonR, top: moonC.y - moonR, right: moonC.x + moonR, bottom: moonC.y + moonR }];
  const meC = home ? { x: X(home.lonDeg), y: Y(home.latDeg) } : null;
  const meR = 4;
  if (meC) obstacles.push({ left: meC.x - meR, top: meC.y - meR, right: meC.x + meR, bottom: meC.y + meR });

  const measure = (text, px) => {
    ctx.font = `bold ${px}px ${family}`;
    return { width: ctx.measureText(text).width, height: px * LINE_HEIGHT };
  };
  const dotR = 2.5;
  const anchors = spots.map((s) => ({ x: X(s.lonDeg), y: Y(s.latDeg) }));
  const sizes = spots.map((s) => measure(s.callsign, STATION_TEXT_PX));
  const labels = spots.map((_, i) => ({ ...anchors[i], ...sizes[i] }));
  let meSize = null;
  if (meC) {
    meSize = measure(home.callsign, ME_TEXT_PX);
    labels.push({ ...meC, ...meSize, priority: true });
  }
  const placed = placeLabels(labels, dotR, 1.5, width, height, obstacles);
  const leader = (from, b, color, lineWidth) => {
    ctx.strokeStyle = color;
    ctx.lineWidth = lineWidth;
    ctx.beginPath();
    ctx.moveTo(from.x, from.y);
    ctx.lineTo(Math.min(Math.max(from.x, b.left), b.right), Math.min(Math.max(from.y, b.top), b.bottom));
    ctx.stroke();
  };
  const text = (str, px, color, b) => {
    ctx.font = `bold ${px}px ${family}`;
    ctx.textBaseline = 'middle';
    ctx.fillStyle = color;
    ctx.shadowColor = col.black;
    ctx.shadowBlur = 3;
    ctx.fillText(str, b.left, (b.top + b.bottom) / 2);
    ctx.shadowBlur = 0;
  };
  const dot = (c, r, color) => {
    ctx.fillStyle = col.black;
    ctx.beginPath();
    ctx.arc(c.x, c.y, r + 1, 0, 2 * Math.PI);
    ctx.fill();
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(c.x, c.y, r, 0, 2 * Math.PI);
    ctx.fill();
  };

  const colors = spots.map((s) => css(style, bandVar(s.band)));
  spots.forEach((_, i) => placed[i].needsLeader && leader(anchors[i], placed[i].box, col.leader, 0.7));
  spots.forEach((_, i) => dot(anchors[i], dotR, colors[i]));
  spots.forEach((s, i) => text(s.callsign, STATION_TEXT_PX, colors[i], placed[i].box));

  if (meC) {
    const p = placed[placed.length - 1];
    if (p.needsLeader) leader(meC, p.box, col.me, 0.7);
    dot(meC, meR, col.me);
    text(home.callsign, ME_TEXT_PX, col.me, p.box);
  }

  drawMoon(ctx, moonC, MOON_RADIUS, phase, col);
  ctx.restore();
}
