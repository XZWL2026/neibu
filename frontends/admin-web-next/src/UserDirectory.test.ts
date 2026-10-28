import {mount,flushPromises,enableAutoUnmount} from '@vue/test-utils';
import {it,expect,vi,beforeEach,afterEach} from 'vitest';
import {createRouter,createMemoryHistory} from 'vue-router';
import {ref} from 'vue';
import {axe} from 'vitest-axe';
import {adminSessionKey} from './adminSession';
const api=vi.hoisted(()=>({account:vi.fn(),options:vi.fn(),session:vi.fn(),directory:vi.fn(),detail:vi.fn(),ledger:vi.fn(),audit:vi.fn(),history:vi.fn(),consumptions:vi.fn(),secret:vi.fn(),status:vi.fn(),rename:vi.fn()}));
vi.mock('./api',async original=>({...await original<object>(),paymentAdminAccountPatchApiV1AdminPaymentsAccountsId:api.account,paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions:api.options,refreshSession:api.session,userDirectoryGetApiV1AdminUsersDirectory:api.directory,userDirectoryDetailGetApiV1AdminUsersIdDirectoryDetail:api.detail,billingEntriesGetApiV1BillingWalletsKeyIdLedger:api.ledger,billingAdjustmentsGetApiV1AdminBillingWalletsKeyIdAdjustments:api.audit,paymentAdminHistoryGetApiV1AdminPaymentsRecharges:api.history,billingListGetApiV1AdminBillingConsumptions:api.consumptions,accessAdminSecretGetApiV1AdminAccessKeysIdSecret:api.secret,userStatusPatchApiV1AdminUsersIdStatus:api.status,userRenamePatchApiV1AdminUsersIdNickname:api.rename}));
import UserDirectory from './UserDirectory.vue';
const user={id:'user-a',nickname:'真实用户',phone:'+8613800000001',enabled:true,registeredAt:'2026-09-18T08:21:00Z',source:'NATURAL',accountType:'PERSONAL',keyId:'key-a',maskedKey:'sk-example****',keyStatus:'ENABLED',availablePoints4:'1234.5678',availableAmount4:'123.4567',paid:true,active:true};
const page={items:[user],total:81,offset:0,limit:10,summary:{totalUsers:81,newUsers:12,activeUsers:27,paidUsers:50,unpaidUsers:31}};
beforeEach(()=>{vi.clearAllMocks();sessionStorage.clear();api.account.mockResolvedValue({data:{}});api.session.mockResolvedValue({authenticated:true,user:{id:"admin"}});api.options.mockResolvedValue({data:{userId:"user-a",accountType:"PERSONAL",pointsPerYuan4:"10.0000",rules:[{accountType:"PERSONAL",minimumAmount4:"5.0000",presetAmounts4:["5.0000"]}]}});api.directory.mockResolvedValue({data:page});api.detail.mockResolvedValue({data:{user,summary:{totalRechargeAmount4:'200.0000',chargedAmount4:'100.0000',netConsumptionAmount4:'76.5433'}}});api.ledger.mockResolvedValue({data:[]});api.audit.mockResolvedValue({data:[]});api.history.mockResolvedValue({data:[]});api.consumptions.mockResolvedValue({data:{items:[],total:0}});Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value(){this.setAttribute('open','');}});Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value(){this.removeAttribute('open');}});});
enableAutoUnmount(afterEach);
function lastListCall(){return api.directory.mock.calls.filter(([q])=>q.limit!==1).at(-1)?.[0];}
async function show(primary=true){const currentAdmin=ref({role:primary?'ADMIN':'NORMAL_ADMIN',primary,username:'admin'});const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/users',component:UserDirectory},{path:'/admin/users/:userRef',component:UserDirectory},{path:'/admin/api-users',component:{template:'<div>API 管理</div>'}}]});await router.push('/admin/users');await router.isReady();const wrapper=mount(UserDirectory,{attachTo:document.body,global:{plugins:[router],stubs:{UserManagement:true},provide:{[adminSessionKey as symbol]:{currentAdmin}}}});await flushPromises();return {wrapper,router,currentAdmin};}
it('uses server totals instead of page size and requests filtered server pagination',async()=>{const {wrapper}=await show();expect(wrapper.get('[data-stat="totalUsers"]').text()).toContain('81');expect(wrapper.get('[data-stat="paidUsers"]').text()).toContain('50');expect(wrapper.findAll('.directory-table tbody tr')).toHaveLength(1);await wrapper.get('select[aria-label="账户类型筛选"]').setValue('ENTERPRISE');await wrapper.get('form.directory-filters').trigger('submit');await flushPromises();expect(lastListCall()).toEqual(expect.objectContaining({accountType:'ENTERPRISE',offset:0,limit:10}));await wrapper.get('button[aria-label="下一页"]').trigger('click');await flushPromises();expect(lastListCall()).toEqual(expect.objectContaining({offset:10,accountType:'ENTERPRISE'}));expect(api.secret).not.toHaveBeenCalled();});
it('opens only the detail drawer from 查看 and clears it when returning to the list',async()=>{const {wrapper,router}=await show();await wrapper.get('button[aria-label="查看用户 真实用户"]').trigger('click');await flushPromises();expect(router.currentRoute.value.path).toBe('/admin/users/user-a');expect(wrapper.get('dialog[aria-labelledby="user-detail-title"]').attributes('open')).toBeDefined();expect(wrapper.get('dialog').text()).toContain('200');expect(wrapper.get('.directory-table').text()).toContain('真实用户');expect(api.detail).toHaveBeenCalledWith('user-a');await wrapper.get('button[aria-label="关闭用户详情"]').trigger('click');await flushPromises();expect(router.currentRoute.value.path).toBe('/admin/users');expect(wrapper.find('dialog[open]').exists()).toBe(false);});
it('ignores a late directory response after a different search',async()=>{let finish!:(value:unknown)=>void;api.directory.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));const {wrapper}=await show();await wrapper.get('input[aria-label="搜索用户"]').setValue('新查询');await wrapper.get('form.directory-filters').trigger('submit');await flushPromises();finish({data:{...page,items:[{...user,nickname:'旧数据'}]}});await flushPromises();expect(wrapper.get('.directory-table').text()).not.toContain('旧数据');});
it('never sends a pasted full API secret in a GET search URL',async()=>{const {wrapper}=await show();const before=api.directory.mock.calls.length;await wrapper.get('input[aria-label="搜索用户"]').setValue('sk-sensitive-full-secret');await wrapper.get('form.directory-filters').trigger('submit');await flushPromises();expect(api.directory).toHaveBeenCalledTimes(before);expect(wrapper.get('[role="alert"]').text()).toContain('脱敏');});

it('stops a batch when the page is unmounted before the first status request returns',async()=>{let finish!:(value:unknown)=>void;api.status.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));api.directory.mockResolvedValue({data:{...page,items:[user,{...user,id:'user-b'}]}});const {wrapper}=await show();await wrapper.get('input[aria-label="选择本页全部用户"]').setValue(true);await wrapper.get('select[aria-label="批量操作"]').setValue('disable');expect(api.status).not.toHaveBeenCalled();await wrapper.get('.status-action-dialog .primary-action').trigger('click');expect(api.status).toHaveBeenCalledTimes(1);wrapper.unmount();finish({data:{success:true}});await flushPromises();expect(api.status).toHaveBeenCalledTimes(1);});

it('keeps the directory and detail dialog accessible and shows net consumption rather than gross',async()=>{const {wrapper}=await show();expect((await axe(wrapper.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);await wrapper.get('button[aria-label="查看用户 真实用户"]').trigger('click');await flushPromises();const dialog=wrapper.get('dialog[aria-labelledby="user-detail-title"]');expect(dialog.get('.detail-finance').text()).toContain('76.54');expect(dialog.get('.detail-finance').text()).not.toContain('100.00');expect((await axe(dialog.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);});

it('opens balance adjustment inside the detail drawer and cancels without editing an account',async()=>{const {wrapper}=await show();await wrapper.get('button[aria-label="查看用户 真实用户"]').trigger('click');await flushPromises();await wrapper.findAll('button').find(b=>b.text()==='调整余额')!.trigger('click');await flushPromises();expect(wrapper.findAll('dialog[open]')).toHaveLength(1);expect(wrapper.find('.reference-adjustment').exists()).toBe(true);expect(api.options).toHaveBeenCalledWith({userId:'user-a'});await wrapper.get('.reference-adjustment-actions button[type="button"]').trigger('click');expect(wrapper.find('.reference-adjustment').exists()).toBe(false);expect(wrapper.find('.detail-finance').exists()).toBe(true);});

it('defaults list and phone search to all time while keeping explicit date filters and reset',async()=>{
 const {wrapper}=await show();
 expect(lastListCall().from).toBeUndefined();
 expect(lastListCall().to).toBeUndefined();
 expect((wrapper.get('input[aria-label="开始日期"]').element as HTMLInputElement).value).toBe('');
 expect(wrapper.find('.period-buttons button[aria-pressed="true"]').exists()).toBe(false);
 await wrapper.get('input[aria-label="搜索用户"]').setValue('18811627113');
 await wrapper.get('form.directory-filters').trigger('submit');await flushPromises();
 expect(lastListCall()).toEqual(expect.objectContaining({search:'18811627113',from:undefined,to:undefined}));
 await wrapper.findAll('.period-buttons button').find(b=>b.text()==='近7天')!.trigger('click');await flushPromises();
 expect(lastListCall().from).toEqual(expect.any(String));
 expect(lastListCall().to).toEqual(expect.any(String));
 await wrapper.findAll('button').find(b=>b.text()==='重置')!.trigger('click');await flushPromises();
 expect(lastListCall()).toEqual(expect.objectContaining({search:undefined,from:undefined,to:undefined,offset:0}));
});

it('opens a type-only picker, cancels without saving, and confirms through the existing account API',async()=>{
 const {wrapper}=await show();await wrapper.get('button[aria-label="查看用户 真实用户"]').trigger('click');await flushPromises();
 await wrapper.get('.identity-type').trigger('click');await flushPromises();
 let picker=wrapper.get('dialog[aria-labelledby="user-type-title"]');
 expect(picker.attributes('open')).toBeDefined();expect(picker.text()).not.toContain('编辑用户');expect(picker.text()).not.toContain('额度调整');
 expect((await axe(picker.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
 await picker.findAll('button').find(b=>b.text()==='取消')!.trigger('click');expect(api.account).not.toHaveBeenCalled();
 await wrapper.get('.identity-type').trigger('click');await flushPromises();picker=wrapper.get('dialog[aria-labelledby="user-type-title"]');
 await picker.get('select').setValue('ENTERPRISE');api.detail.mockResolvedValue({data:{user:{...user,accountType:'ENTERPRISE'},summary:{}}});
 await picker.get('form').trigger('submit');await flushPromises();
 expect(api.account).toHaveBeenCalledExactlyOnceWith('user-a',expect.objectContaining({accountType:'ENTERPRISE',createKey:false,requestId:expect.any(String)}));
 expect(wrapper.find('dialog[aria-labelledby="user-type-title"][open]').exists()).toBe(false);
 expect(wrapper.get('.identity-type').text()).toContain('企业用户');
});
it('keeps the original type-change request for retry after an uncertain save failure',async()=>{
 api.account.mockRejectedValueOnce(new Error('connection interrupted'));
 const {wrapper}=await show();await wrapper.get('button[aria-label="查看用户 真实用户"]').trigger('click');await flushPromises();
 await wrapper.get('.identity-type').trigger('click');await flushPromises();const picker=wrapper.get('dialog[aria-labelledby="user-type-title"]');
 await picker.get('select').setValue('ENTERPRISE');await picker.get('form').trigger('submit');await flushPromises();
 expect(picker.attributes('open')).toBeDefined();expect(picker.get('select').attributes('disabled')).toBeDefined();
 const request=api.account.mock.calls[0]![1];await picker.get('form').trigger('submit');await flushPromises();expect(api.account.mock.calls[1]![1]).toEqual(request);
});

it.each(['ENTERPRISE','API'])('does not allow a normal administrator to change %s type',async(accountType)=>{
 api.options.mockResolvedValue({data:{userId:'user-a',accountType}});
 const {wrapper}=await show(false);await wrapper.get('button[aria-label="查看用户 真实用户"]').trigger('click');await flushPromises();
 await wrapper.get('.identity-type').trigger('click');await flushPromises();const picker=wrapper.get('dialog[aria-labelledby="user-type-title"]');
 expect(picker.get('select').attributes('disabled')).toBeDefined();
 expect(picker.findAll('button').some(b=>b.text()==='确定')).toBe(false);
 await picker.get('form').trigger('submit');await flushPromises();expect(api.account).not.toHaveBeenCalled();
});

it.each(['PERSONAL','ENTERPRISE','API'])('allows the primary administrator to save an API user as %s',async(accountType)=>{
 api.options.mockResolvedValue({data:{userId:'user-a',accountType:'API'}});
 api.detail.mockResolvedValue({data:{user:{...user,accountType:'API'},summary:{}}});
 const {wrapper}=await show();await wrapper.get('button[aria-label="查看用户 真实用户"]').trigger('click');await flushPromises();
 await wrapper.get('.identity-type').trigger('click');await flushPromises();const picker=wrapper.get('dialog[aria-labelledby="user-type-title"]');
 expect(picker.get('select').attributes('disabled')).toBeUndefined();
 expect(picker.text()).not.toContain('API 账户类型只读');
 await picker.get('select').setValue(accountType);
 api.detail.mockResolvedValue({data:{user:{...user,accountType},summary:{}}});
 await picker.get('form').trigger('submit');await flushPromises();
 expect(api.account).toHaveBeenCalledExactlyOnceWith('user-a',expect.objectContaining({accountType,createKey:accountType==='API'}));
 expect(wrapper.find('dialog[aria-labelledby="user-type-title"][open]').exists()).toBe(false);
 expect(wrapper.get('.identity-type').text()).toContain(({PERSONAL:'个人',ENTERPRISE:'企业',API:'API'})[accountType]!);
});

it('keeps a converted API user in the same detail and removes the edit entry',async()=>{
 api.options.mockResolvedValue({data:{userId:'user-a',accountType:'ENTERPRISE'}});
 const {wrapper,router}=await show();await wrapper.get('button[aria-label="查看用户 真实用户"]').trigger('click');await flushPromises();
 expect(wrapper.find('button[aria-label="编辑用户账户"]').exists()).toBe(false);
 await wrapper.get('.identity-type').trigger('click');await flushPromises();const picker=wrapper.get('dialog[aria-labelledby="user-type-title"]');
 expect(picker.get('select').findAll('option').map(o=>o.attributes('value'))).toContain('API');
 await picker.get('select').setValue('API');api.detail.mockResolvedValue({data:{user:{...user,accountType:'API'},summary:{}}});await picker.get('form').trigger('submit');await flushPromises();
 expect(api.account).toHaveBeenCalledExactlyOnceWith('user-a',expect.objectContaining({accountType:'API',createKey:true}));
 expect(router.currentRoute.value.path).toBe('/admin/users/user-a');expect(wrapper.get('.identity-type').text()).toContain('API 用户');
});

it('shows API users in the directory and supports selecting the API filter',async()=>{
 api.directory.mockResolvedValue({data:{...page,items:[{...user,accountType:'API'}]}});
 const {wrapper}=await show();expect(wrapper.get('.directory-table').text()).toContain('API 用户');
 const filter=wrapper.get('select[aria-label="账户类型筛选"]');expect(filter.findAll('option').map(o=>o.attributes('value'))).toContain('API');
 await filter.setValue('API');await wrapper.get('form.directory-filters').trigger('submit');await flushPromises();
 expect(lastListCall()).toEqual(expect.objectContaining({accountType:'API',from:undefined,to:undefined}));
});


it('compares all real selected-period totals with the preceding day',async()=>{
 vi.useFakeTimers({toFake:['Date']});vi.setSystemTime(new Date('2026-09-22T03:00:00Z'));
 try {
  api.directory.mockImplementation(async q=>({data:q.limit===1?{...page,summary:{totalUsers:69,newUsers:18,activeUsers:27,paidUsers:40,unpaidUsers:29}}:page}));
  const {wrapper}=await show();
  expect(api.directory).toHaveBeenCalledWith(expect.objectContaining({offset:0,limit:1,from:undefined,to:'2026-09-21T16:00:00.000Z'}));
  expect(wrapper.get('[data-stat="totalUsers"] .stat-change').text()).toBe('↑ 12较昨日');
  expect(wrapper.get('[data-stat="newUsers"] .stat-change').text()).toBe('↓ 6较昨日');
  expect(wrapper.get('[data-stat="newUsers"] .stat-change').classes()).toContain('down');
  expect(wrapper.get('[data-stat="activeUsers"] .stat-change').text()).toBe('↑ 0较昨日');
  expect(wrapper.get('[data-stat="paidUsers"] .stat-change').text()).toBe('↑ 10较昨日');
  expect(wrapper.get('[data-stat="unpaidUsers"] .stat-change').text()).toBe('↑ 2较昨日');
  api.directory.mockImplementation(async q=>({data:q.limit===1?{...page,summary:{...page.summary,paidUsers:40,unpaidUsers:41}}:page}));
  await wrapper.get('input[aria-label="开始日期"]').setValue('2026-09-01');
  await wrapper.get('input[aria-label="结束日期"]').setValue('2026-09-19');
  await wrapper.get('input[aria-label="搜索用户"]').setValue('真实');
  await wrapper.get('form.directory-filters').trigger('submit');await flushPromises();
  expect(api.directory).toHaveBeenCalledWith(expect.objectContaining({search:'真实',limit:1,from:'2026-08-30T16:00:00.000Z',to:'2026-09-18T16:00:00.000Z'}));
  expect(wrapper.get('[data-stat="unpaidUsers"] .stat-change').text()).toBe('↓ 10较昨日');
  expect(wrapper.get('[data-stat="unpaidUsers"] .stat-change').classes()).toContain('down');
 }finally{vi.useRealTimers();}
});
it('isolates comparison failure and ignores its stale response after a new search',async()=>{
 let finish!:(value:unknown)=>void;
 api.directory.mockImplementation(q=>q.limit===1?new Promise(resolve=>{finish=resolve;}):Promise.resolve({data:page}));
 const {wrapper}=await show();
 api.directory.mockImplementation(q=>q.limit===1?Promise.reject(Error('unavailable')):Promise.resolve({data:page}));
 await wrapper.get('input[aria-label="搜索用户"]').setValue('新查询');
 await wrapper.get('form.directory-filters').trigger('submit');await flushPromises();
 finish({data:{...page,summary:{...page.summary,totalUsers:1}}});await flushPromises();
 expect(wrapper.get('[data-stat="totalUsers"] .stat-change').text()).toBe('—较昨日');
 expect(wrapper.get('.directory-table').text()).toContain('真实用户');
 expect(wrapper.find('[role="alert"]').exists()).toBe(false);
 for(const field of ['paidUsers','unpaidUsers'])expect(wrapper.get(`[data-stat="${field}"] .stat-change`).text()).toBe('—较昨日');
});

it('shows grouped precise points, server yuan equivalent and the real recharge rate in detail',async()=>{
 api.detail.mockResolvedValue({data:{user:{...user,availablePoints4:'25600.1234',availableAmount4:'2560.0123'},summary:{totalRechargeAmount4:'8800.1234',netConsumptionAmount4:'6240.5678'}}});
 const {wrapper}=await show();await wrapper.get('button[aria-label="查看用户 真实用户"]').trigger('click');await flushPromises();
 const cards=wrapper.get('.detail-finance');
 expect(cards.text()).toContain('25,600.1234');expect(cards.text()).toContain('≈ ¥ 2,560.01');
 expect(cards.text()).toContain('¥ 8,800.12');expect(cards.text()).toContain('¥ 6,240.56');
 expect(cards.text()).toContain('1 元 = 10 积分');expect(cards.text()).not.toContain('1 元 = 100 积分');
 expect(cards.findAll('.mini-icon svg')).toHaveLength(3);
});
it('leaves the conversion rate unknown when options fail without hiding financial data',async()=>{
 api.options.mockRejectedValue(new Error('unavailable'));
 const {wrapper}=await show();await wrapper.get('button[aria-label="查看用户 真实用户"]').trigger('click');await flushPromises();
 expect(wrapper.get('.detail-finance').text()).toContain('1 元 = — 积分');
 expect(wrapper.get('.detail-finance').text()).toContain('≈ ¥ 123.45');
 expect(wrapper.find('.user-detail-content [role="alert"]').exists()).toBe(false);
});

it('refreshes both the directory row and detail after renaming a user',async()=>{
 const {wrapper}=await show();await wrapper.get('button[aria-label="查看用户 真实用户"]').trigger('click');await flushPromises();
 const renamed={...user,nickname:'更新后的用户名'};
 api.rename.mockResolvedValueOnce({data:renamed});api.detail.mockResolvedValue({data:{user:renamed}});api.directory.mockResolvedValue({data:{...page,items:[renamed]}});
 await wrapper.get('.profile-edit').trigger('click');await wrapper.get('input[name="nickname"]').setValue(renamed.nickname);await wrapper.get('form.profile-name-form').trigger('submit');await flushPromises();
 expect(api.rename).toHaveBeenCalledWith('user-a',{nickname:renamed.nickname});expect(wrapper.get('.directory-table').text()).toContain(renamed.nickname);expect(wrapper.get('.detail-profile h3').text()).toContain(renamed.nickname);
});

it('requires explicit disable confirmation, supports cancel and reports per-user partial failures',async()=>{
 api.status.mockReset().mockResolvedValueOnce({data:{}}).mockRejectedValueOnce(new Error('unavailable'));
 api.directory.mockResolvedValue({data:{...page,items:[user,{...user,id:'user-b',nickname:'第二位用户'}]}});
 const {wrapper}=await show();await wrapper.get('[aria-label="选择本页全部用户"]').setValue(true);
 await wrapper.get('[aria-label="批量操作"]').setValue('disable');expect(api.status).not.toHaveBeenCalled();
 expect(wrapper.get('.status-action-dialog').text()).toContain('确认停用 2 位用户');
 await wrapper.get('.status-action-dialog button').trigger('click');expect(wrapper.find('.status-action-dialog').exists()).toBe(false);expect(api.status).not.toHaveBeenCalled();
 await wrapper.get('[aria-label="批量操作"]').setValue('disable');await wrapper.get('.status-action-dialog .primary-action').trigger('click');await flushPromises();
 expect(api.status).toHaveBeenCalledTimes(2);expect(wrapper.get('.status-action-result').text()).toContain('成功 1 位，未完成 1 位');
 expect(wrapper.get('[role="alert"]').text()).toContain('第二位用户（user-b）');
});

it('does not let an old identity request dismiss the new identity confirmation',async()=>{
 let finish!:(value:unknown)=>void;api.status.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
 const {wrapper,currentAdmin}=await show();
 await wrapper.get('[aria-label="选择本页全部用户"]').setValue(true);
 await wrapper.get('[aria-label="批量操作"]').setValue('disable');await wrapper.get('.status-action-dialog .primary-action').trigger('click');
 currentAdmin.value={...currentAdmin.value,username:'second-admin'};await flushPromises();
 await wrapper.get('[aria-label="选择本页全部用户"]').setValue(true);
 await wrapper.get('[aria-label="批量操作"]').setValue('disable');expect(wrapper.find('.status-action-dialog').exists()).toBe(true);
 finish({data:{}});await flushPromises();expect(wrapper.find('.status-action-dialog').exists()).toBe(true);expect(wrapper.find('.status-action-result').exists()).toBe(false);
});
