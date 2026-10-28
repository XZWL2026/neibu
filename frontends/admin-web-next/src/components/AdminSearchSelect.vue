<script setup lang="ts">
import { cloneVNode, computed, h, ref, watchPostEffect, type VNode } from 'vue';
import { NSelect, NButton, type SelectInst, type SelectOption } from 'naive-ui';
const props = defineProps<{
  id: string; label: string; modelValue: string;
  options: { value: string; label: string; detail?: string; search?: string }[];
  invalid?: boolean; describedBy?: string; remote?: boolean; loading?: boolean; hasMore?: boolean; disabled?: boolean;
}>();
const emit = defineEmits<{ 'update:modelValue': [value: string]; query: [value: string]; 'load-more': [] }>();
const control = ref<HTMLDivElement>(), select = ref<SelectInst>(), query = ref(''), show=ref(false);
const options = computed(() => props.options.map(option => ({ ...option, label: [option.label, option.detail].filter(Boolean).join(' · ') })));
function filter(pattern: string, option: SelectOption) {
  const original = props.options.find(item => item.value === option.value);
  return (original?.search ?? `${original?.label} ${original?.detail ?? ''}`).toLocaleLowerCase().includes(pattern.toLocaleLowerCase().trim());
}
function search(value: string) { query.value = value; if (props.remote) emit('query', value.trim()); }
function opened(value: boolean) { show.value=value; if (value) { query.value = ''; if (props.remote) emit('query', ''); } }
function renderLabel(option: SelectOption) {
  const original = props.options.find(item => item.value === option.value);
  return h('div', { class: 'search-select-option' }, [h('strong', original?.label ?? String(option.label)), original?.detail ? h('small', original.detail) : null]);
}
function renderOption({node,option,selected}:{node:VNode;option:SelectOption;selected:boolean}) {
  return cloneVNode(node, {role:'option','aria-selected':selected,id:props.id+'-option-'+String(option.value)});
}
watchPostEffect(() => {
  const attrs = { id:props.id, 'aria-label':props.label, 'aria-invalid':String(!!props.invalid),
    'aria-expanded':String(show.value), 'aria-controls':props.id+'-listbox', 'aria-haspopup':'listbox', role:'combobox' };
  const describedBy = props.describedBy;
  const list = control.value?.querySelector('.n-base-select-menu');
  list?.setAttribute('id',props.id+'-listbox');list?.setAttribute('role','listbox');
  const input = control.value?.querySelector('.n-base-selection-label');
  for (const [key,value] of Object.entries(attrs)) input?.setAttribute(key,value);
  if (describedBy) input?.setAttribute('aria-describedby', describedBy);
  else input?.removeAttribute('aria-describedby');
});
defineExpose({ focus: () => select.value?.focus() });
</script>
<template><div ref="control" class="admin-search-select"><NSelect ref="select" :value="modelValue || null" :options="options" :disabled="disabled" :loading="loading" :status="invalid ? 'error' : undefined" :remote="remote" :filter="filter" :render-label="renderLabel" :render-option="renderOption" :input-props="{'aria-label':'搜索'+label,'aria-describedby':describedBy}" filterable :virtual-scroll="false" placeholder="请选择或搜索" :to="false" @update:value="emit('update:modelValue', $event == null ? '' : String($event))" @search="search" @update:show="opened"><template #empty><p role="status">{{ loading ? '正在查询…' : '未找到匹配用户' }}</p></template><template v-if="remote && hasMore" #action><NButton text :disabled="loading" :aria-label="'加载更多'+label" @click="emit('load-more')">加载更多</NButton></template></NSelect></div></template>
<style>.search-select-option { display:grid;gap:3px;padding:3px 0; }.search-select-option strong { font-size:13px;font-weight:500; }.search-select-option small { font-size:11px;color:#627087; }.admin-search-select { min-width:0;width:100%; }</style>
