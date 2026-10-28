import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import FinanceTrend from './FinanceTrend.vue';
import BaseChart from './BaseChart.vue';
import type { EChartsCoreOption } from 'echarts/core';
const api = vi.hoisted(() => ({ finance:vi.fn() }));
vi.mock('../api', () => ({ billingDailyFinanceGetApiV1AdminBillingDailyFinance:api.finance }));
enableAutoUnmount(afterEach);
const row = (date:string) => ({date,rechargeAmount4:'12.3456',consumptionAmount4:null});
const response = (query:{from?:string;to?:string;offset:number;limit:number},items:ReturnType<typeof row>[],totalDays=items.length) => ({data:{...query,from:query.from??null,to:query.to??null,items,totalDays,totalRechargeAmount4:'12.3456',totalConsumptionAmount4:null}});
beforeEach(() => { api.finance.mockReset(); });

it('loads every server page for all dates using the real 30-day contract', async () => {
  const items=Array.from({length:45},(_,index)=>row(new Date(Date.UTC(2026,8,index+1)).toISOString().slice(0,10)));
  api.finance.mockImplementation(async query=>response(query,items.slice(query.offset,query.offset+query.limit),items.length));
  const wrapper=mount(FinanceTrend,{props:{days:0,revision:0}});await flushPromises();
  expect(api.finance.mock.calls.map(([query])=>query)).toEqual([{offset:0,limit:30},{offset:30,limit:30}]);
  const option = wrapper.getComponent(BaseChart).props('option') as EChartsCoreOption;
  expect(option.xAxis).toMatchObject({ data: expect.any(Array) });
  expect((option.xAxis as {data:string[]}).data).toHaveLength(45);
  expect(wrapper.findAll('tbody tr')).toHaveLength(45);
  expect(option.series).toEqual(expect.arrayContaining([expect.objectContaining({ name:'消费金额', data:Array(45).fill(null) })]));
  expect(wrapper.text()).toContain('全部日期');expect(wrapper.text()).toContain('12.3456');
});

it('uses the applied custom date range and hides stale data during a new request',async()=>{
  let resolve!:(value:unknown)=>void;
  api.finance.mockImplementationOnce(async query=>response(query,[row('2026-09-01')]))
    .mockImplementationOnce(()=>new Promise(done=>{resolve=done;}));
  const wrapper=mount(FinanceTrend,{props:{days:-1,range:{from:'2026-09-01',to:'2026-09-02'},revision:0}});await flushPromises();
  await wrapper.setProps({range:{from:'2026-09-03',to:'2026-09-04'}});
  expect(wrapper.findComponent(BaseChart).exists()).toBe(false);
  const query=api.finance.mock.lastCall![0];expect(query).toEqual({from:'2026-09-03',to:'2026-09-04',offset:0,limit:30});
  resolve(response(query,[row('2026-09-03')]));await flushPromises();
  expect(wrapper.text()).not.toContain('2026-09-01');expect(wrapper.text()).toContain('2026-09-03');
});

it('rejects incomplete pagination and allows an explicit retry instead of showing partial totals',async()=>{
  api.finance.mockImplementation(async query=>response(query,[],2));
  const wrapper=mount(FinanceTrend,{props:{days:0,revision:0}});await flushPromises();
  expect(wrapper.get('[role="alert"]').text()).toContain('读取失败');expect(wrapper.findComponent(BaseChart).exists()).toBe(false);
  api.finance.mockImplementation(async query=>response(query,[]));
  await wrapper.get('button').trigger('click');await flushPromises();
  expect(wrapper.get('[role="status"]').text()).toContain('暂无账务记录');
});
