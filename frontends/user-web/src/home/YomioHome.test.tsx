import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter } from 'react-router-dom';
import { axe } from 'vitest-axe';
import { App } from '../App';
import { userRoutes } from '../router';
import { readComposerDraft } from '../creator/composer-draft';
import { homeCreationEntry, homeModelMatches } from './home-entry';
import { selectLanguage, t } from '../i18n';

beforeEach(() => {
  localStorage.clear();
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); selectLanguage('zh-CN'); });
function mount(entry = '/home') {
  const router = createMemoryRouter([...userRoutes.filter(route => route.path !== '/create/video' && route.path !== '/create/image'),
    { path: '/create/video', element: <h1>视频创作目标</h1> }, { path: '/create/image', element: <h1>图片创作目标</h1> }], { initialEntries: [entry] });
  const view = render(<App fixture router={router} />);
  return { router, view };
}

it('switches the real homepage menu, filters, cards and existing error through every language without losing input', () => {
 const { view: { container } } = mount();
 fireEvent.focus(screen.getByRole('textbox', { name: '创作描述' }));
 fireEvent.submit(container.querySelector('form')!);
 expect(screen.getByRole('alert')).toHaveTextContent('请先描述你的想法，再开始创作。');
 for (const language of ['en', 'ja', 'ko', 'zh-CN'] as const) {
  act(() => selectLanguage(language));
  expect(screen.getByRole('alert')).toHaveTextContent(t('请先描述你的想法，再开始创作。'));
 }
 const input = screen.getByRole('textbox', { name: '创作描述' });
 fireEvent.change(input, { target: { value: '首页 · My own idea' } });
 const images = Array.from(container.querySelectorAll('.ym-artwork-cover')).map(image => image.getAttribute('src'));
 for (const language of ['en', 'ja', 'ko', 'zh-CN'] as const) {
  fireEvent.click(screen.getByRole('button', { name: t('语言切换') }));
  fireEvent.click(screen.getByRole('button', { name: { en: 'English', ja: '日本語', ko: '한국어', 'zh-CN': '简体中文' }[language] }));
  expect(screen.getByRole('textbox', { name: t('创作描述') })).toBe(input);
  expect(input).toHaveValue('首页 · My own idea');
  expect(input).toHaveAttribute('placeholder', t('描述你的想法，创造你的影像...'));
  expect(screen.getByRole('button', { name: t('社区') })).toBeVisible();
  expect(screen.getByRole('region', { name: t('电商创作专区') })).toBeInTheDocument();
  const gallery = within(screen.getByRole('region', { name: t('精选作品') }));
  expect(gallery.getByText(t('云际之境'))).toBeVisible();
  expect(gallery.getByText('唐羽')).toBeVisible();
  expect(Array.from(container.querySelectorAll('.ym-artwork-cover')).map(image => image.getAttribute('src'))).toEqual(images);
  fireEvent.click(gallery.getByRole('button', { name: t('短剧') }));
  expect(container.querySelectorAll('.ym-artwork-card')).toHaveLength(2);
  fireEvent.click(gallery.getByRole('button', { name: t('全部') }));
 }
});

it('opens the story entry with the same prompt and video model as the homepage action', () => {
  const { router } = mount('/home?entry=story');
  const input = screen.getByRole('textbox', { name: '创作描述' });
  expect(input).toHaveValue('为一段短剧编排故事和镜头，');
  expect(input).toHaveFocus();
  fireEvent.click(screen.getByRole('button', { name: '开始创作' }));
  expect(router.state.location.pathname).toBe('/create/video');
  expect(homeCreationEntry(router.state.location.state)).toMatchObject({ prompt: '为一段短剧编排故事和镜头，', modelName: 'Seedance 2.5' });
});

it('ships three sections with real commerce/history routes, real fixture balance and an accessible home', async () => {
  const { view } = mount();
  expect(screen.getByRole('heading', { name: '精选作品' })).toBeVisible();
  expect(screen.getByRole('link', { name: '产品精修' })).toHaveAttribute('href', '/commerce/product-retouch');
  expect(screen.getByRole('link', { name: 'A+/详情页' })).toHaveAttribute('href', '/commerce/detail-page');
  expect(screen.getByRole('link', { name: '商品套图' })).toHaveAttribute('href', '/commerce/product-suite');
  expect(screen.getByRole('link', { name: '模特穿戴' })).toHaveAttribute('href', '/commerce/model-wear');
  expect(screen.getByRole('link', { name: '视频复刻' })).toHaveAttribute('href', '/commerce/video-replica');
  expect(screen.getByRole('link', { name: '我的作品' })).toHaveAttribute('href', '/create/image?tab=history');
  expect(screen.getByRole('link', { name: '充值' })).toHaveAttribute('href', '/account/recharge');
  expect(view.container).not.toHaveTextContent('1552');
  expect((await axe(view.container, { rules: { 'color-contrast': { enabled: false } } })).violations).toEqual([]);
});

it('filters all twelve works across the six actual categories', () => {
  mount();
  const gallery = within(screen.getByRole('region', { name: '精选作品' }));
  for (const [name, count] of [['全部', 12], ['精选画布', 3], ['专业影视', 3], ['短剧', 2], ['漫剧', 2], ['电商', 2]] as const) {
    fireEvent.click(gallery.getByRole('button', { name }));
    expect(gallery.getAllByRole('button', { name: /^查看 / })).toHaveLength(count);
    expect(gallery.getByRole('button', { name })).toHaveAttribute('aria-pressed', 'true');
  }
});

it('hands prompt and chosen model to the creator without submitting a generation request', () => {
  const { router } = mount();
  fireEvent.focus(screen.getByRole('textbox', { name: '创作描述' }));
  fireEvent.click(within(screen.getByRole('group', { name: '生成类型' })).getByRole('button', { name: '图片' }));
  fireEvent.change(screen.getByRole('textbox', { name: '创作描述' }), { target: { value: '晨光里的海岸' } });
  fireEvent.click(screen.getByRole('button', { name: '开始创作' }));
  expect(router.state.location.pathname).toBe('/create/image');
  expect(homeCreationEntry(router.state.location.state)).toMatchObject({ source: 'yomio-home', prompt: '晨光里的海岸', modelName: 'Image 2', mode: 'auto', materials: [] });
  const stored = Object.keys(localStorage).find(key => key.startsWith('xzwl-composer-draft:'));
  expect(stored).toBeDefined();
  const [owner] = JSON.parse(stored!.slice('xzwl-composer-draft:'.length));
  expect(readComposerDraft(owner, 'create', 'IMAGE')?.prompt).toBe('晨光里的海岸');
});

it('stays on the homepage and exposes the real recovery message if durable storage fails', () => {
  const { router } = mount();
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
  fireEvent.change(screen.getByRole('textbox', { name: '创作描述' }), { target: { value: '森林晨光' } });
  fireEvent.click(screen.getByRole('button', { name: '开始创作' }));
  expect(router.state.location.pathname).toBe('/home');
  expect(screen.getByRole('alert')).toHaveTextContent('草稿保存失败');
});

it.each([
  ['Seedance 2.5', 'video', ''], ['Seedance 2.0', 'video', ''],
  ['Image 2', 'image', ''], ['Nano Banana', 'image', ''],
  ['Seedance 2.5', 'video', '晨光里的海岸'], ['Seedance 2.0', 'video', '晨光里的海岸'],
  ['Image 2', 'image', '晨光里的海岸'], ['Nano Banana', 'image', '晨光里的海岸'],
])('opens %s directly in the %s workspace with description "%s"', (name, kind, prompt) => {
  const { router } = mount();
  fireEvent.change(screen.getByRole('textbox', { name: '创作描述' }), { target: { value: prompt } });
  const card = within(screen.getByRole('region', { name: '热门模型' })).getByRole('link', { name: new RegExp(name) });
  expect(card).toHaveAttribute('href', `/create/${kind}`);
  fireEvent.click(card);
  expect(router.state.location.pathname).toBe(`/create/${kind}`);
  expect(homeCreationEntry(router.state.location.state)).toEqual({ source: 'yomio-home', prompt, modelName: name });
});

it('moves the model highlight on hover and focus, then restores the selected model without navigating', () => {
  const { router } = mount();
  const region = screen.getByRole('region', { name: '热门模型' });
  const links = within(region).getAllByRole('link');
  const grid = links[0].parentElement!;
  expect(links[0]).toHaveClass('ym-highlighted');
  for (const link of links.slice(1)) {
    fireEvent.pointerEnter(link, { pointerType: 'mouse' });
    expect(link).toHaveClass('ym-highlighted');
    expect(region.querySelectorAll('.ym-highlighted')).toHaveLength(1);
    expect(links[0]).toHaveClass('ym-chosen');
    expect(router.state.location.pathname).toBe('/home');
  }
  fireEvent.pointerLeave(grid);
  expect(links[0]).toHaveClass('ym-highlighted');
  fireEvent.focus(links[2]);
  expect(links[2]).toHaveClass('ym-highlighted');
  fireEvent.pointerEnter(links[3], { pointerType: 'mouse' });
  expect(links[3]).toHaveClass('ym-highlighted');
  fireEvent.pointerLeave(grid);
  expect(links[2]).toHaveClass('ym-highlighted');
  fireEvent.blur(links[2]);
  expect(links[0]).toHaveClass('ym-highlighted');
});

it('matches server display names and rejects arbitrary router state', () => {
  expect(homeModelMatches('GPT Image 2', 'Image 2')).toBe(true);
  expect(homeModelMatches('Seedance 2.0', 'Seedance 2.5')).toBe(false);
  expect(homeModelMatches('Nano Banana Pro', 'Nano Banana')).toBe(true);
  expect(homeCreationEntry({ source: 'other', prompt: 'x' })).toBeNull();
  expect(homeCreationEntry({ source: 'yomio-home', prompt: 'x'.repeat(2001) })).toBeNull();
});

it('keeps the user description when switching inspiration and exposes the selected creator kind', () => {
  mount();
  const input = screen.getByRole('textbox', { name: '创作描述' });
  fireEvent.change(input, { target: { value: '海边的一座白色房子' } });
  fireEvent.click(screen.getByRole('button', { name: '自然' }));
  expect(input).toHaveValue('自然风光中，海边的一座白色房子');
  fireEvent.click(screen.getByRole('button', { name: '未来' }));
  expect(input).toHaveValue('未来世界中，海边的一座白色房子');
  expect(screen.getByRole('button', { name: '未来' })).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByRole('button', { name: '自然' })).toHaveAttribute('aria-pressed', 'false');
  fireEvent.focus(input);
  fireEvent.click(within(screen.getByRole('group', { name: '生成类型' })).getByRole('button', { name: '图片' }));
  fireEvent.click(screen.getByRole('button', { name: '展开创作导航' }));
  expect(screen.getByRole('link', { name: '图片' })).toHaveClass('ym-selected');
  expect(screen.getByRole('link', { name: '视频' })).not.toHaveClass('ym-selected');
  expect(input).toHaveValue('未来世界中，海边的一座白色房子');
});

it('provides an inline empty-description recovery without navigating away', () => {
  const { router } = mount();
  const input = screen.getByRole('textbox', { name: '创作描述' });
  fireEvent.click(screen.getByRole('button', { name: '开始创作' }));
  expect(router.state.location.pathname).toBe('/home');
  expect(screen.getByRole('alert')).toHaveTextContent('请先描述你的想法');
  expect(input).toHaveFocus();
  expect(input).toHaveAttribute('aria-invalid', 'true');
  fireEvent.change(input, { target: { value: '山间晨雾' } });
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(input).toHaveAttribute('aria-invalid', 'false');
});

it('never truncates a near-limit user description when adding inspiration', () => {
  mount();
  const input = screen.getByRole('textbox', { name: '创作描述' });
  const description = '海'.repeat(1999);
  fireEvent.change(input, { target: { value: description } });
  fireEvent.click(screen.getByRole('button', { name: '自然' }));
  expect(input).toHaveValue(description);
  expect(screen.getByRole('alert')).toHaveTextContent('添加灵感会超过 2000 字');
  expect(input).toHaveFocus();
  expect(input).toHaveAttribute('aria-invalid', 'false');
});

it('defaults to a collapsed navigation and supports explicit opening and Escape recovery', () => {
  mount();
  const trigger = screen.getByRole('button', { name: '展开创作导航' });
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
  expect(screen.queryByRole('link', { name: '图片' })).not.toBeInTheDocument();
  fireEvent.click(trigger);
  expect(trigger).toHaveAttribute('aria-expanded', 'true');
  expect(screen.getByRole('link', { name: '图片' })).toHaveAttribute('href', '/create/image');
  fireEvent.keyDown(trigger, { key: 'Escape' });
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
  expect(trigger).toHaveFocus();
  expect(screen.queryByRole('link', { name: '图片' })).not.toBeInTheDocument();
});
