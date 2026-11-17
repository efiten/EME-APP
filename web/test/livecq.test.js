import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';
import { toLatLon } from '../src/lib/maidenhead.js';
import {
  BANDS,
  CALL_REGEX,
  GRID_REGEX,
  bandForFrequency,
  mergeSpots,
  parseN6nu,
  parseW3sz,
  selectSpots,
  sourceLabel,
  splitCsvLine,
  stationLatLon,
} from '../src/lib/livecq.js';

const resource = (name) => readFileSync(new URL(`../../app/src/test/resources/${name}`, import.meta.url), 'utf8');
const millis = (iso) => Date.parse(iso);

describe('LiveCqParser (W3SZ)', () => {
  // Real export from W3SZ (4-5 November 2026).
  const sample = resource('livecq_sample.csv');

  test('bands', () => {
    expect(BANDS.map((b) => b.id)).toEqual([
      'B50', 'B144', 'B222', 'B432', 'B902', 'B23CM', 'B13CM', 'B9CM', 'B6CM', 'B3CM', 'B1CM',
    ]);
    expect(BANDS.at(-1).maxMhz).toBe(Infinity);
    expect(BANDS[5]).toEqual({ id: 'B23CM', label: '23cm', minMhz: 1240, maxMhz: 1300 });
  });

  test('parsesRealExport', () => {
    const spots = parseW3sz(sample);
    expect(spots.length).toBe(584);
    const first = spots[0];
    expect(first.callsign).toBe('KA1EME');
    expect(first.grid).toBe('FN31');
    expect(first.freqMhz).toBeCloseTo(144.112, 9);
    expect(first.band).toBe('B144');
    expect(first.mode).toBe('JT65B');
    expect(first.spotter).toBe('W9IP');
    expect(first.utcMillis).toBe(millis('2026-11-04T13:39:00Z'));
  });

  test('bandsFromFrequency', () => {
    const byBand = {};
    for (const s of parseW3sz(sample)) byBand[s.band] = (byBand[s.band] ?? 0) + 1;
    expect(byBand.B144).toBe(239);
    expect(byBand.B432).toBe(284);
    expect(byBand.B23CM).toBe(61);
    expect(bandForFrequency(1296.065)).toBe('B23CM');
    expect(bandForFrequency(10368.1)).toBe('B3CM');
    expect(bandForFrequency(700.0)).toBeNull();
  });

  test('selectFiltersBandAgeSelfSpotsAndDuplicates', () => {
    const spots = parseW3sz(sample);
    const now = millis('2026-11-05T13:30:00Z');
    const shown = selectSpots(spots, new Set(['B23CM']), now, 24 * 60);
    expect(shown.length).toBeGreaterThan(0);
    expect(shown.every((s) => s.band === 'B23CM')).toBe(true);
    expect(shown.every((s) => s.spotter !== s.callsign)).toBe(true);
    expect(new Set(shown.map((s) => s.callsign)).size).toBe(shown.length);
    expect(shown).toEqual([...shown].sort((a, b) => b.utcMillis - a.utcMillis));

    const later = selectSpots(spots, new Set(['B23CM']), millis('2026-11-05T15:00:00Z'), 60);
    expect(later).toEqual([]);
    expect(selectSpots(spots, new Set(), now, 24 * 60)).toEqual([]);
  });

  test('skipsInvalidLinesAndHtml', () => {
    const csv = [
      'skedfreq,rxfreq,rpol,dt,dB,msgtype,callsign,grid,mode,utcdatetime,spotter,spottergrid,txpol,apptype,band,uploadtime',
      '1296.070,0,0,0,-20,CQ,SP5GDM,KO02,Q65-60C,"2026-11-06 20:01:00",OK1DFC,JN79,/,MAP65,1290,"2026-11-06 20:02:00"',
      '1296.070,0,0,0,-20,CQ,NOT A CALL,KO02,Q65-60C,"2026-11-06 20:01:00",OK1DFC,JN79,/,MAP65,1290,x',
      'abc,0,0,0,-20,CQ,DL7APV,JO62,Q65-60C,"2026-11-06 20:01:00",OK1DFC,JN79,/,MAP65,1290,x',
      '1296.070,0,0,0,-20,CQ,DL7APV,JO62,Q65-60C,geen-datum,OK1DFC,JN79,/,MAP65,1290,x',
      '1296.080,0,0,0,-21,CQ,G4CCH,XX99,Q65-60C,"2026-11-06 20:02:00",OK1DFC,JN79,/,MAP65,1290,x',
    ].join('\n');
    const spots = parseW3sz(csv);
    expect(spots.map((s) => s.callsign)).toEqual(['SP5GDM', 'G4CCH']);
    expect(spots[1].grid).toBe('');
  });

  test('zeroTimestamp_usesUploadTime', () => {
    const spot = parseW3sz(sample).find((s) => s.callsign === 'SM2BYC' && s.spotter === 'S52LM');
    expect(spot.utcMillis).toBeGreaterThanOrEqual(millis('2026-11-04T00:00:00Z'));
  });

  test('quotedFields', () => {
    expect(splitCsvLine('a,"b,c","d""e",')).toEqual(['a', 'b,c', 'd"e', '']);
  });

  test('stationGridWith8Characters', () => {
    const p = stationLatLon('JO21sc45');
    const q = toLatLon('JO21SC');
    expect(p.latDeg).toBeCloseTo(q.latDeg, 9);
    expect(stationLatLon('')).toBeNull();
  });

  test('regexes', () => {
    expect(CALL_REGEX.test('PA/ON5GS')).toBe(true);
    expect(GRID_REGEX.test('jo21sc45')).toBe(true);
  });
});

describe('N6nuParser', () => {
  // Real answer of livecq.n6nu.org/api/spots (50 spots: 28 CQ, 22 directed).
  const json = resource('n6nu_spots.json');

  test('onlyValidCqCalls', () => {
    const spots = parseN6nu(json);
    expect(spots.length).toBe(27);
    expect(spots.some((s) => s.callsign === 'KB1HY')).toBe(false);
    expect(spots.every((s) => s.sources.size === 1 && s.sources.has('N6NU'))).toBe(true);
    expect(spots.filter((s) => s.band === 'B23CM').length).toBe(22);
    expect(spots.filter((s) => s.band === 'B50').length).toBe(5);
  });

  test('fieldsOfOneSpot', () => {
    const k3sk = parseN6nu(json)[0];
    expect(k3sk.callsign).toBe('K3SK');
    expect(k3sk.utcMillis).toBe(millis('2026-11-13T21:08:00Z'));
    expect(k3sk.grid).toBe('FM07');
    expect(k3sk.freqMhz).toBeCloseTo(1296.09, 9);
    expect(k3sk.mode).toBe('Q65-60C');
    expect(k3sk.db).toBe('-24');
    expect(k3sk.dt).toBe('2.8');
    expect(k3sk.spotter).toBe('W3TI');
    expect(k3sk.spotterGrid).toBe('FN20');
    expect(k3sk.app).toBe('WSJT-X');
  });

  test('missingLocatorAndBadData', () => {
    const text = `{"spots":[
      {"utc":"2026-11-14T10:00:00.000Z","callsign":"OK1DFC","grid":"--","mode":"Q65-60A","msgtype":"CQ","dB":-20,"dt":1,"skedfreq":432.065,"spotter":"PA3DRK","spottergrid":"JO21","apptype":"WSJT-X"},
      {"utc":"2026-11-14T10:00:00.000Z","callsign":"DL7APV","grid":"JO62","mode":"Q65","msgtype":"DIR","skedfreq":144.12,"spotter":"PA3DRK"},
      {"utc":"bad","callsign":"SP5GDM","msgtype":"CQ","skedfreq":1296.06,"spotter":"PA3DRK"},
      {"utc":"2026-11-14T10:00:00.000Z","callsign":"SP5GDM","msgtype":"CQ","skedfreq":null,"spotter":"PA3DRK"}
    ]}`;
    const spots = parseN6nu(text);
    expect(spots.map((s) => s.callsign)).toEqual(['OK1DFC']);
    expect(spots[0].grid).toBe('');
    expect(parseN6nu('{"total":0}')).toEqual([]);
  });
});

describe('LiveCqMerge', () => {
  const t = 1_800_000_000_000 - (1_800_000_000_000 % 60_000);

  const spot = (call, src, { time = t, spotter = 'PA3DRK', grid = '', dt = '', app = '', f = 1296.065 } = {}) => ({
    utcMillis: time,
    callsign: call,
    grid,
    freqMhz: f,
    band: bandForFrequency(f),
    mode: 'Q65-60C',
    db: '-20',
    spotter,
    spotterGrid: 'JO21',
    sources: new Set([src]),
    dt,
    app,
  });

  test('sameDecodeOnBothSitesBecomesOneSpot', () => {
    const w = spot('OK1DFC', 'W3SZ', { grid: 'JO70FD', app: 'MAP65' });
    const n = spot('OK1DFC', 'N6NU', { time: t + 30_000, dt: '2.5', app: 'WSJT-X' });
    const merged = mergeSpots([w], [n]);
    expect(merged.length).toBe(1);
    const m = merged[0];
    expect(m.sources).toEqual(new Set(['W3SZ', 'N6NU']));
    expect(sourceLabel(m)).toBe('W3SZ+N6NU');
    expect(m.grid).toBe('JO70FD');
    expect(m.dt).toBe('2.5');
    expect(m.app).toBe('MAP65');
  });

  test('otherSpotterBandOrMinuteStaysSeparate', () => {
    const w = spot('OK1DFC', 'W3SZ');
    const merged = mergeSpots(
      [w],
      [
        spot('OK1DFC', 'N6NU', { spotter: 'W3TI' }),
        spot('OK1DFC', 'N6NU', { f: 432.065 }),
        spot('OK1DFC', 'N6NU', { time: t + 60_000 }),
      ],
    );
    expect(merged.length).toBe(4);
  });

  test('selectShowsAllSourcesAndKeepsALocator', () => {
    // 11 h later than t: the moon is up at FM07 (31 deg), so #105 does not hide the station
    const u = t + 11 * 3_600_000;
    const older = spot('K3SK', 'W3SZ', { time: u, grid: 'FM07', spotter: 'W3TI' });
    const newer = spot('K3SK', 'N6NU', { time: u + 120_000, grid: '' });
    const shown = selectSpots(mergeSpots([older], [newer]), new Set(['B23CM']), u + 180_000, 60);
    expect(shown.length).toBe(1);
    const s = shown[0];
    expect(s.utcMillis).toBe(u + 120_000);
    expect(s.grid).toBe('FM07');
    expect(s.sources).toEqual(new Set(['W3SZ', 'N6NU']));
  });

  test('w3szCsvHasDtAndProgram', () => {
    const first = parseW3sz(resource('livecq_sample.csv'))[0];
    expect(first.dt).toBe('2.7');
    expect(first.app).toBe('MAP65');
    expect(first.sources).toEqual(new Set(['W3SZ']));
  });
});
