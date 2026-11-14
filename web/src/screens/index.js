// Tab id -> factory returning a screen { mount(el, ctx), unmount() }.
// Screens poll only while mounted and while document.visibilityState === 'visible'.
import { createMoonScreen } from './moon.js';
import { createDegradationScreen } from './degradation.js';
import { createN0ukScreen } from './n0uk.js';
import { createLiveCqScreen } from './livecq.js';

export const SCREENS = {
  moon: createMoonScreen,
  deg: createDegradationScreen,
  n0uk: createN0ukScreen,
  livecq: createLiveCqScreen,
};
