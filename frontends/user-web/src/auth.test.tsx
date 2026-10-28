import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom';
import { AuthPage } from './pages';
import { ThemeProvider, ThemeToggle } from './theme';
import { configureGateway } from './api';
import { clearPendingInvitation, usePendingInvitation } from './promotion/pendingInvitation';

beforeEach(() => configureGateway({ baseUrl: 'http://localhost:8200' }));
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); clearPendingInvitation(); sessionStorage.clear(); localStorage.clear(); delete document.documentElement.dataset.theme; vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' }
});
function Landing() {
  usePendingInvitation();
  return <><h1>Authenticated destination</h1><Link to="/user/login">登录入口</Link><Link to="/user/register">注册入口</Link></>;
}
function open(path="/user/login") {
  const router = createMemoryRouter([
    { path: '/user/login', element: <AuthPage /> },
    { path: '/user/register', element: <AuthPage /> },
    { path: '/home', element: <Landing /> }
  ], { initialEntries: [path] });
  render(<ThemeProvider><RouterProvider router={router} /></ThemeProvider>);
  if (path.startsWith('/home')) return router;
  fireEvent.change(screen.getByLabelText('手机号'), { target: { value: '13800138000' } });
  fireEvent.change(screen.getByLabelText(/验证码/), { target: { value: '123456' } });
  return router;
}

it.each([false, true])('sends OTP through the normal SDK and CSRF flow (test mode: %s)', async testMode => {
  vi.stubEnv('MODE', 'development');
  vi.stubEnv('VITE_LOCAL_OTP_ENABLED', String(testMode));
  const network = vi.fn()
    .mockResolvedValueOnce(json({ authenticated: false, csrfToken: 'before-login' }))
    .mockResolvedValueOnce(json({ success: true, expiresInSeconds: 300, resendAfterSeconds: 60 }))
    .mockResolvedValueOnce(json({ success: true }))
    .mockResolvedValueOnce(json({ authenticated: true, csrfToken: 'after-login' }));
  vi.stubGlobal('fetch', network);
  open();
  fireEvent.click(screen.getByRole('button', { name: '获取验证码' }));
  expect(await screen.findByRole('status')).toHaveTextContent(testMode ? '测试验证码已就绪，无需短信' : '验证码已发送');
  fireEvent.click(screen.getByRole('button', { name: '登录/注册' }));
  expect(await screen.findByRole('heading', { name: 'Authenticated destination' })).toBeVisible();
  expect(network.mock.calls.map(call => call[0])).toEqual([
    'http://localhost:8200/api/v1/auth/session', 'http://localhost:8200/api/v1/auth/otp/send',
    'http://localhost:8200/api/v1/auth/otp/login', 'http://localhost:8200/api/v1/auth/session'
  ]);
  const request = network.mock.calls[2][1];
  expect(JSON.parse(request.body)).toEqual({ phone: '13800138000', code: '123456' });
  expect(request.credentials).toBe('include');
  expect(request.headers.get('X-CSRF-TOKEN')).toBe('before-login');
});

it('shows SMS unavailability and invalid OTP without pretending to log in', async () => {
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(json({ authenticated: false, csrfToken: 'csrf' }))
    .mockResolvedValueOnce(json({ code: 'SMS_PROVIDER_UNAVAILABLE' }, 503))
    .mockResolvedValueOnce(json({ code: 'OTP_INVALID_OR_EXPIRED' }, 401)));
  const router = open();
  fireEvent.click(screen.getByRole('button', { name: '获取验证码' }));
  expect(await screen.findByRole('status')).toHaveTextContent('短信服务暂时不可用');
  expect(screen.getByRole('button', { name: '获取验证码' })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: '登录/注册' }));
  expect(await screen.findByText('验证码无效或已过期')).toBeVisible();
  expect(router.state.location.pathname).toBe('/user/login');
  expect(document.documentElement).toHaveAttribute('data-theme', 'light');
  expect(localStorage.getItem('yomio-theme')).toBeNull();
});

it.each(['development', 'test'])('labels login and registration in explicitly enabled %s mode', mode => {
  vi.stubEnv('MODE', mode);
  vi.stubEnv('VITE_LOCAL_OTP_ENABLED', 'true');
  for (const path of ["/user/login", "/user/register"]) {
    render(<RouterProvider router={createMemoryRouter([{ path, element: <AuthPage /> }], { initialEntries: [path] })} />);
    expect(screen.getByText(/测试验证码模式/)).toBeVisible();
    cleanup();
  }
});

it.each([['production', 'true'], ['staging', 'true'], ['development', 'false'], ['development', '']])(
  'hides test-mode information in %s with switch %s', (mode, enabled) => {
    vi.stubEnv('MODE', mode);
    vi.stubEnv('VITE_LOCAL_OTP_ENABLED', enabled);
    open();
    expect(screen.queryByText(/测试验证码模式/)).not.toBeInTheDocument();
    expect(screen.queryByText(/123456/)).not.toBeInTheDocument();
  }
);

it.each([false,true])('binds invitations and prevents retry after account switch=%s',async(switched)=>{
 let logged=false,binds=0,currentOwner='invitee-a';const paths:string[]=[];const changed=vi.fn();window.addEventListener('xzwl-account-changed',changed);
 vi.stubGlobal('fetch',vi.fn(async(input,options)=>{
  const path=new URL(String(input)).pathname;paths.push(path);
  if(path.endsWith('/auth/session'))return json({authenticated:logged,user:logged?{id:currentOwner}:null,csrfToken:'csrf'});
  if(path.endsWith('/auth/otp/login')){logged=true;return json({success:true});}
  if(path.endsWith('/invitations/bind')){expect(JSON.parse(String(options?.body))).toEqual({code:'REAL-CODE'});binds++;if(binds===1)throw new Error('binding connection lost');return json({version:1});}
  throw new Error(path);
 }));
 open('/user/login?ref=REAL-CODE');expect(screen.queryByLabelText("推广码")).not.toBeInTheDocument();expect(screen.queryByRole('link',{name:'立即注册'})).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'登录/注册'}));await screen.findByRole('button',{name:'重试绑定邀请'});await screen.findByRole('status');expect(changed).toHaveBeenCalledTimes(1);window.removeEventListener('xzwl-account-changed',changed);
 if(switched)currentOwner='invitee-b';
 fireEvent.click(screen.getByRole('button',{name:'重试绑定邀请'}));
 if(switched){expect(await screen.findByRole('status')).toHaveTextContent('登录账户已变化');expect(binds).toBe(1);expect(screen.getByRole('button',{name:'登录/注册'})).toBeVisible();}
 else{await screen.findByRole('heading',{name:'Authenticated destination'});expect(binds).toBe(2);}
 expect(paths.filter(path=>path.endsWith('/auth/otp/login'))).toHaveLength(1);
});


it.each(['button', 'escape'])('closes the login dialog to home via %s and restores page scrolling', async method => {
  const previous = document.body.style.overflow;
  const router = open('/user/login?ref=REAL-CODE');
  const dialog = screen.getByRole('dialog', { name: '登录/注册' });
  expect(dialog).toHaveAttribute('open');
  expect(screen.queryByLabelText('推广码')).not.toBeInTheDocument();
  expect(document.body.style.overflow).toBe('hidden');
  if (method === 'button') fireEvent.click(screen.getByRole('button', { name: '关闭登录窗口' }));
  else fireEvent(dialog, new Event('cancel', { bubbles: true, cancelable: true }));
  await screen.findByRole('heading', { name: 'Authenticated destination' });
  expect(router.state.location.pathname).toBe('/home');
  expect(router.state.historyAction).toBe('REPLACE');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(document.body.style.overflow).toBe(previous);
});


it.each([['login', null], ['register', null], ['login', 'setItem'], ['login', 'removeItem']] as const)('retains hidden home referrals across navigation and remount, binds after %s and clears with %s failure', async (mode, failure) => {
  if (failure) vi.spyOn(Storage.prototype, failure).mockImplementation(() => { throw new DOMException('blocked', 'QuotaExceededError'); });
  let logged = false, bindings = 0;
  vi.stubGlobal('fetch', vi.fn(async (input, options) => {
    const path = new URL(String(input)).pathname;
    if (path.endsWith('/auth/session')) return json({ authenticated: logged, user: logged ? { id: 'new-owner' } : null, csrfToken: 'csrf' });
    if (path.endsWith('/auth/otp/login')) { logged = true; return json({ success: true }); }
    if (path.endsWith('/invitations/bind')) { expect(logged).toBe(true); expect(JSON.parse(String(options?.body))).toEqual({ code: 'HOME-AGENT' }); bindings++; return json({ version: 1 }); }
    throw new Error(path);
  }));
  const landing = open('/home?ref=HOME-AGENT');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(bindings).toBe(0);
  await landing.navigate('/home'); cleanup();
  const router = open('/home');
  fireEvent.click(screen.getByRole('link', { name: mode === 'login' ? '登录入口' : '注册入口' }));
  await screen.findByRole('dialog');
  expect(screen.queryByLabelText('推广码')).not.toBeInTheDocument(); expect(screen.queryByText('HOME-AGENT')).not.toBeInTheDocument();
  // The unified entry and closing the dialog must retain the hidden source.
  expect(screen.getByRole('button', { name: '登录/注册' })).toBeVisible();
  expect(screen.queryByText('立即注册')).not.toBeInTheDocument();
  expect(screen.queryByText('去登录')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '关闭登录窗口' })); await screen.findByRole('heading', { name: 'Authenticated destination' });
  fireEvent.click(screen.getByRole('link', { name: mode === 'login' ? '登录入口' : '注册入口' })); await screen.findByRole('dialog');
  fireEvent.change(screen.getByLabelText('手机号'), { target: { value: '13800138000' } });
  fireEvent.change(screen.getByLabelText(/验证码/), { target: { value: '123456' } });
  fireEvent.click(screen.getByRole('button', { name: '登录/注册' }));
  await screen.findByRole('heading', { name: 'Authenticated destination' }); expect(bindings).toBe(1);
  await router.navigate('/user/login'); await screen.findByRole('dialog');
  fireEvent.change(screen.getByLabelText('手机号'), { target: { value: '13800138000' } });
  fireEvent.change(screen.getByLabelText(/验证码/), { target: { value: '123456' } });
  fireEvent.click(screen.getByRole('button', { name: '登录/注册' }));
  await screen.findByRole('heading', { name: 'Authenticated destination' }); expect(bindings).toBe(1);
});

it('retains a hidden referral through navigation when the sessionStorage getter throws', async () => {
  const storage = vi.spyOn(window, 'sessionStorage', 'get').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
  try {
    let logged = false, bindings = 0;
    vi.stubGlobal('fetch', vi.fn(async (input, options) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/auth/session')) return json({ authenticated: logged, user: logged ? { id: 'owner' } : null, csrfToken: 'csrf' });
      if (path.endsWith('/auth/otp/login')) { logged = true; return json({ success: true }); }
      if (path.endsWith('/invitations/bind')) { expect(JSON.parse(String(options?.body))).toEqual({ code: 'MEMORY-AGENT' }); bindings++; return json({ version: 1 }); }
      throw new Error(path);
    }));
    const router = open('/home?ref=MEMORY-AGENT'); await router.navigate('/home');
    fireEvent.click(screen.getByRole('link', { name: '登录入口' })); await screen.findByRole('dialog');
    fireEvent.change(screen.getByLabelText('手机号'), { target: { value: '13800138000' } });
    fireEvent.change(screen.getByLabelText(/验证码/), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: '登录/注册' }));
    await screen.findByRole('heading', { name: 'Authenticated destination' }); expect(bindings).toBe(1);
  } finally { storage.mockRestore(); }
});

it.each(['login', 'register'])('uses only the phone in temporary %s mode and preserves invitation retry', async mode => {
  vi.stubEnv('MODE', 'production');
  vi.stubEnv('VITE_PHONE_ONLY_LOGIN_ENABLED', 'true');
  let logged = false, logins = 0, binds = 0;
  const network = vi.fn(async (input, options) => {
    const path = new URL(String(input)).pathname;
    if (path.endsWith('/auth/session')) return json({ authenticated: logged, user: logged ? { id: 'phone-owner' } : null, csrfToken: logged ? 'rotated' : 'anonymous' });
    if (path.endsWith('/auth/phone/login')) { expect(JSON.parse(String(options?.body))).toEqual({ phone: '13800138000' }); expect(options.headers.get('X-CSRF-TOKEN')).toBe('anonymous'); logged = true; logins++; return json({ success: true }); }
    if (path.endsWith('/invitations/bind')) { expect(logged).toBe(true); expect(JSON.parse(String(options?.body))).toEqual({ code: 'PHONE-REF' }); expect(options.headers.get('X-CSRF-TOKEN')).toBe('rotated'); if (++binds === 1) throw new Error('bind temporarily unavailable'); return json({ version: 1 }); }
    throw new Error(path);
  });
  vi.stubGlobal('fetch', network);
  const router = open('/home?ref=PHONE-REF');
  await router.navigate(`/user/${mode}`); await screen.findByRole('dialog');
  expect(screen.queryByLabelText(/验证码/)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '获取验证码' })).not.toBeInTheDocument();
  expect(screen.queryByText(/测试验证码模式/)).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('手机号'), { target: { value: '13800138000' } });
  fireEvent.click(screen.getByRole('button', { name: '登录/注册' }));
  fireEvent.click(await screen.findByRole('button', { name: '重试绑定邀请' }));
  await screen.findByRole('heading', { name: 'Authenticated destination' });
  expect(logins).toBe(1); expect(binds).toBe(2);
  expect(network.mock.calls.some(([input]) => String(input).includes('/auth/otp/'))).toBe(false);
});

it('shows phone-only login rejection without pretending to authenticate', async () => {
  vi.stubEnv('VITE_PHONE_ONLY_LOGIN_ENABLED', 'true');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ authenticated: false, csrfToken: 'csrf' }))
    .mockResolvedValueOnce(json({ code: 'PHONE_ONLY_LOGIN_DISABLED', message: 'PHONE_ONLY_LOGIN_DISABLED' }, 403)));
  const router = open('/home'); await router.navigate('/user/login'); await screen.findByRole('dialog');
  fireEvent.change(screen.getByLabelText('手机号'), { target: { value: '13800138000' } });
  fireEvent.click(screen.getByRole('button', { name: '登录/注册' }));
  expect(await screen.findByRole('status')).toHaveTextContent('手机号直接登录已关闭，请刷新页面使用验证码登录');
  expect(router.state.location.pathname).toBe('/user/login');
});


it.each(['/user/login', '/user/register'])('locks OTP resend for 60 seconds on %s', async path => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  const network = vi.fn()
    .mockResolvedValueOnce(json({ authenticated: false, csrfToken: 'csrf' }))
    .mockResolvedValueOnce(json({ success: true, expiresInSeconds: 300, resendAfterSeconds: 60 }))
    .mockResolvedValueOnce(json({ success: true, expiresInSeconds: 300, resendAfterSeconds: 60 }));
  vi.stubGlobal('fetch', network);
  open(path);
  const button = screen.getByRole('button', { name: '获取验证码' });
  await act(async () => { fireEvent.click(button); });
  expect(button).toHaveTextContent('60 秒后重试');
  expect(button).toBeDisabled();
  fireEvent.click(button);
  expect(network).toHaveBeenCalledTimes(2);
  await act(async () => { vi.advanceTimersByTime(1000); });
  expect(button).toHaveTextContent('59 秒后重试');
  await act(async () => { vi.advanceTimersByTime(58000); });
  expect(button).toHaveTextContent('1 秒后重试');
  expect(button).toBeDisabled();
  await act(async () => { vi.advanceTimersByTime(1000); });
  expect(button).toHaveTextContent('获取验证码');
  expect(button).toBeEnabled();
  await act(async () => { fireEvent.click(button); });
  expect(network).toHaveBeenCalledTimes(3);
  expect(button).toHaveTextContent('60 秒后重试');
  expect(button).toBeDisabled();
  // A throttled background tab must catch up to elapsed wall-clock time.
  vi.setSystemTime(Date.now() + 60000);
  await act(async () => { vi.advanceTimersByTime(1000); });
  expect(button).toHaveTextContent('获取验证码');
  expect(button).toBeEnabled();
});


it.each([
  [false, 'light'], [false, 'dark'], [true, 'light'], [true, 'dark']
] as const)('selects and remembers dark after login (phoneOnly=%s, previous=%s)', async (phoneOnly, previous) => {
  vi.stubEnv('VITE_PHONE_ONLY_LOGIN_ENABLED', String(phoneOnly));
  localStorage.setItem('yomio-theme', previous);
  vi.stubGlobal('fetch', vi.fn()
    .mockResolvedValueOnce(json({ authenticated: false, csrfToken: 'before-login' }))
    .mockResolvedValueOnce(json({ success: true }))
    .mockResolvedValueOnce(json({ authenticated: true, csrfToken: 'after-login' })));
  const router = open('/home');
  await act(async () => { await router.navigate(phoneOnly ? '/user/register' : '/user/login'); });
  fireEvent.change(screen.getByLabelText('手机号'), { target: { value: '13800138000' } });
  if (!phoneOnly) fireEvent.change(screen.getByLabelText(/验证码/), { target: { value: '123456' } });
  expect(document.documentElement).toHaveAttribute('data-theme', previous);
  fireEvent.click(screen.getByRole('button', { name: '登录/注册' }));
  await screen.findByRole('heading', { name: 'Authenticated destination' });
  expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
  expect(localStorage.getItem('yomio-theme')).toBe('dark');
  cleanup();
  render(<ThemeProvider><ThemeToggle /></ThemeProvider>);
  expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
  fireEvent.click(screen.getByRole('button', { name: '切换至浅色主题' }));
  expect(document.documentElement).toHaveAttribute('data-theme', 'light');
});
