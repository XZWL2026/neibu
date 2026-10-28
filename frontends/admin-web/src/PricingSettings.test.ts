import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import PricingSettings from './PricingSettings.vue';
import { configureGateway, type ResponseBillingDomainPricingMediaPrice as Price } from './api';

enableAutoUnmount(afterEach);
afterEach(() => vi.unstubAllGlobals());
const price = (model: string, resolution: string, accountType = 'PERSONAL', type = 'VIDEO'): Price => ({
  model, resolution, accountType, type, unit: type === 'VIDEO' ? 'SECOND' : 'IMAGE', pointsPerUnit: null, yuanPerUnit: '1.2345', enabled: true
});
let routingModels = [{ modelId: 'video-model', providerId: 'ark', providerModel: 'real-upstream', capability: { type: 'VIDEO', displayName: '视频模型', resolutions: ['480p', '720p', '1080p'] } }, { modelId: 'image-model', providerId: 'ark', providerModel: 'real-image', capability: { type: 'IMAGE', displayName: '图片模型', resolutions: ['2k'] } }];
const requests = vi.fn<(path: string, options?: RequestInit) => Promise<Response>>();
let saved: { expectedVersion: number; prices: Price[] } | undefined;
beforeEach(() => {
  saved = undefined;
  configureGateway({});
  requests.mockReset().mockImplementation(async (path: string, options: RequestInit = {}) => {
    const body = path.endsWith('/auth/session') ? { authenticated: true, csrfToken: 'csrf' }
      : path.endsWith('/settings/runtime') ? { rechargePointsPerYuan4: '10.0000', rechargeRateVersion: 1 }
      : path.endsWith('/routing/models') ? routingModels
      : options.method === 'PUT' ? (() => { saved = JSON.parse(String(options.body)); return { managed: true, version: 8, prices: saved!.prices }; })()
      : { managed: true, version: 7, prices: [price('image-model', '2k', 'PERSONAL', 'IMAGE'), price('video-model', '480p'), price('video-model', '720p'), price('api-video', '1080p', 'API')] };
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', requests);
});
const button = (wrapper: ReturnType<typeof mount>, text: string) => wrapper.findAll('button').find(item => item.attributes('aria-label') === text || item.text() === text)!;

it('confirms deletion across filtered specifications, saves only the current account removal and preserves other edits', async () => {
  const original = requests.getMockImplementation()!;
  const records = [price('old-video','480p'), price('old-video','720p'), price('old-video','720p','API'), price('image-model','2k','PERSONAL','IMAGE')];
  requests.mockImplementation(async (path, options) => path.endsWith('/billing/media-prices') && options?.method !== 'PUT'
    ? new Response(JSON.stringify({managed:true,version:7,prices:records}), {headers:{'Content-Type':'application/json'}}) : original(path, options));
  const wrapper = mount(PricingSettings, {global:{stubs:{RouterLink:true}}}); await flushPromises();
  await wrapper.get('[aria-label="image-model 2k 金额（元）"]').setValue('5');
  await wrapper.get('[aria-label="搜索模型名称或规格"]').setValue('720p');
  await button(wrapper, '删除 old-video 的个人价格').trigger('click');
  expect(wrapper.get('.pricing-delete-confirmation').text()).toContain('全部 2 个规格');
  expect(saved).toBeUndefined();
  await button(wrapper, '取消删除').trigger('click');
  expect(wrapper.find('[data-model="old-video"]').exists()).toBe(true);
  await button(wrapper, '删除 old-video 的个人价格').trigger('click');
  await button(wrapper, '确认删除定价').trigger('click');
  expect(wrapper.find('[data-model="old-video"]').exists()).toBe(false);
  expect(saved).toBeUndefined();
  expect(wrapper.text()).toContain('点击“保存全部价格”后生效');
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
  expect(saved?.expectedVersion).toBe(7);
  expect(saved?.prices).toEqual([records[2], {...records[3],yuanPerUnit:'5'}]);
  expect(wrapper.find('[data-model="old-video"]').exists()).toBe(false);
});

it('restores staged deletions on reload and returns registered models to unpriced after saving', async () => {
  const wrapper = mount(PricingSettings, {global:{stubs:{RouterLink:true}}}); await flushPromises();
  await button(wrapper, '删除 视频模型 的个人价格').trigger('click');
  expect(wrapper.get('.pricing-delete-confirmation').text()).toContain('待定价');
  await button(wrapper, '确认删除定价').trigger('click');
  expect(wrapper.find('[data-model="video-model"]').exists()).toBe(false);
  await button(wrapper, '重新加载').trigger('click'); await flushPromises();
  expect(wrapper.find('[data-model="video-model"]').exists()).toBe(true);
  await button(wrapper, '删除 视频模型 的个人价格').trigger('click');
  await button(wrapper, '确认删除定价').trigger('click');
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
  expect(saved?.prices.some(p => p.model === 'video-model' && p.accountType === 'PERSONAL')).toBe(false);
  const group = wrapper.get('[data-model="video-model"]');
  expect(group.text()).toContain('待定价');
  expect(group.find('.pricing-delete-model').exists()).toBe(false);
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
  expect(saved?.prices.some(p => p.model === 'video-model' && p.accountType === 'PERSONAL')).toBe(false);
});

it('dismisses deletion confirmation on account switch and retains staged removals when saving conflicts', async () => {
  const wrapper = mount(PricingSettings, {global:{stubs:{RouterLink:true}}}); await flushPromises();
  await button(wrapper, '删除 视频模型 的个人价格').trigger('click');
  await button(wrapper, 'API 价格').trigger('click');
  expect(wrapper.find('.pricing-delete-confirmation').exists()).toBe(false);
  await button(wrapper, '删除 api-video 的API 价格').trigger('click');
  await button(wrapper, '确认删除定价').trigger('click');
  const original = requests.getMockImplementation()!;
  requests.mockImplementation(async (path, options) => options?.method === 'PUT' ? new Response('{}', {status:409}) : original(path,options));
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('请重新加载后编辑');
  expect(wrapper.find('[data-model="api-video"]').exists()).toBe(false);
  requests.mockImplementation(original);
  await button(wrapper, '重新加载').trigger('click'); await flushPromises();
  expect(wrapper.find('[data-model="api-video"]').exists()).toBe(true);
});

it('groups actual models into video/image resolution matrices and switches account pricing without losing edits', async () => {
  const wrapper = mount(PricingSettings, { global: { stubs: { RouterLink: true } } });
  await flushPromises();
  expect(wrapper.findAll('.pricing-model')).toHaveLength(2);
  expect(wrapper.findAll('.pricing-model')[0].findAll('.price-input')).toHaveLength(3);
  expect(wrapper.text()).toContain('视频生成模型');
  expect(wrapper.text()).toContain('图片生成模型');
  expect(wrapper.text()).not.toContain('积分/秒');
  await wrapper.findAll('.price-input')[0].setValue('0.1234');
  await button(wrapper, 'API 价格').trigger('click');
  expect(wrapper.findAll('.pricing-model')).toHaveLength(3);
  expect(wrapper.get('.pricing-model').attributes('data-model')).toBe('api-video');
  await wrapper.get('.price-input').setValue('9.8765');
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click');
  await flushPromises();
  expect(saved?.expectedVersion).toBe(7);
  expect(saved?.prices).toHaveLength(4);
  expect(saved?.prices.find(p => p.model === 'video-model' && p.resolution === '480p')?.yuanPerUnit).toBe('0.1234');
  expect(saved?.prices.find(p => p.accountType === 'API')?.yuanPerUnit).toBe('9.8765');
});

it('automatically adds missing resolutions with no price and preserves disabled prices on save', async () => {
  const wrapper = mount(PricingSettings, { global: { stubs: { RouterLink: true } } }); await flushPromises();
  const cells = wrapper.findAll('.pricing-model')[0].findAll('.pricing-cell');
  expect(cells[2].get('.price-input').element).toHaveProperty('value', '');
  expect(cells[2].get('.pricing-resolution').text()).toBe('1080p');
  await cells[2].get('.price-input').setValue('0');
  await cells[0].get('input[type="checkbox"]').setValue(false);
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
  expect(saved?.prices.find(p => p.resolution === '480p')?.enabled).toBe(false);
  expect(saved?.prices.find(p => p.resolution === '1080p' && p.accountType === 'PERSONAL')).toMatchObject({ yuanPerUnit: '0', unit: 'SECOND', model: 'video-model' });
  expect(saved?.prices).toHaveLength(5);
});

it('rejects clearing an existing price, while unpriced automatic entries do not block saving', async () => {
  const wrapper = mount(PricingSettings, { global: { stubs: { RouterLink: true } } }); await flushPromises();
  await wrapper.findAll('.price-input')[0].setValue('');
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
  expect(saved).toBeUndefined();
  expect(wrapper.text()).toContain('最多四位小数');
  await wrapper.findAll('.price-input')[0].setValue('1.0000');
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
  expect(saved?.prices).toHaveLength(4);
  expect(saved?.prices.some(p => p.pointsPerUnit === '')).toBe(false);
});

it('loads newly registered models on refresh, uses system identifiers and includes all account types', async () => {
  const wrapper = mount(PricingSettings, { global: { stubs: { RouterLink: true } } }); await flushPromises();
  const original = routingModels;
  try {
    routingModels = [...original, { modelId: 'custom-alias', providerId: 'ark', providerModel: 'upstream-real-version', capability: { type: 'VIDEO', displayName: '新模型名称', resolutions: ['4K'] } }];
    await button(wrapper, '重新加载').trigger('click'); await flushPromises();
    for (const tab of ['个人价格', '企业价格', 'API 价格']) {
      await button(wrapper, tab).trigger('click');
      const group = wrapper.findAll('.pricing-model').find(g => g.attributes('data-model') === 'custom-alias')!;
      expect(group.text()).toContain('新模型名称');
      expect(group.get('.pricing-resolution').text()).toBe('4K');
      expect(group.get<HTMLInputElement>('.price-input').element.value).toBe('');
      if (tab === '企业价格') await group.get('.price-input').setValue('0.4321');
    }
    await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
    expect(saved?.prices.filter(p => p.model === 'custom-alias')).toEqual([{ model: 'custom-alias', resolution: '4K', accountType: 'ENTERPRISE', type: 'VIDEO', unit: 'SECOND', pointsPerUnit: null, yuanPerUnit: '0.4321', enabled: true }]);
    expect(saved?.prices.some(p => p.model === 'upstream-real-version')).toBe(false);
    expect(saved?.prices.some(p => p.model === 'api-video')).toBe(true);
  } finally { routingModels = original; }
});

it('blocks saving when the model directory fails to load and can retry', async () => {
  const original = requests.getMockImplementation()!;
  requests.mockImplementation(async (path, options) => String(path).endsWith('/routing/models') ? new Response('{}', { status: 503 }) : original(path, options));
  const wrapper = mount(PricingSettings, { global: { stubs: { RouterLink: true } } }); await flushPromises();
  expect(wrapper.get<HTMLButtonElement>('[data-testid="admin-save-pricing"]').element.disabled).toBe(true);
  expect(wrapper.text()).toContain('加载价格或模型失败');
  requests.mockImplementation(original);
  await button(wrapper, '重新加载').trigger('click'); await flushPromises();
  expect(wrapper.get<HTMLButtonElement>('[data-testid="admin-save-pricing"]').element.disabled).toBe(false);
});

it('displays yuan without trailing zeros and preserves untouched legacy point prices', async () => {
  const original=requests.getMockImplementation()!;
  requests.mockImplementation(async (path, options) => path.endsWith('/billing/media-prices') && options?.method !== 'PUT'
    ? new Response(JSON.stringify({managed:true,version:7,prices:[
      {...price('video-model','480p'),pointsPerUnit:'12.3456',yuanPerUnit:null},
      {...price('video-model','720p'),yuanPerUnit:'0.5000'},
      {...price('image-model','2k','PERSONAL','IMAGE'),yuanPerUnit:'10.0000'}
    ]}),{headers:{'Content-Type':'application/json'}}) : original(path,options));
  const wrapper=mount(PricingSettings, { global: { stubs: { RouterLink: true } } }); await flushPromises();
  const inputs=wrapper.findAll<HTMLInputElement>('.price-input');
  expect(inputs[0].element.value).toBe('1.2345');
  expect(inputs[1].element.value).toBe('0.5');
  expect(inputs.at(-1)!.element.value).toBe('10');
  await inputs[1].setValue('0.6000'); await inputs[1].trigger('blur');
  expect(inputs[1].element.value).toBe('0.6');
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
  expect(saved?.prices[0]).toMatchObject({pointsPerUnit:'12.3456',yuanPerUnit:null});
  expect(saved?.prices[1]).toMatchObject({pointsPerUnit:null,yuanPerUnit:'0.6'});
  expect(saved?.prices[1]).not.toHaveProperty('inputPrice');
});

it('preserves a valid legacy point price even when its displayed yuan amount exceeds new input limits', async () => {
  const original=requests.getMockImplementation()!;
  requests.mockImplementation(async (path, options) => path.endsWith('/settings/runtime')
    ? new Response(JSON.stringify({rechargePointsPerYuan4:'0.0001',rechargeRateVersion:1}),{headers:{'Content-Type':'application/json'}})
    : path.endsWith('/billing/media-prices') && options?.method !== 'PUT'
      ? new Response(JSON.stringify({managed:true,version:7,prices:[{...price('video-model','480p'),pointsPerUnit:'100000000.0000',yuanPerUnit:null}]}),{headers:{'Content-Type':'application/json'}})
      : original(path,options));
  const wrapper=mount(PricingSettings, { global: { stubs: { RouterLink: true } } }); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('.price-input').element.value).toBe('1000000000000');
  await wrapper.get('input[type="checkbox"]').setValue(false);
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
  expect(saved?.prices[0]).toMatchObject({pointsPerUnit:'100000000.0000',yuanPerUnit:null,enabled:false});
});


it('filters real specifications and edits the selected price in the side panel without dropping other accounts', async () => {
  const wrapper = mount(PricingSettings, { global: { stubs: { RouterLink: true } } }); await flushPromises();
  await wrapper.get('[aria-label="搜索模型名称或规格"]').setValue('720p');
  expect(wrapper.findAll('.pricing-cell')).toHaveLength(1);
  await wrapper.get('.pricing-cell').trigger('click');
  expect(wrapper.get('.pricing-detail').text()).toContain('720p');
  await wrapper.get('[aria-label="编辑金额（元）"]').setValue('2.9600');
  await wrapper.get('.pricing-detail form').trigger('submit'); await flushPromises();
  expect(saved?.prices.find(p=>p.resolution==='720p')?.yuanPerUnit).toBe('2.96');
  expect(saved?.prices.some(p=>p.accountType==='API')).toBe(true);
  expect(saved?.prices.some(p=>p.resolution==='480p')).toBe(true);
  await wrapper.get('[aria-label="编辑金额（元）"]').setValue('8');
  await wrapper.get('.pricing-detail form').trigger('keydown', {key:'Escape'});
  expect(wrapper.get<HTMLInputElement>('[aria-label="编辑金额（元）"]').element.value).toBe('2.96');
});

it('advances only after a successful save and cancels a changed enabled state locally', async () => {
  const wrapper = mount(PricingSettings, { global: { stubs: { RouterLink: true } } }); await flushPromises();
  const panel = () => wrapper.get('.pricing-detail');
  await panel().get('[aria-label="编辑金额（元）"]').setValue('2.5');
  await panel().get('form').trigger('submit'); await flushPromises();
  expect(panel().get('.pricing-specifications [aria-pressed="true"]').text()).toBe('720p');
  await panel().get('[aria-label="启用所选规格"]').setValue(false);
  await panel().get('form').trigger('keydown', {key:'Escape'});
  expect(panel().get<HTMLInputElement>('[aria-label="启用所选规格"]').element.checked).toBe(true);
  const original = requests.getMockImplementation()!;
  requests.mockImplementation(async (path, options) => options?.method === 'PUT' ? new Response('{}', {status:409}) : original(path, options));
  await panel().get('[aria-label="编辑金额（元）"]').setValue('3.7');
  await panel().get('form').trigger('submit'); await flushPromises();
  expect(panel().get('.pricing-specifications [aria-pressed="true"]').text()).toBe('720p');
  expect(panel().get<HTMLInputElement>('[aria-label="编辑金额（元）"]').element.value).toBe('3.7');
  expect(wrapper.text()).toContain('请重新加载后编辑');
  requests.mockImplementation(original);
  await panel().findAll('.pricing-specifications button')[2].trigger('click');
  await panel().get('[aria-label="编辑金额（元）"]').setValue('4');
  await panel().get('form').trigger('submit'); await flushPromises();
  expect(panel().get('.pricing-detail-model strong').text()).toBe('图片模型');
});


it('keeps the edited specification selected when disabling it under an enabled-only filter', async () => {
  const wrapper = mount(PricingSettings, { global: { stubs: { RouterLink: true } } }); await flushPromises();
  await wrapper.get('[aria-label="价格状态"]').setValue('enabled');
  const panel = wrapper.get('.pricing-detail');
  await panel.get('[aria-label="启用所选规格"]').setValue(false);
  expect(panel.get('.pricing-specifications [aria-pressed="true"]').text()).toBe('480p');
  await panel.get('form').trigger('keydown', {key:'Escape'});
  expect(panel.get<HTMLInputElement>('[aria-label="启用所选规格"]').element.checked).toBe(true);
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
  expect(saved?.prices.find(p=>p.accountType==='PERSONAL'&&p.resolution==='480p')?.enabled).toBe(true);
});

it('opens per-user special pricing in place for personal, enterprise and API users', async()=>{
 const wrapper=mount(PricingSettings,{global:{stubs:{RouterLink:true,UserSpecialPrices:{template:'<div data-testid="special-user-editor">选择定价用户</div>'}}}});await flushPromises();
 await button(wrapper,'特殊客户价格').trigger('click');
 expect(wrapper.get('[data-testid="special-user-editor"]').text()).toContain('选择定价用户');
 expect(wrapper.find('.pricing-detail').exists()).toBe(false);
 expect(wrapper.find('[data-testid="admin-save-pricing"]').exists()).toBe(false);
 await button(wrapper,'个人价格').trigger('click');expect(wrapper.find('.pricing-detail').exists()).toBe(true);
});


it('configures enhancement seconds prices independently of generation model prices', async () => {
  const wrapper = mount(PricingSettings, { global: { stubs: { RouterLink: true } } });
  await flushPromises();
  await wrapper.get('[aria-label="2K 超分增强金额（元/秒）"]').setValue('0.25');
  await wrapper.get('[data-testid="admin-save-pricing"]').trigger('click'); await flushPromises();
  expect(saved?.prices).toContainEqual({type:'ENHANCEMENT',accountType:'PERSONAL',model:'video-enhancement',resolution:'2K',unit:'SECOND',pointsPerUnit:null,yuanPerUnit:'0.25',enabled:true});
  expect(saved?.prices).toContainEqual(price('video-model','720p'));
  expect(saved?.prices.some(p=>p.type==='ENHANCEMENT' && p.resolution==='720p')).toBe(false);
});
