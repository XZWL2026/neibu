import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { axe } from 'vitest-axe';
import * as matchers from 'vitest-axe/matchers';
import { nextTick } from 'vue';
import Panel from './PromotionAttributionsPanel.vue';
import Search from '../components/AdminSearchSelect.vue';
import { adminAttributionAdapterKey, type AdminAttributionAdapter } from './httpPromotionAdminAdapters';
expect.extend(matchers); enableAutoUnmount(afterEach);
const binding = { version: 3, directInviterId: 'original-inviter', pointBeneficiaryId: 'points-owner', cashPromoterId: 'cash-owner', cashTier: 'LEVEL_2', reason: '人工核实', operator: 'admin-id', effectiveAt: '2026-09-15 10:20' };
function adapter(): AdminAttributionAdapter {
  return { records:vi.fn(async()=>({items:[{id:'record-1',userId:'user-a',user:'用户甲',previousInviter:'旧代理',inviter:'新代理',operator:'admin-id',reason:'业务调整',time:'2026-09-15 10:20'}],total:21})), search: vi.fn(async () => ({ items: [{ value: 'user-a', label: '用户甲' }, { value: 'user-b', label: '用户乙' }], total: 42 })), history: vi.fn(async () => ({ items: [binding], total: 3, currentVersion: 3 })), save: vi.fn(async () => {}) };
}
async function show(source = adapter()) {
  const wrapper = mount(Panel, { attachTo: document.body, global: { provide: { [adminAttributionAdapterKey as symbol]: source } } });
  await flushPromises();
  const controls=wrapper.findAllComponents(Search);const user=controls.find(c=>c.props('id')==='attribution-user')!, inviter=controls.find(c=>c.props('id')==='attribution-inviter')!;
  return { wrapper, user, inviter, source };
}
it('uses remote directory search and loads later pages without fixture identities', async () => {
  const { wrapper, user, source } = await show();
  expect(wrapper.text()).not.toMatch(/Nova|本地演示/);
  user.vm.$emit('query', '新用户'); await flushPromises();
  expect(source.search).toHaveBeenLastCalledWith('新用户', 0);
  user.vm.$emit('load-more'); await flushPromises();
  expect(source.search).toHaveBeenLastCalledWith('新用户', 20);
});
it('renders server-owned benefit attribution and sends the current version with the audit reason', async () => {
  const { wrapper, user, inviter, source } = await show();
  await user.setValue('user-a'); await flushPromises();
  expect(wrapper.get('[aria-label="推荐关系变更"]').text()).toContain('original-inviter'); expect(wrapper.text()).toContain('admin-id');
  await inviter.setValue('user-b'); await wrapper.get('textarea').setValue('核实归属'); await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(source.save).toHaveBeenCalledWith('user-a', 'user-b', 3, '核实归属');
  expect(source.history).toHaveBeenCalledTimes(2);
  expect(wrapper.findAll('[data-testid="attribution-version-user-a"]')).toHaveLength(1);
  expect(wrapper.find('select[aria-label="选择现金层级"]').exists()).toBe(false);
});
it('requires identity, non-self inviter, and a reason before writing', async () => {
  const { wrapper, user, inviter, source } = await show();
  await wrapper.get('form').trigger('submit'); expect(wrapper.get('[role="alert"]').text()).toContain('请选择被邀请用户');
  await user.setValue('user-a'); await flushPromises(); await inviter.setValue('user-a'); await wrapper.get('form').trigger('submit'); expect(wrapper.get('[role="alert"]').text()).toContain('其他用户');
  await inviter.setValue('user-b'); await wrapper.get('form').trigger('submit'); expect(wrapper.get('[role="alert"]').text()).toContain('必须填写');
  expect(source.save).not.toHaveBeenCalled();
});
it('keeps an invalid or conflicted server save as failure without appending local history', async () => {
  const source = adapter(); source.save = vi.fn().mockRejectedValue(new Error('cycle/version conflict'));
  const { wrapper, user, inviter } = await show(source);
  await user.setValue('user-a'); await flushPromises(); await inviter.setValue('user-b'); await wrapper.get('textarea').setValue('审计原因');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(wrapper.get('[role="alert"]').text()).toContain('保存失败');
  expect(wrapper.findAll('[data-testid="attribution-version-user-a"]')).toHaveLength(1);
});
it('does not let a previous user history response replace the current selection', async () => {
  let finish!: (value: Awaited<ReturnType<AdminAttributionAdapter['history']>>) => void;
  const source = adapter(); source.history = vi.fn().mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })).mockResolvedValue({ items: [], total: 0, currentVersion: 0 });
  const { wrapper, user } = await show(source); await user.setValue('user-a'); await nextTick(); await user.setValue('user-b'); await flushPromises();
  finish({ items: [binding], total: 3, currentVersion: 3 }); await flushPromises();
  expect(wrapper.text()).not.toContain('cash-owner'); expect(wrapper.get('[aria-label="推荐关系变更"]').text()).toContain('无');
});
it('does not write if the current relation cannot be read and keeps accessible controls', async () => {
  const source = adapter(); source.history = vi.fn().mockRejectedValue(new Error('offline'));
  const { wrapper, user, inviter } = await show(source); await user.setValue('user-a'); await flushPromises();
  await inviter.setValue('user-b'); await wrapper.get('textarea').setValue('原因'); await wrapper.get('form').trigger('submit');
  expect(source.save).not.toHaveBeenCalled();
  expect(wrapper.get('[role="alert"]').text()).toContain('尚未加载成功');
  expect(await axe(wrapper.element, { rules: { 'color-contrast': { enabled: false } } })).toHaveNoViolations();
});

it('keeps the chosen recommender when selecting the client and previews the change',async()=>{
 const {wrapper,user,inviter,source}=await show();await inviter.setValue('user-b');await user.setValue('user-a');await flushPromises();
 expect(inviter.props('modelValue')).toBe('user-b');expect(wrapper.get('[aria-label="推荐关系变更"]').text()).toContain('用户乙');
 await wrapper.get('textarea').setValue('业务调整');await wrapper.get('form').trigger('submit');await flushPromises();expect(source.save).toHaveBeenCalledWith('user-a','user-b',3,'业务调整');
});

it.each(['user', 'inviter'] as const)('excludes the selected %s from the other dropdown across remote searches and restores it on clear', async first => {
  const { wrapper, user, inviter } = await show();
  const selected = first === 'user' ? user : inviter;
  const other = first === 'user' ? inviter : user;
  await selected.setValue('user-a'); await flushPromises();
  for (const refresh of ['query', 'load-more'] as const) {
    if (refresh === 'query') other.vm.$emit('query', '用户甲');
    else other.vm.$emit('load-more');
    await flushPromises();
    expect(other.props('options').map(option => option.value)).not.toContain('user-a');
    expect(other.props('options').map(option => option.value)).toContain('user-b');
  }
  await wrapper.get(`[aria-label="${first === 'user' ? '清除客户' : '清除推荐人'}"]`).trigger('click');
  await flushPromises();
  expect(other.props('options').map(option => option.value)).toContain('user-a');
});

it('loads all adjustment records before selecting a client and supports pagination',async()=>{const {wrapper,source}=await show();expect(source.records).toHaveBeenCalledWith(0,10);expect(wrapper.get('[aria-label="调整记录"]').text()).toContain('旧代理');await wrapper.get('[aria-label="下一页调整记录"]').trigger('click');await flushPromises();expect(source.records).toHaveBeenLastCalledWith(1,10);});
