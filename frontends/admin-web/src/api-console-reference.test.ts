import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import ApiPage from './ApiPage.vue';
import { csv, sumPoints } from './apiConsolePresentation';
import { adminSessionKey } from './adminSession';
import { configureGateway } from './api';
enableAutoUnmount(afterEach);
const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
const now = new Date(2026, 8, 18, 15);
const orders = [
 {orderId:'paid-a',userId:'owner',amount4:'12.3456',createdAt:new Date(2026,8,18,10).toISOString(),status:'PAID',fulfilled:true,paymentMethod:'ALIPAY',source:'ONLINE',remark:'月度充值'},
 {orderId:'pending-b',userId:'owner',amount4:'20.0000',createdAt:new Date(2026,8,17,10).toISOString(),status:'PAID',fulfilled:false,paymentMethod:'WECHAT',source:'ONLINE',remark:null}
];
let fetch: ReturnType<typeof vi.fn>;
beforeEach(() => {
 configureGateway({onUnauthorized:()=>{}}); vi.useFakeTimers({toFake:['Date']}); vi.setSystemTime(now);
 fetch=vi.fn(async(url:string)=>{const u=new URL(url,'http://test');
  if(u.pathname.endsWith('/access/keys'))return json([{ownerId:'owner',keyId:'key',maskedKey:'sk-****',enabled:true,accountType:'API'}]);
  if(u.pathname.endsWith('/secret'))return json({secret:'sk-real-owner'});
  if(u.pathname.includes('/wallets/'))return json({walletId:'key',availablePoints4:'876.5440'});
  if(u.pathname.endsWith('/portal/summary'))return json({totalCalls:21,totalRechargeAmount4:'250.1234',chargedAmount4:'12.3456',netPoints4:'123.4560'});
  if(u.pathname.endsWith('/portal/consumptions')&&u.searchParams.get('limit')==='100')return json({items:[{id:'model-row',ownerId:'owner',model:'真实模型',type:'VIDEO',resolution:'720p',durationSeconds:12,count:1,amount4:'12.3456'}],total:1});
  if(u.pathname.endsWith('/portal/consumptions'))return json({items:[{id:'row',ownerId:'owner',taskId:'task',model:'真实模型',type:'VIDEO',resolution:'720p',durationSeconds:12,count:1,createdAt:now.toISOString(),amount4:'12.3456',refundedPoints4:'0.0000'}],total:21});
  if(u.pathname.endsWith('/recharges'))return json(orders);
  throw Error('Unexpected '+url);
 });vi.stubGlobal('fetch',fetch);
});
afterEach(()=>{vi.unstubAllGlobals();vi.useRealTimers();});
async function open(path:string) {
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/api/dashboard',name:'api-dashboard',component:ApiPage},{path:'/api/billing',name:'api-billing',component:ApiPage},{path:'/api/transfer',name:'api-transfer',component:ApiPage}]});
 await router.push(path);await router.isReady();
 const identity=ref({role:'API_USER',userId:'owner',username:'用户'});
 const wrapper=mount(ApiPage,{global:{plugins:[router],provide:{[adminSessionKey as symbol]:{currentAdmin:identity}}}});await flushPromises();return{wrapper,router,identity};
}
it('shows stable table and metric placeholders instead of empty results while the dashboard is loading',async()=>{
 fetch.mockImplementation(()=>new Promise(()=>{}));
 const {wrapper}=await open('/api/dashboard');
 expect(wrapper.findAll('table[aria-label="调用记录"] .table-loading-row')).toHaveLength(6);
 expect(wrapper.findAll('table[aria-label="每日消费查询列表"] .table-loading-row')).toHaveLength(6);
 expect(wrapper.findAll('.api-summary-card .loading-value')).toHaveLength(4);
 expect(wrapper.text()).not.toContain('暂无');
 expect(wrapper.text()).not.toContain('共 0 条');
 expect(wrapper.get('.api-key-panel [role="status"]').attributes('aria-label')).toBe('正在读取密钥');
 expect(wrapper.findAll('.api-page-actions a[href="/api/transfer"]')).toHaveLength(1);
});
it('renders the four reference cards and paged real daily/usage data without fetching a secret automatically',async()=>{
 const {wrapper}=await open('/api/dashboard');
 expect(wrapper.findAll('.api-summary-card h2').map(c=>c.text())).toEqual(['总充值','剩余额度','今日消费','昨日消费']);
 expect(wrapper.get('[data-testid="api-balance"]').text()).toContain('876.544');
 expect(wrapper.get('[data-testid="api-balance"]').text()).toContain('积分');
 expect(wrapper.get('table[aria-label="调用记录"]').text()).toContain('真实模型');
 expect(wrapper.get('table[aria-label="调用记录"]').text()).toContain('720p');
 expect(wrapper.get('table[aria-label="真实模型 消耗明细"]').text()).toContain('720p');
 expect(wrapper.findAll('table[aria-label="每日消费查询列表"] tbody tr')).toHaveLength(10);
 expect(fetch.mock.calls.some(([url])=>String(url).endsWith('/secret'))).toBe(false);
 await wrapper.get('button[aria-label="显示 API 密钥"]').trigger('click');await flushPromises();
 expect(wrapper.get('.api-key-secret').text()).toBe('sk-real-owner');
 await wrapper.get('button[aria-label="隐藏 API 密钥"]').trigger('click');
 expect(wrapper.text()).not.toContain('sk-real-owner');
 const before=fetch.mock.calls.filter(([url])=>String(url).includes('/portal/summary')).length;
 await wrapper.get('nav[aria-label="调用记录分页"] button:last-child').trigger('click');await flushPromises();
 expect(fetch.mock.calls.some(([url])=>String(url).includes('offset=10'))).toBe(true);
 expect(fetch.mock.calls.filter(([url])=>String(url).includes('/portal/summary'))).toHaveLength(before);
 await wrapper.get('nav[aria-label="每日消费分页"] button:last-child').trigger('click');await flushPromises();
 expect(wrapper.findAll('table[aria-label="每日消费查询列表"] tbody tr')).toHaveLength(8);
 expect(wrapper.get('table[aria-label="每日消费查询列表"] tbody').text()).toContain('2026-09-01');
});
it('filters recharge records and keeps unfulfilled payments distinct from completed credits',async()=>{
 const {wrapper}=await open('/api/billing');
 expect(wrapper.get('h1').text()).toBe('充值记录');
 expect(wrapper.findAll('.api-summary-card h2').map(c=>c.text())).toEqual(['本月充值','充值次数']);
 expect(wrapper.get('table[aria-label="充值记录"]').text()).toContain('待入账');
 expect(wrapper.get('.api-billing-summary').text()).toContain('250.1234');
 await wrapper.get('input[aria-label="充值开始日期"]').setValue('2026-09-18');
 expect(wrapper.get('tbody').text()).not.toContain('pending-b');
 await wrapper.get('input[aria-label="充值开始日期"]').setValue('');
 await wrapper.get('input[aria-label="订单号或备注"]').setValue('月度');
 expect(wrapper.findAll('tbody tr')).toHaveLength(1);expect(wrapper.get('tbody').text()).toContain('paid-a');
 await wrapper.get('select[aria-label="支付方式筛选"]').setValue('WECHAT');
 expect(wrapper.get('tbody').text()).toContain('暂无');
 expect(wrapper.find('.api-recharge').exists()).toBe(false);
});

it('discards delayed secret reads after the account changes',async()=>{
 const {wrapper,identity}=await open('/api/dashboard');
 let finish!:(value:Response)=>void;
 fetch.mockImplementationOnce(()=>new Promise<Response>(resolve=>{finish=resolve;}));
 await wrapper.get('button[aria-label="显示 API 密钥"]').trigger('click');
 identity.value={role:'API_USER',userId:'another',username:'another'};await flushPromises();
 finish(json({secret:'private-old-owner'}));await flushPromises();
 expect(wrapper.text()).not.toContain('private-old-owner');
 expect(wrapper.text()).toContain('密钥归属不匹配');
});
it('keeps transfer routes reachable without inventing bank details or submitting unsupported requests',async()=>{
 const {wrapper,router}=await open('/api/dashboard');
 await wrapper.get('a[href="/api/transfer"]').trigger('click');await flushPromises();
 expect(router.currentRoute.value.path).toBe('/api/transfer');
 expect(wrapper.get('h1').text()).toBe('公户转账');
 expect(wrapper.findAll('.api-bank-details dd')).toHaveLength(5);
 expect(wrapper.text()).not.toContain('7559');
 expect(wrapper.text()).toContain('770580898450');
 const writeText=vi.fn().mockResolvedValue(undefined);vi.stubGlobal('navigator',{clipboard:{writeText}});
 await wrapper.get('button[aria-label="复制银行账号"]').trigger('click');await flushPromises();expect(writeText).toHaveBeenCalledWith('770580898450');
 expect(wrapper.get('.api-transfer-submit').attributes('disabled')).toBeDefined();
 expect(fetch.mock.calls.every(([,options])=>!options||options.method==='GET')).toBe(true);
});
it('preserves four-decimal financial values and neutralizes formula-like CSV cells',()=>{
 expect(sumPoints(['9007199254740993.1234','0.0001'])).toBe('9007199254740993.1235');
 expect(sumPoints(['bad'])).toBeNull();
 expect(csv([['=1+1','a,"b','12.3456']])).toBe('\uFEFF"\'=1+1","a,""b","12.3456"');
});
