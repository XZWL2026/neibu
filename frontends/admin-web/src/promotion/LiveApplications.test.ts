import {mount,flushPromises,enableAutoUnmount} from '@vue/test-utils';
import {ref} from 'vue';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import Panel from './PromotionApplicationsPanel.vue';
import {adminSessionKey} from '../adminSession';
import * as api from '../api';
vi.mock('../api',async original=>({...await original<typeof import('../api')>(),promotionApplicationsGetApiV1AdminPromotionsApplications:vi.fn(),promotionReviewPostApiV1AdminPromotionsApplicationsApplicationIdReview:vi.fn(),userDetailGetApiV1AdminUsersId:vi.fn()}));
enableAutoUnmount(afterEach);
beforeEach(()=>{sessionStorage.clear();vi.clearAllMocks();});
it('reviews real applications and requires a rejection reason',async()=>{
 const applications=[{id:'real-app-1',userId:'real-user',status:'PENDING',accountType:'PERSONAL',reason:'申请代理',plannedChannel:'社群'},{id:'real-app-2',userId:'real-user',status:'PENDING',accountType:'PERSONAL',reason:'申请代理',plannedChannel:'社群'}];
 vi.mocked(api.promotionApplicationsGetApiV1AdminPromotionsApplications).mockImplementation(async()=>({data:{items:applications.map(a=>({...a})),total:2,page:0,size:20}} as any));
 vi.mocked(api.userDetailGetApiV1AdminUsersId).mockResolvedValue({data:{nickname:'真实用户',phone:'+8613800000000'}} as any);
 const review=vi.mocked(api.promotionReviewPostApiV1AdminPromotionsApplicationsApplicationIdReview).mockImplementation(async(id,body)=>{applications.find(a=>a.id===id)!.status=body.decision==='APPROVE'?'APPROVED':'REJECTED';return {data:{}} as any;});
 const wrapper=mount(Panel,{global:{provide:{[adminSessionKey as symbol]:{currentAdmin:ref({userId:'admin',username:'admin',role:'ADMIN',primary:true})}}}});await flushPromises();
 expect(wrapper.text()).toContain('真实用户');expect(wrapper.text()).not.toContain('+86');
 await wrapper.findAll('button').find(b=>b.text()==='通过')!.trigger('click');await flushPromises();expect(review).toHaveBeenCalledWith('real-app-1',expect.objectContaining({decision:'APPROVE'}));expect(wrapper.text()).toContain('已通过');
 await wrapper.findAll('button').find(b=>b.text()==='驳回')!.trigger('click');expect(wrapper.find('input[aria-label="real-app-2 拒绝原因"]').exists()).toBe(true);expect(review).toHaveBeenCalledTimes(1);
 await wrapper.get('input[aria-label="real-app-2 拒绝原因"]').setValue('补充渠道');await wrapper.findAll('button').find(b=>b.text()==='确认驳回')!.trigger('click');await flushPromises();expect(review).toHaveBeenLastCalledWith('real-app-2',expect.objectContaining({decision:'REJECT',rejectionReason:'补充渠道'}));expect(wrapper.text()).toContain('已驳回');
});

it('uses server totals for status cards and supports actual page size changes',async()=>{
 const list=vi.mocked(api.promotionApplicationsGetApiV1AdminPromotionsApplications).mockImplementation(async({page=0,size=20,status}={})=>({data:{items:[],total:status==='PENDING'?26:status==='APPROVED'?24:8,page,size}} as any));
 const wrapper=mount(Panel,{global:{provide:{[adminSessionKey as symbol]:{currentAdmin:ref({userId:'admin',role:'ADMIN',primary:true})}}}});await flushPromises();
 expect(wrapper.get('[aria-label="待审核申请"]').text()).toContain('26');expect(wrapper.get('[aria-label="已通过申请"]').text()).toContain('24');
 await wrapper.get('button[aria-label="下一页申请"]').trigger('click');await flushPromises();expect(list).toHaveBeenCalledWith({page:1,size:10,status:'PENDING'});
 await wrapper.get('[aria-label="已通过申请"]').trigger('click');await flushPromises();expect(list).toHaveBeenCalledWith({page:0,size:10,status:'APPROVED'});
 await wrapper.get('select[aria-label="每页申请条数"]').setValue('20');await flushPromises();expect(list).toHaveBeenCalledWith({page:0,size:20,status:'APPROVED'});
});
