// Port of LabelPlacer.kt: places callsign labels on the map without overlapping each other or other dots.
// First 8 places right next to the dot, then the same directions step by step further away (with a leader line).
// When nothing is free, the place with the least overlap wins.

/** Box = { left, top, right, bottom }. */
const overlap = (a, o) => {
  const w = Math.min(a.right, o.right) - Math.max(a.left, o.left);
  const h = Math.min(a.bottom, o.bottom) - Math.max(a.top, o.top);
  return w > 0 && h > 0 ? w * h : 0;
};
const inside = (b, width, height) => b.left >= 0 && b.top >= 0 && b.right <= width && b.bottom <= height;
const box = (left, top, right, bottom) => ({ left, top, right, bottom });

/** The 8 candidate places at distance d from the dot: right, left, upper right, lower right, upper left, lower left, above, below. */
function candidates(l, d) {
  const { width: w, height: h } = l;
  const diag = d * 0.75;
  return [
    box(l.x + d, l.y - h / 2, l.x + d + w, l.y + h / 2),
    box(l.x - d - w, l.y - h / 2, l.x - d, l.y + h / 2),
    box(l.x + diag, l.y - diag - h, l.x + diag + w, l.y - diag),
    box(l.x + diag, l.y + diag, l.x + diag + w, l.y + diag + h),
    box(l.x - diag - w, l.y - diag - h, l.x - diag, l.y - diag),
    box(l.x - diag - w, l.y + diag, l.x - diag, l.y + diag + h),
    box(l.x - w / 2, l.y - d - h, l.x + w / 2, l.y - d),
    box(l.x - w / 2, l.y + d, l.x + w / 2, l.y + d + h),
  ];
}

const RINGS = [0, 1.3, 2.4, 3.6, 5];

/**
 * @param labels    [{ x, y, width, height, priority? }]: anchor (the dot) and size of each label; priority labels go first
 * @param dotRadius radius of the dots (they must not be covered either)
 * @param gap       space between dot and label
 * @param obstacles fixed boxes that must stay free (own QTH, the moon)
 * @returns [{ box, needsLeader }] in the order of `labels`
 */
export function placeLabels(labels, dotRadius, gap, width, height, obstacles = []) {
  const dots = labels.map((l) => box(l.x - dotRadius, l.y - dotRadius, l.x + dotRadius, l.y + dotRadius));
  const taken = [...obstacles];
  const result = new Array(labels.length);

  // Left to right, then top to bottom: a calm picture; priority first
  const order = labels
    .map((_, i) => i)
    .sort((a, b) => Number(!labels[a].priority) - Number(!labels[b].priority) || labels[a].x - labels[b].x || labels[a].y - labels[b].y);

  for (const i of order) {
    const l = labels[i];
    let best = null;
    let bestOverlap = Infinity;
    search: for (let ring = 0; ring < RINGS.length; ring++) {
      const dist = dotRadius + gap + RINGS[ring] * l.height;
      for (const b of candidates(l, dist)) {
        if (!inside(b, width, height)) continue;
        let ov = 0;
        for (const t of taken) ov += overlap(t, b);
        dots.forEach((d, j) => {
          if (j !== i) ov += overlap(d, b);
        });
        if (ov < bestOverlap) {
          bestOverlap = ov;
          best = { box: b, needsLeader: ring > 0 };
        }
        if (ov === 0) break search;
      }
    }
    const chosen = best ?? { box: candidates(l, dotRadius + gap)[0], needsLeader: false };
    result[i] = chosen;
    taken.push(chosen.box);
  }
  return result;
}
