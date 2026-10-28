<script setup lang="ts">
import { computed, nextTick, ref, watchPostEffect } from 'vue';
import { ElInput, ElOption, ElSelect, type InputInstance, type SelectInstance } from 'element-plus';
import 'element-plus/theme-chalk/base.css';
import 'element-plus/theme-chalk/el-scrollbar.css';
import 'element-plus/theme-chalk/el-popper.css';
import 'element-plus/theme-chalk/el-tag.css';
import 'element-plus/theme-chalk/el-option.css';
import 'element-plus/theme-chalk/el-option-group.css';
import 'element-plus/theme-chalk/el-select.css';
import 'element-plus/theme-chalk/el-input.css';

const props = defineProps<{
  id: string;
  label: string;
  modelValue: string;
  options: { value: string; label: string; detail?: string; search?: string }[];
  invalid?: boolean;
  describedBy?: string;
  remote?: boolean;
  loading?: boolean;
  hasMore?: boolean;
  disabled?: boolean;
}>();
const emit = defineEmits<{ 'update:modelValue': [value: string]; query: [value: string]; 'load-more': [] }>();
const control = ref<HTMLDivElement>();
const select = ref<SelectInstance>();
// ElSelect puts undeclared attributes on its wrapper, so associate errors with the focused input.
watchPostEffect(() => {
  const input = control.value?.querySelector('[role="combobox"]');
  input?.setAttribute('aria-invalid', String(!!props.invalid));
  if (props.describedBy) input?.setAttribute('aria-describedby', props.describedBy);
  else input?.removeAttribute('aria-describedby');
});
const searchInput = ref<InputInstance>();
const query = ref('');
const applied = ref('');
const filtered = computed(() => props.remote ? props.options : props.options.filter(option =>
  (option.search ?? `${option.label} ${option.detail ?? ''}`).toLocaleLowerCase().includes(applied.value)
));
function search() {
  if (props.loading) return;
  if (props.remote) emit('query', query.value.trim());
  else applied.value = query.value.trim().toLocaleLowerCase();
}
async function opened(visible: boolean) {
  if (!visible) return;
  query.value = ''; applied.value = '';
  if (props.remote) search();
  await nextTick(); searchInput.value?.focus();
}
async function close() { select.value?.blur(); await nextTick(); select.value?.focus(); }
defineExpose({ focus: () => select.value?.focus() });
</script>

<template>
  <div ref="control" class="admin-search-select" :class="{'is-invalid':invalid}">
    <ElSelect ref="select" :id="id" :model-value="modelValue" :aria-label="label" :disabled="disabled"
      placeholder="请选择"
      :teleported="false" :show-arrow="false" popper-class="admin-search-select-popup"
      @update:model-value="emit('update:modelValue', $event)" @visible-change="opened">
      <template #header>
        <div class="admin-select-search" @keydown.esc.stop.prevent="close" @keydown.down.stop.prevent="select?.focus()">
          <ElInput ref="searchInput" v-model="query" :aria-label="'搜索'+label" placeholder="输入姓名 / 手机号" clearable
            @clear="search" @keydown.enter.stop.prevent="search" />
          <button type="button" class="secondary-action" :aria-label="'查询'+label" :disabled="loading" @click="search">{{ loading ? '查询中…' : '查询' }}</button>
        </div>
      </template>
      <ElOption v-for="option in filtered" :key="option.value" :value="option.value" :label="[option.label,option.detail].filter(Boolean).join(' · ')">
        <strong>{{ option.label }}</strong><small v-if="option.detail">{{ option.detail }}</small>
      </ElOption>
      <template #empty><p class="admin-select-empty" role="status">未找到匹配用户</p></template>
      <template v-if="remote && hasMore" #footer><button type="button" class="secondary-action" :aria-label="'加载更多'+label" :disabled="loading" @click="emit('load-more')">加载更多</button></template>
    </ElSelect>
  </div>
</template>

<style scoped>
.admin-search-select {
  min-width:0; width:100%; color-scheme:dark;
  --el-color-primary:#55d9df; --el-color-primary-light-9:#173336;
  --el-bg-color:#141818; --el-bg-color-overlay:#181f1f;
  --el-fill-color:#263030; --el-fill-color-light:#223232; --el-fill-color-blank:#101313;
  --el-fill-color-lighter:#1e2929; --el-text-color-primary:#eef3f3; --el-text-color-regular:#dbe3e3;
  --el-text-color-secondary:#9aabab; --el-text-color-placeholder:#8e9898;
  --el-border-color:#343d3d; --el-border-color-light:#343d3d; --el-border-color-lighter:#2b3232;
  --el-border-radius-base:9px; --el-font-size-base:14px;
}
.admin-search-select :deep(.el-select__wrapper) { min-height:44px; padding:10px 12px; }
.admin-search-select :deep(.el-select__input), .admin-search-select :deep(.el-input__inner) { min-height:0; padding:0; border:0; border-radius:0; background:transparent; box-shadow:none; outline:0; }
.admin-search-select :deep(.el-select__input) { height:22px; }
.admin-search-select :deep(.el-select__placeholder) { font-size:14px; }
.admin-search-select :deep(.admin-search-select-popup) { border-radius:12px; overflow:hidden; box-shadow:0 12px 36px #0007; }
.admin-search-select :deep(.el-select-dropdown__header) { padding:12px; border-bottom:1px solid #2b3232; }
.admin-select-search { display:flex; align-items:center; gap:8px; }
.admin-select-search :deep(.el-input) { min-width:0; flex:1; }
.admin-select-search :deep(.el-input__wrapper) { min-height:40px; }
.admin-select-search button { flex-shrink:0; min-height:40px; padding:0 14px; font-size:13px; }
.admin-search-select :deep(.el-select-dropdown__item) { display:flex; flex-direction:column; justify-content:center; gap:5px; height:auto; min-height:62px; padding:10px 34px 10px 14px; line-height:1.4; }
.admin-search-select :deep(.el-select-dropdown__item strong) { font-size:14px; font-weight:500; overflow:hidden; text-overflow:ellipsis; }
.admin-search-select :deep(.el-select-dropdown__item small) { font-size:12px; color:#9aabab; overflow:hidden; text-overflow:ellipsis; }
.admin-search-select :deep(.el-select-dropdown__item.is-selected) { background:#173336; color:#83e4e4; }
.admin-search-select.is-invalid :deep(.el-select__wrapper) { box-shadow:0 0 0 1px #ffaaa0 inset; }
.admin-select-empty { margin:0; padding:24px 12px; text-align:center; color:#9aabab; font-size:13px; }
@media (prefers-reduced-motion:reduce) { .admin-search-select :deep(*) { transition:none!important; animation:none!important; } }
</style>
