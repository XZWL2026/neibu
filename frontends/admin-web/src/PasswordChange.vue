<script setup lang="ts">
import { ref } from 'vue';
import { iamPasswordPostApiV1AuthAdminPassword } from './api';
const emit = defineEmits<{ changed: []; cancel: [] }>();
const currentPassword = ref('');
const newPassword = ref('');
const busy = ref(false);
const error = ref('');
async function submit() {
  if (busy.value) return;
  busy.value = true; error.value = '';
  try {
    await iamPasswordPostApiV1AuthAdminPassword({ currentPassword: currentPassword.value, newPassword: newPassword.value });
    currentPassword.value = ''; newPassword.value = '';
    emit('changed');
  } catch { error.value = '修改失败，请检查当前密码或服务状态后重试。'; }
  finally { busy.value = false; }
}
</script>
<template>
  <form class="admin-card" aria-label="修改登录密码" @submit.prevent="submit">
    <h2>修改登录密码</h2><p>修改成功后，当前账号需要重新登录。</p>
    <label>当前密码<input v-model="currentPassword" type="password" autocomplete="current-password" required></label>
    <label>新密码<input v-model="newPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" required></label>
    <p v-if="error" role="alert">{{ error }}</p>
    <button class="primary-action" type="submit" :disabled="busy">{{ busy ? '正在修改…' : '保存新密码' }}</button>
    <button class="secondary-action" type="button" :disabled="busy" @click="emit('cancel')">取消</button>
  </form>
</template>
