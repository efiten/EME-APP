// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createLiveCqStore, createN0ukStore, fetchLiveCq, fetchN0uk } from '../src/data.js';
import { callsTo, fails, hangs, ok, resource, status, stubFetch } from './fetch-stub.js';

const W3SZ = '/api/livecq/w3sz';
const N6NU = '/api/livecq/n6nu';
const N0UK = '/api/n0uk';
const T0 = Date.parse('2026-11-14T14:30:00Z');

const HEADER = 'skedfreq,rxfreq,rpol,dt,dB,msgtype,callsign,grid,mode,utcdatetime,spotter,spottergrid,txpol,apptype,band,uploadtime';
const csv = (...rows) => [HEADER, ...rows].join('\n');
const csvRow = (call) => `1296.065,0,0,2.7,-20,CQ,${call},JO21,Q65-60C,"2026-11-14 14:29:00",PA3DRK,JO21,/,MAP65,1296,"2026-11-14 14:29:40"`;
const n6nuJson = (...calls) =>
  JSON.stringify({
    spots: calls.map((c) => ({
      utc: '2026-11-14T14:29:10.000Z', callsign: c, grid: 'JO21', mode: 'Q65-60C', msgtype: 'CQ', dB: -20, dt: 1,
      skedfreq: 1296.065, spotter: 'PA3DRK', spottergrid: 'JO21', apptype: 'WS',
    })),
  });

let unsubscribe;
const open = (store) => {
  unsubscribe = store.subscribe(() => {});
};
const close = () => {
  unsubscribe();
  unsubscribe = null;
};
const settle = () => vi.advanceTimersByTimeAsync(0);

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(T0);
  unsubscribe = null;
});
afterEach(() => {
  unsubscribe?.();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('fetchLiveCq', () => {
  test('merges both endpoints and reports the oldest copy time', async () => {
    stubFetch({
      [W3SZ]: ok(csv(csvRow('OK1DFC')), { 'X-Fetched-At': String(T0 - 40_000) }),
      [N6NU]: ok(n6nuJson('OK1DFC', 'K3SK'), { 'X-Fetched-At': String(T0 - 10_000) }),
    });
    const r = await fetchLiveCq();
    expect(r.spots.map((s) => s.callsign).sort()).toEqual(['K3SK', 'OK1DFC']);
    expect([...r.spots.find((s) => s.callsign === 'OK1DFC').sources].sort()).toEqual(['N6NU', 'W3SZ']);
    expect(r.fetchedAt).toBe(T0 - 40_000);
    expect(r.error).toBeNull();
  });

  test('a copy with X-Upstream-Error is used and the error is reported', async () => {
    stubFetch({
      [W3SZ]: ok(csv(csvRow('OK1DFC')), { 'X-Fetched-At': String(T0), 'X-Upstream-Error': 'W3SZ HTTP 500' }),
      [N6NU]: ok(n6nuJson(), { 'X-Fetched-At': String(T0) }),
    });
    const r = await fetchLiveCq();
    expect(r.spots.map((s) => s.callsign)).toEqual(['OK1DFC']);
    expect(r.error).toBe('W3SZ HTTP 500');
  });

  test('502 without any copy: error from X-Upstream-Error, empty list', async () => {
    const down = status(502, 'W3SZ HTTP 500', { 'X-Upstream-Error': 'W3SZ HTTP 500' });
    stubFetch({ [W3SZ]: down, [N6NU]: down });
    const r = await fetchLiveCq();
    expect(r.spots).toEqual([]);
    expect(r.error).toBe('W3SZ HTTP 500; W3SZ HTTP 500');
    expect(r.fetchedAt).toBeNull();
  });
});

describe('fetchN0uk', () => {
  test('parses the page and returns the copy time', async () => {
    stubFetch({ [N0UK]: ok(resource('n0uk_jt65emeA.html'), { 'X-Fetched-At': String(T0) }) });
    const r = await fetchN0uk({ now: Date.parse('2026-11-13T15:02:00Z') });
    expect(r.messages).toHaveLength(50);
    expect(r.fetchedAt).toBe(T0);
    expect(r.error).toBeNull();
  });
});

describe('Live CQ store (port of LiveCqStoreTest.kt)', () => {
  const both = () => ({
    [W3SZ]: ok(csv(csvRow('OK1DFC'))),
    [N6NU]: ok(n6nuJson('OK1DFC', 'K3SK')),
  });

  test('bothSourcesMerged', async () => {
    stubFetch(both());
    const store = createLiveCqStore();
    await store.refreshIfOlderThan(0);
    const s = store.getState();
    expect(s.spots.map((x) => x.callsign)).toEqual(['OK1DFC', 'K3SK']);
    expect([...s.spots[0].sources].sort()).toEqual(['N6NU', 'W3SZ']);
    expect(s.error).toBeNull();
    expect(s.loading).toBe(false);
  });

  test('notMoreOftenThanBefore', async () => {
    const f = stubFetch(both());
    const store = createLiveCqStore();
    const at = async (ms, maxAge) => {
      vi.setSystemTime(T0 + ms);
      await store.refreshIfOlderThan(maxAge);
    };
    await at(0, 120_000);
    await at(119_999, 120_000);
    await at(29_999, 0); // refresh button: minimum 30 s
    expect([callsTo(f, W3SZ), callsTo(f, N6NU)]).toEqual([1, 1]);
    await at(30_000, 0);
    expect([callsTo(f, W3SZ), callsTo(f, N6NU)]).toEqual([2, 2]);
    await at(149_999, 120_000);
    expect(callsTo(f, W3SZ)).toBe(2);
    await at(150_000, 120_000);
    expect([callsTo(f, W3SZ), callsTo(f, N6NU)]).toEqual([3, 3]);
  });

  test('oneSourceFailingKeepsTheOtherAndItsOldList', async () => {
    const f = stubFetch(both());
    const store = createLiveCqStore();
    await store.refreshIfOlderThan(0);
    f.handlers[N6NU] = status(503);
    f.handlers[W3SZ] = ok(csv(csvRow('OK1DFC'), csvRow('PA0NEW')));
    vi.setSystemTime(T0 + 30_000);
    await store.refreshIfOlderThan(0);
    const s = store.getState();
    expect(s.error).toBe('N6NU HTTP 503');
    expect(s.spots.map((x) => x.callsign).sort()).toEqual(['K3SK', 'OK1DFC', 'PA0NEW']); // new W3SZ list, old N6NU list
    expect(callsTo(f, W3SZ)).toBe(2);
  });

  test('cancelledFetchIsNotStoredAsError', async () => {
    stubFetch({ [W3SZ]: hangs, [N6NU]: ok(n6nuJson()) });
    const store = createLiveCqStore();
    open(store);
    await settle();
    expect(store.getState().loading).toBe(true);
    close();
    await settle();
    const s = store.getState();
    expect(s.error).toBeNull();
    expect(s.lastFetch).toBe(0);
    expect(s.loading).toBe(false);
  });

  test('failedFetchIsStoredAsError', async () => {
    stubFetch({ [W3SZ]: fails('Failed to fetch'), [N6NU]: ok(n6nuJson()) });
    const store = createLiveCqStore();
    await store.refreshIfOlderThan(0);
    const s = store.getState();
    expect(s.error).toBe('Failed to fetch');
    expect(s.lastFetch).toBeGreaterThan(0);
    expect(s.loading).toBe(false);
  });

  test('cancelledFetchIsNoErrorAndStillCountsForThe30Seconds', async () => {
    const f = stubFetch({ [W3SZ]: hangs, [N6NU]: ok(n6nuJson('K3SK')) });
    const store = createLiveCqStore();
    open(store);
    await settle();
    close();
    await settle();
    expect(store.getState().error).toBeNull();
    f.handlers[W3SZ] = ok(csv(csvRow('OK1DFC')));
    vi.setSystemTime(T0 + 5_000);
    await store.refreshIfOlderThan(0);
    expect(callsTo(f, W3SZ)).toBe(1);
    vi.setSystemTime(T0 + 30_000);
    await store.refreshIfOlderThan(0);
    expect(callsTo(f, W3SZ)).toBe(2);
    expect(store.getState().spots).not.toEqual([]);
  });

  test('network error keeps the previous list and sets the error', async () => {
    const f = stubFetch(both());
    const store = createLiveCqStore();
    await store.refreshIfOlderThan(0);
    f.handlers[W3SZ] = fails('Failed to fetch');
    f.handlers[N6NU] = fails('Failed to fetch');
    vi.setSystemTime(T0 + 30_000);
    await store.refreshIfOlderThan(0);
    expect(store.getState().error).toBe('Failed to fetch; Failed to fetch');
    expect(store.getState().spots.map((x) => x.callsign)).toEqual(['OK1DFC', 'K3SK']);
  });

  test('X-Fetched-At is what the screen shows as the update time', async () => {
    stubFetch({
      [W3SZ]: ok(csv(), { 'X-Fetched-At': String(T0 - 90_000) }),
      [N6NU]: ok(n6nuJson(), { 'X-Fetched-At': String(T0 - 20_000) }),
    });
    const store = createLiveCqStore();
    await store.refreshIfOlderThan(0);
    expect(store.getState().fetchedAt).toBe(T0 - 90_000);
  });
});

describe('polling', () => {
  test('two subscribers share one poller: one request per source per 120 s', async () => {
    const f = stubFetch({ [W3SZ]: ok(csv()), [N6NU]: ok(n6nuJson()) });
    const store = createLiveCqStore();
    open(store);
    const second = store.subscribe(() => {});
    await settle();
    expect(callsTo(f, W3SZ)).toBe(1);
    await vi.advanceTimersByTimeAsync(105_000);
    expect(callsTo(f, W3SZ)).toBe(1);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(callsTo(f, W3SZ)).toBe(2);
    second();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(callsTo(f, W3SZ)).toBe(3); // still polling for the remaining subscriber
  });

  test('stops when the last subscriber leaves, restarts with the next one', async () => {
    const f = stubFetch({ [W3SZ]: ok(csv()), [N6NU]: ok(n6nuJson()) });
    const store = createLiveCqStore();
    open(store);
    await settle();
    close();
    await vi.advanceTimersByTimeAsync(600_000);
    expect(callsTo(f, W3SZ)).toBe(1);
    open(store);
    await settle();
    expect(callsTo(f, W3SZ)).toBe(2);
  });

  test('no request while the page is hidden; catches up when visible again', async () => {
    let state = 'hidden';
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => state);
    const f = stubFetch({ [W3SZ]: ok(csv()), [N6NU]: ok(n6nuJson()) });
    const store = createLiveCqStore();
    open(store);
    await vi.advanceTimersByTimeAsync(300_000);
    expect(f).not.toHaveBeenCalled();
    state = 'visible';
    document.dispatchEvent(new Event('visibilitychange'));
    await settle();
    expect(callsTo(f, W3SZ)).toBe(1);
    state = 'hidden';
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(600_000);
    expect(callsTo(f, W3SZ)).toBe(1);
  });

  test('a late response after unsubscribe does not reach the listener', async () => {
    let release;
    const late = new Promise((r) => (release = r));
    // a fetch that ignores the abort signal, like a response already on its way
    vi.stubGlobal('fetch', vi.fn((url) => (url === W3SZ ? late : Promise.resolve(new Response(n6nuJson())))));
    const store = createLiveCqStore();
    const seen = vi.fn();
    unsubscribe = store.subscribe(seen);
    await settle();
    seen.mockClear();
    close();
    release(new Response(csv(csvRow('LATE1A'))));
    await settle();
    expect(seen).not.toHaveBeenCalled();
    expect(store.getState().spots).toEqual([]);
  });
});

describe('N0UK store', () => {
  const NOW = Date.parse('2026-11-13T15:02:00Z');
  beforeEach(() => vi.setSystemTime(NOW));

  test('fetches, parses, and does not ask more often than every 90 s', async () => {
    const f = stubFetch({ [N0UK]: ok(resource('n0uk_jt65emeA.html'), { 'X-Fetched-At': String(NOW) }) });
    const store = createN0ukStore();
    await store.refreshIfOlderThan(0);
    expect(store.getState().messages).toHaveLength(50);
    vi.setSystemTime(NOW + 89_999);
    await store.refreshIfOlderThan(0);
    expect(callsTo(f, N0UK)).toBe(1);
    vi.setSystemTime(NOW + 90_000);
    await store.refreshIfOlderThan(0);
    expect(callsTo(f, N0UK)).toBe(2);
  });

  test('an error keeps the old messages', async () => {
    const f = stubFetch({ [N0UK]: ok(resource('n0uk_jt65emeA.html')) });
    const store = createN0ukStore();
    await store.refreshIfOlderThan(0);
    f.handlers[N0UK] = status(502, 'N0UK HTTP 500', { 'X-Upstream-Error': 'N0UK HTTP 500' });
    vi.setSystemTime(NOW + 90_000);
    await store.refreshIfOlderThan(0);
    expect(store.getState().error).toBe('N0UK HTTP 500');
    expect(store.getState().messages).toHaveLength(50);
  });

  test('polls every 90 s while subscribed, and a cancelled request is no error', async () => {
    const f = stubFetch({ [N0UK]: ok(resource('n0uk_jt65emeA.html')) });
    const store = createN0ukStore();
    open(store);
    await settle();
    await vi.advanceTimersByTimeAsync(95_000);
    expect(callsTo(f, N0UK)).toBe(2);
    f.handlers[N0UK] = hangs;
    await vi.advanceTimersByTimeAsync(90_000);
    close();
    await settle();
    expect(store.getState().error).toBeNull();
    expect(store.getState().loading).toBe(false);
  });
});
