import {mount,flushPromises,enableAutoUnmount,type VueWrapper} from '@vue/test-utils';
import {it,expect,vi,beforeEach,afterEach} from 'vitest';
import {ref} from 'vue';
import {axe} from 'vitest-axe';
import {createRouter,createMemoryHistory} from 'vue-router';
const api=vi.hoisted(()=>({list:vi.fn(),detail:vi.fn(),access:vi.fn(),users:vi.fn(),secret:vi.fn(),lookup:vi.fn(),analytics:vi.fn()}));
vi.mock('./api',()=>({billingListGetApiV1AdminBillingConsumptions:api.list,billingDetailGetApiV1AdminBillingConsumptionsId:api.detail,billingAccessGetApiV1AdminBillingConsumptionsIdFilesFileIdAccessUrl:api.access,userListGetApiV1AdminUsers:api.users,accessAdminSecretGetApiV1AdminAccessKeysIdSecret:api.secret,accessAdminLookupPostApiV1AdminAccessKeysLookup:api.lookup,billingAnalyticsGetApiV1AdminBillingConsumptionsAnalytics:api.analytics}));
import ConsumptionManagement from './ConsumptionManagement.vue';
import ConsumptionPreview from './ConsumptionPreview.vue';
import {adminSessionKey} from './adminSession';
const ledger={id:'entry-real',taskId:'task-real',keyId:'disabled-real-key',ownerId:'owner',type:'IMAGE',model:'model-v1',accountType:null,createdAt:'2026-09-09T01:02:03Z',debitedPoints4:'4.0000',refundedPoints4:'2.0000',netPoints4:'2.0000',amount4:null,rate4:null};
const item={ledger,user:{id:'owner',nickname:'真实用户',phone:'138****1234'},currentAccountType:'PERSONAL',keyEnabled:false,taskStatus:'PARTIAL_SUCCESS',modelName:'历史模型'};
const detail={item,entries:[{id:'entry-real',requestId:'task-real',kind:'CONSUME',points4:'-4.0000',balanceBefore4:'10.0000',balanceAfter4:'6.0000',createdAt:ledger.createdAt},{id:'refund',requestId:'task-refund',kind:'REFUND',points4:'2.0000',balanceBefore4:'6.0000',balanceAfter4:'8.0000',createdAt:ledger.createdAt}],snapshot:{ownerId:'owner',references:[{index:0,fileId:'reference-real'}],expectedRefundPoints4:'2.0000',task:{id:'task-real',type:'IMAGE',status:'PARTIAL_SUCCESS',model:'model-v1',modelName:'历史模型',prompt:'提交时原始提示词',count:2,aspectRatio:'1:1',resolution:'2K',durationSeconds:0,createdAt:ledger.createdAt,results:[{index:0,fileId:'output-real'}],failures:[{index:1,message:'生成失败'}],refundStatus:'COMPLETED',referenceFollowsFirstFrame:false}}};
enableAutoUnmount(afterEach);
beforeEach(()=>{vi.clearAllMocks();api.analytics.mockResolvedValue({data:{total:105,amount4:null,unknownAmounts:105,models:[],days:[]}});api.list.mockResolvedValue({data:{items:[item],total:105,offset:0,limit:10}});api.detail.mockResolvedValue({data:detail});api.secret.mockResolvedValue({data:{secret:'sk-platform-real'}});api.access.mockResolvedValue({data:{url:'https://storage.example/real',expiresAt:'2099-01-01T00:00:00Z'}});api.users.mockResolvedValue({data:{items:[item.user]}});});
async function show(path='/admin/consumption',role='ADMIN'){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/consumption',component:ConsumptionManagement},{path:'/admin/consumption/:recordRef',component:ConsumptionManagement}]});await router.push(path);await router.isReady();const currentAdmin=ref({username:'admin',role,primary:role==='ADMIN'});const wrapper=mount(ConsumptionManagement,{global:{plugins:[router],provide:{[adminSessionKey as symbol]:{currentAdmin}}}});await flushPromises();return {wrapper,router,currentAdmin};}
it('shows historical yuan charge and debit-time balances separately from refunds',async()=>{
 api.detail.mockResolvedValue({data:{...detail,item:{...item,ledger:{...ledger,amount4:'0.4000',rate4:'10.0000'}}}});
 const {wrapper}=await show('/admin/consumption/entry-real');const card=wrapper.get('[aria-label="消费金额"]');
 expect(card.text()).toContain('消耗金额：0.4 元');expect(card.text()).toContain('消耗前余额：1 元');expect(card.text()).toContain('消耗后余额：0.6 元');
 expect(card.text()).toContain('退回金额：0.2 元');expect(card.text()).toContain('已退款');expect(card.text()).not.toContain('积分');
});
it.each([null,'0.0000','-1.0000','invalid'])('does not invent yuan balances with invalid historical rate %s',async rate4=>{
 api.detail.mockResolvedValue({data:{...detail,item:{...item,ledger:{...ledger,rate4}}}});
 const {wrapper}=await show('/admin/consumption/entry-real');const card=wrapper.get('[aria-label="消费金额"]');
 expect(card.text()).toContain('消耗金额：—');expect(card.text()).toContain('消耗前余额：—');expect(card.text()).toContain('消耗后余额：—');
});
it('retains large balance precision and truncates only at four yuan decimal places',async()=>{
 api.detail.mockResolvedValue({data:{...detail,item:{...item,ledger:{...ledger,amount4:'1.3333',rate4:'3.0000'}},entries:[{...detail.entries[0],balanceBefore4:'90071992547409.1234',balanceAfter4:'90071992547405.1234'}]}});
 const {wrapper}=await show('/admin/consumption/entry-real');const card=wrapper.get('[aria-label="消费金额"]');
 expect(card.text()).toContain('消耗前余额：30023997515803.0411 元');expect(card.text()).toContain('消耗后余额：30023997515801.7078 元');
});
it.each(['missing','mismatch','inconsistent'])('rejects %s debit balances in the yuan summary',async failure=>{
 const entries=failure==='missing'?[]:[{...detail.entries[0],...(failure==='mismatch'?{id:'different-entry'}:{balanceAfter4:'7.0000'})}];
 api.detail.mockResolvedValue({data:{...detail,item:{...item,ledger:{...ledger,amount4:'0.4000',rate4:'10.0000'}},entries}});
 const {wrapper}=await show('/admin/consumption/entry-real');const card=wrapper.get('[aria-label="消费金额"]');
 expect(card.text()).toContain('消耗金额：0.4 元');expect(card.text()).toContain('消耗前余额：—');expect(card.text()).toContain('消耗后余额：—');
});
it('renders real ledger records and asks server for next page with the same cutoff',async()=>{const {wrapper}=await show();expect(wrapper.text()).toContain('真实用户');expect(wrapper.text()).not.toContain(ledger.keyId);expect(wrapper.get('.consumption-table thead').text()).toContain('消费金额（元）');expect(wrapper.get('.consumption-table tbody').text()).not.toContain('当前账户 ·');expect(wrapper.text()).toContain('2');expect(wrapper.text()).not.toMatch(/Nova|sk-demo/);const cutoff=api.list.mock.calls[0][0].to;await wrapper.get('button[aria-label="下一页"]').trigger('click');await flushPromises();expect(api.list.mock.lastCall?.[0]).toMatchObject({offset:10,limit:10,to:cutoff});});
it('applies filters on server and resets pagination',async()=>{const {wrapper}=await show();await wrapper.get('input[aria-label="模型标识"]').setValue('historical-model');await wrapper.get('select[aria-label="任务状态"]').setValue('RUNNING');await wrapper.get('form.consumption-filters').trigger('submit');await flushPromises();expect(api.list.mock.lastCall?.[0]).toMatchObject({model:'historical-model',status:'RUNNING',offset:0});});
it('shows empty and errors without demo fallback and allows retry',async()=>{api.list.mockRejectedValueOnce(Error('offline')).mockResolvedValueOnce({data:{items:[],total:0,offset:0,limit:10}});const {wrapper}=await show();expect(wrapper.get('[role="alert"]').text()).toContain('读取失败');expect(wrapper.text()).not.toMatch(/Nova|sk-demo/);await wrapper.get('button[aria-label="重试读取"]').trigger('click');await flushPromises();expect(wrapper.text()).toContain('暂无消费记录');});
it('shows prompt, actual outputs and refund ledger; a missing task retains ledger',async()=>{const {wrapper}=await show('/admin/consumption/entry-real');expect(wrapper.text()).toContain('提交时原始提示词');expect(wrapper.text()).toContain('10');expect(wrapper.text()).toContain('已退款');expect(wrapper.get('[aria-label="生成信息"]').findAllComponents(ConsumptionPreview)).toHaveLength(1);expect(api.access.mock.calls.every(([record,file])=>record==='entry-real'&&['reference-real','output-real'].includes(file))).toBe(true);api.detail.mockResolvedValue({data:{...detail,snapshot:null}});await wrapper.get('button[aria-label="刷新详情"]').trigger('click');await flushPromises();expect(wrapper.text()).toContain('历史任务信息缺失');expect(wrapper.text()).toContain('10');});
it('does not call APIs for users without admin permission',async()=>{const {wrapper}=await show('/admin/consumption','PROMOTER');expect(wrapper.text()).toContain('无权限');expect(api.list).not.toHaveBeenCalled();});
it('opens on demand, refreshes expired URLs and reports media read failures',async()=>{
 Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:function(){this.setAttribute('open','');}});
 Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:function(){this.removeAttribute('open');}});
 const wrapper=mount(ConsumptionPreview,{props:{recordId:'entry-real',fileId:'output-real',video:true,label:'视频输出'}});
 await flushPromises();expect(api.access).not.toHaveBeenCalled();
 api.access.mockResolvedValueOnce({data:{url:'https://storage.example/expired',expiresAt:'2000-01-01T00:00:00Z'}});
 await wrapper.get('.consumption-file-actions button').trigger('click');await flushPromises();
 expect(wrapper.find('video').exists()).toBe(false);expect(wrapper.text()).toContain('链接失效');
 await wrapper.get('.consumption-file-actions button').trigger('click');await flushPromises();
 expect(wrapper.get('video').attributes('src')).toBe('https://storage.example/real');
 await wrapper.get('video').trigger('error');await flushPromises();
 expect(wrapper.text()).toContain('暂时无法读取');expect(wrapper.find('video').exists()).toBe(false);
});

it('applies footer page size immediately without applying pending filters',async()=>{const {wrapper}=await show();await wrapper.get('input[aria-label="模型标识"]').setValue('pending-model');await wrapper.get('select[aria-label="每页条数"]').setValue('50');await flushPromises();await wrapper.get('button[aria-label="下一页"]').trigger('click');await flushPromises();expect(api.list.mock.lastCall?.[0]).toMatchObject({offset:50,limit:50});expect(api.list.mock.lastCall?.[0].model).toBeUndefined();await wrapper.get('form').trigger('submit');await flushPromises();expect(api.list.mock.lastCall?.[0]).toMatchObject({offset:0,limit:50});});
it('shows reference-driven video proportions and accessible content',async()=>{api.detail.mockResolvedValue({data:{...detail,snapshot:{...detail.snapshot,task:{...detail.snapshot.task,type:'VIDEO',referenceFollowsFirstFrame:true,aspectRatio:'16:9'}}}});const {wrapper}=await show('/admin/consumption/entry-real');expect(wrapper.text()).toContain('跟随首帧');expect(wrapper.text()).not.toContain('16:9');expect((await axe(wrapper.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);});

it('previews only authorized final and reference files while call-chain links remain on demand',async()=>{
 const {wrapper}=await show('/admin/consumption/entry-real');
 expect(wrapper.find('video').exists()).toBe(false);
 expect(wrapper.find('img').exists()).toBe(true);
 expect(api.access.mock.calls.every(([record,file])=>record==='entry-real'&&['reference-real','output-real'].includes(file))).toBe(true);
 expect(wrapper.text()).toContain('打开图片');
 expect(wrapper.text()).toContain('复制图片链接');
 expect(wrapper.text()).not.toContain('实际生成内容');
});

const generation={provider:'火山',taskId:'upstream-original',status:'COMPLETE',resolution:'480p',durationMs:12345,videoTokens:34567,outputs:[{index:0,fileId:'native-file'},{index:1,fileId:'native-file-2'}]};
const enhanced={provider:'腾讯云 MPS',taskId:'upstream-enhanced',status:'MPS_RUNNING',resolution:'720p',durationMs:null,definitionId:720,scheduleId:null,outputs:[]};
it.each(['MPS_RUNNING','COMPLETE','FAILED'])('separates original and enhancement records in %s state',async state=>{
 api.detail.mockResolvedValue({data:{...detail,snapshot:{...detail.snapshot,task:{...detail.snapshot.task,type:'VIDEO'},generation,enhancement:{...enhanced,status:state,failureStage:state==='FAILED'?'MPS_RUNNING':null,failureCode:state==='FAILED'?'MediaProcessFailed':null,outputs:state==='COMPLETE'?[{index:0,fileId:'enhanced-file'}]:[]}}}});
 const {wrapper}=await show('/admin/consumption/entry-real');
 expect(wrapper.text()).toContain('火山生成');expect(wrapper.text()).toContain('upstream-original');expect(wrapper.text()).toContain('0.21 分钟');expect(wrapper.text()).toContain('34567');
 const original=wrapper.get('[aria-label="生成信息"]');expect(original.text()).not.toContain('enhanced-file');
 expect(wrapper.get('[aria-label="增强信息"]').text()).toContain('upstream-enhanced');
 expect(wrapper.findAll('.consumption-diagnostic').flatMap(w=>w.findAllComponents(ConsumptionPreview)).map(w=>w.props('fileId'))).toEqual(state==='COMPLETE'?['native-file','native-file-2','enhanced-file']:['native-file','native-file-2']);
 expect(api.access.mock.calls.some(([,file])=>file==='native-file'||file==='enhanced-file')).toBe(false);
 if(state==='FAILED')expect(wrapper.get('[aria-label="增强信息"]').text()).toContain('MPS_RUNNING · MediaProcessFailed');
});
it('renders actual other provider, unknown historical metrics and no empty enhancement card',async()=>{
 api.detail.mockResolvedValue({data:{...detail,snapshot:{...detail.snapshot,task:{...detail.snapshot.task,type:'VIDEO'},generation:{...generation,provider:'ZEX',taskId:null,durationMs:null,videoTokens:null},enhancement:null}}});
 const {wrapper}=await show('/admin/consumption/entry-real');
 expect(wrapper.text()).toContain('ZEX生成');expect(wrapper.text()).not.toContain('火山生成');expect(wrapper.find('[aria-label="增强信息"]').exists()).toBe(false);
 expect(wrapper.get('[aria-label="生成信息"] dl').text()).not.toContain('task-real');expect(wrapper.get('[aria-label="生成信息"] dl').text()).toContain('Token未记录');
 expect(wrapper.text()).not.toContain(ledger.keyId);
});
it('copies a newly authorized link each time, handles clipboard denial and rejects unsafe or missing files',async()=>{
 const copy=vi.fn().mockResolvedValue(undefined);Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:copy}});
 const wrapper=mount(ConsumptionPreview,{props:{recordId:'entry-real',fileId:'native-file',video:true,label:'原始输出 1'}});
 await wrapper.get('.consumption-file-actions button:nth-child(2)').trigger('click');await flushPromises();
 expect(api.access).toHaveBeenLastCalledWith('entry-real','native-file');expect(copy).toHaveBeenLastCalledWith('https://storage.example/real');expect(wrapper.text()).toContain('链接已复制');expect(wrapper.find('video').exists()).toBe(false);
 copy.mockRejectedValueOnce(Error('denied'));api.access.mockResolvedValueOnce({data:{url:'https://storage.example/fresh',expiresAt:'2099-01-01T00:00:00Z'}});
 await wrapper.get('.consumption-file-actions button:nth-child(2)').trigger('click');await flushPromises();expect(wrapper.text()).toContain('复制失败');expect(api.access).toHaveBeenCalledTimes(2);
 for(const response of [{data:{url:'http://internal.example/file',expiresAt:'2099-01-01T00:00:00Z'}},{data:{}}]){
 api.access.mockResolvedValueOnce(response);await wrapper.get('.consumption-file-actions button:nth-child(2)').trigger('click');await flushPromises();expect(wrapper.text()).toContain('暂时无法读取');}
 expect(copy).toHaveBeenCalledTimes(2);
 api.access.mockRejectedValueOnce(Error('404'));await wrapper.get('.consumption-file-actions button').trigger('click');await flushPromises();expect(wrapper.text()).toContain('文件已删除');
});
it('loads authorized reference thumbnails in the reference card',async()=>{
 const {wrapper}=await show('/admin/consumption/entry-real');
 expect(api.access.mock.calls.filter(([,file])=>file==='reference-real')).toEqual([['entry-real','reference-real']]);expect(wrapper.get('.consumption-references .consumption-thumbnail img').attributes('alt')).toBe('参考素材 1');
});

it('separates user, time and historical yuan balances despite a later refund',async()=>{
 const paid={...item,ledger:{...ledger,amount4:'0.4000',rate4:'10.0000'}};api.list.mockResolvedValue({data:{items:[paid],total:1}});api.detail.mockResolvedValue({data:{...detail,item:paid}});
 const {wrapper}=await show();expect(wrapper.findAll('.consumption-table thead th').map(c=>c.text())).toEqual(['','用户','时间','类型','模型','分辨率','生成量','单价','消费前额度（元）','消费金额（元）','消费后额度（元）','状态','操作']);
 const cells=wrapper.findAll('.consumption-table tbody tr:first-child td');expect(cells[1]!.text()).toContain('真实用户');expect(cells[1]!.text()).not.toContain('2026');expect(cells[2]!.text()).not.toContain('真实用户');
 expect(wrapper.findAll('[data-column]').map(c=>c.text())).toEqual(['1.00','0.40','0.60']);expect(cells[12]!.text()).toContain('Key · real-key');expect(cells[12]!.text()).not.toContain('sk-platform-real');expect(cells[12]!.text()).not.toContain(ledger.keyId);
 const copy=vi.fn().mockResolvedValue(undefined);Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:copy}});const button=wrapper.get('button[aria-label="复制本条消费的平台 Key"]');await button.trigger('click');await flushPromises();expect(copy).toHaveBeenCalledExactlyOnceWith('sk-platform-real');expect(button.text()).toBe('已复制');
});
it('keeps missing historical amounts and debit balances unknown instead of substituting refunds or current rates',async()=>{
 api.detail.mockResolvedValue({data:{...detail,entries:[detail.entries[1]]}});const {wrapper}=await show();expect(wrapper.findAll('[data-column]').map(c=>c.text())).toEqual(['—','—','—']);
});
it('defers key reads until explicit copy and preserves full accounting precision behind yuan display',async()=>{
 const before='9007199254740993.1234',after='9007199254740989.1234',paid={...item,ledger:{...ledger,amount4:'4.0000',rate4:'1.0000'}};
 api.list.mockResolvedValue({data:{items:[paid,{...paid,ledger:{...paid.ledger,id:'entry-two'}}],total:2}});
 api.detail.mockImplementation(async id=>({data:{...detail,item:{...paid,ledger:{...paid.ledger,id}},entries:[{...detail.entries[0],id,balanceBefore4:before,balanceAfter4:after}]}}));
 const {wrapper}=await show();expect(api.secret).not.toHaveBeenCalled();expect(wrapper.get('[data-column="before"]').attributes('title')).toBe(before);expect(wrapper.get('[data-column="after"]').attributes('title')).toBe(after);expect(wrapper.get('[data-column="amount"]').attributes('title')).toBe('4.0000');
});
it('retains rows on detail or secret failure and does not expose keys to a normal admin',async()=>{
 api.detail.mockRejectedValueOnce(Error('offline'));api.secret.mockRejectedValueOnce(Error('denied'));const {wrapper}=await show();expect(wrapper.findAll('.consumption-table tbody tr')).toHaveLength(1);await wrapper.get('button[aria-label="复制本条消费的平台 Key"]').trigger('click');await flushPromises();expect(wrapper.text()).toContain('复制失败，重试');expect(wrapper.findAll('[data-column]').map(c=>c.text())).toEqual(['—','—','—']);
 api.secret.mockClear();const normal=await show('/admin/consumption','NORMAL_ADMIN');expect(api.secret).not.toHaveBeenCalled();expect(normal.wrapper.text()).not.toContain('sk-platform-real');expect(normal.wrapper.find('button[aria-label="复制本条消费的平台 Key"]').exists()).toBe(false);
});
it('discards late secret data across administrator changes before copying',async()=>{
 let resolve!:(data:unknown)=>void;api.secret.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
 const copied=vi.fn();Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:copied}});
 const {wrapper,currentAdmin}=await show();await wrapper.get('button[aria-label="复制本条消费的平台 Key"]').trigger('click');
 currentAdmin.value={username:'normal',role:'NORMAL_ADMIN',primary:false};await flushPromises();resolve({data:{secret:'sk-platform-real'}});await flushPromises();expect(wrapper.text()).not.toContain('sk-platform-real');expect(api.secret).toHaveBeenCalledOnce();expect(copied).not.toHaveBeenCalled();
});

it('searches users in one control and requires a choice for multiple matches',async()=>{
 const {wrapper}=await show();expect(wrapper.find('[aria-label="筛选用户"]').exists()).toBe(false);
 api.users.mockResolvedValueOnce({data:{items:[item.user,{id:'second',nickname:'第二位',phone:'13912345678'}]}});
 await wrapper.get('[aria-label="搜索消费用户"]').setValue('用户');await wrapper.get('form').trigger('submit');await flushPromises();
 expect(api.list).toHaveBeenCalledOnce();expect(wrapper.findAll('[aria-label="用户检索结果"] button')).toHaveLength(2);
 await wrapper.findAll('[aria-label="用户检索结果"] button')[1]!.trigger('click');await flushPromises();
 expect(api.list.mock.lastCall?.[0].userId).toBe('second');
 await wrapper.get('[aria-label="搜索消费用户"]').setValue('');await wrapper.get('form').trigger('submit');await flushPromises();expect(api.list.mock.lastCall?.[0].userId).toBeUndefined();
});
it('keeps the existing table and filters mounted during a list refresh, and sends selected dates',async()=>{
 const {wrapper}=await show();const table=wrapper.get('table').element,form=wrapper.get('form').element;expect(wrapper.get('[aria-label="开始时间"]').attributes('type')).toBe('date');expect(wrapper.get('[aria-label="截止时间"]').attributes('type')).toBe('date');
 let resolve!:(value:unknown)=>void;api.list.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
 await wrapper.get('[aria-label="开始时间"]').setValue('2026-09-09');await wrapper.get('[aria-label="截止时间"]').setValue('2026-09-11');
 await wrapper.get('form').trigger('submit');await flushPromises();
 expect(wrapper.get('table').element).toBe(table);expect(wrapper.get('form').element).toBe(form);
 expect(api.list.mock.lastCall?.[0]).toMatchObject({from:new Date('2026-09-09T00:00:00.000+08:00').toISOString(),to:new Date('2026-09-11T23:59:59.999+08:00').toISOString()});
 resolve({data:{items:[item],total:1}});await flushPromises();expect(wrapper.get('table').element).toBe(table);
});

it('retains the applied user across details and cancels pending user searches on navigation',async()=>{
 const {wrapper,router}=await show();await wrapper.get('[aria-label="搜索消费用户"]').setValue('真实用户');await wrapper.get('form').trigger('submit');await flushPromises();
 await router.push('/admin/consumption/entry-real');await flushPromises();await router.push('/admin/consumption');await flushPromises();
 expect(wrapper.get('[aria-label="搜索消费用户"]').element).toHaveProperty('value','真实用户 · 138****1234');expect(api.list.mock.lastCall?.[0].userId).toBe('owner');
 let resolve!:(value:unknown)=>void;api.users.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));await wrapper.get('[aria-label="搜索消费用户"]').setValue('pending');await wrapper.get('form').trigger('submit');await flushPromises();
 await router.push('/admin/consumption/entry-real');await flushPromises();await router.push('/admin/consumption');await flushPromises();
 expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined();resolve({data:{items:[item.user]}});await flushPromises();expect(wrapper.find('[aria-label="用户检索结果"]').exists()).toBe(false);
});

it.each(['sk-'+'a'.repeat(32),'sk-_'+'b'.repeat(64)])('searches an exact platform Key through the body and preserves server pagination',async secret=>{
 const {wrapper}=await show();api.lookup.mockResolvedValueOnce({data:{keyId:ledger.keyId,ownerId:'key-owner',enabled:false}});
 await wrapper.get('[aria-label="搜索消费用户"]').setValue(' '+secret+' ');await wrapper.get('form').trigger('submit');await flushPromises();
 expect(api.lookup).toHaveBeenCalledExactlyOnceWith({secret});expect(api.users).not.toHaveBeenCalled();
 expect(api.list.mock.lastCall?.[0]).toMatchObject({userId:'key-owner',offset:0});expect(JSON.stringify(api.list.mock.calls)).not.toContain(secret);
 expect(wrapper.get('[aria-label="搜索消费用户"]').element).not.toHaveProperty('value',secret);
 await wrapper.get('[aria-label="下一页"]').trigger('click');await flushPromises();expect(api.list.mock.lastCall?.[0]).toMatchObject({userId:'key-owner',offset:10});
 await wrapper.get('[aria-label="搜索消费用户"]').setValue('');await wrapper.get('form').trigger('submit');await flushPromises();expect(api.list.mock.lastCall?.[0].userId).toBeUndefined();
});
it('does not fall back to user search or unfiltered consumption for invalid or unknown Keys',async()=>{
 const {wrapper}=await show();
 await wrapper.get('[aria-label="搜索消费用户"]').setValue('sk-_****07b2');await wrapper.get('form').trigger('submit');await flushPromises();
 expect(wrapper.text()).toContain('请输入完整的平台 Key');expect(api.lookup).not.toHaveBeenCalled();
 api.lookup.mockRejectedValueOnce({status:404});await wrapper.get('[aria-label="搜索消费用户"]').setValue('sk-'+'a'.repeat(32));await wrapper.get('form').trigger('submit');await flushPromises();
 expect(wrapper.text()).toContain('未找到匹配的平台 Key');expect(api.users).not.toHaveBeenCalled();expect(api.list).toHaveBeenCalledOnce();
 api.lookup.mockRejectedValueOnce({status:503});await wrapper.get('form').trigger('submit');await flushPromises();expect(wrapper.text()).toContain('检索失败');expect(api.list).toHaveBeenCalledOnce();
});
it('cancels pending Key searches when input or administrator changes',async()=>{
 const {wrapper,currentAdmin}=await show();let resolve!:(value:unknown)=>void;
 api.lookup.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
 await wrapper.get('[aria-label="搜索消费用户"]').setValue('sk-'+'a'.repeat(32));await wrapper.get('form').trigger('submit');await flushPromises();
 await wrapper.get('[aria-label="搜索消费用户"]').setValue('new search');resolve({data:{ownerId:'stale'}});await flushPromises();expect(api.list).toHaveBeenCalledOnce();
 api.lookup.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));await wrapper.get('[aria-label="搜索消费用户"]').setValue('sk-'+'a'.repeat(32));await wrapper.get('form').trigger('submit');await flushPromises();
 currentAdmin.value={username:'normal',role:'NORMAL_ADMIN',primary:false};await flushPromises();const count=api.list.mock.calls.length;
 resolve({data:{ownerId:'stale'}});await flushPromises();expect(api.list).toHaveBeenCalledTimes(count);
 api.lookup.mockClear();await wrapper.get('form').trigger('submit');await flushPromises();expect(api.lookup).not.toHaveBeenCalled();expect(api.users).not.toHaveBeenCalled();expect(wrapper.text()).toContain('仅主管理员可按平台 Key 查询');
});

it('uses the reference table with historical yuan, generation parameters and full-query total',async()=>{
 const paid={...item,ledger:{...ledger,amount4:'0.4000',rate4:'10.0000'}};api.list.mockResolvedValue({data:{items:[paid],total:105}});api.detail.mockResolvedValue({data:{...detail,item:paid}});api.analytics.mockResolvedValue({data:{total:105,amount4:'42.1234',unknownAmounts:0,models:[],days:[]}});
 const {wrapper}=await show();expect(wrapper.find('.consumption-charts').exists()).toBe(false);await wrapper.get('[aria-controls="consumption-analysis"]').trigger('click');await flushPromises();expect(wrapper.find('.consumption-charts').exists()).toBe(true);expect(wrapper.get('.consumption-list-total').text()).toContain('42.12');
 expect(wrapper.get('.consumption-table thead').text()).toContain('消费前额度（元）');expect(wrapper.get('.consumption-table tbody').text()).toContain('2K');expect(wrapper.get('.consumption-table tbody').text()).toContain('2张');
 expect(wrapper.get('[data-column="before"]').text()).toBe('1.00');expect(wrapper.get('[data-column="amount"]').text()).toBe('0.40');expect(wrapper.get('[data-column="after"]').text()).toBe('0.60');
});
it('renders a two-column detail with authorized final output, prompt and readonly historical facts',async()=>{
 const {wrapper}=await show('/admin/consumption/entry-real');expect(wrapper.find('.consumption-detail-grid').exists()).toBe(true);expect(wrapper.text()).toContain('基本信息');expect(wrapper.text()).toContain('费用明细');expect(wrapper.text()).toContain('模型调用链');expect(wrapper.text()).toContain('历史生成');expect(wrapper.find('textarea').exists()).toBe(false);
 expect(api.access.mock.calls.every(([record,file])=>record==='entry-real'&&['output-real','reference-real'].includes(file))).toBe(true);
});

it('exports all filtered pages with four-decimal amounts and formula-safe cells, without secrets',async()=>{
 const records=Array.from({length:101},(_,i)=>({...item,ledger:{...ledger,id:'entry-'+i,amount4:'12.3456',rate4:'10.0000'},user:{...item.user,nickname:i===0?'=SUM(1,2)':'用户'+i}}));
 api.list.mockImplementation(async(q)=>({data:{items:records.slice(q.offset,q.offset+q.limit),total:101,offset:q.offset,limit:q.limit}}));
 api.detail.mockImplementation(async(id)=>({data:{...detail,item:records.find(r=>r.ledger.id===id)}}));
 let blob:Blob|undefined;const create=vi.fn((value:Blob)=>{blob=value;return 'blob:export';});
 const originalCreate=URL.createObjectURL,originalRevoke=URL.revokeObjectURL;URL.createObjectURL=create;URL.revokeObjectURL=vi.fn();const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});
 try{const {wrapper}=await show();await wrapper.get('input[aria-label="模型标识"]').setValue('pending-model');await wrapper.findAll('button').find(b=>b.text()==='导出数据')!.trigger('click');await flushPromises();expect(create).toHaveBeenCalledOnce();const content=await new Promise<string>((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result));reader.onerror=reject;reader.readAsText(blob!);});expect(content.split('\r\n')).toHaveLength(102);expect(content).toContain('"\'=SUM(1,2)"');expect(content).toContain('"12.3456"');expect(content).not.toContain('sk-platform');expect(content).not.toContain('https://storage');expect(api.list.mock.calls).toContainEqual([expect.objectContaining({offset:100,limit:100})]);expect(api.list.mock.calls.every(([q])=>!q.model)).toBe(true);}finally{URL.createObjectURL=originalCreate;URL.revokeObjectURL=originalRevoke;click.mockRestore();}
});

it('shows both scheduled steps with separate videos and minute processing times',async()=>{
 const steps=[{index:0,definitionId:10,width:2560,height:1440,status:'COMPLETE',durationMs:65000,fileId:'intermediate-file'},{index:1,definitionId:20,width:1920,height:1080,status:'COMPLETE',durationMs:72000,fileId:'final-file'}];
 api.detail.mockResolvedValue({data:{...detail,snapshot:{...detail.snapshot,task:{...detail.snapshot.task,type:'VIDEO',durationSeconds:5,count:1},generation,enhancement:{...enhanced,status:'COMPLETE',durationMs:81988,scheduleId:'108307',steps,outputs:[{index:0,fileId:'final-file'}]}}}});
 const {wrapper}=await show('/admin/consumption/entry-real');const card=wrapper.get('[aria-label="增强信息"]');
 expect(wrapper.text()).toContain('总耗时 1.57 分钟');expect(card.text()).toContain('1.37 分钟');
 expect(card.text()).toContain('2560 × 1440');expect(card.text()).toContain('1920 × 1080');expect(card.text()).toContain('1.08 分钟');expect(card.text()).toContain('1.20 分钟');
 const previews=card.findAllComponents(ConsumptionPreview);expect(previews.map((w:{props:(key:string)=>unknown})=>w.props('fileId'))).toEqual(['intermediate-file','final-file']);
 for(const [i,file] of ['intermediate-file','final-file'].entries()) {await previews[i]!.get('.consumption-file-actions button').trigger('click');await flushPromises();expect(api.access).toHaveBeenLastCalledWith('entry-real',file);expect(previews[i]!.get('video').attributes('src')).toBe('https://storage.example/real');}
 expect((await axe(wrapper.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
});

it('paginates all owner history with a stable cutoff and retries the requested page',async()=>{
 const records=Array.from({length:38},(_,i)=>({...item,ledger:{...ledger,id:'history-'+i},modelName:'历史模型 '+i}));
 api.list.mockImplementation(async(q)=>({data:{items:records.slice(q.offset,q.offset+q.limit),total:38}}));
 api.detail.mockImplementation(async(id)=>({data:{...detail,item:records.find(r=>r.ledger.id===id)||item}}));
 const {wrapper,router}=await show('/admin/consumption/entry-real');
 const panel=wrapper.get('.consumption-history');
 expect(panel.findAll('.consumption-history-list>article')).toHaveLength(10);
 expect(panel.text()).toContain('共 38 条');
 const cutoff=api.list.mock.calls[0][0].to;
 expect(api.list.mock.calls[0][0]).toMatchObject({userId:'owner',offset:0,limit:10});
 expect(panel.get('[aria-label="历史生成上一页"]').attributes('disabled')).toBeDefined();
 api.list.mockRejectedValueOnce(Error('offline'));
 await panel.get('[aria-label="历史生成下一页"]').trigger('click');await flushPromises();
 expect(panel.get('[role="alert"]').text()).toContain('历史记录读取失败');
 await panel.get('[role="alert"] button').trigger('click');await flushPromises();
 expect(api.list.mock.lastCall?.[0]).toEqual({userId:'owner',offset:10,limit:10,to:cutoff});
 expect(panel.findAll('.consumption-history-list>article')).toHaveLength(10);
 await panel.get('[aria-label="历史生成下一页"]').trigger('click');await flushPromises();
 await panel.get('[aria-label="历史生成下一页"]').trigger('click');await flushPromises();
 expect(api.list.mock.lastCall?.[0]).toMatchObject({offset:30,limit:10});
 expect(panel.findAll('.consumption-history-list>article')).toHaveLength(8);
 expect(panel.text()).toContain('历史模型 37');expect(panel.text()).not.toContain('历史模型 0');
 expect(panel.get('[aria-label="历史生成下一页"]').attributes('disabled')).toBeDefined();
 await panel.get('[aria-label="历史生成上一页"]').trigger('click');await flushPromises();
 expect(panel.findAll('.consumption-history-list>article')).toHaveLength(10);
 await router.push('/admin/consumption/history-30');await flushPromises();
 expect(api.list.mock.lastCall?.[0]).toMatchObject({userId:'owner',offset:0,limit:10});
});

it('opens all owner generations in the list and clears older list filters',async()=>{
 const {wrapper,router}=await show();
 await wrapper.get('[aria-label="模型标识"]').setValue('previous-model');
 await wrapper.get('[aria-label="任务状态"]').setValue('RUNNING');
 await wrapper.get('[aria-label="开始时间"]').setValue('2026-09-01');
 await wrapper.get('[aria-label="截止时间"]').setValue('2026-09-02');
 await wrapper.get('form').trigger('submit');await flushPromises();
 await router.push('/admin/consumption/entry-real');await flushPromises();
 await wrapper.get('.consumption-history header a').trigger('click');await flushPromises();
 expect(router.currentRoute.value.path).toBe('/admin/consumption');
 expect(router.currentRoute.value.query).toEqual({userId:'owner'});
 expect(wrapper.find('.consumption-record-detail').exists()).toBe(false);
 expect(wrapper.find('.consumption-table').exists()).toBe(true);
 expect(api.list.mock.lastCall?.[0]).toEqual({userId:'owner',to:expect.any(String),offset:0,limit:10});
 for(const label of ['模型标识','任务状态','开始时间','截止时间']) expect(wrapper.get(`[aria-label="${label}"]`).element).toHaveProperty('value','');
});

it('keeps thumbnail placeholders for failed and timed out history while retaining successful previews',async()=>{
 const records=['FAILED','TIMED_OUT','SUCCEEDED'].map(status=>({...item,taskStatus:status,ledger:{...ledger,id:'history-'+status,amount4:'0.1000'}}));
 api.list.mockResolvedValue({data:{items:records,total:3}});
 api.detail.mockImplementation(async id=>{const record=records.find(r=>r.ledger.id===id);return {data:record?{...detail,item:record,snapshot:{...detail.snapshot,task:{...detail.snapshot.task,status:record.taskStatus,results:record.taskStatus==='SUCCEEDED'?detail.snapshot.task.results:[]}}}:detail};});
 const {wrapper}=await show('/admin/consumption/entry-real');const cards=wrapper.findAll('.consumption-history-list>article');
 expect(cards.map(card=>card.find('.consumption-history-placeholder').exists())).toEqual([true,true,false]);
 expect(cards[0]!.get('.consumption-history-placeholder').text()).toBe('生成失败');
 expect(cards[1]!.get('.consumption-history-placeholder').text()).toBe('生成超时');
 expect(cards[2]!.findComponent(ConsumptionPreview).exists()).toBe(true);
 for(const [i,card] of cards.entries()){expect(card.get('a').attributes('href')).toBe('/admin/consumption/'+records[i]!.ledger.id);expect(card.text()).toContain('历史模型');expect(card.text()).toContain('0.1 元');}
 expect(api.access.mock.calls.some(([id])=>id==='history-FAILED'||id==='history-TIMED_OUT')).toBe(false);
});

it('previews references by their own media kind including legacy images',async()=>{
 const references=[{index:0,fileId:'clip',kind:'VIDEO'},{index:1,fileId:'sound',kind:'AUDIO'},{index:2,fileId:'picture',kind:'IMAGE'},{index:3,fileId:'legacy'}];
 api.detail.mockResolvedValue({data:{...detail,snapshot:{...detail.snapshot,references,task:{...detail.snapshot.task,type:'VIDEO'}}}});
 const {wrapper}=await show('/admin/consumption/entry-real');const panel=wrapper.get('.consumption-references');
 await panel.get('header button').trigger('click');await flushPromises();
 const previews:VueWrapper[]=panel.findAllComponents(ConsumptionPreview);
 expect(previews.map(p=>p.find('video').exists())).toEqual([true,false,false,false]);
 expect(panel.get('h2').text()).toBe('参考素材（4 项）');
 expect(previews.map(p=>p.find('audio').exists())).toEqual([false,true,false,false]);
 expect(previews.map(p=>p.find('img').exists())).toEqual([false,false,true,true]);
 expect(previews[1]!.get('audio').attributes('controls')).toBeDefined();
 expect(previews[1]!.find('button audio').exists()).toBe(false);
 for(const [i,kind] of ['视频','音频','图片','图片'].entries()) {
  const preview=previews[i]!;
  expect(preview.text()).toContain('打开'+kind);expect(preview.text()).toContain('下载'+kind);
  await preview.get('.consumption-file-actions button').trigger('click');await flushPromises();
  expect(api.access).toHaveBeenLastCalledWith('entry-real',references[i]!.fileId);
  expect(preview.get('dialog '+(['video','audio','img','img'][i])).attributes('src')).toBe('https://storage.example/real');
 }
 await previews[0]!.get('video').trigger('error');await flushPromises();
 expect(previews[0]!.text()).toContain('暂时无法读取');
 await previews[0]!.get('[role="alert"] button').trigger('click');await flushPromises();
 expect(previews[0]!.find('video').exists()).toBe(true);expect(previews[0]!.find('img').exists()).toBe(false);
});
