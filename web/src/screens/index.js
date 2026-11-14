// Tab id -> factory returning a screen { mount(el, ctx), unmount() }.
// Tasks 10-12 replace the placeholders; screens poll only while mounted and while document.visibilityState === 'visible'.
const placeholder = () => ({ mount() {}, unmount() {} });

export const SCREENS = {
  moon: placeholder,
  deg: placeholder,
  n0uk: placeholder,
  livecq: placeholder,
};
