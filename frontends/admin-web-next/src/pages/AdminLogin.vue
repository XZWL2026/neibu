<script setup lang="ts">
import { inject, nextTick, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NButton, NInput, NForm, NFormItem, NAlert, type InputInst } from 'naive-ui';
import { ShieldCheck, UserRound, LockKeyhole, ArrowRight, Eye, EyeOff, Layers } from '@lucide/vue';
import { adminSessionKey } from '../adminSession';
const session = inject(adminSessionKey);
if (!session) throw new Error('AdminLogin requires an admin session');
const route = useRoute(), router = useRouter();
const username = ref(''), password = ref(''), loading = ref(false), error = ref(''), passwordVisible = ref(false);
const passwordInput = ref<InputInst>(), capsLock = ref(false);
function detectCapsLock(event: KeyboardEvent) { capsLock.value = event.getModifierState('CapsLock'); }
async function login() {
  if (loading.value) return;
  if (!username.value.trim() || !password.value) { error.value = '请输入账号和密码。'; return; }
  loading.value = true; error.value = '';
  try {
    const succeeded = await session!.authenticate(username.value.trim(), password.value);
    password.value = '';
    if (!succeeded) { error.value = '登录失败，请检查管理员账号、密码或服务状态。'; return; }
    await router.replace(session!.destination?.(route.query.redirect) ?? '/admin/overview');
  } catch { error.value = '登录未完成，请检查网络后重试。'; }
  finally { loading.value = false; passwordVisible.value = false; if (error.value) { await nextTick(); passwordInput.value?.focus(); } }
}
</script>
<template>
  <main class="login-screen" aria-labelledby="login-title">
    <section class="login-workspace">
      <aside class="login-context" aria-label="YOMIO 管理工作台">
        <div class="login-brand"><Layers :size="28" :stroke-width="1.6" aria-hidden="true"/><span class="brand-word">YOMIO<span class="brand-dot">.</span></span></div>
        <div class="login-intro">
          <h2>运营的每一步，<br>清晰有序。</h2>
          <p>在一个工作空间中，<br>管理用户、资金与模型服务。</p>
        </div>
        <ul class="login-capabilities" aria-label="管理工作台功能">
          <li><strong>用户与代理</strong><span>账户管理 · 推荐关系 · 推广收益</span></li>
          <li><strong>资金与账务</strong><span>支付订单 · 充值审核 · 消费明细</span></li>
          <li><strong>模型与服务</strong><span>基础设施 · 生成日志 · 模型定价</span></li>
        </ul>
        <p class="login-context-footer">YOMIO 管理工作台</p>
      </aside>
      <div class="login-form-area">
        <div class="login-panel">
          <header><h1 id="login-title">登录管理后台</h1><p class="login-description">欢迎回来，请使用管理员账号继续。</p></header>
          <NAlert v-if="route.query.logout === 'failed'" type="warning" class="login-alert">退出请求未完成，当前页面已锁定，请重新登录。</NAlert>
          <NForm @submit.prevent="login" :show-feedback="false" :aria-busy="loading">
            <NFormItem label="管理员账号" label-for="login-username">
              <NInput v-model:value="username" :disabled="loading" placeholder="请输入管理员账号" :input-props="{id:'login-username','aria-label':'用户名',autocomplete:'username',autocapitalize:'none',spellcheck:false,required:true,'aria-describedby':error?'login-error':undefined}" size="large" :theme-overrides="{heightLarge:'48px',fontSizeLarge:'14px',borderRadius:'8px'}"><template #prefix><UserRound :size="18" aria-hidden="true"/></template></NInput>
            </NFormItem>
            <NFormItem label="密码" label-for="login-password">
              <NInput ref="passwordInput" v-model:value="password" :disabled="loading" :type="passwordVisible ? 'text' : 'password'" placeholder="请输入密码" :input-props="{id:'login-password','aria-label':'密码',autocomplete:'current-password',required:true,'aria-describedby':[capsLock?'login-caps-lock':'',error?'login-error':''].filter(Boolean).join(' ')||undefined,onKeydown:detectCapsLock,onKeyup:detectCapsLock,onBlur:()=>capsLock=false}" size="large" :theme-overrides="{heightLarge:'48px',fontSizeLarge:'14px',borderRadius:'8px'}">
                <template #prefix><LockKeyhole :size="18" aria-hidden="true"/></template>
                <template #suffix><NButton class="login-password-toggle" attr-type="button" quaternary :disabled="loading" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :aria-pressed="passwordVisible" @click="passwordVisible=!passwordVisible"><EyeOff v-if="passwordVisible" :size="18" aria-hidden="true"/><Eye v-else :size="18" aria-hidden="true"/></NButton></template>
              </NInput>
            </NFormItem>
            <p v-if="capsLock" id="login-caps-lock" class="login-caps-lock" role="status">大写锁定已开启</p>
            <NAlert v-if="error" id="login-error" type="error" role="alert" class="login-alert login-error">{{ error }}</NAlert>
            <NButton class="login-submit" attr-type="submit" type="primary" size="large" block :loading="loading" :disabled="loading" aria-label="登录管理后台" :theme-overrides="{heightLarge:'48px',fontSizeLarge:'15px',fontWeight:'600',borderRadiusLarge:'8px'}"><span>{{ loading ? '正在登录…' : '登录管理后台' }}</span><ArrowRight v-if="!loading" :size="18" aria-hidden="true"/></NButton>
          </NForm>
          <p class="login-security"><ShieldCheck :size="16" aria-hidden="true"/>仅限授权管理员访问</p>
        </div>
      </div>
    </section>
    <footer class="login-footer">© {{ new Date().getFullYear() }} YOMIO · 用 AI 创造更大的可能</footer>
  </main>
</template>
<style scoped>
.login-screen { min-height: 100dvh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 28px; padding: 48px 32px 28px; box-sizing: border-box; background: var(--surface, #f6f7fa); color: var(--ink, #202d43); }
.login-workspace { display: grid; grid-template-columns: 400px minmax(0, 1fr); width: min(100%, 1024px); min-height: 620px; background: #fff; border-radius: 16px; overflow: hidden; box-shadow: 0 16px 64px #1822310d; }
.login-context { display: flex; flex-direction: column; padding: 36px 40px 28px; background: #182231; color: #fff; }
.login-brand { display: flex; align-items: center; gap: 12px; color: #c8c3ff; }
.login-brand .brand-word { color: #fff; font-size: 27px; font-weight: 700; letter-spacing: -.025em; }
.login-brand .brand-dot { color: #a9a1ff; }
.login-intro { margin: 60px 0 40px; }
.login-intro h2 { margin: 0 0 20px; font-size: 32px; line-height: 1.5; font-weight: 600; letter-spacing: -.025em; }
.login-intro p { margin: 0; color: #b8c4d7; font-size: 14px; line-height: 1.8; }
.login-capabilities { display: grid; gap: 16px; margin: 0; padding: 24px 0 0; list-style: none; border-top: 1px solid #344054; }
.login-capabilities li { display: grid; gap: 4px; }
.login-capabilities strong { color: #edf1f8; font-size: 13px; font-weight: 500; }
.login-capabilities span { color: #b8c4d7; font-size: 12px; line-height: 1.6; }
.login-context-footer { margin: auto 0 0; padding-top: 28px; color: #a8b5c9; font-size: 12px; }
.login-form-area { display: flex; align-items: center; justify-content: center; min-width: 0; padding: 56px 64px; }
.login-panel { width: 100%; max-width: 384px; }
.login-panel header { margin-bottom: 36px; }
h1 { margin: 0; font-size: 28px; font-weight: 650; line-height: 1.4; letter-spacing: -.025em; }
.login-description { color: #657187; font-size: 14px; line-height: 1.7; margin: 10px 0 0; }
.login-panel :deep(.n-form-item) { margin-bottom: 24px; }
.login-panel :deep(.n-form-item-label) { color: #465269; font-size: 14px; font-weight: 500; padding-bottom: 10px; }
.login-panel :deep(.n-input__prefix) { margin-right: 10px; color: #657187; }
.login-panel :deep(.n-input__input-el) { caret-color: #4f46e5; }
.login-password-toggle { width: 40px; height: 40px; margin-right: -6px; color: #657187; }
.login-submit :deep(.n-button__content) { gap: 12px; }
.login-alert { margin-bottom: 24px; line-height: 1.7; }
.login-error { margin-top: -4px; }
.login-caps-lock { color: #956014; font-size: 13px; margin: -12px 0 20px; }
.login-security { display: flex; align-items: center; justify-content: center; gap: 7px; margin: 24px 0 0; font-size: 12px; color: #657187; }
.login-footer { color: #657187; font-size: 12px; text-align: center; line-height: 1.6; }
@media (max-width: 980px) {
  .login-workspace { grid-template-columns: 320px minmax(0, 1fr); min-height: 600px; }
  .login-context { padding: 32px; }.login-intro { margin-top: 48px; }.login-intro h2 { font-size: 28px; }
  .login-form-area { padding: 40px 32px; }
}
@media (max-width: 720px) {
  .login-screen { padding: 32px 20px 24px; gap: 24px; }
  .login-workspace { grid-template-columns: minmax(0, 1fr); max-width: 440px; min-height: 0; }
  .login-context { padding: 24px; }
  .login-intro, .login-capabilities, .login-context-footer { display: none; }
  .login-brand .brand-word { font-size: 24px; }
  .login-form-area { padding: 36px 24px; }
  .login-panel header { margin-bottom: 28px; }h1 { font-size: 25px; }
  .login-description { font-size: 13px; }
}
@media (max-width: 360px) {
  .login-screen { padding-inline: 12px; }.login-form-area { padding-inline: 20px; }
  h1 { font-size: 24px; }
}
</style>
