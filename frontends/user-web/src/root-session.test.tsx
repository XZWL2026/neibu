import '@testing-library/jest-dom/vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter } from 'react-router-dom';
import { App } from './App';
import { userRoutes } from './router';
import * as api from './api';

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(),
  refreshSession: vi.fn(),
  userSelfGetApiV1UsersMe: vi.fn(),
  paymentOptionsGetApiV1PaymentsRechargeOptions: vi.fn(),
  accessListGetApiV1AccessKeys: vi.fn(),
}));
// Keep the real router and account provider; presentation is irrelevant to entry decisions.
vi.mock('./home/YomioHome', () => ({ YomioHome: () => <h1>首页</h1> }));
vi.mock('./auth/LoginSpace', () => ({ LoginSpace: () => null }));

const identity = { authenticated: true, csrfToken: 'test', primary: true, roles: ['USER'], user: { id: 'owner', username: 'test' } };
const anonymous = { ...identity, authenticated: false, user: null };
const reply = <T,>(data: T) => ({ data, status: 200 as const, headers: new Headers() });

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.refreshSession).mockResolvedValue(identity);
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'owner', phone: '13800138000', nickname: 'test', enabled: true, authVersion: 1, avatarFileId: null }));
  vi.mocked(api.paymentOptionsGetApiV1PaymentsRechargeOptions).mockResolvedValue(reply({ userId: 'owner', accountType: null, firstRecharge: true, pointsPerYuan4: '1.0000', rules: [] }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([]));
});
afterEach(cleanup);

function openRoot(basename = '/') {
  const router = createMemoryRouter(userRoutes, { basename, initialEntries: [basename] });
  render(<App router={router} />);
  return router;
}

it.each(['/', '/portal/'])('waits for a valid session without flashing login, including repeat entry under %s', async basename => {
  let finish!: (value: typeof identity) => void;
  vi.mocked(api.refreshSession).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  const router = openRoot(basename);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(router.state.location.pathname).toBe(basename);
  await act(async () => finish(identity));
  await waitFor(() => expect(router.state.location.pathname).toBe(`${basename}home`));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(router.state.historyAction).toBe('REPLACE');
  await act(async () => router.navigate('/'));
  await waitFor(() => expect(router.state.location.pathname).toBe(`${basename}home`));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

it('opens login only after the server confirms an anonymous session', async () => {
  let finish!: (value: typeof anonymous) => void;
  vi.mocked(api.refreshSession).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  const router = openRoot();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  await act(async () => finish(anonymous));
  expect(await screen.findByRole('dialog', { name: '登录/注册' })).toBeVisible();
  expect(router.state.location.pathname).toBe('/user/login');
  expect(api.userSelfGetApiV1UsersMe).not.toHaveBeenCalled();
});

it('opens login when a returning user session has expired', async () => {
  const router = openRoot();
  await waitFor(() => expect(router.state.location.pathname).toBe('/home'));
  vi.mocked(api.refreshSession).mockResolvedValue(anonymous);
  await act(async () => { window.dispatchEvent(new Event('focus')); });
  await act(async () => router.navigate('/'));
  expect(await screen.findByRole('dialog', { name: '登录/注册' })).toBeVisible();
});

it('does not treat a network failure as an expired session and recovers on focus', async () => {
  vi.mocked(api.refreshSession).mockRejectedValueOnce(new Error('offline'));
  const router = openRoot();
  await act(async () => {});
  expect(screen.getByRole('heading', { name: '首页' })).toBeVisible();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  await act(async () => { window.dispatchEvent(new Event('focus')); });
  await waitFor(() => expect(router.state.location.pathname).toBe('/home'));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
