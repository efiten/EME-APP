import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { clearCredentials, loadCredentials, saveCredentials } from '../src/credentials.js';

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

describe('credentials', () => {
  test('nothing stored gives null', () => {
    expect(loadCredentials()).toBeNull();
  });

  test('save, load, clear', () => {
    saveCredentials('ON0AAA', 'pw-a');
    expect(loadCredentials()).toEqual({ login: 'ON0AAA', password: 'pw-a' });
    expect(localStorage.getItem('eme.hb9q')).not.toBeNull();
    clearCredentials();
    expect(loadCredentials()).toBeNull();
  });

  test('null unless both are non-empty strings', () => {
    saveCredentials('ON0AAA', '');
    expect(loadCredentials()).toBeNull();
    saveCredentials('', 'pw-a');
    expect(loadCredentials()).toBeNull();
    localStorage.setItem('eme.hb9q', JSON.stringify({ login: 'ON0AAA' }));
    expect(loadCredentials()).toBeNull();
    localStorage.setItem('eme.hb9q', 'rubbish{');
    expect(loadCredentials()).toBeNull();
  });

  test('missing localStorage does not throw', () => {
    delete globalThis.localStorage;
    expect(loadCredentials()).toBeNull();
    expect(() => saveCredentials('ON0AAA', 'pw-a')).not.toThrow();
    expect(() => clearCredentials()).not.toThrow();
  });

  test('localStorage that throws does not throw', () => {
    globalThis.localStorage = { getItem: throwing, setItem: throwing, removeItem: throwing };
    expect(loadCredentials()).toBeNull();
    expect(() => saveCredentials('ON0AAA', 'pw-a')).not.toThrow();
    expect(() => clearCredentials()).not.toThrow();
  });
});
