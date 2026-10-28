import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {enableAutoUnmount,flushPromises,mount} from '@vue/test-utils';
import {ref} from 'vue';
import {adminSessionKey,type AdminIdentity} from './adminSession';
import DailyFinanceLists from './DailyFinanceLists.vue';
enableAutoUnmount(afterEach);
const api=vi.hoisted(()=>({query:vi.fn(),orders:vi.fn()}));
vi.mock('./api',()=>({billingDailyFinanceGetApiV1AdminBillingDailyFinance:api.query,paymentAdminHistoryGetApiV1AdminPaymentsRecharges:api.orders}));
const identity=ref<AdminIdentity|null>(null);
const days=Array.from({length:31},(_,i)=>({date:`2026-08-${String(31-i).padStart(2,'0')}`,rechargeAmount4:'12.3456',consumptionAmount4:i===0?'-2.1234':'0.0001'}));
const report={items:days.slice(0,30),totalDays:31,offset:0,limit:30,totalRechargeAmount4:'382.7136',totalConsumptionAmount4:'-2.1204'};
beforeEach(()=>{
 api.orders.mockReset().mockResolvedValue({data:[]});
 identity.value={username:'admin',userId:'admin',role:'ADMIN',primary:true};
 api.query.mockReset().mockImplementation(async q=>({data:{...report,...q,items:days.slice(q.offset,q.offset+q.limit)}}));
});
async function open(){const w=mount(DailyFinanceLists,{props:{range:{},revision:0},global:{provide:{[adminSessionKey as symbol]:{currentAdmin:identity}}}});await flushPromises();return w;}
it('keeps existing daily rows visible while refresh is pending and removes them on identity change',async()=>{
 const w=await open();api.query.mockImplementation(()=>new Promise(()=>{}));
 await w.setProps({revision:1});await flushPromises();
 expect(w.findAll('.consumption th[scope="row"]')).toHaveLength(30);
 expect(w.get('.consumption [role="status"]').text()).toBe('正在更新…');
 expect(w.find('.consumption .table-loading-row').exists()).toBe(false);
 identity.value={username:'another',userId:'another',role:'ADMIN',primary:true};await flushPromises();
 expect(w.findAll('.consumption th[scope="row"]')).toHaveLength(0);
 expect(w.findAll('.consumption .table-loading-row')).toHaveLength(6);
});
it('shows precise interval totals and matching daily pages with accessible table headings',async()=>{
 const w=await open();expect(w.findAll('table')).toHaveLength(2);expect(w.findAll('.consumption tbody tr')).toHaveLength(30);
 expect(w.findAll('th[scope="row"]')[0]!.text()).toBe('2026-08-31');expect(w.text()).toContain('-2.1234');
 expect(w.get('[data-testid="finance-consumption-total"]').text()).toBe('¥-2.12');
 expect(w.findAll('caption').map(c=>c.text())).toEqual(['充值记录','每日净消耗金额']);
 await w.get('[aria-label="账务统计下一页"]').trigger('click');await flushPromises();
 expect(api.query).toHaveBeenLastCalledWith({offset:30,limit:30});expect(w.findAll('.consumption tbody tr')).toHaveLength(1);
 expect(w.findAll('th[scope="row"]').map(c=>c.text())).toEqual(['2026-08-01']);
 expect(w.get('[data-testid="finance-consumption-total"]').text()).toBe('¥-2.12');
 expect(w.get<HTMLButtonElement>('[aria-label="账务统计下一页"]').element.disabled).toBe(true);
 await w.setProps({range:{from:'2026-08-01',to:'2026-08-31'},revision:1});await flushPromises();
 expect(api.query).toHaveBeenLastCalledWith({from:'2026-08-01',to:'2026-08-31',offset:0,limit:30});
});
it('clears stale totals on failure, allows retry and distinguishes empty success',async()=>{
 const w=await open();api.query.mockRejectedValueOnce(new Error('unavailable'));
 await w.setProps({revision:1});await flushPromises();
 expect(w.get('[role="alert"]').text()).toContain('读取失败');expect(w.get('[data-testid="finance-consumption-total"]').text()).toBe('—');
 expect(w.text()).not.toContain('382.7136');expect(w.get('.consumption').text()).not.toContain('暂无');
 api.query.mockResolvedValueOnce({data:{...report,items:[],totalDays:0,totalRechargeAmount4:'0.0000',totalConsumptionAmount4:'0.0000'}});
 await w.setProps({revision:2});await flushPromises();expect(w.text()).toContain('该日期范围暂无账务记录');
 expect(w.get('[data-testid="finance-consumption-total"]').text()).toBe('¥0.00');
});
it('rejects invalid money data rather than displaying it as a zero',async()=>{
 api.query.mockResolvedValueOnce({data:{...report,totalRechargeAmount4:null}});const w=await open();
 expect(w.get('[data-testid="finance-consumption-total"]').text()).toBe('—');expect(w.find('[role="alert"]').exists()).toBe(true);
});
it('clears data immediately on account change and ignores the previous account response',async()=>{
 const w=await open();let resolve!:(value:unknown)=>void;
 api.query.mockImplementationOnce(()=>new Promise(r=>resolve=r));await w.setProps({revision:1});
 identity.value={username:'reader',userId:'reader',role:'NORMAL_ADMIN',primary:false};await flushPromises();
 resolve({data:{...report,totalConsumptionAmount4:'9999.0000'}});await flushPromises();expect(w.text()).not.toContain('9999');
 identity.value=null;await flushPromises();expect(w.find('table').exists()).toBe(false);
});
it('ignores a late response from a previously selected date range',async()=>{
 const w=await open();let resolve!:(value:unknown)=>void;
 api.query.mockImplementationOnce(()=>new Promise(r=>resolve=r));await w.setProps({range:{from:'2026-08-01',to:'2026-08-02'},revision:1});
 await w.setProps({range:{from:'2026-08-03',to:'2026-08-04'},revision:2});await flushPromises();
 resolve({data:{...report,totalConsumptionAmount4:'8888.0000'}});await flushPromises();expect(w.text()).not.toContain('8888');
});
it('keeps interval totals and the pagination button mounted while the next page loads',async()=>{
 const w=await open();const button=w.get<HTMLButtonElement>('[aria-label="账务统计下一页"]').element;
 let resolve!:(value:unknown)=>void;api.query.mockImplementationOnce(()=>new Promise(r=>resolve=r));
 await w.get('[aria-label="账务统计下一页"]').trigger('click');
 expect(w.get('[data-testid="finance-consumption-total"]').text()).toBe('¥-2.12');
 expect(w.get('[aria-label="账务统计下一页"]').element).toBe(button);expect(button.getAttribute('aria-disabled')).toBe('true');
 await w.get('[aria-label="账务统计下一页"]').trigger('click');expect(api.query).toHaveBeenCalledTimes(2);
 resolve({data:{...report,offset:30,items:days.slice(30)}});await flushPromises();
 expect(w.get('[aria-label="账务统计下一页"]').element).toBe(button);expect(button.disabled).toBe(true);
});

it('shows unknown historical yuan as a dash without hiding recharge amounts',async()=>{
 api.query.mockResolvedValueOnce({data:{...report,items:[{...days[0],consumptionAmount4:null}],totalDays:1,totalConsumptionAmount4:null}});
 const w=await open();expect(w.get('[data-testid="finance-consumption-total"]').text()).toBe('—');
 expect(w.get('.consumption tbody td').text()).toBe('—');expect(w.get('[data-testid="finance-recharge-total"]').text()).toBe('¥0.00');
 expect(w.find('[role="alert"]').exists()).toBe(false);expect(w.text()).toContain('净消耗（元）');expect(w.text()).not.toContain('积分');
});

it('renders real recharge orders with exact credited totals and Beijing date filtering',async()=>{
 const base={source:'ONLINE',status:'PAID',fulfilled:true,paymentMethod:'ALIPAY',createdAt:'2026-08-01T16:01:00Z'};
 api.orders.mockResolvedValue({data:[{...base,orderId:'paid',amount4:'12.3456'},{...base,orderId:'pending',amount4:'999.0000',fulfilled:false},{...base,orderId:'corporate',source:'CORPORATE',amount4:'0.0001'},{...base,orderId:'outside',createdAt:'2026-08-01T15:59:00Z',amount4:'10.0000'}]});
 const w=await open();await w.setProps({range:{from:'2026-08-02',to:'2026-08-02'},revision:1});await flushPromises();
 expect(w.findAll('.recharge tbody tr')).toHaveLength(3);expect(w.get('[data-testid="finance-recharge-total"]').text()).toBe('¥12.34');
 expect(w.get('.recharge').text()).toContain('待入账');expect(w.get('.recharge').text()).toContain('对公转账');expect(w.get('.recharge').text()).not.toContain('outside');
 api.orders.mockRejectedValueOnce(Error('unavailable'));await w.setProps({revision:2});await flushPromises();
 expect(w.get('.recharge').text()).toContain('充值记录读取失败');expect(w.get('[data-testid="finance-recharge-total"]').text()).toBe('—');
});

it('ignores a previous administrator recharge response after identity changes',async()=>{
 let resolve!:(value:unknown)=>void;api.orders.mockImplementationOnce(()=>new Promise(r=>resolve=r));
 const w=await open();identity.value={username:'reader',userId:'reader',role:'NORMAL_ADMIN',primary:false};await flushPromises();
 resolve({data:[{orderId:'old-secret-order',amount4:'9999.0000',status:'PAID',fulfilled:true,createdAt:'2026-08-02T00:00:00Z'}]});await flushPromises();
 expect(w.text()).not.toContain('old-secret-order');expect(w.get('[data-testid="finance-recharge-total"]').text()).toBe('¥0.00');
 api.orders.mockClear();identity.value={username:'api',userId:'api',role:'API_USER',primary:false};await flushPromises();expect(api.orders).not.toHaveBeenCalled();expect(w.find('.recharge').exists()).toBe(false);
});

it('formats reference header dates, calendar days and grouped yuan while retaining precise source amounts',async()=>{
 api.orders.mockResolvedValue({data:[{orderId:'paid',amount4:'12560.0099',status:'PAID',fulfilled:true,createdAt:'2026-09-01T00:00:00Z'}]});
 api.query.mockImplementation(async q=>({data:{...q,items:[],totalDays:0,totalRechargeAmount4:'12560.0099',totalConsumptionAmount4:'1286.5999'}}));
 const w=await open();await w.setProps({range:{from:'2026-09-01',to:'2026-09-19'}});await flushPromises();
 expect(w.get('.recharge .finance-meta').text()).toContain('2026/09/01 – 2026/09/19');
 expect(w.get('.recharge .finance-meta').text()).toContain('1 条记录');
 expect(w.get('.consumption .finance-meta').text()).toContain('19 天');
 expect(w.get('[data-testid="finance-recharge-total"]').text()).toBe('¥12,560.00');
 expect(w.get('[data-testid="finance-recharge-total"]').attributes('title')).toContain('12560.0099');
 expect(w.get('[data-testid="finance-consumption-total"]').text()).toBe('¥1,286.59');
 expect(w.get('[data-testid="finance-consumption-total"]').attributes('title')).toContain('1286.5999');
 expect(w.get('.recharge .finance-meta').attributes('aria-label')).toContain('最近100条');
});
