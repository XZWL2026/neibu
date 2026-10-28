<script setup lang="ts">
import PromoterIcon from "./PromoterIcon.vue";
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import ArkConnectionSettings from './ArkConnectionSettings.vue';
import MpsConfiguration from './MpsConfiguration.vue';
import './provider-infrastructure.css';
import { routingProvidersGetApiV1AdminRoutingProviders, routingModelsGetApiV1AdminRoutingModels,
  routingDeleteModelDeleteApiV1AdminRoutingModelsModelId,
  routingSaveProviderPutApiV1AdminRoutingProvidersProviderId, routingSaveModelPutApiV1AdminRoutingModelsModelId,
  mediaGetGetApiV1AdminMediaProviderStatus, storageGetGetApiV1AdminStorageProviderStatus, iamGetGetApiV1AdminIamSmsProviderStatus,
  type ResponseMediaApplicationProviderArkModelCatalogModel as CatalogModel,
  type ResponseRoutingInterfacesHttpRoutingAdminControllerProviderView as Provider,
  type ResponseRoutingInterfacesHttpRoutingAdminControllerModelView as Model,
  type ResponseRoutingInterfacesHttpRoutingAdminControllerCapability as Capability,
  type ResponseWebProviderConfigurationStatus as Status } from './api';
const providers = ref<Provider[]>([]);
const models = ref<Model[]>([]);
const statuses = ref<Status[]>([]);
const busy = ref(false);
const loaded = ref(false);
const message = ref('');
const statusError = ref('');
const provider = ref<Provider>({ providerId: '', displayName: '', endpoint: '', enabled: false });
const editingProviderId = ref<string | null>(null);
const providerMessage = ref('');
const providerForm = ref<HTMLFormElement>();
const providerIdError = computed(() => /^[A-Za-z0-9._:-]{1,128}$/.test(provider.value.providerId ?? '') ? ''
  : '供应商标识只能包含英文字母、数字、点、下划线、冒号和连字符，例如 image-provider；中文请填写在名称中。');
const newCapability = (): Capability => ({ type: '', displayName: '', resolutions: [], aspectRatios: [], maxCount: 0, minDurationSeconds: 0, maxDurationSeconds: 0, sizes: null });
const modelMessage = ref('');
const modelTimeout = ref<number | ''>('');
const modelRetries = ref<number | ''>('');
const deletingModelId = ref<string | null>(null);
const deleteError = ref('');
const deletionMessage = ref('');
const modelHeading = ref<HTMLHeadingElement>();
const modelIdError = computed(() => /^[A-Za-z0-9._:-]{1,128}$/.test(model.value.modelId ?? '') ? ''
  : '模型标识只能包含英文字母、数字、点、下划线、冒号和连字符，不能包含空格。');
const modelForm = ref<HTMLFormElement>();
const catalog = ref<{ providerId: string; models: CatalogModel[] } | null>(null);
const model = ref<Model>({ modelId: '', providerId: '', providerModel: '', capability: newCapability(), enabled: true, timeoutSeconds: null, retryCount: null });
const selectedModel = computed(() => catalog.value?.providerId === model.value.providerId
  ? catalog.value.models.find(item => item.id === model.value.providerModel) : undefined);
const savedModel = computed(() => models.value.find(item => item.modelId === model.value.modelId
  && item.providerId === model.value.providerId && item.providerModel === model.value.providerModel));
const automaticCapability = computed(() => (selectedModel.value?.status !== 'Shutdown' ? selectedModel.value?.capability : undefined)
  ?? savedModel.value?.capability);
const selectedResolutions = ref<string[] | null>(null);
const capability = computed<Capability>(() => {
  const available = automaticCapability.value ?? newCapability();
  const resolutions = (available.resolutions ?? []).filter(value => selectedResolutions.value === null || selectedResolutions.value.includes(value));
  return { ...available, resolutions,
    sizes: available.sizes ? Object.fromEntries(Object.entries(available.sizes).filter(([key]) => resolutions.includes(key))) : null,
    type: selectedModel.value?.type ?? savedModel.value?.capability?.type ?? '', displayName: model.value.capability?.displayName ?? '' };
});
function toggleResolution(value: string) {
  selectedResolutions.value = capability.value.resolutions?.includes(value)
    ? capability.value.resolutions.filter(item => item !== value) : [...(capability.value.resolutions ?? []), value];
}
watch(() => [model.value.providerId, model.value.providerModel], () => {
  selectedResolutions.value = model.value.capability?.resolutions?.length ? [...model.value.capability.resolutions] : null;
});
const tab = ref('VIDEO');
const tabs = [{ id: 'VIDEO', label: '视频 API' }, { id: 'IMAGE', label: '图片 API' }, { id: 'MPS', label: '腾讯增强' }, { id: 'BITRATE', label: '码率配置' }, { id: 'ROUTING', label: '路由策略' }, { id: 'VERSIONS', label: '配置版本' }];
const connectionSettings = ref<InstanceType<typeof ArkConnectionSettings>>();
let editRequest = 0;
const search = ref('');
const page = ref(1);
const modelEditor = ref(false);
const catalogOpen = ref(false);
const providerEditor = ref(false);
const connectionVersion = ref<{ providerId?: string | null; version?: number; source?: string | null }>();
const mpsVersion = ref<{ version: number; source?: string }>();
const filteredModels = computed(() => models.value.filter(item => item.capability?.type === tab.value
  && (!search.value.trim() || [item.modelId, item.providerModel, item.capability?.displayName, item.providerId].some(value => value?.toLowerCase().includes(search.value.trim().toLowerCase())))));
const pages = computed(() => Math.max(1, Math.ceil(filteredModels.value.length / 10)));
const visibleModels = computed(() => filteredModels.value.slice((page.value - 1) * 10, page.value * 10));
watch([tab, search], () => { page.value = 1; });
watch(pages, value => { page.value = Math.min(page.value, value); });
function newModel() { editRequest++; selectedResolutions.value = null; modelTimeout.value = ''; modelRetries.value = ''; model.value = { modelId: '', providerId: '', providerModel: '', capability: newCapability(), enabled: true, timeoutSeconds: null, retryCount: null }; modelMessage.value = ''; modelEditor.value = true; catalogOpen.value = true; }
function cancelModel() { editRequest++; modelEditor.value = false; catalogOpen.value = false; modelMessage.value = ''; }
async function load() {
  busy.value = true; message.value = ''; statusError.value = '';
  try {
    const [p, m] = await Promise.all([routingProvidersGetApiV1AdminRoutingProviders(), routingModelsGetApiV1AdminRoutingModels()]);
    providers.value = p.data; models.value = m.data; loaded.value = true;
    await refreshStatuses();
  } catch { message.value = '配置加载失败，请重试。'; }
  finally { busy.value = false; }
}
async function refreshStatuses() {
  statusError.value = '';
  const results = await Promise.allSettled([mediaGetGetApiV1AdminMediaProviderStatus(), storageGetGetApiV1AdminStorageProviderStatus(), iamGetGetApiV1AdminIamSmsProviderStatus()]);
  statuses.value = results.flatMap(result => result.status === 'fulfilled' ? [result.value.data] : []);
  if (results.some(r => r.status === 'rejected')) statusError.value = '部分服务状态无法读取，请检查服务连接后重试。';
}
async function editProvider(value?: Provider) {
  providerEditor.value = true;
  editingProviderId.value = value?.providerId ?? null;
  provider.value = value ? { ...value } : { providerId: '', displayName: '', endpoint: '', enabled: false };
  providerMessage.value = '';
  await nextTick();
  providerForm.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
}
async function saveProvider() {
  if (busy.value) return;
  if (providerIdError.value) { providerMessage.value = providerIdError.value; return; }
  busy.value = true; providerMessage.value = '';
  try {
    const id = editingProviderId.value ?? provider.value.providerId!;
    const saved = (await routingSaveProviderPutApiV1AdminRoutingProvidersProviderId(id, {
      displayName: provider.value.displayName?.trim(), endpoint: provider.value.endpoint?.trim(), enabled: provider.value.enabled
    })).data;
    const index = providers.value.findIndex(item => item.providerId === saved.providerId);
    if (index === -1) providers.value.push(saved); else providers.value[index] = saved;
    provider.value = { ...saved }; editingProviderId.value = saved.providerId ?? id;
    providerMessage.value = '供应商已保存，已更新上方列表。';
  } catch { providerMessage.value = '供应商保存失败，请检查名称、标识和 HTTP/HTTPS 地址。'; }
  finally { busy.value = false; }
}
async function editModel(value: Model) {
  const request = ++editRequest;
  if (value.providerId && value.providerId !== connectionVersion.value?.providerId) {
    if (!await connectionSettings.value?.selectProvider(value.providerId) || request !== editRequest) return;
  }
  modelEditor.value = true; catalogOpen.value = catalog.value?.providerId !== value.providerId;
  modelMessage.value = ''; model.value = { ...JSON.parse(JSON.stringify(value)), enabled: value.enabled ?? true };
  modelTimeout.value = value.timeoutSeconds ?? ''; modelRetries.value = value.retryCount ?? '';
  if (!model.value.capability) model.value.capability = newCapability();
  selectedResolutions.value = model.value.capability.resolutions?.length ? [...model.value.capability.resolutions] : null;
}

function clearConnectionSelection() {
  catalog.value = null; modelTimeout.value = ''; modelRetries.value = '';
  model.value = { modelId: '', providerId: '', providerModel: '', capability: newCapability(), enabled: true, timeoutSeconds: null, retryCount: null };
  modelMessage.value = '';
}
async function selectCatalogModel(value: { providerId: string; model: CatalogModel }) {
  if (busy.value || !loaded.value || !value.model.id || !['IMAGE', 'VIDEO'].includes(value.model.type ?? '')) return;
  const existing = models.value.find(item => item.providerId === value.providerId && item.providerModel === value.model.id);
  if (existing) await editModel(existing);
  else {
    modelTimeout.value = ''; modelRetries.value = '';
    model.value = { modelId: value.model.id, providerId: value.providerId, providerModel: value.model.id,
      enabled: true, timeoutSeconds: null, retryCount: null, capability: { ...newCapability(), displayName: value.model.name || value.model.id, type: value.model.type } };
  }
  modelEditor.value = true; tab.value = value.model.type!;
  modelMessage.value = existing ? '已载入模型，能力已按当前模型版本自动更新，保存后生效。' : '已自动带入模型标识和能力，可修改显示名称后保存。';
  await nextTick();
  modelForm.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  modelForm.value?.querySelector('input')?.focus({ preventScroll: true });
}
async function saveModel() {
  if (busy.value) return;
  if (!model.value.modelId || modelIdError.value) { modelMessage.value = modelIdError.value; return; }
  if (!automaticCapability.value) { modelMessage.value = '尚未取得此模型的能力，请从供应商模型目录选择已适配模型。'; return; }
  if (!capability.value.resolutions?.length) { modelMessage.value = '请至少选择一个支持规格。'; return; }
  if (modelTimeout.value !== '' && (!Number.isInteger(modelTimeout.value) || modelTimeout.value < 1 || modelTimeout.value > 180)
    || modelRetries.value !== '' && (!Number.isInteger(modelRetries.value) || modelRetries.value < 0 || modelRetries.value > 3)) { modelMessage.value = '超时须为 1–180 秒，重试次数须为 0–3 的整数；留空继承全局设置。'; return; }
  busy.value = true; modelMessage.value = '';
  try {
    const saved = (await routingSaveModelPutApiV1AdminRoutingModelsModelId(model.value.modelId, { ...model.value, timeoutSeconds: modelTimeout.value === '' ? null : modelTimeout.value, retryCount: modelRetries.value === '' ? null : modelRetries.value,
      capability: JSON.parse(JSON.stringify(capability.value)) })).data;
    const index = models.value.findIndex(item => item.modelId === saved.modelId);
    if (index === -1) models.value.push(saved);
    else models.value[index] = saved;
    modelMessage.value = '模型与能力已保存，已更新上方模型列表。';
  } catch { modelMessage.value = '模型保存失败，请检查供应商、能力范围和图片尺寸。'; }
  finally { busy.value = false; }
}
async function deleteModel() {
  const id = deletingModelId.value;
  if (busy.value || !id) return;
  busy.value = true; deleteError.value = ''; deletionMessage.value = '';
  try {
    await routingDeleteModelDeleteApiV1AdminRoutingModelsModelId(id);
    models.value = models.value.filter(item => item.modelId !== id);
    if (model.value.modelId === id) {
      model.value = { modelId: '', providerId: '', providerModel: '', capability: newCapability(), enabled: true, timeoutSeconds: null, retryCount: null };
      modelMessage.value = '';
    }
    deletingModelId.value = null;
    deletionMessage.value = `已删除模型 ${id}。`;
    await nextTick(); modelHeading.value?.focus();
  } catch { deleteError.value = '删除失败，请重试。'; }
  finally { busy.value = false; }
}
onMounted(load);
</script>
<template>
  <div class="provider-infrastructure">
    <header class="infra-heading"><div><h1 id="configuration-title">{{ tab === 'MPS' ? '腾讯增强' : tab === 'BITRATE' ? '码率配置' : '模型基础设施' }}</h1><p>{{ tab === 'MPS' ? '管理视频增强模板与输出规格。' : '统一管理模型接入与生成能力。' }}</p></div></header>
    <nav class="infra-tabs" aria-label="模型配置分类"><button v-for="item in tabs" :key="item.id" type="button" :aria-pressed="tab === item.id" @click="cancelModel(); tab = item.id">{{ item.label }}</button></nav>
    <div v-show="tab === 'VIDEO' || tab === 'IMAGE'" class="infra-layout" :class="{ 'without-detail': !modelEditor }">
      <div class="infra-main">
        <ArkConnectionSettings ref="connectionSettings" :media-type="tab" :title="`${tab === 'IMAGE' ? '图片' : '视频'} API 全局配置`" :show-catalog="catalogOpen" @provider-change="clearConnectionSelection" :providers="providers" @saved="refreshStatuses" @select-model="selectCatalogModel" @catalogue="catalog = $event" @version="connectionVersion = $event" />
        <section class="infra-card"><header class="infra-card-heading"><h2 ref="modelHeading" tabindex="-1">模型列表</h2><div class="infra-tools"><input v-model="search" type="search" aria-label="搜索模型" placeholder="搜索模型名称或 Model ID…"><button type="button" class="primary-action" :disabled="busy || !loaded" @click="newModel"><PromoterIcon name="plus"/>新增模型</button></div></header>
          <div class="table-wrap"><table><caption class="sr-only">模型列表</caption><thead><tr><th>模型名称</th><th>Model ID</th><th>支持规格</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in visibleModels" :key="item.modelId ?? ''">
            <td><strong>{{ item.capability?.displayName || item.modelId }}</strong><small>{{ item.modelId }}</small></td><td>{{ item.providerModel }}</td><td><div class="infra-specs"><span v-for="spec in item.capability?.resolutions" :key="spec">{{ spec }}</span></div></td><td><span class="infra-badge" :class="{ inactive: item.enabled === false }">{{ item.enabled !== false ? '启用' : '停用' }}</span></td>
            <td><div class="model-row-actions"><button type="button" class="infra-text-button" :disabled="busy" @click="editModel(item)">编辑模型</button><button type="button" class="infra-text-button danger-action" :disabled="busy" @click="deletingModelId = item.modelId; deleteError = ''; deletionMessage = ''">删除模型</button></div>
              <div v-if="deletingModelId === item.modelId" data-testid="model-delete-confirmation" class="model-delete-confirmation" role="group" :aria-label="`删除模型 ${item.modelId}`"><p>确认删除模型 {{ item.modelId }}？删除后无法用于新任务，历史任务、流水和定价记录会保留。</p><div class="model-row-actions"><button data-testid="model-delete-confirm" type="button" class="secondary-action danger-action" :disabled="busy" @click="deleteModel">{{ busy ? '正在删除…' : '确认删除' }}</button><button data-testid="model-delete-cancel" type="button" class="secondary-action" :disabled="busy" @click="deletingModelId = null">取消</button></div><p v-if="deleteError" role="alert">{{ deleteError }}</p></div>
            </td></tr><tr v-if="!visibleModels.length"><td colspan="5" class="infra-empty">{{ loaded ? '暂无匹配模型' : '正在加载模型…' }}</td></tr></tbody></table></div>
          <footer class="infra-pagination"><span>共 {{ filteredModels.length }} 个模型</span><div><button type="button" aria-label="上一页模型" :disabled="page <= 1" @click="page--"><PromoterIcon name="chevron-left"/></button><span>{{ page }} / {{ pages }}</span><button type="button" aria-label="下一页模型" :disabled="page >= pages" @click="page++"><PromoterIcon name="chevron-right"/></button></div></footer>
          <p v-if="deletionMessage" role="status">{{ deletionMessage }}</p>
        </section>
      </div>
      <aside v-show="modelEditor" class="infra-detail" aria-label="模型编辑"><header class="infra-card-heading"><h2>{{ model.modelId ? '编辑' : '新增' }}{{ tab === 'IMAGE' ? '图片' : '视频' }}模型</h2><button type="button" aria-label="关闭模型编辑" :disabled="busy" @click="cancelModel"><PromoterIcon name="close"/></button></header>
  <form ref="modelForm" data-testid="routing-model-form" @submit.prevent="saveModel"><fieldset :disabled="busy || !loaded" class="configuration-fields"><legend class="sr-only">新增或编辑模型</legend><div class="configuration-grid">
    <label>模型标识<input v-model.trim="model.modelId" required maxlength="128" pattern="[A-Za-z0-9._:\-]{1,128}" aria-describedby="model-id-help" :aria-invalid="!!model.modelId && !!modelIdError"></label><label>供应商<select v-model="model.providerId" required><option value="" disabled>选择供应商</option><option v-for="p in providers" :key="p.providerId ?? ''" :value="p.providerId">{{ p.displayName }}</option></select></label>
    <label>供应商模型标识<input v-model="model.providerModel" required maxlength="128"></label><label>显示名称<input v-model="model.capability!.displayName" required maxlength="128"></label><div><span>支持规格</span><div class="infra-spec-options" role="group" aria-label="模型支持规格"><button v-for="resolution in automaticCapability?.resolutions" :key="resolution" type="button" :aria-label="`支持规格 ${resolution}`" :aria-pressed="capability.resolutions?.includes(resolution)" @click="toggleResolution(resolution)">{{ resolution }}</button></div></div>  </div>
  <label class="provider-route-toggle"><span>状态</span><span class="provider-route-control"><input v-model="model.enabled" type="checkbox" role="switch" aria-label="模型启用状态"><span class="provider-route-track" aria-hidden="true"></span><span>{{ model.enabled ? '启用' : '停用' }}</span></span></label>
  <details class="infra-advanced"><summary>高级设置与模型能力</summary><div class="configuration-grid"><label for="model-timeout">请求超时（秒）<input id="model-timeout" v-model.number="modelTimeout" type="number" min="1" max="180" step="1" placeholder="继承全局设置"></label><label for="model-retries">查询重试次数<input id="model-retries" v-model.number="modelRetries" type="number" min="0" max="3" step="1" placeholder="继承全局设置"></label><label>媒体类型<input :value="capability.type === 'IMAGE' ? '图片' : capability.type === 'VIDEO' ? '视频' : ''" readonly></label>
    <label>分辨率<span class="infra-specs"><span v-for="resolution in capability.resolutions" :key="resolution">{{ resolution }}</span></span><input :value="capability.resolutions?.join('、') ?? ''" readonly></label>
    <label>比例<input :value="capability.aspectRatios?.join('、') ?? ''" readonly></label>
    <label>最多张数<input :value="automaticCapability ? capability.maxCount : ''" readonly></label>
    <label>最短秒数<input :value="!automaticCapability ? '' : capability.type === 'IMAGE' ? '不适用' : capability.minDurationSeconds" readonly></label>
    <label>最长秒数<input :value="!automaticCapability ? '' : capability.type === 'IMAGE' ? '不适用' : capability.maxDurationSeconds" readonly></label>
</div>
  <p id="model-id-help" class="muted">模型标识用于系统路由，不能包含空格，例如 seedance-2.5；显示名称可填写 Seedance 2.5。</p>
  <p v-if="model.modelId && modelIdError" role="alert">{{ modelIdError }}</p>
  <p v-if="automaticCapability" role="status">已载入模型能力，支持规格可在上方选择。</p>
  <p v-else role="status">请从上方供应商模型目录选择已适配模型，系统将自动带入能力。读取失败时可刷新模型目录。</p>
  <p v-if="selectedModel?.id === 'doubao-seedance-2-5-260628'">使用首帧或首尾帧图片时，视频比例自动跟随首帧图片。</p>
  <details v-if="capability.type === 'IMAGE' && automaticCapability"><summary>自动配置的图片尺寸</summary>
    <div v-for="resolution in capability.resolutions" :key="resolution" class="configuration-grid"><label v-for="ratio in capability.aspectRatios" :key="ratio">{{ resolution }} · {{ ratio }}<input :value="capability.sizes?.[resolution]?.[ratio] ?? '由供应商按档位与比例决定'" readonly></label></div>
  </details>
  </details><div class="infra-editor-actions"><button type="button" :disabled="busy" @click="cancelModel">取消</button>
  <button type="submit" class="primary-action" :disabled="!automaticCapability || !capability.resolutions?.length">{{ busy ? '正在保存…' : '保存模型与能力' }}</button></div>
  <p v-if="modelMessage" role="status">{{ modelMessage }}</p></fieldset></form>

      </aside>
    </div>
    <MpsConfiguration v-show="tab === 'MPS' || tab === 'BITRATE'" :section="tab === 'BITRATE' ? 'BITRATE' : 'MPS'" @navigate-bitrate="tab = 'BITRATE'" @version="mpsVersion = $event" />
    <section v-show="tab === 'ROUTING'" class="infra-card"><header class="infra-card-heading"><h2>供应商路由配置</h2><button type="button" class="secondary-action" :disabled="busy" @click="load">重新加载路由与状态</button></header>
  <div class="table-wrap"><table><caption class="sr-only">供应商列表</caption><thead><tr><th>标识</th><th>名称</th><th>路由</th><th>操作</th></tr></thead><tbody><tr v-for="item in providers" :key="item.providerId ?? ''"><td>{{ item.providerId }}</td><td>{{ item.displayName }}</td><td>{{ item.enabled ? '启用' : '停用' }}</td><td><button type="button" class="secondary-action" :disabled="busy" @click="editProvider(item)">编辑供应商</button></td></tr></tbody></table></div>
  <button type="button" class="secondary-action" :disabled="busy" @click="editProvider()">新增供应商</button>
  <form v-show="providerEditor" ref="providerForm" data-testid="routing-provider-form" @submit.prevent="saveProvider"><fieldset :disabled="busy || !loaded" class="configuration-fields"><legend>新增或编辑供应商</legend><div class="configuration-grid">
    <label>供应商标识<input v-model.trim="provider.providerId" :readonly="editingProviderId !== null" required maxlength="128" pattern="[A-Za-z0-9._:\-]{1,128}" aria-describedby="provider-id-help"></label><label>名称<input v-model="provider.displayName" required maxlength="128"></label><label>HTTP/HTTPS 地址<input v-model="provider.endpoint" type="url" required maxlength="512"></label><label class="provider-route-toggle"><span>接收生成任务</span><span class="provider-route-control">
      <input v-model="provider.enabled" type="checkbox" role="switch" aria-label="接收生成任务" aria-describedby="provider-routing-help">
      <span class="provider-route-track" aria-hidden="true"></span><span>{{ provider.enabled ? '已启用' : '已停用' }}</span>
    </span></label></div><p id="provider-routing-help" class="muted">开启后，关联模型可用于新的生成任务；关闭后停止接收新任务，已有任务记录保留。修改后点击“保存供应商”生效。</p><p id="provider-id-help">{{ editingProviderId ? '供应商标识创建后不可修改；显示名称可以修改。' : '标识使用英文字母、数字、点、下划线、冒号或连字符，例如 image-provider；名称可填写中文。' }}</p>
  <p v-if="!editingProviderId && provider.providerId && providerIdError" role="alert">{{ providerIdError }}</p>
  <button type="submit" class="primary-action">{{ busy ? '正在保存…' : '保存供应商' }}</button>
  <button type="button" class="secondary-action" :disabled="busy" @click="providerEditor = false">取消</button>
  <p v-if="providerMessage" role="status">{{ providerMessage }}</p>
  </fieldset></form>

      <details class="infra-advanced"><summary>供应商配置状态</summary><p>可用表示服务已加载所需配置，尚未进行远端健康探测。短信供应商尚未实现。</p><div class="infra-status-grid"><article v-for="status in statuses" :key="status.providerName ?? ''"><h3>{{ status.providerName }}</h3><p>{{ status.configured ? '已配置' : '未配置' }} · {{ status.available ? '可用' : '不可用' }} {{ status.maskedIdentifier }}</p><p v-if="status.missingEnvironmentVariables?.length">缺少环境变量：{{ status.missingEnvironmentVariables.join('、') }}</p></article></div><p v-if="statusError" role="status">{{ statusError }}</p></details>
    </section>
    <section v-show="tab === 'VERSIONS'" class="infra-card"><h2>当前配置版本</h2><table><thead><tr><th>配置</th><th>版本</th><th>来源</th></tr></thead><tbody><tr><td>供应商连接 {{ connectionVersion?.providerId }}</td><td>{{ connectionVersion?.version ?? '—' }}</td><td>{{ connectionVersion ? connectionVersion.source === 'DATABASE' ? '已保存配置' : '环境配置' : '—' }}</td></tr><tr><td>腾讯增强</td><td>{{ mpsVersion?.version ?? '—' }}</td><td>{{ mpsVersion ? mpsVersion.source === 'DATABASE' ? '已保存配置' : '环境配置' : '—' }}</td></tr></tbody></table></section>
    <p v-if="busy" role="status">正在处理…</p><p v-if="message" role="alert">{{ message }}<button type="button" @click="load">重新加载</button></p>
  </div>
</template>

<style scoped>
.provider-route-control { position: relative; display: flex; align-items: center; gap: 10px; min-height: 44px; cursor: pointer; }
.provider-route-control input { position: absolute; left: 0; width: 48px; height: 28px; min-height: 0; margin: 0; padding: 0; opacity: 0; cursor: pointer; }
.provider-route-track { width: 48px; height: 28px; flex: 0 0 48px; border: 1px solid var(--line); border-radius: 999px; background: #bdc9dc; }
.provider-route-track::before { content: ''; display: block; width: 20px; height: 20px; margin: 3px; border-radius: 50%; background: #eef2f2; }
.provider-route-control input:checked + .provider-route-track { background: #006bff; }
.provider-route-control input:checked + .provider-route-track::before { transform: translateX(20px); background: #fff; }
.provider-route-control input:focus-visible + .provider-route-track { outline: 2px solid #4ed9df; outline-offset: 3px; }
.provider-route-control input:disabled + .provider-route-track { opacity: .5; }

.model-row-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.model-delete-confirmation { margin-top: 12px; max-width: 360px; white-space: normal; }
.model-delete-confirmation p { margin: 8px 0; }
</style>
