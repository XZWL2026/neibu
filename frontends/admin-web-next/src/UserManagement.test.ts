import {flushPromises,mount,enableAutoUnmount} from '@vue/test-utils';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {ref} from 'vue';
import {createMemoryHistory,createRouter,RouterView} from 'vue-router';
import {axe} from 'vitest-axe';
const api=vi.hoisted(()=>({identities:vi.fn(),credentials:vi.fn(),ticket:vi.fn(),session:vi.fn(),promoters:vi.fn(),list:vi.fn(),detail:vi.fn(),create:vi.fn(),keys:vi.fn(),key:vi.fn(),options:vi.fn(),balance:vi.fn(),status:vi.fn(),account:vi.fn(),secret:vi.fn(),keyStatus:vi.fn(),ledger:vi.fn(),audit:vi.fn(),manual:vi.fn(),debit:vi.fn()}));
vi.mock('./api',async original=>({...await original<object>(),paymentAdminIdentitiesGetApiV1AdminPaymentsAccountIdentities:api.identities,iamListGetApiV1AdminAdmins:api.credentials,iamIssueApiUserPostApiV1AdminApiUsersIdAgentTicket:api.ticket,refreshSession:api.session,promotionListPromotersGetApiV1AdminPromotionsPromoters:api.promoters,userListGetApiV1AdminUsers:api.list,userDetailGetApiV1AdminUsersId:api.detail,userCreatePostApiV1AdminUsers:api.create,accessAdminListGetApiV1AdminAccessKeys:api.keys,accessAdminGetGetApiV1AdminAccessKeysId:api.key,paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions:api.options,billingBalanceGetApiV1BillingWalletsKeyId:api.balance,userStatusPatchApiV1AdminUsersIdStatus:api.status,paymentAdminAccountPatchApiV1AdminPaymentsAccountsId:api.account,accessAdminSecretGetApiV1AdminAccessKeysIdSecret:api.secret,accessStatusPatchApiV1AdminAccessKeysIdStatus:api.keyStatus,billingEntriesGetApiV1BillingWalletsKeyIdLedger:api.ledger,billingAdjustmentsGetApiV1AdminBillingWalletsKeyIdAdjustments:api.audit,paymentManualPostApiV1AdminPaymentsRecharges:api.manual,billingDebitPostApiV1AdminBillingDebits:api.debit}));
import {adminNavigation} from './navigation';
import {adminRoutes} from './router';
import {adminSessionKey,type AdminIdentity} from './adminSession';
import UserManagement from './UserManagement.vue';
import AmountAdjustment from './AmountAdjustment.vue';
const user={id:'real-user',phone:'+8613800000001',nickname:'真实用户',enabled:true,authVersion:0};
const key={keyId:'real-key',ownerId:user.id,kind:'MEDIA',enabled:true,accountType:'PERSONAL',firstRechargeOrderId:null};
const options={userId:user.id,accountType:'PERSONAL',firstRecharge:true,pointsPerYuan4:'10.1234',rules:[{accountType:'PERSONAL',minimumAmount4:'5.0000',presetAmounts4:['5.0000','10.0000']}]};
enableAutoUnmount(afterEach);
it('classifies a directory page with one batch request instead of recharge options per user',async()=>{
 api.list.mockResolvedValue({data:{items:[user,{...user,id:'second',nickname:'第二位'}],total:2}});
 const {wrapper}=await show();expect(wrapper.findAll('tbody tr')).toHaveLength(2);
 expect(api.identities).toHaveBeenCalledExactlyOnceWith({userIds:[user.id,'second']});
 expect(api.options).not.toHaveBeenCalled();
});
it.each([{data:[]},{data:[{userId:'other',accountType:'PERSONAL'}]},{data:[{userId:'real-user',accountType:null},{userId:'real-user',accountType:'API'}]}])('rejects incomplete or mismatched classification batches $data',async({data})=>{
 api.identities.mockResolvedValue({data});const {wrapper}=await show();
 expect(wrapper.findAll('tbody tr')).toHaveLength(0);expect(wrapper.text()).toContain('无法读取真实用户或账户');
});
it('classifies more than 100 users in bounded batches and keeps an exact total',async()=>{
 const source=Array.from({length:205},(_,i)=>({...user,id:`batch-${i}`}));
 api.list.mockImplementation(async({offset,limit})=>({data:{items:source.slice(offset,offset+limit),total:source.length}}));
 const {wrapper}=await show();expect(api.identities.mock.calls.map(([query])=>query.userIds.length)).toEqual([100,100,5]);
 expect(wrapper.text()).toContain('共 205 位用户');expect(wrapper.findAll('tbody tr')).toHaveLength(10);expect(api.options).not.toHaveBeenCalled();
});
it('discards a previous pages delayed balance after a fresh search',async()=>{
 let finish!:(value:unknown)=>void;api.balance.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
 api.keys.mockImplementation(async({ownerId}:{ownerId:string})=>({data:[{...key,ownerId,keyId:ownerId+'-key'}]}));
 const {wrapper}=await show();api.list.mockResolvedValue({data:{items:[{...user,id:'new-user',nickname:'新查询用户'}],total:1}});
 api.balance.mockResolvedValue({data:{availablePoints4:'88.0000'}});
 await wrapper.get('input[aria-label="搜索用户"]').setValue('新查询');await wrapper.get('form.user-search').trigger('submit');await flushPromises();
 finish({data:{availablePoints4:'999.0000'}});await flushPromises();
 expect(wrapper.get('tbody').text()).toContain('新查询用户');expect(wrapper.get('tbody tr td:nth-child(5)').text()).toBe('88');
});
it('renders users and their Key while a wallet is still loading',async()=>{
 let finish!:(value:unknown)=>void;api.balance.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
 const {wrapper}=await show();
 expect(wrapper.findAll('tbody tr')).toHaveLength(1);
 expect(wrapper.get('tbody').text()).toContain('真实用户');
 expect(wrapper.get('tbody').text()).toContain('sk-****cret');
 expect(wrapper.get('tbody tr td:nth-child(5)').text()).toBe('—');
 expect(wrapper.text()).not.toContain('正在读取真实用户');
 finish({data:{availablePoints4:'12.3456'}});await flushPromises();
 expect(wrapper.get('tbody tr td:nth-child(5)').text()).toBe('12.3456');
});
it('does not make every row wait for another users slow Key request',async()=>{
 let finish!:(value:unknown)=>void;
 api.list.mockResolvedValue({data:{items:[user,{...user,id:'slow-user',nickname:'慢用户'}],total:2}});
 api.keys.mockImplementation(({ownerId}:{ownerId:string})=>ownerId==='slow-user'?new Promise(resolve=>{finish=resolve;}):Promise.resolve({data:[key]}));
 const {wrapper}=await show();expect(wrapper.findAll('tbody tr')).toHaveLength(2);
 expect(wrapper.get('tbody tr').text()).toContain('41.2345');
 expect(wrapper.get('button[aria-label="编辑用户 慢用户"]').attributes('disabled')).toBeDefined();
 finish({data:[]});await flushPromises();expect(wrapper.get('button[aria-label="编辑用户 慢用户"]').attributes('disabled')).toBeUndefined();
});
it('reuses the current directory when paging but fetches it again on refresh and search',async()=>{
 const source=Array.from({length:24},(_,i)=>({...user,id:`paged-${i}`,nickname:`分页用户${i}`}));
 api.list.mockImplementation(async({offset,limit})=>({data:{items:source.slice(offset,offset+limit),total:source.length}}));
 const {wrapper}=await show();await wrapper.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();
 expect(wrapper.findAll('tbody tr')).toHaveLength(10);expect(api.list).toHaveBeenCalledTimes(1);expect(api.identities).toHaveBeenCalledTimes(1);
 await wrapper.findAll('button').find(b=>b.text()==='刷新')!.trigger('click');await flushPromises();expect(api.list).toHaveBeenCalledTimes(2);
 await wrapper.get('input[aria-label="搜索用户"]').setValue('分页');await wrapper.get('form.user-search').trigger('submit');await flushPromises();
 expect(api.list).toHaveBeenCalledTimes(3);expect(wrapper.findAll('tbody tr')).toHaveLength(10);
});
it('includes enabled and disabled agents when counting and paging users',async()=>{
 const ordinary=Array.from({length:22},(_,i)=>({...user,id:`ordinary-${i}`,nickname:`普通用户${i}`}));
 const source=[{...user,id:'agent-enabled',nickname:'启用代理'},...ordinary.slice(0,10),{...user,id:'agent-disabled',nickname:'停用代理'},...ordinary.slice(10)];
 api.promoters.mockImplementation(async({page})=>({data:{items:page===0?[{userId:'agent-enabled',status:'ENABLED'}]:[{userId:'agent-disabled',status:'DISABLED'}],total:2,page,size:1}}));
 api.list.mockImplementation(async({offset,limit})=>({data:{items:source.slice(offset,offset+limit),total:source.length,offset,limit}}));
 const {wrapper}=await show();
 expect(wrapper.text()).toContain('启用代理');expect(wrapper.text()).not.toContain('停用代理');expect(wrapper.text()).toContain('共 24 位用户');
 expect(wrapper.findAll('tbody tr')).toHaveLength(10);
 expect(api.keys.mock.calls.some(([query])=>query.ownerId.startsWith('agent-'))).toBe(true);
 await wrapper.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();
 expect(wrapper.findAll('tbody tr')).toHaveLength(10);expect(wrapper.text()).toContain('停用代理');
 await wrapper.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();
 expect(wrapper.findAll('tbody tr')).toHaveLength(4);expect(wrapper.text()).toContain('普通用户21');
});
it('includes agents in phone search results without depending on the promoter directory',async()=>{
 api.promoters.mockResolvedValue({data:{items:[{userId:user.id}],total:1,page:0,size:100}});
 const {wrapper}=await show();await wrapper.get('input[aria-label="搜索用户"]').setValue('13800000001');
 await wrapper.get('form.user-search').trigger('submit');await flushPromises();
 expect(wrapper.findAll('tbody tr')).toHaveLength(1);expect(wrapper.text()).toContain('共 1 位用户');
 expect(api.list).toHaveBeenLastCalledWith({search:'13800000001',offset:0,limit:100});
 api.promoters.mockRejectedValueOnce(new Error('unavailable'));
 await wrapper.findAll('button').find(b=>b.text()==='刷新')!.trigger('click');await flushPromises();
 expect(wrapper.find('[role="alert"]').exists()).toBe(false);expect(wrapper.findAll('tbody tr')).toHaveLength(1);expect(api.promoters).not.toHaveBeenCalled();
});
it('redirects an agent user-detail link to agent management without reading user account details',async()=>{
 api.promoters.mockResolvedValue({data:{items:[{userId:user.id,status:'DISABLED'}],total:1,page:0,size:1}});
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/users/:userRef',component:UserManagement},{path:'/admin/agents/:userId',component:{template:'<div>代理详情</div>'}}]});
 await router.push(`/admin/users/${user.id}`);await router.isReady();
 mount(RouterView,{global:{plugins:[router],provide:{[adminSessionKey as symbol]:{currentAdmin:ref({username:'admin',role:'ADMIN',primary:true})}}}});await flushPromises();
 expect(router.currentRoute.value.path).toBe(`/admin/agents/${user.id}`);
 expect(api.detail).not.toHaveBeenCalled();expect(api.keys).not.toHaveBeenCalled();
});
beforeEach(()=>{vi.clearAllMocks();api.identities.mockImplementation(async({userIds}:{userIds:string[]})=>({data:userIds.map(userId=>({userId,accountType:'PERSONAL'}))}));api.credentials.mockResolvedValue({data:[]});api.promoters.mockReset().mockResolvedValue({data:{items:[],total:0,page:0,size:100}});sessionStorage.clear();api.session.mockResolvedValue({authenticated:true,user:{id:'admin'}});api.list.mockResolvedValue({data:{items:[user],total:1,offset:0,limit:20}});api.detail.mockResolvedValue({data:user});api.keys.mockResolvedValue({data:[key]});api.key.mockResolvedValue({data:key});api.secret.mockResolvedValue({data:{secret:'sk-test-primary-secret'}});api.options.mockResolvedValue({data:options});api.balance.mockResolvedValue({data:{walletId:'wallet',availablePoints4:'41.2345',version:1}});api.ledger.mockResolvedValue({data:[]});api.audit.mockResolvedValue({data:[]});api.account.mockResolvedValue({data:options});api.status.mockResolvedValue({data:{...user,enabled:false}});api.create.mockResolvedValue({data:user});Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value(){this.setAttribute('open','');}});Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value(){this.removeAttribute('open');}});});
async function show(path='/admin/users',identity:AdminIdentity={username:'admin',role:'ADMIN',primary:true}){const router=createRouter({history:createMemoryHistory(),routes:adminRoutes});await router.push(path);await router.isReady();const currentAdmin=ref(identity);const wrapper=mount(UserManagement,{attachTo:document.body,global:{plugins:[router],provide:{[adminSessionKey as symbol]:{currentAdmin}}}});await flushPromises();return {wrapper,router,currentAdmin};}
it('keeps user management as the only user and primary key navigation',()=>{expect(adminNavigation.filter(item=>item.to==='/admin/users')).toHaveLength(1);expect(adminNavigation.some(item=>String(item.to)==='/admin/keys')).toBe(false);});
it('provides a real user detail route and preserves legacy keys',()=>{expect(adminRoutes.some(route=>route.path==='/admin/users/:userRef')).toBe(true);expect(adminRoutes.some(route=>route.path==='/admin/keys/:keyRef')).toBe(true);});
it('lists actual directory, primary Key and wallet without cumulative or fixture amounts',async()=>{const {wrapper}=await show();expect(api.list).toHaveBeenCalledWith({search:undefined,offset:0,limit:100});expect(wrapper.text()).toContain('真实用户');expect(wrapper.text()).not.toContain('real-key');expect(wrapper.text()).toContain('41.2345');expect(wrapper.text()).not.toMatch(/累计充值|累计消耗|USR-1388/);await wrapper.get('button[aria-label="停用用户 真实用户"]').trigger('click');await flushPromises();expect(api.status).toHaveBeenCalledWith(user.id,{enabled:false});});
it('resolves a legacy Key through actual owner and redirects old user status',async()=>{const {router,wrapper}=await show('/admin/keys/key-link');await flushPromises();expect(api.key).toHaveBeenCalledWith('key-link');expect(router.currentRoute.value.path).toBe('/admin/users/real-user');expect(wrapper.get('h1').text()).toBe('用户详情');await router.push('/admin/users/real-user/status');expect(router.currentRoute.value.path).toBe('/admin/users/real-user');});
it('shows a true no-Key state and disables all amount actions before saving',async()=>{api.keys.mockResolvedValue({data:[]});const {wrapper}=await show();expect(wrapper.text()).toContain('未生成 Key');await wrapper.findAll('button').find(b=>b.text()==='新增用户')!.trigger('click');await flushPromises();expect(wrapper.get('dialog').attributes('open')).toBeDefined();expect(wrapper.get('dialog fieldset').attributes('disabled')).toBeDefined();expect(api.manual).not.toHaveBeenCalled();expect(api.debit).not.toHaveBeenCalled();});
it('keeps user creation independent from account failure and retries same account body',async()=>{api.keys.mockResolvedValue({data:[]});api.account.mockRejectedValueOnce(new TypeError('lost response')).mockResolvedValueOnce({data:options});const {wrapper}=await show();await wrapper.findAll('button').find(b=>b.text()==='新增用户')!.trigger('click');await wrapper.get('input[aria-label="昵称"]').setValue('  新用户  ');await wrapper.get('input[aria-label="手机号"]').setValue('+86 13800000001');await wrapper.get('form.new-user-form').trigger('submit');await flushPromises();expect(api.create).toHaveBeenCalledOnce();expect(api.create.mock.calls[0][0]).toMatchObject({nickname:'新用户',phone:user.phone});expect(wrapper.get('dialog').text()).toContain('已保存用户：real-user');await wrapper.get('dialog .account-section form').trigger('submit');await flushPromises();expect(wrapper.get('dialog').text()).toContain('账户保存响应尚未确认');const body=api.account.mock.calls[0][1];await wrapper.get('dialog .account-section form').trigger('submit');await flushPromises();expect(api.account.mock.calls[1][1]).toEqual(body);expect(api.create).toHaveBeenCalledOnce();});
it('freezes and restores the exact user creation request after uncertain response',async()=>{api.create.mockRejectedValueOnce(new TypeError('lost response')).mockResolvedValueOnce({data:user});const {wrapper}=await show();await wrapper.findAll('button').find(b=>b.text()==='新增用户')!.trigger('click');await wrapper.get('input[aria-label="昵称"]').setValue('新用户');await wrapper.get('input[aria-label="手机号"]').setValue(user.phone);await wrapper.get('form.new-user-form').trigger('submit');await flushPromises();const body=api.create.mock.calls[0][0];expect(wrapper.get('input[aria-label="手机号"]').attributes('disabled')).toBeDefined();wrapper.unmount();const second=await show();await second.wrapper.findAll('button').find(b=>b.text()==='继续未确认的创建')!.trigger('click');await second.wrapper.get('form.new-user-form').trigger('submit');await flushPromises();expect(api.create.mock.calls[1][0]).toEqual(body);});
it('normal admin cannot select enterprise or change enterprise back to personal',async()=>{api.options.mockResolvedValue({data:{...options,accountType:'ENTERPRISE'}});const {wrapper}=await show('/admin/users/real-user',{username:'staff',role:'NORMAL_ADMIN'});expect(wrapper.get('select[aria-label="账户类型"]').attributes('disabled')).toBeDefined();expect(wrapper.text()).toContain('企业账户仅主管理员可以修改');expect(wrapper.findAll('button').some(b=>b.text()==='查看 Key')).toBe(false);const list=await show('/admin/users',{username:'staff',role:'NORMAL_ADMIN'});await list.wrapper.findAll('button').find(b=>b.text()==='新增用户')!.trigger('click');expect(list.wrapper.get('select[aria-label="新用户账户类型"]').findAll('option').map(o=>o.text())).toEqual(['个人']);});
it('dialog is accessible at desktop and narrow viewport and closes with Escape cancellation',async()=>{const {wrapper}=await show();for(const width of [1280,375]){Object.defineProperty(window,'innerWidth',{value:width,configurable:true});await wrapper.findAll('button').find(b=>b.text()==='新增用户')!.trigger('click');await flushPromises();const result=await axe(wrapper.get('dialog').element,{rules:{'color-contrast':{enabled:false}}});expect(result.violations).toEqual([]);await wrapper.get('dialog').trigger('cancel');expect(wrapper.get('dialog').attributes('open')).toBeUndefined();}});
it('uses exact BigInt points, rejects nonpositive yuan, and retains one debit request across retries',async()=>{api.debit.mockRejectedValueOnce(new TypeError('lost')).mockResolvedValueOnce({data:{availablePoints4:'40.0000'}});const wrapper=mount(AmountAdjustment,{props:{userId:user.id,keyId:key.keyId,options}});await flushPromises();await wrapper.get('input[aria-label="到账凭证与审核备注"]').setValue('修正误发');await wrapper.get('input[aria-label="人民币金额（元）"]').setValue('-1');expect(wrapper.findAll('button').find(b=>b.text()==='扣减')!.attributes('disabled')).toBeDefined();await wrapper.get('input[aria-label="人民币金额（元）"]').setValue('0.01');expect(wrapper.text()).toContain('0.1012');expect(wrapper.findAll('button').find(b=>b.text()==='充值')!.attributes('disabled')).toBeUndefined();await wrapper.findAll('button').find(b=>b.text()==='扣减')!.trigger('click');await flushPromises();const body=api.debit.mock.calls[0][0];expect(body).toMatchObject({keyId:key.keyId,amount4:'0.01',remark:'修正误发'});expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined();await wrapper.findAll('button').find(b=>b.text()==='重试原扣减申请')!.trigger('click');await flushPromises();expect(api.debit.mock.calls[1][0]).toEqual(body);expect(wrapper.emitted('changed')).toHaveLength(1);});

it('restores a pending account before a 409 options read and retries its exact body',async()=>{const body={requestId:'pending-account',accountType:'PERSONAL',createKey:true};sessionStorage.setItem('xzwl-user-account:admin:real-user',JSON.stringify(body));api.options.mockRejectedValueOnce(new Error('account transition pending')).mockResolvedValue({data:options});const {wrapper}=await show('/admin/users/real-user');expect(wrapper.text()).toContain('账户保存响应尚未确认');await wrapper.findAll('button').find(b=>b.text()==='重试原账户保存')!.trigger('click');await flushPromises();expect(api.account).toHaveBeenCalledWith(user.id,body);expect(wrapper.text()).toContain('账户已保存');});
it('keeps classified users visible when their wallets cannot be read',async()=>{api.list.mockResolvedValue({data:{items:[user,{...user,id:'second',nickname:'另一位用户'}],total:2}});api.options.mockResolvedValue({data:options});api.keys.mockImplementation(({ownerId}:{ownerId:string})=>Promise.resolve({data:[{...key,ownerId,keyId:ownerId+'-key'}]}));api.balance.mockRejectedValue(new Error('billing unavailable'));const {wrapper}=await show();expect(wrapper.text()).toContain('真实用户');expect(wrapper.text()).toContain('另一位用户');expect(wrapper.findAll('tbody tr')).toHaveLength(2);expect(wrapper.text()).toContain('读取失败');expect(wrapper.findAll('tbody td').some(cell=>cell.text()==='0')).toBe(false);});
it('reveals and copies only the server secret and changes Key status independently',async()=>{const writeText=vi.fn().mockResolvedValue(undefined);Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText}});api.secret.mockResolvedValue({data:{secret:'server-generated-secret'}});api.keyStatus.mockResolvedValue({data:{...key,enabled:false}});const {wrapper}=await show('/admin/users/real-user');expect(wrapper.text()).not.toContain('server-generated-secret');await wrapper.findAll('button').find(b=>b.text()==='查看 Key')!.trigger('click');await flushPromises();expect(api.secret).toHaveBeenCalledWith(key.keyId);await wrapper.findAll('button').find(b=>b.text()==='复制 Key')!.trigger('click');await flushPromises();expect(writeText).toHaveBeenCalledWith('server-generated-secret');await wrapper.findAll('button').find(b=>b.text()==='停用 Key')!.trigger('click');await flushPromises();expect(api.keyStatus).toHaveBeenCalledWith(key.keyId,{enabled:false});expect(api.status).not.toHaveBeenCalled();});
it('normalizes domestic phone and prevents concurrent duplicate user creation',async()=>{let resolve!:(value:unknown)=>void;api.create.mockImplementation(()=>new Promise(r=>{resolve=r;}));const {wrapper}=await show();await wrapper.findAll('button').find(b=>b.text()==='新增用户')!.trigger('click');await wrapper.get('input[aria-label="昵称"]').setValue('新用户');await wrapper.get('input[aria-label="手机号"]').setValue('13800000001');await wrapper.get('form.new-user-form').trigger('submit');await wrapper.get('form.new-user-form').trigger('submit');expect(api.create).toHaveBeenCalledOnce();expect(api.create.mock.calls[0][0].phone).toBe('+8613800000001');resolve({data:user});await flushPromises();expect(wrapper.get('dialog footer').text()).toContain('完成');});
it('does not load directory data for a cross-role direct link',async()=>{const {wrapper}=await show('/admin/users',{username:'promoter',role:'PROMOTER'});expect(wrapper.get('h1').text()).toBe('无权限');expect(api.list).not.toHaveBeenCalled();});
it('loads the server preset after an initially unavailable account without replacing typed yuan',async()=>{const wrapper=mount(AmountAdjustment,{props:{userId:user.id,keyId:key.keyId,options:null}});await flushPromises();expect((wrapper.get('input[aria-label="人民币金额（元）"]').element as HTMLInputElement).value).toBe('');await wrapper.setProps({options});expect((wrapper.get('input[aria-label="人民币金额（元）"]').element as HTMLInputElement).value).toBe('5.00');await wrapper.get('input[aria-label="人民币金额（元）"]').setValue('8.23');await wrapper.setProps({options:{...options,rules:[{...options.rules[0],presetAmounts4:['10.0000']}]}});expect((wrapper.get('input[aria-label="人民币金额（元）"]').element as HTMLInputElement).value).toBe('8.23');});
it('restores an unresolved debit when the real Key arrives after mounting the detail panel',async()=>{const body={requestId:'debit-response-lost',keyId:key.keyId,amount4:'0.50',remark:'原扣减'};sessionStorage.setItem('xzwl-amount-adjustment:admin:real-user',JSON.stringify({kind:'debit',body}));const wrapper=mount(AmountAdjustment,{props:{userId:user.id,options:null}});await flushPromises();await wrapper.setProps({keyId:key.keyId,options});await flushPromises();expect(wrapper.text()).toContain('原扣减申请尚未确认受理');api.debit.mockResolvedValue({data:{availablePoints4:'40.0000'}});await wrapper.findAll('button').find(b=>b.text()==='重试原扣减申请')!.trigger('click');await flushPromises();expect(api.debit).toHaveBeenCalledWith(body);});
it('resolves legacy Key query links through the same server owner lookup',async()=>{const {router}=await show('/admin/keys?keyId=query-key');await flushPromises();expect(api.key).toHaveBeenCalledWith('query-key');expect(router.currentRoute.value.path).toBe('/admin/users/real-user');await router.push('/admin/keys?userRef=real-user');await flushPromises();expect(router.currentRoute.value.path).toBe('/admin/users/real-user');});
it('keeps the displayed pending enterprise request distinct from the current personal account',async()=>{const body={requestId:'pending-enterprise',accountType:'ENTERPRISE',createKey:false};sessionStorage.setItem('xzwl-user-account:admin:real-user',JSON.stringify(body));const {wrapper}=await show('/admin/users/real-user');expect(wrapper.text()).toContain('当前账户：个人');const select=wrapper.get('select[aria-label="账户类型"]');expect((select.element as HTMLSelectElement).value).toBe('ENTERPRISE');expect(select.attributes('disabled')).toBeDefined();await wrapper.findAll('button').find(b=>b.text()==='刷新账户')!.trigger('click');await flushPromises();expect((select.element as HTMLSelectElement).value).toBe('ENTERPRISE');await wrapper.findAll('button').find(b=>b.text()==='重试原账户保存')!.trigger('click');await flushPromises();expect(api.account).toHaveBeenCalledWith(user.id,body);});
it.each([400,422])('unlocks all original forms after an uncertain attempt receives definitive HTTP %s',async status=>{
 const {ApiError}=await import('./api');const rejected=new ApiError(new Response(null,{status}),{code:'INVALID_REQUEST'});
 api.manual.mockRejectedValueOnce(new TypeError('lost response')).mockRejectedValueOnce(rejected);
 const amount=mount(AmountAdjustment,{props:{userId:user.id,keyId:key.keyId,options}});await flushPromises();await amount.get('input[aria-label="到账凭证与审核备注"]').setValue('原备注');await amount.get('form').trigger('submit');await flushPromises();await amount.findAll('button').find(b=>b.text()==='重试原人工申请')!.trigger('click');await flushPromises();expect(amount.get('fieldset').attributes('disabled')).toBeUndefined();expect(amount.get('[role="alert"]').text()).toContain('服务端未受理');expect(amount.get('[role="alert"]').text()).not.toContain('未确认');expect((amount.get('input[aria-label="到账凭证与审核备注"]').element as HTMLInputElement).value).toBe('原备注');expect(api.manual).toHaveBeenCalledTimes(2);
 const accountBody={requestId:'pending-rejected-account',accountType:'PERSONAL',createKey:false};sessionStorage.setItem('xzwl-user-account:admin:real-user',JSON.stringify(accountBody));api.account.mockRejectedValue(rejected);const account=await show('/admin/users/real-user');await account.wrapper.findAll('button').find(b=>b.text()==='重试原账户保存')!.trigger('click');await flushPromises();expect(account.wrapper.get('select[aria-label="账户类型"]').attributes('disabled')).toBeUndefined();expect(sessionStorage.getItem('xzwl-user-account:admin:real-user')).toBeNull();
 const createBody={requestId:'pending-rejected-user',nickname:'原昵称',phone:user.phone};sessionStorage.setItem('xzwl-create-user:admin',JSON.stringify({body:createBody,type:'PERSONAL',createKey:false}));api.create.mockRejectedValue(rejected);const directory=await show();await directory.wrapper.findAll('button').find(b=>b.text()==='继续未确认的创建')!.trigger('click');await directory.wrapper.get('form.new-user-form').trigger('submit');await flushPromises();expect(directory.wrapper.get('input[aria-label="昵称"]').attributes('disabled')).toBeUndefined();expect((directory.wrapper.get('input[aria-label="昵称"]').element as HTMLInputElement).value).toBe('原昵称');expect(sessionStorage.getItem('xzwl-create-user:admin')).toBeNull();
});
it('waits for existing creation draft restoration before opening or creating a user',async()=>{let resolve!:(value:unknown)=>void;const pending=new Promise(r=>{resolve=r;});api.session.mockReturnValue(pending);const body={requestId:'older-request',nickname:'旧草稿',phone:user.phone};sessionStorage.setItem('xzwl-create-user:admin',JSON.stringify({body,type:'PERSONAL',createKey:false}));const {wrapper}=await show();const button=wrapper.findAll('button').find(b=>b.text()==='新增用户')!;expect(button.attributes('disabled')).toBeDefined();await button.trigger('click');expect(wrapper.get('dialog').attributes('open')).toBeUndefined();await wrapper.get('form.new-user-form').trigger('submit');expect(api.create).not.toHaveBeenCalled();resolve({authenticated:true,user:{id:'admin'}});await flushPromises();await wrapper.findAll('button').find(b=>b.text()==='继续未确认的创建')!.trigger('click');expect((wrapper.get('input[aria-label="昵称"]').element as HTMLInputElement).value).toBe('旧草稿');await wrapper.get('form.new-user-form').trigger('submit');await flushPromises();expect(api.create).toHaveBeenCalledWith(body);});
it('places the native create submit and cancel actions in the dialog footer',async()=>{const {wrapper}=await show();await wrapper.findAll('button').find(b=>b.text()==='新增用户')!.trigger('click');const form=wrapper.get('form.new-user-form');expect(form.attributes('id')).toBe('new-user-create-form');const submit=wrapper.get('dialog footer button[type="submit"]');expect(submit.attributes('form')).toBe('new-user-create-form');expect(submit.text()).toBe('创建用户');expect(wrapper.get('dialog footer').text()).toContain('取消');expect(form.find('button[type="submit"]').exists()).toBe(false);});
it('keeps creation closed when draft initialization fails and safely restores it on retry',async()=>{const body={requestId:'preserve-on-init-error',nickname:'已存在草稿',phone:user.phone};sessionStorage.setItem('xzwl-create-user:admin',JSON.stringify({body,type:'PERSONAL',createKey:false}));api.session.mockRejectedValue(new TypeError('session unavailable'));const {wrapper}=await show();expect(wrapper.text()).toContain('旧草稿不会被覆盖');expect(wrapper.findAll('button').find(b=>b.text()==='新增用户')!.attributes('disabled')).toBeDefined();expect(sessionStorage.getItem('xzwl-create-user:admin')).toContain('preserve-on-init-error');api.session.mockResolvedValue({authenticated:true,user:{id:'admin'}});await wrapper.findAll('button').find(b=>b.text()==='重试读取创建草稿')!.trigger('click');await flushPromises();await wrapper.findAll('button').find(b=>b.text()==='继续未确认的创建')!.trigger('click');await wrapper.get('form.new-user-form').trigger('submit');await flushPromises();expect(api.create).toHaveBeenCalledWith(body);});
it('retains the exact uncertain amount request when retrying receives a conflict',async()=>{const {ApiError}=await import('./api');const body={requestId:'still-uncertain',userId:user.id,accountType:'PERSONAL',amount4:'5.00',remark:'原请求'};sessionStorage.setItem('xzwl-amount-adjustment:admin:real-user',JSON.stringify({kind:'recharge',body}));api.manual.mockRejectedValue(new ApiError(new Response(null,{status:409}),{code:'IDEMPOTENCY_CONFLICT'}));const wrapper=mount(AmountAdjustment,{props:{userId:user.id,keyId:key.keyId,options}});await flushPromises();await wrapper.findAll('button').find(b=>b.text()==='重试原人工申请')!.trigger('click');await flushPromises();expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined();expect(JSON.parse(sessionStorage.getItem('xzwl-amount-adjustment:admin:real-user')!).body).toEqual(body);expect(api.manual).toHaveBeenCalledWith(body);});
it('reports a definitive permission denial instead of an uncertain request',async()=>{const {ApiError}=await import('./api');const forbidden=new ApiError(new Response(null,{status:403}),{code:'FORBIDDEN'});api.manual.mockRejectedValue(forbidden);const amount=mount(AmountAdjustment,{props:{userId:user.id,keyId:key.keyId,options}});await flushPromises();await amount.get('input[aria-label="到账凭证与审核备注"]').setValue('原备注');await amount.get('form').trigger('submit');await flushPromises();expect(amount.get('[role="alert"]').text()).toContain('没有此操作权限');expect(amount.get('[role="alert"]').text()).not.toContain('未确认');api.create.mockRejectedValue(forbidden);const {wrapper}=await show();await wrapper.findAll('button').find(b=>b.text()==='新增用户')!.trigger('click');await wrapper.get('input[aria-label="昵称"]').setValue('新用户');await wrapper.get('input[aria-label="手机号"]').setValue(user.phone);await wrapper.get('form.new-user-form').trigger('submit');await flushPromises();expect(wrapper.get('dialog [role="alert"]').text()).toContain('没有创建用户权限');expect(wrapper.get('dialog [role="alert"]').text()).not.toContain('未确认');});
it('edits an existing user without a Key and generates its primary Key without creating a user',async()=>{
 api.keys.mockResolvedValue({data:[]});
 api.account.mockImplementation(async()=>{api.keys.mockResolvedValue({data:[key]});return {data:options};});
 const {wrapper,router}=await show();
 await wrapper.get('button[aria-label="编辑用户 真实用户"]').trigger('click');await flushPromises();
 const dialog=wrapper.get('dialog[aria-labelledby="edit-user-title"]');
 expect(dialog.attributes('open')).toBeDefined();expect(router.currentRoute.value.path).toBe('/admin/users');
 expect(dialog.text()).toContain('真实用户');expect(dialog.text()).toContain(user.phone);
 expect(dialog.get('fieldset').attributes('disabled')).toBeDefined();
 expect((dialog.get('input[type="checkbox"]').element as HTMLInputElement).checked).toBe(true);
 await dialog.get('.account-section form').trigger('submit');await flushPromises();
 expect(api.account).toHaveBeenCalledWith(user.id,expect.objectContaining({accountType:'PERSONAL',createKey:true}));
 expect(api.create).not.toHaveBeenCalled();expect(dialog.text()).not.toContain(key.keyId);
 expect(dialog.get('fieldset').attributes('disabled')).toBeUndefined();
 expect(wrapper.get('tbody').text()).not.toContain(key.keyId);
 expect((await axe(dialog.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
});
it('edits an existing Key with the shared yuan recharge and debit actions and preserves input on reopen',async()=>{
 api.manual.mockResolvedValue({data:{orderId:'manual-review'}});api.debit.mockResolvedValue({data:{availablePoints4:'31.1111'}});
 const {wrapper}=await show();const edit=wrapper.get('button[aria-label="编辑用户 真实用户"]');
 await edit.trigger('click');await flushPromises();const dialog=wrapper.get('dialog[aria-labelledby="edit-user-title"]');
 await dialog.get('input[aria-label="人民币金额（元）"]').setValue('5.00');
 await dialog.get('input[aria-label="到账凭证与审核备注"]').setValue('编辑用户充值');
 await dialog.get('.amount-adjustment form').trigger('submit');await flushPromises();
 expect(api.manual).toHaveBeenCalledWith(expect.objectContaining({userId:user.id,amount4:'5.00'}));
 await dialog.get('input[aria-label="人民币金额（元）"]').setValue('1.00');
 await dialog.trigger('cancel');expect(dialog.attributes('open')).toBeUndefined();
 await edit.trigger('click');await flushPromises();
 expect((dialog.get('input[aria-label="人民币金额（元）"]').element as HTMLInputElement).value).toBe('1.00');
 await dialog.findAll('button').find(b=>b.text()==='扣减')!.trigger('click');await flushPromises();
 expect(api.debit).toHaveBeenCalledWith(expect.objectContaining({keyId:key.keyId,amount4:'1.00'}));
 expect(api.create).not.toHaveBeenCalled();expect(api.account).not.toHaveBeenCalled();
});

it('shows the actual primary secret in its own list cell for the primary admin',async()=>{
 const {wrapper}=await show();expect(api.secret).toHaveBeenCalledWith(key.keyId);
 expect(wrapper.get('tbody').text()).not.toContain(user.id);
 expect(wrapper.get('tbody tr td:nth-child(2)').text()).toBe('13800000001');
 expect(wrapper.get('tbody').text()).not.toContain('sk-test-primary-secret');
 expect(wrapper.get('tbody tr td:nth-child(4) code').text()).toBe('sk-****cret');
 expect(wrapper.get('tbody tr td:first-child a').attributes('href')).toBe('/admin/users/real-user');
 expect(sessionStorage.length).toBe(0);
});
it.each([{username:'normal',role:'NORMAL_ADMIN',primary:false},{username:'secondary',role:'ADMIN',primary:false}] as AdminIdentity[])('does not request or render plaintext for $username',async identity=>{
 const {wrapper}=await show('/admin/users',identity);expect(api.secret).not.toHaveBeenCalled();expect(wrapper.text()).not.toContain('sk-test-primary-secret');expect(wrapper.get('tbody tr td:nth-child(4)').text()).toBe('—');
});
it('keeps the wallet and user available if plaintext loading fails',async()=>{
 api.secret.mockRejectedValueOnce(new Error('unavailable'));const {wrapper}=await show();expect(wrapper.text()).toContain('Key 明文读取失败');expect(wrapper.text()).toContain('41.2345');expect(wrapper.text()).toContain('真实用户');
});
it('discards delayed plaintext when the current administrator changes',async()=>{
 let resolve!:(value:unknown)=>void;api.secret.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
 const {wrapper,currentAdmin}=await show();expect(api.secret).toHaveBeenCalledOnce();currentAdmin.value={username:'normal',role:'NORMAL_ADMIN',primary:false};await flushPromises();
 resolve({data:{secret:'sk-late-secret'}});await flushPromises();expect(wrapper.text()).not.toContain('sk-late-secret');expect(api.secret).toHaveBeenCalledOnce();
});

it('copies the complete primary key behind its abbreviated display and reports success on the button',async()=>{
 const copy=vi.fn().mockResolvedValue(undefined);Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:copy}});
 const {wrapper}=await show();const button=wrapper.get('button[aria-label="复制真实用户的主 Key"]');
 await button.trigger('click');await flushPromises();expect(copy).toHaveBeenCalledExactlyOnceWith('sk-test-primary-secret');expect(button.text()).toBe('已复制');
 expect(wrapper.get('tbody tr td:nth-child(4)').text()).not.toMatch(/Key 已启用|Key 已停用/);
});
it('allows retrying a failed clipboard write without claiming it succeeded',async()=>{
 const copy=vi.fn().mockRejectedValueOnce(Error('denied')).mockResolvedValue(undefined);Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:copy}});
 const {wrapper}=await show();const button=wrapper.get('button[aria-label="复制真实用户的主 Key"]');
 await button.trigger('click');await flushPromises();expect(button.text()).toBe('复制失败，重试');
 await button.trigger('click');await flushPromises();expect(copy).toHaveBeenCalledTimes(2);expect(button.text()).toBe('已复制');
});

it('creates an API identity with its primary key and restores the exact uncertain account request',async()=>{
 api.options.mockResolvedValue({data:{...options,accountType:null}});api.keys.mockResolvedValue({data:[]});
 const {wrapper}=await show();await wrapper.findAll('button').find(b=>b.text()==='新增用户')!.trigger('click');
 await wrapper.get('input[aria-label="昵称"]').setValue('API 用户');await wrapper.get('input[aria-label="手机号"]').setValue('13800000001');
 const select=wrapper.get('select[aria-label="新用户账户类型"]');expect(select.findAll('option').map(o=>o.attributes('value'))).toContain('API');await select.setValue('API');
 await wrapper.get('form.new-user-form').trigger('submit');await flushPromises();
 expect((wrapper.get('dialog select[aria-label="账户类型"]').element as HTMLSelectElement).value).toBe('API');
 api.account.mockRejectedValueOnce(new TypeError('lost response')).mockResolvedValueOnce({data:{...options,accountType:'API'}});
 await wrapper.get('dialog .account-section form').trigger('submit');await flushPromises();
 const body=api.account.mock.calls[0][1];expect(body).toMatchObject({accountType:'API',createKey:true});wrapper.unmount();api.options.mockResolvedValue({data:{...options,accountType:'API'}});api.identities.mockImplementation(async({userIds}:{userIds:string[]})=>({data:userIds.map(userId=>({userId,accountType:'API'}))}));
 const restored=await show('/admin/users/real-user');await restored.wrapper.findAll('button').find(b=>b.text()==='重试原账户保存')!.trigger('click');await flushPromises();
 expect(api.account.mock.calls[1][1]).toEqual(body);expect(api.create).toHaveBeenCalledOnce();
});
it('restores API selection when retrying an uncertain new user creation',async()=>{
 const body={requestId:'api-create-pending',nickname:'API 用户',phone:user.phone};sessionStorage.setItem('xzwl-create-user:admin',JSON.stringify({body,type:'API',createKey:true}));
 const {wrapper}=await show();await wrapper.findAll('button').find(b=>b.text()==='继续未确认的创建')!.trigger('click');expect((wrapper.get('select[aria-label="新用户账户类型"]').element as HTMLSelectElement).value).toBe('API');
});

it('places API management after agents and partitions API accounts from ordinary users',async()=>{
 expect(adminNavigation[3]).toMatchObject({label:'API 管理',to:'/admin/api-users'});
 api.list.mockResolvedValue({data:{items:[user,{...user,id:'api-owner',nickname:'接口用户'}],total:2}});
 api.identities.mockImplementation(async({userIds}:{userIds:string[]})=>({data:userIds.map(userId=>({userId,accountType:userId==='api-owner'?'API':'PERSONAL'}))}));
 const ordinary=await show();expect(ordinary.wrapper.text()).not.toContain('接口用户');ordinary.wrapper.unmount();
 const {wrapper}=await show('/admin/api-users');expect(wrapper.get('h1').text()).toBe('API 管理');
 expect(wrapper.findAll('tbody tr')).toHaveLength(1);expect(wrapper.get('tbody').text()).toContain('接口用户');expect(wrapper.get('tbody').text()).not.toContain('真实用户');
 expect(wrapper.get('[data-testid="enter-api-api-owner"]').attributes('disabled')).toBeUndefined();
});
it('enters the selected API business user without a login credential in an independent browser-bound tab',async()=>{
 const {webcrypto}=await import('node:crypto');vi.stubGlobal('crypto',webcrypto);
 api.options.mockResolvedValue({data:{...options,accountType:'API'}});api.identities.mockImplementation(async({userIds}:{userIds:string[]})=>({data:userIds.map(userId=>({userId,accountType:'API'}))}));api.credentials.mockResolvedValue({data:[]});api.ticket.mockResolvedValue({data:{ticket:'d'.repeat(64)}});
 const replace=vi.fn(),close=vi.fn(),storage=new Map<string,string>();
 vi.stubGlobal('open',vi.fn(()=>({opener:{},closed:false,close,location:{replace},sessionStorage:{clear:()=>storage.clear(),setItem:(k:string,v:string)=>storage.set(k,v)}})));
 try{const {wrapper}=await show('/admin/api-users');await wrapper.get('[data-testid="enter-api-real-user"]').trigger('click');await flushPromises();
 expect(api.ticket).toHaveBeenCalledWith(user.id,{challenge:expect.stringMatching(/^[0-9a-f]{64}$/)});
 expect(replace).toHaveBeenCalledWith(expect.stringMatching(/^\/agent\/[0-9a-f]{32}\/admin\/portal-entry#d{64}$/));expect(storage.get('xzwl.portal.verifier')).toMatch(/^[0-9a-f]{64}$/);expect(close).not.toHaveBeenCalled();
 }finally{vi.unstubAllGlobals();}
});
it('allows normal admins to view API accounts without issuing entry tickets',async()=>{
 api.options.mockResolvedValue({data:{...options,accountType:'API'}});api.identities.mockImplementation(async({userIds}:{userIds:string[]})=>({data:userIds.map(userId=>({userId,accountType:'API'}))}));
 const {wrapper}=await show('/admin/api-users',{username:'normal',role:'NORMAL_ADMIN',primary:false});
 expect(wrapper.text()).toContain('真实用户');expect(wrapper.find('[data-testid="enter-api-real-user"]').exists()).toBe(false);expect(api.credentials).not.toHaveBeenCalled();expect(api.ticket).not.toHaveBeenCalled();
});

it.each(['/admin/users','/admin/api-users'])('does not misclassify accounts or publish an incorrect total after lookup failure on %s',async path=>{
 api.identities.mockRejectedValue(new Error('unavailable'));
 const {wrapper}=await show(path);expect(wrapper.findAll('tbody tr')).toHaveLength(0);expect(wrapper.text()).toContain('无法读取真实用户或账户，请重试');
});
it('does not offer API creation to a normal administrator',async()=>{
 const {wrapper}=await show('/admin/api-users',{username:'normal',role:'NORMAL_ADMIN',primary:false});
 expect(wrapper.findAll('button').some(b=>b.text()==='新增用户')).toBe(false);expect(api.create).not.toHaveBeenCalled();
});

it('exposes create recovery and failed editor reads in embedded mode',async()=>{api.session.mockRejectedValue(new Error('offline'));const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/users',component:{template:'<div />'}}]});await router.push('/admin/users');await router.isReady();const wrapper=mount(UserManagement,{props:{embedded:true},global:{plugins:[router],provide:{[adminSessionKey as symbol]:{currentAdmin:ref({role:'ADMIN',primary:true,username:'admin'})}}}});await flushPromises();expect(wrapper.text()).toContain('旧草稿不会被覆盖');api.session.mockResolvedValue({authenticated:true,user:{id:'admin'}});await wrapper.findAll('button').find(b=>b.text()==='重试读取创建草稿')!.trigger('click');await flushPromises();expect(wrapper.vm.createReady).toBe(true);await wrapper.vm.openEditor(user.id,new Event('click'));await flushPromises();await wrapper.get('button[aria-label="关闭编辑用户"]').trigger('click');api.detail.mockRejectedValueOnce(new Error('offline'));await wrapper.vm.openEditor(user.id,new Event('click'));await flushPromises();expect(wrapper.get('.user-editor-host > [role="alert"]').text()).toContain('无法读取用户');});

it('previews reference balance changes exactly and retries the same debit request',async()=>{
 api.debit.mockRejectedValueOnce(new TypeError('response lost')).mockResolvedValueOnce({data:{availablePoints4:'10.0000'}});
 const wrapper=mount(AmountAdjustment,{props:{userId:user.id,keyId:key.keyId,options,reference:true,availableAmount4:'2560.1234'}});
 await flushPromises();
 await wrapper.get('button[aria-label="减少额度"]').trigger('click');
 await wrapper.get('input[aria-label="人民币金额（元）"]').setValue('1000.01');
 await wrapper.get('input[aria-label="到账凭证与审核备注"]').setValue('修正额度');
 expect(wrapper.get('[data-testid="adjusted-balance"]').attributes('title')).toBe('1560.1134');
 await wrapper.get('form').trigger('submit');await flushPromises();
 const body=api.debit.mock.calls[0][0];expect(body.amount4).toBe('1000.01');
 expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined();
 await wrapper.findAll('button').find(b=>b.text()==='重试原扣减申请')!.trigger('click');await flushPromises();
 expect(api.debit.mock.calls[1][0]).toEqual(body);expect(wrapper.emitted('changed')).toHaveLength(1);
});

it('creates a reference user with metadata and safely resumes account setup without a second identity',async()=>{
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/users',component:UserManagement}]});await router.push('/admin/users');await router.isReady();
 const wrapper=mount(UserManagement,{props:{embedded:true},global:{plugins:[router],provide:{[adminSessionKey as symbol]:{currentAdmin:ref({role:'ADMIN',primary:true,username:'admin'})}}}});await flushPromises();
 (wrapper.vm as unknown as {openNew:()=>void}).openNew();await flushPromises();
 await wrapper.get('input[aria-label="手机号"]').setValue('13800000001');await wrapper.get('input[aria-label="企业用户"]').setValue();await wrapper.get('select[aria-label="注册来源"]').setValue('REFERRAL');await wrapper.get('textarea[aria-label="用户备注"]').setValue('线下活动');
 api.account.mockRejectedValueOnce(new TypeError('lost')).mockResolvedValueOnce({data:{}});
 await wrapper.get('form.new-user-form').trigger('submit');await flushPromises();
 expect(api.create.mock.calls[0][0]).toMatchObject({nickname:'用户0001',phone:'+8613800000001',source:'REFERRAL',remark:'线下活动'});
 const account=api.account.mock.calls[0][1];expect(account).toMatchObject({accountType:'ENTERPRISE',createKey:true});expect(wrapper.get('dialog[open]').text()).toContain('账户设置尚未完成');
 await wrapper.get('form.new-user-form').trigger('submit');await flushPromises();expect(api.create.mock.calls[1][0]).toEqual(api.create.mock.calls[0][0]);expect(api.account.mock.calls[1][1]).toEqual(account);expect(wrapper.find('dialog[open]').exists()).toBe(false);
});
it('reference normal-user creation does not silently activate a paid account',async()=>{
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/users',component:UserManagement}]});await router.push('/admin/users');await router.isReady();const wrapper=mount(UserManagement,{props:{embedded:true},global:{plugins:[router]}});await flushPromises();(wrapper.vm as unknown as {openNew:()=>void}).openNew();await flushPromises();await wrapper.get('input[aria-label="手机号"]').setValue('13800000001');await wrapper.get('input[aria-label="普通用户"]').setValue();await wrapper.get('form.new-user-form').trigger('submit');await flushPromises();expect(api.create).toHaveBeenCalledTimes(1);expect(api.account).not.toHaveBeenCalled();
});
it('reference enterprise recharge does not require a company name rejected by no current rule',async()=>{
 const wrapper=mount(AmountAdjustment,{props:{userId:user.id,keyId:key.keyId,reference:true,availableAmount4:'50.0000',options:{...options,accountType:'ENTERPRISE',rules:[{accountType:'ENTERPRISE',minimumAmount4:'5.0000',presetAmounts4:['5.0000']}]}}});await flushPromises();await wrapper.get('input[aria-label="到账凭证与审核备注"]').setValue('已核对转账');await wrapper.get('form').trigger('submit');await flushPromises();expect(api.manual).toHaveBeenCalledWith(expect.objectContaining({accountType:'ENTERPRISE',amount4:'5.00'}));expect(api.manual.mock.calls[0][0].enterpriseName).toBeUndefined();
});
it.each([null,'API'])('allows cancelling reference adjustments for unavailable account %s',async(accountType)=>{const wrapper=mount(AmountAdjustment,{props:{userId:user.id,reference:true,options:{...options,accountType}}});await flushPromises();const cancel=wrapper.findAll('button').find(b=>b.text()==='取消')!;expect((cancel.element as HTMLButtonElement).matches(':disabled')).toBe(false);await cancel.trigger('click');expect(wrapper.emitted('cancel')).toHaveLength(1);});
it('does not save an account after an in-flight creation outlives its page',async()=>{let finish!:(value:unknown)=>void;api.create.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/users',component:UserManagement}]});await router.push('/admin/users');await router.isReady();const wrapper=mount(UserManagement,{props:{embedded:true},global:{plugins:[router]}});await flushPromises();(wrapper.vm as unknown as {openNew:()=>void}).openNew();await flushPromises();await wrapper.get('input[aria-label="手机号"]').setValue('13800000001');await wrapper.get('form.new-user-form').trigger('submit');wrapper.unmount();finish({data:user});await flushPromises();expect(api.account).not.toHaveBeenCalled();expect(sessionStorage.getItem('xzwl-create-user:admin')).not.toBeNull();});

it.each(['PERSONAL','ENTERPRISE'])('admin %s adjustments accept whole cents below platform minimum',async(accountType)=>{
 const wrapper=mount(AmountAdjustment,{props:{userId:user.id,keyId:key.keyId,reference:true,availableAmount4:'0.0000',options:{...options,accountType,rules:[{accountType,minimumAmount4:'5000.0000',presetAmounts4:[]}]}}});await flushPromises();
 await wrapper.get('input[aria-label="到账凭证与审核备注"]').setValue('调整');
 for(const amount of ['0','-1','0.001','abc']){await wrapper.get('input[aria-label="人民币金额（元）"]').setValue(amount);expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined();}
 await wrapper.get('input[aria-label="人民币金额（元）"]').setValue('0.01');expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined();expect(wrapper.text()).not.toContain('充值最低');
 await wrapper.get('button[aria-label="减少额度"]').trigger('click');expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined();
 await wrapper.get('button[aria-label="增加额度"]').trigger('click');await wrapper.get('form').trigger('submit');await flushPromises();expect(api.manual).toHaveBeenCalledWith(expect.objectContaining({accountType,amount4:'0.01'}));
});
