import {test,expect} from '@playwright/test';
import {mkdir} from 'node:fs/promises';
import {mockGateway} from './gateway-fixture';

test('new-user form keeps compact radios, readable fields and visible actions at desktop and narrow sizes',async({page})=>{
 test.setTimeout(90_000);await mockGateway(page);
 const writes:{path:string;body:any}[]=[];
 await page.route('**/api/v1/admin/users',async route=>{
  if(route.request().method()!=='POST')return route.fallback();
  writes.push({path:'user',body:route.request().postDataJSON()});return route.fulfill({json:{id:'created-user',phone:'+8613800000001',nickname:'用户0001',enabled:true}});
 });
 await page.route('**/api/v1/admin/payments/accounts/created-user',route=>{
  writes.push({path:'account',body:route.request().postDataJSON()});return route.fulfill({json:{}});
 });
 await mkdir('.impeccable/review/user-create',{recursive:true});
 for(const [width,height] of [[1440,900],[1280,760],[390,844],[390,640]]){
  await page.setViewportSize({width,height});await page.goto('/admin/users');
  const trigger=page.getByRole('button',{name:'新增用户',exact:true});await trigger.click();
  const dialog=page.getByRole('dialog',{name:'新增用户',exact:true});await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('textbox',{name:'手机号',exact:true})).toBeFocused();
  await expect(dialog.getByRole('button',{name:'创建用户',exact:true})).toBeDisabled();
  const phone=await dialog.locator('.user-create-phone input').boundingBox();const prefix=await dialog.getByRole('combobox',{name:'手机区号'}).boundingBox();
  expect(prefix!.width).toBeLessThan(100);expect(phone!.width).toBeGreaterThan(prefix!.width);
  for(const radio of await dialog.getByRole('radio').all()){
   const box=await radio.boundingBox();expect(box!.width).toBeLessThanOrEqual(20);expect(box!.height).toBeLessThanOrEqual(20);
  }
  await expect(dialog.getByRole('radio',{name:'个人用户',exact:true})).toBeChecked();
  await dialog.getByRole('radio',{name:'个人用户',exact:true}).focus();await page.keyboard.press('ArrowRight');
  await expect(dialog.getByRole('radio',{name:'企业用户',exact:true})).toBeChecked();
  await expect(dialog.getByRole('button',{name:'取消',exact:true})).toBeInViewport();
  await expect(dialog.getByRole('button',{name:'创建用户',exact:true})).toBeInViewport();
  expect(await dialog.evaluate(el=>el.scrollWidth<=el.clientWidth)).toBe(true);
  await dialog.locator('.user-create-form').evaluate(el=>{el.scrollTop=0;});
  await dialog.screenshot({path:`.impeccable/review/user-create/${width}-${height}.png`});
  await page.keyboard.press('Escape');await expect(dialog).not.toBeVisible();await expect(trigger).toBeFocused();
 }
 expect(writes).toHaveLength(0);
 await page.setViewportSize({width:1440,height:900});await page.getByRole('button',{name:'新增用户',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'新增用户',exact:true});
 await dialog.getByRole('textbox',{name:'手机号',exact:true}).fill('13800000001');
 await dialog.getByRole('radio',{name:'企业用户',exact:true}).check();
 await dialog.getByRole('combobox',{name:'注册来源'}).selectOption('ADMIN');
 await dialog.getByRole('textbox',{name:'用户备注'}).fill('创建表单回归');
 await dialog.getByRole('button',{name:'创建用户',exact:true}).click();await expect(dialog).not.toBeVisible();
 expect(writes).toHaveLength(2);expect(writes[0].body).toMatchObject({phone:'+8613800000001',source:'ADMIN',remark:'创建表单回归'});
 expect(writes[1].body).toMatchObject({accountType:'ENTERPRISE',createKey:true});
});

test('ordinary administrators only see permitted account options',async({page})=>{
 await mockGateway(page,'normal_admin');await page.goto('/admin/users');await page.getByRole('button',{name:'新增用户',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'新增用户',exact:true});await expect(dialog.getByRole('radio')).toHaveCount(2);
 await dialog.getByRole('radio',{name:'普通用户',exact:true}).check();await expect(dialog).toContainText('普通用户暂不生成 Key');
 await dialog.getByRole('button',{name:'取消',exact:true}).click();await expect(dialog).not.toBeVisible();
});
