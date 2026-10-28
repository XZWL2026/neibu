import {mount,flushPromises,enableAutoUnmount} from '@vue/test-utils';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import AgentStatistics from './AgentStatistics.vue';
import {configureGateway} from './api';
enableAutoUnmount(afterEach);
afterEach(()=>vi.unstubAllGlobals());
const request=vi.fn();
beforeEach(()=>{configureGateway({});vi.stubGlobal('fetch',request);request.mockReset().mockImplementation(async(path:string)=>{
 const url=new URL(path,'http://local');
 if(url.pathname.endsWith('/downlines'))return Response.json({items:[{nickname:'下级甲',userId:'downline-a',phone:'138****1234',accountType:'PERSONAL',enabled:true,paid:true,boundAt:'2026-09-12T00:00:00Z',totalRechargeAmount4:'20.1234'}],total:21,page:Number(url.searchParams.get('page')),size:20});
 if(url.pathname.endsWith('/recharges'))return Response.json({items:[{nickname:'充值乙',maskedPhone:'139****5678',orderId:'order-1',paidAmount4:'50.1234',refundedPaidAmount4:'2.1234',commissionAmount4:'5.0123'}],total:1,page:0,size:20});
 if(url.pathname.endsWith('/benefit-audit'))return Response.json({items:[{id:'earning-1',action:'COMMISSION_RELEASED',amount4:'9007199254740993.1234',orderId:'paid-order'}],total:1,page:0,size:20});
 if(url.pathname.endsWith('/payouts'))return Response.json({items:[{id:'withdrawal-1',amount4:'12.3456',status:'PAID',maskedAccountReference:'****1234'}],total:1,page:0,size:20});
 if(url.pathname.endsWith('/operations'))return Response.json({items:[{id:'operation-1',type:'ADMIN_PROMOTER_CREATE',operatorId:'主管理员'}],total:1,page:0,size:20});
 if(url.pathname.endsWith('/consumptions'))return Response.json({items:[{nickname:'消费丙',maskedPhone:'139****9999',type:'VIDEO',points4:'8.0000',balanceBeforePoints4:'20.0000',balanceAfterPoints4:'12.0000',paidAmount4:'0.8000',commissionAmount4:'0.0800'}],total:1,page:0,size:20});
 throw new Error(path);
});});
it('paginates real downlines and shows recharge yuan separately from consumption points',async()=>{
 const w=mount(AgentStatistics,{props:{userId:'agent-a'}});await flushPromises();expect(w.text()).toContain('下级甲');expect(w.text()).toContain('¥ 20.1234');
 await w.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();expect(request.mock.calls.at(-1)![0]).toContain('page=1');
 await w.findAll('button').find(b=>b.text()==='充值记录')!.trigger('click');await flushPromises();expect(request.mock.calls.at(-1)![0]).toContain('page=0');expect(w.text()).toContain('充值乙');expect(w.text()).toContain('¥ 50.1234');expect(w.text()).not.toContain('下级甲');
 await w.findAll('button').find(b=>b.text()==='消费记录')!.trigger('click');await flushPromises();for(const text of ['视频','20 积分','8 积分','12 积分','¥ 0.8','¥ 0.08'])expect(w.text()).toContain(text);
});
it('keeps a read failure distinct from an empty or zero-valued result',async()=>{
 request.mockRejectedValue(new Error('offline'));const w=mount(AgentStatistics,{props:{userId:'agent-a'}});await flushPromises();expect(w.get('[role=alert]').text()).toContain('读取失败');expect(w.text()).not.toContain('共 0 条');expect(w.text()).not.toContain('暂无记录');
});
it('discards a delayed response when another agent is selected',async()=>{
 let resolve!:(response:Response)=>void;request.mockImplementationOnce(()=>new Promise<Response>(r=>{resolve=r;}));
 const w=mount(AgentStatistics,{props:{userId:'agent-a'}});await w.setProps({userId:'agent-b'});await flushPromises();resolve(Response.json({items:[{nickname:'旧代理私有记录'}],total:1}));await flushPromises();expect(w.text()).toContain('下级甲');expect(w.text()).not.toContain('旧代理私有记录');expect(request.mock.calls[1]![0]).toContain('agent-b');
});

it('keeps the last successful page when the next page fails and retries that page',async()=>{
 const w=mount(AgentStatistics,{props:{userId:'agent-a'}});await flushPromises();request.mockRejectedValueOnce(new Error('next page failed'));
 await w.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();expect(w.get('[role=alert]').text()).toContain('读取失败');expect(w.text()).toContain('第 1 页');expect(w.text()).toContain('下级甲');
 await w.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();expect(request.mock.calls.at(-1)![0]).toContain('page=1');expect(w.text()).toContain('第 2 页');
});

it('searches and filters all downlines through the server and resets pagination',async()=>{
 const w=mount(AgentStatistics,{props:{userId:'agent-a'}});await flushPromises();
 await w.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();
 await w.get('input').setValue('真实手机号');await w.get('select').setValue('API');await w.get('form').trigger('submit');await flushPromises();
 const url=new URL(request.mock.calls.at(-1)![0],'http://local');expect(url.pathname).toBe('/api/v1/admin/promotions/agent-directory/agent-a/downlines');expect(url.searchParams.get('search')).toBe('真实手机号');expect(url.searchParams.get('accountType')).toBe('API');expect(url.searchParams.get('page')).toBe('0');
});
it('scopes financial records to the selected administrator agent without converting money to Number',async()=>{
 const w=mount(AgentStatistics,{props:{userId:'agent-private',administrator:true,primary:true}});await flushPromises();
 await w.findAll('button').find(b=>b.text()==='收益记录')!.trigger('click');await flushPromises();
 let url=new URL(request.mock.calls.at(-1)![0],'http://local');expect(url.pathname).toContain('benefit-audit');expect(url.searchParams.get('userId')).toBe('agent-private');expect(url.searchParams.get('unit')).toBe('CASH');expect(w.text()).toContain('9007199254740993.1234');
 await w.findAll('button').find(b=>b.text()==='提现记录')!.trigger('click');await flushPromises();
 url=new URL(request.mock.calls.at(-1)![0],'http://local');expect(url.searchParams.get('promoterUserId')).toBe('agent-private');expect(w.text()).toContain('¥ 12.3456');expect(w.text()).toContain('已到账');
 await w.findAll('button').find(b=>b.text()==='操作日志')!.trigger('click');await flushPromises();expect(w.text()).toContain('新增代理');expect(w.text()).toContain('主管理员');
});
it('never offers or calls privileged financial reads to normal administrators, including after a role change',async()=>{
 const w=mount(AgentStatistics,{props:{userId:'agent-a',administrator:true,primary:true}});await flushPromises();
 await w.findAll('button').find(b=>b.text()==='收益记录')!.trigger('click');await flushPromises();request.mockClear();
 await w.setProps({primary:false});await flushPromises();expect(w.findAll('button').some(b=>['收益记录','提现记录'].includes(b.text()))).toBe(false);
 await w.findAll('button').find(b=>b.text()==='操作日志')!.trigger('click');await flushPromises();expect(w.text()).toContain('新增代理');
 expect(request.mock.calls.every(([url])=>!url.includes('/benefit-audit')&&!url.includes('/payouts')&&!url.includes('/commissions')&&!url.includes('/withdrawals'))).toBe(true);
});
it('aborts pending requests when unmounted',async()=>{
 let signal:AbortSignal|undefined;request.mockImplementationOnce((_url,options)=>{signal=options.signal;return new Promise(()=>{});});
 const w=mount(AgentStatistics,{props:{userId:'agent-a'}});await flushPromises();expect(signal?.aborted).toBe(false);w.unmount();expect(signal?.aborted).toBe(true);
});
it('exports every matching page with raw decimal strings and escaped spreadsheet formulas',async()=>{
 const makeUrl=vi.fn((_blob:Blob)=> 'blob:export');vi.stubGlobal('URL',class extends URL {static createObjectURL=makeUrl;static revokeObjectURL=vi.fn();});
 vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});
 const w=mount(AgentStatistics,{props:{userId:'agent-a'}});await flushPromises();await w.get('input').setValue('导出筛选');await w.get('select').setValue('API');await w.get('form').trigger('submit');await flushPromises();
 request.mockImplementation(async(path:string)=>{const url=new URL(path,'http://local');return Response.json({items:[{userId:'u'+url.searchParams.get('page'),nickname:url.searchParams.get('page')==='0'?'=SUM(1,2)':' \t=尾页',phone:'+8613812345678',accountType:'API',enabled:true,totalRechargeAmount4:'12.3456'}],total:101,page:Number(url.searchParams.get('page')),size:100});});
 await w.findAll('button').find(b=>b.text()==='导出')!.trigger('click');await flushPromises();
 expect(request.mock.calls.at(-1)![0]).toContain('page=1');expect(request.mock.calls.at(-1)![0]).toContain('size=100');expect(request.mock.calls.at(-1)![0]).toContain('accountType=API');
 const blob=makeUrl.mock.calls[0]![0] as unknown as Blob;const content=await new Promise<string>(resolve=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result));reader.readAsText(blob);});
 expect(content).toContain("'=SUM(1,2)");expect(content).toContain("'+8613812345678");expect(content).toContain("' \t=尾页");expect(content).toContain('12.3456');vi.restoreAllMocks();
});
