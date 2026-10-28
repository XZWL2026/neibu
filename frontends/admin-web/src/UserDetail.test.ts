import {mount,flushPromises,enableAutoUnmount} from '@vue/test-utils';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {ref} from 'vue';
import {axe} from 'vitest-axe';
import {adminSessionKey,type AdminIdentity} from './adminSession';
const api=vi.hoisted(()=>({detail:vi.fn(),rename:vi.fn(),options:vi.fn()}));
vi.mock('./api',async original=>({...await original<object>(),userDirectoryDetailGetApiV1AdminUsersIdDirectoryDetail:api.detail,userRenamePatchApiV1AdminUsersIdNickname:api.rename,paymentAdminOptionsGetApiV1AdminPaymentsRechargeOptions:api.options}));
import UserDetail from './UserDetail.vue';
const user={id:'user-a',nickname:'原用户名',phone:'+8613800000001',enabled:true,registeredAt:'2026-10-12T03:16:00Z'};
beforeEach(()=>{vi.resetAllMocks();api.detail.mockImplementation(async(id:string)=>({data:{user:{...user,id}}}));api.rename.mockImplementation(async(id:string,{nickname}:{nickname:string})=>({data:{...user,id,nickname}}));api.options.mockResolvedValue({data:{userId:'user-a',pointsPerYuan4:'10.0000'}});});
enableAutoUnmount(afterEach);
async function show(role:AdminIdentity['role']='ADMIN'){
 const currentAdmin=ref<AdminIdentity|null>({role,username:'admin',primary:role==='ADMIN'});
 const wrapper=mount(UserDetail,{props:{userId:'user-a'},attachTo:document.body,global:{stubs:{RouterLink:true},provide:{[adminSessionKey as symbol]:{currentAdmin}}}});
 await flushPromises();return {wrapper,currentAdmin};
}
async function edit(wrapper:Awaited<ReturnType<typeof show>>['wrapper']){await wrapper.get('.profile-edit').trigger('click');await flushPromises();return wrapper.get('input[name="nickname"]');}
it.each(['ADMIN','NORMAL_ADMIN'] as const)('lets %s trim and save a nickname and notify the directory',async role=>{
 const {wrapper}=await show(role);const input=await edit(wrapper);expect(document.activeElement).toBe(input.element);
 await input.setValue(' 新用户名 ');await wrapper.get('form').trigger('submit');await flushPromises();
 expect(api.rename).toHaveBeenCalledWith('user-a',{nickname:'新用户名'});expect(wrapper.get('h3').text()).toContain('新用户名');expect(wrapper.emitted('changed')).toHaveLength(1);expect(wrapper.text()).toContain('用户名已更新');expect(wrapper.find('form').exists()).toBe(false);
});
it('supports keyboard cancellation and unchanged input without sending a mutation',async()=>{
 const {wrapper}=await show();await (await edit(wrapper)).setValue('已放弃');await wrapper.get('form').trigger('keydown',{key:'Escape'});await flushPromises();expect(wrapper.get('h3').text()).toContain('原用户名');expect(document.activeElement).toBe(wrapper.get('.profile-edit').element);
 await edit(wrapper);await wrapper.get('form').trigger('submit');expect(api.rename).not.toHaveBeenCalled();
});
it.each(['   ','x'.repeat(65),'bad\u007fname'])('rejects invalid input before the request',async value=>{
 const {wrapper}=await show();await (await edit(wrapper)).setValue(value);await wrapper.get('form').trigger('submit');await flushPromises();expect(api.rename).not.toHaveBeenCalled();expect(wrapper.get('[role="alert"]').text()).toContain('1–64');
});
it('preserves draft and original name on failure and allows retry',async()=>{
 api.rename.mockRejectedValueOnce(new Error('offline'));const {wrapper}=await show();await (await edit(wrapper)).setValue('重试用户名');await wrapper.get('form').trigger('submit');await flushPromises();expect(wrapper.get('h3').text()).toContain('原用户名');expect((wrapper.get('input').element as HTMLInputElement).value).toBe('重试用户名');expect(wrapper.get('[role="alert"]').text()).toContain('输入已保留');
 await wrapper.get('form').trigger('submit');await flushPromises();expect(wrapper.get('h3').text()).toContain('重试用户名');
});
it('prevents duplicate saves and ignores a response after changing the viewed user',async()=>{
 let finish!:(result:unknown)=>void;api.rename.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));const {wrapper}=await show();await (await edit(wrapper)).setValue('迟到名称');await wrapper.get('form').trigger('submit');await wrapper.get('form').trigger('submit');expect(api.rename).toHaveBeenCalledTimes(1);expect(wrapper.get('input').attributes('disabled')).toBeDefined();
 await wrapper.setProps({userId:'user-b'});finish({data:{...user,nickname:'迟到名称'}});await flushPromises();expect(wrapper.emitted('changed')).toBeUndefined();expect(wrapper.text()).not.toContain('迟到名称');
});
it('ignores a late response after closing the detail',async()=>{
 let finish!:(result:unknown)=>void;api.rename.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));const {wrapper}=await show();await (await edit(wrapper)).setValue('迟到名称');await wrapper.get('form').trigger('submit');wrapper.unmount();finish({data:{...user,nickname:'迟到名称'}});await flushPromises();expect(wrapper.emitted('changed')).toBeUndefined();
});
it.each(['PROMOTER','API_USER'] as const)('does not expose editing to %s',async role=>{const {wrapper}=await show(role);expect(wrapper.find('.profile-edit').exists()).toBe(false);});
it('closes editing when the administrator session changes',async()=>{const {wrapper,currentAdmin}=await show();await edit(wrapper);currentAdmin.value=null;await flushPromises();expect(wrapper.find('form').exists()).toBe(false);expect(api.rename).not.toHaveBeenCalled();});
it('keeps the inline editor accessible',async()=>{const {wrapper}=await show();await edit(wrapper);expect((await axe(wrapper.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);});
