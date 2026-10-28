import { afterEach, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import PromotionLink from './PromotionLink.vue';

enableAutoUnmount(afterEach);
afterEach(() => { vi.unstubAllEnvs(); vi.restoreAllMocks(); });

it('copies the public login URL with encoded code and no isolated agent session', async () => {
  vi.stubEnv('VITE_USER_WEB_URL', 'https://users.example.test/');
  window.history.replaceState(null, '', '/agent/0123456789abcdef0123456789abcdef/promoter/account');
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
  const wrapper = mount(PromotionLink, { props: { code: 'agent-code' } });
  expect(wrapper.get('input').element.value).toBe('https://users.example.test/home?ref=agent-code');
  expect(wrapper.get('input').attributes()).toHaveProperty('readonly');
  await wrapper.get('button').trigger('click'); await flushPromises();
  expect(writeText).toHaveBeenCalledWith('https://users.example.test/home?ref=agent-code');
  expect(wrapper.get('button').text()).toBe('已复制');
  await wrapper.setProps({ code: 'other-code' });
  expect(wrapper.get('button').text()).toBe('复制链接');
  expect(wrapper.get('input').element.value).toContain('ref=other-code');
});

it('uses the same-origin user root and permits clipboard failure retry', async () => {
  vi.stubEnv('VITE_USER_WEB_URL', '');
  const writeText = vi.fn().mockRejectedValueOnce(new Error('denied')).mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
  const wrapper = mount(PromotionLink);
  expect(wrapper.get('button').element.disabled).toBe(true);
  await wrapper.setProps({ code: 'agent-code' });
  expect(wrapper.get('input').element.value).toBe(`${window.location.origin}/home?ref=agent-code`);
  await wrapper.get('button').trigger('click'); await flushPromises();
  expect(wrapper.get('button').text()).toBe('复制失败，重试');
  await wrapper.get('button').trigger('click'); await flushPromises();
  expect(wrapper.get('button').text()).toBe('已复制');
});
