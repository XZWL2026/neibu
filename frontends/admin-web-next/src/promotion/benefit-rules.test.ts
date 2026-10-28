import {afterEach,expect,it,vi} from 'vitest';
import {enableAutoUnmount,flushPromises,mount} from '@vue/test-utils';
import {nextTick} from 'vue';
import {axe} from 'vitest-axe';
import Panel from './PromotionBenefitSettingsPanel.vue';
import AdminSearchSelect from '../components/AdminSearchSelect.vue';
import {adminBenefitSettingsAdapterKey} from './localBenefitSettingsFixtures';
enableAutoUnmount(afterEach);
const initial=()=>({version:'V1',effectiveAt:'2026-09-21 10:00',operator:'admin',reason:'配置',global:{invitationPointsRate:'0.05',defaultLevel1CashRate:'0.1',defaultLevel2CashRate:'0'},promoters:[{id:'agent',name:'星辰',phone:'138****8043',level1CashRate:'0.15',level2CashRate:null,effectiveAt:'2026-09-20 10:00',enabled:true}],customers:[{id:'customer',name:'张三',phone:'138****5568',pointsRate:'0.1',effectiveAt:'2026-09-19 10:00',enabled:true}]});
async function show(overrides:Record<string,unknown>={}) {
 let current=initial();
 const api={load:vi.fn(async()=>current),loadHistory:vi.fn(async()=>({count:0,records:[]})),queryHistory:vi.fn(async()=>({count:0,records:[]})),saveGlobalRate:vi.fn(async()=>current),savePromoterRate:vi.fn(async(id:string,level:string,rate:string|null)=>{current={...current,promoters:current.promoters.map(p=>p.id===id?{...p,[level]:rate}:p)};return current;}),saveCustomerRate:vi.fn(async(id:string,rate:string|null)=>{current={...current,customers:rate===null?current.customers.filter(c=>c.id!==id):[...current.customers.filter(c=>c.id!==id),{id,name:'张三',phone:'138****5568',pointsRate:rate,effectiveAt:'2026-09-21 10:00',enabled:true}]};return current;}),searchCustomers:vi.fn(async()=>({items:[{value:'new-user',label:'李四',detail:'139****2221'}],total:1})),...overrides};
 const wrapper=mount(Panel,{attachTo:document.body,global:{provide:{[adminBenefitSettingsAdapterKey as symbol]:api},stubs:{RouterLink:{template:'<a><slot /></a>'}}}});
 await flushPromises();return {wrapper,api};
}
it('shows two default cards and separate real agent/customer override tables',async()=>{
 const {wrapper}=await show();
 expect(wrapper.findAll('.benefit-default-card')).toHaveLength(2);
 expect(wrapper.get('[data-testid="rules-agent"]').text()).toContain('星辰');
 expect(wrapper.get('[data-testid="rules-customer"]').text()).toContain('张三');
 expect(wrapper.get('[data-testid="rules-agent"]').text()).toContain('15%');
 expect(wrapper.get('[data-testid="rules-customer"]').text()).toContain('10%');
 expect(wrapper.get('.benefit-defaults').text()).toContain('¥1,000');
 expect(wrapper.get('.benefit-defaults').text()).toContain('500');
 expect(wrapper.text()).not.toContain('BENEFIT-V');
});
it.each([['0','0'],['12.3456','0.123456'],['100','1']])('edits customer points percent %s with existing save protocol',async(percent,rate)=>{
 const {wrapper,api}=await show();
 await wrapper.get('[aria-label="编辑客户 张三"]').trigger('click');
 await wrapper.get('[aria-label="客户返积分比例"]').setValue(percent);
 await wrapper.get('[aria-label="保存客户返积分比例"]').trigger('click');await flushPromises();
 expect(api.saveCustomerRate).toHaveBeenCalledWith('customer',rate,'调整收益比例');
 expect(wrapper.get('[data-testid="rules-customer"]').text()).toContain(percent+'%');
});
it('adds a searched real customer and deletes only that override to inherit the default',async()=>{
 const {wrapper,api}=await show();
 await wrapper.get('[aria-label="添加特殊客户"]').trigger('click');await flushPromises();
 const select=wrapper.findAllComponents(AdminSearchSelect).find(c=>c.props('id')==='benefit-customer-select')!;
 select.vm.$emit('query','李四');await flushPromises();
 expect(api.searchCustomers).toHaveBeenCalledWith('李四',0);
 select.vm.$emit('update:modelValue','new-user');await nextTick();
 await wrapper.get('[aria-label="客户返积分比例"]').setValue('8');
 await wrapper.get('[aria-label="保存客户返积分比例"]').trigger('click');await flushPromises();
 expect(api.saveCustomerRate).toHaveBeenCalledWith('new-user','0.08','调整收益比例');
 await wrapper.get('[aria-label="删除客户规则 customer"]').trigger('click');await flushPromises();
 expect(api.saveCustomerRate).toHaveBeenLastCalledWith('customer',null,'调整收益比例');
 expect(wrapper.find('[aria-label="删除客户规则 customer"]').exists()).toBe(false);
 expect(api.savePromoterRate).not.toHaveBeenCalled();
});
it('keeps a zero override visible and filters/paginates each table independently',async()=>{
 const data=initial();data.customers=Array.from({length:12},(_,i)=>({...data.customers[0],id:`c${i}`,name:`客户${i}`,pointsRate:'0'}));
 const {wrapper}=await show({load:async()=>data});
 expect(wrapper.get('[data-testid="rules-customer"]').findAll('tbody tr')).toHaveLength(10);
 await wrapper.get('[aria-label="客户规则下一页"]').trigger('click');
 expect(wrapper.get('[data-testid="rules-customer"]').findAll('tbody tr')).toHaveLength(2);
 await wrapper.get('[aria-label="搜索特殊客户"]').setValue('客户11');
 expect(wrapper.get('[data-testid="rules-customer"]').findAll('tbody tr')).toHaveLength(1);
 expect(wrapper.get('[data-testid="rules-customer"]').text()).toContain('0%');
 expect(wrapper.get('[data-testid="rules-agent"]').text()).toContain('星辰');
});
it('failed customer saves retain the form and do not mutate the displayed snapshot',async()=>{
 const {wrapper}=await show({saveCustomerRate:async()=>{throw new Error('private');}});
 await wrapper.get('[aria-label="编辑客户 张三"]').trigger('click');
 await wrapper.get('[aria-label="客户返积分比例"]').setValue('25');
 await wrapper.get('[aria-label="保存客户返积分比例"]').trigger('click');await flushPromises();
 expect(wrapper.get('[aria-label="客户返积分比例"]').element).toHaveProperty('value','25');
 expect(wrapper.get('[data-testid="rules-customer"]').text()).toContain('10%');
 expect(wrapper.text()).not.toContain('private');
});
it('cancelling an edit never saves and restores focus to the trigger',async()=>{
 const {wrapper,api}=await show();const button=wrapper.get('[aria-label="编辑客户 张三"]');
 await button.trigger('click');await wrapper.get('[aria-label="取消客户编辑"]').trigger('click');await nextTick();
 expect(api.saveCustomerRate).not.toHaveBeenCalled();expect(document.activeElement).toBe(button.element);
});
it('renders audited before/after percentages and a customer history filter',async()=>{
 const {wrapper}=await show({loadHistory:async()=>({count:1,records:[{version:'V2',scope:'CUSTOMER_OVERRIDE',maskedPromoter:'张* · USR-customer',point:'0.12',defaultL1:null,defaultL2:null,promoterL1:null,promoterL2:null,field:'invitationPointsRate',beforeRate:'0.08',afterRate:'0.12',effectiveAt:'2026-09-21 10:00',admin:'admin',reason:'调整收益比例'}]})});
 expect(wrapper.get('[data-testid="benefit-history-table"]').text()).toMatch(/8%.*12%/);
 expect(wrapper.get('select[aria-label="历史范围"]').text()).toContain('特殊客户');
});

it('keeps both configuration dialogs accessible',async()=>{
 const {wrapper}=await show();
 await wrapper.get('[aria-label="编辑客户 张三"]').trigger('click');
 expect((await axe(wrapper.get('dialog[aria-labelledby="customer-edit-title"]').element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
 await wrapper.get('[aria-label="取消客户编辑"]').trigger('click');
 await wrapper.get('[aria-label="编辑代理 星辰"]').trigger('click');
 expect((await axe(wrapper.get('dialog[aria-labelledby="agent-edit-title"]').element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
});
it('keeps a failed delete visible and prevents duplicate saves while pending',async()=>{
 let reject!:(e:Error)=>void;const remove=vi.fn(()=>new Promise((_resolve,r)=>reject=r));
 const {wrapper}=await show({saveCustomerRate:remove});
 const button=wrapper.get('[aria-label="删除客户规则 customer"]');
 await button.trigger('click');await button.trigger('click');
 expect(remove).toHaveBeenCalledTimes(1);expect(button.attributes('disabled')).toBeDefined();
 reject(new Error('private'));await flushPromises();
 expect(wrapper.get('[data-testid="rules-customer"]').text()).toContain('张三');
 expect(button.attributes('disabled')).toBeUndefined();
});

it('a filter query replaces a pending initial history load without leaving the spinner stuck',async()=>{
 let finish!:(v:unknown)=>void;
 const {wrapper}=await show({loadHistory:()=>new Promise(r=>finish=r),queryHistory:async()=>({count:0,records:[]})});
 expect(wrapper.find('[data-testid="benefit-history-loading"]').exists()).toBe(true);
 await wrapper.get('[data-testid="benefit-history-filter-form"]').trigger('submit');await flushPromises();
 expect(wrapper.find('[data-testid="benefit-history-loading"]').exists()).toBe(false);
 expect(wrapper.find('[data-testid="benefit-history-empty"]').exists()).toBe(true);
 finish({count:0,records:[]});await flushPromises();
 expect(wrapper.find('[data-testid="benefit-history-loading"]').exists()).toBe(false);
});
