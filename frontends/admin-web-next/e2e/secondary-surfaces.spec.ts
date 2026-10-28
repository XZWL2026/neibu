import {test,expect} from '@playwright/test';
import {mkdir,writeFile} from 'node:fs/promises';
import {secondaryGateway} from './secondary-fixture';

for(const width of [1440,390])test(`nested surfaces inventory ${width}`,async({page})=>{
 test.setTimeout(240_000);page.setDefaultTimeout(6000);await page.setViewportSize({width,height:width===390?740:900});const state=await secondaryGateway(page);
 const output='.impeccable/review/secondary-surfaces';await mkdir(output,{recursive:true});
 const report:any[]=[];const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 const button=(name:string)=>page.getByRole('button',{name,exact:true});
 async function snap(name:string,selector='dialog[open]'){
  const surface=page.locator(selector).last();await expect(surface).toBeVisible();
  await page.waitForLoadState('networkidle');
  const metrics=await surface.evaluate(el=>{
   const rect=el.getBoundingClientRect();const visible=(n:Element)=>{const r=n.getBoundingClientRect();return r.width>0&&r.height>0&&getComputedStyle(n).opacity!=='0'&&n.getAttribute('role')!=='switch';};
   return {width:rect.width,height:rect.height,viewport:[innerWidth,innerHeight],overflow:el.scrollWidth-el.clientWidth,
    badChoices:[...el.querySelectorAll('input[type=radio],input[type=checkbox]')].filter(visible).filter(n=>n.getBoundingClientRect().width>30||n.getBoundingClientRect().height>30).map(n=>n.getAttribute('aria-label')||n.outerHTML.slice(0,100)),
    outside:[...el.querySelectorAll('input,select,textarea,button')].filter(visible).filter(n=>{const r=n.getBoundingClientRect();return r.x<rect.x-2||r.right>rect.right+2;}).map(n=>n.getAttribute('aria-label')||n.textContent?.slice(0,30)),
    text:el.textContent?.slice(0,180)};
  });report.push({name,...metrics});await surface.screenshot({path:`${output}/${name}-${width}.png`});
  expect(metrics.overflow,`${name}: surface must not overflow horizontally`).toBeLessThanOrEqual(1);
  expect(metrics.badChoices,`${name}: visible choices retain native dimensions`).toEqual([]);
 }
 async function group(name:string,run:()=>Promise<void>){try{await run();}catch(e){report.push({name,error:String(e).slice(0,900)});}}
 await group('user',async()=>{
  await page.goto('/admin/users/user-1');await snap('user-detail','.user-detail-drawer');
  await button('修改用户名').click();await snap('user-rename','.user-detail-drawer');await page.getByRole('form',{name:'修改用户名'}).getByRole('button',{name:'取消',exact:true}).click();
  await page.locator('.identity-type').click();await snap('user-type','.user-type-dialog');await button('关闭用户类型选择').click();
  await button('调整余额').click();await snap('user-adjust','.user-detail-drawer');await page.locator('.reference-adjustment-actions').getByRole('button',{name:'取消',exact:true}).click();
  for(const name of ['充值记录','消费记录','生成记录','账户日志']){await page.getByRole('tab',{name,exact:true}).click();await snap('user-'+name,'.user-detail-drawer');}
 });
 await group('user-create',async()=>{await page.goto('/admin/users');await button('新增用户').click();await snap('user-create','.user-create-dialog');});
 await group('agent',async()=>{
  await page.goto('/admin/agents/user-1');await snap('agent-detail','.agent-detail-drawer');
  await button('调整').click();await snap('agent-rate','.agent-detail-drawer');await page.getByRole('form',{name:'调整收益比例'}).getByRole('button',{name:'取消',exact:true}).click();
  for(const name of ['充值记录','收益记录','提现记录','操作日志','消费记录']){await page.locator('.agent-record-tabs').getByRole('button',{name,exact:true}).click();await snap('agent-'+name,'.agent-detail-drawer');}
  await button('推广链接').click();await snap('agent-link','.agent-detail-drawer');await button('代理设置').click();await snap('agent-settings','.agent-detail-drawer');
  await button('设置登录账号').click();await snap('agent-login','.agent-dialog');
 });
 await group('agent-create',async()=>{await page.goto('/admin/agents');await button('新增代理').click();await snap('agent-create','.agent-dialog');});
 await group('api-create',async()=>{
  await page.goto('/admin/api-users');await button('新增 API 客户').click();await expect(page.getByRole('textbox',{name:'企业名称'})).toBeEnabled();await snap('api-create','.api-create-dialog');
  await page.locator('input[name="api-price-mode"][value="custom"]').click();await snap('api-create-prices','.api-create-dialog');
 });
 await group('api-detail',async()=>{
  await page.goto('/admin/api-users/api-1');await snap('api-detail','.api-detail-drawer');
  await button('额度记录').click();await snap('api-quota-history','.api-detail-drawer');await button('返回额度管理').click();
  for(const name of ['充值记录','使用数据','操作日志']){await page.getByRole('tab',{name,exact:true}).click();await snap('api-'+name,'.api-detail-drawer');}
  await page.getByLabel('客户更多操作').click();await button('停用客户').click();await snap('status-confirm','.status-action-dialog');await button('取消').click();
 });
 await group('benefit',async()=>{
  await page.goto('/admin/promotions/benefit-settings');await button('添加特殊代理').click();await page.getByRole('combobox',{name:'选择推广人',exact:true}).click();await page.getByRole('option').filter({hasText:'林舟'}).click();await expect(page.locator('.n-base-select-menu:visible')).toHaveCount(0);await snap('benefit-agent','.benefit-dialog[open]');await button('取消代理编辑').click();
  await button('添加特殊客户').click();await page.getByRole('combobox',{name:'选择客户',exact:true}).click();await page.getByRole('option').filter({hasText:'林舟客户'}).click();await expect(page.locator('.n-base-select-menu:visible')).toHaveCount(0);await snap('benefit-customer','.benefit-dialog[open]');
 });
 await group('finance',async()=>{for(const [path,name] of [['/admin/payments','order-detail'],['/admin/recharges/review','order-review']]){await page.goto(path);await page.locator('.finance-table').getByRole('button',{name:/查看|审核/}).first().click();await snap(name,'.finance-drawer');}});
 await group('online-recharge',async()=>{await page.goto('/admin/recharges/online');await button('详情').first().click();await snap('recharge-detail','.review-details');expect(await page.locator('.review-details dd').first().evaluate(el=>getComputedStyle(el).color)).toBe('rgb(32, 45, 67)');await page.locator('.review-details dl').evaluate(el=>el.scrollTop=el.scrollHeight);await expect(button('关闭充值详情')).toBeInViewport();});
 await group('corporate',async()=>{await page.goto('/admin/recharges/corporate');await button('确认收款').click();await snap('corporate-confirm','[aria-label="确认对公收款"]');await button('取消').click();await button('转账凭证.png').click();await snap('receipt-preview','.receipt-preview');});
 await group('consumption',async()=>{
  await page.goto('/admin/consumption/entry-1');await snap('consumption-detail','.consumption-record-detail');
  await page.getByRole('button',{name:/点击查看所有/}).click();await snap('consumption-prompt','.consumption-prompt-dialog');await page.locator('.consumption-prompt-dialog > p').evaluate(el=>el.scrollTop=el.scrollHeight);await expect(button('关闭完整提示词')).toBeInViewport();await button('关闭完整提示词').click();
  await page.getByRole('button',{name:/打开图片/}).first().click();await snap('consumption-image','.consumption-lightbox[open]');
 });
 await group('provider-log',async()=>{await page.goto('/admin/provider-logs?taskId=task-1');await snap('provider-log','.provider-log-detail');});
 await group('pricing',async()=>{await page.goto('/admin/pricing');await page.getByRole('button',{name:/编辑 .+/}).first().click();await snap('price-editor','.pricing-detail');});
 await group('models',async()=>{
  await page.goto('/admin/providers');await button('编辑模型').click();await snap('model-editor','.infra-detail');await button('关闭模型编辑').click();
  await button('删除模型').click();await snap('model-delete','[data-testid=model-delete-confirmation]');await page.getByTestId('model-delete-cancel').click();
  await button('新增模型').click();await snap('model-new','.infra-detail');
  await button('路由策略').click();await button('编辑供应商').click();await snap('provider-editor','[data-testid=routing-provider-form]');
  await button('腾讯增强').click();await button('编辑模板 hd').click();await snap('mps-template','.mps-editor');
  await page.locator('.mps-editor').getByText('高级设置',{exact:true}).click();await snap('mps-template-advanced','.mps-editor');
  await page.locator('.infra-tabs').getByRole('button',{name:'码率配置',exact:true}).click();await button('编辑码率 delivery').click();await snap('mps-bitrate','.mps-editor');
 });
 await group('income',async()=>{
  for(const mode of ['adjust','reject','pay','create']){
   state.noPayout=mode==='create';state.payoutStatus=mode==='pay'?'PENDING_PAYOUT':'WITHDRAWING';await page.goto('/admin/promotions/withdrawals');
   await page.getByRole('button',{name:'查看林舟收益'}).click();await snap('income-'+mode+'-detail','.income-drawer');
   expect(await page.locator('.income-drawer').evaluate(el=>el.matches(':modal'))).toBe(true);
   await button('关闭收益详情').focus();await page.keyboard.press('Tab');expect(await page.locator('.income-drawer').evaluate(el=>el.contains(document.activeElement))).toBe(true);
   if(mode==='adjust'){for(const name of ['消费明细','调整记录','发放记录']){await page.locator('.income-tabs').getByRole('button',{name,exact:true}).click();await snap('income-tab-'+name,'.income-drawer');}await page.locator('.income-tabs').getByRole('button',{name:'结算详情',exact:true}).click();}
   if(mode==='adjust')await page.getByTestId('open-adjustment').click();else await button({reject:'驳回',pay:'确认发放',create:'创建发放单'}[mode]!).click();
   await snap('income-'+mode,'.income-modal');
   await page.keyboard.press('Escape');await expect(page.locator('.income-modal')).toHaveCount(0);await expect(page.locator('.income-drawer')).toBeVisible();await page.keyboard.press('Escape');await expect(page.locator('.income-drawer')).toHaveCount(0);await expect(page.getByRole('button',{name:'查看林舟收益'})).toBeFocused();
  }
 });
 await group('password',async()=>{await page.goto('/admin/overview');await button('打开管理员菜单').click();await page.getByTestId('admin-change-password').click();await snap('password','.password-change-form');});
 await writeFile(`${output}/inventory-${width}.json`,JSON.stringify({surfaces:report,errors},null,2));
 expect(report.filter(r=>r.error)).toEqual([]);expect(errors).toEqual([]);
});
