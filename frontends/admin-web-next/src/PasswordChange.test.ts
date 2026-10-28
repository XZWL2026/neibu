import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import PasswordChange from './PasswordChange.vue';
import { configureGateway } from './api';
enableAutoUnmount(afterEach);
afterEach(() => vi.unstubAllGlobals());
const requests = vi.fn();
beforeEach(() => {
  configureGateway({});
  requests.mockReset().mockImplementation(async (path: string) => new Response(JSON.stringify(path.endsWith('/auth/session')
    ? { authenticated: true, csrfToken: 'csrf' } : { success: true }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', requests);
});
it('changes the current credential through SDK and clears passwords only after server confirmation', async () => {
  const wrapper = mount(PasswordChange);
  await wrapper.get('[autocomplete="current-password"]').setValue('CurrentPassword123');
  await wrapper.get('[autocomplete="new-password"]').setValue('NewPassword12345');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  const call = requests.mock.calls.find(([path]) => path.endsWith('/auth/admin/password'))!;
  expect(JSON.parse(call[1].body)).toEqual({ currentPassword: 'CurrentPassword123', newPassword: 'NewPassword12345' });
  expect(new Headers(call[1].headers).get('X-CSRF-TOKEN')).toBe('csrf');
  expect(wrapper.emitted('changed')).toHaveLength(1);
  expect(wrapper.get('[autocomplete="current-password"]').element).toHaveProperty('value', '');
});
it('keeps the form usable after a rejected password without reporting success', async () => {
  requests.mockImplementation(async (path: string) => new Response(JSON.stringify(path.endsWith('/auth/session')
    ? { authenticated: true, csrfToken: 'csrf' } : { code: 'INVALID_PASSWORD' }), { status: path.endsWith('/auth/session') ? 200 : 400 }));
  const wrapper = mount(PasswordChange);
  await wrapper.get('[autocomplete="current-password"]').setValue('WrongPassword123');
  await wrapper.get('[autocomplete="new-password"]').setValue('NewPassword12345');
  await wrapper.get('form').trigger('submit'); await flushPromises();
  expect(wrapper.emitted('changed')).toBeUndefined();
  expect(wrapper.get('[role="alert"]').text()).toContain('修改失败');
  expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined();
});
