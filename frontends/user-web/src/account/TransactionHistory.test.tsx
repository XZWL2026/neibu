import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi, type Mock } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { configureGateway, getSession } from '../api';
import { TransactionHistory } from './TransactionHistory';

const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { 'Content-Type': 'application/json' }
});
const entry = (requestId: string, kind = 'CONSUME', points4 = '-2.5000', day = 1) => ({
  requestId, kind, points4, amount4: null as string | null, balanceAfter4: '100.0000', recordedAt: `2026-09-${String(day).padStart(2, '0')}T12:00:00Z`
});
let rows: ReturnType<typeof entry>[];
let authenticated: boolean;
let network: Mock<(input: string) => Promise<Response>>;
let failLedger: number;
const unauthorized = vi.fn();
beforeEach(() => {
  configureGateway({ onUnauthorized: unauthorized });
  unauthorized.mockClear();
  rows = [entry('image-1'), entry('image-1-refund', 'REFUND', '2.5000', 2),
    entry('order-1', 'RECHARGE', '123.4560', 3), entry('order-1', 'CASH_REFUND', '-123.4560', 4)];
  authenticated = true;
  failLedger = 0;
  network = vi.fn(async (input: string) => {
    const url = new URL(input, 'https://example.test');
    if (url.pathname.endsWith('/auth/session')) return json({ authenticated, csrfToken: 'csrf', user: authenticated ? { id: 'owner-1' } : null });
    if (url.pathname.endsWith('/access/keys')) return json([
      { keyId: 'my-media', ownerId: 'owner-1', kind: 'MEDIA', enabled: true },
      { keyId: 'someone-else', ownerId: 'owner-2', kind: 'MEDIA', enabled: true },
      { keyId: 'disabled', ownerId: 'owner-1', kind: 'MEDIA', enabled: false }
    ]);
    if (url.pathname.endsWith('/my-media')) return json({availablePoints4:'7797.5123',version:1,walletId:'my-media'});
    if (url.pathname.endsWith('/my-media/ledger')) return json(failLedger ? {} : rows, failLedger || 200);
    if (url.pathname.includes('/media/tasks/')) return json({ id: url.pathname.split('/').at(-1), type: 'IMAGE', status: 'SUCCEEDED', modelName: `模型 ${url.pathname.split('/').at(-1)}`, resolution: '2K' });
    if (url.pathname.endsWith('/payments/recharges/order-1')) return json({ orderId: 'order-1', amount4: '12.3456', status: 'REFUND_PENDING', fulfilled: true });
    throw new Error(`Unexpected request: ${input}`);
  });
  vi.stubGlobal('fetch', network);
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
function open(overview = false) {
  const router = createMemoryRouter([{ path: '/account/transactions', element: <TransactionHistory overview={overview} /> }], {
    initialEntries: ['/account/transactions']
  });
  render(<RouterProvider router={router} />);
  return router;
}

it('uses the real session and owned keys, defaults to consumption, and keeps point returns out of recharge', async () => {
  const router = open();
  expect(screen.getByRole('status')).toHaveTextContent('正在加载');
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
  const table = await screen.findByRole('table');
  expect(screen.getByRole('button', { name: '消耗历史' })).toHaveAttribute('aria-pressed', 'true');
  expect(within(table).getAllByRole('row')[1]).toHaveTextContent('积分退回');
  expect(within(table).getByText('积分退回')).toBeVisible();
  expect(within(table).getByText('生图')).toBeVisible();
  expect(within(table).getByText('+2.5')).toBeVisible();
  expect(within(table).queryByText('order-1')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '充值历史' }));
  expect(await screen.findByRole('columnheader', { name: '支付金额（元）' })).toBeVisible();
  expect(screen.getByRole('columnheader', { name: '到账积分（积分）' })).toBeVisible();
  expect(screen.getByText('原路退款处理中')).toBeVisible();
  expect(screen.getByText('12.3456')).toBeVisible();
  expect(screen.getByText('-123.456')).toBeVisible();
  expect(screen.queryByText('image-1-refund')).not.toBeInTheDocument();
  expect(router.state.location.pathname).toBe('/account/transactions');
  expect(network.mock.calls.every(([url]) => !String(url).includes('someone-else') && !String(url).includes('/disabled/'))).toBe(true);
  expect(network.mock.calls.some(([url]) => String(url).endsWith('/my-media/ledger?limit=100'))).toBe(true);
});

it('sorts before paginating each category and resets to page one when switching', async () => {
  rows = Array.from({ length: 12 }, (_, i) => entry(`task-${i + 1}`, 'CONSUME', '-1.0000', i + 1));
  rows.push(entry('order-1', 'RECHARGE', '123.4560', 20));
  open();
  const table = await screen.findByRole('table');
  expect(within(table).getAllByRole('row')[1]).toHaveTextContent('模型 task-12');
  expect(within(table).getAllByRole('row')).toHaveLength(11);
  fireEvent.click(screen.getByRole('button', { name: '下一页' }));
  expect(await screen.findByText('模型 task-1')).toBeVisible();
  expect(screen.queryByText('模型 task-12')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '充值历史' }));
  expect(await screen.findByText('order-1')).toBeVisible();
  expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '消耗历史' }));
  expect(await screen.findByText('模型 task-12')).toBeVisible();
  expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled();
});

it('does not show fixtures or request private data without a real login', async () => {
  authenticated = false;
  open();
  expect(await screen.findByRole('link', { name: '登录后查看' })).toHaveAttribute('href', '/user/login');
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
  expect(network).toHaveBeenCalledTimes(1);
});

it('shows empty records and request failure with retry, without stale rows', async () => {
  failLedger = 503;
  open();
  expect(await screen.findByRole('alert')).toHaveTextContent('记录加载失败');
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
  failLedger = 0;
  rows = [];
  fireEvent.click(screen.getByRole('button', { name: '重新加载' }));
  expect(await screen.findByText('暂无消耗记录')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '充值历史' }));
  expect(await screen.findByText('暂无充值记录')).toBeVisible();
});

it('clears the SDK session on 401 and distinguishes permission denial', async () => {
  failLedger = 401;
  const first = open();
  expect(await screen.findByRole('link', { name: '登录后查看' })).toBeVisible();
  expect(getSession()).toBeNull();
  expect(unauthorized).toHaveBeenCalledOnce();
  cleanup(); first.dispose();
  configureGateway({ onUnauthorized: unauthorized });
  failLedger = 403;
  open();
  expect(await screen.findByRole('alert')).toHaveTextContent('无权查看');
  expect(screen.queryByRole('link', { name: '登录后查看' })).not.toBeInTheDocument();
  expect(getSession()?.authenticated).toBe(true);
});

it('ignores a late response from the previously selected category', async () => {
  let resolveLedger!: (value: Response) => void;
  const normal = network.getMockImplementation()!;
  network.mockImplementationOnce(normal).mockImplementationOnce(normal).mockImplementationOnce(() => new Promise<Response>(resolve => { resolveLedger = resolve; }));
  open();
  await waitFor(() => expect(resolveLedger).toBeDefined());
  fireEvent.click(screen.getByRole('button', { name: '充值历史' }));
  expect(await screen.findByText('原路退款处理中')).toBeVisible();
  resolveLedger(json([entry('late-consumption')]));
  await waitFor(() => expect(screen.queryByText('late-consumption')).not.toBeInTheDocument());
  expect(screen.getByRole('button', { name: '充值历史' })).toHaveAttribute('aria-pressed', 'true');
});

it('uses the media task type and treats a missing old task as generic consumption', async () => {
  rows = [entry('video-1'), entry('old-operation', 'CONSUME', '-1.2500', 2)];
  const normal = network.getMockImplementation()!;
  network.mockImplementation(async (url: string) => {
    if (url.endsWith('/media/tasks/video-1')) return json({ id: 'video-1', type: 'VIDEO' });
    if (url.endsWith('/media/tasks/old-operation')) return json({}, 404);
    return normal(url);
  });
  open();
  expect(await screen.findByText('生视频')).toBeVisible();
  expect(screen.getByText('积分消耗')).toBeVisible();
  expect(screen.getByText('-1.25')).toBeVisible();
});

it('only marks cash as refunded after the real order confirms it, preserving decimal amounts', async () => {
  const normal = network.getMockImplementation()!;
  network.mockImplementation(async (url: string) => url.endsWith('/payments/recharges/order-1')
    ? json({ orderId: 'order-1', amount4: '12.3456', status: 'REFUNDED', fulfilled: true }) : normal(url));
  open();
  fireEvent.click(screen.getByRole('button', { name: '充值历史' }));
  expect(await screen.findByText('原路退款成功')).toBeVisible();
  expect(screen.getByText('-12.3456')).toBeVisible();
  expect(screen.getByText('12.3456')).toBeVisible();
  expect(screen.queryByText('积分退回')).not.toBeInTheDocument();
});

it('does not replace a failed recharge detail request with invented payment data', async () => {
  const normal = network.getMockImplementation()!;
  network.mockImplementation(async (url: string) => url.endsWith('/payments/recharges/order-1') ? json({}, 503) : normal(url));
  open();
  fireEvent.click(screen.getByRole('button', { name: '充值历史' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('记录加载失败');
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
});

it('shows server balances and generation parameters with signed point colors, hiding ledger IDs', async () => {
  rows = [{ ...entry('video-1', 'CONSUME', '-2.5000', 1), balanceAfter4: '97.1234' },
    { ...entry('video-1-refund', 'REFUND', '2.5000', 2), balanceAfter4: '99.6234' }];
  const normal = network.getMockImplementation()!;
  network.mockImplementation(async (url: string) => url.endsWith('/media/tasks/video-1')
    ? json({ id: 'video-1', type: 'VIDEO', model: 'seedance', modelName: 'Seedance 2.5', resolution: '720p', durationSeconds: 5 }) : normal(url));
  open();
  const table = await screen.findByRole('table');
  expect(within(table).queryByRole('columnheader', { name: '流水号' })).not.toBeInTheDocument();
  expect(within(table).queryByText('video-1')).not.toBeInTheDocument();
  expect(within(table).getByRole('columnheader', { name: '变动后余额（积分）' })).toBeVisible();
  expect(within(table).getByText('97.1234')).toBeVisible();
  expect(within(table).getByText('99.6234')).toBeVisible();
  expect(within(table).getAllByText('Seedance 2.5')).toHaveLength(2);
  expect(within(table).getAllByText('720p · 5秒')).toHaveLength(2);
  expect(within(table).getByText('-2.5')).toHaveClass('history-debit');
  expect(within(table).getByText('+2.5')).toHaveClass('history-credit');
});

it('combines only supported owned ledger kinds in the checkout all-records view', async () => {
  rows.push(entry('unknown', 'UNKNOWN', '9.0000', 5));
  const router = createMemoryRouter([{ path: '/', element: <TransactionHistory initialCategory="all" hideCategories/> }], { initialEntries: ['/'] });
  render(<RouterProvider router={router}/>);
  const table = await screen.findByRole('table', { name: '全部记录' });
  expect(within(table).getAllByRole('row')).toHaveLength(5);
  expect(within(table).getByText('充值')).toBeVisible();
  expect(within(table).getByText('生图')).toBeVisible();
  expect(within(table).getByText('积分退回')).toBeVisible();
  expect(within(table).getByText('现金退款')).toBeVisible();
  expect(within(table).getByText('12.3456')).toBeVisible();
  expect(within(table).queryByText('unknown')).not.toBeInTheDocument();
  expect(screen.queryByRole('group', { name: '记录分类' })).not.toBeInTheDocument();
});

it('shows exact account totals and filters dates without changing monthly totals', async () => {
  const now = new Date();
  const date = (offset: number) => new Date(now.getFullYear(), now.getMonth() + offset, 2, 12).toISOString();
  rows = [{...entry('current'), points4:'-0.1234', recordedAt:date(0)},
    {...entry('current-two'), points4:'-0.0001', recordedAt:date(0)},
    {...entry('prior'), points4:'-20.0000', recordedAt:date(-1)},
    {...entry('order-1','RECHARGE','1000.1234'), recordedAt:date(0)}];
  open(true);
  await screen.findByRole('table');
  expect(screen.getByLabelText('当前积分')).toHaveTextContent('7,797.5123');
  expect(screen.getByLabelText('本月消耗')).toHaveTextContent('0.1235');
  expect(screen.getByLabelText('本月充值')).toHaveTextContent('1,000.1234');
  fireEvent.change(screen.getByLabelText('时间范围'), {target:{value:'month'}});
  await waitFor(()=>expect(screen.queryByText('模型 prior')).not.toBeInTheDocument());
  expect(await screen.findByText('模型 current')).toBeVisible();
  expect(screen.getByLabelText('本月消耗')).toHaveTextContent('0.1235');
});

it('does not present a truncated current-month ledger as a complete monthly total', async () => {
  rows = Array.from({length:100},(_,i)=>({...entry(`task-${i}`),recordedAt:new Date().toISOString()}));
  open(true);
  await screen.findByRole('table');
  expect(screen.getByLabelText('本月消耗')).toHaveTextContent('—');
  expect(screen.getByLabelText('本月充值')).toHaveTextContent('—');
});

it('shows the historical consumption yuan amount in both consumption and all records without deriving it from points', async () => {
  rows = [{...entry('video-priced', 'CONSUME', '-37.4919'), amount4:'-3.7035'}, entry('legacy-no-amount')];
  open();
  let table = await screen.findByRole('table');
  expect(within(table).getByRole('columnheader', {name:'金额（元）'})).toBeVisible();
  expect(within(table).getByText('-3.7035')).toBeVisible();
  expect(within(table).getByText('-37.4919')).toBeVisible();
  expect(within(table).getByText('模型 video-priced')).toBeVisible();
  fireEvent.click(screen.getByRole('button',{name:'全部记录'}));
  table = await screen.findByRole('table', {name:'全部记录'});
  expect(within(table).getByText('-3.7035')).toBeVisible();
  expect(within(table).getByRole('row',{name:/-2.5/})).toHaveTextContent('—');
});
