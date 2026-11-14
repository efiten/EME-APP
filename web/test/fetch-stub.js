// Test helper: a stubbed fetch with the abort behaviour of the real one.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { vi } from 'vitest';

export const resource = (name) => readFileSync(join(import.meta.dirname, '../../app/src/test/resources', name), 'utf8');

/** Response factory for a route; a new Response per call because a body can be read once. */
export const ok = (body, headers = {}) => () => new Response(body, { status: 200, headers });
export const status = (code, body = '', headers = {}) => () => new Response(body, { status: code, headers });
export const fails = (message) => () => Promise.reject(new TypeError(message));
export const hangs = () => new Promise(() => {});

/** handlers: url -> () => Response | Promise<Response>; replace one later through stub.handlers. */
export function stubFetch(handlers) {
  const stub = vi.fn(
    (url, init = {}) =>
      new Promise((resolve, reject) => {
        const onAbort = () => reject(new DOMException('The operation was aborted.', 'AbortError'));
        if (init.signal?.aborted) return onAbort();
        init.signal?.addEventListener('abort', onAbort);
        Promise.resolve(stub.handlers[url]()).then(resolve, reject);
      }),
  );
  stub.handlers = handlers;
  vi.stubGlobal('fetch', stub);
  return stub;
}

export const callsTo = (stub, url) => stub.mock.calls.filter(([u]) => u === url).length;
