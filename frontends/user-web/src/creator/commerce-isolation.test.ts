// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { readVideoSubmission, saveVideoSubmission } from './video-submission-recovery';
import { regenerationEntry } from './creation-task-draft';
import { submitCreation, CreationRejection } from './creation-submission';
import * as api from '../api';
import { createProductionMediaPort } from '../canvas/production-api';
vi.mock('../canvas/production-api', () => ({ createProductionMediaPort: vi.fn() }));
import type { VideoSubmission } from './VideoTaskResults';
const entry = (key: string): VideoSubmission => ({ key, taskRef: key + '-task', request: {
  requestRef: key, settings: { type: 'VIDEO', model: 'video', resolution: '720p', aspectRatio: '16:9', count: 1, durationSeconds: 5 }, prompt: key, inputAssetRefs: [],
}, snapshot: { type: 'VIDEO', model: 'video', resolution: '720p', aspectRatio: '16:9', count: 1, durationSeconds: 5, prompt: key } });
afterEach(() => { sessionStorage.clear(); vi.resetAllMocks(); });
it('keeps accepted and uncertain commerce submissions separate in both directions and across owners', () => {
  const ordinary = entry('ordinary'), commerce = entry('commerce-video');
  saveVideoSubmission('owner', ordinary);
  saveVideoSubmission('owner', commerce);
  expect(readVideoSubmission('owner')).toMatchObject({ key: ordinary.key });
  expect(readVideoSubmission('owner', 'VIDEO', true)).toMatchObject({ key: commerce.key });
  expect(readVideoSubmission('other', 'VIDEO', true)).toBeNull();
  saveVideoSubmission('owner', { ...commerce, taskRef: undefined, state: 'UNCERTAIN' });
  expect(readVideoSubmission('owner', 'VIDEO', true)).toMatchObject({ key: commerce.key, state: 'UNCERTAIN' });
  expect(readVideoSubmission('owner')).toMatchObject({ key: ordinary.key });
  saveVideoSubmission('owner', null, 'VIDEO', true);
  expect(readVideoSubmission('owner', 'VIDEO', true)).toBeNull();
  expect(readVideoSubmission('owner')).toMatchObject({ key: ordinary.key });
});
it('keeps regenerated commerce requests in their source scope', () => {
  const regenerated = regenerationEntry(entry('commerce-video'), true);
  expect(regenerated.key).toMatch(/^commerce-/);
  expect(regenerated.request?.requestRef).toBe(regenerated.key);
  expect(regenerated.replacesTaskId).toBe('commerce-video-task');
  expect(regenerationEntry(entry('ordinary')).key).not.toMatch(/^commerce-/);
});

it('routes late accepted and rejected requests to their own recovery slot', async () => {
  const ordinary = entry('ordinary'), commerce = { ...entry('commerce-pending'), taskRef: undefined };
  saveVideoSubmission('owner', ordinary);
  saveVideoSubmission('owner', commerce);
  let accept!: (value: { taskRef: string }) => void;
  vi.mocked(createProductionMediaPort).mockReturnValue({ submit: () => new Promise(resolve => { accept = resolve; }) } as never);
  const flight = submitCreation('owner', commerce, true);
  accept({ taskRef: 'accepted-commerce' });
  await flight;
  expect(readVideoSubmission('owner', 'VIDEO', true)?.taskRef).toBe('accepted-commerce');
  expect(readVideoSubmission('owner')?.key).toBe('ordinary');
  const rejected = { ...entry('commerce-rejected'), taskRef: undefined };
  saveVideoSubmission('owner', rejected);
  vi.mocked(createProductionMediaPort).mockReturnValue({ submit: () => Promise.reject(new api.ApiError(new Response(null, { status: 422 }), {})) } as never);
  await expect(submitCreation('owner', rejected, true)).rejects.toBeInstanceOf(CreationRejection);
  expect(readVideoSubmission('owner', 'VIDEO', true)).toBeNull();
  expect(readVideoSubmission('owner')?.key).toBe('ordinary');
});
