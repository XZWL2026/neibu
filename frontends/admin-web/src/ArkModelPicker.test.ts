import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import ArkModelPicker from './ArkModelPicker.vue';
import { configureGateway, refreshSession } from './api';
enableAutoUnmount(afterEach);
afterEach(() => vi.unstubAllGlobals());
const response = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });
const catalog = { version: 3, providerId: 'ark', success: true, models: [
  { id: 'real-image', name: '图片模型', type: 'IMAGE', status: '', capability: { resolutions: ['2K'], aspectRatios: ['1:1'], maxCount: 4, minDurationSeconds: 0, maxDurationSeconds: 0, sizes: { '2K': { '1:1': '2048x2048' } } } },
  { id: 'real-video', name: '视频模型', type: 'VIDEO', status: 'Retiring', capability: { resolutions: ['720p'], aspectRatios: ['16:9'], maxCount: 1, minDurationSeconds: 4, maxDurationSeconds: 15, sizes: null } },
  { id: 'old-video', name: '停用模型', type: 'VIDEO', status: 'Shutdown' }
] };
async function open() {
  const requests = vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/auth/session')
    ? { authenticated: true, roles: ['admin'], primary: true, csrfToken: 'csrf', user: { id: 'admin' } } : catalog),
    { status: 200, headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', requests); configureGateway({}); await refreshSession();
  const wrapper = mount(ArkModelPicker, { props: { version: 3, providerId: 'ark', enabled: true, disabled: false } });
  await flushPromises();return { wrapper, requests };
}
it('auto loads saved connection models, groups by output type and emits the real ID without saving routes', async () => {
  const { wrapper, requests } = await open();
  expect(requests.mock.calls[1][0]).toBe('/api/v1/admin/media/ark-connection/models?providerId=ark');
  expect(wrapper.findAll('optgroup').map(g => g.attributes('label'))).toEqual(['图片', '视频']);
  expect(wrapper.get('option[value="VIDEO:old-video"]').attributes('disabled')).toBeDefined();
  await wrapper.get('select').setValue('VIDEO:real-video');
  await wrapper.get('[data-testid="ark-use-model"]').trigger('click');
  expect(wrapper.emitted('select')?.[0]).toEqual([{ providerId: 'ark', model: catalog.models[1] }]);
  expect(requests).toHaveBeenCalledTimes(2);
});
it('clears models on connection edits and ignores an in-flight response from an old connection', async () => {
  const { wrapper, requests } = await open();
  let done!: (response: Response) => void;
  requests.mockImplementationOnce(() => new Promise(resolve => { done = resolve; }));
  await wrapper.get('[data-testid="ark-refresh-models"]').trigger('click');
  expect(wrapper.text()).toContain('正在读取');
  await wrapper.setProps({ enabled: false });
  done(response(catalog)); await flushPromises();
  expect(wrapper.find('option[value="IMAGE:real-image"]').exists()).toBe(false);
  expect(wrapper.get<HTMLButtonElement>('[data-testid="ark-use-model"]').element.disabled).toBe(true);
  await wrapper.setProps({ enabled: true, version: 4 }); await flushPromises();
  expect(wrapper.text()).toContain('配置已变化');
  expect(wrapper.find('option[value="IMAGE:real-image"]').exists()).toBe(false);
});
it('supports empty and safe retryable failure states', async () => {
  const { wrapper, requests } = await open();
  requests.mockImplementationOnce(async () => response({ ...catalog, models: [] }));
  await wrapper.get('[data-testid="ark-refresh-models"]').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('没有图片或视频模型');
  requests.mockImplementationOnce(async () => response({ ...catalog, success: false, message: 'private-upstream-error' }));
  await wrapper.get('[data-testid="ark-refresh-models"]').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('模型目录读取失败');expect(wrapper.text()).not.toContain('private-upstream-error');
  requests.mockImplementationOnce(async () => { throw new Error('private-network-error'); });
  await wrapper.get('[data-testid="ark-refresh-models"]').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('模型目录读取失败');expect(wrapper.text()).not.toContain('private-network-error');
});

it('lets the administrator choose actual ZEX models and explicitly disables unknown protocols', async () => {
  const { wrapper, requests } = await open();
  const models = [
    { ...catalog.models[0], id: 'gpt-image2', name: 'gpt-image2' },
    { id: 'unknown-new-model', name: '未适配模型', type: 'UNSUPPORTED', status: '', capability: null },
  ];
  requests.mockImplementationOnce(async () => response({ version: 1, providerId: 'zex', success: true, models }));
  await wrapper.setProps({ providerId: 'zex', version: 1 }); await flushPromises();
  expect(wrapper.find('option[value="IMAGE:real-image"]').exists()).toBe(false);
  expect(wrapper.get('option[value="UNSUPPORTED:unknown-new-model"]').attributes('disabled')).toBeDefined();
  expect(wrapper.text()).toContain('能力尚未适配');
  await wrapper.get('select').setValue('IMAGE:gpt-image2');
  await wrapper.get('[data-testid="ark-use-model"]').trigger('click');
  expect(wrapper.emitted('select')?.[0]).toEqual([{ providerId: 'zex', model: models[0] }]);
});

it('offers async image models and clearly disables synchronous-only entries', async () => {
  const { wrapper, requests } = await open();
  const models = [
    { ...catalog.models[0], id: 'gpt-image-2.5-flare', name: 'GPT async' },
    { ...catalog.models[0], id: 'nano_banana_pro-4K', name: 'Banana async' },
    { id: 'image2', name: 'image2', type: 'UNSUPPORTED', status: 'SynchronousOnly', capability: null },
  ];
  requests.mockImplementationOnce(async () => response({ version: 1, providerId: 'zex', success: true, models }));
  await wrapper.setProps({ providerId: 'zex', version: 1 }); await flushPromises();
  expect(wrapper.get('option[value="UNSUPPORTED:image2"]').attributes('disabled')).toBeDefined();
  expect(wrapper.text()).toContain('同步型号，请选择异步型号');
  for (const model of models.slice(0, 2)) {
    await wrapper.get('select').setValue(`IMAGE:${model.id}`);
    await wrapper.get('[data-testid="ark-use-model"]').trigger('click');
    expect(wrapper.emitted('select')?.at(-1)).toEqual([{ providerId: 'zex', model }]);
  }
});
