// Shared data for the whole page: one Live CQ store and one N0UK store, each with one poller.
// Port of LiveCqStore.kt and N0ukStore.kt. A store polls only while at least one screen is subscribed and the
// page is visible. The Go server (web/server) already limits the requests to the real sites for all users together.

import { mergeSpots, parseN6nu, parseW3sz } from './lib/livecq.js';
import { parseN0uk } from './lib/n0uk.js';
import { visibleInterval } from './lib/visible-interval.js';

const URL_W3SZ = '/api/livecq/w3sz';
const URL_N6NU = '/api/livecq/n6nu';
const URL_N0UK = '/api/n0uk';

export const LIVECQ_REFRESH_MS = 120_000;
/** Minimum time between two Live CQ fetches, also for the refresh button and after a cancelled request. */
export const LIVECQ_MIN_FETCH_MS = 30_000;
export const N0UK_REFRESH_MS = 90_000;

const isAbort = (signal) => signal?.aborted === true;
const messageOf = (e) => (e && e.message ? e.message : String(e?.name ?? e));

/** Body text of one endpoint, with the copy time and the upstream error of the server. Throws on an HTTP error. */
async function getText(url, name, signal) {
  const res = await fetch(url, { signal });
  const upstream = res.headers.get('X-Upstream-Error') || null;
  if (!res.ok) throw new Error(upstream ?? `${name} HTTP ${res.status}`);
  const at = Number(res.headers.get('X-Fetched-At'));
  return { text: await res.text(), fetchedAt: at > 0 ? at : null, error: upstream };
}

/** One source: its parsed list, or the error with the list it had before. */
async function source(url, name, parse, previous, signal) {
  try {
    const r = await getText(url, name, signal);
    return { list: parse(r.text), fetchedAt: r.fetchedAt, error: r.error };
  } catch (e) {
    if (isAbort(signal)) throw e;
    return { list: previous, fetchedAt: null, error: messageOf(e) };
  }
}

const joinErrors = (errors) => errors.filter(Boolean).join('; ') || null;
const oldest = (times) => {
  const known = times.filter((x) => x !== null);
  return known.length ? Math.min(...known) : null;
};

/**
 * W3SZ and N6NU together, merged. A source that fails keeps its list from `previous` ({w3sz, n6nu}) and the other
 * source still delivers its new list. Rejects only when `signal` is aborted.
 * Returns { spots, fetchedAt (oldest copy of the two, or null), error, lists }.
 */
export async function fetchLiveCq({ signal, previous = {} } = {}) {
  const [w, n] = await Promise.all([
    source(URL_W3SZ, 'W3SZ', parseW3sz, previous.w3sz ?? [], signal),
    source(URL_N6NU, 'N6NU', parseN6nu, previous.n6nu ?? [], signal),
  ]);
  return {
    spots: mergeSpots(w.list, n.list),
    fetchedAt: oldest([w.fetchedAt, n.fetchedAt]),
    error: joinErrors([w.error, n.error]),
    lists: { w3sz: w.list, n6nu: n.list },
  };
}

/** The N0UK chat as messages; on an error the `previous` messages come back with it. Rejects only on abort. */
export async function fetchN0uk({ signal, previous = [], now = Date.now() } = {}) {
  try {
    const r = await getText(URL_N0UK, 'N0UK', signal);
    return { messages: parseN0uk(r.text, now), fetchedAt: r.fetchedAt, error: r.error };
  } catch (e) {
    if (isAbort(signal)) throw e;
    return { messages: previous, fetchedAt: null, error: messageOf(e) };
  }
}

/**
 * Generic store with one poller. load(signal, state, t) resolves to a patch for the state (and rejects only when
 * aborted). lastFetch is the end of the last attempt that was not cancelled, lastAttempt the start of the last
 * attempt, cancelled or not: both count for the minimum time between two requests.
 */
function createStore({ initial, load, autoMs, minMs, checkMs, now = () => Date.now() }) {
  let state = { ...initial, fetchedAt: null, error: null, loading: false, lastFetch: 0, lastAttempt: 0 };
  const listeners = new Set();
  let stopPolling = null;
  let controller = null;

  const set = (patch, emit = true) => {
    state = { ...state, ...patch };
    if (emit) [...listeners].forEach((fn) => fn(state));
  };

  function abort() {
    if (!controller) return;
    controller.abort();
    controller = null;
    set({ loading: false }, false);
  }

  async function refreshIfOlderThan(maxAgeMs) {
    if (controller) return;
    const t = now();
    if (state.lastFetch !== 0 && t - state.lastFetch < Math.max(maxAgeMs, minMs)) return;
    if (state.lastAttempt !== 0 && t - state.lastAttempt < minMs) return;
    const ctl = new AbortController();
    controller = ctl;
    set({ loading: true, lastAttempt: t });
    try {
      const patch = await load(ctl.signal, state, t);
      if (ctl.signal.aborted) return; // a response that arrives after stop belongs to nobody
      controller = null;
      set({ ...patch, fetchedAt: patch.fetchedAt ?? state.fetchedAt, lastFetch: now(), loading: false });
    } catch (e) {
      if (ctl.signal.aborted) return; // abort() already reset loading; no error for a cancelled request
      controller = null;
      set({ error: messageOf(e), lastFetch: now(), loading: false });
    }
  }

  return {
    getState: () => state,
    refreshIfOlderThan,
    /** Refresh button: not more often than the minimum time. */
    refresh: () => refreshIfOlderThan(0),
    /** Starts polling with the first listener, stops with the last. Returns the unsubscribe function. */
    subscribe(listener) {
      listeners.add(listener);
      if (stopPolling === null) {
        stopPolling = visibleInterval(() => refreshIfOlderThan(autoMs), checkMs, abort);
      }
      return () => {
        if (!listeners.delete(listener) || listeners.size > 0) return;
        stopPolling();
        stopPolling = null;
        abort();
      };
    },
  };
}

export function createLiveCqStore(options = {}) {
  return createStore({
    initial: { spots: [], lists: { w3sz: [], n6nu: [] } },
    load: async (signal, state) => {
      const r = await fetchLiveCq({ signal, previous: state.lists });
      return { spots: r.spots, lists: r.lists, fetchedAt: r.fetchedAt, error: r.error };
    },
    autoMs: LIVECQ_REFRESH_MS,
    minMs: LIVECQ_MIN_FETCH_MS,
    checkMs: 15_000,
    ...options,
  });
}

export function createN0ukStore(options = {}) {
  return createStore({
    initial: { messages: [] },
    load: async (signal, state, t) => {
      const r = await fetchN0uk({ signal, previous: state.messages, now: t });
      return { messages: r.messages, fetchedAt: r.fetchedAt, error: r.error };
    },
    autoMs: N0UK_REFRESH_MS,
    minMs: N0UK_REFRESH_MS,
    checkMs: 5_000,
    ...options,
  });
}

export const liveCqStore = createLiveCqStore();
export const n0ukStore = createN0ukStore();
