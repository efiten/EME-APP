// Shared HB9Q chat data for the chat screens. Port of ChatStore.kt. The browser talks to web/server, which holds the
// HB9Q session per web session (cookie) and limits the requests to HB9Q; the password is only sent in the login body.

import { bandByName, loggerRows, parseRows } from './lib/hb9q.js';

export const HB9Q_REFRESH_MS = 20_000;

const URL_LOGIN = '/api/hb9q/login';
const URL_LOGOUT = '/api/hb9q/logout';
const urlMessages = (id) => `/api/hb9q/messages?band=${id}`;

const messageOf = (e) => (e && e.message ? e.message : String(e?.name ?? e));

/** An error in the state shape { key, detail }. */
class Hb9qError extends Error {
  constructor(key, detail) {
    super(detail);
    this.key = key;
    this.detail = detail;
  }
}

const initialState = (patch = {}) => ({
  band: null,
  messages: [],
  error: null,
  loading: false,
  lastFetch: 0,
  lastAttempt: 0,
  fetchedAt: null,
  loggedOut: false,
  ...patch,
});

async function errorBody(res) {
  try {
    const j = await res.json();
    return j && typeof j === 'object' ? j : {};
  } catch {
    return {};
  }
}

/** The error for a failed answer of the server. */
async function failure(res, what) {
  const body = await errorBody(res);
  if (res.status === 401 && body.error === 'login_failed') return new Hb9qError('login_failed', '');
  if (res.status === 429 && body.error === 'cooldown') {
    return new Hb9qError('hb9q_error', 'HB9Q: te veel mislukte logins, even wachten');
  }
  if (res.status === 429 && body.error === 'busy') {
    return new Hb9qError('hb9q_error', 'HB9Q: too many logins on this server, try again in a minute');
  }
  if (res.status === 429 && what === 'login') {
    // nginx limits the login per IP and answers with HTML, not with one of our JSON codes
    return new Hb9qError('hb9q_error', 'HB9Q: too many logins from this device, try again in a minute');
  }
  if (body.error === 'upstream' && body.detail) return new Hb9qError('hb9q_error', String(body.detail));
  return new Hb9qError('hb9q_error', `HB9Q ${what}: HTTP ${res.status}`);
}

export function createHb9qStore({ now = () => Date.now() } = {}) {
  let state = initialState();
  const listeners = new Set();
  let controller = null;
  let sessionLogin = null; // login of the server session this browser has, null when none

  const set = (patch) => {
    state = { ...state, ...patch };
    [...listeners].forEach((fn) => fn(state));
  };

  async function login(creds, signal) {
    const res = await fetch(URL_LOGIN, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ login: creds.login, password: creds.password }),
      credentials: 'same-origin',
      signal,
    });
    if (!res.ok) {
      sessionLogin = null;
      throw await failure(res, 'login');
    }
    sessionLogin = creds.login;
  }

  /** The messages answer, or null for 401 no_session / session_expired. */
  async function readMessages(id, signal) {
    const res = await fetch(urlMessages(id), { credentials: 'same-origin', signal });
    if (res.ok) {
      const at = Number(res.headers.get('X-Fetched-At'));
      return { text: await res.text(), fetchedAt: at > 0 ? at : null };
    }
    if (res.status === 401) {
      const body = await errorBody(res);
      if (body.error === 'no_session' || body.error === 'session_expired') {
        sessionLogin = null;
        return null;
      }
    }
    throw await failure(res, 'messages');
  }

  async function fetchMessages(id, creds, signal) {
    let fresh = false; // this fetch made the session itself
    if (sessionLogin !== creds.login) {
      await login(creds, signal);
      fresh = true;
    }
    let r = await readMessages(id, signal);
    if (r === null) {
      // a session that was just made and is refused again is an error, not a reason for a second login
      if (fresh) throw new Hb9qError('hb9q_error', 'HB9Q: session expired');
      await login(creds, signal); // once; a second 401 is an error, not a loop
      r = await readMessages(id, signal);
      if (r === null) throw new Hb9qError('hb9q_error', 'HB9Q: session expired');
    }
    return { messages: parseRows(loggerRows(r.text)), fetchedAt: r.fetchedAt };
  }

  async function refreshIfOlderThan(maxAgeMs, bandName, creds) {
    if (state.loggedOut || controller) return;
    const band = bandByName(bandName);
    if (!band) return;
    if (state.band !== bandName) set(initialState({ band: bandName })); // other band: clear the list
    const t = now();
    if (t - state.lastAttempt < maxAgeMs) return;
    const ctl = new AbortController();
    controller = ctl;
    set({ lastAttempt: t, loading: true });
    try {
      const r = await fetchMessages(band.id, creds, ctl.signal);
      if (ctl.signal.aborted) return;
      controller = null;
      set({ messages: r.messages, error: null, lastFetch: t, fetchedAt: r.fetchedAt ?? now(), loading: false });
    } catch (e) {
      if (ctl.signal.aborted) return; // abort() already reset loading; a cancelled request is no error
      controller = null;
      const error = e instanceof Hb9qError ? { key: e.key, detail: e.detail } : { key: 'hb9q_error', detail: messageOf(e) };
      set({ error, lastFetch: now(), loading: false });
    }
  }

  function abort() {
    if (!controller) return;
    controller.abort();
    controller = null;
    state = { ...state, loading: false };
  }

  return {
    getState: () => state,
    subscribe(listener) {
      listeners.add(listener);
      return () => void listeners.delete(listener);
    },
    refreshIfOlderThan,
    abort,
    /** Log out at the server and forget what the chat held; nothing is fetched until logInAgain(). */
    async logout() {
      abort();
      sessionLogin = null;
      set(initialState({ loggedOut: true }));
      try {
        await fetch(URL_LOGOUT, { method: 'POST', credentials: 'same-origin' });
      } catch {
        // best effort: the server drops an idle session itself
      }
    },
    /** Drop the server session without touching the chat state (Settings: Clear). */
    async forgetServerSession() {
      abort();
      sessionLogin = null;
      try {
        await fetch(URL_LOGOUT, { method: 'POST', credentials: 'same-origin' });
      } catch {
        // best effort: the server drops an idle session itself
      }
    },
    logInAgain() {
      set(initialState());
    },
  };
}

/** The error of `state`, but only when it belongs to `band` (name). */
export const errorFor = (state, band) => (state.band === band ? state.error : null);

export const hb9qStore = createHb9qStore();
