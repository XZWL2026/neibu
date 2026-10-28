import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils';
import { afterEach, expect, it } from 'vitest';
import { axe } from 'vitest-axe';
import AdminSearchSelect from './AdminSearchSelect.vue';
enableAutoUnmount(afterEach);
const options = [
  { value: 'one', label: '林＊', detail: '138****8031', search: '林＊ 138****8031' },
  { value: 'two', label: '星河企业', detail: '139****2046', search: '星河企业 139****2046' }
];
it('searches inside the popup, keeps the selection while searching and emits only a picked candidate', async () => {
  const wrapper = mount(AdminSearchSelect, { props: { id:'user',label:'选择用户',modelValue:'one',options }, attachTo:document.body });
  const trigger = wrapper.get('[role="combobox"]');
  await trigger.trigger('click');await flushPromises();
  const search = wrapper.get('input[aria-label="搜索选择用户"]');
  expect(search.isVisible()).toBe(true);
  await search.setValue('2046');await flushPromises();
  const visibleOptions = wrapper.findAll('[role="option"]').filter(o=>o.isVisible());
  expect(visibleOptions.map(o=>o.text())).toEqual(['星河企业139****2046']);
  expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  await visibleOptions[0]!.trigger('click');await flushPromises();
  expect(wrapper.emitted('update:modelValue')).toEqual([['two']]);
  expect(wrapper.get('[role="combobox"]').attributes('aria-expanded')).toBe('false');
});
it('shows empty results, handles Enter and Escape, restores candidates on reopen and remains accessible', async () => {
  const wrapper = mount(AdminSearchSelect,{props:{id:'inviter',label:'选择邀请人',modelValue:'',options},attachTo:document.body});
  await wrapper.get('[role="combobox"]').trigger('click');await flushPromises();
  const search=wrapper.get('input[aria-label="搜索选择邀请人"]');
  await search.setValue('9999');await search.trigger('keydown',{key:'Enter'});await flushPromises();
  expect(wrapper.text()).toContain('未找到匹配用户');expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  await search.trigger('keydown',{key:'Escape'});await flushPromises();expect(wrapper.get('[role="combobox"]').attributes('aria-expanded')).toBe('false');
  await wrapper.get('[role="combobox"]').trigger('click');await flushPromises();
  expect(wrapper.findAll('[role="option"]').filter(o=>o.isVisible())).toHaveLength(2);
  expect((await axe(wrapper.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
});

it('associates errors with the actual focusable combobox and clears them after correction',async()=>{
 const wrapper=mount(AdminSearchSelect,{props:{id:'user-error',label:'选择用户',modelValue:'',options},attachTo:document.body});
 await wrapper.setProps({invalid:true,describedBy:'attribution-error'});
 wrapper.vm.focus();const input=wrapper.get('[role="combobox"]');
 expect(document.activeElement).toBe(input.element);expect(input.attributes('aria-invalid')).toBe('true');expect(input.attributes('aria-describedby')).toBe('attribution-error');
 await wrapper.setProps({invalid:false,describedBy:undefined});expect(input.attributes('aria-invalid')).toBe('false');expect(input.attributes('aria-describedby')).toBeUndefined();
});

it('queries remote users and loads later matches without filtering just the current options',async()=>{
 const wrapper=mount(AdminSearchSelect,{props:{id:'remote',label:'业务用户',modelValue:'one',options,remote:true,hasMore:true},attachTo:document.body});
 await wrapper.get('[role="combobox"]').trigger('click');await flushPromises();
 const input=wrapper.get('input[aria-label="搜索业务用户"]');await input.setValue('13812348031');

 expect(wrapper.emitted('query')?.at(-1)).toEqual(['13812348031']);
 expect(wrapper.findAll('[role="option"]').filter(o=>o.isVisible())).toHaveLength(2);
 await wrapper.get('button[aria-label="加载更多业务用户"]').trigger('click');
 expect(wrapper.emitted('load-more')).toHaveLength(1);
 await wrapper.setProps({loading:true});expect(wrapper.get('button[aria-label="加载更多业务用户"]').attributes('disabled')).toBeDefined();
 expect(wrapper.emitted('update:modelValue')).toBeUndefined();
});
