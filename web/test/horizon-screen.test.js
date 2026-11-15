// @vitest-environment jsdom
import { beforeEach, describe, expect, test } from 'vitest';
import { createSettingsScreen } from '../src/screens/settings.js';
import { setLanguage } from '../src/i18n.js';
import { defaultHorizon } from '../src/lib/horizon.js';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseSurvey } from '../src/lib/horizon-survey.js';
import { encode } from '../src/lib/horizon.js';

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

  test('dish diameter gives the margin and is saved; empty means no dish', () => {
    q('horizon-edit').click();
    expect(q('dish-support').textContent).toContain('Leave empty for no margin');
    type(q('dish'), '150');
    expect(q('dish-support').textContent).toBe('0 – 100');
    expect(q('horizon-save').disabled).toBe(true);
    type(q('dish'), '6');
    expect(q('dish-support').textContent).toBe('Margin on 1296 MHz: 1.35°');
    q('horizon-save').click();
    expect(JSON.parse(localStorage.getItem('eme.settings')).dishDiameterM).toBe(6);

    q('horizon-edit').click();
    expect(q('dish').value).toBe('6');
    type(q('dish'), '');
    q('horizon-save').click();
    expect(JSON.parse(localStorage.getItem('eme.settings')).dishDiameterM).toBe(0);
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

describe('"Measure horizon" and "Load horizon" (same as HorizonImportTest.kt)', () => {
  let host;
  // jsdom: no file: URL for import.meta.url, so relative to the web folder (where npm test runs)
  const DIR = join(process.cwd(), '../app/src/test/resources/horizon_survey/');
  const q = (role) => host.querySelector(`[data-role="${role}"]`);
  const all = (role) => [...host.querySelectorAll(`[data-role="${role}"]`)];
  const flush = () => new Promise((r) => setTimeout(r, 0));
  // A user picks the file in the browser's file picker
  const pick = async (name) => {
    const input = q('horizon-file');
    Object.defineProperty(input, 'files', { value: [new File([readFileSync(DIR + name, 'utf8')], name)], configurable: true });
    input.dispatchEvent(new Event('change'));
    for (let i = 0; i < 5; i++) await flush();
  };

  beforeEach(() => {
    localStorage.clear();
    setLanguage('en');
    host = document.createElement('div');
    const settings = { language: 'en', callsign: 'ON5GS', locator: 'JO21sc', horizon: { minElDeg: 5, obstacles: [{ fromAzDeg: 180, toAzDeg: 200, maxElDeg: 20, kind: 'P' }] } };
    createSettingsScreen().mount(host, { settings, draft: { callsign: 'ON5GS', locator: 'JO21sc' }, now: () => NOW, onLanguage() {}, onSaved() {} });
    q('horizon-edit').click();
  });

  test('measure opens horizon-survey in a new tab', () => {
    const a = q('horizon-measure');
    expect(a.getAttribute('href')).toBe('https://horizon.dl6zg.de/');
    expect(a.getAttribute('target')).toBe('_blank');
    expect(a.getAttribute('rel')).toContain('noopener');
  });

  test('loading a survey fills the obstacles; the minimum stays; only Save stores', async () => {
    await pick('horizon_survey_example.json');
    expect(q('horizon-import-message').textContent).toBe('Loaded: 12 points, 7 obstacles. Check them, mark thin trees as partial, then save.');
    expect(all('from').length).toBe(7);
    expect(localStorage.getItem('eme.settings')).toBeNull(); // nothing stored yet
    q('horizon-save').click();
    // Same obstacles as parseSurvey gives (checked against expected/horizon_import.csv in horizon-survey.test.js), minimum 5 kept
    const { obstacles } = parseSurvey(readFileSync(DIR + 'horizon_survey_example.json', 'utf8'));
    expect(JSON.parse(localStorage.getItem('eme.settings')).horizon).toBe(encode({ minElDeg: 5, obstacles }));
    expect(JSON.parse(localStorage.getItem('eme.settings')).horizon).toBe('1;5;B 30 60 24;B 60 110 25;B 110 130 23;B 130 180 17;B 180 230 19;B 230 270 7;B 270 30 6');
  });

  test('a wrong file shows an error and keeps the obstacles', async () => {
    await pick('not_a_survey.txt');
    expect(q('horizon-import-message').textContent).toBe('This file is not a horizon survey from horizon.dl6zg.de (JSON or CSV).');
    expect(q('horizon-import-message').classList.contains('error')).toBe(true);
    expect(all('from').length).toBe(1);
  });

  test('too few points', async () => {
    await pick('one_point.json');
    expect(q('horizon-import-message').textContent).toBe('Too few points in this file (at least 2 are needed).');
  });
});
