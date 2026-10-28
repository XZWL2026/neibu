import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {enableAutoUnmount,flushPromises,mount} from '@vue/test-utils';
import {ref} from 'vue';
import {NSelect} from 'naive-ui';
import {createMemoryHistory,createRouter} from 'vue-router';
import AdminPage from './pages/AdminOverview.vue';
import {adminSessionKey,type AdminIdentity} from './adminSession';
enableAutoUnmount(afterEach);
const api=vi.hoisted(()=>({report:vi.fn(),finance:vi.fn()}));
vi.mock('./api',async original=>({...await original<typeof import('./api')>(),mediaNativeVideoUsageGetApiV1AdminMediaNativeVideoUsage:api.report,billingDailyFinanceGetApiV1AdminBillingDailyFinance:api.finance}));
const identity=ref<AdminIdentity|null>(null);
const metric=(tasks:number,seconds:number)=>({tasks,seconds});
const row={date:'2026-09-11',model:'Seedance 2.5',native480:metric(1,5),native720:metric(1,9),native1080:metric(1,12),enhanced720:metric(2,13),enhanced1080:metric(0,0),enhanced4k:metric(0,0),total:metric(5,39)};
beforeEach(()=>{
 api.finance.mockReset().mockImplementation(async query=>({data:{...query,items:[],totalDays:0,totalRechargeAmount4:'0.0000',totalConsumptionAmount4:'0.0000'}}));
 identity.value={username:'admin',userId:'admin',role:'ADMIN',primary:true};
 api.report.mockReset().mockImplementation(async query=>({data:{...query,items:[row],total:metric(5,39)}}));
});
it('loads the last seven Beijing calendar days by default',async()=>{
 vi.useFakeTimers({toFake:['Date']});vi.setSystemTime(new Date('2026-09-11T17:00:00Z'));
 try {
  const w=await open();expect(api.report).toHaveBeenCalledWith({from:'2026-09-06',to:'2026-09-12'});
  expect(w.get<HTMLInputElement>('#native-from').element.value).toBe('2026-09-06');expect(w.get<HTMLInputElement>('#native-to').element.value).toBe('2026-09-12');
  expect(w.get('.native-title p').text()).toBe('各模型生成数据（所选时间段）');
  expect(w.get('.native-title').text()).not.toContain('2026-09-06');
 } finally {vi.useRealTimers();}
});
it('distinguishes empty success from a failed request and rejects reversed dates',async()=>{
 api.report.mockResolvedValueOnce({data:{from:'2026-09-11',to:'2026-09-11',items:[],total:metric(0,0)}});
 const w=await open();expect(w.text()).toContain('该日期范围暂无生成记录');
 await w.get('#native-from').setValue('2026-09-12');await w.get('#native-to').setValue('2026-09-11');
 await w.get('[data-testid="native-video-usage"] form').trigger('submit');expect(api.report).toHaveBeenCalledTimes(1);
 await w.get('#native-from').setValue('2026-09-01');api.report.mockRejectedValueOnce(new Error('unavailable'));
 await w.get('[data-testid="native-video-usage"] form').trigger('submit');await flushPromises();
 expect(w.text()).toContain('火山生成统计读取失败，请重试。');expect(w.text()).not.toContain('该日期范围暂无生成记录');
 expect(w.findAll('.native-model-card')).toHaveLength(0);
});
it('aggregates every day by model and includes native and enhanced resolution buckets',async()=>{
 api.report.mockImplementationOnce(async query=>({data:{...query,items:[row,{...row,date:'2026-09-12'},{...row,model:'Seedance 2.0'}],total:metric(15,117)}}));
 const w=await open();const cards=w.findAll('.native-model-card');expect(cards).toHaveLength(4);
 expect(cards.find(c=>c.text().includes('Seedance 2.0 Mini'))!.findAll('dd').map(c=>c.text())).toEqual(Array(5).fill('0 / 0 秒'));
 const card=cards.find(c=>c.text().includes('Seedance 2.5'))!;
 expect(card.findAll('dt').map(c=>c.text())).toEqual(['480P','720P','1080P','4K','合计']);
 expect(card.findAll('dd').map(c=>c.text())).toEqual(['2 / 10 秒','6 / 44 秒','2 / 24 秒','0 / 0 秒','10 / 78 秒']);
});
it('does not let a previous account response replace the current report',async()=>{
 let resolve!:(value:unknown)=>void;api.report.mockImplementationOnce(()=>new Promise(r=>resolve=r));
 const w=await open();identity.value={username:'reader',userId:'reader',role:'NORMAL_ADMIN',primary:false};await flushPromises();
 resolve({data:{from:'2026-09-11',to:'2026-09-11',items:[{...row,model:'旧账号记录'}],total:metric(9,99)}});await flushPromises();
 expect(w.text()).not.toContain('旧账号记录');expect(w.get('.native-model-card .native-model-total dd').text()).toBe('5 / 39 秒');
});
it.each(['PROMOTER','API_USER'] as const)('never requests administrator statistics for %s',async role=>{
 identity.value={username:'user',role,primary:false};await open();expect(api.report).not.toHaveBeenCalled();expect(api.finance).not.toHaveBeenCalled();
});
async function open(){
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/overview',name:'admin-overview',component:AdminPage}]});
 await router.push('/admin/overview');await router.isReady();
 const wrapper=mount(AdminPage,{global:{plugins:[router],provide:{[adminSessionKey as symbol]:{currentAdmin:identity}}}});
 await flushPromises();return wrapper;
}
it('queries date-only inclusive ranges and keeps the existing overview mounted',async()=>{
 const w=await open();const overview=w.get('[data-testid="overview-finance-summary"]').element;
 const inputs=w.findAll('[data-testid="native-video-usage"] input');expect(inputs.map(i=>i.attributes('type'))).toEqual(['date','date']);
 await inputs[0]!.setValue('2026-09-01');await inputs[1]!.setValue('2026-09-11');
 expect(api.report).toHaveBeenCalledTimes(1);expect(api.finance).toHaveBeenCalledWith({from:expect.any(String),to:expect.any(String),offset:0,limit:10});
 await w.get('[data-testid="native-video-usage"] form').trigger('submit');await flushPromises();
 expect(api.report).toHaveBeenLastCalledWith({from:'2026-09-01',to:'2026-09-11'});expect(w.get('[data-testid="overview-finance-summary"]').element).toBe(overview);
 expect(w.get('#native-from').element).toHaveProperty('value','2026-09-01');expect(w.get('#native-to').element).toHaveProperty('value','2026-09-11');
 expect(api.finance).toHaveBeenCalledWith({from:'2026-09-01',to:'2026-09-11',offset:0,limit:10});
 expect(w.text()).not.toContain('活跃用户');expect(w.text()).not.toContain('本地运营数据已就绪');
});

it('uses the overview preset for model and finance queries and marks manual dates as custom',async()=>{
 vi.useFakeTimers({toFake:['Date']});vi.setSystemTime(new Date('2026-09-11T17:00:00Z'));
 try{
  const w=await open();const preset=w.getComponent(NSelect);
  expect(preset.props('value')).toBe(7);expect(api.report).toHaveBeenLastCalledWith({from:'2026-09-06',to:'2026-09-12'});
  preset.vm.$emit('update:value',30);await flushPromises();expect(api.report).toHaveBeenLastCalledWith({from:'2026-08-14',to:'2026-09-12'});
  expect(api.finance).toHaveBeenCalledWith({from:'2026-08-14',to:'2026-09-12',offset:0,limit:10});
  await w.get('#native-from').setValue('2026-09-01');await w.get('[data-testid="native-video-usage"] form').trigger('submit');await flushPromises();
  expect(preset.props('value')).toBe(-1);expect(api.report).toHaveBeenLastCalledWith({from:'2026-09-01',to:'2026-09-12'});
  preset.vm.$emit('update:value',0);await flushPromises();expect(api.report).toHaveBeenLastCalledWith({from:'0000-01-01',to:'9999-12-31'});
 }finally{vi.useRealTimers();}
});
