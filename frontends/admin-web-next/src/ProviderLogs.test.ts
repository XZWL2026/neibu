import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {enableAutoUnmount,flushPromises,mount} from '@vue/test-utils';
import {ref} from 'vue';
import {createMemoryHistory,createRouter} from 'vue-router';
import {axe} from 'vitest-axe';
import ProviderLogs from './ProviderLogs.vue';
import {adminSessionKey,canAccessAdminPath} from './adminSession';

const api=vi.hoisted(()=>({tasks:vi.fn(),list:vi.fn(),detail:vi.fn()}));
vi.mock('./api',()=>({mediaTasksGetApiV1AdminMediaProviderLogsTasks:api.tasks,mediaListGetApiV1AdminMediaProviderLogs:api.list,mediaDetailGetApiV1AdminMediaProviderLogsId:api.detail}));
const row={id:'log-one',taskId:'task-one',phase:'ARK_SENDING',createdAt:1791777600000,event:'response_received',model:'seedance-2',httpStatus:400,elapsedMs:250,requestId:'upstream-one',errorCode:'invalid_request',message:'reference image format unsupported'};
const evidence={requestedResolution:'720p',requestedDurationSeconds:15,requestedAspectRatio:'16:9',referenceCount:6,promptCharacters:4116,promptUtf8Bytes:12348,request:{model:'seedance-2',resolution:'480p',duration:15,ratio:'16:9'},response:{error:{code:'invalid_request',message:'reference image format unsupported',param:'content[6]'}},endpoint:'https://provider.example/tasks'};
const record={entry:row,detailJson:JSON.stringify(evidence)};
const start={...row,id:'log-start',event:'request_started',httpStatus:null,errorCode:null,message:null};
const group={taskId:row.taskId,recordCount:2,latest:row};
enableAutoUnmount(afterEach);
beforeEach(()=>{vi.resetAllMocks();api.tasks.mockResolvedValue({data:{items:[group],total:45,offset:0,limit:10}});api.list.mockResolvedValue({data:{items:[row,start],total:2,offset:0,limit:10}});api.detail.mockResolvedValue({data:record});});
async function show(path='/admin/provider-logs',role='ADMIN',primary=true){
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/provider-logs',component:ProviderLogs}]});await router.push(path);await router.isReady();
 const currentAdmin=ref({username:'admin',role,primary});
 const wrapper=mount(ProviderLogs,{global:{plugins:[router],provide:{[adminSessionKey as symbol]:{currentAdmin}}}});await flushPromises();return {wrapper,router,currentAdmin};
}
it('loads a task deep link and shows the actual request alongside the requested parameters',async()=>{
 const {wrapper}=await show('/admin/provider-logs?taskId=task-one');
 expect(api.tasks.mock.calls[0][0]).toMatchObject({taskId:'task-one',from:0,offset:0,limit:10});
 expect(api.list.mock.calls[0][0]).toMatchObject({taskId:'task-one',from:0,offset:0,limit:10});
 expect(wrapper.text()).toContain('reference image format unsupported');expect(wrapper.text()).toContain('upstream-one');
 expect(wrapper.text()).toContain('720p');expect(wrapper.text()).toContain('480p');expect(wrapper.text()).toContain('4116');
 expect(wrapper.get('[aria-label="脱敏上游响应"]').text()).toContain('content[6]');
 expect((await axe(wrapper.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);
});
it.each([['NORMAL_ADMIN',false],['ADMIN',false],['PROMOTER',false],['API_USER',false]])('does not fetch evidence for %s without primary access',async(role,primary)=>{
 const {wrapper}=await show('/admin/provider-logs',role,primary);expect(wrapper.text()).toContain('无权限');expect(api.tasks).not.toHaveBeenCalled();expect(api.list).not.toHaveBeenCalled();expect(api.detail).not.toHaveBeenCalled();
 expect(canAccessAdminPath('/admin/provider-logs',{username:'other',role:role as 'ADMIN',primary})).toBe(false);
});
it('keeps a stable query cutoff while paging and clears detail when the query changes',async()=>{
 const {wrapper,router}=await show();const cutoff=api.tasks.mock.calls[0][0].to;
 await wrapper.get('[aria-label="下一页日志"]').trigger('click');await flushPromises();
 expect(api.tasks.mock.lastCall?.[0]).toMatchObject({offset:10,to:cutoff});
 api.tasks.mockResolvedValue({data:{items:[],total:0}});await wrapper.get('input').setValue('another-task');await wrapper.get('form').trigger('submit');await flushPromises();
 expect(api.tasks.mock.lastCall?.[0]).toMatchObject({taskId:'another-task',offset:0});expect(router.currentRoute.value.query.taskId).toBe('another-task');
 expect(wrapper.text()).toContain('暂无匹配的日志');expect(wrapper.text()).not.toContain('upstream-one');
});
it('discards a delayed detail response after changing tasks or losing permission',async()=>{
 let resolve!:(value:unknown)=>void;api.detail.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
 const {wrapper,router,currentAdmin}=await show();api.tasks.mockResolvedValue({data:{items:[],total:0}});
 await router.push('/admin/provider-logs?taskId=another');await flushPromises();resolve({data:record});await flushPromises();expect(wrapper.text()).not.toContain('upstream-one');
 currentAdmin.value={username:'other',role:'NORMAL_ADMIN',primary:false};await flushPromises();expect(wrapper.text()).toContain('无权限');expect(wrapper.find('[aria-label="日志详情"]').exists()).toBe(false);
});
it('reports list and detail errors with explicit retry controls and no stale evidence',async()=>{
 api.tasks.mockRejectedValueOnce(Error('offline'));const {wrapper}=await show();expect(wrapper.get('[role="alert"]').text()).toContain('读取失败');
 api.detail.mockRejectedValueOnce(Error('gone'));await wrapper.get('[role="alert"] button').trigger('click');await flushPromises();expect(wrapper.text()).toContain('记录可能已清理');
 await wrapper.get('[aria-label="日志详情"] [role="alert"] button').trigger('click');await flushPromises();expect(wrapper.text()).toContain('upstream-one');
});
it('shows one task row with its separate submission and response records',async()=>{
 const {wrapper}=await show();expect(wrapper.findAll('.provider-log-list .provider-log-row')).toHaveLength(1);
 expect(wrapper.get('.provider-log-list').text()).toContain('匹配 2 条记录');
 expect(wrapper.findAll('.provider-log-timeline li')).toHaveLength(2);
 api.detail.mockResolvedValueOnce({data:{entry:start,detailJson:JSON.stringify({...evidence,response:undefined})}});
 await wrapper.findAll('.provider-log-timeline li button')[1].trigger('click');await flushPromises();
 expect(api.detail.mock.lastCall?.[0]).toBe('log-start');expect(wrapper.text()).toContain('请求参数已记录，响应将在另一条日志中显示');
});
it('pages all records within the task without inheriting list phase or time filters',async()=>{
 api.list.mockResolvedValue({data:{items:[row],total:41}});
 const {wrapper}=await show('/admin/provider-logs?phase=ARK_SENDING&period=24');
 const cutoff=api.tasks.mock.calls[0][0].to;
 expect(api.tasks.mock.calls[0][0].phase).toBe('ARK_SENDING');
 expect(api.list.mock.calls[0][0]).toEqual({taskId:'task-one',from:0,to:cutoff,offset:0,limit:10});
 await wrapper.get('[aria-label="下一页任务记录"]').trigger('click');await flushPromises();
 expect(api.list.mock.lastCall?.[0]).toEqual({taskId:'task-one',from:0,to:cutoff,offset:10,limit:10});
 expect(api.tasks).toHaveBeenCalledTimes(1);
});
it('discards old task records after selecting another task and after losing access',async()=>{
 let resolve!:(value:unknown)=>void;api.list.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
 const other={...row,taskId:'task-two',id:'other-log',requestId:'other-request'};
 api.tasks.mockResolvedValue({data:{items:[group,{taskId:'task-two',recordCount:1,latest:other}],total:2}});
 const {wrapper,currentAdmin}=await show();api.list.mockResolvedValueOnce({data:{items:[other],total:1}});api.detail.mockResolvedValueOnce({data:{entry:other,detailJson:JSON.stringify(evidence)}});
 await wrapper.findAll('.provider-log-list .provider-log-row')[1].trigger('click');await flushPromises();
 resolve({data:{items:[row,start],total:2}});await flushPromises();
 expect(wrapper.get('.provider-log-timeline').text()).toContain('task-two');expect(wrapper.get('.provider-log-timeline').text()).not.toContain('task-one');
 expect(wrapper.text()).not.toContain('upstream-one');
 api.list.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));await wrapper.findAll('.provider-log-list .provider-log-row')[0].trigger('click');
 currentAdmin.value={username:'other',role:'ADMIN',primary:false};await flushPromises();resolve({data:{items:[row],total:1}});await flushPromises();
 expect(wrapper.text()).toContain('无权限');expect(wrapper.find('.provider-log-timeline').exists()).toBe(false);
});
it('retries a failed task record query independently',async()=>{
 api.list.mockRejectedValueOnce(Error('offline'));const {wrapper}=await show();expect(wrapper.text()).toContain('任务记录读取失败');expect(api.detail).not.toHaveBeenCalled();
 await wrapper.get('.provider-log-timeline [role="alert"] button').trigger('click');await flushPromises();expect(wrapper.text()).toContain('upstream-one');expect(api.tasks).toHaveBeenCalledTimes(1);
});
it('copies the displayed sanitized record and handles clipboard denial',async()=>{
 const write=vi.fn().mockResolvedValue(undefined);Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:write}});
 const {wrapper}=await show();await wrapper.get('[aria-label="日志详情"] header button').trigger('click');await flushPromises();
 expect(JSON.parse(write.mock.calls[0][0])).toEqual(evidence);expect(wrapper.text()).toContain('完整日志已复制');
 write.mockRejectedValueOnce(Error('denied'));await wrapper.get('[aria-label="复制任务 ID"]').trigger('click');await flushPromises();expect(wrapper.text()).toContain('复制失败');
});
