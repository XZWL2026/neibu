import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import ArkConnectionSettings from './ArkConnectionSettings.vue';
import { configureGateway, refreshSession } from './api';
enableAutoUnmount(afterEach);
afterEach(() => vi.unstubAllGlobals());
const path = '/api/v1/admin/media/ark-connection';
const providers = [
  { providerId: 'a', displayName: '供应商 A', enabled: true, endpoint: 'https://a.example/api/v3' },
  { providerId: 'b', displayName: '生图 B', enabled: true, endpoint: 'https://b.example/api/v3' },
  { providerId: 'empty', displayName: '无地址', enabled: true, endpoint: '' }
];
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
async function open() {
  const rows = new Map([['a', { version: 1, providerId: 'a', baseUrl: providers[0].endpoint, apiKey: 'a-fixture-key', apiKeyConfigured: true, editable: true }]]);
  const waiting = new Map<string, ReturnType<typeof deferred<Response>>>();
  const failed = new Set<string>();
  const requests = vi.fn(async (raw: string, options: RequestInit = {}) => {
    if (raw.endsWith('/auth/session')) return reply({ authenticated: true, roles: ['admin'], primary: true, csrfToken: 'csrf', user: { id: 'admin' } });
    const [url, query] = raw.split('?');
    const id = new URLSearchParams(query).get('providerId')!;
    const row = rows.get(id) ?? { version: 0, providerId: id, baseUrl: '', apiKey: '', apiKeyConfigured: false, editable: true };
    if (url === `${path}/models`) return reply({ version: row.version, providerId: id, success: true, models: [{ id: `${id}-model`, name: `${id} 模型`, type: 'IMAGE', capability: {} }] });
    if (url === `${path}/test`) return waiting.get('probe')?.promise ?? reply({ version: row.version, reachable: true });
    if (url !== path) throw new Error('unexpected request');
    if (options.method === 'PUT') {
      const body = JSON.parse(String(options.body));
      const old = rows.get(body.providerId);
      rows.set(body.providerId, { ...body, version: (old?.version ?? 0) + 1, apiKey: body.apiKey || old?.apiKey, apiKeyConfigured: true, editable: true });
      return reply(rows.get(body.providerId));
    }
    expect(id).toBeTruthy();
    if (waiting.has(id)) return waiting.get(id)!.promise;
    return failed.has(id) ? reply({ message: 'sensitive-server-error' }, 500) : reply(row);
  });
  vi.stubGlobal('fetch', requests); configureGateway({}); await refreshSession();
  const mountPage = () => mount(ArkConnectionSettings, { props: { providers } });
  const wrapper = mountPage(); await flushPromises();
  return { wrapper, mountPage, requests, rows, waiting, failed };
}
it('persists providers independently, rereads after save, and restores after switching and a page remount', async () => {
  const { wrapper, mountPage, requests, rows } = await open();
  await wrapper.get('#ark-provider').setValue('b'); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('');
  expect(wrapper.get<HTMLInputElement>('#ark-base-url').element.value).toBe(providers[1].endpoint);
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.required).toBe(true);
  expect(wrapper.get<HTMLButtonElement>('[data-testid="ark-test"]').element.disabled).toBe(true);
  await wrapper.get('#ark-api-key').setValue('b-fixture-key');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(rows.get('a')!.apiKey).toBe('a-fixture-key');
  expect(rows.get('b')!.apiKey).toBe('b-fixture-key');
  const putIndex = requests.mock.calls.findIndex(([, options]) => options?.method === 'PUT');
  expect(requests.mock.calls[putIndex + 1][0]).toBe(`${path}?providerId=b`);
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('b-fixture-key');
  await wrapper.get('#ark-api-key').setValue('');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('b-fixture-key');
  await wrapper.get('#ark-provider').setValue('a'); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('a-fixture-key');
  await wrapper.get('#ark-provider').setValue('b'); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('b-fixture-key');
  wrapper.unmount();
  const refreshed = mountPage(); await flushPromises();
  await refreshed.get('#ark-provider').setValue('b'); await flushPromises();
  expect(refreshed.get<HTMLInputElement>('#ark-api-key').element.value).toBe('b-fixture-key');
  await refreshed.get('#ark-provider').setValue('empty'); await flushPromises();
  expect(refreshed.get<HTMLInputElement>('#ark-base-url').element.value).toBe('');
  expect(refreshed.get<HTMLInputElement>('#ark-api-key').element.value).toBe('');
});
it('clears immediately, allows rapid switching and ignores stale responses and verification results', async () => {
  const { wrapper, waiting } = await open();
  const probe = deferred<Response>(); waiting.set('probe', probe);
  await wrapper.get('[data-testid="ark-test"]').trigger('click');
  const b = deferred<Response>(); waiting.set('b', b);
  await wrapper.get('#ark-provider').setValue('b');
  expect(wrapper.get<HTMLInputElement>('#ark-base-url').element.value).toBe('');
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('');
  expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true);
  expect(wrapper.find('option[value="IMAGE:a-model"]').exists()).toBe(false);
  expect(wrapper.get<HTMLSelectElement>('#ark-provider').element.disabled).toBe(false);
  await wrapper.get('#ark-provider').setValue('a'); await flushPromises();
  b.resolve(reply({ version: 9, providerId: 'b', baseUrl: providers[1].endpoint, apiKey: 'stale-b-key', apiKeyConfigured: true, editable: true }));
  probe.resolve(reply({ version: 1, reachable: true })); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('a-fixture-key');
  expect(wrapper.text()).not.toContain('连接验证通过');
  expect(wrapper.get<HTMLSelectElement>('#ark-discovered-model').element.value).toBe('');
});
it('keeps failed reads blank and disabled, retries the selected provider and does not trust mismatched responses', async () => {
  const { wrapper, failed, waiting } = await open();
  failed.add('b');
  await wrapper.get('#ark-provider').setValue('b'); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-base-url').element.value).toBe('');
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('');
  expect(wrapper.text()).toContain('连接配置加载失败');
  expect(wrapper.text()).not.toContain('sensitive-server-error');
  expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true);
  expect(wrapper.get<HTMLButtonElement>('[data-testid="ark-test"]').element.disabled).toBe(true);
  failed.clear();
  await wrapper.get('[data-testid="ark-reload"]').trigger('click'); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-base-url').element.value).toBe(providers[1].endpoint);
  const wrong = deferred<Response>(); waiting.set('b', wrong);
  await wrapper.get('[data-testid="ark-reload"]').trigger('click');
  wrong.resolve(reply({ providerId: 'a', apiKey: 'wrong-provider-key' })); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('');
  expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true);
});
it('requires an explicit draft decision and never saves an A draft to B', async () => {
  const { wrapper, requests } = await open();
  await wrapper.get('#ark-api-key').setValue('a-draft-key');
  await wrapper.get('#ark-provider').setValue('b');
  expect(wrapper.get<HTMLSelectElement>('#ark-provider').element.value).toBe('a');
  expect(wrapper.text()).toContain('切换将放弃');
  await wrapper.get('form').trigger('submit');
  expect(requests.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
  await wrapper.get('[data-testid="ark-keep-draft"]').trigger('click');
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('a-draft-key');
  await wrapper.get('#ark-provider').setValue('b');
  await wrapper.get('[data-testid="ark-discard-draft"]').trigger('click'); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('');
  expect(wrapper.get<HTMLSelectElement>('#ark-provider').element.value).toBe('b');
  expect(requests.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
});
it('pins the provider while saving and keeps a failed post-save reread blank until retried', async () => {
  const { wrapper, waiting, requests, rows } = await open();
  const reread = deferred<Response>(); waiting.set('a', reread);
  await wrapper.get('#ark-api-key').setValue('a-rotated-key');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(wrapper.get<HTMLSelectElement>('#ark-provider').element.disabled).toBe(true);
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('');
  await wrapper.get('#ark-provider').setValue('b');
  reread.resolve(reply({}, 503)); await flushPromises();
  expect(wrapper.get<HTMLSelectElement>('#ark-provider').element.value).toBe('a');
  expect(wrapper.get<HTMLInputElement>('#ark-base-url').element.value).toBe('');
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('');
  expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true);
  expect(rows.get('a')!.apiKey).toBe('a-rotated-key');
  expect(rows.has('b')).toBe(false);
  expect(JSON.parse(String(requests.mock.calls.find(([, options]) => options?.method === 'PUT')![1]!.body)).providerId).toBe('a');
  waiting.clear(); await wrapper.get('[data-testid="ark-reload"]').trigger('click'); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('a-rotated-key');
});

it('selects the model owner connection without discarding another provider draft', async () => {
  const { wrapper, requests } = await open();
  await wrapper.get('#ark-api-key').setValue('a-draft');
  expect(await wrapper.vm.selectProvider('b')).toBe(false);
  expect(wrapper.get<HTMLSelectElement>('#ark-provider').element.value).toBe('a');
  expect(requests.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
  await wrapper.get('[data-testid="ark-discard-draft"]').trigger('click'); await flushPromises();
  expect(wrapper.get<HTMLSelectElement>('#ark-provider').element.value).toBe('b');
  expect(await wrapper.vm.selectProvider('a')).toBe(true); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('a-fixture-key');
});
