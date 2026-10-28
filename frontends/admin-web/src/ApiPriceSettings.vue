<script setup lang="ts">
import {onMounted,ref} from 'vue';
import ApiClientPrices from './ApiClientPrices.vue';
import type {ResponseBillingDomainPricingMediaPrice as Price,RequestBillingDomainPricingMediaPrice as Override} from './api';
const props=defineProps<{prices:Price[];initialPrices:Override[];initialMode:string;pointsPerYuan4:string;titleId?:string;disabled?:boolean}>();
const emit=defineEmits<{cancel:[];save:[prices:Override[],mode:string]}>();
const mode=ref(props.initialMode);
const draft=ref(props.initialPrices.map(price=>({...price})));
const editor=ref<InstanceType<typeof ApiClientPrices>>();
const back=ref<HTMLButtonElement>();
function cancel(){if(!props.disabled)emit('cancel');}
function save(){if(props.disabled||mode.value==='custom'&&!editor.value?.valid)return;emit('save',mode.value==='default'?[]:draft.value.map(price=>({...price})),mode.value);}
onMounted(()=>back.value?.focus());
</script>
<template>
 <section class="api-price-settings" :aria-labelledby="titleId||'api-price-settings-title'" @keydown.esc.stop.prevent="cancel">
  <header class="api-price-settings-header"><button ref="back" type="button" aria-label="返回客户信息" :disabled="disabled" @click="cancel"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m14 5-7 7 7 7"/></svg></button><h2 :id="titleId||'api-price-settings-title'">设置价格</h2><button type="button" aria-label="关闭价格设置" :disabled="disabled" @click="cancel"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button></header>
  <div class="api-price-settings-body">
   <fieldset class="api-price-settings-choices" :disabled="disabled"><legend class="sr-only">价格配置</legend><label :class="{selected:mode==='default'}"><input v-model="mode" type="radio" name="settings-price-mode" value="default"/><span>使用默认价格</span></label><label :class="{selected:mode==='custom'}"><input v-model="mode" type="radio" name="settings-price-mode" value="custom"/><span>自定义价格</span></label></fieldset>
   <ApiClientPrices ref="editor" :model-value="mode==='default'?[]:draft" @update:model-value="draft=$event" :prices="prices" :points-per-yuan4="pointsPerYuan4" matrix-layout :readonly="mode==='default'" :disabled="disabled"/>
  </div>
  <footer class="api-price-settings-footer"><button type="button" :disabled="disabled" @click="cancel">取消</button><button type="button" class="primary-action" :disabled="disabled||(mode==='custom'&&!editor?.valid)" @click="save">保存价格</button></footer>
 </section>
</template>
