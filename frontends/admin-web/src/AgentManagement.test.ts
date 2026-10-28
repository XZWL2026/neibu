import {webcrypto,createHash} from 'node:crypto';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import AgentManagement from './AgentManagement.vue';
import { adminSessionKey, type AdminIdentity } from './adminSession';
import { configureGateway } from './api';
import * as apiModule from './api';
import * as cacheModule from './localPersistence';
import { axe } from 'vitest-axe';
enableAutoUnmount(afterEach);
afterEach(() => vi.unstubAllGlobals());
const calls = vi.fn();
const identity = ref<AdminIdentity | null>(null);
let credentials: Record<string, unknown>[];
let agents: Record<string, unknown>[];
const directoryAgent=(agent:Record<string,unknown>)=>({...agent,nickname:agent.userId==='user-1'?'真实用户':'停用代理',phone:'+8613800011234',enabled:agent.status==='ENABLED',source:'ADMIN',registeredAt:'2026-09-01T00:00:00Z',personalPaidCount:1,enterprisePaidCount:0,apiPaidCount:1,invitedCount:3,paidCount:2,totalRechargeAmount4:'1234.5678',totalCommissionAmount4:'98.7654',pendingCommissionAmount4:'9007199254740993.1234'});
const summary={totalAgents:109,todayNewAgents:8,todayReferredUsers:17,paidDownlineUsers:53,pendingCommissionAmount4:'9999999999999999.4321',yesterday:{totalAgents:97,newAgents:4,referredUsers:20,paidDownlineUsers:35,pendingCommissionAmount4:'9433962264150942.8605'}};
const directoryPage=(page=0,total=agents.length)=>({items:agents.map(directoryAgent),summary,total,page,size:10});
const detailData=(id='user-1')=>({agent:directoryAgent(agents.find(a=>a.userId===id)!),personalPaidCount:1,enterprisePaidCount:0,apiPaidCount:1,paidPercent:'66.66',withdrawnAmount4:'321.1234',level1CashRate6:'0.123456',level2CashRate6:'0.010000'});
const owner = { id:'user-1',nickname:'真实用户',phone:'+8613800011234',enabled:true };
beforeEach(() => {
 configureGateway({}); identity.value={username:'primary',role:'ADMIN',primary:true,userId:'admin'};
 credentials=[]; agents=[{userId:'user-1',invitationCode:'P123',status:'ENABLED',createdAt:'2026-09-11T00:00:00Z'}];
 vi.stubGlobal('fetch',calls); calls.mockReset().mockImplementation(async(path:string,init:RequestInit={})=>{
  const url=new URL(path,'http://local'); const body=init.body?JSON.parse(String(init.body)):{};
  if(url.pathname.endsWith('/auth/session'))return Response.json({authenticated:true,csrfToken:'csrf'});
  if(url.pathname==='/api/v1/admin/promotions/agent-directory')return Response.json(directoryPage(Number(url.searchParams.get('page')||0)));
  if(/\/agent-directory\/[^/]+$/.test(url.pathname))return Response.json(detailData(decodeURIComponent(url.pathname.split('/').at(-1)!)));
  if(/\/agent-directory\/[^/]+\/(downlines|operations)$/.test(url.pathname))return Response.json({items:[],total:0,page:0,size:20});
  if(/\/promoters\/[^/]+\/(downlines|recharges|consumptions)$/.test(url.pathname))return Response.json({items:[],total:0,page:0,size:20});
  if(url.pathname==='/api/v1/admin/promotions/promoters') {
   if(init.method==='POST')return Response.json({userId:body.userId,invitationCode:'PNEW',status:'ENABLED'});
   return Response.json({items:agents,total:agents.length,page:Number(url.searchParams.get('page')||0),size:20});
  }
  if(url.pathname==='/api/v1/admin/admins') {
   if(init.method==='POST'){const credential={id:'credential',...body,enabled:true};credentials.push(credential);return Response.json(credential,{status:201});}
   return Response.json(credentials);
  }
  if(url.pathname==='/api/v1/admin/users')return Response.json({items:Array.from({length:20},(_,i)=>i?{...owner,id:`candidate-${i}`} :owner),total:51,offset:Number(url.searchParams.get('offset')||0),limit:20});
  if(url.pathname==='/api/v1/admin/users/user-1')return Response.json(owner);
  throw new Error('Unexpected request '+path);
 });
 Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:function(this:HTMLDialogElement){this.setAttribute('open','');}});
 Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:function(this:HTMLDialogElement){this.removeAttribute('open');}});
});
afterEach(()=>vi.restoreAllMocks());
async function openPage(path='/admin/agents') {
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/agents',component:AgentManagement},{path:'/admin/agents/:agentRef',component:AgentManagement},{path:'/admin/admins',component:{template:'<div/>'}}]});
 await router.push(path);await router.isReady();
 const wrapper=mount(AgentManagement,{attachTo:document.body,global:{plugins:[router],provide:{[adminSessionKey as symbol]:{currentAdmin:identity}}}});
 await flushPromises();return wrapper;
}
async function draft(w:Awaited<ReturnType<typeof openPage>>) {
 await w.get('button[data-testid="add-agent"]').trigger('click');await flushPromises();
 const select=w.getComponent({name:'AdminSearchSelect'});
 select.vm.$emit('query','13800011234');await flushPromises();
 select.vm.$emit('update:modelValue','user-1');await flushPromises();
 await w.get('[aria-label="登录用户名"]').setValue('new.agent');
}
it('reads all real agents including disabled ones and refreshes only the list',async()=>{
 agents.push({userId:'user-2',invitationCode:'PDIS',status:'DISABLED'});
 const base=calls.getMockImplementation()!;calls.mockImplementation(async(p,i)=>p==='/api/v1/admin/users/user-2'?Response.json({...owner,id:'user-2',nickname:'停用代理'}):base(p,i));
 const w=await openPage();expect(w.text()).toContain('真实用户');expect(w.text()).toContain('停用代理');expect(w.text()).toContain('已停用');
 expect(w.text()).not.toContain('AGT-2046');
 await w.get('[data-testid="refresh-agents"]').trigger('click');await flushPromises();
 expect(calls.mock.calls.filter(([p])=>p.startsWith('/api/v1/admin/promotions/agent-directory'))).toHaveLength(2);
});
it('queries users by phone on the server and can load later result pages',async()=>{
 const w=await openPage();await draft(w);
 expect(calls.mock.calls.some(([p])=>p.includes('/admin/users?')&&p.includes('search=13800011234'))).toBe(true);
 w.getComponent({name:'AdminSearchSelect'}).vm.$emit('load-more');await flushPromises();
 expect(calls.mock.calls.some(([p])=>p.includes('offset=20')&&p.includes('search=13800011234'))).toBe(true);
});
it('provisions before creating a bound login and closes only after both succeed',async()=>{
 const w=await openPage();await draft(w);await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 const posts=calls.mock.calls.filter(([,i])=>i?.method==='POST');
 expect(posts.map(([p])=>p)).toEqual(['/api/v1/admin/promotions/promoters','/api/v1/admin/admins']);
 expect(JSON.parse(posts[1]![1].body)).toMatchObject({username:'new.agent',role:'PROMOTER',boundUserId:'user-1'});
 const generated=JSON.parse(posts[1]![1].body).password;
 expect(generated).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
 expect(w.find('input[type="password"]').exists()).toBe(false);
 expect(w.html()).not.toContain(generated);
 expect(w.get('dialog.agent-dialog').attributes('open')).toBeUndefined();
});
it('keeps a provisioned user after login failure and retries only login',async()=>{
 const base=calls.getMockImplementation()!;let fail=true;
 calls.mockImplementation(async(p,i)=>p==='/api/v1/admin/admins'&&i?.method==='POST'&&fail?Response.json({code:'USERNAME_EXISTS'},{status:409}):base(p,i));
 const w=await openPage();await draft(w);await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 expect(w.get('dialog.agent-dialog').text()).toContain('用户名已存在');expect(w.get('dialog.agent-dialog').attributes('open')).toBeDefined();
 expect(w.getComponent({name:'AdminSearchSelect'}).props('disabled')).toBe(true);
 fail=false;await w.get('[aria-label="登录用户名"]').setValue('corrected.agent');await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 expect(calls.mock.calls.filter(([p,i])=>p==='/api/v1/admin/promotions/promoters'&&i?.method==='POST')).toHaveLength(1);
 expect(w.get('dialog.agent-dialog').attributes('open')).toBeUndefined();
});
it('does not provision when the login name already belongs to another account',async()=>{
 credentials=[{id:'other',username:'new.agent',role:'API_USER',boundUserId:'another-user'}];
 const w=await openPage();await draft(w);await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 expect(w.get('dialog.agent-dialog').text()).toContain('用户名已存在');
 expect(calls.mock.calls.filter(([,i])=>i?.method==='POST')).toHaveLength(0);
});
it('gives ordinary admins read-only access and makes no requests for portal roles',async()=>{
 identity.value={username:'normal',role:'NORMAL_ADMIN',primary:false};const w=await openPage();
 expect(w.text()).toContain('真实用户');expect(w.find('[data-testid="add-agent"]').exists()).toBe(false);
 expect(calls.mock.calls.some(([p])=>p==='/api/v1/admin/admins')).toBe(false);
 w.unmount();calls.mockClear();identity.value={username:'promoter',role:'PROMOTER',userId:'user-1'};const denied=await openPage();
 expect(denied.text()).toContain('无权限');expect(calls).not.toHaveBeenCalled();
});
it('preserves drafts on unchanged session refresh and clears them on expiry',async()=>{
 const w=await openPage();await draft(w);identity.value={...identity.value!};await flushPromises();
 expect(w.get('[aria-label="登录用户名"]').element).toHaveProperty('value','new.agent');
 identity.value=null;await flushPromises();expect(w.find('dialog').exists()).toBe(false);expect(w.text()).not.toContain('真实用户');
});
it('reconciles an accepted login whose response was lost without issuing a second creation',async()=>{
 const base=calls.getMockImplementation()!;
 calls.mockImplementation(async(p,i)=>{const r=await base(p,i);if(p==='/api/v1/admin/admins'&&i?.method==='POST')throw new TypeError('connection lost');return r;});
 const w=await openPage();await draft(w);await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 expect(w.get('[aria-label="登录用户名"]').attributes('disabled')).toBeDefined();
 await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 expect(w.get('dialog.agent-dialog').attributes('open')).toBeUndefined();
 expect(calls.mock.calls.filter(([p,i])=>p==='/api/v1/admin/admins'&&i?.method==='POST')).toHaveLength(1);
});
it('retries an uncertain provision using the same command and selected user',async()=>{
 const base=calls.getMockImplementation()!;let fail=true;
 calls.mockImplementation(async(p,i)=>{if(p==='/api/v1/admin/promotions/promoters'&&i?.method==='POST'&&fail)throw new TypeError('connection lost');return base(p,i);});
 const w=await openPage();await draft(w);await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 expect(w.getComponent({name:'AdminSearchSelect'}).props('disabled')).toBe(true);
 fail=false;await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 const posts=calls.mock.calls.filter(([p,i])=>p==='/api/v1/admin/promotions/promoters'&&i?.method==='POST');
 expect(posts).toHaveLength(2);expect(posts[0]![1].body).toBe(posts[1]![1].body);
});
it('discards a late list after identity expiry without fetching more user details',async()=>{
 let resolve!:(response:Response)=>void;
 const base=calls.getMockImplementation()!;calls.mockImplementation((p,i)=>p.includes('/agent-directory?')?new Promise<Response>(r=>{resolve=r;}):base(p,i));
 const w=await openPage();expect(calls).toHaveBeenCalledTimes(2);identity.value=null;await flushPromises();
 resolve(Response.json(directoryPage()));await flushPromises();
 expect(w.text()).toContain('无权限');expect(w.text()).not.toContain('真实用户');expect(calls).toHaveBeenCalledTimes(2);
});
it('filters real detail links by user ID and requests later agent pages',async()=>{
 const detail=await openPage('/admin/agents/user-1');
 expect(calls.mock.calls.some(([p])=>p==='/api/v1/admin/promotions/agent-directory/user-1')).toBe(true);
 expect(detail.get('h1').text()).toBe('代理管理');detail.unmount();
 const base=calls.getMockImplementation()!;
 calls.mockImplementation(async(p,i)=>new URL(p,'http://local').pathname==='/api/v1/admin/promotions/agent-directory'?Response.json(directoryPage(0,41)):base(p,i));
 const w=await openPage();await w.get('[aria-label="下一页"]').trigger('click');await flushPromises();
 expect(calls.mock.calls.some(([p])=>p.includes('page=1'))).toBe(true);
});
it('reconciles a conflicting binding without replacing an existing account password',async()=>{
 const base=calls.getMockImplementation()!;
 calls.mockImplementation(async(p,i)=>{
  if(p==='/api/v1/admin/admins'&&i?.method==='POST'){
   credentials=[{id:'existing',username:'actual.agent',boundUserId:'user-1',role:'PROMOTER',enabled:true}];
   return Response.json({code:'PORTAL_ACCOUNT_EXISTS'},{status:409});
  }return base(p,i);
 });
 const w=await openPage();await draft(w);await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 expect(w.get('dialog.agent-dialog').text()).toContain('actual.agent');
 expect(calls.mock.calls.filter(([p,i])=>p==='/api/v1/admin/admins'&&i?.method==='POST')).toHaveLength(1);
 expect(calls.mock.calls.some(([p])=>p.endsWith('/password'))).toBe(false);
});
it('labels the dialog accessibly and clears drafts on keyboard cancel',async()=>{
 const w=await openPage();await draft(w);
 expect((await axe(w.get('dialog.agent-dialog').element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
 await w.get('dialog.agent-dialog').trigger('cancel');await flushPromises();
 expect(w.get('dialog.agent-dialog').attributes('open')).toBeUndefined();
 expect(w.get('[aria-label="登录用户名"]').element).toHaveProperty('value','');
 expect(document.activeElement).toBe(w.get('[data-testid="add-agent"]').element);
});

it('integrates real statistics, detail navigation and login management',async()=>{
 credentials=[{id:'login-1',username:'agent.login',role:'PROMOTER',boundUserId:'user-1',enabled:true}];
 const w=await openPage();
 for(const text of ['推广用户','付费用户','累计充值','累计收益','1234.5678','98.7654'])expect(w.text()).toContain(text);
 expect(w.get('tbody tr td:nth-child(3)').text()).toBe('3');
 expect(w.text()).not.toMatch(/一级|二级/);
 expect(w.get('[data-testid="agent-detail-user-1"]').text()).toBe('查看');
 expect(w.text()).not.toContain('user-1');
 expect(w.get('[data-testid="add-agent"]').text()).toContain('新增代理');
 w.unmount();const detail=await openPage('/admin/agents/user-1');
 expect(detail.text()).toContain('9007199254740993.1234');expect(detail.text()).toContain('下级用户');
 expect(detail.findAll('.agent-statistics th').map(th=>th.text())).not.toContain('级别');
 await detail.findAll('button').find(b=>b.text()==='代理设置')!.trigger('click');
 expect(detail.get('.agent-detail-drawer').text()).toContain('agent.login');
});

it('keeps the successful agent page when a later page cannot be read',async()=>{
 const base=calls.getMockImplementation()!;let fail=false;
 calls.mockImplementation(async(p,i)=>{if(p.includes('/agent-directory')){if(fail)throw new Error('offline');return Response.json(directoryPage(0,41));}return base(p,i);});
 const w=await openPage();fail=true;await w.get('[aria-label="下一页"]').trigger('click');await flushPromises();expect(w.text()).toContain('第 1 页');expect(w.text()).toContain('代理列表读取失败');
 fail=false;await w.get('[aria-label="下一页"]').trigger('click');await flushPromises();expect(calls.mock.calls.filter(([p])=>p.includes('/agent-directory')).at(-1)![0]).toContain('page=1');expect(w.text()).toContain('第 2 页');
});

function agentTab(){
 const values=new Map<string,string>();
 const tab={closed:false,opener:{},sessionStorage:{clear:()=>values.clear(),setItem:(k:string,v:string)=>values.set(k,v)},location:{replace:vi.fn()},close:vi.fn()};
 const open=vi.fn(()=>tab);vi.stubGlobal('open',open);vi.stubGlobal('crypto',webcrypto);
 return {tab,open,values};
}
function agentCredential(){credentials=[{id:'agent-credential',username:'agent',role:'PROMOTER',boundUserId:'user-1',enabled:true}];}
it('opens an isolated tab synchronously and preserves the administrator session',async()=>{
 agentCredential();const {tab,open,values}=agentTab();const navigate=vi.fn();vi.stubGlobal('location',{assign:navigate});
 const clear=vi.spyOn(apiModule,'clearSession'),cache=vi.spyOn(cacheModule,'clearAdminPageCache');const base=calls.getMockImplementation()!;
 calls.mockImplementation(async(p,i)=>p.endsWith('/agent-ticket')?Response.json({ticket:'d'.repeat(64)}):base(p,i));
 const w=await openPage();clear.mockClear();cache.mockClear();await w.get('[data-testid="enter-agent-user-1"]').trigger('click');
 expect(open).toHaveBeenCalledWith('about:blank','_blank');
 await vi.waitFor(()=>expect(tab.location.replace).toHaveBeenCalled());
 expect(tab.opener).toBeNull();expect(tab.location.replace.mock.calls[0]![0]).toMatch(/^\/agent\/[0-9a-f]{32}\/admin\/portal-entry#d{64}$/);
 const verifier=values.get('xzwl.portal.verifier')!;expect(verifier).toMatch(/^[0-9a-f]{64}$/);
 const issued=calls.mock.calls.find(([p])=>p.endsWith('/agent-ticket'))!;expect(issued[0]).toBe('/api/v1/admin/admins/agent-credential/agent-ticket');
 expect(JSON.parse(issued[1].body).challenge).toBe(createHash('sha256').update(verifier).digest('hex'));
 expect(navigate).not.toHaveBeenCalled();expect(clear).not.toHaveBeenCalled();expect(cache).not.toHaveBeenCalled();
});
it('disables unavailable agent entry and hides it from ordinary admins',async()=>{
 const w=await openPage();expect(w.get('[data-testid="enter-agent-user-1"]').attributes('disabled')).toBeDefined();
 credentials=[{id:'agent-credential',role:'PROMOTER',boundUserId:'user-1',enabled:false}];
 await w.get('[data-testid="refresh-agents"]').trigger('click');await flushPromises();expect(w.get('[data-testid="enter-agent-user-1"]').attributes('disabled')).toBeDefined();
 credentials[0]!.enabled=true;agents[0]!.status='DISABLED';await w.get('[data-testid="refresh-agents"]').trigger('click');await flushPromises();expect(w.get('[data-testid="enter-agent-user-1"]').attributes('disabled')).toBeDefined();
 identity.value={username:'normal',role:'NORMAL_ADMIN'};await flushPromises();expect(w.find('[data-testid="enter-agent-user-1"]').exists()).toBe(false);
});
it('handles blocked popups without issuing a ticket',async()=>{
 agentCredential();vi.stubGlobal('open',vi.fn(()=>null));const w=await openPage();await w.get('[data-testid="enter-agent-user-1"]').trigger('click');await flushPromises();
 expect(calls.mock.calls.some(([p])=>p.endsWith('/agent-ticket'))).toBe(false);expect(w.get('[role="alert"]').text()).toContain('进入代理后台失败');
});
it('prevents duplicate entry and closes the blank tab after failure',async()=>{
 agentCredential();const {tab,open}=agentTab();const base=calls.getMockImplementation()!;let finish!:(r:Response)=>void;
 calls.mockImplementation((p,i)=>p.endsWith('/agent-ticket')?new Promise(r=>{finish=r;}):base(p,i));
 const w=await openPage();const button=w.get('[data-testid="enter-agent-user-1"]');await button.trigger('click');await button.trigger('click');
 await vi.waitFor(()=>expect(finish).toBeTypeOf('function'));expect(open).toHaveBeenCalledTimes(1);expect(button.attributes('disabled')).toBeDefined();
 finish(Response.json({code:'PORTAL_ACCESS_DENIED'},{status:403}));await flushPromises();expect(tab.close).toHaveBeenCalled();expect(tab.location.replace).not.toHaveBeenCalled();expect(w.get('[role="alert"]').text()).toContain('进入代理后台失败');
});
it('finishes the new tab handoff after the source page unmounts',async()=>{
 agentCredential();const {tab}=agentTab();const base=calls.getMockImplementation()!;let finish!:(r:Response)=>void;
 calls.mockImplementation((p,i)=>p.endsWith('/agent-ticket')?new Promise(r=>{finish=r;}):base(p,i));
 const w=await openPage();await w.get('[data-testid="enter-agent-user-1"]').trigger('click');await vi.waitFor(()=>expect(finish).toBeTypeOf('function'));w.unmount();
 finish(Response.json({ticket:'d'.repeat(64)}));await flushPromises();expect(tab.location.replace).toHaveBeenCalled();
});

it('opens the route detail in a fixed labelled drawer while retaining the agent list',async()=>{
 const w=await openPage('/admin/agents/user-1');
 expect(w.get('h1').text()).toBe('代理管理');
 expect(w.find('dialog.agent-detail-drawer[aria-labelledby="agent-detail-title"]').exists()).toBe(true);
 expect(w.get('.agent-detail-drawer').attributes('open')).toBeDefined();
 expect(w.find('.agent-directory-table').exists()).toBe(true);
});


it('shows the reference columns with paid categories and actual user registration time',async()=>{
 const w=await openPage();
 expect(w.findAll('.agent-directory-table th').map(n=>n.text())).toEqual(['代理名称','手机号','推广用户','付费用户','个人','企业','API','累计充值（元）','累计收益（元）','待结算（元）','状态','注册时间','操作']);
 const cells=w.findAll('.agent-directory-table tbody tr:first-child td');
 expect(cells.slice(2,7).map(n=>n.text())).toEqual(['3','2','1','0','1']);
 expect(cells[11].text()).toBe('2026-09-01 08:00');
 expect(cells.slice(2,10).every(n=>n.classes().includes('balance-centered'))).toBe(true);
});
it('uses global summary totals independently of the current page',async()=>{
 const w=await openPage();
 expect(w.findAll('[aria-label="代理统计"] .agent-metric-value').map(n=>n.text())).toEqual(['109','8','17','53','9,999,999,999,999,999.4321']);
 expect(w.findAll('.stat-change>span').map(n=>n.text())).toEqual(['↑ 12','↑ 4','↓ 3','↑ 18','↑ 6%']);
 expect(w.findAll('.stat-change small').map(n=>n.text())).toEqual(Array(5).fill('较昨日'));
 expect(w.findAll('.stat-change.down')).toHaveLength(1);
 expect(w.findAll('tbody tr')).toHaveLength(1);
});
it.each([
 {before:'100.0000',current:'90.1234',expected:'↓ 9.88%'},
 {before:'0.0000',current:'0.0000',expected:'↑ 0%'},
 {before:'0.0000',current:'1.0000',expected:'—'},
 {before:null,current:'1.0000',expected:'—'},
])('compares precise pending commission $before → $current',async({before,current,expected})=>{
 const original=calls.getMockImplementation()!;
 calls.mockImplementation((path:string,init:RequestInit={})=>path.startsWith('/api/v1/admin/promotions/agent-directory?')?Promise.resolve(Response.json({...directoryPage(),summary:{...summary,pendingCommissionAmount4:current,yesterday:{...summary.yesterday,pendingCommissionAmount4:before}}})):original(path,init));
 const w=await openPage();expect(w.findAll('.stat-change>span').at(-1)?.text()).toBe(expected);
});
it('keeps missing historical facts unknown instead of inventing zero',async()=>{
 const original=calls.getMockImplementation()!;
 calls.mockImplementation((path:string,init:RequestInit={})=>path.startsWith('/api/v1/admin/promotions/agent-directory?')?Promise.resolve(Response.json({...directoryPage(),summary:{...summary,yesterday:null}})):original(path,init));
 const w=await openPage();expect(w.findAll('.stat-change>span').map(n=>n.text())).toEqual(Array(5).fill('—'));
});
it('sends search source status and Shanghai inclusive calendar dates and resets every filter',async()=>{
 const w=await openPage();
 await w.get('[aria-label="搜索代理"]').setValue('  真实用户  ');await w.get('[aria-label="代理来源"]').setValue('APPLICATION');await w.get('[aria-label="代理状态"]').setValue('false');
 await w.get('[aria-label="开始日期"]').setValue('2026-09-01');await w.get('[aria-label="结束日期"]').setValue('2026-09-19');
 await w.get('form[aria-label="代理筛选"]').trigger('submit');await flushPromises();
 const query=()=>new URL(calls.mock.calls.filter(([p])=>p.startsWith('/api/v1/admin/promotions/agent-directory?')).at(-1)![0],'http://local').searchParams;
 expect(Object.fromEntries(query())).toEqual({search:'真实用户',source:'APPLICATION',enabled:'false',from:'2026-08-31T16:00:00.000Z',to:'2026-09-19T16:00:00.000Z',page:'0',size:'10'});
 await w.findAll('button').find(b=>b.text()==='重置')!.trigger('click');await flushPromises();
 expect(Object.fromEntries(query())).toEqual({page:'0',size:'10'});
 for(const label of ['搜索代理','代理来源','代理状态','开始日期','结束日期'])expect((w.get(`[aria-label="${label}"]`).element as HTMLInputElement).value).toBe('');
});
it('opens the selected agent route and restores focus when closing the drawer',async()=>{
 agents.push({userId:'user-2',invitationCode:'P2',status:'ENABLED'});
 const w=await openPage();const trigger=w.get('[data-testid="agent-detail-user-2"]');await trigger.trigger('click');await flushPromises();
 expect(w.vm.$router.currentRoute.value.path).toBe('/admin/agents/user-2');expect(w.get('.agent-detail-profile').text()).toContain('停用代理');
 expect(calls.mock.calls.some(([p])=>p==='/api/v1/admin/promotions/agent-directory/user-2')).toBe(true);
 await w.get('[aria-label="关闭代理详情"]').trigger('click');await flushPromises();
 expect(w.vm.$router.currentRoute.value.path).toBe('/admin/agents');expect(w.get('dialog.agent-detail-drawer').attributes('open')).toBeUndefined();
 expect(w.find('.agent-detail-content').exists()).toBe(false);expect(document.activeElement).toBe(trigger.element);
});

it('reuses generated credentials after an uncertain login failure',async()=>{
 const base=calls.getMockImplementation()!;let fail=true;
 calls.mockImplementation(async(p,i)=>{if(p==='/api/v1/admin/admins'&&i?.method==='POST'&&fail)throw new TypeError('connection lost');return base(p,i);});
 const w=await openPage();await draft(w);expect(w.find('input[type="password"]').exists()).toBe(false);
 await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 fail=false;await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 const posts=calls.mock.calls.filter(([p,i])=>p==='/api/v1/admin/admins'&&i?.method==='POST');
 expect(posts).toHaveLength(2);expect(posts[0]![1].body).toBe(posts[1]![1].body);
 expect(calls.mock.calls.filter(([p,i])=>p==='/api/v1/admin/promotions/promoters'&&i?.method==='POST')).toHaveLength(1);
});

it('creates a Chinese named agent with matching native form validation',async()=>{
 const w=await openPage();await draft(w);await w.get('[aria-label="登录用户名"]').setValue('测试代理');
 expect(w.get('form[aria-label="新增代理"] button[type="submit"]').attributes('disabled')).toBeUndefined();
 const pattern=w.get('[aria-label="登录用户名"]').attributes('pattern')!;
 expect(new RegExp('^(?:'+pattern+')$','v').test('测试代理')).toBe(true);
 expect(new RegExp('^(?:'+pattern+')$','v').test('agent-name')).toBe(true);
 await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 const login=calls.mock.calls.find(([p,i])=>p==='/api/v1/admin/admins'&&i?.method==='POST');
 expect(JSON.parse(login![1].body)).toMatchObject({username:'测试代理',role:'PROMOTER',boundUserId:'user-1'});
});
it.each(['','测试 代理','测试\t代理','<代理>','测'.repeat(65)])('rejects invalid agent name %s',async name=>{
 const w=await openPage();await draft(w);await w.get('[aria-label="登录用户名"]').setValue(name);
 expect(w.get('form[aria-label="新增代理"] button[type="submit"]').attributes('disabled')).toBeDefined();
 await w.get('form[aria-label="新增代理"]').trigger('submit');await flushPromises();
 expect(calls.mock.calls.filter(([,i])=>i?.method==='POST')).toHaveLength(0);
});
