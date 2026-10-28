import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ProductionImageComposer } from './ProductionImageComposer';
import * as api from '../api';

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  mediaListGetApiV1MediaTasks: vi.fn(), mediaGetGetApiV1MediaTasksTaskId: vi.fn(),
  storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(),
}));
const reply = (data: unknown) => ({ data }) as never;
const list = vi.mocked(api.mediaListGetApiV1MediaTasks);
const images = Array.from({ length: 21 }, (_, i) => ({ id: `image-${i}`, type: 'IMAGE', status: 'SUCCEEDED',
  prompt: `图片${i}`, count: 2, createdAt: `2026-10-${String(i + 1).padStart(2,'0')}T00:00:00Z`,
  results: [{ index: 0, fileId: `first-${i}` }, { index: 1, fileId: `second-${i}` }] }));
const open = () => render(<MemoryRouter initialEntries={['/create/image?tab=history']}><ProductionImageComposer historyOnly /></MemoryRouter>);
beforeEach(() => {
  vi.resetAllMocks(); localStorage.clear(); sessionStorage.clear();
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.spyOn(window, 'scrollBy').mockImplementation(() => {});
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(async id => reply(images.find(image => image.id === id)));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockImplementation(async id => reply({ url: `https://assets.example/${id}.png` }));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
it('shows all image outputs and full result actions in grid layouts', async () => {
  list.mockResolvedValue(reply([images[20]]));
  open();
  const card = await screen.findByRole('article', { name: '图片任务：图片20' });
  await waitFor(() => expect(within(card).getAllByRole('img')).toHaveLength(2));
  expect(within(card).getByRole('button', { name: '重新编辑' })).toBeVisible();
  expect(within(card).getByRole('button', { name: '删除图片' })).toBeVisible();
  expect(within(card).queryByText(/时长/)).not.toBeInTheDocument();
  fireEvent.click(within(card).getByRole('button', { name: '放大图片 1' }));
  expect(screen.getByRole('dialog', { name: '图片预览' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '关闭图片预览' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
it('traverses video-only pages, retries the failed older cursor, and never duplicates cards', async () => {
  const videos = Array.from({ length: 20 }, (_, i) => ({ id: `video-${i}`, type: 'VIDEO' }));
  list.mockResolvedValueOnce(reply(videos)).mockResolvedValueOnce(reply(images.slice(1)))
    .mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(reply([images[0]]));
  open(); await screen.findByRole('article', { name: '图片任务：图片20' });
  expect(screen.getAllByRole('article')).toHaveLength(20);
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ top: 100, bottom: 200 } as DOMRect);
  fireEvent.scroll(window);
  fireEvent.click(await screen.findByRole('button', { name: '重新读取任务' }));
  await screen.findByRole('article', { name: '图片任务：图片0' });
  expect(list.mock.calls.slice(-2).map(([query]) => query?.beforeId)).toEqual(['image-20', 'image-20']);
  expect(screen.getAllByRole('article')).toHaveLength(21);
  expect(screen.getByText('已加载全部')).toBeVisible();
});
it('updates existing image snapshots on bottom refresh while keeping older entries', async () => {
  let y = 1600;
  vi.spyOn(document.documentElement, 'scrollHeight', 'get').mockReturnValue(2400);
  vi.stubGlobal('innerHeight', 800);
  vi.spyOn(window, 'scrollY', 'get').mockImplementation(() => y);
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ top: 100-y, bottom: 500-y } as DOMRect));
  list.mockResolvedValueOnce(reply([images[1], images[0]]));
  open(); await screen.findByRole('article', { name: '图片任务：图片1' });
  list.mockResolvedValueOnce(reply([{ ...images[1], prompt: '刷新后的图片' }]));
  y = 500; fireEvent.scroll(window); y = 1600; fireEvent.scroll(window);
  await screen.findByRole('article', { name: '图片任务：刷新后的图片' });
  expect(screen.getAllByRole('article')).toHaveLength(2);
  expect(screen.getByRole('article', { name: '图片任务：图片0' })).toBeVisible();
});
