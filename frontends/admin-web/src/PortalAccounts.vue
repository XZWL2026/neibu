<script setup lang="ts">
import TableLoadingRows from './components/TableLoadingRows.vue';
import TableUpdating from './components/TableUpdating.vue';
import { computed, inject, onUnmounted, ref, watch } from 'vue';
import { adminSessionKey } from './adminSession';
import { ApiError, iamListGetApiV1AdminAdmins, iamCreatePostApiV1AdminAdmins,
  iamResetPostApiV1AdminAdminsIdPassword, iamStatusPatchApiV1AdminAdminsIdStatus } from './api';
const session = inject(adminSessionKey);
const permitted = computed(() => session?.currentAdmin.value?.role === 'ADMIN' && session.currentAdmin.value.primary);
type Account = NonNullable<Awaited<ReturnType<typeof iamListGetApiV1AdminAdmins>>['data']>[number];
const accounts = ref<Account[]>([]);
const username = ref(''); const password = ref(''); const role = ref('API_USER'); const boundUserId = ref('');
const resetTarget = ref<Account>(); const resetPassword = ref('');
const loading = ref(false); const busy = ref(false); const error = ref(''); const notice = ref('');
const loadFailed = ref(false);
let revision = 0;
const portal = computed(() => ['API_USER', 'PROMOTER'].includes(role.value));
async function load() {
  const version = ++revision;
  if (!permitted.value) return;
  loading.value = true; error.value = ''; loadFailed.value = false;
  try { const result = await iamListGetApiV1AdminAdmins(); if (version === revision) accounts.value = (result.data ?? []).filter(account => account.role !== 'API_USER'); }
  catch { if (version === revision) { accounts.value = []; loadFailed.value = true; error.value = '账号加载失败，请重试。'; } }
  finally { if (version === revision) loading.value = false; }
}
async function create() {
  if (!permitted.value || busy.value) return;
  busy.value = true; error.value = ''; notice.value = ''; loadFailed.value = false;
  const requestedRole = role.value;
  try {
    await iamCreatePostApiV1AdminAdmins({ username: username.value.trim(), password: password.value,
      role: requestedRole, boundUserId: portal.value ? boundUserId.value.trim() : null });
    username.value = ''; password.value = ''; boundUserId.value = '';
    notice.value = '登录账号已创建，可使用设置的用户名和密码登录后台。'; await load();
  } catch (cause) {
    const messages: Record<string, string> = {
      INVALID_PORTAL_BINDING: requestedRole === 'PROMOTER'
        ? '绑定失败：请使用已启用的真实业务用户，并确认已开通且启用推广资格。'
        : '绑定失败：请使用已启用的真实业务用户，并确认已开通 API 身份。',
      USERNAME_EXISTS: '用户名已存在，请更换用户名。',
      PORTAL_ACCOUNT_EXISTS: '该业务用户已有此角色的登录账号，请在列表中管理已有账号。',
      INVALID_ADMIN_INPUT: '用户名须为 1–64 位中文、字母、数字或 ._@-；密码至少 8 个字符，且不超过 72 字节。',
      USER_DIRECTORY_UNAVAILABLE: '暂时无法核验业务用户或角色资格，请稍后重新提交。',
      PORTAL_DIRECTORY_UNAVAILABLE: '暂时无法核验业务用户或角色资格，请稍后重新提交。',
    };
    error.value = (cause instanceof ApiError && messages[cause.code ?? '']) || '创建失败，请稍后重新提交。';
  }
  finally { busy.value = false; }
}
async function reset() {
  if (!permitted.value || busy.value || !resetTarget.value?.id) return;
  busy.value = true; error.value = ''; notice.value = ''; loadFailed.value = false;
  try { await iamResetPostApiV1AdminAdminsIdPassword(resetTarget.value.id, { password: resetPassword.value });
    resetPassword.value = ''; resetTarget.value = undefined; notice.value = '密码已重置，该账号需要重新登录。'; }
  catch { error.value = '重置失败，请检查密码或服务状态后重试。'; }
  finally { busy.value = false; }
}
async function toggle(account: Account) {
  if (!permitted.value || busy.value || !account.id) return;
  busy.value = true; error.value = ''; notice.value = ''; loadFailed.value = false;
  try { await iamStatusPatchApiV1AdminAdminsIdStatus(account.id, { enabled: !account.enabled });
    notice.value = account.enabled ? '账号已停用，原登录会话已失效。' : '账号已启用。'; await load(); }
  catch { error.value = '状态更新失败，请重试。'; }
  finally { busy.value = false; }
}
watch(() => { const a = session?.currentAdmin.value; return a ? `${a.role}:${a.primary}:${a.userId}:${a.username}` : ''; }, () => {
  revision++; accounts.value = []; username.value = ''; password.value = ''; boundUserId.value = '';
  resetTarget.value = undefined; resetPassword.value = ''; notice.value = ''; error.value = '';
  void load();
}, { immediate: true });
onUnmounted(() => { revision++; });
</script>
<template>
  <p v-if="!permitted" role="alert">需要主管理员权限。</p>
  <div v-else class="portal-accounts">
    <p>API 用户和代理使用已有业务用户 ID 建立独立登录账号。API 用户须已确认 API 身份；代理须已启用推广资格。</p>
    <p v-if="error" role="alert">{{ error }} <button v-if="loadFailed" type="button" @click="load">重试</button></p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <form class="admin-card payout-fields portal-account-form" aria-label="创建登录账号" @submit.prevent="create">
      <h2>创建登录账号</h2>
      <label>用户名<input v-model="username" data-testid="credential-username" autocomplete="off" pattern="[A-Za-z0-9一-鿿._@\-]{1,64}" required></label>
      <label>初始密码<input v-model="password" data-testid="credential-password" type="password" autocomplete="new-password" minlength="8" maxlength="72" required></label>
      <label>角色<select v-model="role" data-testid="credential-role"><option value="API_USER">API 用户</option><option value="PROMOTER">代理</option><option value="NORMAL_ADMIN">普通管理员</option><option value="ADMIN">管理员</option></select></label>
      <label v-if="portal">业务用户 ID<input v-model="boundUserId" data-testid="credential-owner" required maxlength="128"><small>填写已创建的真实业务用户 ID，不能使用演示列表 ID；创建后不可换绑。</small></label>
      <button class="primary-action" type="submit" :disabled="busy">创建登录账号</button>
    </form>
    <div class="table-wrap data-table-region" role="region" tabindex="0" aria-label="登录账号列表" :aria-busy="loading"><TableUpdating v-if="loading&&accounts.length"/><table><caption>登录账号</caption><thead><tr><th>用户名</th><th>角色</th><th>业务用户 ID</th><th>状态</th><th>操作</th></tr></thead><tbody><TableLoadingRows v-if="loading&&!accounts.length" :columns="5" label="正在加载登录账号…"/>
      <tr v-for="account in accounts" :key="account.id ?? account.username ?? ''"><td>{{ account.username }}</td><td>{{ account.role }}{{ account.primary ? '（主管理员）' : '' }}</td><td>{{ account.boundUserId ?? '—' }}</td><td>{{ account.enabled ? '启用' : '停用' }}</td><td>
        <template v-if="!account.primary && account.username !== session?.currentAdmin.value?.username"><button type="button" :disabled="busy" @click="resetTarget = account; resetPassword = ''">重置密码</button><button type="button" :disabled="busy" @click="toggle(account)">{{ account.enabled ? '停用' : '启用' }}</button></template>
      </td></tr><tr v-if="!loading && !error && !accounts.length"><td colspan="5">暂无账号</td></tr>
    </tbody></table></div>
    <form v-if="resetTarget" class="admin-card" aria-label="重置登录密码" @submit.prevent="reset"><h2>重置 {{ resetTarget.username }} 的密码</h2><label>新密码<input v-model="resetPassword" data-testid="reset-password" type="password" autocomplete="new-password" minlength="8" maxlength="72" required></label><button type="submit" :disabled="busy">保存密码</button><button type="button" @click="resetTarget = undefined; resetPassword = ''">取消</button></form>
  </div>
</template>
<style scoped>
.portal-accounts { min-width: 0; }
.portal-account-form { grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; gap: 20px; }
.portal-account-form > h2 { grid-column: 1 / -1; margin: 0; }
.portal-account-form > label { min-width: 0; align-content: start; }
.portal-account-form input, .portal-account-form select { width: 100%; height: 44px; }
.portal-account-form small { line-height: 1.5; overflow-wrap: anywhere; }
.portal-account-form > button { grid-column: 1 / -1; justify-self: end; height: 44px; }
@media (max-width: 760px) {
  .portal-account-form { grid-template-columns: minmax(0, 1fr); }
}
</style>
