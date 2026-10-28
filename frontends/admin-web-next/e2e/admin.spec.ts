import { test, expect } from '@playwright/test';
import { mockGateway } from './gateway-fixture';
import { mkdir } from 'node:fs/promises';
import { adminNavigation } from '../src/navigation';

test('desktop overview renders real ECharts canvas, has no overflow, and reuses scoped query cache',async({page})=>{
  const {calls}=await mockGateway(page);const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));
  await page.goto('/admin/overview');await expect(page.getByRole('heading',{name:'运营概览',exact:true})).toBeVisible();
  await expect(page.locator('.finance-trend-panel canvas')).toHaveCount(1);
  await expect(page.locator('.summary-amount').first()).toContainText('286450.78');
  await expect(page.locator('.native-model-card')).toHaveCount(4);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await mkdir('.impeccable/review',{recursive:true});await page.screenshot({path:'.impeccable/review/desktop.png',fullPage:true});
  const before=calls.filter(path=>path.startsWith('/api/v1/admin/billing/daily-finance')).length;
  await page.getByRole('link',{name:'用户管理',exact:true}).click();await expect(page.getByRole('heading',{name:'用户管理',exact:true})).toBeVisible();
  await page.getByRole('link',{name:'运营概览',exact:true}).click();await expect(page.locator('.finance-trend-panel canvas')).toHaveCount(1);
  expect(calls.filter(path=>path.startsWith('/api/v1/admin/billing/daily-finance')).length).toBe(before);
  await page.getByRole('button',{name:'刷新当前页面数据'}).click();await expect.poll(()=>calls.filter(path=>path.startsWith('/api/v1/admin/billing/daily-finance')).length).toBeGreaterThan(before);
  expect(errors).toEqual([]);
});

test('mobile navigation and overview remain usable without document overflow',async({page})=>{
  await page.setViewportSize({width:390,height:844});await mockGateway(page);await page.goto('/admin/overview');
  await expect(page.locator('.finance-trend-panel canvas')).toHaveCount(1);await expect(page.locator('.native-model-card')).toHaveCount(4);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await mkdir('.impeccable/review',{recursive:true});await page.screenshot({path:'.impeccable/review/mobile.png',fullPage:true});
  await page.getByRole('button',{name:'打开导航'}).click();await page.getByRole('link',{name:'用户管理',exact:true}).last().click();
  await expect(page.getByRole('heading',{name:'用户管理',exact:true})).toBeVisible();await expect(page.locator('.n-drawer')).toHaveCount(0);
});

test('paginated user table preserves columns, selection, details and responsive scroll',async({page})=>{
  await mockGateway(page);await page.goto('/admin/users');await expect(page.getByRole('button',{name:'查看用户 林舟',exact:true})).toBeVisible();
  await page.getByRole('checkbox',{name:'选择用户 林舟',exact:true}).check();await expect(page.locator('.selection-actions')).toContainText('已选择 1 项');
  await mkdir('.impeccable/review',{recursive:true});await page.screenshot({path:'.impeccable/review/users-desktop.png',fullPage:true});
  await page.getByRole('button',{name:'查看用户 林舟',exact:true}).click();await expect(page.locator('dialog[open]')).toBeVisible();
  for(const width of [1440,390]) {
    await page.setViewportSize({width,height:1000});await page.waitForLoadState('networkidle');
    await page.screenshot({path:`.impeccable/review/user-detail-${width}.png`,fullPage:true});
  }
  await page.getByRole('button',{name:'关闭用户详情'}).click();await expect(page).toHaveURL(/\/admin\/users$/);
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await page.screenshot({path:'.impeccable/review/users-mobile.png',fullPage:true});
});

test('ordinary administrators cannot navigate or mount primary-only pages',async({page})=>{
  const {calls}=await mockGateway(page,'normal_admin');await page.goto('/admin/pricing');await expect(page.getByRole('heading',{name:'无权限'})).toBeVisible();
  await expect(page.getByRole('link',{name:'模型定价',exact:true})).toHaveCount(0);await expect(page.getByRole('link',{name:'管理员',exact:true})).toHaveCount(0);
  expect(calls.some(path=>path.includes('/admin/billing/media-prices'))).toBe(false);
});

test('login, safe deep links and logout work without local credential storage',async({page})=>{
  await mockGateway(page,'admin',false);await page.goto('/admin/users');await expect(page).toHaveURL(/\/admin\/login/);
  await mkdir('.impeccable/review',{recursive:true});
  for(const width of [1440,390]) {
    await page.setViewportSize({width,height:1000});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
    await page.screenshot({path:`.impeccable/review/login-${width}.png`,fullPage:true});
  }
  await page.setViewportSize({width:1440,height:1000});
  await page.getByRole('textbox',{name:'用户名',exact:true}).fill('primary.admin');await page.locator('#login-password').fill('test-password');
  await page.getByRole('button',{name:'登录管理后台'}).click();await expect(page).toHaveURL(/\/admin\/users$/);
  expect(await page.evaluate(()=>Object.keys(localStorage).some(key=>/token|password|secret/i.test(key)))).toBe(false);
  // Create a protected history entry before logout; login redirects replace the original entry.
  await page.getByRole('link',{name:'运营概览',exact:true}).click();await expect(page).toHaveURL(/\/admin\/overview$/);
  await page.getByRole('button',{name:'打开管理员菜单'}).click();await page.getByTestId('admin-logout').click();await expect(page).toHaveURL(/\/admin\/login/);
  await page.goBack();await expect(page).toHaveURL(/\/admin\/login/);
});

test('all administrator workspaces share the new shell and fit desktop and mobile viewports',async({page})=>{
  test.setTimeout(120_000);
  await mockGateway(page);const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));
  await mkdir('.impeccable/review',{recursive:true});
  for(const width of [1440,390]) {
    await page.setViewportSize({width,height:1000});
    for(const item of adminNavigation) {
      await page.goto(item.to);await expect(page.locator('#main-content h1').first()).toBeVisible();
      await page.waitForLoadState('networkidle');
      await expect(page.locator('[data-testid="protected-content"]')).toBeVisible();
      if(item.to.endsWith('benefit-settings'))await expect(page.getByRole('heading',{name:'代理默认分佣比例',exact:true})).toBeVisible();
      if(item.to.endsWith('reconciliation'))await expect(page.getByText('暂无账实差异',{exact:true})).toBeVisible();
      await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),{message:`${item.to} width=${width}`}).toBe(true);
      const styles=await page.locator('#main-content h1').first().evaluate(el=>({color:getComputedStyle(el).color,size:getComputedStyle(el).fontSize}));
      expect(styles.color,`${item.to} heading color`).toBe('rgb(32, 45, 67)');
      expect(styles.size,`${item.to} heading size`).toBe(width===390?'24px':'28px');
      await page.screenshot({path:`.impeccable/review/${item.to.split('/').at(-1)}-${width}.png`,fullPage:true});
    }
  }
  expect(errors).toEqual([]);
});

test('detail, creation and rule dialogs preserve loaded controls at both sizes',async({page})=>{
  test.setTimeout(90_000);await mockGateway(page);await mkdir('.impeccable/review',{recursive:true});
  for(const width of [1440,390]) {
    await page.setViewportSize({width,height:1000});
    for(const [path,name,ready] of [
      ['/admin/agents/user-1','agent-detail','.agent-detail-profile'],
      ['/admin/api-users/api-1','api-detail','.api-balance']
    ]) {
      await page.goto(path!);await expect(page.locator(ready!)).toBeVisible();await page.waitForLoadState('networkidle');
      await expect(page.locator('dialog[open]')).toBeVisible();
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
      await page.screenshot({path:`.impeccable/review/${name}-${width}.png`,fullPage:true});
    }
    await page.goto('/admin/api-users');await page.getByRole('button',{name:'新增 API 客户',exact:true}).click();
    await expect(page.locator('.api-create-dialog[open]')).toBeVisible();
    await page.screenshot({path:`.impeccable/review/api-create-${width}.png`,fullPage:true});
    await page.goto('/admin/promotions/benefit-settings');await page.getByRole('button',{name:'添加特殊代理',exact:true}).click();
    await expect(page.locator('dialog[open]')).toBeVisible();
    await page.screenshot({path:`.impeccable/review/benefit-dialog-${width}.png`,fullPage:true});
    await page.goto('/admin/overview');await page.getByRole('button',{name:'打开管理员菜单'}).click();await page.getByTestId('admin-change-password').click();
    await expect(page.locator('.password-change-form')).toBeVisible();
    await page.screenshot({path:`.impeccable/review/password-${width}.png`,fullPage:true});
  }
});

test('overview all-time and custom ranges update finance, model statistics and daily lists together',async({page})=>{
  const {calls}=await mockGateway(page);await page.goto('/admin/overview');
  await expect(page.locator('.finance-trend-panel canvas')).toHaveCount(1);
  await page.locator('.overview-actions .n-select').click();await page.getByText('全部时间',{exact:true}).click();
  await expect(page.locator('.finance-trend-panel')).toContainText('全部日期');
  await expect.poll(()=>calls.some(path=>path.includes('/daily-finance?')&&path.includes('offset=30'))).toBe(true);
  const from=new Date(Date.now()+28800000-3*86400000).toISOString().slice(0,10),to=new Date(Date.now()+28800000).toISOString().slice(0,10);
  await page.locator('#native-from').fill(from);await page.locator('#native-to').fill(to);
  await page.getByRole('button',{name:'查询',exact:true}).click();
  await expect(page.locator('.finance-trend-panel')).toContainText(`${from} – ${to}`);
  await expect(page.getByTestId('finance-consumption-total')).toBeVisible();
  await expect.poll(()=>calls.some(path=>path.includes('/native-video-usage?')&&path.includes(`from=${from}`)&&path.includes(`to=${to}`))).toBe(true);
  await expect(page.locator('.native-error,.finance-error,.trend-error')).toHaveCount(0);
});
