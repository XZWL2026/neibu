import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { HomeComposer } from './HomeComposer';
import { HomeBanner } from './HomeBanner';
import { ThemeProvider, ThemeToggle } from '../theme';
import * as api from '../api';

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  mediaOptionsGetApiV1MediaGenerationOptions: vi.fn(), mediaQuotePostApiV1MediaQuotes: vi.fn(),
  mediaListGetApiV1MediaTasks: vi.fn(), mediaSubmitPostApiV1MediaTasks: vi.fn(), storageUploadReferenceImagePostApiV1FilesReferenceImages: vi.fn(),
  storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(), mediaGetGetApiV1MediaTasksTaskId: vi.fn(),
  mediaPortraitListGetApiV1MediaPortraits: vi.fn(), mediaPortraitSubmitPostApiV1MediaPortraits: vi.fn(),
}));
const reply = (data: unknown) => ({ data, status: 200, headers: new Headers() }) as never;
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([]));
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({ models: [
    { type: 'IMAGE', model: 'image-model', displayName: '图片模型', resolutions: ['2K'], aspectRatios: ['1:1', '16:9'], maxCount: 2, minDurationSeconds: 0, maxDurationSeconds: 0, maxReferenceImages: 1 },
    { type: 'VIDEO', model: 'video-model', displayName: '视频模型', resolutions: ['720p'], aspectRatios: ['16:9'], maxCount: 1, minDurationSeconds: 4, maxDurationSeconds: 10, durationStepSeconds: 1, maxReferenceImages: 0 },
    { type: 'VIDEO', model: 'review-model', displayName: '审核模型', resolutions: ['720p'], aspectRatios: ['16:9'], maxCount: 1, minDurationSeconds: 4, maxDurationSeconds: 10, maxReferenceImages: 1, omniReference: { maxImages: 1, maxVideos: 0, maxAudios: 0, maxTotal: 1 } },
  ] }));
  vi.mocked(api.mediaQuotePostApiV1MediaQuotes).mockResolvedValue(reply({ points: '2.0000' }));
  vi.mocked(api.mediaSubmitPostApiV1MediaTasks).mockResolvedValue(reply({ id: 'homepage-task' }));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'homepage-task', status: 'RUNNING' }));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValue(reply({ fileId: 'homepage-file' }));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://assets.example/homepage.png' }));
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([]));
  vi.mocked(api.mediaPortraitSubmitPostApiV1MediaPortraits).mockResolvedValue(reply({ id: 'review-homepage', fileId: 'homepage-file', model: 'review-model', status: 'ACTIVE' }));
});

afterEach(() => { cleanup(); vi.useRealTimers(); });
const home = () => render(<MemoryRouter><input aria-label="搜索内容" /><HomeBanner /><HomeComposer /></MemoryRouter>);

it('collapses on pointer leave with a focused prompt and preserves drafts and settings', async () => {
  home();
  const toggle = screen.getByRole('button', { name: '展开创作区域' });
  const dock = screen.getByRole('region', { name: '快捷创作' });
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  fireEvent.pointerEnter(dock, { pointerType: 'mouse' });
  expect(toggle).toHaveAttribute('aria-expanded', 'true');
  const input = screen.getByRole('textbox', { name: '图片提示词' });
  fireEvent.change(input, { target: { value: '海边的青色星光' } });
  fireEvent.click(screen.getByRole('button', { name: '放大输入框' }));
  act(() => screen.getByRole('textbox', { name: '搜索内容' }).focus());
  fireEvent.pointerLeave(dock);
  await new Promise(resolve => setTimeout(resolve, 250));
  expect(toggle).toHaveAttribute('aria-expanded', 'true');
  fireEvent.keyDown(input, { key: 'Escape' });
  expect(toggle).toHaveAttribute('aria-expanded', 'true');
  expect(screen.getByRole('button', { name: '放大输入框' })).toHaveAttribute('aria-expanded', 'false');
  act(() => input.focus());
  fireEvent.pointerLeave(dock);
  await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'false'));
  expect(toggle).toHaveFocus();
  fireEvent.click(toggle);
  expect(screen.queryByRole('button', { name: '收起创作区域' })).not.toBeInTheDocument();
  fireEvent.click(await screen.findByRole('button', { name: /^图片参数摘要/ }));
  fireEvent.click(screen.getByRole('button', { name: '16:9' }));
  fireEvent.pointerLeave(dock);
  await new Promise(resolve => setTimeout(resolve, 250));
  expect(toggle).toHaveAttribute('aria-expanded', 'true');
  fireEvent.click(screen.getByRole('button', { name: '关闭图片设置' }));
  fireEvent.keyDown(screen.getByRole('textbox', { name: '图片提示词' }), { key: 'Escape' });
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(toggle);
  expect(screen.getByRole('textbox', { name: '图片提示词' })).toHaveValue('海边的青色星光');
});

it('keeps a focused prompt expanded after touch pointer leave', async () => {
  home();
  const toggle = screen.getByRole('button', { name: '展开创作区域' });
  fireEvent.click(toggle);
  const input = screen.getByRole('textbox', { name: '图片提示词' });
  act(() => input.focus());
  const leave = new Event('pointerout', { bubbles: true });
  Object.defineProperty(leave, 'pointerType', { value: 'touch' });
  fireEvent(input, leave);
  await new Promise(resolve => setTimeout(resolve, 250));
  expect(toggle).toHaveAttribute('aria-expanded', 'true');
  expect(input).toHaveFocus();
});

it('retains independent image/video drafts while using server settings', async () => {
  home();
  fireEvent.click(screen.getByRole('button', { name: '展开创作区域' }));
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '图片草稿' } });
  await waitFor(() => expect(screen.getByRole('button', { name: /^图片参数摘要/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('tab', { name: '视频' }));
  fireEvent.change(screen.getByRole('textbox', { name: '视频提示词' }), { target: { value: '视频草稿' } });
  fireEvent.click(screen.getByRole('tab', { name: '图片' }));
  expect(screen.getByRole('textbox', { name: '图片提示词' })).toHaveValue('图片草稿');
  fireEvent.click(screen.getByRole('tab', { name: '视频' }));
  expect(screen.getByRole('textbox', { name: '视频提示词' })).toHaveValue('视频草稿');
});

it('supports banner controls and autoplay pause/resume', () => {
  vi.useFakeTimers();
  home();
  const banner = screen.getByRole('region', { name: '首页精选' });
  expect(within(banner).getByText('YOMIO · IMAGE STUDIO')).toBeVisible();
  expect(within(banner).queryByText(/XZWLAB/i)).not.toBeInTheDocument();
  const first = within(banner).getByRole('button', { name: '第 1 张：用 AI，把想象变成现实。' });
  expect(first).toHaveAttribute('aria-current', 'true');
  act(() => vi.advanceTimersByTime(5000));
  expect(first).toHaveAttribute('aria-current', 'false');
  fireEvent.click(first);
  fireEvent.pointerEnter(banner, { pointerType: 'mouse' });
  act(() => vi.advanceTimersByTime(10000));
  expect(first).toHaveAttribute('aria-current', 'true');
  fireEvent.click(within(banner).getByRole('button', { name: '下一张' }));
  expect(first).toHaveAttribute('aria-current', 'false');
  fireEvent.click(within(banner).getByRole('button', { name: '上一张' }));
  expect(first).toHaveAttribute('aria-current', 'true');
});

it('closes after leaving without focus and supports touch plus keyboard dismissal', () => {
  vi.useFakeTimers();
  home();
  const dock = screen.getByRole('region', { name: '快捷创作' });
  const toggle = screen.getByRole('button', { name: '展开创作区域' });
  fireEvent.pointerEnter(dock);
  fireEvent.pointerLeave(dock);
  act(() => vi.advanceTimersByTime(200));
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(toggle);
  expect(toggle).toHaveAttribute('aria-expanded', 'true');
  fireEvent.keyDown(screen.getByRole('tab', { name: '图片' }), { key: 'ArrowRight' });
  expect(screen.getByRole('tab', { name: '视频' })).toHaveFocus();
  fireEvent.keyDown(dock, { key: 'Escape' });
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  expect(toggle).toHaveFocus();
});

it('preserves uploaded material through collapsing and restores the original submission', async () => {
  home();
  const toggle = screen.getByRole('button', { name: '展开创作区域' });
  fireEvent.click(toggle);
  await waitFor(() => expect(screen.getByRole('button', { name: '添加素材' })).toBeEnabled());
  fireEvent.change(within(screen.getByRole('region', { name: '图片生成' })).getByLabelText('上传素材'), {
    target: { files: [new File(['image'], 'homepage.png', { type: 'image/png' })] },
  });
  await screen.findByText('homepage.png');
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  expect(screen.getAllByAltText('homepage.png')[0]).toBeInTheDocument();
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '保留素材与提示词' } });
  fireEvent.keyDown(screen.getByRole('textbox', { name: '图片提示词' }), { key: 'Escape' });
  fireEvent.click(toggle);
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  expect(screen.getAllByAltText('homepage.png')[0]).toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
  expect(screen.queryByRole('article', { name: /^图片任务/ })).not.toBeInTheDocument();
  expect(api.mediaSubmitPostApiV1MediaTasks).not.toHaveBeenCalled();
});

it('ignores a retained inactive draft when dismissing the active composer', () => {
  vi.useFakeTimers();
  home();
  const dock = screen.getByRole('region', { name: '快捷创作' });
  fireEvent.click(screen.getByRole('button', { name: '展开创作区域' }));
  fireEvent.change(screen.getByRole('textbox', { name: '图片提示词' }), { target: { value: '保留图片草稿' } });
  fireEvent.click(screen.getByRole('button', { name: '放大输入框' }));
  fireEvent.click(screen.getByRole('tab', { name: '视频' }));
  act(() => screen.getByRole('textbox', { name: '搜索内容' }).focus());
  fireEvent.pointerLeave(dock);
  act(() => vi.advanceTimersByTime(200));
  expect(screen.getByRole('button', { name: '展开创作区域' })).toHaveAttribute('aria-expanded', 'false');
});

it('keeps keyboard focus on a visible control after changing the focused slide', () => {
  home();
  const banner = screen.getByRole('region', { name: '首页精选' });
  const link = within(banner).getByRole('link', { name: '用 AI，把想象变成现实。' });
  act(() => link.focus());
  fireEvent.keyDown(link, { key: 'ArrowRight' });
  expect(within(banner).getByRole('button', { name: '第 2 张：让故事，在光影间发生' })).toHaveFocus();
});

it('dismisses the mention menu before collapsing the home composer', () => {
  home();
  const toggle = screen.getByRole('button', { name: '展开创作区域' });
  fireEvent.click(toggle);
  const prompt = screen.getByRole('textbox', { name: '图片提示词' });
  fireEvent.change(prompt, { target: { value: '@', selectionStart: 1 } });
  expect(screen.getByRole('listbox', { name: '引用已上传素材' })).toBeVisible();
  fireEvent.keyDown(prompt, { key: 'Escape' });
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  expect(toggle).toHaveAttribute('aria-expanded', 'true');
  fireEvent.keyDown(prompt, { key: 'Escape' });
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
});

it('keeps lightweight hero images visible without waiting for another load event', () => {
  home();
  const hero = document.querySelector<HTMLImageElement>('.home-banner-card img')!;
  expect(hero.src).toContain('light-home-reference.webp');
  for (const image of [hero]) {
    expect(image).toHaveAttribute('fetchpriority', 'high');
    expect(image).toHaveAttribute('decoding', 'async');
    expect(image).toBeVisible();
  }
});

it('switches hero artwork with the shared theme without replacing the carousel', () => {
  localStorage.removeItem('yomio-theme');
  render(<ThemeProvider><MemoryRouter><ThemeToggle /><HomeBanner /></MemoryRouter></ThemeProvider>);
  const hero = document.querySelector<HTMLImageElement>('.home-banner-card img')!;
  fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
  expect(hero.src).toContain('cinematic-hero.webp');
  fireEvent.click(screen.getByRole('button', { name: '切换至浅色主题' }));
  expect(hero.src).toContain('light-home-reference.webp');
  localStorage.removeItem('yomio-theme');
});

it('runs the light hero breeze only while visible and respects pause and reduced motion', () => {
  let motionChange: (() => void) | undefined;
  const media = { matches: false, addEventListener: (_: string, listener: () => void) => { motionChange = listener; }, removeEventListener: vi.fn() };
  vi.stubGlobal('matchMedia', () => media);
  const pause = vi.fn();
  const resume = vi.fn();
  Object.defineProperties(SVGSVGElement.prototype, {
    pauseAnimations: { configurable: true, value: pause },
    unpauseAnimations: { configurable: true, value: resume },
  });
  try {
    localStorage.removeItem('yomio-theme');
    render(<ThemeProvider><MemoryRouter><ThemeToggle /><HomeBanner /></MemoryRouter></ThemeProvider>);
    const hero = document.querySelector<HTMLImageElement>('.home-banner-card img')!;
    expect(hero.style.filter).toContain('url(');
    const gusts = document.querySelectorAll('.home-breeze animate');
    expect(gusts.length).toBeGreaterThan(0);
    for (const gust of gusts) {
      const frames = gust.getAttribute('values')!.split(';').map(Number);
      expect(frames.every(value => Number.isFinite(value) && value >= 0)).toBe(true);
      expect(frames.at(-1)).toBe(frames[0]);
      expect(Math.max(...frames) - Math.min(...frames)).toBeGreaterThan(.05);
      expect(frames.slice(1).every((value, index) => Math.abs(value - frames[index]) < .005)).toBe(true);
    }
    expect(resume).toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '暂停轮播' }));
    expect(pause).toHaveBeenCalled();
    resume.mockClear();
    fireEvent.click(screen.getByRole('button', { name: '播放轮播' }));
    expect(resume).toHaveBeenCalled();
    pause.mockClear();
    fireEvent.click(screen.getByRole('button', { name: '下一张' }));
    expect(pause).toHaveBeenCalled();
    resume.mockClear();
    fireEvent.click(screen.getByRole('button', { name: '上一张' }));
    expect(resume).toHaveBeenCalled();
    pause.mockClear();
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    fireEvent(document, new Event('visibilitychange'));
    expect(pause).toHaveBeenCalled();
    vi.restoreAllMocks();
    fireEvent(document, new Event('visibilitychange'));
    act(() => { media.matches = true; motionChange?.(); });
    expect(hero.style.filter).toBe('');
    expect(screen.getByRole('button', { name: '暂停轮播' })).toBeDisabled();
    act(() => { media.matches = false; motionChange?.(); });
    fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
    expect(hero.style.filter).toBe('');
    expect(document.querySelector('.home-breeze')).not.toBeInTheDocument();
  } finally {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    delete (SVGSVGElement.prototype as Partial<SVGSVGElement>).pauseAnimations;
    delete (SVGSVGElement.prototype as Partial<SVGSVGElement>).unpauseAnimations;
    localStorage.removeItem('yomio-theme');
  }
});
