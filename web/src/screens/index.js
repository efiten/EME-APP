// Tab id -> factory returning a screen { mount(el, ctx), unmount() }.
// Tasks 10-12 replace the placeholders; screens poll only while mounted and while document.visibilityState === 'visible'.
import { createMoonScreen } from './moon.js';

const placeholder = () => ({ mount() {}, unmount() {} });

export const SCREENS = {
  moon: createMoonScreen,
  deg: placeholder,
  n0uk: placeholder,
  livecq: placeholder,
};
