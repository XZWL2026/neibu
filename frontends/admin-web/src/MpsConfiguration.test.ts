import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import MpsConfiguration from './MpsConfiguration.vue';
import { configureGateway, refreshSession, type ResponseMediaInterfacesHttpMpsConfigurationControllerView as View } from './api';
enableAutoUnmount(afterEach);
afterEach(() => vi.unstubAllGlobals());
const path = '/api/v1/admin/media/mps-configuration';
const template = (id: string, outputResolution: string, input: string, definitionId: number) => ({ id, outputResolution, acceptedInputResolutions: [input], enabled: true, bitrateProfileId: null, definitionId, scheduleIds: {} });
async function open(overrides: Partial<View> = {}) {
  let value: View = { version: 1, secretId: 'saved-id', secretKeyConfigured: true, region: 'ap-beijing', bucket: 'bucket-123', bucketRegion: 'ap-guangzhou', outputDir: 'enhance/', template720p: 11, template1080p: 12, template4k: 13, source: 'DATABASE', policyConfigured: true,
    enhancementTemplates: [template('hd', '720p', '480p', 11), template('fullhd', '1080p', '720p', 12), template('ultra', '4K', '1080p', 13)], bitrateProfiles: [{ id: 'delivery', name: '交付码率', videoBitrateKbps: 12000 }], ...overrides };
  const state = { conflict: false, probeSuccess: true, writes: [] as any[], probes: [] as any[] };
  const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
  vi.stubGlobal('fetch', vi.fn(async (url: string, options: RequestInit = {}) => {
    if (url.endsWith('/auth/session')) return response({ authenticated: true, roles: ['admin'], primary: true, csrfToken: 'csrf-test', user: { id: 'admin', username: 'administrator' } });
    if (url === `${path}/test`) {
      state.probes.push(JSON.parse(String(options.body)));
      return response({ version: value.version, success: state.probeSuccess, checkedAt: '2026-09-21T00:00:00Z', durationMs: 25, width: 1920, height: 1080, definitions: [12], videoBitrateKbps: 12000, message: state.probeSuccess ? '配置校验通过' : '配置校验失败' }, state.conflict ? 409 : 200);
    }
    if (url !== path) throw new Error('unexpected request');
    if (options.method === 'PUT') {
      const write = JSON.parse(String(options.body)); state.writes.push(write);
      if (state.conflict) return response({}, 409);
      const { expectedVersion, secretKey, ...fields } = write;
      value = { ...value, ...fields, version: value.version + 1, secretKeyConfigured: true, policyConfigured: write.enhancementTemplates != null || value.policyConfigured };
    }
    return response(value);
  }));
  configureGateway({}); await refreshSession();
  const page = mount(MpsConfiguration); await flushPromises();
  return { page, state };
}
it('keeps global, template and bitrate drafts isolated, clears saved secrets and preserves conflicts', async () => {
  const { page, state } = await open();
  expect(page.emitted('version')?.[0]).toEqual([{ version: 1, source: 'DATABASE' }]);
  expect(page.get('.mps-execution').attributes('open')).toBeUndefined();
  expect(page.get('input[name=enabled]').attributes('role')).toBe('switch');
  await page.get('[aria-label="编辑模板 hd"]').trigger('click');
  await page.get('input[name=definitionId]').setValue(101);
  await page.get('[aria-label="取消编辑模板"]').trigger('click');
  expect(state.writes).toHaveLength(0);
  await page.get('[aria-label="编辑模板 hd"]').trigger('click');
  expect(page.get<HTMLInputElement>('input[name=definitionId]').element.value).toBe('11');
  await page.get('input[name=secretId]').setValue('pending-id');
  await page.get('input[name=secretKey]').setValue('new-secret');
  await page.get('input[name=definitionId]').setValue(101);
  await page.get('form[aria-label="增强模板配置"]').trigger('submit'); await flushPromises();
  expect(state.writes[0]).toMatchObject({ expectedVersion: 1, secretId: 'saved-id', secretKey: '' });
  expect(state.writes[0].enhancementTemplates[0].definitionId).toBe(101);
  expect(page.get<HTMLInputElement>('input[name=secretKey]').element.value).toBe('new-secret');
  await page.get('[aria-label="编辑模板 fullhd"]').trigger('click');
  await page.get('input[name=definitionId]').setValue(202);
  await page.setProps({ section: 'BITRATE' });
  await page.get('[aria-label="编辑码率 delivery"]').trigger('click');
  await page.get('input[name=videoBitrateKbps]').setValue(16000);
  await page.get('form[aria-label="码率配置"]').trigger('submit'); await flushPromises();
  expect(state.writes[1]).toMatchObject({ expectedVersion: 2, secretId: 'saved-id', secretKey: '' });
  expect(state.writes[1].enhancementTemplates[1].definitionId).toBe(12);
  expect(state.writes[1].bitrateProfiles[0].videoBitrateKbps).toBe(16000);
  await page.setProps({ section: 'MPS' });
  expect(page.get<HTMLInputElement>('input[name=definitionId]').element.value).toBe('202');
  await page.get('form[aria-label="全局连接配置"]').trigger('submit'); await flushPromises();
  expect(state.writes[2]).toMatchObject({ expectedVersion: 3, secretId: 'pending-id', secretKey: 'new-secret' });
  expect(state.writes[2].enhancementTemplates[1].definitionId).toBe(12);
  expect(page.get<HTMLInputElement>('input[name=secretKey]').element.value).toBe('');
  state.conflict = true;
  await page.get('form[aria-label="增强模板配置"]').trigger('submit'); await flushPromises();
  expect(page.text()).toContain('配置已被其他管理员修改');
  expect(page.get<HTMLInputElement>('input[name=definitionId]').element.value).toBe('202');
  expect(page.emitted('version')).toHaveLength(4);
});
it.each([['1080p', '720p'], ['2K', '1080p']])('creates, updates and deletes %s templates with schedules and source inputs', async (target, source) => {
  const { page, state } = await open({ enhancementTemplates: [] });
  await page.get('[aria-label="新增增强模板"]').trigger('click');
  expect(page.get('.mps-execution').attributes('open')).toBeDefined();
  await page.get('input[name=templateId]').setValue('fullhd');
  await page.get('select[name=outputResolution]').setValue(target);
  await page.get(`input[value="${source}"]`).setValue(true);
  await page.get('select[name=executionMode]').setValue('SCHEDULE');
  for (const [shape, id] of Object.entries({ WIDE: 108307, SQUARE: 108308, STANDARD: 108309, ULTRAWIDE: 108310 })) await page.get(`input[name="${shape}"]`).setValue(id);
  await page.get('select[name=bitrateProfileId]').setValue('delivery');
  await page.get('form[aria-label="增强模板配置"]').trigger('submit'); await flushPromises();
  expect(state.writes[0].enhancementTemplates[0]).toMatchObject({ id: 'fullhd', outputResolution: target, enabled: true, acceptedInputResolutions: expect.arrayContaining([source]), bitrateProfileId: 'delivery', definitionId: null, scheduleIds: { WIDE: 108307, SQUARE: 108308, STANDARD: 108309, ULTRAWIDE: 108310 } });
  await page.get('[aria-label="编辑模板 fullhd"]').trigger('click');
  await page.get('input[name=enabled]').setValue(false);
  await page.get('form[aria-label="增强模板配置"]').trigger('submit'); await flushPromises();
  expect(state.writes[1].enhancementTemplates[0].enabled).toBe(false);
  await page.get('[aria-label="删除模板 fullhd"]').trigger('click'); await flushPromises();
  expect(state.writes[2].enhancementTemplates).toEqual([]);
});
it('uses the saved version for real probes and never presents failure as a successful check', async () => {
  const { page, state } = await open();
  await page.get('[aria-label="编辑模板 fullhd"]').trigger('click');
  await page.get('input[name=definitionId]').setValue(999);
  await page.get('[aria-label="校验当前模板配置"]').trigger('click'); await flushPromises();
  expect(state.probes).toEqual([{ expectedVersion: 1, templateId: 'fullhd', aspectRatio: '16:9' }]);
  expect(state.writes).toHaveLength(0);
  expect(page.text()).toContain('配置校验通过'); expect(page.text()).toContain('1920 × 1080');
  state.probeSuccess = false;
  await page.get('[aria-label="校验当前模板配置"]').trigger('click'); await flushPromises();
  expect(page.text()).toContain('配置校验失败'); expect(page.text()).not.toContain('配置校验通过');
  state.conflict = true;
  await page.get('[aria-label="校验当前模板配置"]').trigger('click'); await flushPromises();
  expect(page.text()).toContain('配置已被其他管理员修改');
});
it('creates bitrate profiles, cancels drafts and prevents deleting a referenced profile', async () => {
  const linked = { ...template('fullhd', '1080p', '720p', 12), bitrateProfileId: 'delivery' };
  const { page, state } = await open({ enhancementTemplates: [linked] });
  await page.setProps({ section: 'BITRATE' });
  expect(page.get('[aria-label="删除码率 delivery"]').attributes('disabled')).toBeDefined();
  await page.get('[aria-label="新增码率配置"]').trigger('click');
  await page.get('input[name=profileId]').setValue('new-profile');
  await page.get('input[name=profileName]').setValue('小文件');
  await page.get('input[name=videoBitrateKbps]').setValue(8000);
  await page.get('[aria-label="取消编辑码率"]').trigger('click'); expect(state.writes).toHaveLength(0);
  await page.get('[aria-label="新增码率配置"]').trigger('click');
  await page.get('input[name=profileId]').setValue('new-profile');
  await page.get('input[name=profileName]').setValue('小文件');
  await page.get('input[name=videoBitrateKbps]').setValue(8000);
  await page.get('form[aria-label="码率配置"]').trigger('submit'); await flushPromises();
  expect(state.writes[0].bitrateProfiles[1]).toEqual({ id: 'new-profile', name: '小文件', videoBitrateKbps: 8000 });
  await page.get('[aria-label="删除码率 new-profile"]').trigger('click'); await flushPromises();
  expect(state.writes[1].bitrateProfiles.map((p: any) => p.id)).toEqual(['delivery']);
});
it('saves initial credentials without requiring fake legacy template IDs', async () => {
  const { page, state } = await open({ version: 0, secretId: '', secretKeyConfigured: false, region: '', bucket: '', bucketRegion: '', outputDir: '', template720p: 0, template1080p: 0, template4k: 0, enhancementTemplates: [], bitrateProfiles: [], policyConfigured: false });
  for (const [name, value] of Object.entries({ secretId: 'new-id', secretKey: 'new-key', region: 'ap-beijing', bucket: 'bucket-123', bucketRegion: 'ap-guangzhou', outputDir: 'enhance/' })) await page.get(`input[name="${name}"]`).setValue(value);
  await page.get('form[aria-label="全局连接配置"]').trigger('submit'); await flushPromises();
  expect(state.writes[0]).toMatchObject({ expectedVersion: 0, secretKey: 'new-key', enhancementTemplates: [], bitrateProfiles: [], template720p: 0 });
  expect(page.get<HTMLInputElement>('input[name=secretKey]').element.value).toBe('');
});
it('preserves real environment schedule projections when saving global settings and bitrate profiles', async () => {
  const schedules = { WIDE: 108307, SQUARE: 108308, STANDARD: 108309, ULTRAWIDE: 108310 };
  const projection = { ...template('mps-1080p', '1080p', '720p', 12), definitionId: null, scheduleIds: schedules };
  const { page, state } = await open({ source: 'ENVIRONMENT', version: 0, policyConfigured: false, template720p: 0, template1080p: 0, template4k: 0, enhancementTemplates: [projection] });
  await page.get('form[aria-label="全局连接配置"]').trigger('submit'); await flushPromises();
  expect(state.writes[0].enhancementTemplates).toEqual([projection]);
  await page.setProps({ section: 'BITRATE' });
  await page.get('[aria-label="编辑码率 delivery"]').trigger('click');
  await page.get('input[name=videoBitrateKbps]').setValue(18000);
  await page.get('form[aria-label="码率配置"]').trigger('submit'); await flushPromises();
  expect(state.writes[1].enhancementTemplates).toEqual([projection]);
});
it('rejects duplicate enabled outputs, missing source inputs, incomplete schedules and invalid bitrate drafts before writing', async () => {
  const { page, state } = await open();
  await page.get('[aria-label="新增增强模板"]').trigger('click');
  await page.get('input[name=templateId]').setValue('second');
  await page.get('input[name=definitionId]').setValue(21);
  await page.get('form[aria-label="增强模板配置"]').trigger('submit'); await flushPromises();
  expect(page.text()).toContain('每个输出规格只能启用一个模板');
  await page.get('input[name=enabled]').setValue(false);
  await page.get('input[value="480p"]').setValue(false);
  await page.get('form[aria-label="增强模板配置"]').trigger('submit'); await flushPromises();
  expect(page.text()).toContain('选择输出规格对应的输入规格');
  await page.get('input[value="480p"]').setValue(true);
  await page.get('select[name=executionMode]').setValue('SCHEDULE');
  await page.get('input[name=WIDE]').setValue(108307);
  await page.get('form[aria-label="增强模板配置"]').trigger('submit'); await flushPromises();
  expect(page.text()).toContain('完整的四组编排');
  await page.setProps({ section: 'BITRATE' });
  await page.get('[aria-label="编辑码率 delivery"]').trigger('click');
  await page.get('input[name=videoBitrateKbps]').setValue(127);
  await page.get('form[aria-label="码率配置"]').trigger('submit'); await flushPromises();
  expect(page.text()).toContain('128–100000 Kbps');
  expect(state.writes).toHaveLength(0);
});
it.each(['edit', 'create', 'delete'])('excludes unbound legacy projections when performing template %s', async action => {
  const hd = template('hd', '720p', '480p', 11);
  const fullhd = { ...template('fullhd', '1080p', '720p', 0), definitionId: null };
  const ultra = { ...template('ultra', '4K', '1080p', 0), definitionId: null };
  const { page, state } = await open({ policyConfigured: false, template1080p: 0, template4k: 0, enhancementTemplates: [hd, fullhd, ultra] });
  if (action === 'delete') await page.get('[aria-label="删除模板 hd"]').trigger('click');
  else {
    await page.get(action === 'edit' ? '[aria-label="编辑模板 fullhd"]' : '[aria-label="新增增强模板"]').trigger('click');
    if (action === 'create') {
      await page.get('input[name=templateId]').setValue('new-fullhd');
      await page.get('select[name=outputResolution]').setValue('1080p');
      await page.get('input[value="720p"]').setValue(true);
    }
    await page.get('input[name=definitionId]').setValue(22);
    await page.get('form[aria-label="增强模板配置"]').trigger('submit');
  }
  await flushPromises();
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].enhancementTemplates).toHaveLength(action === 'delete' ? 0 : 2);
  if (action !== 'delete') {
    expect(state.writes[0].enhancementTemplates).toContainEqual(hd);
    expect(state.writes[0].enhancementTemplates.find((item: any) => item.id === (action === 'edit' ? 'fullhd' : 'new-fullhd'))).toMatchObject({ definitionId: 22 });
  }
});
it('preserves every managed policy row while saving an unrelated bitrate profile', async () => {
  const invalid = { ...template('fullhd', '1080p', '720p', 0), definitionId: null };
  const { page, state } = await open({ policyConfigured: true, enhancementTemplates: [invalid] });
  await page.setProps({ section: 'BITRATE' });
  await page.get('[aria-label="编辑码率 delivery"]').trigger('click');
  await page.get('form[aria-label="码率配置"]').trigger('submit'); await flushPromises();
  expect(state.writes[0].enhancementTemplates).toEqual([invalid]);
});

it.each([
  { name: 'confirmed four-shape 4K schedules', definitionId: null, scheduleIds: { WIDE: 108311, SQUARE: 108312, STANDARD: 108313, ULTRAWIDE: 108314 }, eightK: true },
  { name: 'custom 4K schedule', definitionId: null, scheduleIds: { WIDE: 999, SQUARE: 108312, STANDARD: 108313, ULTRAWIDE: 108314 }, eightK: false },
  { name: 'single 4K template', definitionId: 13, scheduleIds: {} as Record<string, number>, eightK: false },
])('shows the complete known 8K intermediate step without inventing it for $name', async ({ definitionId, scheduleIds, eightK }) => {
  const { page, state } = await open({ enhancementTemplates: [{ ...template('ultra', '4K', '1080p', 13), definitionId, scheduleIds }] });
  const steps = page.get('.mps-flow').findAll('li').map(step => step.text());
  expect(steps).toHaveLength(3);
  expect(steps[0]).toContain('输入视频1080p');
  if (eightK) {
    expect(steps[1]).toContain('超分增强8K');
    expect(steps[1]).toContain('108311 / 108312 / 108313 / 108314');
    expect(steps[2]).toContain('缩放输出4K');
  } else {
    expect(steps[1]).not.toContain('8K');
    expect(steps[2]).toContain('输出视频4K');
  }
  expect(state.writes).toHaveLength(0);
});


it.each([
  { name: 'confirmed 1080p schedules', wide: 108307, intermediate: true },
  { name: 'custom 1080p schedule', wide: 999, intermediate: false },
])('shows the 2K stage only for $name', async ({ wide, intermediate }) => {
  const { page, state } = await open({ enhancementTemplates: [{ ...template('fullhd', '1080p', '720p', 12), definitionId: null,
    scheduleIds: { WIDE: wide, SQUARE: 108308, STANDARD: 108309, ULTRAWIDE: 108310 } }] });
  const steps = page.get('.mps-flow').findAll('li').map(step => step.text());
  expect(steps).toHaveLength(3);
  expect(steps[0]).toContain('输入视频720p');
  if (intermediate) {
    expect(steps[1]).toContain('超分增强2K');
    expect(steps[1]).toContain('108307 / 108308 / 108309 / 108310');
    expect(steps[2]).toContain('缩放输出1080p');
  } else {
    expect(steps[1]).not.toContain('2K');
    expect(steps[2]).toContain('输出视频1080p');
  }
  expect(state.writes).toHaveLength(0);
});

it('presents known 720p schedules as one enhancement step while retaining editable schedule IDs', async () => {
  const scheduleIds = { WIDE: 108412, SQUARE: 108413, STANDARD: 108414, ULTRAWIDE: 108415 };
  const { page, state } = await open({ enhancementTemplates: [{ ...template('hd', '720p', '480p', 11), definitionId: null, scheduleIds }] });
  expect(page.get('.mps-template-card').text()).toContain('处理方式单步增强（按画幅）');
  const flow = page.get('.mps-flow');
  expect(flow.findAll('li').map(step => step.text())).toEqual(['▷输入视频480p', '↗单步超分720p', '▤输出视频720p · 模板码率']);
  for (const [shape, id] of Object.entries(scheduleIds)) {
    expect(flow.text()).not.toContain(String(id));
    expect(page.get<HTMLInputElement>(`input[name="${shape}"]`).element.value).toBe(String(id));
  }
  expect(state.writes).toHaveLength(0);
});
