import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import ArkConnectionSettings from './ArkConnectionSettings.vue';
import { configureGateway, refreshSession } from './api';

enableAutoUnmount(afterEach);
afterEach(() => vi.unstubAllGlobals());
const path = '/api/v1/admin/media/ark-connection';
const original = { version: 1, providerId: 'ark', baseUrl: 'https://ark.example/api/v3', apiKeyConfigured: true, apiKey: 'saved-provider-key', maskedApiKey: '***', editable: true, source: 'DATABASE', timeoutSeconds: 180, retryCount: 0 };
async function open(overrides = {}, endpoint = 'https://ark.example') {
  let connection = { ...original, ...overrides };
  const requests = vi.fn(async (url: string, options: RequestInit = {}) => {
    const pathname = url.split('?')[0];
    if (url.endsWith('/auth/session')) return response({ authenticated: true, roles: ['admin'], primary: true, csrfToken: 'csrf-test', user: { id: 'admin', username: 'administrator' } });
    if (pathname === `${path}/models`) return response({ version: connection.version, providerId: connection.providerId, success: true, models: [] });
    if (pathname === `${path}/test`) return response({ version: connection.version, reachable: true, message: 'untrusted-probe-text' });
    if (pathname !== path) throw new Error('unexpected request');
    if (options.method === 'PUT') {
      const body = JSON.parse(String(options.body));
      connection = { ...connection, version: body.expectedVersion + 1, providerId: body.providerId, baseUrl: body.baseUrl, apiKey: body.apiKey || connection.apiKey, apiKeyConfigured: true, timeoutSeconds: body.timeoutSeconds, retryCount: body.retryCount };
    }
    return response(connection);
  });
  vi.stubGlobal('fetch', requests); configureGateway({}); await refreshSession();
  const wrapper = mount(ArkConnectionSettings, { props: { providers: [{ providerId: 'ark', displayName: '方舟', endpoint, enabled: true }] } });
  await flushPromises();
  return { wrapper, requests };
}
function response(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }); }

it('displays the saved plaintext key, updates and reloads it without persisting it in browser storage', async () => {
  const storageWrite = vi.spyOn(Storage.prototype, 'setItem');
  const { wrapper, requests } = await open();
  const secret = 'admin-entered-provider-key';
  const password = wrapper.get<HTMLInputElement>('#ark-api-key');
  expect(password.element.value).toBe(original.apiKey); expect(password.attributes('type')).toBe('password');
  await wrapper.get('[aria-label="显示 API Key"]').trigger('click');
  expect(password.attributes('type')).toBe('text');
  await password.setValue(secret);
  await wrapper.get('form').trigger('submit'); await flushPromises();
  const saved = requests.mock.calls.find(([, options]) => options?.method === 'PUT');
  expect(JSON.parse(String(saved?.[1]?.body))).toEqual({ expectedVersion: 1, providerId: 'ark', baseUrl: original.baseUrl, apiKey: secret, timeoutSeconds: 180, retryCount: 0 });
  expect(password.element.value).toBe(secret);
  expect(wrapper.get<HTMLButtonElement>('[data-testid="ark-test"]').element.disabled).toBe(false);
  expect(wrapper.emitted('saved')).toHaveLength(1);
  await wrapper.get('[data-testid="ark-reload"]').trigger('click'); await flushPromises();
  expect(password.element.value).toBe(secret);
  await password.setValue('');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  const writes = requests.mock.calls.filter(([, options]) => options?.method === 'PUT');
  expect(JSON.parse(String(writes[1]?.[1]?.body)).apiKey).toBe('');
  expect(password.element.value).toBe(secret);
  expect(storageWrite).not.toHaveBeenCalled();
});

it('tests only a clean saved version and clears prior success on every edit or reload', async () => {
  const { wrapper, requests } = await open();
  const test = wrapper.get<HTMLButtonElement>('[data-testid="ark-test"]');
  await test.trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('已保存配置的连接验证通过');
  expect(wrapper.text()).not.toContain('untrusted-probe-text');
  await wrapper.get('#ark-api-key').setValue('new-key');
  expect(test.element.disabled).toBe(true); expect(wrapper.text()).not.toContain('连接验证通过');
  await test.trigger('click');
  expect(requests.mock.calls.filter(([url]) => url.split('?')[0].endsWith('/test'))).toHaveLength(1);
  await wrapper.get('[data-testid="ark-reload"]').trigger('click'); await flushPromises();
  expect(test.element.disabled).toBe(false); expect(wrapper.text()).not.toContain('连接验证通过');
  await wrapper.get('input[type="url"]').setValue('https://other.example/api/v3');
  expect(test.element.disabled).toBe(true);
  await wrapper.get('form').trigger('submit'); await flushPromises();
  const hostOnlySave = requests.mock.calls.find(([, options]) => options?.method === 'PUT');
  expect(JSON.parse(String(hostOnlySave?.[1]?.body)).apiKey).toBe('');
});

it('rejects stale probe results without claiming the displayed version passed', async () => {
  const { wrapper, requests } = await open();
  requests.mockImplementationOnce(async () => response({ version: 2, reachable: true, message: 'raw-key' }));
  await wrapper.get('[data-testid="ark-test"]').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('请重新加载');
  expect(wrapper.text()).not.toContain('连接验证通过'); expect(wrapper.text()).not.toContain('raw-key');
});

it.each([409, 400, 503, 500])('shows safe save feedback for status %s without exposing a returned secret', async status => {
  const { wrapper, requests } = await open();
  requests.mockImplementationOnce(async () => response({ message: 'server-secret-must-not-render' }, status));
  await wrapper.get('#ark-api-key').setValue('typed-key');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(wrapper.text()).not.toContain('server-secret-must-not-render');
  expect(wrapper.text()).not.toContain('已保存，');
  expect(wrapper.emitted('saved')).toBeUndefined();
  if (status === 409) expect(wrapper.text()).toContain('请重新加载后编辑');
});

it('disables editing when unavailable and disables testing without a configured key', async () => {
  const { wrapper, requests } = await open({ editable: false, apiKeyConfigured: false, apiKey: '', maskedApiKey: '' });
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.disabled).toBe(true);
  expect(wrapper.get<HTMLButtonElement>('[data-testid="ark-test"]').element.disabled).toBe(true);
  expect(wrapper.text()).toContain('连接配置暂不可编辑');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(requests).toHaveBeenCalledTimes(2);
});

it('keeps load/probe failures safe and prevents duplicate verification while busy', async () => {
  const { wrapper, requests } = await open();
  let complete!: (value: Response) => void;
  requests.mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
  const test = wrapper.get<HTMLButtonElement>('[data-testid="ark-test"]');
  await test.trigger('click'); expect(test.element.disabled).toBe(true);
  await test.trigger('click');
  complete(response({ message: 'private-error' }, 503)); await flushPromises();
  expect(wrapper.text()).toContain('连接验证失败'); expect(wrapper.text()).not.toContain('private-error');
  requests.mockImplementationOnce(async () => response({ message: 'private-load-error' }, 500));
  await wrapper.get('[data-testid="ark-reload"]').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('连接配置加载失败'); expect(wrapper.text()).not.toContain('private-load-error');
  expect(test.element.disabled).toBe(true);
});

it('loads a different provider with an empty key instead of retaining the previous credentials', async () => {
  const { wrapper } = await open();
  await wrapper.setProps({ providers: [
    { providerId: 'ark', displayName: '方舟', endpoint: 'https://ark.example', enabled: true },
    { providerId: 'image', displayName: '生图', endpoint: 'https://other.example', enabled: true }
  ] });
  await wrapper.get('#ark-provider').setValue('image');
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('');
  expect(wrapper.get<HTMLInputElement>('#ark-base-url').element.value).toBe('');
  expect(wrapper.get<HTMLButtonElement>('[data-testid="ark-test"]').element.disabled).toBe(true);
});

it('accepts an HTTP connection with a port and prefix and reloads the saved address', async () => {
  const base = 'http://14.103.26.228:3000/doubao/api/v3';
  const { wrapper, requests } = await open({ baseUrl: '' }, base);
  const input = wrapper.get<HTMLInputElement>('#ark-base-url');
  expect(input.element.value).toBe(base);
  await input.setValue(base);
  expect(input.element.checkValidity()).toBe(true);
  await wrapper.get('#ark-api-key').setValue('proxy-test-key');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  const saved = requests.mock.calls.find(([, options]) => options?.method === 'PUT');
  expect(JSON.parse(String(saved?.[1]?.body)).baseUrl).toBe(base);
  expect(input.element.value).toBe(base);
});

it('persists request policy, invalidates probe on edits and restores the saved values on reload', async () => {
  const { wrapper, requests } = await open();
  await wrapper.get('[data-testid="ark-test"]').trigger('click'); await flushPromises();
  await wrapper.get('#ark-timeout').setValue('45');
  await wrapper.get('#ark-retries').setValue('2');
  expect(wrapper.get<HTMLButtonElement>('[data-testid="ark-test"]').element.disabled).toBe(true);
  await wrapper.get('form').trigger('submit'); await flushPromises();
  const write = requests.mock.calls.find(([, options]) => options?.method === 'PUT');
  expect(JSON.parse(String(write?.[1]?.body))).toMatchObject({ timeoutSeconds: 45, retryCount: 2 });
  await wrapper.get('#ark-timeout').setValue('999');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(requests.mock.calls.filter(([, options]) => options?.method === 'PUT')).toHaveLength(1);
  await wrapper.get('[data-testid="ark-reload"]').trigger('click'); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('#ark-timeout').element.value).toBe('45');
  expect(wrapper.get<HTMLInputElement>('#ark-retries').element.value).toBe('2');
});
