<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import adminBrand from './assets/admin-brand.png';
import loginEarth from './assets/api-login-earth.webp';
import { apiLoginError, loginApiWithPhone, sendApiLoginCode } from './apiPhoneLogin';

const router = useRouter();
const route = useRoute();
const phone = ref('');
const code = ref('');
const busy = ref<'send' | 'login' | null>(null);
const error = ref('');
const status = ref('');
const seconds = ref(0);
const codeInput = ref<HTMLInputElement | null>(null);
const validPhone = computed(() => /^1[3-9]\d{9}$/.test(phone.value.trim()));
let resendAt = 0;
let alive = true;
const timer = window.setInterval(() => { seconds.value = Math.max(0, Math.ceil((resendAt - Date.now()) / 1000)); }, 1000);
onUnmounted(() => { alive = false; window.clearInterval(timer); });

async function requestCode() {
  if (busy.value || seconds.value) return;
  error.value = ''; status.value = '';
  if (!validPhone.value) { error.value = '请输入正确的 11 位手机号'; return; }
  busy.value = 'send';
  try {
    await sendApiLoginCode(phone.value.trim());
    if (!alive) return;
    resendAt = Date.now() + 60_000; seconds.value = 60;
    status.value = '验证码已发送';
  } catch (failure) { if (alive) error.value = apiLoginError(failure); }
  finally { if (alive) { busy.value = null; await nextTick(); if (status.value) codeInput.value?.focus(); } }
}

async function submit() {
  if (busy.value) return;
  error.value = ''; status.value = '';
  if (!validPhone.value) { error.value = '请输入正确的 11 位手机号'; return; }
  if (!/^\d{6}$/.test(code.value)) { error.value = '请输入 6 位短信验证码'; codeInput.value?.focus(); return; }
  busy.value = 'login';
  try {
    await loginApiWithPhone(phone.value.trim(), code.value);
    code.value = '';
    if (!alive) return;
    const target = route.query.redirect;
    const resolved = typeof target === 'string' && /^\/api\//.test(target) && !/[\\\s]/.test(target)
      ? router.resolve(target) : null;
    const safe = resolved && resolved.matched.length && String(resolved.name).startsWith('api-')
      && !['api-login', 'api-portal-entry'].includes(String(resolved.name));
    await router.replace(safe ? resolved.fullPath : '/api/dashboard');
  } catch (failure) { if (alive) { error.value = apiLoginError(failure); code.value = ''; codeInput.value?.focus(); } }
  finally { if (alive) { busy.value = null; await nextTick(); if (error.value) codeInput.value?.focus(); } }
}
</script>

<template>
  <section class="api-login-page" aria-labelledby="api-login-title">
    <div class="api-login-shell">
      <div class="api-login-visual">
        <img class="api-login-earth" :src="loginEarth" alt="" fetchpriority="high" />
        <a class="api-login-mark" href="/home" aria-label="YOMIO 首页"><img :src="adminBrand" alt="YOMIO" width="160" height="56" /></a>
      </div>
      <div class="api-login-panel">
        <a class="api-login-close" href="/home" aria-label="关闭登录，返回首页"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="m5 5 14 14M19 5 5 19" stroke-linecap="round" /></svg></a>
        <form class="api-login-card" :aria-busy="!!busy" @submit.prevent="submit" novalidate>
          <header class="api-login-heading"><h1 id="api-login-title">API数据登录</h1></header>
          <div class="api-login-fields">
            <label class="sr-only" for="api-login-phone">手机号</label>
            <div class="api-login-field">
              <div class="api-login-country">
                <select aria-label="国家或地区区号" :disabled="!!busy"><option value="86">+86</option></select>
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m3 6 5 5 5-5" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </div>
              <input id="api-login-phone" v-model="phone" type="tel" inputmode="tel" autocomplete="tel-national" placeholder="请输入手机号" maxlength="11" :disabled="!!busy" required />
            </div>
            <label class="sr-only" for="api-login-code">短信验证码</label>
            <div class="api-login-field api-login-code-row">
              <span class="api-login-code-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="m12 2 8 4v7c0 4-4 7-8 9-4-2-8-5-8-9V6l8-4Z" stroke-linejoin="round"/><path d="m8 12 3 3 5-6" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
              <input id="api-login-code" ref="codeInput" v-model="code" inputmode="numeric" autocomplete="one-time-code" placeholder="请输入验证码" maxlength="6" :disabled="!!busy" required />
              <button type="button" class="api-login-send" :disabled="!!busy || seconds > 0" @click="requestCode">{{ busy === 'send' ? '发送中…' : seconds ? `${seconds} 秒后重发` : '获取验证码' }}</button>
            </div>
          </div>
          <div class="api-login-feedback" aria-live="polite">
            <p v-if="error" role="alert" class="api-login-error">{{ error }}</p>
            <p v-else-if="status" role="status">{{ status }}</p>
            <p v-else-if="route.query.logout === 'failed'" role="alert" class="api-login-error">退出未完成，请重新登录后重试退出。</p>
          </div>
          <button class="api-login-submit" type="submit" aria-label="登录 API 控制台" :disabled="!!busy"><span>{{ busy === 'login' ? '正在登录…' : '登录' }}</span><svg v-if="busy !== 'login'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="M3 12h18m-7-7 7 7-7 7" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
        </form>
      </div>
    </div>
  </section>
</template>

<style scoped>
@font-face { font-family: 'ApiLoginSans'; src: url("./assets/api-login-sans.woff2") format('woff2'); font-weight: 100 900; font-style: normal; font-display: swap; }
.api-login-page {
  --login-ink: #0e102a;
  --login-muted: #68748b;
  --login-accent: #0079ee;
  display: grid;
  place-items: center;
  min-height: 100svh;
  padding: 48px 32px;
  color-scheme: light;
  color: var(--login-ink);
  background: linear-gradient(120deg, #d4e6fc, #f2f8ff 78%);
  font-family: 'ApiLoginSans', Arial, 'Microsoft YaHei', sans-serif;
}
.api-login-page * { box-sizing: border-box; }
.api-login-page ::selection { color: #123d74; background: #c9e3ff; }
.api-login-page :focus-visible { outline: 3px solid var(--login-accent); outline-offset: 4px; }
.api-login-shell { position: relative; display: grid; grid-template-columns: 57.6% minmax(0, 1fr); width: min(1120px, 100%, calc((100svh - 96px) * 1.425)); aspect-ratio: 1.425; min-height: 520px; border-radius: 30px; overflow: hidden; background: #7cb9f2; box-shadow: 0 10px 28px #8bb9ef38, inset 0 0 0 2px #ffffffb3; }
.api-login-visual { position: relative; min-width: 0; }
.api-login-earth { position: absolute; inset: 0; width: calc(100% + 34px); height: 100%; object-fit: cover; object-position: center; }
.api-login-mark { position: absolute; top: 5.1%; left: 6.5%; display: block; width: clamp(110px, 9vw, 138px); border-radius: 4px; }
.api-login-mark img { display: block; width: 100%; height: auto; filter: brightness(0) invert(1); }
.api-login-panel { position: relative; display: grid; place-items: center; padding: 34px 12.5% 10px; border-radius: 34px; background: linear-gradient(155deg, #fff 15%, #f5f9ff 59%, #fffffffa); isolation: isolate; }
.api-login-panel::after { content: ''; position: absolute; z-index: -1; right: -17%; bottom: -13%; width: 82%; height: 36%; border: 2px solid #ffffffb8; border-radius: 50%; background: linear-gradient(150deg, #d9eaff75, #edf6ff00 70%); transform: rotate(-34deg); box-shadow: -48px -15px 45px #e8f2ff85; pointer-events: none; }
.api-login-close { position: absolute; top: 22px; right: 22px; display: grid; place-items: center; width: 44px; height: 44px; border: 1px solid #e6e9ee; border-radius: 50%; background: #ffffffba; color: #30374c; text-decoration: none; transition: background-color 150ms ease-out; }
.api-login-close svg { width: 25px; height: 25px; }
.api-login-close:hover { background: #edf5ff; }
.api-login-card { width: 100%; max-width: 360px; margin: 0; padding: 0; }
.api-login-heading { text-align: center; margin-bottom: 42px; }
.api-login-card h1 { margin: 0; color: var(--login-ink); font-size: clamp(30px, 2.56vw, 38px); line-height: 1.35; font-weight: 700; letter-spacing: -.025em; text-wrap: balance; }
.api-login-fields { display: grid; grid-template-columns: minmax(0, 1fr); gap: 20px; }
.api-login-field { display: flex; align-items: center; gap: 16px; min-height: 60px; padding: 0 16px; border: 1px solid #e0e8f2; border-radius: 15px; background: #fffffff2; box-shadow: 0 7px 18px #86b3ed15; transition: border-color 150ms ease-out, box-shadow 150ms ease-out; }
.api-login-field:focus-within { border-color: var(--login-accent); box-shadow: 0 0 0 3px #0079ee1a; }
.api-login-country { position: relative; flex: 0 0 76px; height: 34px; border-right: 1px solid #d9dce3; }
.api-login-country select { appearance: none; width: 100%; height: 100%; padding: 0 30px 0 0; border: 0; border-radius: 4px; background: transparent; color: var(--login-ink); font: inherit; font-size: 16px; cursor: pointer; }
.api-login-country svg { position: absolute; top: 10px; right: 15px; width: 16px; height: 16px; pointer-events: none; }
.api-login-field input { width: 100%; min-width: 0; height: 58px; padding: 0; border: 0; border-radius: 0; background: transparent; color: var(--login-ink); font: inherit; font-size: 16px; caret-color: var(--login-accent); box-shadow: none; }
.api-login-field input:focus-visible { outline: none; }
.api-login-field input::placeholder { color: var(--login-muted); opacity: 1; }
.api-login-field input:disabled { color: var(--login-muted); }
.api-login-code-row { gap: 14px; padding-right: 12px; }
.api-login-code-icon { display: flex; align-items: center; flex: 0 0 36px; height: 34px; border-right: 1px solid #d9dce3; }
.api-login-code-icon svg { width: 25px; height: 25px; }
.api-login-send { flex-shrink: 0; min-height: 44px; padding: 0 5px; border: 0; border-radius: 8px; background: transparent; color: #0073da; font: inherit; font-size: 15px; font-variant-numeric: tabular-nums; cursor: pointer; }
.api-login-send:hover:not(:disabled) { background: #e9f2ff; }
.api-login-send:disabled { color: var(--login-muted); opacity: 1; cursor: default; }
.api-login-submit { display: flex; align-items: center; justify-content: center; gap: 20px; width: 100%; min-height: 68px; padding: 14px 16px; border: 0; border-radius: 16px; background: linear-gradient(105deg, #2997f7, #007aff 84%); box-shadow: 0 14px 24px #1385ef36, inset 0 1px 0 #ffffff4d; color: #fff; font: inherit; font-weight: 500; font-size: 21px; cursor: pointer; transition: box-shadow 150ms ease-out, transform 150ms ease-out; }
.api-login-submit svg { width: 28px; height: 28px; flex-shrink: 0; transition: transform 150ms ease-out; }
.api-login-submit:enabled:hover { box-shadow: 0 17px 30px #1385ef4d; }
.api-login-submit:enabled:hover svg { transform: translateX(3px); }
.api-login-submit:enabled:active { transform: translateY(1px); }
.api-login-submit:disabled { background: #d8e5f5; color: #4e6381; opacity: 1; box-shadow: none; cursor: default; }
.api-login-feedback { min-height: 36px; padding: 7px 2px; font-size: 14px; line-height: 22px; color: #23548b; overflow-wrap: anywhere; }
.api-login-feedback p { margin: 0; }
.api-login-feedback .api-login-error { color: #a12d38; }
@media (max-width: 1100px) and (min-width: 761px) {
  .api-login-page { padding: 48px 24px; }
  .api-login-shell { grid-template-columns: 53% minmax(0, 1fr); }
  .api-login-panel { padding-inline: 9%; }
  .api-login-card h1 { font-size: 32px; }
  .api-login-field { gap: 14px; padding-inline: 16px; }
  .api-login-country { flex-basis: 76px; }
  .api-login-country svg { right: 15px; }
  .api-login-field input, .api-login-country select { font-size: 16px; }
  .api-login-code-icon { flex-basis: 36px; }
  .api-login-send { font-size: 15px; }
}
@media (max-width: 760px) {
  .api-login-page { padding: 16px; }
  .api-login-shell { grid-template-columns: minmax(0, 1fr); width: min(420px, 100%); aspect-ratio: auto; min-height: 0; border-radius: 26px; }
  .api-login-visual { min-height: 180px; }
  .api-login-earth { width: 100%; object-position: center 42%; }
  .api-login-mark { top: 26px; left: 26px; width: 118px; }
  .api-login-panel { min-height: 440px; margin-top: -26px; padding: 62px 28px 36px; border-radius: 26px; }
  .api-login-close { top: 18px; right: 18px; width: 40px; height: 40px; }
  .api-login-close svg { width: 20px; height: 20px; }
  .api-login-heading { margin-bottom: 28px; }
  .api-login-card h1 { font-size: 28px; }
  .api-login-fields { gap: 18px; }
  .api-login-field { min-height: 60px; gap: 14px; padding: 0 15px; border-radius: 14px; }
  .api-login-field input { height: 58px; font-size: 16px; }
  .api-login-country { flex-basis: 76px; }
  .api-login-country select { font-size: 16px; }
  .api-login-country svg { right: 15px; }
  .api-login-code-row { gap: 13px; padding-right: 10px; }
  .api-login-code-icon { flex-basis: 34px; }
  .api-login-code-icon svg { width: 22px; height: 22px; }
  .api-login-send { padding-inline: 2px; font-size: 15px; }
  .api-login-submit { min-height: 64px; font-size: 21px; border-radius: 15px; }
}
@media (max-width: 380px) {
  .api-login-panel { padding-inline: 20px; }
  .api-login-field { gap: 10px; padding-inline: 12px; }
  .api-login-code-row { gap: 10px; padding-right: 8px; }
  .api-login-code-icon { flex-basis: 29px; }
  .api-login-send { font-size: 14px; }
}
@media (prefers-reduced-motion: reduce) { .api-login-page button, .api-login-field, .api-login-submit svg, .api-login-close { transition: none; } }
</style>
