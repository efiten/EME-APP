import { LANGUAGES, t } from '../i18n.js';
import { isValid, normalize, toLatLon } from '../lib/maidenhead.js';
import { saveSettings } from '../settings.js';

/** Port of AppLanguage.options (native names, as in the language picker of the app). */
const LANGUAGE_NAMES = {
  nl: 'Nederlands', fr: 'Français', en: 'English', de: 'Deutsch', es: 'Español', it: 'Italiano', pt: 'Português',
  sv: 'Svenska', da: 'Dansk', fi: 'Suomi', ru: 'Русский', zh: '中文', ja: '日本語',
};

const RELEASE_NOTES_URL = `https://github.com/on5gsdirk-cmd/EME-APP/releases/tag/v${__APP_VERSION__}`;

function el(tag, props = {}, ...children) {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children);
  return node;
}

const formatCentre = ({ latDeg, lonDeg }) =>
  `${Math.abs(latDeg).toFixed(4)}° ${latDeg >= 0 ? 'N' : 'S'}, ${Math.abs(lonDeg).toFixed(4)}° ${lonDeg >= 0 ? 'E' : 'W'}`;

/**
 * Settings screen (SettingsScreen.kt without the HB9Q login).
 * ctx.draft ({ callsign, locator }) lives in the caller so typed values survive a re-render after a language switch.
 * The screen calls ctx.onLanguage(code) and ctx.onSaved().
 */
export function createSettingsScreen() {
  return {
    mount(host, ctx) {
      const { settings, draft } = ctx;
      host.replaceChildren();

      const locatorValid = () => isValid(draft.locator);
      const saveEnabled = () => locatorValid() && draft.callsign.trim() !== '';

      const select = el(
        'select',
        {},
        el('option', { value: '', textContent: t('language_system') }),
        ...LANGUAGES.map((c) => el('option', { value: c, textContent: LANGUAGE_NAMES[c] })),
      );
      select.value = settings.language;
      select.addEventListener('change', () => ctx.onLanguage(select.value));

      const callsign = el('input', { type: 'text', value: draft.callsign, autocapitalize: 'characters', spellcheck: false });
      const locator = el('input', { type: 'text', value: draft.locator, autocapitalize: 'characters', spellcheck: false });
      const support = el('div', { className: 'support' });
      const locatorField = el('label', { className: 'field' }, t('locator_label'), locator, support);
      const save = el('button', { type: 'button', className: 'button', textContent: t('save') });
      save.dataset.action = 'save';

      const refresh = () => {
        const valid = locatorValid();
        locatorField.classList.toggle('invalid', !valid);
        const pos = toLatLon(draft.locator);
        support.textContent = !valid ? t('locator_invalid') : pos ? t('locator_center', formatCentre(pos)) : '';
        save.disabled = !saveEnabled();
      };

      callsign.addEventListener('input', () => {
        draft.callsign = callsign.value.toUpperCase().trim();
        callsign.value = draft.callsign;
        refresh();
      });
      locator.addEventListener('input', () => {
        draft.locator = locator.value.trim().slice(0, 6);
        locator.value = draft.locator;
        refresh();
      });
      save.addEventListener('click', () => {
        if (!saveEnabled()) return;
        saveSettings({ callsign: draft.callsign, locator: normalize(draft.locator) });
        ctx.onSaved();
      });

      const notes = el('a', {
        className: 'link',
        href: RELEASE_NOTES_URL,
        target: '_blank',
        rel: 'noopener noreferrer',
        textContent: `${t('release_notes')}  v${__APP_VERSION__}`,
      });

      host.append(
        el('div', { className: 'settings' },
          el('h2', { textContent: t('language') }), select,
          el('hr'),
          el('h2', { textContent: t('station') }),
          el('label', { className: 'field' }, t('callsign'), callsign),
          locatorField, save,
          el('hr'), notes),
      );
      refresh();
    },
    unmount() {},
  };
}
