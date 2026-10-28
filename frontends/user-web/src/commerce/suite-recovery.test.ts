import { beforeEach, expect, it } from 'vitest';
import { clearPendingSuite, readPendingSuite, savePendingSuite, type PendingSuite } from './suite-recovery';

beforeEach(() => localStorage.clear());
const pending = (key: string): PendingSuite => ({ key, request: { productName: '格纹套装', sellingPoints: '', language: '中文',
  fileIds: ['product-file'], modules: ['WHITE_BACKGROUND'], templateId: 'streetwear', templateVersion: 1,
  model: 'image-model', aspectRatio: '1:1', resolution: '1K', options: null } });

it('keeps each tool pending request independent and preserves the original suite key', () => {
  const suite = pending('suite'), replica = pending('replica');
  expect(savePendingSuite('owner-a', suite)).toBe(true);
  expect(savePendingSuite('owner-a', replica, 'image-replica')).toBe(true);
  expect(readPendingSuite('owner-a')).toEqual(suite);
  expect(readPendingSuite('owner-a', 'image-replica')).toEqual(replica);
  expect(readPendingSuite('owner-a', 'model-wear')).toBeNull();
  clearPendingSuite('owner-a', 'replica', 'image-replica');
  expect(readPendingSuite('owner-a', 'image-replica')).toBeNull();
  expect(readPendingSuite('owner-a')).toEqual(suite);
});

it('preserves another tab’s newer pending key while allowing the same key and other owners', () => {
  const newer = pending('newer-key'), stale = pending('stale-key');
  expect(savePendingSuite('owner-a', newer)).toBe(true);
  expect(savePendingSuite('owner-a', stale)).toBe(false);
  expect(readPendingSuite('owner-a')).toEqual(newer);
  expect(savePendingSuite('owner-a', newer)).toBe(true);
  expect(savePendingSuite('owner-b', stale)).toBe(true);
  expect(readPendingSuite('owner-b')).toEqual(stale);
  expect(readPendingSuite('owner-a')).toEqual(newer);
});
