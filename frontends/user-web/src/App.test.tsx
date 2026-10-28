import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as api from './api';
import { App } from './App';
import { UserFrame } from './UserFrame';

const options = vi.hoisted(() => vi.fn());
vi.mock('./api', async original => ({...await original<typeof import('./api')>(),
  promotionParticipationGetApiV1PromotionsParticipation: vi.fn(async()=>reply({eligible:true,accountType:'PERSONAL',invitationCode:'REAL-CODE',application:null,promoter:null,directInvites:{items:[],page:0,size:20,total:0}})),
  paymentOptionsGetApiV1PaymentsRechargeOptions: options, paymentGetGetApiV1PaymentsRechargesId: vi.fn(),
  iamOptionsGetApiV1AuthPortalOptions: vi.fn(), iamIssuePostApiV1AuthPortalTickets: vi.fn(),
  refreshSession: vi.fn(), userSelfGetApiV1UsersMe: vi.fn(), accessListGetApiV1AccessKeys: vi.fn(),
  storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl: vi.fn(),
  billingBalanceGetApiV1BillingWalletsKeyId: vi.fn(), accessSecretGetApiV1AccessKeysIdSecret: vi.fn(), logout: vi.fn(),
}));
const reply = <T,>(data: T) => ({ data, status: 200 as const, headers: new Headers() });
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.iamOptionsGetApiV1AuthPortalOptions).mockResolvedValue(reply({roles:[]}));
  vi.mocked(api.paymentGetGetApiV1PaymentsRechargesId).mockResolvedValue(reply({
    orderId: 'paid-order', userId: 'real-user', accountType: 'PERSONAL', status: 'PAID', fulfilled: true,
    amount4: '1000.0000', rateSnapshot4: '1.0000', expectedPoints4: '1000.0000', source: 'PROVIDER',
    checkoutUrl: null, paymentMethod: null, expiresAt: '2026-09-09T00:00:00Z', createdAt: '2026-09-08T23:00:00Z', remark: null, submittedBy: null, confirmedBy: null, rejectedAt: null, rejectedBy: null,
  }));
  options.mockResolvedValue(reply({ userId: "real-user", accountType: "PERSONAL", firstRecharge: false, pointsPerYuan4: "1.0000", rules: [] }));
  vi.mocked(api.refreshSession).mockResolvedValue({ authenticated: true, csrfToken: 'csrf', primary: true, roles: ['USER'], user: { id: 'real-user', username: '13800138000' } });
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'real-user', phone: '13800138000', nickname: '服务端昵称', enabled: true, authVersion: 1, avatarFileId: null }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ ownerId: 'real-user', keyId: 'real-key', enabled: true, kind: 'MEDIA', accountType: 'PERSONAL', firstRechargeOrderId: 'paid-order' , maskedKey: 'sk-boundRealPrefix****'}]));
  vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockResolvedValue(reply({ availablePoints4: '0.0000', walletId: 'real-key', version: 1 }));
  vi.mocked(api.logout).mockResolvedValue(reply({ success: true }));
});
afterEach(cleanup);
it('places the theme switch immediately after language and preserves selection on navigation', async () => {
  localStorage.removeItem('yomio-theme');
  const router = createMemoryRouter([
    { path: '/', element: <UserFrame><p>内容</p></UserFrame> },
    { path: '/home', element: <UserFrame><p>首页内容</p></UserFrame> },
  ]);
  render(<App router={router} fixture />);
  const toggle = screen.getByRole('button', { name: '切换至深色主题' });
  expect(screen.getByRole('button', { name: '语言切换' }).parentElement?.nextElementSibling).toBe(toggle);
  fireEvent.click(toggle);
  fireEvent.click(screen.getByRole('link', { name: '首页' }));
  await screen.findByText('首页内容');
  expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
  expect(screen.getByRole('button', { name: '切换至浅色主题' })).toBeVisible();
  localStorage.removeItem('yomio-theme');
});
it('returns to the top when navigating home and when clicking home again', async () => {
  const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  const router = createMemoryRouter([
    { path: '/create/image', element: <UserFrame><p>创作</p></UserFrame> },
    { path: '/home', element: <UserFrame><h1>首页内容</h1></UserFrame> },
  ], { initialEntries: ['/create/image'] });
  render(<App router={router} />);
  expect(scroll).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('link', { name: '首页' }));
  await screen.findByRole('heading', { name: '首页内容' });
  expect(scroll).toHaveBeenLastCalledWith({ top: 0, left: 0, behavior: 'instant' });
  scroll.mockClear();
  fireEvent.click(screen.getByRole('link', { name: '首页' }));
  await waitFor(() => expect(scroll).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'instant' }));
  scroll.mockRestore();
});
function mount() {
  const router = createMemoryRouter([{ path: '/', element: <UserFrame><p>内容</p></UserFrame> }, { path: '/user/login', element: <p>登录页面</p> }]);
  render(<App router={router} />);
  return router;
}
it('mounts the real provider in App, renders the real frame identity/balance and performs logout', async () => {
  const router = mount();
  await waitFor(() => expect(api.userSelfGetApiV1UsersMe).toHaveBeenCalled());
  await waitFor(() => expect(screen.getByLabelText('积分余额')).not.toHaveTextContent('—'));
  expect(screen.queryByText('real-key')).not.toBeInTheDocument();
  expect(screen.queryByText('当前密钥')).not.toBeInTheDocument();
  expect(screen.queryByText('sk-boundRealPrefix****')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '打开用户菜单' }));
  expect(screen.getByText('服务端昵称')).toBeVisible();
  expect(screen.queryByText(/real-user/)).not.toBeInTheDocument();
  expect(screen.getByText('个人账户')).toBeVisible();
  expect(screen.getByLabelText('积分余额')).toHaveTextContent(/^0$/);
  expect(screen.queryByText(/Nova|1388|1,280/)).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: '邀请有礼' })).toBeVisible();
  expect(screen.queryByRole('link', { name: '申请成为代理' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '推广登录' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '退出登录' }));
  await waitFor(() => expect(router.state.location.pathname).toBe('/user/login'));
  expect(api.logout).toHaveBeenCalledTimes(1);
});
it('shows anonymous state with no invented key and no protected request', async () => {
  vi.mocked(api.refreshSession).mockResolvedValue({ authenticated: false, csrfToken: 'csrf', primary: false, roles: [], user: null });
  mount();
  expect(await screen.findByRole('link', { name: '登录/注册' })).toBeVisible();
  expect(api.userSelfGetApiV1UsersMe).not.toHaveBeenCalled();
  expect(screen.queryByText(/Nova|1388|1,280/)).not.toBeInTheDocument();
  expect(within(screen.getByRole('complementary', { name: '用户端导航' })).queryByRole('link', { name: '个人中心' })).not.toBeInTheDocument();
});

it('retains fixtures only when App explicitly opts in', async () => {
  const router = createMemoryRouter([{ path: '/', element: <UserFrame><p>fixture</p></UserFrame> }]);
  render(<App fixture router={router} />);
  fireEvent.click(screen.getByRole('button', { name: '打开用户菜单' }));
  expect(screen.getByText('Nova')).toBeVisible();
  expect(api.refreshSession).not.toHaveBeenCalled();
});

it('shows one combined authentication entry in the unauthenticated homepage header without opening a modal', async () => {
  vi.mocked(api.refreshSession).mockResolvedValue({ authenticated: false, csrfToken: 'csrf', primary: false, roles: [], user: null });
  const router = createMemoryRouter([{ path: '/home', element: <UserFrame><h1>公开首页</h1></UserFrame> }], { initialEntries: ['/home?ref=agent-code'] });
  render(<App router={router} />);
  expect(await screen.findByRole('link', { name: '登录/注册' })).toHaveAttribute('href', '/user/login');
  expect(screen.queryByRole('link', { name: /^(注册|登录)$/ })).not.toBeInTheDocument();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '打开用户菜单' })).not.toBeInTheDocument();
  expect(router.state.location.pathname).toBe('/home');
});

it('groups the real points and recharge after language and invitations, with portal entries inside the account dropdown',async()=>{
 vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockResolvedValue(reply({availablePoints4:'2303.4500',walletId:'real-key',version:1}));
 vi.mocked(api.iamOptionsGetApiV1AuthPortalOptions).mockResolvedValue(reply({roles:['API_USER','PROMOTER']}));
 mount();await waitFor(() => expect(screen.getByLabelText('积分余额')).toHaveTextContent('2303.45'));
 const header=screen.getByRole('banner');
 const language=within(header).getByRole('button',{name:'语言切换'});
 const gift=within(header).getByRole('link',{name:'邀请'});
 const balance=within(header).getByLabelText('积分余额');
 const recharge=within(header).getByRole('link',{name:'充值'});
 expect(balance).toHaveTextContent('2303.45');
 expect(balance.parentElement).toBe(recharge.parentElement);
 expect(language.compareDocumentPosition(gift)&Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
 expect(gift.compareDocumentPosition(balance)&Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
 expect(gift).toHaveAttribute('href','/account/referrals');
 expect(screen.queryByRole('button',{name:'API 登录'})).not.toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'推广登录'})).not.toBeInTheDocument();
 fireEvent.click(within(header).getByRole('button',{name:'打开用户菜单'}));
 expect(await screen.findByRole('button',{name:'推广登录'})).toBeEnabled();
 expect(screen.getByRole('button',{name:'API 登录'})).toBeEnabled();
 fireEvent.keyDown(document,{key:'Escape'});
 expect(screen.queryByRole('button',{name:'API 登录'})).not.toBeInTheDocument();
});
it('opens the four-language menu and dismisses it outside the control',async()=>{
 mount();const toggle=screen.getByRole('button',{name:'语言切换'});
 fireEvent.click(toggle);expect(screen.getByRole('button',{name:'简体中文'})).toHaveAttribute('aria-pressed','true');
 for (const name of ['English', '한국어', '日本語']) expect(screen.getByRole('button', { name })).toBeVisible();
 fireEvent.pointerDown(screen.getByLabelText('积分余额'));
 expect(screen.queryByRole('button',{name:'简体中文'})).not.toBeInTheDocument();
});

it('shows the invitation tooltip on hover or focus and keeps the invitation destination',()=>{
 const router=mount();const gift=screen.getByRole('link',{name:'邀请'});
 expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
 fireEvent.mouseEnter(gift);
 expect(screen.getByRole('tooltip')).toHaveTextContent('邀请好友，获取积分');
 expect(gift).toHaveAttribute('aria-describedby',screen.getByRole('tooltip').id);
 fireEvent.mouseLeave(gift);expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
 fireEvent.focus(gift);expect(screen.getByRole('tooltip')).toBeVisible();
 fireEvent.keyDown(gift,{key:'Escape'});expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
 fireEvent.blur(gift);fireEvent.focus(gift);expect(screen.getByRole('tooltip')).toBeVisible();
 fireEvent.blur(gift);expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
 fireEvent.click(gift);expect(router.state.location.pathname).toBe('/account/referrals');
});


it.each(['/create/image', '/create/video'])('returns to the first record on entering %s history', async path => {
  const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  try {
    const router = createMemoryRouter([{ path, element: <UserFrame><p>创作与历史</p></UserFrame> }], { initialEntries: [path] });
    render(<App router={router} />);
    expect(scroll).not.toHaveBeenCalled();
    await act(() => router.navigate(`${path}?tab=history`));
    expect(scroll).toHaveBeenLastCalledWith({ top: 0, left: 0, behavior: 'instant' });
    scroll.mockClear();
    await act(() => router.navigate(`${path}?tab=history`));
    expect(scroll).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'instant' });
    scroll.mockClear();
    await act(() => router.navigate(path));
    expect(scroll).not.toHaveBeenCalled();
  } finally { scroll.mockRestore(); }
});


it('does not flash the nickname initial while an existing avatar URL is loading', async () => {
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'real-user', phone: '13800138000', nickname: '用户7111', enabled: true, authVersion: 1, avatarFileId: 'avatar-owned' }));
  let finishAvatar!: () => void;
  vi.mocked(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).mockReturnValue(new Promise(resolve => {
    finishAvatar = () => resolve(reply({ url: 'https://example.com/avatar.png', expiresAt: '2027-01-01T00:00:00Z' }));
  }));
  let finishSession!: () => void;
  vi.mocked(api.refreshSession).mockReturnValueOnce(new Promise(resolve => {
    finishSession = () => resolve({ authenticated: true, csrfToken: 'csrf', primary: true, roles: ['USER'], user: { id: 'real-user', username: '13800138000' } });
  }));
  mount();
  expect(screen.queryByText('账户')).not.toBeInTheDocument();
  expect(within(screen.getByRole('banner')).getByRole('status', { name: '正在读取账户' })).toBeEmptyDOMElement();
  await act(async () => finishSession());
  const button = await screen.findByRole('button', { name: '打开用户菜单' });
  expect(button).toBeEmptyDOMElement();
  await waitFor(() => expect(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).toHaveBeenCalledTimes(1));
  await act(async () => finishAvatar());
  expect(button.querySelector('img')).toHaveAttribute('src', 'https://example.com/avatar.png');
  expect(button).not.toHaveTextContent('用');
});

it.each(['/home', '/account/recharge', '/create/image', '/create/video', '/account/transactions', '/commerce'])('shows the shared avatar on %s and links to the profile', async path => {
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'real-user', phone: '13800138000', nickname: '用户7111', enabled: true, authVersion: 1, avatarFileId: 'avatar-owned' }));
  vi.mocked(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).mockResolvedValue(reply({ url: 'https://example.com/avatar.png', expiresAt: '2027-01-01T00:00:00Z' }));
  const router = createMemoryRouter([
    { path, element: <UserFrame><h1>页面内容</h1></UserFrame> },
    { path: '/account/profile', element: <UserFrame><h1>个人中心内容</h1></UserFrame> },
  ], { initialEntries: [path] });
  render(<App router={router} />);
  expect(document.querySelector('.home-sidebar-account')).not.toBeInTheDocument();
  const sidebar = screen.queryByRole('complementary', { name: '用户端导航' });
  if (sidebar) expect(within(sidebar).queryByRole('link', { name: '个人中心' })).not.toBeInTheDocument();
  const menu = await screen.findByRole('button', { name: '打开用户菜单' });
  await waitFor(() => expect(menu.querySelector('img')).toHaveAttribute('src', 'https://example.com/avatar.png'));
  fireEvent.click(menu);
  const profileLink = screen.getByRole('link', { name: '个人中心' });
  expect(screen.getByRole('button', { name: '打开用户菜单' }).querySelector('img')).toHaveAttribute('src', 'https://example.com/avatar.png');
  expect(api.storageAvatarAccessUrlGetApiV1FilesAvatarsFileIdAccessUrl).toHaveBeenCalledTimes(1);
  fireEvent.click(profileLink);
  expect(await screen.findByRole('heading', { name: '个人中心内容' })).toBeVisible();
  expect(document.querySelector('.home-sidebar-account')).not.toBeInTheDocument();
});

it('switches all four languages, keeps route state and remembers the choice', async () => {
 localStorage.removeItem('yomio-language');
 const router = mount();
 const cases = [
  ['English', 'Language', 'Home', 'en'],
  ['한국어', '언어 선택', '홈', 'ko'],
  ['日本語', '言語を選択', 'ホーム', 'ja'],
  ['简体中文', '语言切换', '首页', 'zh-CN'],
 ];
 let toggleLabel = '语言切换';
 for (const [name, nextLabel, home, locale] of cases) {
  fireEvent.click(screen.getByRole('button', { name: toggleLabel }));
  fireEvent.click(screen.getByRole('button', { name }));
  expect(screen.getByRole('link', { name: home })).toBeVisible();
  expect(document.documentElement.lang).toBe(locale);
  expect(localStorage.getItem('yomio-language')).toBe(locale);
  expect(router.state.location.pathname).toBe('/');
  toggleLabel = nextLabel;
 }
 localStorage.removeItem('yomio-language');
});
