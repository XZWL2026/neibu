import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {agentSessionBase} from './portalSession';
import {readLocal,writeLocal,clearAdminPageCache} from './localPersistence';
import {configureAdminGateway,iamListGetApiV1AdminAdmins} from './api';
const a='/agent/'+'a'.repeat(32),b='/agent/'+'b'.repeat(32);
beforeEach(()=>{window.history.replaceState(null,'','/admin/agents');localStorage.clear();sessionStorage.clear();});
afterEach(()=>{vi.unstubAllGlobals();window.history.replaceState(null,'','/admin/agents');});
it('uses only complete random agent namespaces',()=>{
 expect(agentSessionBase(a+'/promoter/overview')).toBe(a);
 for(const path of ['/admin/agents','/agent/admin/promoter/overview',a+'x/promoter/overview','/agent/%2e%2e/promoter/overview'])expect(agentSessionBase(path)).toBe('');
});
it('routes and authenticates within the new tab namespace',async()=>{
 window.history.replaceState(null,'',a+'/admin/portal-entry');
 const {adminRouter}=await import('./router');
 expect(adminRouter.options.history.base).toBe(a);expect(adminRouter.resolve('/promoter/overview').href).toBe(a+'/promoter/overview');
 const fetch=vi.fn(async(_path:string)=>Response.json([]));vi.stubGlobal('fetch',fetch);configureAdminGateway();await iamListGetApiV1AdminAdmins();
 expect(fetch.mock.calls[0]![0]).toBe(window.location.origin+a+'/api/v1/admin/admins');adminRouter.options.history.destroy();
});
it('keeps agent caches separate and clearing one never clears the administrator',()=>{
 localStorage.setItem('xzwl.admin.users','["admin"]');
 window.history.replaceState(null,'',a+'/promoter/overview');writeLocal('xzwl.promoter.view','A');
 window.history.replaceState(null,'',b+'/promoter/overview');expect(readLocal('xzwl.promoter.view','empty')).toBe('empty');writeLocal('xzwl.promoter.view','B');clearAdminPageCache();expect(readLocal('xzwl.promoter.view','empty')).toBe('empty');
 window.history.replaceState(null,'',a+'/promoter/overview');expect(readLocal('xzwl.promoter.view','empty')).toBe('A');clearAdminPageCache();expect(localStorage.getItem('xzwl.admin.users')).toBe('["admin"]');
});
it('waits for initial routing before checking an anonymous ticket entry',async()=>{
 const {createRouter,createMemoryHistory}=await import('vue-router');
 const {createAdminAuth}=await import('./adminAuth');
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/admin/portal-entry',name:'portal-entry',component:{template:'<div />'}},{path:'/admin/login',name:'admin-login',component:{template:'<div />'}}]});
 const fetch=vi.fn(async()=>Response.json({authenticated:false,csrfToken:'csrf',roles:[],user:null}));vi.stubGlobal('fetch',fetch);
 const auth=createAdminAuth(router);
 try {
  const verifying=auth.verify!();
  await router.push('/admin/portal-entry#'+'d'.repeat(64));await verifying;
  expect(router.currentRoute.value.name).toBe('portal-entry');expect(fetch).not.toHaveBeenCalled();
 } finally {auth.dispose!();}
});
