// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createMoonScreen } from '../src/screens/moon.js';
import { setLanguage, t } from '../src/i18n.js';

const NOW = Date.parse('2026-11-07T10:00:00Z');
let host;
let screen;
let getContext;
let drawCalls;
let width;
const clientWidth = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');

const settings = (over = {}) => ({ locator: 'JO21SC', callsign: 'ON5GS', cqBands: new Set(['B23CM']), language: 'en', ...over });
const ctxOf = (s, extra = {}) => ({ settings: s, t, now: () => Date.now(), ...extra });
const q = (role) => host.querySelector(`[data-role="${role}"]`);

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
  setLanguage('en');
  getContext = HTMLCanvasElement.prototype.getContext;
  drawCalls = 0;
  width = 375;
  HTMLCanvasElement.prototype.getContext = () => {
    drawCalls++;
    return null;
  };
  Object.defineProperty(HTMLCanvasElement.prototype, 'clientWidth', { get: () => width, configurable: true });
  host = document.createElement('div');
  document.body.append(host);
  screen = createMoonScreen();
});
afterEach(() => {
  screen.unmount();
  vi.useRealTimers();
  vi.restoreAllMocks();
  HTMLCanvasElement.prototype.getContext = getContext;
  delete HTMLCanvasElement.prototype.clientWidth;
  host.remove();
});

describe('moon screen', () => {
  test('shows AZ and EL with 1 decimal for JO21SC at 2026-11-07T10:00:00Z', () => {
    screen.mount(host, ctxOf(settings()));
    expect(q('az').textContent).toBe('182.4°');
    expect(q('el').textContent).toBe('25.1°');
    expect(host.textContent).toContain('ON5GS  ·  JO21SC');
  });

  test('shows distance, phase and the event lines', () => {
    screen.mount(host, ctxOf(settings()));
    expect(q('distance').textContent).toMatch(/^Distance: \d{3},\d{3} km$/);
    expect(q('phase').textContent).toMatch(/^Moon phase: .+ \(\d+% lit\)$/);
    const lines = [...host.querySelectorAll('[data-role="event"]')].map((e) => e.querySelector('.label').textContent);
    expect(lines).toEqual([t('culmination'), t('moonset'), t('next_moonrise')]);
    expect(host.textContent).toContain(t('moon_up_now'));
    expect(host.querySelector('[data-role="event"] .time').textContent).toMatch(/^(.+ )?\d\d:\d\d$/);
  });

  test('clock ticks every second', () => {
    screen.mount(host, ctxOf(settings()));
    const first = q('utc').textContent;
    vi.advanceTimersByTime(1000);
    expect(q('utc').textContent).not.toBe(first);
  });

  test('no locator: message only, no numbers, no map, no NaN', () => {
    const canvasSpy = vi.spyOn(HTMLCanvasElement.prototype, 'getContext');
    screen.mount(host, ctxOf(settings({ locator: '' })));
    expect(host.textContent).toContain(t('no_locator'));
    expect(host.querySelector('canvas')).toBeNull();
    expect(q('az')).toBeNull();
    expect(host.textContent).not.toMatch(/NaN|AZ|EL|°|undefined/);
    vi.advanceTimersByTime(61_000);
    expect(host.textContent).not.toMatch(/NaN/);
    expect(canvasSpy).not.toHaveBeenCalled();
  });

  test('legend lists the chosen bands only, in band order', () => {
    screen.mount(host, ctxOf(settings({ cqBands: new Set(['B432', 'B144']) })));
    const labels = [...host.querySelectorAll('[data-role="legend"] .band')].map((e) => e.textContent);
    expect(labels).toEqual(['144', '432']);
  });

  test('timers pause while the page is hidden and resume when visible', () => {
    let state = 'visible';
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => state);
    screen.mount(host, ctxOf(settings()));
    state = 'hidden';
    document.dispatchEvent(new Event('visibilitychange'));
    const frozen = q('utc').textContent;
    vi.advanceTimersByTime(5000);
    expect(q('utc').textContent).toBe(frozen);
    state = 'visible';
    document.dispatchEvent(new Event('visibilitychange'));
    expect(q('utc').textContent).not.toBe(frozen);
  });

  test('unmount stops timers and removes listeners', () => {
    const remove = vi.spyOn(document, 'removeEventListener');
    screen.mount(host, ctxOf(settings()));
    screen.unmount();
    expect(remove).toHaveBeenCalledWith('visibilitychange', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });

  test('resize redraws the map only when the width changed, coalesced per frame', () => {
    screen.mount(host, ctxOf(settings()));
    expect(drawCalls).toBe(1);
    window.dispatchEvent(new Event('resize'));
    vi.advanceTimersByTime(50);
    expect(drawCalls).toBe(1);
    width = 390;
    window.dispatchEvent(new Event('resize'));
    window.dispatchEvent(new Event('resize'));
    vi.advanceTimersByTime(50);
    expect(drawCalls).toBe(2);
  });
});
