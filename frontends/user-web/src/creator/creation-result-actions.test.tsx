import { saveComposerDraft } from './composer-draft';
import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter } from 'react-router-dom';
import { App } from '../App';
import { userRoutes } from '../router';
import * as api from '../api';

vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  refreshSession: vi.fn(), userSelfGetApiV1UsersMe: vi.fn(), paymentOptionsGetApiV1PaymentsRechargeOptions: vi.fn(),
  paymentGetGetApiV1PaymentsRechargesId: vi.fn(), billingBalanceGetApiV1BillingWalletsKeyId: vi.fn(), accessListGetApiV1AccessKeys: vi.fn(),
  mediaOptionsGetApiV1MediaGenerationOptions: vi.fn(), mediaQuotePostApiV1MediaQuotes: vi.fn(), mediaListGetApiV1MediaTasks: vi.fn(),
  mediaGetGetApiV1MediaTasksTaskId: vi.fn(), mediaSubmitPostApiV1MediaTasks: vi.fn(), mediaPortraitListGetApiV1MediaPortraits: vi.fn(),
  mediaRegeneratePostApiV1MediaTasksTaskIdRegenerations: vi.fn(), mediaDeleteHistoryDeleteApiV1MediaTasksTaskId: vi.fn(),
  storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl: vi.fn(),
}));

const reply = (data: unknown, status = 200) => ({ data, status, headers: new Headers() }) as never;
const quote = vi.mocked(api.mediaQuotePostApiV1MediaQuotes);
const regenerate = vi.mocked(api.mediaRegeneratePostApiV1MediaTasksTaskIdRegenerations);
const remove = vi.mocked(api.mediaDeleteHistoryDeleteApiV1MediaTasksTaskId);
const submit = vi.mocked(api.mediaSubmitPostApiV1MediaTasks);
const kinds = [['IMAGE', '图片'], ['VIDEO', '视频']] as const;

function fixture(type: 'IMAGE' | 'VIDEO') {
  const settings = { type, model: 'saved-model', resolution: '1080p', aspectRatio: '1:1', count: 1, durationSeconds: type === 'VIDEO' ? 8 : 0 };
  const snapshot = { ...settings, id: 'original-task', status: 'SUCCEEDED', prompt: '原任务提示词',
    references: [{ fileId: 'original-reference', kind: 'IMAGE', durationSeconds: null }], results: [{ fileId: 'old-output', index: 0 }] };
  const request = { requestRef: 'original-key', settings, prompt: snapshot.prompt, inputAssetRefs: ['original-reference'] };
  const draft = { prompt: snapshot.prompt, materials: [{ fileId: 'original-reference', name: '参考图片', preview: '', kind: 'IMAGE', reviewStatus: 'ACTIVE' }], mode: type === 'VIDEO' ? 'frames' : 'reference', bindings: [] };
  sessionStorage.setItem(`xzwl-${type.toLowerCase()}-submission:owner`, JSON.stringify({ key: request.requestRef, taskRef: snapshot.id, request, snapshot, draft }));
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([snapshot]));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockImplementation(async id => reply(id === snapshot.id ? snapshot
    : { ...snapshot, id, results: [{ fileId: 'new-output', index: 0 }] }));
  return { settings, snapshot, storageKey: `xzwl-${type.toLowerCase()}-submission:owner` };
}
const mount = (type: 'IMAGE' | 'VIDEO') => render(<App router={createMemoryRouter(userRoutes, { initialEntries: [`/create/${type.toLowerCase()}`] })} />);
const output = (card: HTMLElement) => card.querySelector('img, video');
async function originalCard(noun: string) {
  const card = await screen.findByRole('article', { name: `${noun}任务：原任务提示词` });
  await waitFor(() => expect(output(card)).toHaveAttribute('src', 'https://assets.example/old-output'));
  await waitFor(() => expect(within(card).getByRole('button', { name: '重新生成' })).toBeEnabled());
  return card;
}

beforeEach(() => {
  vi.resetAllMocks(); sessionStorage.clear(); localStorage.clear();
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  vi.spyOn(window, 'confirm').mockReturnValue(true);
  vi.mocked(api.refreshSession).mockResolvedValue({ authenticated: true, user: { id: 'owner' } } as never);
  vi.mocked(api.userSelfGetApiV1UsersMe).mockResolvedValue(reply({ id: 'owner', enabled: true }));
  vi.mocked(api.paymentOptionsGetApiV1PaymentsRechargeOptions).mockResolvedValue(reply({ userId: 'owner', accountType: 'PERSONAL' }));
  vi.mocked(api.paymentGetGetApiV1PaymentsRechargesId).mockResolvedValue(reply({ userId: 'owner', orderId: 'first-order', accountType: 'PERSONAL', status: 'PAID', fulfilled: true }));
  vi.mocked(api.accessListGetApiV1AccessKeys).mockResolvedValue(reply([{ keyId: 'owner-key', ownerId: 'owner', firstRechargeOrderId: 'first-order', enabled: true, kind: 'MEDIA', accountType: 'PERSONAL' }]));
  vi.mocked(api.billingBalanceGetApiV1BillingWalletsKeyId).mockResolvedValue(reply({ walletId: 'owner-key', availablePoints4: '1000.0000' }));
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({ models: kinds.map(([type]) => ({
    type, model: 'current-model', displayName: '当前模型', resolutions: ['720p'], aspectRatios: ['16:9'], maxCount: 1,
    minDurationSeconds: type === 'VIDEO' ? 4 : 0, maxDurationSeconds: type === 'VIDEO' ? 12 : 0,
    durationStepSeconds: type === 'VIDEO' ? 1 : 0, maxReferenceImages: 2, referenceFollowsFirstFrame: false,
  })) }));
  vi.mocked(api.mediaPortraitListGetApiV1MediaPortraits).mockResolvedValue(reply([]));
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockImplementation(async id => reply({ url: `https://assets.example/${id}` }));
  quote.mockResolvedValue(reply({ points: '12.3456' }));
  regenerate.mockResolvedValue(reply({ id: 'replacement-task' }, 202));
  remove.mockResolvedValue(reply(undefined, 204));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it.each(kinds)('%s first submission reaches the API on public HTTP without randomUUID', async (type, noun) => {
  const browserCrypto = globalThis.crypto;
  vi.stubGlobal('crypto', { getRandomValues: browserCrypto.getRandomValues.bind(browserCrypto) });
  try {
    vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([]));
    submit.mockResolvedValue(reply({ id: 'http-created-task' }, 202));
    mount(type);
    await waitFor(() => expect(screen.getByRole('button', { name: `选择${noun}模型：当前模型` })).toBeEnabled());
    fireEvent.change(screen.getByRole('textbox', { name: `${noun}提示词` }), { target: { value: 'HTTP 首次提交' } });
    await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: /^开始生成/ }));
    await waitFor(() => expect(submit).toHaveBeenCalledOnce());
    expect(submit.mock.calls[0][0]).toMatchObject({ type, prompt: 'HTTP 首次提交' });
    const key = new Headers(submit.mock.calls[0][1]?.headers).get('Idempotency-Key');
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    await waitFor(() => expect(JSON.parse(sessionStorage.getItem(`xzwl-${type.toLowerCase()}-submission:owner`)!))
      .toMatchObject({ key, taskRef: 'http-created-task' }));
  } finally { vi.unstubAllGlobals(); }
});

it.each(kinds)('%s regeneration sends a stable UUID on public HTTP without randomUUID', async (type, noun) => {
  const browserCrypto = globalThis.crypto;
  vi.stubGlobal('crypto', { getRandomValues: browserCrypto.getRandomValues.bind(browserCrypto) });
  try {
    fixture(type);mount(type);const card = await originalCard(noun);
    fireEvent.click(within(card).getByRole('button', { name: '重新生成' }));
    await waitFor(() => expect(regenerate).toHaveBeenCalledOnce());
    expect(new Headers(regenerate.mock.calls[0][1]?.headers).get('Idempotency-Key')).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    await waitFor(() => expect(output(card)).toHaveAttribute('src', 'https://assets.example/new-output'));
  } finally { vi.unstubAllGlobals(); }
});

it.each(kinds)('%s requotes the original snapshot, replaces the same card on acceptance, and deletes its durable recovery', async (type, noun) => {
  const { settings, snapshot, storageKey } = fixture(type);
  const view = mount(type), card = await originalCard(noun);
  fireEvent.change(screen.getByRole('textbox', { name: `${noun}提示词` }), { target: { value: '当前完全不同的编辑内容' } });
  await waitFor(() => expect(screen.getByRole('button', { name: /^开始生成/ })).toBeEnabled());
  let accept!: (value: never) => void;
  regenerate.mockImplementationOnce(() => new Promise(resolve => { accept = resolve; }));
  quote.mockClear();
  fireEvent.click(within(card).getByRole('button', { name: '重新生成' }));
  await waitFor(() => expect(regenerate).toHaveBeenCalledOnce());
  expect(quote.mock.calls[0][0]).toEqual({ ...settings, references: snapshot.references });
  expect(quote.mock.invocationCallOrder[0]).toBeLessThan(regenerate.mock.invocationCallOrder[0]);
  expect(regenerate.mock.calls[0][0]).toBe(snapshot.id);
  expect(new Headers(regenerate.mock.calls[0][1]?.headers).get('Idempotency-Key')).toBeTruthy();
  expect(output(card)).toHaveAttribute('src', 'https://assets.example/old-output');
  expect(screen.getAllByRole('article')).toEqual([card]);
  vi.mocked(api.mediaListGetApiV1MediaTasks).mockResolvedValue(reply([]));
  await act(async () => accept(reply({ id: 'replacement-task' }, 202)));
  await waitFor(() => expect(output(card)).toHaveAttribute('src', 'https://assets.example/new-output'));
  expect(screen.getAllByRole('article')).toEqual([card]);
  expect(screen.getByRole('textbox', { name: `${noun}提示词` })).toHaveValue('当前完全不同的编辑内容');
  expect(JSON.parse(sessionStorage.getItem(storageKey)!)).toMatchObject({ taskRef: 'replacement-task' });
  const deleteButton = within(card).getByRole('button', { name: `删除${noun}` });
  vi.mocked(window.confirm).mockReturnValueOnce(false);
  fireEvent.click(deleteButton);
  expect(window.confirm).toHaveBeenCalledWith(`确定删除这条${noun}生成记录吗？`);
  expect(remove).not.toHaveBeenCalled();
  expect(card).toBeInTheDocument();
  expect(deleteButton.parentElement).toHaveClass('video-result-media');
  expect(card.querySelector('.result-card-actions')).not.toContainElement(deleteButton);
  fireEvent.click(deleteButton);
  await waitFor(() => expect(card).not.toBeInTheDocument());
  expect(remove).toHaveBeenCalledWith('replacement-task');
  expect(sessionStorage.getItem(storageKey)).toBeNull();
  view.unmount(); mount(type);
  await screen.findByRole('button', { name: `选择${noun}模型：当前模型` });
  expect(screen.queryByRole('article')).not.toBeInTheDocument();
  expect(submit).not.toHaveBeenCalled();
  expect(regenerate).toHaveBeenCalledOnce();
});

it.each(kinds)('%s preserves the original result after DELETE failure and a first HTTP 422 rejection, then permits retry', async (type, noun) => {
  fixture(type); mount(type); const card = await originalCard(noun);
  remove.mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 403 }), {}));
  fireEvent.click(within(card).getByRole('button', { name: `删除${noun}` }));
  await waitFor(() => expect(remove).toHaveBeenCalledOnce());
  await waitFor(() => expect(within(card).getByRole('button', { name: '重新生成' })).toBeEnabled());
  expect(output(card)).toHaveAttribute('src', 'https://assets.example/old-output');
  regenerate.mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 422 }), {}));
  fireEvent.click(within(card).getByRole('button', { name: '重新生成' }));
  await waitFor(() => expect(regenerate).toHaveBeenCalledOnce());
  await waitFor(() => expect(within(card).getByRole('button', { name: '重新生成' })).toBeEnabled());
  expect(screen.getAllByRole('article')).toEqual([card]);
  expect(output(card)).toHaveAttribute('src', 'https://assets.example/old-output');
  expect(screen.queryByRole('button', { name: '使用原请求重试' })).not.toBeInTheDocument();
  fireEvent.click(within(card).getByRole('button', { name: '重新生成' }));
  await waitFor(() => expect(output(card)).toHaveAttribute('src', 'https://assets.example/new-output'));
  expect(regenerate).toHaveBeenCalledTimes(2);
  expect(submit).not.toHaveBeenCalled();
});

it.each(kinds)('%s retains an uncertain regeneration source and key through remount and original-request retry', async (type, noun) => {
  const { storageKey } = fixture(type);
  const view = mount(type), card = await originalCard(noun);
  regenerate.mockRejectedValue(new TypeError('network disconnected'));
  fireEvent.click(within(card).getByRole('button', { name: '重新生成' }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  const firstCall = regenerate.mock.calls[0];
  expect(firstCall[0]).toBe('original-task');
  const firstKey = new Headers(firstCall[1]?.headers).get('Idempotency-Key');
  expect(JSON.parse(sessionStorage.getItem(storageKey)!)).toMatchObject({ replacesTaskId: 'original-task', state: 'UNCERTAIN', request: { requestRef: firstKey } });
  expect(output(card)).toHaveAttribute('src', 'https://assets.example/old-output');
  expect(screen.getAllByRole('article')).toHaveLength(1);
  view.unmount(); mount(type);
  fireEvent.click(await screen.findByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(regenerate.mock.calls.length).toBeGreaterThan(1));
  await waitFor(() => expect(screen.getByRole('button', { name: '使用原请求重试' })).toBeEnabled());
  regenerate.mockResolvedValue(reply({ id: 'replacement-task' }, 202));
  fireEvent.click(screen.getByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(output(screen.getByRole('article'))).toHaveAttribute('src', 'https://assets.example/new-output'));
  for (const call of regenerate.mock.calls) expect(call).toEqual(firstCall);
  expect(screen.getAllByRole('article')).toHaveLength(1);
  expect(JSON.parse(sessionStorage.getItem(storageKey)!)).toMatchObject({ taskRef: 'replacement-task', replacesTaskId: 'original-task' });
  expect(submit).not.toHaveBeenCalled();
});

it.each(kinds)('%s keeps the old output when a fresh regeneration quote exceeds the balance', async (type, noun) => {
  fixture(type); mount(type); const card = await originalCard(noun);
  quote.mockResolvedValue(reply({ points: '1001.0000' })); quote.mockClear();
  fireEvent.click(within(card).getByRole('button', { name: '重新生成' }));
  await waitFor(() => expect(quote).toHaveBeenCalled());
  await waitFor(() => expect(within(card).getByRole('button', { name: '重新生成' })).toBeEnabled());
  expect(screen.getAllByRole('article')).toEqual([card]);
  expect(output(card)).toHaveAttribute('src', 'https://assets.example/old-output');
  expect(regenerate).not.toHaveBeenCalled();
  expect(submit).not.toHaveBeenCalled();
});

it.each(kinds)('%s releases uncertainty when another request has replaced the source', async (type, noun) => {
  const { storageKey } = fixture(type); mount(type); const card = await originalCard(noun);
  regenerate.mockRejectedValueOnce(new TypeError('network disconnected'));
  fireEvent.click(within(card).getByRole('button', { name: '重新生成' }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  regenerate.mockRejectedValueOnce(new api.ApiError(new Response(null, { status: 409 }), {}));
  fireEvent.click(screen.getByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(within(card).getByRole('button', { name: '重新编辑' })).toBeEnabled());
  expect(screen.queryByRole('button', { name: '使用原请求重试' })).not.toBeInTheDocument();
  expect(sessionStorage.getItem(storageKey)).toBeNull();
  expect(output(card)).toHaveAttribute('src', 'https://assets.example/old-output');
  expect(regenerate.mock.calls[1]).toEqual(regenerate.mock.calls[0]);
});

it.each(kinds)('%s renews expired draft previews on every remount without resubmitting', async (type, noun) => {
  const { storageKey } = fixture(type);
  const saved = JSON.parse(sessionStorage.getItem(storageKey)!);
  saved.draft.materials[0].preview = 'https://assets.example/expired';
  saved.draft.materials[0].portraitId = 'approved-portrait';
  sessionStorage.setItem(storageKey, JSON.stringify(saved));
  saveComposerDraft('owner', 'create', type, { ...saved.draft, selection: saved.request.settings });
  const signer = vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl);
  signer.mockImplementation(async id => reply({ url: `https://assets.example/fresh/${id}` }));
  const view = mount(type); await originalCardWithFreshOutput(noun);
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  await waitFor(() => expect(screen.getAllByAltText('参考图片').every(img => img.getAttribute('src') === 'https://assets.example/fresh/original-reference')).toBe(true));
  expect(screen.getByRole('textbox', { name: `${noun}提示词` })).toHaveValue('原任务提示词');
  view.unmount(); signer.mockClear(); mount(type);
  await waitFor(() => expect(signer).toHaveBeenCalledWith('original-reference'));
  expect(submit).not.toHaveBeenCalled(); expect(regenerate).not.toHaveBeenCalled();
});
async function originalCardWithFreshOutput(noun: string) {
  await screen.findByRole('article', { name: `${noun}任务：原任务提示词` });
  await waitFor(() => expect(screen.getByRole('button', { name: '添加素材' })).toBeEnabled());
}

it.each(kinds)('%s does not restore a removed material when preview signing finishes late', async (type, noun) => {
  const { storageKey } = fixture(type);
  const saved = JSON.parse(sessionStorage.getItem(storageKey)!);
  saveComposerDraft('owner', 'create', type, { ...saved.draft, selection: saved.request.settings });
  let finish!: (value: never) => void;
  vi.mocked(api.storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl).mockImplementation(async id => id === 'original-reference'
    ? new Promise(resolve => { finish = resolve; }) : reply({ url: `https://assets.example/${id}` }));
  mount(type); await originalCardWithFreshOutput(noun);
  fireEvent.pointerEnter(screen.getByRole('button', { name: '添加素材' }), { pointerType: 'mouse' });
  fireEvent.click(await screen.findByRole('button', { name: type === 'VIDEO' ? '移除首帧' : '移除参考图 1' }));
  await act(async () => finish(reply({ url: 'https://assets.example/fresh/original-reference' })));
  expect(screen.queryByAltText('参考图片')).not.toBeInTheDocument();
  expect(submit).not.toHaveBeenCalled();
});
