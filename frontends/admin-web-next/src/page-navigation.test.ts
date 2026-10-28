import { afterEach, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { defineComponent, ref } from 'vue';
import { fixtureSession } from './fixtureSession.test-support';
const motion = vi.hoisted(() => ({ play: vi.fn(), cancel: vi.fn() }));
vi.mock('./pageMotion', () => ({ createPageMotion: () => motion }));
import App from './App.vue';

enableAutoUnmount(afterEach);
afterEach(() => { vi.restoreAllMocks(); vi.clearAllMocks(); document.body.innerHTML = ''; });

it('animates workspace changes without remounting list state or stealing detail focus', async () => {
  const session = fixtureSession(); session.authenticate('primary.admin');
  const phase = ref<'ready' | 'checking'>('ready');
  const page = defineComponent({ setup: () => ({ search: ref('') }), template: '<label>查询<input v-model="search" /></label>' });
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/admin/agents', name: 'admin-agents', component: { template: '<h1>代理</h1>' } },
    { path: '/admin/users', name: 'admin-users', component: page },
    { path: '/admin/users/:userRef', name: 'admin-user-detail', component: page }
  ] });
  await router.push('/admin/agents'); await router.isReady();
  const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  const wrapper = mount(App, { props: { session: { ...session, phase } }, attachTo: document.body, global: { plugins: [router] } });
  await flushPromises();
  expect(motion.play).not.toHaveBeenCalled();
  await router.push('/admin/users'); await flushPromises();
  expect(motion.play).toHaveBeenCalledExactlyOnceWith(wrapper.get('[data-testid="protected-content"]').element, -1);
  const input = wrapper.get<HTMLInputElement>('input');
  await input.setValue('保留筛选'); input.element.focus();
  scroll.mockClear(); motion.play.mockClear();
  await router.push('/admin/users?page=2'); await flushPromises();
  await router.push('/admin/users/example'); await flushPromises();
  expect(wrapper.get<HTMLInputElement>('input').element.value).toBe('保留筛选');
  expect(document.activeElement).toBe(input.element);
  expect(scroll).not.toHaveBeenCalled();
  expect(motion.play).not.toHaveBeenCalled();
  motion.cancel.mockClear();
  phase.value = 'checking'; await flushPromises();
  expect(motion.cancel).toHaveBeenCalled();
  expect(wrapper.get('[data-testid="protected-content"]').isVisible()).toBe(false);
  motion.cancel.mockClear();
  wrapper.unmount();
  expect(motion.cancel).toHaveBeenCalled();
});
