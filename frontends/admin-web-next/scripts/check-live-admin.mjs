import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const output = process.argv[2];
assert(output?.startsWith('.deployment/'));
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ channel: 'msedge', headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const errors = [], requests = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.hostname === 'aiyomio.cn') {
      requests.push({ path: url.pathname, status: response.status() });
      if (response.status() >= 400) errors.push(`${response.status()} ${url.pathname}`);
    }
  });
  await page.goto('https://aiyomio.cn/admin/users', { waitUntil: 'networkidle', timeout: 60000 });
  await page.getByRole('heading', { name: '登录管理后台', exact: true }).waitFor();
  assert(page.url().startsWith('https://aiyomio.cn/admin/login?redirect='));
  assert(requests.some(r => r.path === '/admin/api/v1/auth/session' && r.status === 200));
  assert(requests.some(r => r.path.startsWith('/admin-ui/assets/') && r.status === 200));
  assert(!requests.some(r => r.path.startsWith('/api/v1/')));
  await page.screenshot({ path: output + '/live-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 740 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.screenshot({ path: output + '/live-mobile.png', fullPage: true });
  assert.deepEqual(errors, []);
  const result = { status: 'passed', url: page.url(), requests, pageErrors: errors,
    checks: ['public HTTPS certificate', 'unauthenticated deep link redirect', 'production admin session endpoint', 'isolated static assets', 'desktop and mobile login rendering'],
    authenticatedBusinessFlowTested: false };
  await writeFile(output + '/public-verification.json', JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify(result));
} finally { await browser.close(); }
