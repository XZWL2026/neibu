import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import ApiModelUsage from './ApiModelUsage.vue';
import { configureGateway } from './api';

enableAutoUnmount(afterEach);
const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
const row = (id: string, model: string, amount4: string, ownerId = 'owner') => ({ id, model, amount4, ownerId });
beforeEach(() => configureGateway({ onUnauthorized: () => {} }));
afterEach(() => vi.unstubAllGlobals());

it('aggregates every page precisely and filters models without inventing resolutions or seconds', async () => {
  const fetch = vi.fn(async (url: string) => {
    expect(new URL(url, 'http://test').searchParams.get('limit')).toBe('100');
    const offset = Number(new URL(url, 'http://test').searchParams.get('offset'));
    return json(offset === 0 ? { items: [row('a', '视频模型', '9007199254740993.1234'), row('b', '图片模型', '0.2500')], total: 3 }
      : { items: [row('c', '视频模型', '0.0001')], total: 3 });
  });
  vi.stubGlobal('fetch', fetch);
  const wrapper = mount(ApiModelUsage, { props: { owner: 'owner' } }); await flushPromises();
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(fetch.mock.calls[1]![0]).toContain('offset=2');
  expect(wrapper.findAll('.api-model-card')).toHaveLength(2);
  expect(wrapper.text()).toContain('9007199254740993.1235');
  expect(wrapper.text()).toContain('2 次调用');
  expect(wrapper.get('.api-resolution-badge').text()).toBe('—');
  await wrapper.get('select').setValue('图片模型');
  expect(wrapper.findAll('.api-model-card')).toHaveLength(1);
  expect(wrapper.get('.api-model-card').text()).toContain('0.25');
  expect(fetch).toHaveBeenCalledTimes(2);
});

it('groups recorded resolutions across pages and sums video duration and exact charges', async () => {
  const video = (id: string, resolution: string | null, durationSeconds: number | null, amount: string) =>
    ({ ...row(id, '视频模型', amount), type: 'VIDEO', resolution, durationSeconds, count: 1 });
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const query = new URL(url, 'http://test').searchParams;
    if (Number(query.get('limit')) > 100) return new Response('', { status: 400 });
    return json(query.get('offset') === '0' ? { items: [video('a', '720p', 5, '1.2345'), video('b', '1080p', 10, '2.0000')], total: 5 }
      : { items: [video('c', '720p', 6, '0.0001'), video('d', null, null, '0.5000'),
        { ...row('e', '图片模型', '0.2500'), type: 'IMAGE', resolution: '2K', count: 4, durationSeconds: 0 }], total: 5 });
  }));
  const wrapper = mount(ApiModelUsage, { props: { owner: 'owner' } }); await flushPromises();
  expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  const table = wrapper.get('table[aria-label="视频模型 消耗明细"]');
  expect(table.findAll('tbody tr').map(tr => tr.findAll('td').map(td => td.text()))).toEqual([
    ['720p', '11', '¥ 1.2346'], ['1080p', '10', '¥ 2'], ['—', '—', '¥ 0.5']
  ]);
  expect(table.findAll('tfoot td').map(td => td.text())).toEqual(['—', '¥ 3.7346']);
  expect(wrapper.get('table[aria-label="图片模型 消耗明细"] tbody').text()).toContain('2K');
  expect(wrapper.get('table[aria-label="图片模型 消耗明细"] tbody tr td:nth-child(2)').text()).toBe('—');
});

it('clears old summaries when dates change and never displays a partial or foreign-owner total', async () => {
  const fetch = vi.fn().mockResolvedValue(json({ items: [row('a', '旧模型', '1.0000')], total: 1 }));
  vi.stubGlobal('fetch', fetch);
  const wrapper = mount(ApiModelUsage, { props: { owner: 'owner' } }); await flushPromises();
  expect(wrapper.text()).toContain('旧模型');
  fetch.mockResolvedValue(json({ items: [row('b', '其他账户模型', '99.0000', 'another')], total: 1 }));
  await wrapper.get('input[aria-label="模型消耗开始日期"]').setValue('2026-01-01'); await flushPromises();
  expect(wrapper.find('.api-model-card').exists()).toBe(false);
  expect(wrapper.get('[role="alert"]').text()).toContain('消费归属不匹配');
  expect(wrapper.text()).not.toContain('其他账户模型');
});

it('discards a delayed response after the owner changes', async () => {
  let finish!: (response: Response) => void;
  vi.stubGlobal('fetch', vi.fn().mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }))
    .mockResolvedValue(json({ items: [], total: 0 })));
  const wrapper = mount(ApiModelUsage, { props: { owner: 'owner' } });
  await wrapper.setProps({ owner: 'new-owner' }); await flushPromises();
  finish(json({ items: [row('a', '旧账户数据', '99.0000')], total: 1 })); await flushPromises();
  expect(wrapper.text()).not.toContain('旧账户数据');
  expect(wrapper.text()).toContain('暂无模型消费记录');
});
