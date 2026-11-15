// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createHb9qScreen } from '../src/screens/hb9q.js';
import { createHb9qStore } from '../src/hb9q-store.js';
import { loadSettings } from '../src/settings.js';
import { setLanguage, t } from '../src/i18n.js';
import { callsTo, resource, stubFetch } from './fetch-stub.js';

const LOGIN = '/api/hb9q/login';
const LOGOUT = '/api/hb9q/logout';
const MSG6 = '/api/hb9q/messages?band=6';
const MSG4 = '/api/hb9q/messages?band=4';
const CREDS = { login: 'ON0AAA', password: 'pw-a' };
const NOW = Date.parse('2026-11-08T09:20:00Z');

const row = (id, hhmm, call, text, { recipient = '', cq = false, name = 'Name' } = {}) =>
  `<tr id="msg_${id}" class="msg_mem_1${cq ? ' msgtype_10' : ''}"><td onclick="showTime(this,'Sun 8th of Nov - ${hhmm}')">${hhmm}</td>` +
  `<td>${recipient ? `<span>@${recipient}: </span>` : ''}${text}</td><td></td><td>${call}</td><td><span>${name}</span></td></tr>`;
const body = (...rows) => JSON.stringify({ status: 'success', logger: rows.join('') });
const respond = (text) => () => new Response(text, { status: 200, headers: { 'X-Fetched-At': String(NOW - 5_000) } });
const loginOk = () => new Response('{"ok":true}', { status: 200, headers: { 'Content-Type': 'application/json' } });

let host;
let screen;
let store;
const mount = async (over = {}) => {
  const settings = { callsign: 'ON5GS', locator: 'JO21SC', language: 'en', chatBand: 'B1296', ...over.settings };
  screen.mount(host, { t, now: () => Date.now(), hb9q: store, credentials: CREDS, ...over, settings });
  await vi.advanceTimersByTimeAsync(0);
};
const q = (role) => host.querySelector(`[data-role="${role}"]`);
const qa = (role) => [...host.querySelectorAll(`[data-role="${role}"]`)];
const chip = (label) => [...host.querySelectorAll('.chip')].find((c) => c.textContent === label);
const subtab = (i) => host.querySelectorAll('[role="tab"]')[i];
const button = (label) => [...host.querySelectorAll('button')].find((b) => b.textContent === label);

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
  setLanguage('en');
  localStorage.clear();
  stubFetch({
    [LOGIN]: loginOk,
    [MSG6]: respond(
      body(
        row(3, '09:03', 'DL1ABC', 'hello all'),
        row(2, '09:02', 'PA3XYZ', 'hi', { recipient: 'ON5GS' }),
        row(1, '09:01', 'ON5GS', 'my own'),
      ),
    ),
    [LOGOUT]: () => new Response('{}', { status: 200 }),
  });
  store = createHb9qStore();
  host = document.createElement('div');
  document.body.append(host);
  screen = createHb9qScreen();
});
afterEach(() => {
  screen.unmount();
  host.remove();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('HB9Q screen', () => {
  test('needLoginWithoutCredentials', async () => {
    await mount({ credentials: null });
    expect(host.textContent).toContain(t('need_login'));
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fetch).not.toHaveBeenCalled();
  });

  test('rendersMessagesOfTheStore', async () => {
    await mount();
    const rows = qa('message');
    expect(rows).toHaveLength(3);
    expect(rows[0].textContent).toContain('09:03');
    expect(rows[0].textContent).toContain('DL1ABC');
    expect(rows[0].textContent).toContain('Name');
    expect(rows[0].textContent).toContain('hello all');
    expect(rows[1].textContent).toContain('→ ON5GS');
    expect(rows[2].querySelector('.call').classList.contains('mine')).toBe(true);
    expect(rows[0].querySelector('.call').classList.contains('mine')).toBe(false);
    expect([0, 1, 2].map((i) => subtab(i).textContent)).toEqual([t('messages'), t('participants', 2), t('tab_chatcq')]);
  });

  test('messageToMeIsMarked', async () => {
    await mount();
    expect(qa('message').map((r) => r.classList.contains('to-me'))).toEqual([false, true, false]);
  });

  test('cqTextHasTheCqClass', async () => {
    stubFetch({
      [LOGIN]: loginOk,
      [MSG6]: respond(body(row(2, '09:02', 'DL1ABC', '***** CQ 1296.088 *****', { cq: true }), row(1, '09:01', 'PA3XYZ', 'plain'))),
    });
    await mount();
    const rows = qa('message');
    expect(rows[0].querySelector('.text').classList.contains('cq')).toBe(true);
    expect(rows[1].querySelector('.text').classList.contains('cq')).toBe(false);
  });

  test('bandChipSwitchesAndSavesChatBand', async () => {
    const f = stubFetch({
      [LOGIN]: loginOk,
      [MSG6]: respond(body(row(1, '09:01', 'DL1ABC', 'on 23cm'))),
      [MSG4]: respond(body(row(5, '09:05', 'F5AOL', 'on 70cm'))),
    });
    await mount();
    expect(host.querySelectorAll('.chip')).toHaveLength(13);
    expect(chip('1296').getAttribute('aria-pressed')).toBe('true');
    chip('432').click();
    await vi.advanceTimersByTimeAsync(0);
    expect(loadSettings().chatBand).toBe('B432');
    expect(chip('432').getAttribute('aria-pressed')).toBe('true');
    expect(chip('1296').getAttribute('aria-pressed')).toBe('false');
    expect(callsTo(f, MSG4)).toBe(1);
    expect(qa('message')).toHaveLength(1);
    expect(host.textContent).toContain('on 70cm');
  });

  test('bandChipSwitchesWhileARequestIsRunning', async () => {
    const f = stubFetch({
      [LOGIN]: loginOk,
      [MSG6]: () => new Promise(() => {}),
      [MSG4]: respond(body(row(5, '09:05', 'F5AOL', 'on 70cm'))),
    });
    await mount();
    expect(q('loading').hidden).toBe(false);
    chip('432').click();
    await vi.advanceTimersByTimeAsync(0);
    expect(callsTo(f, MSG4)).toBe(1);
    expect(qa('message')).toHaveLength(1);
    expect(q('loading').hidden).toBe(true);
  });

  test('logoutShowsLoggedOutAndLogInResumes', async () => {
    await mount();
    button(t('log_out')).click();
    await vi.advanceTimersByTimeAsync(0);
    expect(q('logged-out').hidden).toBe(false);
    expect(host.textContent).toContain(t('logged_out'));
    expect(qa('message')).toHaveLength(0);
    expect(callsTo(fetch, LOGOUT)).toBe(1);
    const before = callsTo(fetch, MSG6);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(callsTo(fetch, MSG6)).toBe(before);
    button(t('log_in')).click();
    await vi.advanceTimersByTimeAsync(0);
    expect(q('logged-out').hidden).toBe(true);
    expect(callsTo(fetch, MSG6)).toBe(before + 1);
    expect(qa('message')).toHaveLength(3);
  });

  test('errorShownOnlyForItsBand', async () => {
    stubFetch({ [LOGIN]: () => new Response('{"error":"upstream","detail":"boom"}', { status: 502 }) });
    await mount();
    expect(q('error').textContent).toBe(t('hb9q_error', 'boom'));
    stubFetch({ [LOGIN]: loginOk, [MSG4]: respond(body(row(5, '09:05', 'F5AOL', 'x'))) });
    chip('432').click();
    await vi.advanceTimersByTimeAsync(0);
    expect(q('error')).toBeNull();
  });

  test('errorOfAnotherBandIsNotShown', async () => {
    const state = { band: 'B432', messages: [], error: { key: 'hb9q_error', detail: 'x' }, loading: false, loggedOut: false, fetchedAt: null };
    const other = {
      getState: () => state,
      subscribe: () => () => {},
      refreshIfOlderThan: async () => {},
      abort() {},
      logout: async () => {},
      logInAgain() {},
    };
    await mount({ hb9q: other });
    expect(q('error')).toBeNull();
  });

  test('participantsListWritersWithoutMe', async () => {
    await mount();
    subtab(1).click();
    expect(host.textContent).toContain(t('recent_in_chat'));
    expect(qa('writer').map((r) => r.querySelector('.call').textContent)).toEqual(['DL1ABC', 'PA3XYZ']);
    expect(qa('writer')[0].textContent).toContain('09:03');
    expect(qa('writer')[0].textContent).toContain('Name');
  });

  test('chatCqTableFromTheRows', async () => {
    stubFetch({ [LOGIN]: loginOk, [MSG6]: respond(JSON.stringify({ status: 'success', logger: resource('hb9q_rows_8nov.html') })) });
    await mount();
    subtab(2).click();
    const rows = qa('cq');
    const f5aol = rows.find((r) => r.textContent.includes('F5AOL'));
    expect([...f5aol.children].map((c) => c.textContent)).toEqual(['09:01', '1296.088', '1st', 'F5AOL', 'Q65-60C', 'CFOM', '1500']);
    expect(host.textContent).toContain(t('chatcq_title', '1296', rows.length));
    expect(q('cq-header').textContent).toBe(t('chatcq_header').split(' - ').join(''));
  });

  test('chatCqOwnEchoShowsEcho', async () => {
    stubFetch({
      [LOGIN]: loginOk,
      [MSG6]: respond(body(row(2, '09:10', 'DL1ABC', '***** CQ 1296.100 1st JT65C own echo *****', { cq: true }))),
    });
    await mount();
    subtab(2).click();
    expect([...qa('cq')[0].children].map((c) => c.textContent)[5]).toBe('echo');
  });

  test('chatCqWindowIsRecheckedEvery5sWithoutAFetch', async () => {
    stubFetch({
      [LOGIN]: loginOk,
      [MSG6]: respond(body(row(2, '09:10', 'DL1ABC', '***** CQ 1296.100 1st JT65C *****', { cq: true }))),
    });
    let clock = NOW;
    await mount({ now: () => clock });
    subtab(2).click();
    expect(qa('cq')).toHaveLength(1);
    const fetches = callsTo(fetch, MSG6);
    clock = Date.parse('2026-11-08T10:11:00Z'); // 61 minutes after the CQ
    await vi.advanceTimersByTimeAsync(5_000);
    expect(callsTo(fetch, MSG6)).toBe(fetches);
    expect(qa('cq')).toHaveLength(0);
  });

  test('messagesPaneKeepsItsNodesAcrossA5sTickWithoutAFetch', async () => {
    await mount();
    const before = qa('message');
    expect(before.length).toBeGreaterThan(0);
    const fetches = callsTo(fetch, MSG6);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(callsTo(fetch, MSG6)).toBe(fetches);
    expect(qa('message').every((n, i) => n === before[i])).toBe(true);
  });

  test('chatCqEmptyShowsEmptyText', async () => {
    await mount();
    subtab(2).click();
    expect(host.textContent).toContain(t('chatcq_empty'));
  });

  test('markupInMessagesStaysText', async () => {
    stubFetch({
      [LOGIN]: loginOk,
      [MSG6]: respond(body(row(1, '09:01', 'DL1ABC', '&lt;img src=x onerror=alert(1)&gt;', { name: '&lt;b&gt;Bo&lt;/b&gt;' }))),
    });
    await mount();
    expect(host.querySelector('img')).toBeNull();
    expect(host.querySelector('b')).toBeNull();
    expect(host.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  test('refreshButtonFetchesAgain', async () => {
    await mount();
    await vi.advanceTimersByTimeAsync(6_000);
    const before = callsTo(fetch, MSG6);
    button(t('refresh')).click();
    await vi.advanceTimersByTimeAsync(0);
    expect(callsTo(fetch, MSG6)).toBe(before + 1);
  });

  test('pollsWhileMountedAndStopsAfterUnmount', async () => {
    await mount();
    expect(callsTo(fetch, MSG6)).toBe(1);
    await vi.advanceTimersByTimeAsync(41_000);
    expect(callsTo(fetch, MSG6)).toBe(3);
    screen.unmount();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(callsTo(fetch, MSG6)).toBe(3);
  });
});
