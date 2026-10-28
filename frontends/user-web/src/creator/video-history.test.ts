import { beforeEach, expect, it, vi } from 'vitest';
import { mediaListGetApiV1MediaTasks } from '../api';
import { readVideoHistory, readMediaHistory } from './video-history';
vi.mock('../api', () => ({ mediaListGetApiV1MediaTasks: vi.fn() }));
const list = vi.mocked(mediaListGetApiV1MediaTasks);
const reply = (data: unknown) => ({ data }) as never;
const page = (prefix: string, type = 'VIDEO') => Array.from({ length: 20 }, (_, i) => ({ id: `${prefix}-${i}`, type }));
beforeEach(() => list.mockReset());
it('skips video-only pages for image history and keeps the raw head for refresh', async () => {
  list.mockResolvedValueOnce(reply(page('video'))).mockResolvedValueOnce(reply([{ id: 'image', type: 'IMAGE' }]));
  const result = await readMediaHistory({ mediaType: 'IMAGE', isCurrent: () => true });
  expect(list).toHaveBeenLastCalledWith({ limit: 20, excludeCanvas: true, beforeId: 'video-19' });
  expect(result?.entries.map(item => item.taskRef)).toEqual(['image']);
  expect(result?.head).toBe('video-0');
});
it('skips image-only pages using raw cursors until videos appear', async () => {
  list.mockResolvedValueOnce(reply(page('image', 'IMAGE'))).mockResolvedValueOnce(reply([{ id: 'video', type: 'VIDEO' }]));
  const result = await readVideoHistory({ isCurrent: () => true });
  expect(list).toHaveBeenLastCalledWith({ limit: 20, excludeCanvas: true, beforeId: 'image-19' });
  expect(result?.entries.map(item => item.taskRef)).toEqual(['video']); expect(result?.cursor).toBeNull();
  expect(result?.head).toBe('image-0');
});
it('bridges more than a page of new tasks back to known history', async () => {
  list.mockResolvedValueOnce(reply(page('new'))).mockResolvedValueOnce(reply([...page('recent').slice(0, 19), { id: 'known', type: 'VIDEO' }]));
  const result = await readVideoHistory({ known: new Set(['known']), isCurrent: () => true });
  expect(list).toHaveBeenCalledTimes(2); expect(result?.entries).toHaveLength(40);
});
it('stops a repeated cursor instead of looping forever', async () => {
  list.mockResolvedValue(reply(page('image', 'IMAGE')));
  await expect(readVideoHistory({ isCurrent: () => true })).rejects.toThrow('Repeated history cursor');
  expect(list).toHaveBeenCalledTimes(2);
});
it('discards an in-flight response when its owner changes', async () => {
  let current = true;
  list.mockImplementation(async () => { current = false; return reply(page('private')); });
  expect(await readVideoHistory({ isCurrent: () => current })).toBeNull();
});
