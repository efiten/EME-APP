// @vitest-environment jsdom
import { beforeEach, describe, expect, test } from 'vitest';
import { createSettingsScreen } from '../src/screens/settings.js';
import { loadCredentials, saveCredentials } from '../src/credentials.js';
import { setLanguage, t } from '../src/i18n.js';

beforeEach(() => localStorage.clear());

describe('HB9Q login section', () => {
  let host;
  const mount = () => {
    host = document.createElement('div');
    createSettingsScreen().mount(host, {
      settings: { language: 'en', callsign: 'ON5GS', locator: 'JO21sc' },
      draft: { callsign: 'ON5GS', locator: 'JO21sc' },
      onLanguage() {},
      onSaved() {},
    });
  };
  const q = (role) => host.querySelector(`[data-role="${role}"]`);
  const type = (input, value) => {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };

  beforeEach(() => setLanguage('en'));

  test('shows the title and the browser storage text', () => {
    mount();
    expect(host.textContent).toContain(t('hb9q_chat'));
    expect(host.textContent).toContain('Your login is stored in this browser on this device.');
  });

  test('savesLoginAndPassword: login upper case and trimmed, message shown, password field emptied', () => {
    mount();
    type(q('hb9q-login'), ' on0aaa ');
    expect(q('hb9q-login').value).toBe('ON0AAA');
    type(q('hb9q-password'), 'pw-a');
    q('save-login').click();
    expect(loadCredentials()).toEqual({ login: 'ON0AAA', password: 'pw-a' });
    expect(q('login-message').textContent).toBe(t('login_saved'));
    expect(q('hb9q-password').value).toBe('');
  });

  test('emptyPasswordKeepsTheSavedOne', () => {
    saveCredentials('ON0AAA', 'pw-a');
    mount();
    type(q('hb9q-login'), 'ON0BBB');
    expect(q('save-login').disabled).toBe(false);
    q('save-login').click();
    expect(loadCredentials()).toEqual({ login: 'ON0BBB', password: 'pw-a' });
  });

  test('passwordFieldNeverShowsTheSavedPassword', () => {
    saveCredentials('ON0AAA', 'pw-a');
    mount();
    expect(q('hb9q-login').value).toBe('ON0AAA');
    expect(q('hb9q-password').value).toBe('');
    expect(host.innerHTML).not.toContain('pw-a');
    expect(q('hb9q-password-label').textContent).toContain(t('password_saved'));
  });

  test('without a saved login the password label is plain and clear is hidden', () => {
    mount();
    expect(q('hb9q-password-label').textContent).toContain(t('password'));
    expect(q('hb9q-password-label').textContent).not.toContain(t('password_saved'));
    expect(q('clear-login')).toBeNull();
  });

  test('clearRemovesTheLogin', () => {
    saveCredentials('ON0AAA', 'pw-a');
    mount();
    q('clear-login').click();
    expect(loadCredentials()).toBeNull();
    expect(q('hb9q-login').value).toBe('');
    expect(q('login-message').textContent).toBe(t('login_cleared'));
    expect(q('clear-login')).toBeNull();
    type(q('hb9q-login'), 'ON0AAA');
    expect(q('save-login').disabled).toBe(true);
  });

  test('saveDisabledWithoutLogin', () => {
    mount();
    expect(q('save-login').disabled).toBe(true);
    type(q('hb9q-password'), 'pw-a');
    expect(q('save-login').disabled).toBe(true);
    type(q('hb9q-login'), 'ON0AAA');
    expect(q('save-login').disabled).toBe(false);
    type(q('hb9q-password'), '');
    expect(q('save-login').disabled).toBe(true);
  });

  test('showTogglesTheInputType', () => {
    mount();
    expect(q('hb9q-password').type).toBe('password');
    expect(q('toggle-password').textContent).toBe(t('show'));
    q('toggle-password').click();
    expect(q('hb9q-password').type).toBe('text');
    expect(q('toggle-password').textContent).toBe(t('hide'));
  });

  test('autocomplete hints for the password manager', () => {
    mount();
    expect(q('hb9q-login').autocomplete).toBe('username');
    expect(q('hb9q-password').autocomplete).toBe('current-password');
  });

  test('typing keeps the login and password inputs in place and focused', () => {
    mount();
    document.body.append(host);
    for (const role of ['hb9q-login', 'hb9q-password']) {
      const input = q(role);
      input.focus();
      for (const v of ['O', 'ON', 'ON0']) {
        type(input, v);
        expect(q(role)).toBe(input);
        expect(input.isConnected).toBe(true);
        expect(document.activeElement).toBe(input);
      }
    }
    host.remove();
  });
});
