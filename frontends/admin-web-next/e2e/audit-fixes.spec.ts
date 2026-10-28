import { test, expect } from '@playwright/test';
import { mockGateway } from './gateway-fixture';
import { mkdir } from 'node:fs/promises';

const output = '.impeccable/review/2026-10-14-fixes';

test('task-first workspaces and status confirmation fit desktop and mobile', async ({ page }) => {
  test.setTimeout(120_000);
  await mockGateway(page);
  let updates = 0;
  await page.route('**/api/v1/admin/users/*/status', route => { updates++; return route.fulfill({ json: {} }); });
  await mkdir(output, { recursive: true });
  for (const width of [1280, 390]) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
    await page.goto('/admin/users');
    await expect(page.getByRole('button', { name: '查看用户 林舟', exact: true })).toBeVisible();
    await expect(page.locator('.directory-statistics')).not.toHaveAttribute('open');
    await expect(page.locator('#user-advanced-filters')).toBeHidden();
    const table = await page.locator('.directory-list').boundingBox();
    expect(table!.y).toBeLessThan(width === 390 ? 650 : 600);
    await page.getByRole('button', { name: '更多筛选', exact: true }).click();
    await expect(page.getByRole('combobox', { name: '来源筛选' })).toBeVisible();
    await page.getByRole('button', { name: '收起筛选', exact: true }).click();
    await page.screenshot({ path: `${output}/users-${width}.png`, fullPage: true });
    await page.getByRole('checkbox', { name: '选择用户 林舟', exact: true }).check();
    await page.getByRole('combobox', { name: '批量操作' }).selectOption('disable');
    await expect(page.getByRole('dialog', { name: '确认停用 1 位用户' })).toBeVisible();
    expect(updates).toBe(width === 1280 ? 0 : 1);
    await page.screenshot({ path: `${output}/status-${width}.png`, fullPage: true });
    await page.getByRole('button', { name: '取消', exact: true }).click();
    await expect(page.locator('.status-action-dialog')).toHaveCount(0);
    await page.getByRole('combobox', { name: '批量操作' }).selectOption('disable');
    await page.getByRole('button', { name: '确认停用', exact: true }).click();
    await expect(page.locator('.status-action-result')).toContainText('成功 1 位，未完成 0 位');
    for (const path of ['/admin/consumption', '/admin/promotions/benefit-settings', '/admin/promotions/benefit-audit', '/admin/admins', '/admin/recharges/review']) {
      await page.goto(path);
      await expect(page.locator('#main-content h1').first()).toBeVisible();
      await page.waitForLoadState('networkidle');
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), path).toBe(true);
      if (path.endsWith('/consumption')) {
        await expect(page.locator('.consumption-charts')).toHaveCount(0);
        await page.getByRole('button', { name: '展开消费分析' }).click();
        await expect(page.locator('.consumption-charts')).toBeVisible();
        await page.getByRole('button', { name: '收起消费分析' }).click();
      }
      if (path.endsWith('benefit-settings')) {
        const agent = await page.getByTestId('rules-agent').boundingBox();
        const customer = await page.getByTestId('rules-customer').boundingBox();
        expect(customer!.y).toBeGreaterThanOrEqual(agent!.y + agent!.height);
      }
      if (path.endsWith('/admins')) await expect(page.getByTestId('credential-role')).toHaveValue('NORMAL_ADMIN');
      if (path.endsWith('/review')) await expect(page.getByText('活动赠送', { exact: true })).toHaveCount(0);
      await page.screenshot({ path: `${output}/${path.split('/').at(-1)}-${width}.png`, fullPage: true });
    }
  }
});

test('single-price save leaves other drafts unpublished in the browser', async ({ page }) => {
  await mockGateway(page);
  const makePrice = (resolution: string) => ({ model: 'audit-video', type: 'VIDEO', accountType: 'PERSONAL', resolution, unit: 'SECOND', yuanPerUnit: '1.0000', pointsPerUnit: null, enabled: true });
  let catalog = { managed: true, version: 1, prices: [makePrice('480p'), makePrice('720p')] };
  const writes: typeof catalog.prices[] = [];
  await page.route('**/api/v1/admin/billing/media-prices', async route => {
    if (route.request().method() === 'PUT') { const body = route.request().postDataJSON(); writes.push(body.prices); catalog = { ...catalog, version: catalog.version + 1, prices: body.prices }; }
    return route.fulfill({ json: catalog });
  });
  await page.goto('/admin/pricing');
  await page.getByRole('textbox', { name: 'audit-video 480p 金额（元）', exact: true }).fill('8');
  await page.getByRole('textbox', { name: 'audit-video 720p 金额（元）', exact: true }).click();
  await page.getByRole('textbox', { name: '编辑金额（元）', exact: true }).fill('2');
  await page.getByRole('button', { name: '保存修改 Enter', exact: true }).click();
  await expect(page.getByText('当前规格已保存并生效，其他未保存修改已保留。', { exact: true })).toBeVisible();
  expect(writes).toHaveLength(1);
  expect(writes[0].find(p => p.resolution === '480p')!.yuanPerUnit).toBe('1.0000');
  expect(writes[0].find(p => p.resolution === '720p')!.yuanPerUnit).toBe('2');
  await expect(page.getByRole('textbox', { name: 'audit-video 480p 金额（元）', exact: true })).toHaveValue('8');
  for (const width of [1280, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page.screenshot({ path: `${output}/pricing-${width}.png`, fullPage: true });
  }
});
