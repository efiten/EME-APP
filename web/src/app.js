import { setLanguage, t } from './i18n.js';
import { effectiveLanguage, loadSettings, saveSettings } from './settings.js';
import { createSettingsScreen } from './screens/settings.js';
import { SCREENS } from './screens/index.js';

// App tabs are Moon, Deg, Chat (HB9Q), N0UK, Live CQ; phase 1 of the web has no HB9Q.
const TABS = [
  { id: 'moon', label: 'tab_moon' },
  { id: 'deg', label: 'tab_degradation' },
  { id: 'n0uk', label: 'tab_n0uk' },
  { id: 'livecq', label: 'tab_livecq' },
];

const SVG = 'http://www.w3.org/2000/svg';
// Material "settings" and "arrow_back" icons (24x24).
const ICONS = {
  settings: 'M19.14 12.94c.04-.3.06-.61.06-.94s-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.48.48 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z',
  back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
};

function icon(name) {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(SVG, 'path');
  path.setAttribute('d', ICONS[name]);
  svg.append(path);
  return svg;
}

function iconButton(action, labelKey) {
  const b = Object.assign(document.createElement('button'), { type: 'button', className: 'icon-button' });
  b.dataset.action = action;
  b.setAttribute('aria-label', t(labelKey));
  b.append(icon(action));
  return b;
}

/** screens: tab id -> factory returning { mount(el, ctx), unmount() }. */
export function mountApp(root, screens = SCREENS) {
  const state = { view: 'tabs', tab: 'moon', draft: null, screen: null, content: null };
  const settingsScreen = createSettingsScreen();
  const now = () => Date.now();

  setLanguage(effectiveLanguage(loadSettings()));

  function stopScreen() {
    state.screen?.unmount();
    state.screen = null;
  }

  function startTab() {
    state.screen = screens[state.tab]();
    state.screen.mount(state.content, { settings: loadSettings(), t, now });
  }

  function selectTab(id) {
    if (id === state.tab) return;
    stopScreen();
    state.tab = id;
    state.content.replaceChildren();
    root.querySelectorAll('[role="tab"]').forEach((b, i) => b.setAttribute('aria-selected', String(TABS[i].id === id)));
    startTab();
  }

  function show(view) {
    state.view = view;
    render();
  }

  function renderSettings(header, content, title) {
    const back = iconButton('back', 'back');
    back.addEventListener('click', () => show('tabs'));
    title.textContent = t('settings');
    header.append(back, title);
    root.append(header, content);
    state.screen = settingsScreen;
    settingsScreen.mount(content, {
      settings: loadSettings(),
      t,
      now,
      draft: state.draft,
      onLanguage: (code) => {
        saveSettings({ language: code });
        setLanguage(effectiveLanguage({ language: code }));
        render();
      },
      onSaved: () => show('tabs'),
    });
  }

  function render() {
    stopScreen();
    root.replaceChildren();
    const header = document.createElement('header');
    header.className = 'topbar';
    const content = document.createElement('main');
    content.className = 'content';
    const title = document.createElement('h1');
    state.content = content;

    if (state.view === 'settings') {
      renderSettings(header, content, title);
      return;
    }

    const version = document.createElement('span');
    version.className = 'version';
    version.dataset.role = 'version';
    version.textContent = 'v' + __APP_VERSION__;
    title.append(t('app_title'), version);
    const gear = iconButton('settings', 'settings');
    gear.addEventListener('click', () => {
      const s = loadSettings();
      state.draft = { callsign: s.callsign, locator: s.locator };
      show('settings');
    });
    header.append(title, gear);

    const bar = document.createElement('nav');
    bar.className = 'tabbar';
    bar.setAttribute('role', 'tablist');
    for (const tab of TABS) {
      const b = Object.assign(document.createElement('button'), { type: 'button', className: 'tab', textContent: t(tab.label) });
      b.setAttribute('role', 'tab');
      b.setAttribute('aria-selected', String(tab.id === state.tab));
      b.addEventListener('click', () => selectTab(tab.id));
      bar.append(b);
    }
    root.append(header, bar, content);
    startTab();
  }

  render();
}
