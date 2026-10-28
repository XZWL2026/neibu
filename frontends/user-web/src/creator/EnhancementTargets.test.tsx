import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { VideoTaskCard } from './VideoTaskResults';
import * as api from '../api';

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  mediaGetGetApiV1MediaTasksTaskId: vi.fn(), storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(),
}));
const native = { id: 'native', type: 'VIDEO', operation: 'GENERATION' as const, status: 'SUCCEEDED', results: [{ index: 0, fileId: 'native-file' }] };
const entry = { key: 'native-key', taskRef: 'native', snapshot: { prompt: '视频', ...native } };
beforeEach(() => { vi.resetAllMocks(); sessionStorage.clear(); api.clearSession();
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue({ data: native } as never);
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockImplementation(async id => ({ data: { url: `https://assets.example/${id}.mp4` } }) as never);
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it.each([['1080p', '720p', '1:1'], ['2K', '1080p', '16:9'], ['4K', '480p', '9:16']])('confirms configured %s enhancement and restores the same paid request after remount', async (target, source, aspect) => {
  const paid: { key: string | null; body: unknown }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/auth/session')) return new Response(JSON.stringify({ authenticated: true, csrfToken: 'csrf', user: { id: 'owner' } }), { headers: { 'Content-Type': 'application/json' } });
    if (url.endsWith('/enhancement-options')) return new Response(JSON.stringify({ taskId: 'native', outputIndex: 0, fileId: 'native-file', sourceResolution: source, aspectRatio: aspect, billableSeconds: 6,
      targets: [{ quoteId: 'quote', targetResolution: target, amount: '1.5000', rate: '10.0000', points: '15.0000', executionSupported: true, expiresAt: '2099-10-13T09:00:00Z' }] }), { headers: { 'Content-Type': 'application/json' } });
    paid.push({ key: new Headers(init?.headers).get('Idempotency-Key'), body: JSON.parse(String(init?.body)) });
    return new Response('{}', { status: 503, headers: { 'Content-Type': 'application/json' } });
  }));
  const first = render(<VideoTaskCard enhancementOwnerId="owner" entry={entry} />);
  fireEvent.click(await screen.findByRole('button', { name: '超分增强' }));
  const confirm = await screen.findByRole('button', { name: '确认费用并增强' });
  expect(screen.getByText('15.0000 积分')).toBeVisible();
  await act(async () => { fireEvent.click(confirm); });
  expect(paid).toHaveLength(1);
  first.unmount();
  render(<VideoTaskCard enhancementOwnerId="owner" entry={entry} />);
  expect(await screen.findByText(`已确认 ${target}增强 · 6 秒 · 15.0000 积分 · 1.5000 元`)).toBeVisible();
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '使用原增强请求确认' })); });
  expect(paid).toHaveLength(2); expect(paid[1]).toEqual(paid[0]);
});

it('offers all four upward target tiers including 2K for a 480p source', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ taskId: 'native', outputIndex: 0, fileId: 'native-file',
    sourceResolution: '480p', aspectRatio: '16:9', billableSeconds: 6,
    targets: ['720p', '1080p', '2K', '4K'].map(target => ({ quoteId: `quote-${target}`, targetResolution: target, amount: '1.5000', rate: '10.0000', points: '15.0000', executionSupported: true, expiresAt: '2099-10-13T09:00:00Z' }))
  }), { headers: { 'Content-Type': 'application/json' } })));
  render(<VideoTaskCard enhancementOwnerId="owner" entry={entry} />);
  fireEvent.click(await screen.findByRole('button', { name: '超分增强' }));
  const select = await screen.findByRole('combobox', { name: '增强目标分辨率' });
  expect(Array.from(select.querySelectorAll('option'), option => option.textContent)).toEqual(['720p', '1080p', '2K', '4K']);
});

it('switches and separately downloads native, 1080p, 2K and 4K from one video record', async () => {
  const versions = ['1080p', '2K', '4K'].map(target => ({ id: `child-${target}`, status: 'SUCCEEDED', sourceOutputIndex: 0, sourceFileId: 'native-file', targetResolution: target, billableSeconds: 6, amount: '1.5000', points: '15.0000', results: [{ index: 0, fileId: `file-${target}` }], refundStatus: 'NOT_REQUIRED' as const, failureMessage: null }));
  const clicked: string[] = [];
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { clicked.push(this.href, this.download); });
  render(<VideoTaskCard enhancementOwnerId="owner" entry={{ ...entry, snapshot: { ...entry.snapshot, enhancements: versions } }} />);
  await act(async () => {});
  for (const target of ['1080p', '2K', '4K']) {
    fireEvent.change(screen.getByRole('combobox', { name: '视频版本' }), { target: { value: `file-${target}` } });
    await act(async () => {});
    expect(screen.getByLabelText('生成视频 1')).toHaveAttribute('src', `https://assets.example/file-${target}.mp4`);
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: `下载${target}增强版` })); });
  }
  fireEvent.change(screen.getByRole('combobox', { name: '视频版本' }), { target: { value: 'native-file' } });
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '下载原版' })); });
  expect(clicked).toEqual(['https://assets.example/file-1080p.mp4', 'video-1-1080p', 'https://assets.example/file-2K.mp4', 'video-1-2K', 'https://assets.example/file-4K.mp4', 'video-1-4K', 'https://assets.example/native-file.mp4', 'video-1-native']);
  expect(screen.getAllByRole('article')).toHaveLength(1);
});
