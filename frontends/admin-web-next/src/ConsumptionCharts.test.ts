import {mount,flushPromises,enableAutoUnmount} from '@vue/test-utils';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {axe} from 'vitest-axe';
const api=vi.hoisted(()=>({analytics:vi.fn()}));
vi.mock('./api',()=>({billingAnalyticsGetApiV1AdminBillingConsumptionsAnalytics:api.analytics}));
import ConsumptionCharts from './ConsumptionCharts.vue';
enableAutoUnmount(afterEach);
const report={total:105,amount4:'12.3456',unknownAmounts:0,models:[{model:'recorded-model',modelName:'真实模型',type:'VIDEO',calls:105,quantity:420,amount4:'12.3456',resolutions:[{resolution:'720p',calls:105,quantity:420,amount4:'12.3456'}]}],days:[{date:'2026-09-19',calls:105,amount4:'12.3456',videoAmount4:'12.3456',imageAmount4:'0.0000'}]};
beforeEach(()=>{vi.clearAllMocks();vi.useFakeTimers({toFake:['Date']});vi.setSystemTime(new Date('2026-09-19T08:00:00Z'));api.analytics.mockResolvedValue({data:report});});
it('renders full-query money charts and the actual model resolution breakdown',async()=>{const w=mount(ConsumptionCharts,{props:{query:{to:'2026-09-19T15:59:59.999Z',userId:'owner'},summary:report}});await flushPromises();expect(w.text()).toContain('模型消耗占比');expect(w.text()).toContain('12.34');expect(w.text()).toContain('真实模型');await w.get('[aria-label="展开真实模型分辨率明细"]').trigger('click');expect(w.text()).toContain('720p');expect(w.text()).toContain('420秒');expect(api.analytics.mock.calls.every(([q])=>q.userId==='owner')).toBe(true);expect((await axe(w.element,{rules:{'color-contrast':{enabled:false}}})).violations).toEqual([]);});
it('keeps missing accounting amounts unknown and exposes failures without invented data',async()=>{api.analytics.mockRejectedValue(Error('offline'));const w=mount(ConsumptionCharts,{props:{query:{to:'2026-09-19T15:59:59.999Z'},summary:{...report,amount4:null,unknownAmounts:1,models:[]}}});await flushPromises();expect(w.text()).toContain('—');expect(w.text()).toContain('统计读取失败');expect(w.find('[data-testid="share-segment"]').exists()).toBe(false);});
it('emits a model/date filter only after synchronization is enabled',async()=>{const w=mount(ConsumptionCharts,{props:{query:{to:'2026-09-19T15:59:59.999Z'},summary:report}});await flushPromises();await w.get('[aria-label="按真实模型筛选消费明细"]').trigger('click');expect(w.emitted('filter')).toBeUndefined();await w.get('[aria-label="同步到消费明细"]').setValue(true);await w.get('[aria-label="按真实模型筛选消费明细"]').trigger('click');expect(w.emitted('filter')?.at(-1)?.[0]).toMatchObject({model:'recorded-model'});});

it('retains custom ranking dates after synchronized parent filters update',async()=>{const w=mount(ConsumptionCharts,{props:{query:{to:'2026-09-19T15:59:59.999Z'},summary:report}});await flushPromises();await w.get('[aria-label="排行开始日期"]').setValue('2026-09-01');await w.get('[aria-label="同步到消费明细"]').setValue(true);expect(w.emitted('filter')?.at(-1)?.[0]).toMatchObject({from:'2026-09-01',to:'2026-09-19'});await w.setProps({query:{from:'2026-08-31T16:00:00.000Z',to:'2026-09-19T15:59:59.999Z',model:'recorded-model'}});await flushPromises();expect((w.get('[aria-label="排行开始日期"]').element as HTMLInputElement).value).toBe('2026-09-01');});

afterEach(()=>vi.useRealTimers());
it('distinguishes pending reports from empty results and exposes a scoped summary retry',async()=>{
 let resolve!: (value:unknown)=>void;
 api.analytics.mockReturnValue(new Promise(done=>{resolve=done;}));
 const w=mount(ConsumptionCharts,{props:{query:{to:'2026-09-19T15:59:59.999Z'},summary:null,summaryBusy:true}});
 expect(w.text()).toContain('正在统计模型消费');expect(w.text()).toContain('正在统计近 7 天消费趋势');expect(w.text()).toContain('正在统计模型排行');
 expect(w.text()).not.toContain('暂无模型消费');expect(w.text()).not.toContain('暂无消费记录');
 resolve({data:report});await flushPromises();await w.setProps({summaryBusy:false,summaryError:true});
 expect(w.text()).toContain('消费汇总读取失败或超时');
 await w.findAll('button').find(button=>button.text()==='重试模型占比')!.trigger('click');expect(w.emitted('retry-summary')).toHaveLength(1);
 await w.setProps({summary:report,summaryError:false});expect(w.findAllComponents({name:'BaseChart'})).toHaveLength(2);
});
it('clears old trend data while a new range loads and ignores its superseded response',async()=>{
 const w=mount(ConsumptionCharts,{props:{query:{to:'2026-09-19T15:59:59.999Z'},summary:report}});await flushPromises();
 let resolve!: (value:unknown)=>void;
 api.analytics.mockImplementationOnce(()=>new Promise(done=>{resolve=done;}));
 await w.get('[aria-label="消费趋势时间范围"]').setValue('30');expect(w.text()).toContain('正在统计近 30 天消费趋势');expect(w.find('.chart-accessible-data').exists()).toBe(false);
 await w.get('[aria-label="消费趋势时间范围"]').setValue('7');await flushPromises();
 resolve({data:{...report,days:[]}});await flushPromises();expect(w.get('.chart-accessible-data').text()).toContain('12.34');
});
it('keeps yesterday fixed to the Shanghai calendar day when parent filters synchronize',async()=>{const w=mount(ConsumptionCharts,{props:{query:{to:'2026-09-19T15:59:59.999Z'},summary:report}});await flushPromises();await w.get('[aria-label="同步到消费明细"]').setValue(true);await w.findAll('button').find(b=>b.text()==='昨天')!.trigger('click');await w.setProps({query:{to:'2026-09-18T15:59:59.999Z'}});await w.findAll('button').find(b=>b.text()==='昨天')!.trigger('click');expect(w.emitted('filter')?.at(-1)?.[0]).toMatchObject({from:'2026-09-18',to:'2026-09-18'});await w.findAll('button').find(b=>b.text()==='今天')!.trigger('click');expect(w.emitted('filter')?.at(-1)?.[0]).toMatchObject({from:'2026-09-19',to:'2026-09-19'});});
it('keeps known video amounts in ECharts and accessible data when another amount is unknown',async()=>{api.analytics.mockResolvedValue({data:{...report,days:[{date:'2026-09-19',calls:2,amount4:null,videoAmount4:'100.0000',imageAmount4:null}]}});const w=mount(ConsumptionCharts,{props:{query:{to:'2026-09-19T15:59:59.999Z'},summary:report}});await flushPromises();const trend=w.findAllComponents({name:'BaseChart'}).find(chart=>chart.props('label')==='每日消费趋势（元）')!;expect(trend.props('option').series.map((series:{data:unknown[]})=>series.data.at(-1))).toEqual([null,100,null]);expect(w.get('.chart-accessible-data').text()).toContain('100.00');});
