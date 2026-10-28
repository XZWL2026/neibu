import {test,expect} from '@playwright/test';
import {mkdir} from 'node:fs/promises';
import {mockGateway} from './gateway-fixture';

const output='.impeccable/review/login';
test('login layout stays readable across desktop, tablet and small phones',async({page})=>{
 test.setTimeout(60_000);await mockGateway(page,'admin',false);await mkdir(output,{recursive:true});
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 for(const [width,height] of [[1440,900],[768,1024],[390,740],[320,568]]){
  await page.setViewportSize({width:width!,height:height!});await page.goto('/admin/login');
  await expect(page.getByRole('heading',{name:'登录管理后台'})).toBeVisible();
  await expect(page.getByLabel('用户名',{exact:true})).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth-innerWidth)).toBeLessThanOrEqual(1);
  await page.getByRole('button',{name:'登录管理后台',exact:true}).scrollIntoViewIfNeeded();
  await expect(page.getByRole('button',{name:'登录管理后台',exact:true})).toBeInViewport();
  await page.screenshot({path:`${output}/after-${width}.png`,fullPage:true});
 }
 expect(errors).toEqual([]);
});

test('login handles visibility, caps lock, pending, failed and successful authentication',async({page})=>{
 test.setTimeout(60_000);await mockGateway(page,'admin',false);await mkdir(output,{recursive:true});
 await page.setViewportSize({width:390,height:740});
 let attempts=0,release:()=>void=()=>{};
 await page.route('**/api/v1/auth/admin/login',async route=>{
  attempts++;
  if(attempts>1)return route.fallback();
  await new Promise<void>(resolve=>release=resolve);
  await route.fulfill({status:401,json:{message:'Invalid credentials'}});
 });
 await page.goto('/admin/login?logout=failed');
 await expect(page.getByText('退出请求未完成，当前页面已锁定，请重新登录。')).toBeVisible();
 await page.goto('/admin/login?redirect=%2Fadmin%2Fusers');
 const username=page.getByLabel('用户名',{exact:true}),password=page.getByLabel('密码',{exact:true}),submit=page.getByRole('button',{name:'登录管理后台',exact:true});
 await username.fill('demo.admin');await username.press('Tab');await expect(password).toBeFocused();
 await password.fill('fixture-password');await page.getByRole('button',{name:'显示密码',exact:true}).click();
 await expect(password).toHaveAttribute('type','text');expect(attempts).toBe(0);
 await password.focus();await password.dispatchEvent('keyup',{key:'A',modifierCapsLock:true});
 await expect(page.getByText('大写锁定已开启')).toBeVisible();
 await submit.click();await expect(submit).toBeDisabled();await expect(username).toBeDisabled();await expect(page.getByRole('button',{name:'隐藏密码',exact:true})).toBeDisabled();
 await page.screenshot({path:`${output}/pending-mobile.png`,fullPage:true});
 expect(attempts).toBe(1);release();
 await expect(page.getByRole('alert')).toContainText('登录失败');await expect(password).toBeFocused();await expect(password).toHaveValue('');await expect(password).toHaveAttribute('type','password');await expect(username).toHaveValue('demo.admin');
 await page.screenshot({path:`${output}/failed-mobile.png`,fullPage:true});
 await password.fill('fixture-password');await password.press('Enter');await expect(page).toHaveURL(/\/admin\/users$/);expect(attempts).toBe(2);
});
