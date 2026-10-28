import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {enableAutoUnmount,flushPromises,mount} from '@vue/test-utils';
import {ref} from 'vue';
import {adminSessionKey,type AdminIdentity} from './adminSession';
import OverviewFinanceSummary from './OverviewFinanceSummary.vue';
enableAutoUnmount(afterEach);
const api=vi.hoisted(()=>({query:vi.fn()}));
vi.mock('./api',()=>({billingDailyFinanceGetApiV1AdminBillingDailyFinance:api.query}));
const identity=ref<AdminIdentity|null>(null);
beforeEach(()=>{
 vi.useFakeTimers({toFake:['Date']});vi.setSystemTime(new Date('2026-09-11T17:00:00Z'));
 identity.value={username:'admin',userId:'admin',role:'ADMIN',primary:true};
 api.query.mockReset().mockImplementation(async q=>({data:{...q,totalRechargeAmount4:'186932.0000',totalConsumptionAmount4:'129378.7900',items:q.from&&q.from!=='0001-01-01'?[{date:'2026-09-11',consumptionAmount4:'0.2500'},{date:'2026-09-12',consumptionAmount4:'12.3456'}]:[],totalDays:q.from&&q.from!=='0001-01-01'?2:100}}));
});
afterEach(()=>vi.useRealTimers());
async function open(){const w=mount(OverviewFinanceSummary,{props:{revision:0},global:{provide:{[adminSessionKey as symbol]:{currentAdmin:identity}}}});await flushPromises();return w;}
it('retains successful amounts during refresh, but clears them on identity change',async()=>{
 const w=await open();const before=w.findAll('.summary-amount').map(e=>e.text());
 api.query.mockImplementation(()=>new Promise(()=>{}));
 await w.setProps({revision:1});await flushPromises();
 expect(w.findAll('.summary-amount').map(e=>e.text())).toEqual(before);
 expect(w.find('.loading-value').exists()).toBe(false);
 identity.value={username:'another',userId:'another',role:'ADMIN',primary:true};await flushPromises();
 expect(w.findAll('.summary-amount .loading-value')).toHaveLength(4);
 expect(w.text()).not.toContain('186932');
});
it('shows real all-time yuan totals and Beijing today/yesterday with decorative icons',async()=>{
 const w=await open();expect(api.query).toHaveBeenCalledTimes(3);
 expect(api.query).toHaveBeenCalledWith({offset:0,limit:1});expect(api.query).toHaveBeenCalledWith({from:'2026-09-10',to:'2026-09-12',offset:0,limit:3});
 expect(w.findAll('h2').map(e=>e.text())).toEqual(['总充值（元）','总消耗（元）','今日消耗（元）','昨日消耗（元）']);
 expect(w.findAll('.summary-amount').map(e=>e.text())).toEqual(['186932','129378.79','12.3456','0.25']);
 expect(w.findAll('svg[aria-hidden="true"]')).toHaveLength(4);
});
it('treats missing days as zero and unknown historical amounts as unavailable',async()=>{
 api.query.mockImplementation(async q=>({data:{...q,totalRechargeAmount4:'51.2000',totalConsumptionAmount4:null,items:q.from&&q.from!=='0001-01-01'?[{date:'2026-09-11',consumptionAmount4:null}]:[],totalDays:q.from&&q.from!=='0001-01-01'?1:100}}));
 const w=await open();expect(w.findAll('.summary-amount').map(e=>e.text())).toEqual(['51.2','—','0','—']);
});
it('clears old values and ignores a response belonging to the previous administrator',async()=>{
 const w=await open();let resolve!:(value:unknown)=>void;
 api.query.mockImplementationOnce(()=>new Promise(r=>resolve=r));await w.setProps({revision:1});
 identity.value={username:'reader',userId:'reader',role:'NORMAL_ADMIN',primary:false};await flushPromises();
 resolve({data:{totalRechargeAmount4:'999999',totalConsumptionAmount4:'999999'}});await flushPromises();expect(w.text()).not.toContain('999999');
 identity.value=null;await flushPromises();expect(w.find('article').exists()).toBe(false);
});
it('retains available total data when the daily query fails and never manufactures zeros',async()=>{
 api.query.mockImplementation(async q=>{if(q.from&&q.from!=='0001-01-01')throw Error('offline');return {data:{totalRechargeAmount4:'51.2000',totalConsumptionAmount4:'20.5418'}};});
 const w=await open();expect(w.findAll('.summary-amount').map(e=>e.text())).toEqual(['51.2','20.5418','—','—']);
 api.query.mockRejectedValue(new Error('offline'));await w.setProps({revision:1});await flushPromises();
 expect(w.findAll('.summary-amount').map(e=>e.text())).toEqual(['—','—','—','—']);
});
it('refreshes today and yesterday after the Beijing date changes',async()=>{
 const w=await open();vi.setSystemTime(new Date('2026-09-12T17:00:00Z'));await w.setProps({revision:1});await flushPromises();
 expect(api.query).toHaveBeenCalledWith({from:'2026-09-11',to:'2026-09-13',offset:0,limit:3});expect(api.query).toHaveBeenCalledTimes(6);
});
it.each(['PROMOTER','API_USER'] as const)('does not request administrator totals for %s',async role=>{
 identity.value={username:'user',role};const w=await open();expect(api.query).not.toHaveBeenCalled();expect(w.find('article').exists()).toBe(false);
});

it('keeps invalid successful responses unavailable rather than inventing zero amounts',async()=>{
 api.query.mockResolvedValue({data:null});const w=await open();
 expect(w.findAll('.summary-amount').map(e=>e.text())).toEqual(['—','—','—','—']);
 expect(w.get('[data-testid="overview-finance-summary"]').attributes('aria-busy')).toBe('false');
});

it('compares cumulative totals to yesterday and daily consumption to its preceding Beijing day',async()=>{
 api.query.mockImplementation(async q=>({data:q.from&&q.from!=='0001-01-01'?{items:[{date:'2026-09-10',consumptionAmount4:'50.0000'},{date:'2026-09-11',consumptionAmount4:'40.0000'},{date:'2026-09-12',consumptionAmount4:'60.0000'}],totalDays:3}:{totalRechargeAmount4:q.to?'100.0000':'112.0000',totalConsumptionAmount4:q.to?'200.0000':'260.0000'}}));
 const w=await open();
 expect(w.findAll('.summary-trend strong').map(e=>e.text())).toEqual(['↑ 12%','↑ 30%','↑ 50%','↓ 20%']);
 expect(w.findAll('.summary-trend small').map(e=>e.text())).toEqual(Array(4).fill('较昨日'));
 expect(api.query).toHaveBeenCalledWith({from:'0001-01-01',to:'2026-09-11',offset:0,limit:1});
 expect(api.query).toHaveBeenCalledWith({from:'2026-09-10',to:'2026-09-12',offset:0,limit:3});
});
it('does not invent percentages for missing baselines and preserves a known zero change',async()=>{
 api.query.mockImplementation(async q=>({data:q.from&&q.from!=='0001-01-01'?{items:[{date:'2026-09-11',consumptionAmount4:null},{date:'2026-09-12',consumptionAmount4:'1.0000'}],totalDays:2}:{totalRechargeAmount4:q.to?'0':'1',totalConsumptionAmount4:'0'}}));
 const w=await open();expect(w.findAll('.summary-trend strong').map(e=>e.text())).toEqual(['—','0%','—','—']);
 api.query.mockImplementation(async q=>{if(q.from==='0001-01-01')throw Error('offline');return {data:q.from&&q.from!=='0001-01-01'?{items:[],totalDays:0}:{totalRechargeAmount4:'12',totalConsumptionAmount4:'10'}};});
 await w.setProps({revision:1});await flushPromises();expect(w.findAll('.summary-trend strong').map(e=>e.text())).toEqual(['—','—','0%','0%']);
 expect(w.findAll('.summary-amount').map(e=>e.text())).toEqual(['12','10','0','0']);
});

it('keeps four-decimal changes and negative net-consumption comparisons precise',async()=>{
 api.query.mockImplementation(async q=>({data:q.from&&q.from!=='0001-01-01'?{items:[{date:'2026-09-10',consumptionAmount4:'-10.0000'},{date:'2026-09-11',consumptionAmount4:'-5.0000'},{date:'2026-09-12',consumptionAmount4:'-6.0000'}],totalDays:3}:{totalRechargeAmount4:q.to?'1.0000':'1.0001',totalConsumptionAmount4:q.to?'100.0000':'99.9999'}}));
 const w=await open();expect(w.findAll('.summary-trend strong').map(e=>e.text())).toEqual(['↑ 0.01%','↓ 0%','↓ 20%','↑ 50%']);
});
