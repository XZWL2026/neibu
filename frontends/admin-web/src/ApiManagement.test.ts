import {mount,flushPromises,enableAutoUnmount} from '@vue/test-utils';
import {it,expect,vi,beforeEach,afterEach} from 'vitest';
import {createRouter,createMemoryHistory} from 'vue-router';
import {ref} from 'vue';
import {axe} from 'vitest-axe';
import {adminSessionKey} from './adminSession';
const api=vi.hoisted(()=>({directory:vi.fn(),secret:vi.fn(),status:vi.fn()}));
vi.mock('./api',async original=>({...await original<object>(),userApiDirectoryGetApiV1AdminApiUsersDirectory:api.directory,accessAdminSecretGetApiV1AdminAccessKeysIdSecret:api.secret,userStatusPatchApiV1AdminUsersIdStatus:api.status}));
import ApiManagement from './ApiManagement.vue';
const user={id:'client-a',nickname:'星辰工作室',phone:'+8613800000001',enabled:true,accountType:'API',keyId:'key-a',maskedKey:'sk-****a123',keyStatus:'ENABLED',availableAmount4:'2560.1234'};
const page={items:[{user,todayCalls:286,todaySeconds:1820,todayAmount4:'326.8012',lastCallAt:'2026-09-19T08:00:00Z'}],total:428,summary:{totalApiUsers:428,todayCalls:12860,todaySeconds:86200,todayAmount4:'3286.4012'}};
beforeEach(()=>{vi.clearAllMocks();api.directory.mockResolvedValue({data:page});api.status.mockResolvedValue({data:{}});});enableAutoUnmount(afterEach);
async function show(primary=true,path='/admin/api-users'){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/api-users',component:ApiManagement},{path:'/admin/api-users/:userRef',component:ApiManagement}]});await router.push(path);await router.isReady();const wrapper=mount(ApiManagement,{attachTo:document.body,global:{plugins:[router],stubs:{ApiClientDetail:{template:'<div>客户详情内容</div>'},ApiClientCreate:true},provide:{[adminSessionKey as symbol]:{currentAdmin:ref({role:primary?'ADMIN':'NORMAL_ADMIN',primary,userId:'admin'})}}}});await flushPromises();return {wrapper,router};}
it('shows real all-page API metrics, exact amount display, and server filters without eager key disclosure',async()=>{const {wrapper}=await show();expect(wrapper.get('[data-stat="totalApiUsers"]').text()).toContain('428');expect(wrapper.get('[data-stat="todayCalls"]').text()).toContain('12,860');expect(wrapper.get('[data-stat="todayAmount4"]').text()).toContain('3,286.40');expect(wrapper.get('tbody').text()).toContain('2,560.12');expect(api.secret).not.toHaveBeenCalled();await wrapper.get('[aria-label="状态筛选"]').setValue('false');await wrapper.get('.api-filters').trigger('submit');await flushPromises();expect(api.directory).toHaveBeenLastCalledWith(expect.objectContaining({enabled:false,offset:0,limit:10}));await wrapper.get('[aria-label="下一页"]').trigger('click');await flushPromises();expect(api.directory).toHaveBeenLastCalledWith(expect.objectContaining({offset:10}));});
it('opens view as a deep-linkable right drawer and restores the listing on close',async()=>{const {wrapper,router}=await show();await wrapper.get('[aria-label="查看 API 客户 星辰工作室"]').trigger('click');await flushPromises();expect(router.currentRoute.value.path).toBe('/admin/api-users/client-a');expect(wrapper.get('.api-detail-drawer').attributes('open')).toBeDefined();expect(wrapper.get('tbody').text()).toContain('星辰工作室');await wrapper.get('[aria-label="关闭 API 客户详情"]').trigger('click');await flushPromises();expect(router.currentRoute.value.path).toBe('/admin/api-users');expect(wrapper.get('.api-detail-drawer').attributes('open')).toBeUndefined();});
it('blocks full-secret GET search and stale results; normal admin has no create/key-copy control',async()=>{const {wrapper}=await show(false);expect(wrapper.find('.new-user').exists()).toBe(false);expect(wrapper.find('[aria-label="复制星辰工作室的 Key"]').exists()).toBe(false);await wrapper.get('[aria-label="搜索 API 客户"]').setValue('sk-secret-full');await wrapper.get('.api-filters').trigger('submit');await flushPromises();expect(api.directory).toHaveBeenCalledTimes(1);expect(wrapper.get('[role="alert"]').text()).toContain('脱敏');});
it('is accessible and can open the detail directly',async()=>{const {wrapper}=await show(true,'/admin/api-users/client-a');expect(wrapper.get('.api-detail-drawer').attributes('open')).toBeDefined();expect((await axe(wrapper.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);});
it('uses real monthly customer and daily usage baselines with exact percentage rounding',async()=>{
  api.directory.mockResolvedValue({data:{...page,summary:{totalApiUsers:428,previousMonthTotalApiUsers:400,todayCalls:118,yesterdayCalls:100,todaySeconds:88,yesterdaySeconds:100,todayAmount4:'106000000000000000000.0000',yesterdayAmount4:'100000000000000000000.0000'}}});
  const {wrapper}=await show();
  expect(wrapper.get('[data-stat="totalApiUsers"] .stat-change').text()).toBe('↑ 7%较上月');
  expect(wrapper.get('[data-stat="todayCalls"] .stat-change').text()).toBe('↑ 18%较昨日');
  expect(wrapper.get('[data-stat="todaySeconds"] .stat-change').text()).toBe('↓ 12%较昨日');
  expect(wrapper.get('[data-stat="todaySeconds"] .stat-change').classes()).toContain('down');
  expect(wrapper.get('[data-stat="todayAmount4"] .stat-change').text()).toBe('↑ 6%较昨日');
});
it('keeps missing and zero baselines honest and rounds from four decimal amounts',async()=>{
  api.directory.mockResolvedValue({data:{...page,summary:{totalApiUsers:5,previousMonthTotalApiUsers:0,todayCalls:0,yesterdayCalls:0,todaySeconds:0,yesterdaySeconds:null,todayAmount4:'0.9012',yesterdayAmount4:'1.0000'}}});
  const {wrapper}=await show();
  expect(wrapper.get('[data-stat="totalApiUsers"] .stat-change').text()).toBe('—较上月');
  expect(wrapper.get('[data-stat="todayCalls"] .stat-change').text()).toBe('↑ 0%较昨日');
  expect(wrapper.get('[data-stat="todaySeconds"] .stat-change').text()).toBe('—较昨日');
  expect(wrapper.get('[data-stat="todayAmount4"] .stat-change').text()).toBe('↓ 9.88%较昨日');
  api.directory.mockRejectedValueOnce(Error('offline'));await wrapper.get('.api-filters').trigger('submit');await flushPromises();
  expect(wrapper.findAll('.stat-change').every(card=>card.text().startsWith('—'))).toBe(true);
});
