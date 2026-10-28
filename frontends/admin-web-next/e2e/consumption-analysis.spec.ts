import {test,expect} from '@playwright/test';
import {mkdir} from 'node:fs/promises';
import {mockGateway} from './gateway-fixture';

for(const width of [1440,390])test(`consumption analysis handles slow reports, retry and real chart geometry at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:1000});await mockGateway(page);
 const day=new Date(Date.now()+28800000).toISOString().slice(0,10);
 const report={total:10,amount4:'90.6000',unknownAmounts:0,models:[{model:'banana_pro',modelName:'banana_pro-2K',type:'IMAGE',amount4:'90.6000',calls:10,resolutions:[]}],days:[{date:day,amount4:'90.6000',videoAmount4:'0.0000',imageAmount4:'90.6000',calls:10}]};
 let release!:()=>void;const gate=new Promise<void>(done=>{release=done;});let ready=false;
 await page.route('**/api/v1/admin/billing/consumptions/analytics?*',async route=>{
  const url=new URL(route.request().url());const from=url.searchParams.get('from');
  const todayStart=new Date(day+'T00:00:00+08:00').toISOString();
  if(from===todayStart||ready)return route.fulfill({json:report});
  await gate;return route.fulfill({status:503,json:{message:'slow aggregation'}});
 });
 await page.goto('/admin/consumption');await page.getByRole('button',{name:'展开消费分析',exact:true}).click();
 await expect(page.getByText('正在统计模型消费，时间范围较大时可能需要更久…')).toBeVisible();
 await expect(page.getByText('正在统计近 7 天消费趋势…')).toBeVisible();
 await expect(page.getByText('banana_pro-2K',{exact:true})).toBeVisible();
 await expect(page.getByText('暂无模型消费',{exact:true})).toHaveCount(0);
 release();await expect(page.getByRole('button',{name:'重试模型占比'})).toBeVisible();
 ready=true;await page.getByRole('button',{name:'重试模型占比'}).click();
 const trend=page.locator('.consumption-chart-card').nth(1);await trend.getByRole('button',{name:'重试',exact:true}).click();
 await expect(page.locator('.share-chart canvas')).toBeVisible();await expect(trend.locator('canvas')).toBeVisible();
 const chart=await page.locator('.share-chart').boundingBox();const total=await page.locator('.share-chart-total').boundingBox();
 expect(chart!.width).toBeGreaterThanOrEqual(190);expect(Math.abs(chart!.x-total!.x)).toBeLessThan(2);expect(total!.height).toBe(chart!.height);
 await trend.getByText('查看趋势数据',{exact:true}).click();await expect(trend.locator('table')).toContainText('90.60');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await mkdir('.impeccable/review/consumption-analysis',{recursive:true});
 await page.locator('.consumption-charts').screenshot({path:`.impeccable/review/consumption-analysis/${width}.png`});
});
