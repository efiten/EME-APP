// The HB9Q login in the browser (localStorage, this browser only). Port of CredentialStore.kt, without encryption:
// the web server never stores it, the browser keeps it so the user does not type it again.
const KEY = 'eme.hb9q';

/** { login, password }, or null unless both are non-empty strings. */
export function loadCredentials() {
  try {
    const v = JSON.parse(localStorage.getItem(KEY));
    if (v && typeof v.login === 'string' && v.login !== '' && typeof v.password === 'string' && v.password !== '') {
      return { login: v.login, password: v.password };
    }
  } catch {
    // storage unavailable or rubbish: no login
  }
  return null;
}

export function saveCredentials(login, password) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ login, password }));
  } catch {
    // storage unavailable (private mode, quota): the login stays in memory of the caller only
  }
}

export function clearCredentials() {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // storage unavailable: nothing to clear
  }
}
