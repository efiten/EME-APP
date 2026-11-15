// @vitest-environment jsdom
import { beforeEach, describe, expect, test } from 'vitest';
import { createSettingsScreen } from '../src/screens/settings.js';
import { setLanguage } from '../src/i18n.js';
import { defaultHorizon } from '../src/lib/horizon.js';

const NOW = Date.parse('2026-11-20T12:00:00Z');

describe('"My horizon" (same as HorizonScreenTest.kt)', () => {
  let host;
  let settings;
  const mount = () => {
    host = document.createElement('div');
    settings = { language: 'en', callsign: 'ON5GS', locator: 'JO21sc', horizon: defaultHorizon() };
    createSettingsScreen().mount(host, {
      settings,
      draft: { callsign: 'ON5GS', locator: 'JO21sc' },
      now: () => NOW,
      onLanguage() {},
      onSaved() {},
    });
  };
  const q = (role) => host.querySelector(`[data-role="${role}"]`);
  const all = (role) => [...host.querySelectorAll(`[data-role="${role}"]`)];
  const type = (input, value) => {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };
  const stored = () => JSON.parse(localStorage.getItem('eme.settings')).horizon;

  beforeEach(() => {
    localStorage.clear();
    setLanguage('en');
    mount();
  });

  test('enter and save the horizon of ON5GS', () => {
    expect(q('horizon-summary').textContent).toBe('Minimum 0° · obstacles: 0');
    q('horizon-edit').click();
    expect(q('horizon-chart')).not.toBeNull();
    expect(all('moon-track').length).toBe(2); // set at 01:36 and rise at 13:39 UTC on 20 Nov 2026

    type(q('min-el'), '5');
    q('horizon-add').click();
    expect(q('horizon-save').disabled).toBe(true); // empty obstacle: not valid yet
    type(q('from'), '60');
    type(q('to'), '110');
    type(q('up-to'), '25');
    expect(q('horizon-save').disabled).toBe(false);
    expect(host.querySelectorAll('.chart rect.blocked').length).toBe(1);

    q('horizon-add').click();
    type(all('from')[1], '180');
    type(all('to')[1], '200');
    type(all('up-to')[1], '20,0'); // comma allowed
    all(`kind-P`)[1].click();
    expect(host.querySelectorAll('.chart rect.partial').length).toBe(1);
    q('horizon-save').click();

    expect(stored()).toBe('1;5;B 60 110 25;P 180 200 20');
    expect(q('horizon-summary').textContent).toBe('Minimum 5° · obstacles: 2');
    expect(host.textContent).toContain('Horizon saved');
  });

  test('wrong numbers block saving, delete removes an obstacle', () => {
    q('horizon-edit').click();
    type(q('min-el'), '95');
    expect(host.textContent).toContain('0 – 90');
    expect(q('horizon-save').disabled).toBe(true);
    type(q('min-el'), '3');

    q('horizon-add').click();
    expect(q('horizon-save').disabled).toBe(true);
    q('horizon-delete').click();
    expect(q('horizon-save').disabled).toBe(false);
    q('horizon-save').click();
    expect(stored()).toBe('1;3');
  });

  test('back leaves without saving', () => {
    q('horizon-edit').click();
    type(q('min-el'), '7');
    q('horizon-back').click();
    expect(q('horizon-summary').textContent).toBe('Minimum 0° · obstacles: 0');
    expect(localStorage.getItem('eme.settings')).toBeNull();
  });

  test('an obstacle across north is drawn in two pieces', () => {
    q('horizon-edit').click();
    q('horizon-add').click();
    type(q('from'), '350');
    type(q('to'), '10');
    type(q('up-to'), '15');
    expect(host.querySelectorAll('.chart rect.blocked').length).toBe(2);
  });
});
