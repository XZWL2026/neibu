import {mount,flushPromises,enableAutoUnmount} from '@vue/test-utils';
import {ref} from 'vue';
import {createRouter,createMemoryHistory} from 'vue-router';
import {it,expect,vi,afterEach,beforeEach} from 'vitest';
import App from '../App.vue';
import {adminRoutes} from '../router';
enableAutoUnmount(afterEach);afterEach(()=>vi.unstubAllGlobals());
beforeEach(()=>vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({items:[],total:0,page:0,size:10}),{headers:{'Content-Type':'application/json'}}))));
async function show(path:string,primary=true){const router=createRouter({history:createMemoryHistory(),routes:adminRoutes});await router.push(path);await router.isReady();const wrapper=mount(App,{props:{session:{authenticate:()=>true,currentAdmin:ref({role:primary?'ADMIN' as const:'NORMAL_ADMIN' as const,primary,username:'admin',userId:'admin'})}},global:{plugins:[router]}});await flushPromises();return wrapper;}
it.each(['applications','attributions'])('combines real panels on the existing %s route',async path=>{const w=await show(`/admin/promotions/${path}`);expect(w.get('h1').text()).toBe('推荐关系管理');expect(w.findAll('h2').map(n=>n.text())).toEqual(expect.arrayContaining(['代理申请审核','推荐关系调整']));expect(w.findAll('a').filter(n=>n.text()==='推荐关系管理')).toHaveLength(1);expect(w.findAll('a').some(n=>n.text()==='推广申请')).toBe(false);});
it('keeps the combined management page primary-admin only',async()=>{const w=await show('/admin/promotions/attributions',false);expect(w.text()).toContain('无权限');expect(w.find('.referral-applications').exists()).toBe(false);expect(w.find('.referral-adjustment').exists()).toBe(false);expect(fetch).not.toHaveBeenCalled();});
