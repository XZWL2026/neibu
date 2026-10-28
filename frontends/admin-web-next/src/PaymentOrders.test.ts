import {mount,flushPromises,enableAutoUnmount} from '@vue/test-utils';
import {beforeEach,afterEach,it,expect,vi} from 'vitest';
import {ref} from 'vue';
import {adminSessionKey} from './adminSession';
const api=vi.hoisted(()=>({orders:vi.fn(),user:vi.fn()}));
vi.mock('./api',()=>({paymentAdminOrdersGetApiV1AdminPaymentsOrders:api.orders,userDirectoryDetailGetApiV1AdminUsersIdDirectoryDetail:api.user}));
vi.mock('./PaymentOrderDrawer.vue',()=>({default:{props:['record','review'],template:'<div role="dialog" aria-label="订单详情">{{record.order.orderId}}</div>'}}));
import PaymentOrders from './PaymentOrders.vue';
enableAutoUnmount(afterEach);
const row=(i=0)=>({order:{orderId:'order-'+i,userId:'owner',amount4:'12.3456',expectedPoints4:'123.4560',rateSnapshot4:'10.0000',accountType:'PERSONAL',source:'MANUAL',status:'REVIEW_PENDING',createdAt:'2026-09-20T01:00:00Z',fulfilled:false},paidAt:null,transactionId:null,providerOrderId:null,keyId:null,refundReference:null});
beforeEach(()=>{vi.clearAllMocks();vi.useFakeTimers({toFake:['Date']});vi.setSystemTime(new Date('2026-09-20T08:00:00Z'));api.orders.mockResolvedValue({data:{items:[row()],total:1,offset:0,limit:100}});api.user.mockResolvedValue({data:{user:{id:'owner',nickname:'真实用户',phone:'13800000000',source:'NATURAL'}}});});
afterEach(()=>vi.useRealTimers());
function show(review=false){const currentAdmin=ref({role:'ADMIN',primary:true,username:'admin'});return {currentAdmin,w:mount(PaymentOrders,{props:{review},global:{provide:{[adminSessionKey as symbol]:{currentAdmin}}}})};}
it('opens a right-side detail only on explicit view and uses real account data',async()=>{const {w}=show();await flushPromises();expect(w.get('h1').text()).toBe('支付订单');expect(w.find('[role="dialog"]').exists()).toBe(false);expect(w.text()).toContain('真实用户');await w.findAll('button').find(b=>b.text()==='查看')!.trigger('click');expect(w.get('[role="dialog"]').text()).toContain('order-0');});
it('loads beyond 100 rows, keeps decimal totals and filters the complete set',async()=>{const records=Array.from({length:101},(_,i)=>row(i));api.orders.mockImplementation(async q=>({data:{items:records.slice(q.offset,q.offset+q.limit),total:101,offset:q.offset,limit:q.limit}}));const {w}=show(true);await flushPromises();expect(w.text()).toContain('101');expect(w.text()).toContain('1,246.90');expect(api.user).toHaveBeenCalledTimes(1);await w.get('[aria-label="搜索订单"] ').setValue('order-100');await w.get('form').trigger('submit');expect(w.get('tbody').text()).toContain('order-100');expect(w.findAll('tbody tr')).toHaveLength(1);});
it('only shows manual applications in the review workspace',async()=>{api.orders.mockResolvedValue({data:{items:[row(),{...row(1),order:{...row(1).order,source:'PROVIDER'}}],total:2}});const {w}=show(true);await flushPromises();expect(w.findAll('tbody tr')).toHaveLength(1);expect(w.get('tbody').text()).toContain('order-0');expect(w.text()).not.toContain('人工减少');});
it('does not request or retain sensitive orders after permission changes',async()=>{const {w,currentAdmin}=show();await flushPromises();currentAdmin.value={role:'API_USER',primary:false,username:'api'};await flushPromises();expect(w.text()).toContain('无权限');expect(w.text()).not.toContain('真实用户');});
it('exports original four decimal amounts with formula escaping and without transaction secrets',async()=>{api.user.mockResolvedValue({data:{user:{id:'owner',nickname:'=SUM(1,2)',source:'ADMIN'}}});let blob:Blob|undefined;const create=URL.createObjectURL,revoke=URL.revokeObjectURL;URL.createObjectURL=vi.fn(b=>{blob=b;return 'blob:csv';});URL.revokeObjectURL=vi.fn();const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});try{const {w}=show();await flushPromises();await w.findAll('button').find(b=>b.text()==='导出订单')!.trigger('click');const content=await new Promise<string>(resolve=>{const r=new FileReader();r.onload=()=>resolve(String(r.result));r.readAsText(blob!)});expect(content).toContain('"12.3456"');expect(content).toContain('"\'=SUM(1,2)"');expect(content).not.toContain('checkout');}finally{URL.createObjectURL=create;URL.revokeObjectURL=revoke;click.mockRestore();}});
it('labels and filters corporate receipts without classifying them as online payments',async()=>{api.orders.mockResolvedValue({data:{items:[row(),{...row(1),order:{...row(1).order,source:'CORPORATE',accountType:'API'}}],total:2}});const {w}=show();await flushPromises();expect(w.get('tbody').text()).toContain('对公转账');await w.get('[aria-label="支付方式"]').setValue('CORPORATE');await w.get('form').trigger('submit');expect(w.findAll('tbody tr')).toHaveLength(1);expect(w.get('tbody').text()).toContain('order-1');expect(w.get('.payment-method-pill svg').attributes('stroke')).toBe('currentColor');});
it('uses confirmed refund time in Shanghai for daily totals and changes, independent of list filters',async()=>{
 const refund=(i:number,amount4:string,refundedAt:string)=>({...row(i),order:{...row(i).order,status:'REFUNDED',createdAt:'2026-08-01T00:00:00Z',amount4},paidAt:'2026-08-02T00:00:00Z',refundedAt});
 const items=[refund(0,'20.0000','2026-09-19T16:00:00Z'),refund(1,'10.0000','2026-09-19T15:59:59Z')];
 api.orders.mockResolvedValue({data:{items,total:2}});const {w}=show();await flushPromises();
 const card=w.findAll('.payment-stats article')[2]!;
 expect(card.get('strong').text()).toBe('¥ 20.00');expect(card.get('p').text()).toBe('较昨日 +100%');
 await w.get('[aria-label="搜索订单"]').setValue('no matching order');await w.get('form').trigger('submit');
 expect(card.get('strong').text()).toBe('¥ 20.00');
});
it('does not invent refund totals for historical refunds without a completion timestamp',async()=>{
 api.orders.mockResolvedValue({data:{items:[{...row(),order:{...row().order,status:'REFUNDED'},refundedAt:null}],total:1}});
 const {w}=show();await flushPromises();const card=w.findAll('.payment-stats article')[2]!;
 expect(card.get('strong').text()).toBe('¥ —');expect(card.get('p').text()).toBe('较昨日 —');expect(card.find('.payment-stat-trend').exists()).toBe(false);
});
it('shows a genuine zero-refund total and no percentage when yesterday has a zero base',async()=>{
 const {w}=show();await flushPromises();const card=w.findAll('.payment-stats article')[2]!;
 expect(card.get('strong').text()).toBe('¥ 0.00');expect(card.get('p').text()).toBe('较昨日 —');
});

it('only exposes funding categories supported by the order data',async()=>{
 const {w}=show(true);await flushPromises();
 expect(w.text()).not.toMatch(/人工减少|退款调整|活动赠送/);
 expect(w.find('[aria-label="申请类型"]').exists()).toBe(false);
 expect(w.get('[aria-label="资金申请分类"]').text()).toContain('人工增加');
});
