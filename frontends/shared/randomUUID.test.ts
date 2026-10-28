import { afterEach, expect, it, vi } from 'vitest';
import { randomUUID } from './randomUUID';

afterEach(() => vi.unstubAllGlobals());

it('uses the native UUID implementation with its Crypto receiver', () => {
  const provider = { randomUUID() { expect(this).toBe(provider); return 'native-request'; } };
  vi.stubGlobal('crypto', provider);
  expect(randomUUID()).toBe('native-request');
});

it('uses cryptographic bytes without randomUUID and encodes UUID version and variant', () => {
  let seed = 0;
  const getRandomValues = vi.fn((bytes: Uint8Array) => { bytes.fill(seed++); return bytes; });
  vi.stubGlobal('crypto', { getRandomValues });
  expect(randomUUID()).toBe('00000000-0000-4000-8000-000000000000');
  expect(randomUUID()).toBe('01010101-0101-4101-8101-010101010101');
  expect(getRandomValues).toHaveBeenCalledTimes(2);
});

it('never substitutes weak randomness when the cryptographic provider fails', () => {
  vi.stubGlobal('crypto', { getRandomValues() { throw new Error('provider unavailable'); } });
  expect(randomUUID).toThrow('provider unavailable');
});
