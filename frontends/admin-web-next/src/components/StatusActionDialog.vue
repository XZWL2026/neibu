<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
const props = defineProps<{ targets: { id: string; label: string }[]; busy: boolean }>();
const emit = defineEmits<{ confirm: []; cancel: [] }>();
const dialog = ref<HTMLDialogElement>();
let trigger: HTMLElement | null = null;
onMounted(async () => { trigger = document.activeElement as HTMLElement; await nextTick(); dialog.value?.showModal(); });
onBeforeUnmount(() => { dialog.value?.close(); if (trigger?.isConnected) trigger.focus(); });
</script>
<template>
  <dialog ref="dialog" class="status-action-dialog" aria-labelledby="status-action-title" aria-describedby="status-action-description" :aria-busy="busy" @cancel.prevent="!busy && emit('cancel')">
    <h2 id="status-action-title">确认停用 {{ props.targets.length }} 位用户</h2>
    <p id="status-action-description">仅处理以下已选用户。停用后无法继续使用账号，已有登录会话将失效。</p>
    <ul><li v-for="target in targets" :key="target.id"><strong>{{ target.label }}</strong><small>{{ target.id }}</small></li></ul>
    <footer><button type="button" autofocus :disabled="busy" @click="emit('cancel')">取消</button><button type="button" class="primary-action" :disabled="busy" @click="emit('confirm')">{{ busy ? '正在停用…' : '确认停用' }}</button></footer>
  </dialog>
</template>
