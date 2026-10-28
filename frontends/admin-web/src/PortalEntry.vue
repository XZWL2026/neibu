<script setup lang="ts">
import {agentSessionBase, isApiConsoleSession} from './portalSession';
import {onMounted,ref} from 'vue';
import {useRouter} from 'vue-router';
import {clearSession,iamRedeemPostApiV1AuthPortalLogin} from './api';
import {clearAdminPageCache} from './localPersistence';
const router=useRouter();
const failed=ref(false);
const agentTab=!!agentSessionBase();
onMounted(async()=>{
  const ticket=window.location.hash.slice(1);
  window.history.replaceState(window.history.state,'',window.location.pathname);
  try {
    const verifier=sessionStorage.getItem('xzwl.portal.verifier');
    sessionStorage.removeItem('xzwl.portal.verifier');
    if(!/^[0-9a-f]{64}$/.test(ticket) || !verifier || !/^[0-9a-f]{64}$/.test(verifier)) throw new Error('Invalid handoff');
    const {data}=await iamRedeemPostApiV1AuthPortalLogin({ticket,verifier});
    if(isApiConsoleSession() && data.role!=='API_USER') throw new Error('Invalid API role');
    if(agentTab && !['PROMOTER','API_USER'].includes(data.role??'')) throw new Error('Invalid agent role');
    const target=data.role==='API_USER'?'/api/dashboard':data.role==='PROMOTER'?'/promoter/overview':null;
    if(!target) throw new Error('Invalid portal role');
    clearAdminPageCache();clearSession();
    // The destination guard verifies the freshly rotated backend session.
    await router.replace(target);
  } catch { failed.value=true; }
});
</script>
<template><section class="auth-card" :aria-busy="!failed"><p v-if="failed" role="alert">{{agentTab?'登录失败，请返回账号管理重试':'登录失败，请返回用户端重试'}}</p><a v-if="failed" class="btn-secondary" :href="agentTab?'/admin/agents':'/home'">{{agentTab?'返回账号管理':'返回用户端'}}</a></section></template>
