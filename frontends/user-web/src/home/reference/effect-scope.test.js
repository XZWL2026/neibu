import { afterEach, expect, it, vi } from 'vitest';
import { createEffectScope } from './effect-scope.js';

afterEach(() => vi.unstubAllGlobals());

it('stops listeners, observers and queued frames when leaving the home route', () => {
  const callbacks = new Map();
  let next = 0;
  vi.stubGlobal('requestAnimationFrame', callback => { callbacks.set(++next, callback); return next; });
  vi.stubGlobal('cancelAnimationFrame', id => callbacks.delete(id));
  const disconnect = vi.fn();
  vi.stubGlobal('ResizeObserver', class { disconnect = disconnect; });
  const scope = createEffectScope();
  const target = new EventTarget(), listener = vi.fn(), draw = vi.fn(), dispose = vi.fn();
  scope.listen(target, 'pointermove', listener);
  scope.resize(() => {});
  scope.frame(draw);
  scope.onDispose(dispose);
  target.dispatchEvent(new Event('pointermove'));
  expect(listener).toHaveBeenCalledTimes(1);
  scope.dispose();
  scope.dispose();
  target.dispatchEvent(new Event('pointermove'));
  expect(listener).toHaveBeenCalledTimes(1);
  expect(disconnect).toHaveBeenCalledTimes(1);
  expect(dispose).toHaveBeenCalledTimes(1);
  expect(callbacks.size).toBe(0);
  expect(scope.frame(draw)).toBe(0);
});
