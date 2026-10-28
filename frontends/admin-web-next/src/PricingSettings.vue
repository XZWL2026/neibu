<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import PromoterIcon from './PromoterIcon.vue';
import UserSpecialPrices from './UserSpecialPrices.vue';
import './pricing.css';
import { ApiError, billingGetGetApiV1AdminBillingMediaPrices, billingSavePutApiV1AdminBillingMediaPrices,
  billingGetGetApiV1SettingsRuntime,
  routingModelsGetApiV1AdminRoutingModels, type ResponseRoutingInterfacesHttpRoutingAdminControllerModelView as Model,
  type ResponseBillingApplicationPricingMediaPriceStoreSnapshot, type ResponseBillingDomainPricingMediaPrice as Price } from './api';
const catalog = ref<ResponseBillingApplicationPricingMediaPriceStoreSnapshot>();
type PriceDraft = Price & { inputPrice: string; originalInput: string };
const prices = ref<PriceDraft[]>([]);
const pointsPerYuan = ref('');
function compact(value: string) {
  return value.includes('.') ? value.replace(/0+$/, '').replace(/\.$/, '') : value;
}
function units4(value: string) {
  if (!/^[0-9]+(\.[0-9]{1,4})?$/.test(value)) throw new Error('Invalid decimal');
  const [whole, fraction = ''] = value.split('.');
  return BigInt(whole) * 10000n + BigInt(fraction.padEnd(4, '0'));
}
function displayYuan(price: Price) {
  if (price.yuanPerUnit != null) return compact(price.yuanPerUnit);
  if (price.pointsPerUnit == null) return '';
  const yuan4 = units4(price.pointsPerUnit) * 10000n / units4(pointsPerYuan.value);
  return compact(`${yuan4 / 10000n}.${(yuan4 % 10000n).toString().padStart(4, '0')}`);
}
function draft(price: Price): PriceDraft {
  const inputPrice = displayYuan(price);
  return { ...price, inputPrice, originalInput: inputPrice };
}
function formatPrice(price: PriceDraft) {
  if (/^[0-9]{1,12}(\.[0-9]{1,4})?$/.test(price.inputPrice)) price.inputPrice = compact(price.inputPrice);
}
const models = ref<Model[]>([]);
const busy = ref(false);
const message = ref('');
const account = ref('PERSONAL');
const accounts = [{ value: 'PERSONAL', label: '个人价格', description: '面向个人用户', icon: 'user' }, { value: 'ENTERPRISE', label: '企业价格', description: '面向企业客户', icon: 'bank' }, { value: 'API', label: 'API 价格', description: '面向 API 客户', icon: 'globe' }];
const sections = [{ type: 'VIDEO', title: '视频生成模型', icon: 'video' }, { type: 'IMAGE', title: '图片生成模型', icon: 'image' }, { type: 'ENHANCEMENT', title: '视频超分增强', icon: 'video' }];
const search = ref(''), typeFilter = ref(''), statusFilter = ref('');
const selectedKey = ref('');
const detailOpen = ref(true), autoNext = ref(true);
const expanded = ref(new Set<string>());
const deleting = ref<{ type: string | null; model: string | null; accountType: string } | null>(null);
const accountLabel = computed(() => accounts.find(item => item.value === account.value)?.label ?? '');
const deletionRows = computed(() => deleting.value ? prices.value.filter(price => price.accountType === deleting.value!.accountType && price.type === deleting.value!.type && price.model === deleting.value!.model) : []);
function hasModelPrice(group: { type: string | null; model: string | null }) {
  return prices.value.some(price => price.accountType === account.value && price.type === group.type && price.model === group.model
    && (price.inputPrice !== '' || catalog.value?.prices?.some(saved => dimension(saved) === dimension(price))));
}
function requestDelete(group: { type: string | null; model: string | null }) {
  if (busy.value) return;
  deleting.value = { type: group.type, model: group.model, accountType: account.value };
}
function removeModelPrices() {
  if (!deleting.value || busy.value) return;
  const target = deleting.value;
  prices.value = prices.value.filter(price => !(price.accountType === target.accountType && price.type === target.type && price.model === target.model));
  deleting.value = null;
  message.value = `已移除 ${modelName(target.model)} 的${accountLabel.value}，点击“保存全部价格”后生效；保存前可重新加载撤销。`;
}
const modelKey = (p: { type: string | null; model: string | null }) => [p.type, p.model].join('/');
const modelName = (id: string | null) => id === 'video-enhancement' ? '视频超分增强' : models.value.find(item => item.modelId === id)?.capability?.displayName || id || '未命名模型';
const providerName = (id: string | null) => models.value.find(item => item.modelId === id)?.providerId;
function toggleGroup(key: string) { if (expanded.value.has(key)) expanded.value.delete(key); else expanded.value.add(key); }
const dimension = (p: Price) => [p.type, p.accountType, p.model, p.resolution].join('/');
function includeRegisteredModels(saved: Price[]) {
  const rows = saved.map(draft);
  const dimensions = new Set(rows.map(dimension));
  for (const model of models.value) {
    const type = model.capability?.type;
    if (!model.modelId || !['IMAGE', 'VIDEO'].includes(type ?? '')) continue;
    for (const owner of accounts) for (const resolution of model.capability?.resolutions ?? []) {
      const price: Price = { type: type!, accountType: owner.value, model: model.modelId, resolution, unit: type === 'IMAGE' ? 'IMAGE' : 'SECOND', pointsPerUnit: null, yuanPerUnit: null, enabled: true };
      if (!dimensions.has(dimension(price))) { rows.push(draft(price)); dimensions.add(dimension(price)); }
    }
  }
  for (const owner of accounts) for (const resolution of ['720p', '1080p', '2K', '4K']) {
    const price: Price = { type: 'ENHANCEMENT', accountType: owner.value, model: 'video-enhancement', resolution, unit: 'SECOND', pointsPerUnit: null, yuanPerUnit: null, enabled: true };
    if (!dimensions.has(dimension(price))) rows.push(draft(price));
  }
  return rows;
}
const groups = computed(() => {
  const result: { key: number; type: string | null; model: string | null; rows: PriceDraft[] }[] = [];
  prices.value.forEach((price, index) => {
    if (price.accountType !== account.value || (typeFilter.value && price.type !== typeFilter.value) || (statusFilter.value && Boolean(price.enabled) !== (statusFilter.value === 'enabled'))) return;
    const query = search.value.trim().toLowerCase();
    if (query && ![price.model, modelName(price.model), price.resolution, providerName(price.model)].join(' ').toLowerCase().includes(query)) return;
    const group = result.find(item => item.type === price.type && item.model === price.model);
    if (group) group.rows.push(price);
    else result.push({ key: index, type: price.type, model: price.model, rows: [price] });
  });
  return result.sort((a, b) => sections.findIndex(s => s.type === a.type) - sections.findIndex(s => s.type === b.type));
});
const visibleRows = computed(() => groups.value.flatMap(group => group.rows));
const selected = computed(() => prices.value.find(price => price.accountType === account.value && dimension(price) === selectedKey.value) ?? visibleRows.value[0]);
const selectedGroup = computed(() => ({ rows: prices.value.filter(price => price.accountType === account.value && price.model === selected.value?.model && price.type === selected.value?.type) }));
watch([account, search, typeFilter, statusFilter], () => { deleting.value = null; selectedKey.value = ''; if (visibleRows.value[0]) select(visibleRows.value[0]); });
function select(price: PriceDraft) { selectedKey.value = dimension(price); expanded.value.add(modelKey(price)); detailOpen.value = true; }
function cancel() {
  if (!selected.value || busy.value) return;
  const original = catalog.value?.prices?.find(price => dimension(price) === dimension(selected.value!));
  selected.value.inputPrice = selected.value.originalInput; selected.value.enabled = original?.enabled ?? true;
}
async function saveSelected() {
  const next = visibleRows.value[visibleRows.value.indexOf(selected.value) + 1];
  if (selected.value && await save(dimension(selected.value))) { if (autoNext.value && next) { const row = prices.value.find(price => dimension(price) === dimension(next)); if (row) select(row); } }
}
async function load() {
  if (busy.value) return;
  busy.value = true; message.value = ''; deleting.value = null;
  try {
    const [priceResponse, modelResponse, rateResponse] = await Promise.all([billingGetGetApiV1AdminBillingMediaPrices({cache:'reload'}), routingModelsGetApiV1AdminRoutingModels({cache:'reload'}), billingGetGetApiV1SettingsRuntime({cache:'reload'})]);
    pointsPerYuan.value = rateResponse.data.rechargePointsPerYuan4 ?? '';
    if (units4(pointsPerYuan.value) <= 0n) throw new Error('Invalid conversion rate');
    catalog.value = priceResponse.data; models.value = modelResponse.data;
    prices.value = includeRegisteredModels(catalog.value.prices ?? []);
    if (selected.value) select(selected.value);
  } catch { catalog.value = undefined; prices.value = []; models.value = []; message.value = '加载价格或模型失败，请重试。'; }
  finally { busy.value = false; }
}
function remove(price: PriceDraft) { if (busy.value) return; prices.value.splice(prices.value.indexOf(price), 1); }
async function save(targetKey?: string) {
  if (!catalog.value || busy.value) return;
  const localDrafts = prices.value.map(price => ({ ...price }));
  const existing = new Set((catalog.value.prices ?? []).map(dimension));
  const target = targetKey ? prices.value.find(price => dimension(price) === targetKey) : undefined;
  if (targetKey && (!target || !target.inputPrice.trim())) { message.value = '请填写当前规格的价格后保存。'; return; }
  // The endpoint replaces the entire catalog. A single save merges only this
  // dimension into the last server snapshot, never other unsaved drafts.
  const submitted = targetKey && target
    ? [...(catalog.value.prices ?? []).filter(price => dimension(price) !== targetKey).map(draft), { ...target }]
    : prices.value.filter(p => p.inputPrice.trim() !== '' || existing.has(dimension(p)));
  if (submitted.some(p => !/^[A-Za-z0-9._:-]{1,128}$/.test(p.model ?? '') || !/^[A-Za-z0-9._:-]{1,128}$/.test(p.resolution ?? '') || (p.inputPrice !== p.originalInput && !/^[0-9]{1,12}(\.[0-9]{1,4})?$/.test(p.inputPrice)))) {
    message.value = '请填写有效的模型、分辨率标识和非负金额（最多四位小数），空价格不会自动设为免费。'; return;
  }
  const dimensions = submitted.map(dimension);
  if (new Set(dimensions).size !== dimensions.length) {
    message.value = '同一账户和模型下不能重复配置相同分辨率。'; return;
  }
  busy.value = true; message.value = '';
  try {
    catalog.value = (await billingSavePutApiV1AdminBillingMediaPrices({ expectedVersion: catalog.value.version, prices: submitted.map(({ inputPrice, originalInput, ...price }) => inputPrice === originalInput ? price : { ...price, pointsPerUnit: null, yuanPerUnit: compact(inputPrice) }) })).data;
    const refreshed = includeRegisteredModels(catalog.value.prices ?? []);
    if (targetKey) {
      const drafts = new Map(localDrafts.map(price => [dimension(price), price]));
      const refreshedKeys = new Set(refreshed.map(dimension));
      prices.value = refreshed.flatMap(price => {
        const key = dimension(price);
        if (key === targetKey) return [price];
        const local = drafts.get(key);
        return local ? [local] : existing.has(key) ? [] : [price];
      });
      prices.value.push(...localDrafts.filter(price => !refreshedKeys.has(dimension(price))));
      message.value = '当前规格已保存并生效，其他未保存修改已保留。';
    } else { prices.value = refreshed; message.value = '已保存，价格已立即生效。'; }
    return true;
  } catch (error) { message.value = error instanceof ApiError && error.status === 409 ? '价格已被其他管理员修改，请重新加载后编辑。' : '保存失败，请检查输入或服务状态。'; }
  finally { busy.value = false; }
}
onMounted(load);
</script>
<template>
  <div class="pricing-editor">
    <header class="pricing-heading"><div><h1 id="configuration-title">模型定价</h1><p>为不同类型客户设置模型的价格，支持批量调整。</p></div><RouterLink class="primary-action" to="/admin/providers"><PromoterIcon name="plus"/>新增模型</RouterLink></header>
    <div class="pricing-layout" :class="{ 'without-detail': !detailOpen || !selected }">
      <div class="pricing-main">
        <div class="pricing-accounts" role="group" aria-label="定价账户类型">
          <button v-for="item in accounts" :key="item.value" type="button" :aria-label="item.label" :aria-pressed="account === item.value" :disabled="busy" @click="account = item.value"><i><PromoterIcon :name="item.icon"/></i><span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span></button>
          <button type="button" class="pricing-special" aria-label="特殊客户价格" :aria-pressed="account === 'SPECIAL'" :disabled="busy" @click="account = 'SPECIAL'"><i><PromoterIcon name="diamond"/></i><span><strong>特殊客户价格</strong><small>个人 / 企业 / API 用户</small></span></button>
        </div>
        <UserSpecialPrices v-if="account === 'SPECIAL'" :models="models"/>
        <template v-else>
        <div class="pricing-filters"><select v-model="typeFilter" aria-label="模型类型"><option value="">全部模型</option><option value="VIDEO">视频模型</option><option value="IMAGE">图片模型</option></select><select v-model="statusFilter" aria-label="价格状态"><option value="">全部状态</option><option value="enabled">启用</option><option value="disabled">停用</option></select><label><PromoterIcon name="search"/><input v-model="search" aria-label="搜索模型名称或规格" placeholder="搜索模型名称、规格…"/></label></div>
        <p v-if="busy" role="status" class="pricing-feedback">正在处理价格配置…</p>
        <p v-if="message" role="status" class="pricing-feedback">{{ message }}</p>
        <p v-if="catalog && !catalog.managed" class="pricing-feedback">当前使用启动兼容价格。首次保存将由本页完整接管，未列出的维度不再提供报价。</p>
        <fieldset v-if="catalog" :disabled="busy" class="pricing-fields"><legend class="sr-only">媒体金额价格</legend>
          <section v-for="section in sections.filter(s => !typeFilter || s.type === typeFilter)" :key="section.type" class="pricing-section" :aria-label="section.title">
            <header class="pricing-section-heading"><i><PromoterIcon :name="section.icon"/></i><h2>{{ section.title }}</h2><span>{{ groups.filter(item => item.type === section.type).length }}</span></header>
            <article v-for="group in groups.filter(item => item.type === section.type)" :key="group.key" class="pricing-model" :data-model="group.model">
              <div class="pricing-model-header">
                <button type="button" class="pricing-model-heading" :aria-expanded="expanded.has(modelKey(group))" @click="toggleGroup(modelKey(group))"><span aria-hidden="true">{{ expanded.has(modelKey(group)) ? '⌃' : '›' }}</span><strong>{{ modelName(group.model) }}</strong><span class="pricing-badge" :class="{ inactive: !group.rows.some(p => p.enabled && p.inputPrice !== '') }">{{ group.rows.some(p => p.enabled && p.inputPrice !== '') ? '已启用' : group.rows.every(p => p.inputPrice === '') ? '待定价' : '已停用' }}</span><small v-if="providerName(group.model)" class="pricing-provider">{{ providerName(group.model) }}</small><small class="pricing-spec-count">{{ group.rows.length }} 个规格</small></button>
                <button v-if="hasModelPrice(group)" type="button" class="pricing-delete-model pricing-delete-icon" title="删除定价" :aria-label="`删除 ${modelName(group.model)} 的${accountLabel}`" @click="requestDelete(group)"><PromoterIcon name="trash"/></button>
              </div>
              <div v-if="deleting && deleting.accountType === account && deleting.type === group.type && deleting.model === group.model" class="pricing-delete-confirmation" role="group" :aria-label="`确认删除 ${modelName(group.model)} 的${accountLabel}`" @keydown.esc.stop="deleting = null">
                <p>删除 {{ modelName(group.model) }} 在{{ accountLabel }}下的全部 {{ deletionRows.length }} 个规格定价？</p>
                <p>点击“保存全部价格”后生效，其他价格分类和历史记录不受影响。</p>
                <p v-if="models.some(item => item.modelId === group.model)">该模型仍在模型列表中，保存后会显示为“待定价”，设置价格后可重新启用报价。</p>
                <div><button type="button" class="pricing-delete-model" @click="removeModelPrices">确认删除定价</button><button type="button" @click="deleting = null">取消删除</button></div>
              </div>
              <div v-show="expanded.has(modelKey(group))" class="pricing-table-wrap"><table><thead><tr><th>规格</th><th>价格（元/{{ section.type !== 'IMAGE' ? '秒' : '张' }}）</th><th>状态</th><th><span class="sr-only">操作</span></th></tr></thead><tbody>
                <tr v-for="price in group.rows" :key="dimension(price)" class="pricing-cell" :class="{ selected: selected === price && detailOpen }" @click="select(price)"><td><button type="button" class="pricing-resolution" :aria-label="`编辑 ${modelName(price.model)} ${price.resolution}`" @click="select(price)">{{ price.resolution }}</button></td><td><input v-model="price.inputPrice" :aria-label="`${price.model} ${price.resolution} 金额（元）`" @focus="select(price)" @blur="formatPrice(price)" @keydown.enter.prevent="save(dimension(price))" class="price-input" :disabled="busy" inputmode="decimal" placeholder="未定价"/></td><td><label class="pricing-row-status"><input v-model="price.enabled" :disabled="busy" type="checkbox" aria-label="启用价格"/><span>{{ price.enabled ? '启用' : '停用' }}</span></label></td><td><button type="button" class="pricing-remove" :disabled="busy" aria-label="移除此分辨率价格" @click.stop="remove(price)"><PromoterIcon name="close"/></button></td></tr>
              </tbody></table></div>
            </article>
            <p v-if="!groups.some(item => item.type === section.type)" class="pricing-empty">暂无符合条件的{{ section.type === 'IMAGE' ? '图片' : '视频' }}模型。</p>
          </section>
        </fieldset>
        <footer class="pricing-footer"><button type="button" :disabled="busy" @click="load">重新加载</button><button type="button" class="primary-action" data-testid="admin-save-pricing" :disabled="busy || !catalog" @click="save()">保存全部价格</button></footer>
        </template>
      </div>
      <aside v-if="detailOpen && selected" class="pricing-detail" aria-label="编辑价格"><header><h2>编辑价格</h2><button type="button" aria-label="关闭价格编辑" @click="detailOpen = false"><PromoterIcon name="close"/></button></header>
        <div class="pricing-detail-model"><i><PromoterIcon :name="selected.type !== 'IMAGE' ? 'video' : 'image'"/></i><div><strong>{{ modelName(selected.model) }}</strong><small v-if="providerName(selected.model)" class="pricing-provider">{{ providerName(selected.model) }}</small></div><span class="pricing-badge" :class="{inactive: !selected.enabled}">{{ selected.enabled ? '已启用' : '已停用' }}</span></div>
        <form @submit.prevent="saveSelected" @keydown.esc.prevent="cancel"><fieldset :disabled="busy"><legend class="sr-only">编辑所选规格</legend>
          <label class="pricing-field-label">选择规格</label><div class="pricing-specifications"><button v-for="price in selectedGroup?.rows" :key="dimension(price)" type="button" :aria-pressed="price === selected" @click="select(price)">{{ price.resolution }}</button></div>
          <label class="pricing-field-label" for="pricing-detail-amount">价格（元/{{ selected.type !== 'IMAGE' ? '秒' : '张' }}）</label><input id="pricing-detail-amount" v-model="selected.inputPrice" aria-label="编辑金额（元）" inputmode="decimal" placeholder="填写金额" @blur="formatPrice(selected)"/>
          <div class="pricing-comparison"><span>原价 <strong>{{ selected.originalInput || '未定价' }}</strong></span><span>→</span><span>新价 <strong>{{ selected.inputPrice || '未定价' }}</strong></span><small>{{ selected.inputPrice === selected.originalInput ? '未变化' : '待保存' }}</small></div>
          <label class="pricing-status-switch"><span>状态</span><input v-model="selected.enabled" type="checkbox" role="switch" aria-label="启用所选规格"/><strong>{{ selected.enabled ? '启用' : '停用' }}</strong></label>
          <label class="pricing-auto-next"><strong>快速操作</strong><span>修改后自动跳转到下一个规格</span><input v-model="autoNext" type="checkbox"/></label>
          <footer><button type="button" @click="cancel">取消 <small>Esc</small></button><button type="submit" class="primary-action">保存修改 <small>Enter</small></button></footer>
        </fieldset></form>
      </aside>
    </div>
  </div>
</template>
