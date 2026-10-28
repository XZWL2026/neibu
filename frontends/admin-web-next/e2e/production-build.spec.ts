import { test, expect } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname, relative, isAbsolute } from 'node:path';
import { mockGateway } from './gateway-fixture';

let server: Server, origin: string;
test.beforeAll(async () => {
  const root = resolve('dist');
  server = createServer(async (req, res) => {
    const path = new URL(req.url!, 'http://localhost').pathname;
    const file = path.startsWith('/admin/') ? resolve(root, 'index.html') : resolve(root, '.' + path);
    const child = relative(root, file);
    if (child.startsWith('..') || isAbsolute(child)) { res.writeHead(404).end(); return; }
    try {
      const data = await readFile(file);
      res.setHeader('Content-Type', ({ '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' } as Record<string,string>)[extname(file)] || 'application/octet-stream');
      res.end(data);
    } catch { res.writeHead(404).end(); }
  });
  await new Promise<void>(done => server.listen(0, '127.0.0.1', done));
  origin = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
});
test.afterAll(async () => { await new Promise<void>((done, reject) => server.close(e => e ? reject(e) : done())); });

test('production bundle uses isolated assets, admin session namespace and correct deep links', async ({ page }) => {
  const errors: string[] = [], api: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('response', r => { if (r.status() >= 400) errors.push(`${r.status()} ${r.url()}`); });
  page.on('request', r => { if (r.url().includes('/api/v1/')) api.push(new URL(r.url()).pathname); });
  await mockGateway(page, 'admin', false);
  await page.route('**/admin/api/v1/**', route => route.fallback({ url: route.request().url().replace('/admin/api/v1/', '/api/v1/') }));
  await page.goto(origin + '/admin/users');
  await expect(page.getByRole('heading', { name: '登录管理后台' })).toBeVisible();
  await expect(page).toHaveURL(/\/admin\/login\?redirect=/);
  await page.getByLabel('用户名', { exact: true }).fill('fixture.admin');
  await page.getByLabel('密码', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: '登录管理后台', exact: true }).click();
  await expect(page).toHaveURL(origin + '/admin/users');
  await expect(page.getByRole('button', { name: '查看用户 林舟', exact: true }).first()).toBeVisible();
  expect(api).toContain('/admin/api/v1/auth/session');
  expect(api).toContain('/admin/api/v1/auth/admin/login');
  expect(api.every(path => path.startsWith('/admin/api/v1/'))).toBe(true);
  expect(errors).toEqual([]);
});
