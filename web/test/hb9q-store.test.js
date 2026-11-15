// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { HB9Q_REFRESH_MS, createHb9qStore, errorFor } from '../src/hb9q-store.js';
import { hangs, stubFetch } from './fetch-stub.js';

const LOGIN = '/api/hb9q/login';
const LOGOUT = '/api/hb9q/logout';
const MSG6 = '/api/hb9q/messages?band=6';
const MSG4 = '/api/hb9q/messages?band=4';
const A = { login: 'ON0AAA', password: 'pw-a' };
const B = { login: 'ON0BBB', password: 'pw-b' };

const row = (id, call) =>
  `<tr id="msg_${id}" class="msg_mem_1"><td onclick="showTime(this,'Sat 14th of Nov - 09:0${id}')">09:0${id}</td>` +
  `<td>text ${id}</td><td></td><td>${call}</td><td><span>Name</span></td></tr>`;
const raw = (...rows) => JSON.stringify({ status: 'success', logger: rows.join('') });
const json = (code, obj) => () =>
  new Response(JSON.stringify(obj), { status: code, headers: { 'Content-Type': 'application/json' } });
const messages = (body, at = 1_700_000_000_000) => () =>
  new Response(body, { status: 200, headers: { 'Content-Type': 'text/plain', 'X-Fetched-At': String(at) } });
const loginOk = json(200, { ok: true });

/** Handlers answering from a list, the last answer repeating. */
const sequence = (...answers) => {
  let i = 0;
  return () => answers[Math.min(i++, answers.length - 1)]();
};

let t;
let store;
const clock = () => t;
const callsTo = (stub, url) => stub.mock.calls.filter(([u]) => u === url);

beforeEach(() => {
  t = 1_000_000;
  store = createHb9qStore({ now: clock });
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Hb9qStore', () => {
  test('firstFetchLogsInThenReadsRows', async () => {
    const stub = stubFetch({ [LOGIN]: loginOk, [MSG6]: messages(raw(row(1, 'DL1ABC'), row(2, 'PA3XYZ')), 1234) });
    await store.refreshIfOlderThan(0, 'B1296', A);

    const login = callsTo(stub, LOGIN);
    expect(login).toHaveLength(1);
    expect(login[0][1].method).toBe('POST');
    expect(login[0][1].headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(login[0][1].body)).toEqual({ login: 'ON0AAA', password: 'pw-a' });
    expect(login[0][1].credentials).toBe('same-origin');
    expect(stub.mock.calls.map(([u]) => u)).toEqual([LOGIN, MSG6]);

    const s = store.getState();
    expect(s.band).toBe('B1296');
    expect(s.messages.map((m) => m.callsign)).toEqual(['DL1ABC', 'PA3XYZ']);
    expect(s.fetchedAt).toBe(1234);
    expect(s.error).toBeNull();
    expect(s.loading).toBe(false);
    expect(s.lastFetch).toBe(t);
  });

  test('secondFetchReusesTheSession', async () => {
    const stub = stubFetch({ [LOGIN]: loginOk, [MSG6]: messages(raw(row(1, 'DL1ABC'))) });
    await store.refreshIfOlderThan(0, 'B1296', A);
    t += HB9Q_REFRESH_MS;
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(callsTo(stub, LOGIN)).toHaveLength(1);
    expect(callsTo(stub, MSG6)).toHaveLength(2);
  });

  test('sessionExpiredLogsInAgainOnce', async () => {
    const stub = stubFetch({
      [LOGIN]: loginOk,
      [MSG6]: sequence(messages(raw(row(1, 'DL1ABC'))), json(401, { error: 'session_expired' }), messages(raw(row(1, 'DL1ABC')))),
    });
    await store.refreshIfOlderThan(0, 'B1296', A);
    t += HB9Q_REFRESH_MS;
    await store.refreshIfOlderThan(0, 'B1296', A); // existing session, 401, one re-login
    expect(callsTo(stub, LOGIN)).toHaveLength(2);
    expect(store.getState().error).toBeNull();
    expect(store.getState().messages).toHaveLength(1);
  });

  test('a second 401 after the re-login is an error, not a loop', async () => {
    const stub = stubFetch({
      [LOGIN]: loginOk,
      [MSG6]: sequence(messages(raw(row(1, 'DL1ABC'))), json(401, { error: 'no_session' })),
    });
    await store.refreshIfOlderThan(0, 'B1296', A);
    t += HB9Q_REFRESH_MS;
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(callsTo(stub, LOGIN)).toHaveLength(2); // first fetch + one re-login
    expect(callsTo(stub, MSG6)).toHaveLength(3);
    expect(store.getState().error.key).toBe('hb9q_error');
  });

  test('a 401 right after a fresh login is no reason for a second login in that fetch', async () => {
    const stub = stubFetch({ [LOGIN]: loginOk, [MSG6]: json(401, { error: 'no_session' }) });
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(callsTo(stub, LOGIN)).toHaveLength(1);
    expect(callsTo(stub, MSG6)).toHaveLength(1);
    expect(store.getState().error).toEqual({ key: 'hb9q_error', detail: 'HB9Q: session expired' });
    t += HB9Q_REFRESH_MS;
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(callsTo(stub, LOGIN)).toHaveLength(2); // the next poll logs in again, once
  });

  test('an nginx 429 with an HTML body on the login is shown as a short error', async () => {
    stubFetch({ [LOGIN]: () => new Response('<html>429 Too Many Requests</html>', { status: 429, headers: { 'Content-Type': 'text/html' } }) });
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(store.getState().error).toEqual({
      key: 'hb9q_error',
      detail: 'HB9Q: too many logins from this device, try again in a minute',
    });
  });

  test('forgetServerSession posts a logout, keeps loggedOut false and makes the next fetch log in', async () => {
    const stub = stubFetch({ [LOGIN]: loginOk, [MSG6]: messages(raw(row(1, 'DL1ABC'))), [LOGOUT]: json(200, {}) });
    await store.refreshIfOlderThan(0, 'B1296', A);
    await store.forgetServerSession();
    expect(callsTo(stub, LOGOUT)).toHaveLength(1);
    expect(store.getState().loggedOut).toBe(false);
    t += HB9Q_REFRESH_MS;
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(callsTo(stub, LOGIN)).toHaveLength(2);
  });

  test('forgetServerSession ignores a failing logout', async () => {
    stubFetch({ [LOGOUT]: () => Promise.reject(new TypeError('offline')) });
    await expect(store.forgetServerSession()).resolves.toBeUndefined();
  });

  test('changingTheLoginWhileASessionIsActiveLogsInAgain', async () => {
    const stub = stubFetch({ [LOGIN]: loginOk, [MSG6]: messages(raw(row(1, 'DL1ABC'))) });
    await store.refreshIfOlderThan(0, 'B1296', A);
    t += HB9Q_REFRESH_MS;
    await store.refreshIfOlderThan(0, 'B1296', B);
    expect(callsTo(stub, LOGIN).map(([, init]) => JSON.parse(init.body).login)).toEqual(['ON0AAA', 'ON0BBB']);
    expect(stub.mock.calls.map(([u]) => u)).toEqual([LOGIN, MSG6, LOGIN, MSG6]);
  });

  test('loginFailedIsShownAsLoginFailed', async () => {
    stubFetch({ [LOGIN]: json(401, { error: 'login_failed' }) });
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(store.getState().error).toEqual({ key: 'login_failed', detail: '' });
  });

  test('cooldownIsShownAsHb9qError', async () => {
    stubFetch({ [LOGIN]: json(429, { error: 'cooldown', retryAfterMs: 30000 }) });
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(store.getState().error).toEqual({ key: 'hb9q_error', detail: 'HB9Q: te veel mislukte logins, even wachten' });
  });

  test('busyIsShownAsHb9qError', async () => {
    stubFetch({ [LOGIN]: json(429, { error: 'busy', retryAfterMs: 30000 }) });
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(store.getState().error).toEqual({
      key: 'hb9q_error',
      detail: 'HB9Q: too many logins on this server, try again in a minute',
    });
  });

  test('upstreamErrorKeepsTheMessages', async () => {
    const stub = stubFetch({ [LOGIN]: loginOk, [MSG6]: messages(raw(row(1, 'DL1ABC'))) });
    await store.refreshIfOlderThan(0, 'B1296', A);
    t += HB9Q_REFRESH_MS;
    stub.handlers[MSG6] = json(502, { error: 'upstream', detail: 'logger_lst: HTTP 500' });
    await store.refreshIfOlderThan(0, 'B1296', A);
    const s = store.getState();
    expect(s.error).toEqual({ key: 'hb9q_error', detail: 'logger_lst: HTTP 500' });
    expect(s.messages).toHaveLength(1);
    expect(s.loading).toBe(false);
  });

  test('cancelledFetchIsNotStoredAsError', async () => {
    stubFetch({ [LOGIN]: hangs });
    const p = store.refreshIfOlderThan(0, 'B1296', A);
    expect(store.getState().loading).toBe(true);
    store.abort();
    await p;
    const s = store.getState();
    expect(s.error).toBeNull();
    expect(s.lastFetch).toBe(0);
    expect(s.loading).toBe(false);
    expect(s.band).toBe('B1296');
  });

  test('cancelledFetchStillCountsForTheWaitTime', async () => {
    const stub = stubFetch({ [LOGIN]: hangs });
    const p = store.refreshIfOlderThan(HB9Q_REFRESH_MS, 'B1296', A);
    store.abort();
    await p;
    expect(store.getState().lastAttempt).toBe(t);
    stub.handlers[LOGIN] = loginOk;
    stub.handlers[MSG6] = messages(raw());
    await store.refreshIfOlderThan(HB9Q_REFRESH_MS, 'B1296', A);
    expect(stub).toHaveBeenCalledTimes(1);
  });

  test('failedFetchIsStoredAsError', async () => {
    stubFetch({ [LOGIN]: () => Promise.reject(new TypeError('Failed to fetch')) });
    await store.refreshIfOlderThan(0, 'B1296', A);
    const s = store.getState();
    expect(s.error).toEqual({ key: 'hb9q_error', detail: 'Failed to fetch' });
    expect(s.lastFetch).toBe(t);
    expect(s.loading).toBe(false);
  });

  test('errorOnlyShownForTheBandItBelongsTo', () => {
    const state = { band: 'B432', error: { key: 'hb9q_error', detail: 'boom' } };
    expect(errorFor(state, 'B1296')).toBeNull();
    expect(errorFor(state, 'B432')).toEqual({ key: 'hb9q_error', detail: 'boom' });
  });

  test('otherBandClearsTheList', async () => {
    const stub = stubFetch({
      [LOGIN]: loginOk,
      [MSG6]: messages(raw(row(1, 'DL1ABC'))),
      [MSG4]: () => hangs(),
    });
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(store.getState().messages).toHaveLength(1);
    const p = store.refreshIfOlderThan(HB9Q_REFRESH_MS, 'B432', A);
    expect(store.getState().band).toBe('B432');
    expect(store.getState().messages).toEqual([]);
    expect(callsTo(stub, MSG4)).toHaveLength(1);
    store.abort();
    await p;
  });

  test('withinMaxAgeNothingIsFetched', async () => {
    const stub = stubFetch({ [LOGIN]: loginOk, [MSG6]: messages(raw(row(1, 'DL1ABC'))) });
    await store.refreshIfOlderThan(HB9Q_REFRESH_MS, 'B1296', A);
    const n = stub.mock.calls.length;
    t += HB9Q_REFRESH_MS - 1;
    await store.refreshIfOlderThan(HB9Q_REFRESH_MS, 'B1296', A);
    expect(stub.mock.calls.length).toBe(n);
  });

  test('a call while a request runs makes no new request', async () => {
    const stub = stubFetch({ [LOGIN]: hangs });
    const p = store.refreshIfOlderThan(0, 'B1296', A);
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(stub).toHaveBeenCalledTimes(1);
    store.abort();
    await p;
  });

  test('logoutPostsLogoutAndStopsFetching', async () => {
    const stub = stubFetch({
      [LOGIN]: loginOk,
      [MSG6]: messages(raw(row(1, 'DL1ABC'))),
      [LOGOUT]: () => new Response(null, { status: 204 }),
    });
    await store.refreshIfOlderThan(0, 'B1296', A);
    await store.logout();
    expect(callsTo(stub, LOGOUT)).toHaveLength(1);
    expect(callsTo(stub, LOGOUT)[0][1].method).toBe('POST');
    expect(store.getState().loggedOut).toBe(true);
    expect(store.getState().messages).toEqual([]);

    const n = stub.mock.calls.length;
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(stub.mock.calls.length).toBe(n);

    store.logInAgain();
    expect(store.getState().loggedOut).toBe(false);
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(callsTo(stub, LOGIN)).toHaveLength(2); // the session was dropped by the logout
    expect(store.getState().messages).toHaveLength(1);
  });

  test('passwordOnlyInTheLoginBody', async () => {
    const stub = stubFetch({
      [LOGIN]: loginOk,
      [MSG6]: sequence(json(401, { error: 'session_expired' }), messages(raw(row(1, 'DL1ABC')))),
      [LOGOUT]: () => new Response(null, { status: 204 }),
    });
    await store.refreshIfOlderThan(0, 'B1296', A);
    await store.logout();
    for (const [url, init = {}] of stub.mock.calls) {
      expect(String(url)).not.toContain('pw-a');
      expect(JSON.stringify(init.headers ?? {})).not.toContain('pw-a');
      if (url !== LOGIN) expect(init.body).toBeUndefined();
      else expect(init.body).toContain('pw-a');
    }
  });

  test('subscribe notifies until unsubscribed', async () => {
    stubFetch({ [LOGIN]: loginOk, [MSG6]: messages(raw()) });
    const seen = [];
    const off = store.subscribe((s) => seen.push(s.loading));
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(seen).toContain(true);
    off();
    const n = seen.length;
    t += HB9Q_REFRESH_MS;
    await store.refreshIfOlderThan(0, 'B1296', A);
    expect(seen.length).toBe(n);
  });
});
