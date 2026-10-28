import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {enableAutoUnmount,flushPromises,mount} from '@vue/test-utils';
import PortalEntry from './PortalEntry.vue';
const api=vi.hoisted(()=>({redeem:vi.fn(),clear:vi.fn(),cache:vi.fn(),replace:vi.fn()}));
vi.mock('./api',()=>({iamRedeemPostApiV1AuthPortalLogin:api.redeem,clearSession:api.clear}));
vi.mock('./localPersistence',()=>({clearAdminPageCache:api.cache}));
vi.mock('vue-router',()=>({useRouter:()=>({replace:api.replace})}));
enableAutoUnmount(afterEach);
beforeEach(()=>{vi.resetAllMocks();sessionStorage.clear();window.history.replaceState(null,'','/admin/portal-entry#'+'d'.repeat(64));sessionStorage.setItem('xzwl.portal.verifier','a'.repeat(64));});
it.each([['API_USER','/api/dashboard'],['PROMOTER','/promoter/overview']])('exchanges once, clears secrets and opens %s',async(role,target)=>{
 api.redeem.mockResolvedValue({data:{role}});mount(PortalEntry);await flushPromises();
 expect(api.redeem).toHaveBeenCalledWith({ticket:'d'.repeat(64),verifier:'a'.repeat(64)});expect(window.location.hash).toBe('');expect(sessionStorage.getItem('xzwl.portal.verifier')).toBeNull();expect(api.clear).toHaveBeenCalledTimes(1);expect(api.cache).toHaveBeenCalledTimes(1);expect(api.replace).toHaveBeenCalledWith(target);
});
it('rejects a copied ticket without the issuing tab secret',async()=>{
 sessionStorage.clear();const view=mount(PortalEntry);await flushPromises();expect(api.redeem).not.toHaveBeenCalled();expect(api.replace).not.toHaveBeenCalled();expect(view.get('a').attributes('href')).toBe('/home');expect(window.location.hash).toBe('');
});
it('never routes to administrative content after rejection or an unexpected role',async()=>{
 api.redeem.mockResolvedValue({data:{role:'ADMIN'}});const view=mount(PortalEntry);await flushPromises();expect(api.replace).not.toHaveBeenCalled();expect(view.get('[role=alert]').text()).toContain('登录失败');expect(sessionStorage.getItem('xzwl.portal.verifier')).toBeNull();
});
it.each([['PROMOTER','/promoter/overview'],['API_USER','/api/dashboard']])('redeems an isolated %s tab without changing its namespace',async(role,target)=>{
 const base='/agent/'+'a'.repeat(32);window.history.replaceState(null,'',base+'/admin/portal-entry#'+'d'.repeat(64));
 api.redeem.mockResolvedValue({data:{role}});mount(PortalEntry);await flushPromises();expect(window.location.pathname).toBe(base+'/admin/portal-entry');expect(api.replace).toHaveBeenCalledWith(target);
});
it('isolated entry rejects administrative roles',async()=>{
 window.history.replaceState(null,'','/agent/'+'a'.repeat(32)+'/admin/portal-entry#'+'d'.repeat(64));
 api.redeem.mockResolvedValue({data:{role:'ADMIN'}});const view=mount(PortalEntry);await flushPromises();expect(api.replace).not.toHaveBeenCalled();expect(view.get('a').attributes('href')).toBe('/admin/agents');
});
