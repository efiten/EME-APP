import { describe, expect, test } from 'vitest';
import { footprintRuns, horizonEdge, latToY, lonToX, moonPhaseShape, parseLand } from '../src/lib/world-map.js';
import { subLunarPoint } from '../src/lib/moon-calculator.js';
import land from '../src/generated/land110m.txt?raw';

const SUB = () => subLunarPoint(Date.parse('2026-11-07T10:00:00Z'));

describe('world map geometry', () => {
  test('equirectangular projection', () => {
    expect(lonToX(-180, 800)).toBe(0);
    expect(lonToX(0, 800)).toBe(400);
    expect(lonToX(180, 800)).toBe(800);
    expect(latToY(90, 400)).toBe(0);
    expect(latToY(0, 400)).toBe(200);
    expect(latToY(-90, 400)).toBe(400);
  });

  test('parseLand skips comments and blank lines, one ring per line', () => {
    const rings = parseLand('# c\n\n1.5,-2 3,4 5,6\n7,8 9,10\n');
    expect(rings).toEqual([[1.5, -2, 3, 4, 5, 6], [7, 8, 9, 10]]);
  });

  test('the shipped land110m.txt parses to 129 rings (131 lines minus 2 comments) with finite numbers', () => {
    const rings = parseLand(land);
    expect(rings.length).toBe(129);
    expect(rings.every((r) => r.length % 2 === 0 && r.every(Number.isFinite))).toBe(true);
  });

  test('footprint covers the sub-lunar point and its runs lie within the map', () => {
    const sub = SUB();
    const runs = footprintRuns(sub);
    expect(runs.length).toBeGreaterThan(50);
    const row = runs.filter((r) => r.latTop >= sub.latDeg && r.latTop - 1.5 <= sub.latDeg);
    expect(row.some((r) => r.lonFrom <= sub.lonDeg && sub.lonDeg <= r.lonTo)).toBe(true);
    expect(runs.every((r) => r.lonFrom >= -180 && r.lonTo <= 180 && r.lonFrom < r.lonTo)).toBe(true);
  });

  test('horizon edge points are finite and in range', () => {
    const pts = horizonEdge(SUB());
    expect(pts.length).toBeGreaterThan(300);
    expect(pts.every(([lat, lon]) => Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90)).toBe(true);
  });

  test('moon phase shape', () => {
    expect(moonPhaseShape(null)).toEqual({ kind: 'full' });
    expect(moonPhaseShape({ illuminated: 1, waxing: true })).toEqual({ kind: 'full' });
    expect(moonPhaseShape({ illuminated: 0, waxing: true })).toEqual({ kind: 'none' });
    expect(moonPhaseShape({ illuminated: 0.25, waxing: true })).toEqual({ kind: 'arcs', waxing: true, a: 0.5, viaRight: true });
    expect(moonPhaseShape({ illuminated: 0.25, waxing: false })).toEqual({ kind: 'arcs', waxing: false, a: 0.5, viaRight: false });
    expect(moonPhaseShape({ illuminated: 0.75, waxing: true })).toEqual({ kind: 'arcs', waxing: true, a: 0.5, viaRight: false });
  });
});
