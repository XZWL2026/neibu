import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import App from './App.vue';
import { adminRoutes } from './router';
import { configureGateway } from './api';

enableAutoUnmount(afterEach);
afterEach(() => vi.unstubAllGlobals());
async function open(role = 'admin', primary = true, path = '/admin/pricing', discovery = false, catalogFailure = false) {
  let prices = { managed: true, version: 1, prices: [{ type: 'IMAGE', accountType: 'PERSONAL', model: 'image-model', resolution: '2k', unit: 'IMAGE', pointsPerUnit: '12.3456', enabled: true }] };
  let providers = [{ providerId: 'ark', displayName: '方舟', endpoint: 'https://ark.example', enabled: true }];
  let models = discovery ? [{ modelId: 'existing-video', providerId: 'ark', providerModel: 'real-video-id', capability: { type: 'VIDEO', displayName: '已有视频名称', resolutions: ['720p'], aspectRatios: ['16:9'], maxCount: 1, minDurationSeconds: 3, maxDurationSeconds: 15, sizes: null } }] : [];
  const requests = vi.fn(async (path: string, options: RequestInit = {}) => {
    let result: unknown;
    if (catalogFailure && path.split('?')[0].endsWith('/ark-connection/models')) return new Response('{}', { status: 503, headers: { 'Content-Type': 'application/json' } });
    if (path.endsWith('/auth/session')) result = { authenticated: true, roles: [role], primary, user: { id: 'a', username: 'administrator' }, csrfToken: 'csrf-test' };
    else if (path.endsWith('/settings/runtime')) result = { rechargePointsPerYuan4: '10.0000', rechargeRateVersion: 1 };
    else if (path.endsWith('/billing/media-prices')) {
      if (options.method === 'PUT') {
        const body = JSON.parse(String(options.body));
        expect(new Headers(options.headers).get('X-CSRF-TOKEN')).toBe('csrf-test');
        prices = { managed: true, version: body.expectedVersion + 1, prices: body.prices };
      }
      result = prices;
    } else if (path.endsWith('/routing/providers')) result = providers;
    else if (path.endsWith('/routing/models')) result = models;
    else if (path.split('?')[0].endsWith('/ark-connection/models')) result = { version: 0, providerId: 'ark', success: true, models: [{ id: 'real-image-id', name: '火山图片', type: 'IMAGE', status: '', capability: { resolutions: ['2K', '4K'], aspectRatios: ['1:1'], maxCount: 4, minDurationSeconds: 0, maxDurationSeconds: 0, sizes: { '2K': { '1:1': '2048x2048' }, '4K': { '1:1': '4096x4096' } } } }, { id: 'real-video-id', name: '火山视频', type: 'VIDEO', status: '', capability: { resolutions: ['480p', '720p'], aspectRatios: ['16:9'], maxCount: 1, minDurationSeconds: 4, maxDurationSeconds: 15, sizes: null } }, { id: 'new-video-id', name: '新视频', type: 'VIDEO', status: '', capability: { resolutions: ['480p', '720p', '1080p'], aspectRatios: ['1:1', '16:9'], maxCount: 1, minDurationSeconds: 4, maxDurationSeconds: 30, sizes: null } }] };
    else if (path.split('?')[0].endsWith('/media/ark-connection')) result = { version: 0, providerId: 'ark', baseUrl: 'https://ark.example/api/v3', apiKeyConfigured: discovery, apiKey: discovery ? 'test-only-key' : '', maskedApiKey: '', editable: true, source: 'ENVIRONMENT' };
    else if (path.includes('/routing/models/')) {
      if (options.method === 'DELETE') {
        expect(new Headers(options.headers).get('X-CSRF-TOKEN')).toBe('csrf-test');
        models = models.filter(item => item.modelId !== decodeURIComponent(path.split('/').at(-1)!));
        return new Response(null, { status: 204 });
      }
      const saved = { ...JSON.parse(String(options.body)), modelId: decodeURIComponent(path.split('/').at(-1)!) };
      models = [...models.filter(item => item.modelId !== saved.modelId), saved]; result = saved;
    } else if (path.includes('/routing/providers/')) {
      const saved = { ...JSON.parse(String(options.body)), providerId: decodeURIComponent(path.split('/').at(-1)!) };
      providers = [...providers.filter(item => item.providerId !== saved.providerId), saved]; result = saved;
    }
    else if (path.endsWith('provider-status')) result = { providerName: path.includes('/storage/') ? 'TOS' : path.includes('/iam/') ? '短信（未实现）' : '方舟', configured: false, available: false, maskedIdentifier: '', missingEnvironmentVariables: path.includes('/media/') ? ['VOLC_ARK_API_KEY'] : [] };
    else throw new Error('unexpected request');
    return new Response(JSON.stringify(result), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', requests);
  configureGateway({});
  const router = createRouter({ history: createMemoryHistory(), routes: adminRoutes });
  await router.push(path);
  const wrapper = mount(App, { global: { plugins: [router] } });
  await flushPromises();
  return { wrapper, requests, router };
}
it('loads and saves exact four-place points through generated Gateway SDK', async () => {
  const { wrapper, requests } = await open();
  expect(wrapper.text()).toContain('模型定价');
  await wrapper.get('.price-input').setValue('0.1234');
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('已保存');
  const saved = requests.mock.calls.find(([, options]) => options?.method === 'PUT');
  expect(JSON.parse(String(saved?.[1]?.body)).prices[0].yuanPerUnit).toBe('0.1234');
  expect(wrapper.text()).not.toContain('fixture');
});
it.each([['normal_admin', false], ['admin', false]])('denies %s primary=%s before loading configuration', async (role, primary) => {
  const { wrapper, requests } = await open(role, primary);
  expect(wrapper.text()).toContain('无权限');
  expect(wrapper.find('a[href="/admin/pricing"]').exists()).toBe(false);
  expect(wrapper.find('a[href="/admin/providers"]').exists()).toBe(false);
  expect(requests).toHaveBeenCalledTimes(1);
});

it('manages routing alongside a separate Ark connection form', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers');
  expect(wrapper.text()).toContain('VOLC_ARK_API_KEY');
  expect(wrapper.text()).toContain('短信（未实现）');
  expect(wrapper.get<HTMLInputElement>('#ark-api-key').element.value).toBe('');
  await wrapper.findAll('button').find(b => b.text() === '编辑供应商')!.trigger('click');
  const providerForm = wrapper.get('[data-testid="routing-provider-form"]');
  const routeSwitch = providerForm.get<HTMLInputElement>('input[role="switch"]');
  expect(routeSwitch.attributes('aria-label')).toBe('接收生成任务');
  expect(routeSwitch.element.checked).toBe(true);
  expect(providerForm.text()).toContain('已启用');
  await routeSwitch.setValue(false);
  expect(providerForm.text()).toContain('已停用');
  expect(providerForm.text()).toContain('修改后点击“保存供应商”生效');
  expect(requests.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
  await providerForm.trigger('submit'); await flushPromises();
  const sent = requests.mock.calls.find(([p, o]) => p.endsWith('/providers/ark') && o?.method === 'PUT');
  expect(JSON.parse(String(sent?.[1]?.body)).enabled).toBe(false);
  expect(wrapper.text()).toContain('供应商已保存');
  expect(wrapper.get<HTMLButtonElement>('[data-testid="routing-model-form"] button[type="submit"]').element.disabled).toBe(true);
});
it('keeps a failed save visible without rendering upstream secret text', async () => {
  const { wrapper, requests } = await open();
  requests.mockImplementationOnce(async () => new Response(JSON.stringify({ message: 'upstream-secret-must-not-be-shown' }), { status: 409, headers: { 'Content-Type': 'application/json' } }));
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click');await flushPromises();
  expect(wrapper.text()).toContain('请重新加载后编辑');
  expect(wrapper.text()).not.toContain('upstream-secret-must-not-be-shown');
  expect(wrapper.findAll('.pricing-feedback').map(item => item.text()).join(' ')).not.toContain('已保存');
});

it('saving an Ark key refreshes status without resetting unsaved model fields', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers');
  const modelForm = wrapper.get('[data-testid="routing-model-form"]');
  const modelId = modelForm.findAll('label').find(label => label.text().startsWith('模型标识'))!.get<HTMLInputElement>('input');
  await modelId.setValue('unsaved-model');
  await wrapper.get('#ark-api-key').setValue('connection-key');
  await wrapper.get('[data-testid="ark-connection-form"]').trigger('submit'); await flushPromises();
  expect(modelId.element.value).toBe('unsaved-model');
  expect(requests.mock.calls.filter(([path]) => path.endsWith('/routing/models'))).toHaveLength(1);
  expect(requests.mock.calls.filter(([path]) => path.endsWith('/media/provider-status'))).toHaveLength(2);
});
it.each([['normal_admin', false], ['admin', false]])('denies Ark connection page for %s primary=%s', async (role, primary) => {
  const { wrapper, requests } = await open(role, primary, '/admin/providers');
  expect(wrapper.text()).toContain('无权限');
  expect(wrapper.find('#ark-api-key[type="text"]').exists()).toBe(false);
  expect(requests).toHaveBeenCalledTimes(1);
});

it('selects automatic capabilities and updates existing model limits without changing its identity', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers', true);
  const form = wrapper.get('[data-testid="routing-model-form"]');
  const field = (name: string) => form.findAll('label').find(label => label.text().startsWith(name))!;
  await wrapper.get('#ark-discovered-model').setValue('IMAGE:real-image-id');
  await wrapper.get('[data-testid="ark-use-model"]').trigger('click'); await flushPromises();
  expect(field('供应商模型标识').get<HTMLInputElement>('input').element.value).toBe('real-image-id');
  expect(field('供应商').get<HTMLSelectElement>('select').element.value).toBe('ark');
  expect(field('模型标识').get<HTMLInputElement>('input').element.value).toBe('real-image-id');
  expect(field('分辨率').get<HTMLInputElement>('input').element.value).toBe('2K、4K');
  expect(requests.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
  await wrapper.get('#ark-discovered-model').setValue('VIDEO:real-video-id');
  await wrapper.get('[data-testid="ark-use-model"]').trigger('click'); await flushPromises();
  expect(field('模型标识').get<HTMLInputElement>('input').element.value).toBe('existing-video');
  expect(field('显示名称').get<HTMLInputElement>('input').element.value).toBe('已有视频名称');
  expect(field('分辨率').get<HTMLInputElement>('input').element.value).toBe('720p');
  expect(field('最长秒数').get<HTMLInputElement>('input').element.value).toBe('15');
  await form.trigger('submit'); await flushPromises();
  const write = requests.mock.calls.find(([path, options]) => path.endsWith('/models/existing-video') && options?.method === 'PUT');
  expect(JSON.parse(String(write?.[1]?.body))).toMatchObject({ modelId: 'existing-video', providerId: 'ark', providerModel: 'real-video-id', capability: { maxDurationSeconds: 15 } });
});

it('automatically fills readonly model parameters, switches media types and saves the exact capability payload', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers', true);
  await wrapper.get('#ark-discovered-model').setValue('VIDEO:new-video-id');
  await wrapper.get('[data-testid="ark-use-model"]').trigger('click'); await flushPromises();
  const form = wrapper.get('[data-testid="routing-model-form"]');
  const field = (name: string) => form.findAll('label').find(label => label.text().startsWith(name))!;
  const values = { 分辨率: '480p、720p、1080p', 比例: '1:1、16:9', 最多张数: '1', 最短秒数: '4', 最长秒数: '30' };
  for (const [name, value] of Object.entries(values)) {
    const input = field(name).get<HTMLInputElement>('input');
    expect(input.element.value).toBe(value); expect(input.element.readOnly).toBe(true);
  }
  await field('模型标识').get('input').setValue('new-video');
  await form.trigger('submit'); await flushPromises();
  const write = requests.mock.calls.find(([path, options]) => path.endsWith('/models/new-video') && options?.method === 'PUT');
  expect(JSON.parse(String(write?.[1]?.body))).toMatchObject({ providerModel: 'new-video-id', capability: { type: 'VIDEO', maxCount: 1, minDurationSeconds: 4, maxDurationSeconds: 30, aspectRatios: ['1:1', '16:9'] } });
  await wrapper.get('#ark-discovered-model').setValue('IMAGE:real-image-id');
  await wrapper.get('[data-testid="ark-use-model"]').trigger('click'); await flushPromises();
  expect(field('最短秒数').get<HTMLInputElement>('input').element.value).toBe('不适用');
  expect(field('最多张数').get<HTMLInputElement>('input').element.value).toBe('4');
  expect(field('2K · 1:1').get<HTMLInputElement>('input').element.value).toBe('2048x2048');
  await field('模型标识').get('input').setValue('new-image');
  await form.trigger('submit'); await flushPromises();
  const image = requests.mock.calls.find(([path, options]) => path.endsWith('/models/new-image') && options?.method === 'PUT');
  expect(JSON.parse(String(image?.[1]?.body)).capability).toMatchObject({ minDurationSeconds: 0, maxDurationSeconds: 0, sizes: { '2K': { '1:1': '2048x2048' }, '4K': { '1:1': '4096x4096' } } });
  await field('供应商模型标识').get('input').setValue('unknown-model-version');
  expect(field('分辨率').get<HTMLInputElement>('input').element.value).toBe('');
  expect(form.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true);
  const before = requests.mock.calls.length;
  await form.trigger('submit'); await flushPromises();
  expect(requests.mock.calls).toHaveLength(before);
});

it('rejects spaces in a model identifier before making a save request', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers', true);
  await wrapper.get('#ark-discovered-model').setValue('VIDEO:new-video-id');
  await wrapper.get('[data-testid="ark-use-model"]').trigger('click'); await flushPromises();
  const form = wrapper.get('[data-testid="routing-model-form"]');
  await form.get('input').setValue('seedance 2.5');
  await form.trigger('submit'); await flushPromises();
  expect(form.text()).toContain('模型标识只能包含英文字母、数字、点、下划线、冒号和连字符，不能包含空格。');
  expect(requests.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
  expect(form.get<HTMLInputElement>('input').element.validity.patternMismatch).toBe(true);
});

it('shows the saved model immediately, retains it on reload and updates without duplicate rows', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers', true);
  await wrapper.get('#ark-discovered-model').setValue('VIDEO:new-video-id');
  await wrapper.get('[data-testid="ark-use-model"]').trigger('click'); await flushPromises();
  const form = wrapper.get('[data-testid="routing-model-form"]');
  const table = () => wrapper.findAll('table').find(t => t.find('caption').text() === '模型列表')!;
  const reads = requests.mock.calls.filter(([path]) => path.endsWith('/routing/models')).length;
  await form.trigger('submit'); await flushPromises();
  expect(table().text()).toContain('new-video-id');
  expect(form.text()).toContain('模型与能力已保存');
  expect(requests.mock.calls.filter(([path]) => path.endsWith('/routing/models'))).toHaveLength(reads);
  await wrapper.findAll('button').find(b => b.text() === '重新加载路由与状态')!.trigger('click'); await flushPromises();
  expect(table().text()).toContain('new-video-id');
  const row = table().findAll('tbody tr').find(row => row.text().includes('new-video-id'))!;
  await row.get('button').trigger('click');
  const name = form.findAll('label').find(label => label.text().startsWith('显示名称'))!.get('input');
  await name.setValue('自定义视频名称');
  await form.trigger('submit'); await flushPromises();
  expect(table().findAll('tbody tr').filter(row => row.text().includes('new-video-id'))).toHaveLength(1);
  requests.mockImplementationOnce(async () => new Response('{}', { status: 400 }));
  await form.get('input').setValue('failed-model');
  await form.trigger('submit'); await flushPromises();
  expect(table().text()).not.toContain('failed-model');
  expect(form.text()).toContain('模型保存失败');
  expect(form.get<HTMLInputElement>('input').element.value).toBe('failed-model');
});

it('confirms model deletion, persists removal on reload and clears the matching edit form', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers', true);
  const table = () => wrapper.findAll('table').find(t => t.find('caption').text() === '模型列表')!;
  await table().findAll('button').find(b => b.text() === '编辑模型')!.trigger('click');
  await table().findAll('button').find(b => b.text() === '删除模型')!.trigger('click');
  expect(wrapper.get('[data-testid="model-delete-confirmation"]').text()).toContain('existing-video');
  expect(requests.mock.calls.some(([,o]) => o?.method === 'DELETE')).toBe(false);
  await wrapper.get('[data-testid="model-delete-cancel"]').trigger('click');
  expect(table().text()).toContain('existing-video');
  await table().findAll('button').find(b => b.text() === '删除模型')!.trigger('click');
  await wrapper.get('[data-testid="model-delete-confirm"]').trigger('click'); await flushPromises();
  expect(table().text()).not.toContain('existing-video');
  expect(wrapper.text()).toContain('已删除模型');
  expect(wrapper.get<HTMLInputElement>('[data-testid="routing-model-form"] input').element.value).toBe('');
  expect(requests.mock.calls.filter(([,o]) => o?.method === 'DELETE')).toHaveLength(1);
  await wrapper.findAll('button').find(b => b.text() === '重新加载路由与状态')!.trigger('click'); await flushPromises();
  expect(table().text()).not.toContain('existing-video');
});
it('retains the model and permits retry when deletion fails', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers', true);
  await wrapper.findAll('button').find(b => b.text() === '删除模型')!.trigger('click');
  requests.mockImplementationOnce(async () => new Response('{}', { status: 500 }));
  await wrapper.get('[data-testid="model-delete-confirm"]').trigger('click'); await flushPromises();
  expect(wrapper.get('[data-testid="model-delete-confirmation"]').text()).toContain('删除失败');
  expect(wrapper.findAll('table').find(t => t.find('caption').text() === '模型列表')!.text()).toContain('existing-video');
  await wrapper.get('[data-testid="model-delete-confirm"]').trigger('click'); await flushPromises();
  expect(wrapper.find('[data-testid="model-delete-confirmation"]').exists()).toBe(false);
});

it('edits a provider under its original identity and retains changes after reload', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers');
  await wrapper.findAll('button').find(b => b.text() === '编辑供应商')!.trigger('click');
  const form = wrapper.get('[data-testid="routing-provider-form"]');
  const inputs = form.findAll('input');
  expect(inputs[0].attributes('readonly')).toBeDefined();
  await inputs[1].setValue('生图');
  await inputs[2].setValue('https://zexapi.com/v1');
  await form.trigger('submit'); await flushPromises();
  const sent = requests.mock.calls.find(([p, o]) => p.endsWith('/providers/ark') && o?.method === 'PUT');
  expect(JSON.parse(String(sent?.[1]?.body))).toEqual({ displayName: '生图', endpoint: 'https://zexapi.com/v1', enabled: true });
  expect(form.text()).toContain('供应商已保存');
  await wrapper.findAll('button').find(b => b.text() === '重新加载路由与状态')!.trigger('click'); await flushPromises();
  expect(wrapper.findAll('table').find(table => table.find('caption').text() === '供应商列表')!.text()).toContain('生图');
});
it('rejects a Chinese provider identifier locally and displays save failures beside the button', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers');
  const form = wrapper.get('[data-testid="routing-provider-form"]');
  await form.findAll('input')[0].setValue('生图');
  await form.findAll('input')[1].setValue('生图');
  await form.findAll('input')[2].setValue('https://zexapi.com/v1');
  await form.trigger('submit'); await flushPromises();
  expect(requests.mock.calls.some(([,o]) => o?.method === 'PUT')).toBe(false);
  expect(form.text()).toContain('供应商标识只能包含');
  await form.findAll('input')[0].setValue('image-provider');
  requests.mockResolvedValueOnce(new Response('{}', { status: 400, headers: { 'Content-Type': 'application/json' } }));
  await form.trigger('submit'); await flushPromises();
  expect(form.text()).toContain('供应商保存失败');
  expect(form.findAll('input')[1].element.value).toBe('生图');
});

it('creates an HTTP provider with a custom port and prefix', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers');
  const form = wrapper.get('[data-testid="routing-provider-form"]');
  const inputs = form.findAll('input');
  await inputs[0].setValue('doubao-proxy');
  await inputs[1].setValue('豆包中转');
  await inputs[2].setValue('http://14.103.26.228:3000/doubao/api/v3');
  expect((inputs[2].element as HTMLInputElement).checkValidity()).toBe(true);
  await form.trigger('submit'); await flushPromises();
  const saved = requests.mock.calls.find(([url, options]) => url.endsWith('/providers/doubao-proxy') && options?.method === 'PUT');
  expect(JSON.parse(String(saved?.[1]?.body)).endpoint).toBe('http://14.103.26.228:3000/doubao/api/v3');
});


it('filters model types and search, opens the side editor and cancels without saving', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers', true);
  const tab = (name: string) => wrapper.findAll('.infra-tabs button').find(button => button.text() === name)!;
  const table = () => wrapper.findAll('table').find(table => table.find('caption').text() === '模型列表')!;
  expect(tab('视频 API').attributes('aria-pressed')).toBe('true');
  expect(table().text()).toContain('已有视频名称');
  await wrapper.get('[aria-label="搜索模型"]').setValue('not-found');
  expect(table().text()).toContain('暂无匹配模型');
  await wrapper.get('[aria-label="搜索模型"]').setValue('existing-video');
  await table().findAll('button').find(button => button.text() === '编辑模型')!.trigger('click');
  const editor = wrapper.get('[aria-label="模型编辑"]');
  expect(editor.isVisible()).toBe(true);
  await editor.findAll('label').find(label => label.text().startsWith('显示名称'))!.get('input').setValue('取消的名称');
  await wrapper.get('[aria-label="关闭模型编辑"]').trigger('click');
  expect(editor.attributes('style')).toContain('display: none');
  expect(table().text()).toContain('已有视频名称');
  expect(requests.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
  await tab('图片 API').trigger('click');
  expect(table().text()).not.toContain('existing-video');
  await wrapper.findAll('button').find(button => button.text() === '＋ 新增模型')!.trigger('click');
  expect(wrapper.get('#ark-discovered-model').isVisible()).toBe(true);
  await wrapper.get('#ark-discovered-model').setValue('IMAGE:real-image-id');
  await wrapper.get('[data-testid="ark-use-model"]').trigger('click'); await flushPromises();
  expect(editor.isVisible()).toBe(true);
  expect(editor.text()).toContain('编辑图片模型');
  await tab('配置版本').trigger('click');
  expect(wrapper.findAll('h2').find(h => h.text() === '当前配置版本')!.isVisible()).toBe(true);
  expect(wrapper.get('.infra-layout').attributes('style')).toContain('display: none');
});

it('saves a nonempty resolution subset, trims image sizes, and preserves it when editing again', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers', true);
  await wrapper.get('#ark-discovered-model').setValue('IMAGE:real-image-id');
  await wrapper.get('[data-testid="ark-use-model"]').trigger('click'); await flushPromises();
  const form = wrapper.get('[data-testid="routing-model-form"]');
  await form.get('button[aria-label="支持规格 4K"]').trigger('click');
  await form.trigger('submit'); await flushPromises();
  const writes = () => requests.mock.calls.filter(([path, options]) => path.endsWith('/models/real-image-id') && options?.method === 'PUT');
  expect(JSON.parse(String(writes()[0][1]?.body)).capability).toMatchObject({ resolutions: ['2K'], sizes: { '2K': { '1:1': '2048x2048' } } });
  expect(JSON.parse(String(writes()[0][1]?.body)).capability.sizes).not.toHaveProperty('4K');
  const row = wrapper.findAll('tbody tr').find(item => item.text().includes('real-image-id'))!;
  await row.findAll('button').find(button => button.text() === '编辑模型')!.trigger('click'); await flushPromises();
  expect(form.get('button[aria-label="支持规格 4K"]').attributes('aria-pressed')).toBe('false');
  await form.get('button[aria-label="支持规格 2K"]').trigger('click');
  await form.trigger('submit'); await flushPromises();
  expect(writes()).toHaveLength(1);
});

it('saves model status and overrides, cancels a draft, and restores inherited policy with empty inputs', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers', true);
  const row = () => wrapper.findAll('tbody tr').find(item => item.text().includes('existing-video'))!;
  await row().findAll('button').find(button => button.text() === '编辑模型')!.trigger('click'); await flushPromises();
  const form = wrapper.get('[data-testid="routing-model-form"]');
  await form.get('[aria-label="模型启用状态"]').setValue(false);
  await form.get('#model-timeout').setValue('25');
  await form.get('#model-retries').setValue('0');
  await form.trigger('submit'); await flushPromises();
  const writes = () => requests.mock.calls.filter(([path, options]) => path.endsWith('/models/existing-video') && options?.method === 'PUT');
  expect(JSON.parse(String(writes()[0][1]?.body))).toMatchObject({ enabled: false, timeoutSeconds: 25, retryCount: 0, capability: { resolutions: ['720p'] } });
  expect(row().text()).toContain('停用');
  await form.get('#model-timeout').setValue('12');
  await form.findAll('button').find(button => button.text() === '取消')!.trigger('click');
  await row().findAll('button').find(button => button.text() === '编辑模型')!.trigger('click'); await flushPromises();
  expect(form.get<HTMLInputElement>('#model-timeout').element.value).toBe('25');
  await form.get('#model-timeout').setValue(''); await form.get('#model-retries').setValue('');
  await form.get('[aria-label="模型启用状态"]').setValue(true);
  await form.trigger('submit'); await flushPromises();
  expect(JSON.parse(String(writes()[1][1]?.body))).toMatchObject({ enabled: true, timeoutSeconds: null, retryCount: null });
});

it('allows saved model edits when the upstream model catalog is temporarily unavailable', async () => {
  const { wrapper, requests } = await open('admin', true, '/admin/providers', true, true);
  const row = wrapper.findAll('tbody tr').find(item => item.text().includes('existing-video'))!;
  await row.findAll('button').find(button => button.text() === '编辑模型')!.trigger('click'); await flushPromises();
  const form = wrapper.get('[data-testid="routing-model-form"]');
  expect(form.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(false);
  await form.get('#model-timeout').setValue('30');
  await form.trigger('submit'); await flushPromises();
  const write = requests.mock.calls.find(([path, options]) => path.endsWith('/models/existing-video') && options?.method === 'PUT');
  expect(JSON.parse(String(write?.[1]?.body))).toMatchObject({ enabled: true, timeoutSeconds: 30, capability: { resolutions: ['720p'], displayName: '已有视频名称' } });
});
