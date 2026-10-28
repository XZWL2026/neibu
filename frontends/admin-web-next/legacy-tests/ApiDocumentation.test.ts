import { mount, enableAutoUnmount, flushPromises } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { createRouter, createMemoryHistory } from 'vue-router';
import { axe } from 'vitest-axe';
import ApiPage from './ApiPage.vue';
import { adminSessionKey } from './adminSession';

enableAutoUnmount(afterEach);
afterEach(() => vi.unstubAllGlobals());
async function open(hash='') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/api/docs', name: 'api-docs', component: ApiPage }] });
  await router.push('/api/docs'+hash); await router.isReady();
  return mount(ApiPage, { global: { plugins: [router], provide: { [adminSessionKey as symbol]: { currentAdmin: ref({ role: 'API_USER', userId: 'owner', username: 'API 用户' }) } } } });
}
it('shows matching request and response examples for the existing console categories without network calls', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  const writeText = vi.fn().mockResolvedValue(undefined); vi.stubGlobal('navigator', { clipboard: { writeText } });
  const wrapper = await open();
  const rail = wrapper.get('aside[aria-label="请求与响应示例"]');
  expect(rail.text()).toContain('Request'); expect(rail.text()).toContain('Response');
  for (const button of wrapper.get('nav[aria-label="API 文档分类"]').findAll('a').filter(link=>['图片生成','视频生成','人像库审核','任务查询'].includes(link.text()))) {
    await button.trigger('click'); await flushPromises();
    expect(rail.get('pre[aria-label="请求示例"]').text()).toContain(button.text() === '人像库审核' ? '/api/v1/media/portraits' : '/api/v1/media/tasks');
    await rail.get('.console-doc-code-toolbar button').trigger('click'); await flushPromises();
    expect(writeText).toHaveBeenLastCalledWith(rail.get('pre[aria-label="请求示例"]').text());
  }
  expect(fetch).not.toHaveBeenCalled();
  expect((await axe(`<main>${wrapper.html()}</main>`, { rules: { 'color-contrast': { enabled: false } } })).violations).toEqual([]);
});
it('keeps request code readable when copying is unavailable', async () => {
  vi.stubGlobal('navigator', {});
  const wrapper = await open();
  await wrapper.get('aside[aria-label="请求与响应示例"] .console-doc-code-toolbar button').trigger('click');
  expect(wrapper.get('[role="status"]').text()).toBe('复制失败，请手动选择代码');
  expect(wrapper.get('pre[aria-label="请求示例"]').text()).toContain('Authorization: Bearer');
});

it('navigates documentation categories and support anchors with the compact section selector', async () => {
  const wrapper = await open();
  const select = wrapper.get('#api-doc-section');
  const option = wrapper.findAll('#api-doc-section option').find(item => item.text() === '视频生成')!;
  await select.setValue(option.attributes('value')); await flushPromises();
  expect(wrapper.get('#api-category-title').text()).toBe('视频生成');
  await select.setValue('#console-api-errors'); await flushPromises();
  expect(wrapper.get('.console-doc-nav a[aria-current="location"]').text()).toBe('错误处理');
  expect((select.element as HTMLSelectElement).value).toBe('#console-api-errors');
});

it('opens the materials deep link in place and includes reference-style field details',async()=>{
 const wrapper=await open('#materials');
 expect(wrapper.get('#api-category-title').text()).toBe('人像库审核');
 expect(wrapper.findAll('.console-doc-nav a[aria-current]')).toHaveLength(1);
 expect(wrapper.get('table[aria-label="请求参数"]').text()).toContain('fileId');
 await wrapper.get('button[aria-label="Python 示例"]').trigger('click');
 expect(wrapper.get('pre[aria-label="请求示例"]').text()).toContain('urllib.request');
 expect(wrapper.get('nav[aria-label="API 文档分类"]').text()).toContain('首次调用 API');
 await wrapper.get('a[href$="#console-api-errors"]').trigger('click');await flushPromises();
 expect(wrapper.findAll('.console-doc-nav a[aria-current]')).toHaveLength(1);
 expect(wrapper.get('.console-doc-nav a[aria-current]').text()).toBe('错误处理');
});

it.each([
  ['#reference-images', '参考图片上传', '/api/v1/files/reference-images', 'form.append("file"'],
  ['#file-access', '获取生成文件 URL', '/api/v1/files/FILE_ID/access-url', 'method: "GET"']
])('opens and copies the file documentation at %s', async (hash, title, path, nodeRequest) => {
  const writeText = vi.fn().mockResolvedValue(undefined);
  const fetch = vi.fn();
  vi.stubGlobal('navigator', { clipboard: { writeText } });
  vi.stubGlobal('fetch', fetch);
  const wrapper = await open(hash);
  expect(wrapper.get('#api-category-title').text()).toBe(title);
  expect(wrapper.get('.console-doc-nav a[aria-current="page"]').text()).toBe(title);
  for (const language of ['cURL', 'Python', 'Node.js']) {
    await wrapper.get(`button[aria-label="${language} 示例"]`).trigger('click');
    const code = wrapper.get('pre[aria-label="请求示例"]').text();
    expect(code).toContain(path);
    expect(code).not.toContain('{fileId}');
    if (language === 'Node.js') expect(code).toContain(nodeRequest);
    await wrapper.get('.console-doc-code-toolbar button').trigger('click');
    await flushPromises();
    expect(writeText).toHaveBeenLastCalledWith(code);
  }
  expect(fetch).not.toHaveBeenCalled();
});
