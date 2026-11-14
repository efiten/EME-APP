/**
 * Calls fn at once and then every ms while the page is visible. When the page is hidden the
 * timer stops (and onHide runs); when it is visible again fn runs at once and the timer restarts.
 * Returns a function that stops everything and removes the listener.
 */
export function visibleInterval(fn, ms, onHide) {
  let timer = null;
  const halt = () => {
    if (timer !== null) clearInterval(timer);
    timer = null;
  };
  const sync = () => {
    halt();
    if (document.visibilityState === 'visible') {
      timer = setInterval(fn, ms);
      fn();
    } else {
      onHide?.();
    }
  };
  document.addEventListener('visibilitychange', sync);
  sync();
  return () => {
    document.removeEventListener('visibilitychange', sync);
    halt();
  };
}
