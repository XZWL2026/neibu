<script setup lang="ts">
import PromoterIcon from './PromoterIcon.vue';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import ArkModelPicker from './ArkModelPicker.vue';
import { ApiError, mediaGetGetApiV1AdminMediaArkConnection, mediaSavePutApiV1AdminMediaArkConnection,
  mediaTestPostApiV1AdminMediaArkConnectionTest,
  type ResponseMediaApplicationProviderArkModelCatalogModel as CatalogModel,
  type ResponseMediaInterfacesHttpArkConnectionControllerView as Connection,
  type ResponseRoutingInterfacesHttpRoutingAdminControllerProviderView as Provider } from './api';
const props = withDefaults(defineProps<{ providers: Provider[]; title?: string; showCatalog?: boolean; mediaType?: string }>(), { title: '供应商配置与 API Key', showCatalog: true });
const emit = defineEmits<{ version: [value: { providerId?: string | null; version?: number; source?: string | null } | undefined]; saved: []; providerChange: []; selectModel: [value: { providerId: string; model: CatalogModel }]; catalogue: [value: { providerId: string; models: CatalogModel[] } | null] }>();
const connection = ref<Connection>();
const providerId = ref('');
const baseUrl = ref('');
const apiKey = ref('');
const timeoutSeconds = ref<number | ''>(180);
const retryCount = ref<number | ''>(0);
const submitPath = computed(() => {
  try {
    const url = new URL(baseUrl.value);
    if (url.hostname === 'zexapi.com' || url.hostname === '127.0.0.1' && url.pathname === '/v1') return '/v1/videos';
    const prefix = url.pathname.replace(/\/+$/, '') || '/api/v3';
    return `${prefix}${props.mediaType === 'IMAGE' ? '/images/generations' : '/contents/generations/tasks'}`;
  } catch { return ''; }
});
const showKey = ref(false);
const loading = ref(false);
const saving = ref(false);
const probing = ref(false);
const busy = computed(() => loading.value || saving.value || probing.value);
const pendingProvider = ref<string | null>(null);
const message = ref('');
const probeMessage = ref('');
let request = 0;
const activeProviders = computed(() => props.providers.filter(provider => provider.enabled));
const dirty = computed(() => !!connection.value && (baseUrl.value !== (connection.value.baseUrl ?? '')
  || apiKey.value !== (connection.value.apiKey ?? '') || timeoutSeconds.value !== (connection.value.timeoutSeconds ?? 180)
  || retryCount.value !== (connection.value.retryCount ?? 0)));
const canTest = computed(() => !!connection.value?.apiKeyConfigured && connection.value.providerId === providerId.value && !dirty.value && !busy.value);
watch([baseUrl, apiKey, timeoutSeconds, retryCount], () => { message.value = ''; probeMessage.value = ''; }, { flush: 'sync' });
function clear() {
  emit('version', undefined); showKey.value = false;
  connection.value = undefined; baseUrl.value = ''; apiKey.value = ''; timeoutSeconds.value = 180; retryCount.value = 0;
  message.value = ''; probeMessage.value = ''; probing.value = false;
  emit('catalogue', null);
}
async function load() {
  const id = providerId.value;
  const current = ++request;
  clear();
  if (!id) return false;
  loading.value = true;
  try {
    const value = (await mediaGetGetApiV1AdminMediaArkConnection({ providerId: id })).data;
    if (current !== request) return false;
    if (value.providerId !== id) throw new Error('Provider mismatch');
    const endpoint = props.providers.find(provider => provider.providerId === id)?.endpoint ?? '';
    const initialUrl = value.baseUrl || (/^https?:\/\//i.test(endpoint) ? endpoint : '');
    baseUrl.value = initialUrl; apiKey.value = value.apiKey ?? '';
    timeoutSeconds.value = value.timeoutSeconds ?? 180; retryCount.value = value.retryCount ?? 0;
    connection.value = { ...value, baseUrl: initialUrl };
    emit('version', { providerId: value.providerId, version: value.version, source: value.source });
    return true;
  } catch {
    if (current === request) message.value = '连接配置加载失败，请重试。';
    return false;
  } finally { if (current === request) loading.value = false; }
}
async function switchProvider(id: string) {
  if (saving.value) return false;
  if (id === providerId.value) return true;
  pendingProvider.value = null; providerId.value = id;
  emit('providerChange'); return load();
}
async function selectProvider(id: string) {
  if (id === providerId.value) return !busy.value;
  if (saving.value) return false;
  if (dirty.value) { pendingProvider.value = id; return false; }
  return switchProvider(id);
}
defineExpose({ selectProvider });
function chooseProvider(event: Event) {
  const select = event.target as HTMLSelectElement;
  const id = select.value;
  select.value = providerId.value;
  if (id === providerId.value || saving.value) return;
  if (dirty.value) { pendingProvider.value = id; return; }
  switchProvider(id);
}
async function save() {
  if (!connection.value?.editable || busy.value || pendingProvider.value !== null) return;
  if (typeof timeoutSeconds.value !== 'number' || !Number.isInteger(timeoutSeconds.value) || timeoutSeconds.value < 1 || timeoutSeconds.value > 180
    || typeof retryCount.value !== 'number' || !Number.isInteger(retryCount.value) || retryCount.value < 0 || retryCount.value > 3) { message.value = '超时须为 1–180 秒，重试次数须为 0–3 的整数。'; return; }
  const id = providerId.value;
  saving.value = true; message.value = ''; probeMessage.value = '';
  try {
    await mediaSavePutApiV1AdminMediaArkConnection({ expectedVersion: connection.value.version,
      providerId: id, baseUrl: baseUrl.value, timeoutSeconds: timeoutSeconds.value, retryCount: retryCount.value,
      apiKey: apiKey.value === connection.value.apiKey ? '' : apiKey.value });
    if (await load()) {
      message.value = '已保存，后续调用使用此供应商的连接配置。'; emit('saved');
    }
  } catch (error) {
    message.value = error instanceof ApiError && error.status === 409 ? '连接配置已被其他管理员修改，请重新加载后编辑。'
      : error instanceof ApiError && error.status === 503 ? '连接暂不可保存，请检查媒体服务或旧配置迁移状态。'
      : '连接保存失败，请检查供应商 HTTP/HTTPS 地址和 API Key；更换协议、主机或端口时须填写新密钥。';
  } finally { saving.value = false; }
}
async function test() {
  if (!canTest.value || !connection.value) return;
  const version = connection.value.version;
  const current = request;
  probing.value = true; message.value = ''; probeMessage.value = '';
  try {
    const result = (await mediaTestPostApiV1AdminMediaArkConnectionTest({ providerId: providerId.value })).data;
    if (current !== request) return;
    probeMessage.value = result.version !== version ? '服务端配置已变化，请重新加载后验证。'
      : result.reachable ? '已保存配置的连接验证通过。此验证不生成图片或视频，也不保证模型调用成功。'
      : '连接验证失败，请检查当前供应商已保存的地址、API Key 和远端服务状态。';
  } catch { if (current === request) probeMessage.value = '连接验证失败，请检查服务连接后重试。'; }
  finally { if (current === request) probing.value = false; }
}
watch(activeProviders, providers => {
  if (!providerId.value && providers[0]?.providerId) switchProvider(providers[0].providerId);
}, { immediate: true });
onBeforeUnmount(() => { request++; clear(); });
</script>
<template>
  <section class="admin-card ark-connection infra-card" aria-labelledby="ark-connection-heading">
    <header class="infra-card-heading"><h2 id="ark-connection-heading">{{ title }}</h2><div class="ark-actions"><span class="infra-badge" :class="{ inactive: !probeMessage.includes('连接验证通过') }">{{ probing ? '验证中' : probeMessage.includes('连接验证通过') ? '连接正常' : '未验证' }}</span><button data-testid="ark-test" type="button" class="secondary-action" :disabled="!canTest" @click="test">测试连接</button></div></header>
    <p v-if="connection && !connection.editable" role="status">连接配置暂不可编辑，请检查服务状态后重新加载。</p>
    <form data-testid="ark-connection-form" @submit.prevent="save">
      <fieldset class="configuration-fields" :disabled="saving"><legend class="sr-only">供应商连接</legend>
        <div class="configuration-grid">
          <label for="ark-provider">供应商<select id="ark-provider" :value="providerId" :disabled="saving" required @change="chooseProvider"><option value="" disabled>选择已启用的供应商</option><option v-if="providerId && !activeProviders.some(item => item.providerId === providerId)" :value="providerId" disabled>{{ providerId }}（请先启用路由）</option><option v-for="item in activeProviders" :key="item.providerId ?? ''" :value="item.providerId">{{ item.displayName }}</option></select></label>
          <label for="ark-base-url">API Base URL<input id="ark-base-url" v-model="baseUrl" :disabled="busy || !connection?.editable" type="url" required maxlength="2048" pattern="[Hh][Tt][Tt][Pp][Ss]?://.*" autocomplete="off"></label>
          <label for="ark-api-key">API Key<span class="infra-secret"><input id="ark-api-key" v-model="apiKey" :disabled="busy || !connection?.editable" :type="showKey ? 'text' : 'password'" maxlength="4096" :required="!connection?.apiKeyConfigured" placeholder="请输入供应商 API Key" autocomplete="off" spellcheck="false" aria-describedby="ark-key-hint"><button type="button" :aria-label="showKey ? '隐藏 API Key' : '显示 API Key'" :aria-pressed="showKey" @click="showKey = !showKey"><PromoterIcon :name="showKey ? 'eye-off' : 'eye'"/></button></span></label>
        </div>
        <div v-if="mediaType" class="configuration-grid infra-protocol-fields"><label>请求路径<input :value="submitPath" readonly aria-label="生成请求路径"></label><label>请求方式<input value="POST" readonly aria-label="生成请求方式"></label></div>
    <details class="infra-advanced"><summary>高级设置</summary><div class="configuration-grid"><label for="ark-timeout">请求超时（秒）<input id="ark-timeout" v-model.number="timeoutSeconds" type="number" min="1" max="180" step="1" required :disabled="busy || !connection?.editable"></label><label for="ark-retries">查询重试次数<input id="ark-retries" v-model.number="retryCount" type="number" min="0" max="3" step="1" required :disabled="busy || !connection?.editable"></label></div><p v-if="connection">{{ connection.source === 'DATABASE' ? '已保存配置' : '环境配置' }} · 版本 {{ connection.version }} · API Key：{{ connection.apiKeyConfigured ? '已配置' : '未配置' }}</p><p id="ark-key-hint">{{ connection?.apiKeyConfigured ? '留空保留现有密钥。更换连接主机时须填写新密钥。' : '首次保存须填写真实 API Key。' }}</p></details>
        <div class="ark-actions"><button type="submit" class="primary-action" :disabled="busy || !connection?.editable || pendingProvider !== null">保存供应商配置</button><button data-testid="ark-reload" type="button" class="secondary-action" :disabled="saving || !providerId" @click="pendingProvider = null; load()">重新加载供应商配置</button></div>
      </fieldset>
    </form>

    <div v-if="pendingProvider !== null" role="alert">
      <p>当前供应商有未保存修改。切换将放弃这些修改，不会保存到其他供应商。</p>
      <button type="button" class="secondary-action" data-testid="ark-keep-draft" @click="pendingProvider = null">继续编辑当前供应商</button>
      <button type="button" class="secondary-action" data-testid="ark-discard-draft" @click="switchProvider(pendingProvider!)">放弃修改并切换</button>
    </div>
    <p v-if="dirty">有未保存修改，请先保存或重新加载，再验证连接。</p>
    <p v-if="busy" role="status">正在处理供应商配置…</p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="probeMessage" role="status">{{ probeMessage }}</p>
    <div v-show="showCatalog"><ArkModelPicker :version="connection?.version ?? 0" :provider-id="providerId" :enabled="!!connection?.apiKeyConfigured && !dirty && !loading && !saving" :disabled="busy" @select="emit('selectModel', $event)" @catalogue="emit('catalogue', $event)" /></div>
  </section>
</template>
<style scoped>
.ark-connection h2 { margin: 0; font-size: 20px; }
.ark-connection p { margin: 8px 0; color: #63769c; line-height: 1.7; font-size: 13px; }
.ark-connection .configuration-fields { border: 0; background: #f8faff; margin: 14px 0; padding: 14px; }
.ark-connection legend { padding: 0 8px; color: #1c315d; font-size: 14px; }
.ark-connection label { color: #354c77; font-size: 13px; }
.ark-connection input, .ark-connection select { width: 100%; min-height: 44px; padding: 10px 12px; border: 1px solid #dce7f9; border-radius: 9px; background: #fff; color: #111740; }
.ark-connection input::placeholder { color: #63769c; opacity: 1; }
.ark-connection button:disabled, .ark-connection fieldset:disabled button { opacity: .5; cursor: default; }
.ark-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.ark-connection p[role="status"] { padding: 10px 14px; border: 1px solid #dce7f9; border-radius: 9px; color: #315887; background: #f4f8ff; }
</style>
