import { describe, expect, test } from 'vitest';
import { placeLabels } from '../src/lib/label-placer.js';
import { toLatLon } from '../src/lib/maidenhead.js';

// Port of LabelPlacerTest.kt: map as on a phone of 411dp wide (xxhdpi, 3 pixels per dp)
const w = 1180;
const h = 590;
const density = 3;
const dotR = 2.5 * density;
const labelH = 8 * density * 1.2;
const labelW = (call) => call.length * 8 * density * 0.62;

const x = (lon) => ((lon + 180) / 360) * w;
const y = (lat) => ((90 - lat) / 180) * h;

function label(call, grid) {
  const p = toLatLon(grid);
  return { x: x(p.lonDeg), y: y(p.latDeg), width: labelW(call), height: labelH };
}

const overlap = (a, b) => {
  const ow = Math.min(a.right, b.right) - Math.max(a.left, b.left);
  const oh = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
  return ow > 0 && oh > 0 ? ow * oh : 0;
};
const inside = (b) => b.left >= 0 && b.top >= 0 && b.right <= w && b.bottom <= h;

function assertNoOverlap(labels, placed) {
  for (let i = 0; i < placed.length; i++) {
    expect(inside(placed[i].box), `label ${i} valt buiten de kaart`).toBe(true);
    for (let j = 0; j < placed.length; j++) {
      if (i === j) continue;
      expect(overlap(placed[i].box, placed[j].box), `label ${i} overlapt label ${j}`).toBe(0);
      const dot = { left: labels[j].x - dotR, top: labels[j].y - dotR, right: labels[j].x + dotR, bottom: labels[j].y + dotR };
      expect(overlap(placed[i].box, dot), `label ${i} bedekt stip ${j}`).toBe(0);
    }
  }
}

describe('placeLabels (LabelPlacerTest.kt)', () => {
  test('ok1aaAndOk2bb_doNotOverlap', () => {
    const labels = [label('OK1AA', 'JO61AA'), label('OK2BB', 'JO71AA')];
    assertNoOverlap(labels, placeLabels(labels, dotR, 1.5 * density, w, h, []));
  });

  test('crowdedCentralEurope_doesNotOverlap', () => {
    const labels = [
      label('OK1AA', 'JO61AA'), label('OK2BB', 'JO71AA'), label('OK1DFC', 'JO70FD'),
      label('DL7APV', 'JO62QM'), label('SP5GDM', 'KO02MD'), label('OK1KIR', 'JO70ED'),
      label('DF1NP', 'JN58TD'), label('OK1AA', 'JO61AA'),
    ];
    const myQth = { left: x(5.54) - 15, top: y(51.1) - 15, right: x(5.54) + 80, bottom: y(51.1) + 15 };
    const placed = placeLabels(labels, dotR, 1.5 * density, w, h, [myQth]);
    assertNoOverlap(labels, placed);
    placed.forEach((p) => expect(overlap(p.box, myQth)).toBe(0));
  });

  test('labelNearMapEdge_staysInside', () => {
    const labels = [{ x: w - 3, y: 100, width: labelW('ZL1AAA'), height: labelH }];
    expect(inside(placeLabels(labels, dotR, 1.5 * density, w, h, [])[0].box)).toBe(true);
  });

  test('a lone label goes right next to its dot without a leader', () => {
    const p = placeLabels([{ x: 500, y: 300, width: 40, height: 10 }], dotR, 4, w, h, [])[0];
    expect(p.needsLeader).toBe(false);
    expect(p.box).toEqual({ left: 500 + dotR + 4, top: 295, right: 500 + dotR + 4 + 40, bottom: 305 });
  });

  test('a priority label is placed first and gets the best spot', () => {
    const labels = [
      { x: 500, y: 300, width: 40, height: 10 },
      { x: 500, y: 300, width: 40, height: 10, priority: true },
    ];
    const placed = placeLabels(labels, dotR, 4, w, h, []);
    expect(placed[1].needsLeader).toBe(false);
    expect(placed[1].box.left).toBe(500 + dotR + 4);
    expect(overlap(placed[0].box, placed[1].box)).toBe(0);
  });
});
