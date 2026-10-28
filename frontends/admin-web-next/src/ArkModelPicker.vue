<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { mediaModelsGetApiV1AdminMediaArkConnectionModels,
  type ResponseMediaApplicationProviderArkModelCatalogModel as Model } from './api';
const props = defineProps<{ version: number; providerId: string; enabled: boolean; disabled: boolean }>();
const emit = defineEmits<{ select: [value: { providerId: string; model: Model }]; catalogue: [value: { providerId: string; models: Model[] } | null] }>();
const models = ref<Model[]>([]);
const selected = ref('');
const loading = ref(false);
const loaded = ref(false);
const error = ref('');
let request = 0;
const groups = [{ type: 'IMAGE', label: '图片' }, { type: 'VIDEO', label: '视频' }, { type: 'UNSUPPORTED', label: '尚未适配' }];
const chosen = computed(() => models.value.find(model => `${model.type}:${model.id}` === selected.value));
const canUse = computed(() => props.enabled && !props.disabled && !loading.value && !!chosen.value && chosen.value.status !== 'Shutdown' && !!chosen.value.capability);
function clear() { emit('catalogue', null); request++; models.value = []; selected.value = ''; loading.value = false; loaded.value = false; error.value = ''; }
async function load() {
  clear();
  if (!props.enabled) return;
  const current = request;
  loading.value = true;
  try {
    const result = (await mediaModelsGetApiV1AdminMediaArkConnectionModels({ providerId: props.providerId }, { cache: 'reload' })).data;
    if (current !== request) return;
    if (result.version !== props.version || result.providerId !== props.providerId) {
      error.value = '服务端连接配置已变化，请重新加载供应商配置。'; return;
    }
    if (!result.success) throw new Error('catalog unavailable');
    models.value = result.models ?? []; loaded.value = true;
    emit('catalogue', { providerId: props.providerId, models: models.value });
  } catch { if (current === request) error.value = '模型目录读取失败，请检查已保存的 API Key 和查询权限后重试。'; }
  finally { if (current === request) loading.value = false; }
}
function useModel() { if (canUse.value && chosen.value) emit('select', { providerId: props.providerId, model: chosen.value }); }
watch(() => [props.version, props.providerId, props.enabled], load, { immediate: true });
onBeforeUnmount(clear);
</script>
<template>
  <div class="ark-model-picker">
    <h3>供应商模型目录</h3>
    <p>自动读取已保存连接的图片和视频模型。ZEX 图片请选择异步型号 GPT Image 2／2.5、Nano Banana 2／Pro 各档位。目录展示不代表已开通调用权限，分辨率、比例、数量及时长会按模型自动带入，积分价格另行配置。</p>
    <label for="ark-discovered-model">选择供应商模型<select id="ark-discovered-model" v-model="selected" :disabled="!enabled || disabled || loading || !models.length">
      <option value="" disabled>请选择要配置的模型</option>
      <optgroup v-for="group in groups.filter(group => group.type !== 'UNSUPPORTED' || models.some(model => model.type === group.type))" :key="group.type" :label="group.label">
        <option v-for="item in models.filter(item => item.type === group.type)" :key="`${item.type}:${item.id}`"
          :value="`${item.type}:${item.id}`" :disabled="item.status === 'Shutdown' || !item.capability">
          {{ item.name }} · {{ item.id }}{{ item.status === 'Shutdown' ? '（已停用）' : item.status === 'SynchronousOnly' ? '（同步型号，请选择异步型号）' : !item.capability ? '（能力尚未适配）' : item.status === 'Retiring' ? '（即将退役）' : item.status ? `（${item.status}）` : '' }}
        </option>
      </optgroup>
    </select></label>
    <div class="ark-actions">
      <button type="button" class="secondary-action" data-testid="ark-refresh-models" :disabled="!enabled || disabled || loading" @click="load">刷新模型目录</button>
      <button type="button" class="primary-action" data-testid="ark-use-model" :disabled="!canUse" @click="useModel">填入模型表单</button>
    </div>
    <p v-if="!enabled">请先保存完整连接，保存后将自动读取模型目录。</p>
    <p v-else-if="loading" role="status">正在读取供应商模型目录…</p>
    <p v-else-if="error" role="status">{{ error }}</p>
    <p v-else-if="loaded && !models.length" role="status">当前目录没有图片或视频模型。</p>
    <p v-else-if="loaded" role="status">已读取 {{ models.length }} 个供应商模型。选择后点击“填入模型表单”，确认后保存。</p>
  </div>
</template>
<style scoped>
.ark-model-picker { margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--line); }
.ark-model-picker h3 { margin: 0 0 8px; }
.ark-model-picker select { display: block; width: 100%; max-width: 100%; min-height: 44px; margin: 8px 0 12px; padding: 10px 12px; border: 1px solid #354444; border-radius: 9px; background: #101717; color: #eef7f7; }
.ark-actions { display: flex; flex-wrap: wrap; gap: 10px; }
button:disabled { opacity: .5; cursor: default; }
</style>
