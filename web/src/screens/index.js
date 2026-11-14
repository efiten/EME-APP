// Tab id -> factory returning a screen { mount(el, ctx), unmount() }.
// Task 12 replaces the placeholders; screens poll only while mounted and while document.visibilityState === 'visible'.
import { createMoonScreen } from './moon.js';
import { createDegradationScreen } from './degradation.js';

const placeholder = () => ({ mount() {}, unmount() {} });

export const SCREENS = {
  moon: createMoonScreen,
  deg: createDegradationScreen,
  n0uk: placeholder,
  livecq: placeholder,
};
