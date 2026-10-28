import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter } from 'react-router-dom';
import { App } from '../App';
import { userRoutes } from '../router';
import { configureGateway } from '../api';
import { axe } from 'vitest-axe';
import brandLogo from '../assets/brand.webp';

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const emptyContent = { nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } };
const summary = (id: string, name: string) => ({ id, name, updatedAt: '2026-09-09T02:00:00Z', previewNodes: [
  { id: 'text-1', kind: 'text', properties: { text: '真实灵感', x: '20', y: '30' } },
] });
let account: string | null;
let records: ReturnType<typeof summary>[];
let network: ReturnType<typeof vi.fn<(url: string, options: RequestInit) => Promise<Response>>>;
beforeEach(() => {
  configureGateway({}); localStorage.removeItem('yomio-theme'); account = 'alice'; records = [];
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
  network = vi.fn(async (url: string, options: RequestInit) => {
    if (url.endsWith('/auth/session')) return json({ authenticated: !!account, csrfToken: 'csrf', roles: ['USER'], user: account ? { id: account, username: account } : null });
    if (url.includes('/canvases?')) return json({ items: records, hasMore: false });
    if (url.endsWith('/canvases') && options.method === 'POST') return json({ id: 'new-canvas', name: '未命名画布', ownerId: account, version: 1, content: emptyContent, updatedAt: '2026-09-09T02:00:00Z' }, 201);
    if (url.includes('/canvases/')) return json({ id: url.split('/').at(-1), name: '真实画布', ownerId: account, version: 1, content: emptyContent, updatedAt: '2026-09-09T02:00:00Z' });
    if (url.endsWith('/generation-options')) return json({ models: [] });
    throw new Error(`Unexpected request: ${url}`);
  });
  vi.stubGlobal('fetch', network);
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.removeItem('yomio-theme'); });
function show(path = '/canvas') {
  const router = createMemoryRouter(userRoutes, { initialEntries: [path] });
  render(<App router={router} />); return router;
}

it('restores canvas navigation only at the top like commerce', async () => {
  vi.stubGlobal('scrollY', 0);
  show();
  await screen.findByRole('heading', { name: '最近作品' });
  const page = document.querySelector('.canvas-home-frame');
  expect(page).toHaveAttribute('data-nav-hidden', 'false');
  for (const offset of [240, 180, 20, 1]) {
    vi.stubGlobal('scrollY', offset);
    fireEvent.scroll(window);
    expect(page).toHaveAttribute('data-nav-hidden', 'true');
  }
  vi.stubGlobal('scrollY', 0);
  fireEvent.scroll(window);
  expect(page).toHaveAttribute('data-nav-hidden', 'false');
});

it('keeps current canvas navigation without a sidebar avatar and retains the header account menu', async () => {
  const router = createMemoryRouter(userRoutes, { initialEntries: ['/canvas'] });
  render(<App fixture router={router} />);
  await screen.findByRole('heading', { name: '最近作品' });
  const logo = screen.getByRole('link', { name: 'YOMIO 首页' }).querySelector('img');
  expect(logo).toHaveAttribute('src', brandLogo);
  expect(logo).toHaveAttribute('width', '600');
  expect(logo).toHaveAttribute('height', '221');
  const shortcuts = screen.getByRole('region', { name: '快速开始' });
  expect(within(shortcuts).getByRole('link', { name: /电商创作/ })).toHaveAttribute('href', '/commerce');
  expect(screen.getByRole('heading', { level: 1, name: '画布创作' })).toBeVisible();
  const sidebar = screen.getByRole('complementary', { name: '用户端导航' });
  fireEvent.pointerEnter(sidebar, { pointerType: 'mouse' });
  expect(within(sidebar).getByRole('link', { name: '画布' })).toHaveAttribute('aria-current', 'page');
  expect(screen.getByRole('link', { name: 'YOMIO 首页' })).toHaveAttribute('href', '/home');
  expect(within(sidebar).queryByRole('link', { name: '个人中心' })).not.toBeInTheDocument();
  expect(sidebar.querySelector('img')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '打开用户菜单' }));
  const profile = screen.getByRole('link', { name: '个人中心' });
  fireEvent.click(profile);
  expect(router.state.location.pathname).toBe('/account/profile');
  expect(screen.getByRole('complementary', { name: '用户端导航' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '返回首页' })).toHaveAttribute('aria-expanded', 'false');
});

it('navigation opens the home, shows a genuine empty state and never mounts the editor', async () => {
  show('/home');
  fireEvent.click(screen.getByRole('button', { name: '展开创作导航' }));
  fireEvent.click(screen.getByRole('link', { name: '画布' }));
  expect(await screen.findByRole('heading', { name: '最近作品' })).toBeVisible();
  expect(await screen.findByText('还没有画布，开始你的第一次创作')).toBeVisible();
  expect(screen.queryByTestId('canvas-workspace')).not.toBeInTheDocument();
  expect(screen.queryByText('本地 fixture')).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: '查看全部' })).toHaveAttribute('href', '#my-canvases');
});

it('returns from an opened canvas editor to the canvas navigation page', async () => {
  records = [summary('recent', '最近画布')];
  const router = show('/home');
  fireEvent.click(screen.getByRole('button', { name: '展开创作导航' }));
  fireEvent.click(screen.getByRole('link', { name: '画布' }));
  fireEvent.click(await screen.findByRole('link', { name: '打开画布：最近画布' }));
  expect(router.state.location.pathname + router.state.location.search).toBe('/canvas?id=recent');
  await waitFor(() => expect(screen.getByRole('button', { name: '保存画布' })).toBeEnabled());
  const back = screen.getByRole('link', { name: '返回画布首页 · YOMIO' });
  expect(back).toHaveAttribute('href', '/canvas');
  fireEvent.click(back);
  await waitFor(() => expect(router.state.location.pathname + router.state.location.search).toBe('/canvas'));
  expect(await screen.findByRole('heading', { name: '最近作品' })).toBeVisible();
  expect(screen.getByRole('link', { name: '打开画布：最近画布' })).toBeVisible();
});

it.each(['light', 'dark'])('new canvas creates only once while pending and opens the returned independent ID (%s)', async theme => {
  localStorage.setItem('yomio-theme', theme);
  let finish!: () => void;
  const original = network.getMockImplementation()!;
  network.mockImplementation(async (url, options) => {
    if (url.endsWith('/canvases') && options.method === 'POST') await new Promise<void>(resolve => { finish = resolve; });
    return original(url, options);
  });
  const router = show();
  const create = await screen.findByRole('button', { name: '开始创作' });
  expect(create).toHaveTextContent('开始创作');
  await waitFor(() => expect(create).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /空白画布/ })); fireEvent.click(create);
  await waitFor(() => expect(finish).toBeDefined());
  expect(create).toBeDisabled();
  expect(screen.getByRole('button', { name: /空白画布/ })).toBeDisabled();
  await act(async () => finish());
  await waitFor(() => expect(router.state.location.search).toBe('?id=new-canvas'));
  expect(network.mock.calls.filter(([url, options]) => url.endsWith('/canvases') && options.method === 'POST')).toHaveLength(1);
  expect(await screen.findByTestId('canvas-workspace')).toBeVisible();
});

it('allows a real name before creating the saved canvas', async () => {
  show();
  const create = await screen.findByRole('button', { name: '开始创作' });
  await waitFor(() => expect(create).toBeEnabled());
  fireEvent.click(screen.getByText('设置名称'));
  fireEvent.change(screen.getByRole('textbox', { name: '新画布名称' }), { target: { value: '  我的产品分镜  ' } });
  fireEvent.click(create);
  await waitFor(() => expect(network.mock.calls.some(([url, options]) => url.endsWith('/canvases') && options.method === 'POST')).toBe(true));
  const [, options] = network.mock.calls.find(([url, options]) => url.endsWith('/canvases') && options.method === 'POST')!;
  expect(JSON.parse(options.body as string).name).toBe('我的产品分镜');
});

it('renders real saved cards, retains server ordering, and opens the selected detail link', async () => {
  records = [summary('recent', '最近的分镜'), summary('older', '之前的分镜')];
  const router = show();
  const links = await screen.findAllByRole('link', { name: /^打开画布：/ });
  expect(links.map(link => link.getAttribute('href'))).toEqual(['/canvas?id=recent', '/canvas?id=older']);
  expect(screen.getByRole('img', { name: '最近的分镜预览缩略图' })).toBeVisible();
  expect(screen.getAllByText(/更新于/)).toHaveLength(2);
  fireEvent.click(links[1]);
  await waitFor(() => expect(router.state.location.search).toBe('?id=older'));
  expect(await screen.findByTestId('canvas-workspace')).toBeVisible();
});

it('refreshes the list on remount rather than using local history', async () => {
  records = [summary('before', '旧画布')]; show();
  await screen.findByText('旧画布'); cleanup();
  records = [summary('after', '另一台设备创建的画布')]; show();
  expect(await screen.findByText('另一台设备创建的画布')).toBeVisible();
  expect(screen.queryByText('旧画布')).not.toBeInTheDocument();
});

it('separates loading and request failure from empty state and retries through the SDK', async () => {
  const original = network.getMockImplementation()!;
  let fail = true;
  network.mockImplementation((url, options) => url.includes('/canvases?') && fail ? Promise.resolve(json({}, 503)) : original(url, options));
  show();
  expect(screen.getByText('正在加载你的画布…')).toHaveAttribute('role', 'status');
  expect(await screen.findByRole('alert')).toHaveTextContent('画布列表加载失败');
  expect(screen.queryByText('还没有画布，开始你的第一次创作')).not.toBeInTheDocument();
  fail = false;
  fireEvent.click(screen.getByRole('button', { name: '重新加载' }));
  expect(await screen.findByText('还没有画布，开始你的第一次创作')).toBeVisible();
});

it('does not load another user’s data when anonymous and revalidates identity on focus', async () => {
  records = [summary('private', 'Alice 私有画布')];
  const router = show();
  await screen.findByText('Alice 私有画布');
  account = null;
  fireEvent.focus(window);
  await waitFor(() => expect(router.state.location.pathname).toBe('/user/login'));
  expect(screen.queryByText('Alice 私有画布')).not.toBeInTheDocument();
});

it('discards a previous account’s pending list when focus starts a new session load', async () => {
  let finish!: (response: Response) => void;
  const original = network.getMockImplementation()!;
  let lists = 0;
  network.mockImplementation((url, options) => {
    if (url.includes('/canvases?') && ++lists === 1) return new Promise<Response>(resolve => { finish = resolve; });
    return original(url, options);
  });
  show();
  await waitFor(() => expect(finish).toBeDefined());
  account = 'bob'; records = [summary('bob', 'Bob 的画布')];
  fireEvent.focus(window);
  await screen.findByText('Bob 的画布');
  await act(async () => finish(json({ items: [summary('alice', '迟到的 Alice 画布')], hasMore: false })));
  expect(screen.queryByText('迟到的 Alice 画布')).not.toBeInTheDocument();
});

it('reports failed creation without opening an editor or automatically retrying POST', async () => {
  const original = network.getMockImplementation()!;
  network.mockImplementation((url, options) => url.endsWith('/canvases') && options.method === 'POST' ? Promise.reject(new TypeError('offline')) : original(url, options));
  const router = show();
  const create = await screen.findByRole('button', { name: '开始创作' });
  await waitFor(() => expect(create).toBeEnabled()); fireEvent.click(create);
  expect(await screen.findByRole('alert')).toHaveTextContent('创建画布失败');
  expect(router.state.location.search).toBe('');
  expect(screen.queryByTestId('canvas-workspace')).not.toBeInTheDocument();
});

it('loads further pages without duplicate cards and keeps retrying the failed offset', async () => {
  const original = network.getMockImplementation()!;
  const offsets: string[] = [];
  let fail = true;
  network.mockImplementation((url, options) => {
    if (url.includes('/canvases?')) {
      const offset = new URL(url, 'http://localhost').searchParams.get('offset')!;
      offsets.push(offset);
      if (offset === '0') return Promise.resolve(json({ items: [summary('first', '第一张画布')], hasMore: true }));
      if (fail) return Promise.resolve(json({}, 503));
      return Promise.resolve(json({ items: [summary('first', '第一张画布'), summary('next', '下一页画布')], hasMore: false }));
    }
    return original(url, options);
  });
  show();
  fireEvent.click(await screen.findByRole('button', { name: '加载更多画布' }));
  await screen.findByRole('alert');
  expect(screen.getByText('第一张画布')).toBeVisible();
  fail = false;
  fireEvent.click(screen.getByRole('button', { name: '重新加载' }));
  await screen.findByText('下一页画布');
  expect(screen.getAllByRole('link', { name: /^打开画布：/ })).toHaveLength(2);
  expect(offsets).toEqual(['0', '1', '1']);
  expect(screen.queryByRole('button', { name: '加载更多画布' })).not.toBeInTheDocument();
});

it('uses the saved image reference for thumbnails and keeps the card usable when the image fails', async () => {
  records = [{ ...summary('image', '图片画布'), previewNodes: [{ id: 'image-1', kind: 'image', properties: { publicId: 'saved-file', x: '0', y: '0' } }] } as any];
  const original = network.getMockImplementation()!;
  network.mockImplementation((url, options) => url.endsWith('/access-url')
    ? Promise.resolve(json({ url: 'https://cdn.example.test/saved-image.png' })) : original(url, options));
  show();
  await waitFor(() => expect(screen.getByRole('img', { name: '图片画布预览缩略图' })).toHaveAttribute('src', 'https://cdn.example.test/saved-image.png'));
  fireEvent.error(screen.getByRole('img', { name: '图片画布预览缩略图' }));
  expect(screen.getByRole('img', { name: '图片画布预览缩略图' }).tagName).toBe('svg');
  expect(screen.getByRole('link', { name: '打开画布：图片画布' })).toHaveAttribute('href', '/canvas?id=image');
});

it('denies an administrator session and never queries canvas data', async () => {
  network.mockResolvedValue(json({ authenticated: true, csrfToken: 'csrf', roles: ['ADMIN'], user: { id: 'admin', username: 'admin' } }));
  show();
  expect(await screen.findByRole('alert')).toHaveTextContent('请使用创作者账号登录');
  expect(network.mock.calls.some(([url]) => url.includes('/canvases'))).toBe(false);
});

it('exposes labelled navigation, cards and preview with no automated accessibility violations', async () => {
  records = [summary('accessible', '可访问画布')];
  show();
  await screen.findByRole('link', { name: '打开画布：可访问画布' });
  // jsdom has no raster context; browser verification covers rendered colors and layout.
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
  expect((await axe(document.body)).violations).toEqual([]);
});


it('starts canvas home at the top on entry and repeat navigation while preserving anchors', async () => {
  const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  try {
    const router = show();
    expect(scroll).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'instant' });
    scroll.mockClear();
    await act(() => router.navigate('/canvas'));
    expect(scroll).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'instant' });
    scroll.mockClear();
    await act(() => router.navigate('/canvas#my-canvases'));
    expect(scroll).not.toHaveBeenCalled();
  } finally { scroll.mockRestore(); }
});

it.each(['light', 'dark'])('shows all saved canvases and four accessible creation shortcuts (%s)', async theme => {
  localStorage.setItem('yomio-theme', theme);
  records = [summary('mountain', '雪山清晨'), summary('city', '城市夜景')];
  show();
  await screen.findByRole('link', { name: '打开画布：雪山清晨' });
  expect(screen.queryByRole('searchbox')).not.toBeInTheDocument();
  const shortcuts = screen.getByRole('region', { name: '快速开始' });
  expect(within(shortcuts).getAllByRole('link')).toHaveLength(3);
  expect(within(shortcuts).getByRole('button', { name: /空白画布/ })).toBeEnabled();
  expect(screen.getAllByRole('link', { name: /^打开画布：/ })).toHaveLength(2);
  expect(network.mock.calls.some(([url, options]) => url.endsWith('/canvases') && options.method === 'POST')).toBe(false);
});

it('shares home header utilities on canvas with working language, invitation and recharge actions', async () => {
  const router = createMemoryRouter(userRoutes, { initialEntries: ['/canvas'] });
  render(<App fixture router={router} />);
  await screen.findByRole('heading', { name: '最近作品' });
  const header = screen.getByRole('banner');
  const language = within(header).getByRole('button', { name: '语言切换' });
  fireEvent.click(language);
  expect(within(header).getByRole('button', { name: /简体中文/ })).toBeVisible();
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(language).toHaveAttribute('aria-expanded', 'false');
  expect(within(header).getByLabelText('积分余额')).toBeVisible();
  expect(within(header).getByRole('link', { name: '充值' })).toHaveAttribute('href', '/account/recharge');
  fireEvent.click(within(header).getByRole('link', { name: '邀请' }));
  expect(router.state.location.pathname).toBe('/account/referrals');
});


it('switches the hero artwork and keeps only the active theme controls accessible', async () => {
  localStorage.setItem('yomio-theme', 'dark');
  show();
  await waitFor(() => expect(screen.getByRole('button', { name: '开始创作' })).toBeEnabled());
  expect(screen.getByRole('heading', { level: 1, name: '画布创作' })).toBeVisible();
  expect(document.querySelector('.theme-artwork-layer[data-active="true"] .canvas-prism-backdrop')).toHaveAttribute('data-prism-theme', 'dark');
  fireEvent.click(screen.getByText('设置名称'));
  fireEvent.change(screen.getByRole('textbox', { name: '新画布名称' }), { target: { value: '主题切换草稿' } });
  const shortcuts = within(screen.getByRole('region', { name: '快速开始' }));
  expect(shortcuts.getByRole('link', { name: /图片生成/ })).toHaveAttribute('href', '/create/image');
  expect(shortcuts.getByRole('link', { name: /视频生成/ })).toHaveAttribute('href', '/create/video');
  expect(shortcuts.getByRole('link', { name: /电商创作/ })).toHaveAttribute('href', '/commerce');
  fireEvent.click(screen.getByRole('button', { name: '切换至浅色主题' }));
  expect(document.querySelector('.theme-artwork-layer[data-active="false"]')).toHaveAttribute('aria-hidden', 'true');
  expect(document.querySelector('.theme-artwork-layer[data-active="false"]')).toHaveAttribute('inert');
  expect(screen.getAllByRole('region', { name: '画布创作' })).toHaveLength(1);
  expect(document.querySelector('.theme-artwork-layer[data-active="true"] .canvas-prism-backdrop')).toHaveAttribute('data-prism-theme', 'light');
  fireEvent.click(screen.getByText('设置名称', { selector: '.theme-artwork-layer[data-active="true"] summary' }));
  expect(screen.getByRole('textbox', { name: '新画布名称' })).toHaveValue('主题切换草稿');
  expect(screen.getByRole('heading', { level: 1, name: '画布创作' })).toBeVisible();
});
