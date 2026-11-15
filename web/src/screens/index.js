// Tab id -> factory returning a screen { mount(el, ctx), unmount() }.
// Screens poll only while mounted and while document.visibilityState === 'visible'.
import { createMoonScreen } from './moon.js';
import { createDegradationScreen } from './degradation.js';
import { createHb9qScreen } from './hb9q.js';
import { createN0ukScreen } from './n0uk.js';
import { createLiveCqScreen } from './livecq.js';

export const SCREENS = {
  moon: createMoonScreen,
  deg: createDegradationScreen,
  chat: createHb9qScreen,
  n0uk: createN0ukScreen,
  livecq: createLiveCqScreen,
};
