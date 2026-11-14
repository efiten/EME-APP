import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { loadSettings, locatorLatLon, saveSettings } from '../src/settings.js';

const DEFAULTS = {
  locator: '',
  callsign: '',
  cqBands: new Set(['B23CM']),
  degBand: 'B1296',
  language: 'en',
};

function fakeStorage() {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
  };
}
const throwing = () => {
  throw new Error('SecurityError');
};

beforeEach(() => {
  globalThis.localStorage = fakeStorage();
});
afterEach(() => {
  delete globalThis.localStorage;
});

describe('loadSettings', () => {
  test('empty localStorage gives the defaults', () => {
    expect(loadSettings()).toEqual(DEFAULTS);
  });

  test('missing localStorage gives the defaults', () => {
    delete globalThis.localStorage;
    expect(loadSettings()).toEqual(DEFAULTS);
    expect(() => saveSettings({ locator: 'JO20' })).not.toThrow();
  });

  test('localStorage that throws on access gives the defaults', () => {
    Object.defineProperty(globalThis, 'localStorage', { get: throwing, configurable: true });
    expect(loadSettings()).toEqual(DEFAULTS);
    expect(() => saveSettings({ locator: 'JO20' })).not.toThrow();
  });

  test('localStorage whose methods throw gives the defaults', () => {
    globalThis.localStorage = { getItem: throwing, setItem: throwing };
    expect(loadSettings()).toEqual(DEFAULTS);
    expect(() => saveSettings({ locator: 'JO20' })).not.toThrow();
  });

  test('corrupt stored value gives the defaults', () => {
    localStorage.setItem('eme.settings', '{nope');
    expect(loadSettings()).toEqual(DEFAULTS);
    localStorage.setItem('eme.settings', '[1]');
    expect(loadSettings()).toEqual(DEFAULTS);
  });

  test('browser language is used when it is one of the 13', () => {
    Object.defineProperty(globalThis, 'navigator', { value: { language: 'nl-BE' }, configurable: true });
    try {
      expect(loadSettings().language).toBe('nl');
    } finally {
      Object.defineProperty(globalThis, 'navigator', { value: { language: 'tr-TR' }, configurable: true });
      expect(loadSettings().language).toBe('en');
      delete globalThis.navigator;
    }
  });
});

describe('saveSettings', () => {
  test('round trip, partial updates keep other fields', () => {
    saveSettings({ locator: 'JO20ab', cqBands: new Set(['B144', 'B432']) });
    saveSettings({ callsign: 'ON8AR', degBand: 'B432', language: 'fr' });
    expect(loadSettings()).toEqual({
      locator: 'JO20ab',
      callsign: 'ON8AR',
      cqBands: new Set(['B144', 'B432']),
      degBand: 'B432',
      language: 'fr',
    });
  });

  test('unknown band ids and languages are dropped on load; empty band set stays empty', () => {
    localStorage.setItem(
      'eme.settings',
      JSON.stringify({ cqBands: ['B144', 'nope'], degBand: 'nope', language: 'xx', locator: 5 }),
    );
    expect(loadSettings()).toEqual({ ...DEFAULTS, cqBands: new Set(['B144']) });
    saveSettings({ cqBands: new Set() });
    expect(loadSettings().cqBands).toEqual(new Set());
  });
});

describe('locatorLatLon', () => {
  test('null for empty and invalid locators', () => {
    expect(locatorLatLon({ locator: '' })).toBeNull();
    expect(locatorLatLon({ locator: 'XX99' })).toBeNull();
    expect(locatorLatLon({ locator: 'JO2' })).toBeNull();
  });

  test('valid locator gives lat/lon of the square centre', () => {
    expect(locatorLatLon({ locator: 'JO20' })).toEqual({ latDeg: 50.5, lonDeg: 5 });
  });
});
