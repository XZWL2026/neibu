import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {enableAutoUnmount,flushPromises,mount} from '@vue/test-utils';
import {ref} from 'vue';
import {createMemoryHistory,createRouter} from 'vue-router';
import AgentDetail from './AgentDetail.vue';
import {adminSessionKey,type AdminIdentity} from './adminSession';
import {configureGateway} from './api';
enableAutoUnmount(afterEach);
const identity=ref<AdminIdentity|null>(null),calls=vi.fn();
const root='/api/v1/admin/promotions';
let detail:ReturnType<typeof facts>;
function facts(){return {agent:{userId:'agent-2',nickname:'真实代理二',phone:'+8613812345678',enabled:true,createdAt:'2026-09-18T16:00:00Z',source:'APPLICATION',invitationCode:'PSECOND',invitedCount:30,paidCount:17,totalRechargeAmount4:'9007199254740993.1234',totalCommissionAmount4:'12.3456',pendingCommissionAmount4:'6.6666'},personalPaidCount:12,enterprisePaidCount:3,apiPaidCount:2,paidPercent:'56.66',withdrawnAmount4:'4.4444',level1CashRate6:'0.123456',level2CashRate6:'0.010000'};}
beforeEach(()=>{
 configureGateway({});identity.value={username:'primary',role:'ADMIN',primary:true,userId:'admin'};detail=facts();
 vi.stubGlobal('fetch',calls);calls.mockReset().mockImplementation(async(path:string,init:RequestInit={})=>{
  const url=new URL(path,'http://local');
  if(url.pathname.endsWith('/auth/session'))return Response.json({authenticated:true,csrfToken:'csrf'});
  if(url.pathname===`${root}/agent-directory/agent-2`)return Response.json(detail);
  if(url.pathname===`${root}/agent-directory/agent-2/downlines`)return Response.json({items:[],total:0,page:0,size:20});
  if(url.pathname===`${root}/benefit-settings`){
   if(init.method==='POST')return Response.json({version:8});
   return Response.json({version:7,global:{defaultLevel1CashRate:'0.100000'},promoters:[{id:'agent-2',level1CashRate:'0.123456'}]});
  }
  if(url.pathname===`${root}/promoters/agent-2/status`&&init.method==='PATCH'){
   detail.agent.enabled=JSON.parse(String(init.body)).status==='ENABLED';return Response.json({userId:'agent-2',status:detail.agent.enabled?'ENABLED':'DISABLED'});
  }
  throw Error('Unexpected request '+path);
 });
});
afterEach(()=>{vi.unstubAllGlobals();vi.restoreAllMocks();});
async function openDetail(){
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{template:'<div/>'}}]});await router.push('/');await router.isReady();
 const w=mount(AgentDetail,{props:{userId:'agent-2',credentialsLoaded:true,entering:false},global:{plugins:[router],provide:{[adminSessionKey as symbol]:{currentAdmin:identity}}}});await flushPromises();return w;
}
async function button(w:Awaited<ReturnType<typeof openDetail>>,label:string){await w.findAll('button').find(b=>b.text()===label)!.trigger('click');await flushPromises();}
async function rateDraft(w:Awaited<ReturnType<typeof openDetail>>){await button(w,'调整');await w.get('input[type="number"]').setValue('23.4567');await w.get('input[maxlength="2000"]').setValue('核准代理收益调整');}
const mutations=()=>calls.mock.calls.filter(([,i])=>['POST','PATCH'].includes(i?.method));
it('reads the requested agent and preserves four-place money beyond Number precision',async()=>{
 const w=await openDetail();expect(calls.mock.calls[0]![0]).toBe(`${root}/agent-directory/agent-2`);
 expect(w.get('.agent-detail-profile').text()).toContain('真实代理二');
 for(const value of ['30','17','12','3','2','9007199254740993.1234','12.3456','6.6666','4.4444','56.66%','12.3456%'])expect(w.get('[aria-label="代理核心数据"]').text()).toContain(value);
 await button(w,'代理设置');expect(w.text()).toContain('推广申请');expect(w.text()).toContain('agent-2');
 expect(mutations()).toEqual([]);
});
it('keeps NORMAL_ADMIN reads free of privileged settings account and financial requests',async()=>{
 identity.value={username:'normal',role:'NORMAL_ADMIN',primary:false};const w=await openDetail();
 expect(w.text()).not.toContain('调整');expect(w.text()).not.toContain('进入代理后台');expect(w.text()).not.toContain('收益记录');expect(w.text()).not.toContain('提现记录');
 await button(w,'代理设置');expect(w.text()).not.toContain('停用代理');expect(w.text()).not.toContain('设置登录账号');expect(w.text()).not.toContain('管理登录账号');
 expect(calls.mock.calls.map(([p])=>new URL(p,'http://local').pathname)).toEqual([`${root}/agent-directory/agent-2`,`${root}/agent-directory/agent-2/downlines`]);
 expect(mutations()).toEqual([]);
});
it('rejects another agent returned for the requested route',async()=>{
 detail.agent.userId='wrong-agent';const w=await openDetail();expect(w.get('[role="alert"]').text()).toContain('代理详情读取失败');expect(w.find('.agent-detail-profile').exists()).toBe(false);expect(calls).toHaveBeenCalledTimes(1);
});
it('ignores a detail reply after unmount without launching child record requests',async()=>{
 let resolve!:(r:Response)=>void;calls.mockImplementation(()=>new Promise<Response>(r=>{resolve=r;}));
 const w=await openDetail();w.unmount();resolve(Response.json(detail));await flushPromises();expect(calls).toHaveBeenCalledTimes(1);expect(w.emitted('changed')).toBeUndefined();
});
it('updates status through the real endpoint and retries an uncertain result with the same command',async()=>{
 const base=calls.getMockImplementation()!;let fail=true;
 calls.mockImplementation(async(p,i)=>p.endsWith('/status')&&fail?Promise.reject(new TypeError('connection lost')):base(p,i));
 const w=await openDetail();await button(w,'代理设置');await button(w,'停用代理');
 expect(w.get('[role="alert"]').text()).toContain('代理状态更新未确认');expect(w.emitted('changed')).toBeUndefined();
 fail=false;await button(w,'停用代理');const writes=mutations();expect(writes).toHaveLength(2);expect(writes[0]![0]).toBe(`${root}/promoters/agent-2/status`);expect(writes[0]![1].method).toBe('PATCH');expect(writes[0]![1].body).toBe(writes[1]![1].body);
 expect(JSON.parse(writes[0]![1].body)).toMatchObject({commandId:expect.any(String),status:'DISABLED',changedAt:expect.any(String)});
 expect(w.text()).toContain('启用代理');expect(w.emitted('changed')).toHaveLength(1);
});
it('validates rate input and submits exact six-place values against the fetched version',async()=>{
 const w=await openDetail();await rateDraft(w);await w.get('input[maxlength="2000"]').setValue('');await w.get('form').trigger('submit');await flushPromises();expect(mutations()).toEqual([]);
 await w.get('input[maxlength="2000"]').setValue('核准代理收益调整');await w.get('form').trigger('submit');await flushPromises();
 expect(mutations()).toHaveLength(1);const [path,init]=mutations()[0]!;expect(path).toBe(`${root}/benefit-settings`);
 expect(JSON.parse(init.body)).toEqual({commandId:expect.any(String),expectedVersion:7,promoterId:'agent-2',field:'level1CashRate',rate:'0.234567',reason:'核准代理收益调整'});expect(w.emitted('changed')).toHaveLength(1);
});
it('locks and retains an uncertain rate command for an exact retry',async()=>{
 const base=calls.getMockImplementation()!;let fail=true;calls.mockImplementation(async(p,i)=>p.endsWith('/benefit-settings')&&i?.method==='POST'&&fail?Promise.reject(new TypeError('connection lost')):base(p,i));
 const w=await openDetail();await rateDraft(w);await w.get('form').trigger('submit');await flushPromises();
 expect(w.get('input[type="number"]').attributes('disabled')).toBeDefined();expect(w.emitted('changed')).toBeUndefined();
 fail=false;await w.get('form').trigger('submit');await flushPromises();expect(mutations()).toHaveLength(2);expect(mutations()[0]![1].body).toBe(mutations()[1]![1].body);expect(w.emitted('changed')).toHaveLength(1);
});
it('requires reopening settings after a rate version conflict',async()=>{
 const base=calls.getMockImplementation()!;calls.mockImplementation(async(p,i)=>p.endsWith('/benefit-settings')&&i?.method==='POST'?Response.json({code:'VERSION_CONFLICT'},{status:409}):base(p,i));
 const w=await openDetail();await rateDraft(w);await w.get('form').trigger('submit');await flushPromises();
 expect(w.get('[role="alert"]').text()).toContain('收益配置已变化');expect(w.findAll('button').find(b=>b.text()==='确认调整')!.attributes('disabled')).toBeDefined();expect(w.emitted('changed')).toBeUndefined();
});
it('uses the alert retry action to replay the uncertain rate command',async()=>{
 const base=calls.getMockImplementation()!;let fail=true;calls.mockImplementation(async(p,i)=>p.endsWith('/benefit-settings')&&i?.method==='POST'&&fail?Promise.reject(new TypeError('response lost')):base(p,i));
 const w=await openDetail();await rateDraft(w);await w.get('form').trigger('submit');await flushPromises();const first=mutations()[0];
 fail=false;await w.get('[role="alert"] button').trigger('click');await flushPromises();
 expect(mutations()).toHaveLength(2);expect(mutations()[1]![1].body).toBe(first![1].body);expect(w.emitted('changed')).toHaveLength(1);
});
