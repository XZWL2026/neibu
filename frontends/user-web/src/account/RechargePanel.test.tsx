import '@testing-library/jest-dom/vitest';
import {act, cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {MemoryRouter} from 'react-router-dom';
import {AccountPage} from '../pages';
import {ApiError} from '../api';
import {RechargePanel,expectedPoints} from './RechargePanel';
import {UserFixtureProvider,UserAccountProvider} from '../userSession';
import {LocalPromotionProvider} from '../promotion/localPromotionViewState';
import {LocalInvitationProvider} from '../promotion/localInvitationProfile';
const api = vi.hoisted(() => ({options:vi.fn(),create:vi.fn(),get:vi.fn(),history:vi.fn(),session:vi.fn(),user:vi.fn(),keys:vi.fn(),balance:vi.fn(),prices:vi.fn()}));
vi.mock('../api', async original => ({...await original<object>(), paymentOptionsGetApiV1PaymentsRechargeOptions:api.options,
 paymentCreatePostApiV1PaymentsRecharges:api.create, paymentGetGetApiV1PaymentsRechargesId:api.get,
 paymentHistoryGetApiV1PaymentsRecharges:api.history, refreshSession:api.session, userSelfGetApiV1UsersMe:api.user, accessListGetApiV1AccessKeys:api.keys, billingBalanceGetApiV1BillingWalletsKeyId:api.balance,billingGetGetApiV1BillingModelPrices:api.prices}));
const options = {userId:'real-owner', accountType:null,firstRecharge:true,pointsPerYuan4:'12.3456',rules:[
 {accountType:'PERSONAL',minimumAmount4:'50.0000',presetAmounts4:['50.0000','200.0000','500.0000','1000.0000','10000.0000']},
 {accountType:'ENTERPRISE',minimumAmount4:'1000.0000',presetAmounts4:['1000.0000','2000.0000','5000.0000','10000.0000','50000.0000']},
 {accountType:'API',minimumAmount4:'5000.0000',presetAmounts4:['5000.0000','10000.0000','50000.0000','100000.0000']}]};
beforeEach(()=>{vi.resetAllMocks();sessionStorage.clear();api.session.mockResolvedValue({authenticated:true,user:{id:'real-owner'}});api.options.mockResolvedValue({data:options});api.history.mockResolvedValue({data:[]});api.prices.mockResolvedValue({data:{pointsPerYuan4:'12.3456',prices:[]}});});
afterEach(cleanup);
function page(){return render(<MemoryRouter><UserFixtureProvider><LocalPromotionProvider><LocalInvitationProvider><AccountPage page="recharge"/></LocalInvitationProvider></LocalPromotionProvider></UserFixtureProvider></MemoryRouter>);}
async function plans(apiOnly=false){
 const view=apiOnly?render(<MemoryRouter><RechargePanel apiOnly/></MemoryRouter>):page();
 await screen.findByLabelText('自定义充值金额（元）');return view;
}
function confirm(){fireEvent.click(screen.getByRole('button',{name:'下一步'}));}
function pay(apiOnly=false){if(!apiOnly)confirm();fireEvent.click(screen.getByRole('button',{name:apiOnly?'创建支付订单':'立即支付'}));}
const pending=(extra={})=>({orderId:'order-one',accountType:'PERSONAL',paymentMethod:'ALIPAY',status:'PENDING',fulfilled:false,amount4:'50.0000',expectedPoints4:'617.2800',rateSnapshot4:'12.3456',checkoutUrl:'https://pay.example.test/scan',expiresAt:'2099-01-01T00:00:00Z',...extra});

it('shows live personal and enterprise model prices without rebinding the recharge identity',async()=>{
 const video={type:'VIDEO',model:'seedance-2.0',resolution:'720p',unit:'SECOND'};
 api.prices.mockResolvedValue({data:{pointsPerYuan4:'12.3456',prices:[
  {...video,accountType:'PERSONAL',yuanPerUnit:'0.7234'},
  {...video,accountType:'PERSONAL',resolution:'1080p',yuanPerUnit:'1.8034'},
  {...video,accountType:'ENTERPRISE',yuanPerUnit:'0.6801'},
  {...video,accountType:'ENTERPRISE',resolution:'1080p',yuanPerUnit:'1.5000'},
  {type:'IMAGE',model:'live-image-model',resolution:'2k',unit:'IMAGE',accountType:'ENTERPRISE',yuanPerUnit:'2.9999'}
 ]}});
 await plans();expect(await screen.findByText('¥ 0.7234 / 秒')).toBeVisible();
 const open=screen.getByRole('button',{name:'查看全部模型价格'});open.focus();fireEvent.click(open);
 const dialog=await screen.findByRole('dialog',{name:'全部模型价格'});
 expect(within(dialog).getByText('¥ 1.8034')).toBeVisible();
 fireEvent.click(within(dialog).getByRole('button',{name:'企业价格'}));
 expect(within(dialog).getByText('¥ 0.6801')).toBeVisible();expect(within(dialog).queryByText('¥ 2.9999')).not.toBeInTheDocument();
 expect(within(dialog).getByText('¥ 1.50')).toBeVisible();
 expect(within(dialog).queryByText('单位：元 / 张')).not.toBeInTheDocument();expect(within(dialog).getByText('1 元 = 12.3456 积分')).toBeVisible();
 expect(within(dialog).queryByText('¥ 0.7234')).not.toBeInTheDocument();
 fireEvent.click(within(dialog).getByRole('button',{name:'返回充值'}));
 expect(screen.queryByRole('dialog')).not.toBeInTheDocument();expect(open).toHaveFocus();
 expect(screen.getByRole('button',{name:'个人充值'})).toHaveAttribute('aria-pressed','true');
 expect(api.create).not.toHaveBeenCalled();
});

it('keeps recharge available when model prices fail and retries only the price read',async()=>{
 api.prices.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({data:{pointsPerYuan4:'12.3456',prices:[]}});
 await plans();fireEvent.click(await screen.findByRole('button',{name:'重新加载价格'}));
 expect(await screen.findByText('暂无已启用的模型价格')).toBeVisible();
 expect(screen.getByRole('button',{name:'购买 ¥ 50 套餐'})).toBeEnabled();
 expect(api.prices).toHaveBeenCalledTimes(2);expect(api.create).not.toHaveBeenCalled();
});

it('compares each live identity price with the five fixed reference prices from the supplied image',async()=>{
 const examples=[
  ['seedance-2.0','720p','1.00','0.7234','27.7%','0.6800','32%'],
  ['seedance-2.0','1080p','2.50','1.8000','28%','1.5000','40%'],
  ['seedance-2.0','4k','5.00','3.9800','20.4%','3.6800','26.4%'],
  ['seedance-2.5','720p','1.55','1.2000','22.6%','0.9900','36.1%'],
  ['seedance-2.5','1080p','3.80','2.8000','26.3%','2.5000','34.2%']
 ];
 const excluded=[
  ['seedance-2.0-mini','720p','VIDEO','SECOND'],['seedance-2.0-fast','720p','VIDEO','SECOND'],
  ['seedance-2.0','480p','VIDEO','SECOND'],['seedance-2.5','4k','VIDEO','SECOND'],
  ['another-video-model','720p','VIDEO','SECOND'],['seedance-2.0','720p','IMAGE','IMAGE'],
  ['seedance-2.0','720p','VIDEO','IMAGE']
 ].flatMap(([model,resolution,type,unit])=>['PERSONAL','ENTERPRISE'].map(accountType=>({model,resolution,type,unit,accountType,yuanPerUnit:'0.1111'})));
 api.prices.mockResolvedValue({data:{pointsPerYuan4:'10.0000',prices:[...excluded,...examples.flatMap(([model,resolution,,personal,,enterprise])=>[
  {type:'VIDEO',unit:'SECOND',model,resolution,accountType:'PERSONAL',yuanPerUnit:personal},
  {type:'VIDEO',unit:'SECOND',model,resolution,accountType:'ENTERPRISE',yuanPerUnit:enterprise}
 ])]}});
 await plans();const open=screen.getByRole('button',{name:'查看全部模型价格'});await waitFor(()=>expect(open).toBeEnabled());
 const preview=within(screen.getByRole('region',{name:'模型价格'}));
 expect(preview.getAllByRole('rowheader').map(cell=>cell.textContent)).toEqual(['seedance-2.0','seedance-2.5']);
 expect(preview.getAllByRole('columnheader').map(cell=>cell.textContent)).toEqual(['模型','720P','1080P','4K']);
 expect(preview.queryByText(/0\.1111/)).not.toBeInTheDocument();fireEvent.click(open);
 const dialog=within(screen.getByRole('dialog',{name:'全部模型价格'}));
 expect(dialog.getByRole('columnheader',{name:'官方参考价'})).toBeVisible();
 for(const [identity,priceIndex,savingIndex] of [['个人价格',3,4],['企业价格',5,6]] as const){
  fireEvent.click(dialog.getByRole('button',{name:identity}));
  expect(dialog.getAllByRole('row')).toHaveLength(6);
  expect(dialog.queryByText(/0\.1111/)).not.toBeInTheDocument();
  expect(dialog.queryByRole('region',{name:'图片模型价格'})).not.toBeInTheDocument();
  for(const example of examples){
   const display=example[priceIndex].replace(/0+$/,'').replace(/\.$/,'');
   const [whole,fraction='']=display.split('.');
   const priceCell=dialog.getByText(`¥ ${whole}.${fraction.padEnd(2,'0')}`,{selector:'.model-price-value'});
   const row=within(priceCell.closest('tr')!);
   expect(row.getByText(`¥ ${example[2]}`).tagName).toBe('DEL');
   expect(row.getByText(`省 ${example[savingIndex]}`)).toBeVisible();
  }
 }
 expect(api.create).not.toHaveBeenCalled();
});

it('hides unsupported prices and handles zero and tiny discounts exactly',async()=>{
 const base={type:'VIDEO',unit:'SECOND',accountType:'PERSONAL',model:'seedance-2.0',resolution:'720p'};
 api.prices.mockResolvedValue({data:{pointsPerYuan4:'10.0000',prices:[
  {...base,yuanPerUnit:'1.0000'},
  {...base,resolution:'1080p',yuanPerUnit:'3.0000'},
  {...base,resolution:'4K',yuanPerUnit:'0.0000'},
  {...base,model:'seedance-2.5',yuanPerUnit:'1.5490'},
  {...base,model:'seedance-2.5',resolution:'1080p',yuanPerUnit:null},
  {...base,model:'seedance-2.0-mini',yuanPerUnit:'0.5000'},
  {...base,model:'seedance-2.0-fast',yuanPerUnit:'0.5000'},
  {...base,resolution:'480p',yuanPerUnit:'0.5000'},
  {...base,model:'seedance-2.5',resolution:'4k',yuanPerUnit:'0.5000'},
  {...base,type:'IMAGE',unit:'IMAGE',yuanPerUnit:'0.5000'}
 ]}});
 await plans();const open=screen.getByRole('button',{name:'查看全部模型价格'});await waitFor(()=>expect(open).toBeEnabled());fireEvent.click(open);
 const dialog=within(screen.getByRole('dialog',{name:'全部模型价格'}));
 expect(dialog.getAllByText(/^省 /).map(item=>item.textContent).sort()).toEqual(['省 100%','省 <0.1%'].sort());
 expect(dialog.queryAllByRole('cell',{name:'¥ 0.50'})).toHaveLength(0);
 expect(dialog.getByRole('cell',{name:'¥ 3.00'}).closest('tr')).toHaveTextContent('—');
});

it('starts with real recharge tiers and confirms the selected plan before creating an order',async()=>{
 page();expect(await screen.findByRole('heading',{name:'充值中心'})).toBeVisible();
 expect(screen.getAllByRole('article')).toHaveLength(options.rules[0].presetAmounts4.length);
 expect(screen.getByRole('button',{name:'个人充值'})).toHaveAttribute('aria-pressed','true');
 fireEvent.click(screen.getByRole('button',{name:'购买 ¥ 200 套餐'}));
 expect(screen.getByRole('heading',{name:'确认充值'})).toBeVisible();expect(api.create).not.toHaveBeenCalled();
 expect(screen.queryByText('银行卡支付')).not.toBeInTheDocument();expect(screen.queryByText(/USDT/)).not.toBeInTheDocument();
 api.create.mockResolvedValue({data:pending({amount4:'200.0000',expectedPoints4:'2469.1200'})});
 fireEvent.click(screen.getByRole('button',{name:'立即支付'}));
 expect(await screen.findByRole('img',{name:'收钱吧支付二维码'})).toBeVisible();
 expect(api.create).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({amount4:'200.00',paymentMethod:'ALIPAY'}));
 expect(screen.queryByRole('button',{name:'取消订单'})).not.toBeInTheDocument();
});
it('uses server amounts and exact points, allows enterprise selection and validates cents and minimum',async()=>{
 await plans();expect(screen.getByRole('button',{name:'购买 ¥ 50 套餐'})).toBeVisible();
 expect(screen.getAllByText('617.28 积分')[0]).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'企业充值'}));
 expect(screen.queryByRole('button',{name:'购买 ¥ 50 套餐'})).not.toBeInTheDocument();
 expect(screen.getByRole('button',{name:'购买 ¥ 2000 套餐'})).toBeEnabled();
 expect(screen.queryByLabelText('企业名称')).not.toBeInTheDocument();
 const input=screen.getByLabelText('自定义充值金额（元）');
 for(const amount of ['999.99','1000.001']){fireEvent.change(input,{target:{value:amount}});expect(screen.getByRole('button',{name:'下一步'})).toBeDisabled();}
 fireEvent.change(input,{target:{value:'1000.00'}});api.create.mockResolvedValue({data:pending({accountType:'ENTERPRISE',amount4:'1000.0000'})});pay();
 await screen.findByText('等待支付');expect(api.create).toHaveBeenCalledWith(expect.objectContaining({accountType:'ENTERPRISE',amount4:'1000.00'}));
});
it('allows an existing personal account to choose enterprise without resetting first recharge',async()=>{
 api.options.mockResolvedValue({data:{...options,accountType:'PERSONAL',firstRecharge:false,rules:[{accountType:'PERSONAL',minimumAmount4:'200.0000',presetAmounts4:['200.0000','500.0000']}]}});
 await plans();expect(screen.getByRole('button',{name:'企业充值'})).toBeEnabled();expect(screen.queryByRole('button',{name:'购买 ¥ 50 套餐'})).not.toBeInTheDocument();
 const input=screen.getByLabelText('自定义充值金额（元）');expect(input).not.toHaveAttribute('max');
 fireEvent.change(input,{target:{value:'199.99'}});expect(screen.getByRole('button',{name:'下一步'})).toBeDisabled();
});
it.each(['confirm','uncertain','pending','expired'])('closes the payment card and returns to amount selection from %s',async(state)=>{
 await plans();fireEvent.change(screen.getByLabelText('自定义充值金额（元）'),{target:{value:'200.00'}});confirm();
 if(state!=='confirm'){
  if(state==='uncertain')api.create.mockRejectedValueOnce(new TypeError('lost response'));
  else api.create.mockResolvedValueOnce({data:pending({amount4:'200.0000',checkoutUrl:null,status:state==='expired'?'EXPIRED':'PENDING'})});
  fireEvent.click(screen.getByRole('button',{name:'立即支付'}));
  if(state!=='expired')await screen.findByRole('button',{name:'重试本次订单'});
 }
 const close=screen.getByRole('button',{name:'关闭支付方式'});await waitFor(()=>expect(close).toBeEnabled());
 const calls=api.create.mock.calls.length;fireEvent.click(close);
 expect(screen.getByRole('heading',{name:'充值中心'})).toBeVisible();
 expect(screen.queryByRole('heading',{name:'确认充值'})).not.toBeInTheDocument();
 expect(screen.getByLabelText('自定义充值金额（元）')).toHaveValue(200);
 expect(screen.getByLabelText('自定义充值金额（元）')).toHaveFocus();
 expect(sessionStorage.getItem('xzwl-recharge:real-owner:account')).toBeNull();
 expect(api.create).toHaveBeenCalledTimes(calls);
 if(state!=='confirm'){
  api.create.mockRejectedValueOnce(new TypeError('lost response'));
  fireEvent.change(screen.getByLabelText('自定义充值金额（元）'),{target:{value:'500.00'}});pay();
  await screen.findByRole('button',{name:'重试本次订单'});
  expect(api.create.mock.calls[1][0].amount4).toBe('500.00');
  expect(api.create.mock.calls[1][0].requestId).not.toBe(api.create.mock.calls[0][0].requestId);
 }
});
it('disables the close action while submitting the payment request',async()=>{
 let finish!:(value:{data:ReturnType<typeof pending>})=>void;
 api.create.mockReturnValue(new Promise(resolve=>{finish=resolve;}));
 await plans();pay();const close=screen.getByRole('button',{name:'关闭支付方式'});
 expect(close).toBeDisabled();fireEvent.click(close);
 expect(screen.getByRole('heading',{name:'确认充值'})).toBeVisible();
 await act(async()=>{finish({data:pending({checkoutUrl:null})});});
 await waitFor(()=>expect(close).toBeEnabled());fireEvent.click(close);
 expect(screen.getByRole('heading',{name:'充值中心'})).toBeVisible();
 expect(api.create).toHaveBeenCalledTimes(1);
});
it.each([false,true])('preserves the selected payment method and request on uncertain retry (API=%s)',async(apiOnly)=>{
 api.create.mockRejectedValue(new TypeError('lost response'));const view=await plans(apiOnly);if(!apiOnly)confirm();
 fireEvent.click(screen.getByRole('button',{name:'微信'}));fireEvent.click(screen.getByRole('button',{name:apiOnly?'创建支付订单':'立即支付'}));
 await screen.findByRole('button',{name:'重试本次订单'});const original=api.create.mock.calls[0][0];expect(original.paymentMethod).toBe('WECHAT');
 expect(screen.getByRole('button',{name:'支付宝'})).toBeDisabled();
 if(!apiOnly)expect(screen.queryByRole('button',{name:'返回充值中心'})).not.toBeInTheDocument();
 view.unmount();render(<MemoryRouter><RechargePanel apiOnly={apiOnly}/></MemoryRouter>);
 await screen.findByRole('button',{name:'重试本次订单'});expect(screen.getByRole('button',{name:'微信'})).toHaveAttribute('aria-pressed','true');
 fireEvent.click(screen.getByRole('button',{name:'重试本次订单'}));await waitFor(()=>expect(api.create).toHaveBeenCalledTimes(2));expect(api.create.mock.calls[1][0]).toEqual(original);
});
it.each([false,true])('returns from a known pending order and creates a new request only after confirmation (API=%s)',async(apiOnly)=>{
 const order=pending({accountType:apiOnly?'API':'PERSONAL',amount4:apiOnly?'5000.0000':'50.0000'});
 api.create.mockResolvedValueOnce({data:order}).mockRejectedValueOnce(new TypeError('lost response'));api.get.mockResolvedValue({data:order});
 await plans(apiOnly);pay(apiOnly);const back=await screen.findByRole('button',{name:apiOnly?'返回选择金额':'返回充值中心'});await waitFor(()=>expect(back).toBeEnabled());fireEvent.click(back);
 expect(screen.queryByRole('img',{name:'收钱吧支付二维码'})).not.toBeInTheDocument();expect(screen.getByLabelText('自定义充值金额（元）')).toHaveFocus();
 expect(sessionStorage.getItem(`xzwl-recharge:real-owner:${apiOnly?'api':'account'}`)).toBeNull();
 fireEvent.change(screen.getByLabelText('自定义充值金额（元）'),{target:{value:'10000.01'}});pay(apiOnly);await screen.findByRole('button',{name:'重试本次订单'});
 expect(api.create.mock.calls[1][0].amount4).toBe('10000.01');expect(api.create.mock.calls[1][0].requestId).not.toBe(api.create.mock.calls[0][0].requestId);
});
it('shows processing after payment and success only after fulfillment, then returns to the account without inventing a balance',async()=>{
 const paid=pending({status:'PAID',amount4:'200.0100',expectedPoints4:'2469.2434'});api.create.mockResolvedValue({data:paid});api.get.mockResolvedValue({data:paid});
 await plans();pay();await screen.findByRole('heading',{name:'已付款，正在入账'});expect(screen.queryByRole('heading',{name:'支付成功'})).not.toBeInTheDocument();
 api.get.mockResolvedValue({data:{...paid,fulfilled:true}});fireEvent.focus(window);
 expect(await screen.findByRole('heading',{name:'支付成功'})).toBeVisible();expect(screen.getByText('已充值 ¥ 200.01')).toBeVisible();expect(screen.getByText('+ 2469.2434 积分')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'返回账户'}));expect(screen.getByRole('heading',{name:'开始下一次创作'})).toBeVisible();
 expect(screen.queryByText('1280')).not.toBeInTheDocument();
 fireEvent.focus(window);await waitFor(()=>expect(api.options).toHaveBeenCalledTimes(3));expect(screen.queryByRole('heading',{name:'支付成功'})).not.toBeInTheDocument();
});
it('confirms polling fulfillment without a manual paid button and stops polling afterwards',async()=>{
 api.create.mockResolvedValue({data:pending()});api.get.mockResolvedValue({data:pending({status:'PAID',fulfilled:true})});
 await plans();confirm();vi.useFakeTimers();try{await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'立即支付'}));});
 expect(screen.queryByRole('heading',{name:'支付成功'})).not.toBeInTheDocument();
 await act(async()=>{await vi.advanceTimersByTimeAsync(3000);});expect(screen.getByRole('heading',{name:'支付成功'})).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'返回账户'}));await act(async()=>{await vi.advanceTimersByTimeAsync(6000);});expect(api.get).toHaveBeenCalledTimes(1);
 }finally{vi.useRealTimers();}
});
it('restores tracked paid orders once and does not announce unrelated paid history',async()=>{
 const paid=pending({status:'PAID',fulfilled:true});api.history.mockResolvedValue({data:[paid]});api.get.mockResolvedValue({data:paid});
 let view=await plans();expect(screen.queryByRole('heading',{name:'支付成功'})).not.toBeInTheDocument();view.unmount();
 sessionStorage.setItem('xzwl-recharge:real-owner:account',JSON.stringify({requestId:'restored',orderId:paid.orderId,amount4:'50.00',accountType:'PERSONAL'}));
 view=page();expect(await screen.findByRole('heading',{name:'支付成功'})).toBeVisible();expect(sessionStorage.getItem('xzwl-recharge:real-owner:account')).toBeNull();
 fireEvent.click(screen.getByRole('button',{name:'查看订单'}));expect(screen.getByRole('heading',{name:'订单记录'})).toBeVisible();expect(screen.getByRole('table')).toHaveTextContent('order-one');
 view.unmount();await plans();expect(screen.queryByRole('heading',{name:'支付成功'})).not.toBeInTheDocument();
});
it.each(['weixin://wxpay/bizpayurl?pr=fixture','weixin://wxpay/bizpayurl/up?pr=fixture'])('renders supported native WeChat URLs as QR only: %s',async(checkoutUrl)=>{
 api.create.mockResolvedValue({data:pending({paymentMethod:'WECHAT',checkoutUrl})});await plans();pay();expect(await screen.findByRole('img',{name:'收钱吧支付二维码'})).toBeVisible();
 expect(screen.getByText('微信扫码付款')).toBeVisible();expect(document.querySelector('a[href^="weixin:"]')).toBeNull();
});
it.each(['javascript:alert(1)','https://user:secret@pay.example/scan'])('does not render an unsafe checkout URL: %s',async(checkoutUrl)=>{
 api.create.mockResolvedValue({data:pending({checkoutUrl})});await plans();pay();await screen.findByRole('button',{name:'重试本次订单'});expect(screen.queryByRole('img',{name:'收钱吧支付二维码'})).not.toBeInTheDocument();
});
it('uses actual expiration without pretending that a countdown means payment or server expiration',async()=>{
 api.create.mockResolvedValue({data:pending({expiresAt:new Date(Date.now()-1000).toISOString()})});api.get.mockResolvedValue({data:pending({status:'EXPIRED'})});
 await plans();pay();await screen.findByText('支付时间已结束，正在确认订单状态');expect(screen.getByRole('img',{name:'收钱吧支付二维码'})).toBeVisible();
 fireEvent.focus(window);await screen.findByText('订单已过期');expect(screen.queryByRole('img',{name:'收钱吧支付二维码'})).not.toBeInTheDocument();expect(api.create).toHaveBeenCalledTimes(1);
});
it('keeps legacy uncertain payment methods unspecified and restores enterprise details',async()=>{
 const legacy={requestId:'legacy',amount4:'1000.0000',accountType:'ENTERPRISE',enterpriseName:'本人企业'};sessionStorage.setItem('xzwl-recharge:real-owner:account',JSON.stringify(legacy));api.create.mockRejectedValue(new TypeError('lost'));
 page();await screen.findByRole('button',{name:'重试本次订单'});for(const name of ['支付宝','微信'])expect(screen.getByRole('button',{name})).toHaveAttribute('aria-pressed','false');
 fireEvent.click(screen.getByRole('button',{name:'重试本次订单'}));await waitFor(()=>expect(api.create).toHaveBeenCalledWith(legacy));
});
it('keeps the in-memory request retryable when storage is unavailable and a later retry is denied',async()=>{
 const write=vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('blocked');});
 api.create.mockRejectedValueOnce(new TypeError('lost')).mockRejectedValueOnce(new ApiError(new Response(null,{status:403}),{})).mockRejectedValue(new TypeError('lost'));
 try{await plans();pay();await screen.findByRole('button',{name:'重试本次订单'});const original=api.create.mock.calls[0][0];
 fireEvent.focus(window);await waitFor(()=>expect(api.options).toHaveBeenCalledTimes(2));
 fireEvent.click(screen.getByRole('button',{name:'重试本次订单'}));await screen.findByText('登录已失效或当前账户没有充值权限。');expect(screen.queryByRole('button',{name:'返回充值中心'})).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'重试本次订单'}));await waitFor(()=>expect(api.create).toHaveBeenCalledTimes(3));expect(api.create.mock.calls[2][0]).toEqual(original);
 }finally{write.mockRestore();}
});
it.each([[false,false],[false,true],[true,false],[true,true]])('unlocks a definitive amount-limit failure (API=%s, restored order=%s)',async(apiOnly,knownOrder)=>{
 const accountType=apiOnly?'API':'PERSONAL',amount4=apiOnly?'5000.00':'200.00';const original={requestId:'limit-request',accountType,amount4};const key=`xzwl-recharge:real-owner:${apiOnly?'api':'account'}`;
 sessionStorage.setItem(key,JSON.stringify({...original,...(knownOrder?{orderId:'limit-order'}:{})}));api.get.mockResolvedValue({data:pending({orderId:'limit-order',accountType,amount4,checkoutUrl:null})});
 api.create.mockRejectedValueOnce(new ApiError(new Response(null,{status:400}),{code:'PAYMENT_CHANNEL_AMOUNT_LIMIT'})).mockRejectedValue(new TypeError('lost'));
 render(<MemoryRouter><RechargePanel apiOnly={apiOnly}/></MemoryRouter>);await screen.findByRole('button',{name:'重试本次订单'});fireEvent.click(screen.getByRole('button',{name:'重试本次订单'}));await screen.findByRole('alert');
 expect(api.create).toHaveBeenCalledExactlyOnceWith(original);expect(sessionStorage.getItem(key)).toBeNull();expect(screen.getByLabelText('自定义充值金额（元）')).toBeEnabled();
 fireEvent.change(screen.getByLabelText('自定义充值金额（元）'),{target:{value:'10001.00'}});pay(apiOnly);await waitFor(()=>expect(api.create).toHaveBeenCalledTimes(2));expect(api.create.mock.calls[1][0].requestId).not.toBe(original.requestId);
});
it('returns definitive validation errors to editable plan selection',async()=>{
 api.create.mockRejectedValue(new ApiError(new Response(null,{status:400}),{code:'INVALID_AMOUNT'}));await plans();pay();await screen.findByRole('alert');expect(screen.getByLabelText('自定义充值金额（元）')).toBeEnabled();expect(sessionStorage.getItem('xzwl-recharge:real-owner:account')).toBeNull();
});
it('uses the API server minimum and preserves the compact API flow',async()=>{
 await plans(true);expect(screen.queryByRole('heading',{name:'为你的创意充值'})).not.toBeInTheDocument();expect(screen.getByRole('button',{name:'¥ 5000'})).toBeVisible();
 fireEvent.change(screen.getByLabelText('自定义充值金额（元）'),{target:{value:'4999.99'}});expect(screen.getByRole('button',{name:'创建支付订单'})).toBeDisabled();
});
it('keeps four-place expected points exact without floating rounding',()=>{
 expect(expectedPoints('50.00','12.3456')).toBe('617.2800');expect(expectedPoints('200.01','0.1234')).toBe('24.6812');expect(expectedPoints('10000000000000.01','10.0000')).toBe('100000000000000.1000');expect(expectedPoints('50.001','10.0000')).toBeNull();
});
it('does not render account data or plans for an unauthenticated visitor',async()=>{
 api.session.mockResolvedValue({authenticated:false,user:null});page();expect(await screen.findByRole('link',{name:'登录'})).toBeVisible();expect(api.options).not.toHaveBeenCalled();expect(screen.queryByRole('button',{name:'充值'})).not.toBeInTheDocument();
});

it('allows returning from a known pending order even when its QR URL is unavailable',async()=>{
 api.create.mockResolvedValue({data:pending({checkoutUrl:null})});await plans();pay();await screen.findByRole('button',{name:'重试本次订单'});
 const back=screen.getByRole('button',{name:'返回充值中心'});await waitFor(()=>expect(back).toBeEnabled());fireEvent.click(back);
 expect(screen.getByRole('heading',{name:'充值中心'})).toBeVisible();expect(sessionStorage.getItem('xzwl-recharge:real-owner:account')).toBeNull();expect(api.create).toHaveBeenCalledTimes(1);
});


it.each([null,'100.0000'])('window focus refreshes the real balance after initial %s',async(initial)=>{
 api.user.mockResolvedValue({data:{id:'real-owner',enabled:true}});
 api.options.mockResolvedValue({data:{...options,accountType:'PERSONAL'}});
 api.keys.mockResolvedValue({data:[{keyId:'real-wallet',ownerId:'real-owner',enabled:true,kind:'MEDIA',accountType:'PERSONAL',firstRechargeOrderId:'first-order'}]});
 api.get.mockResolvedValue({data:{...pending(),userId:'real-owner',orderId:'first-order',status:'PAID',fulfilled:true}});
 if(initial===null)api.balance.mockRejectedValueOnce(new Error('balance unavailable'));
 else api.balance.mockResolvedValueOnce({data:{walletId:'real-wallet',availablePoints4:initial}});
 api.balance.mockResolvedValue({data:{walletId:'real-wallet',availablePoints4:'150.0000'}});
 render(<MemoryRouter><UserAccountProvider><RechargePanel/></UserAccountProvider></MemoryRouter>);
 await waitFor(()=>expect(api.balance).toHaveBeenCalledTimes(1));
 await waitFor(()=>expect(screen.getByText('当前余额').parentElement).toHaveTextContent(initial===null?'—':'100'));
 fireEvent.focus(window);
 await waitFor(()=>expect(screen.getByText('当前余额').parentElement).toHaveTextContent('150'));
 expect(api.balance).toHaveBeenCalledTimes(2);expect(api.create).not.toHaveBeenCalled();
});

it.each([null,'12345.6789'])('shows only the real available balance on payment success: %s',async(balance)=>{
 api.user.mockResolvedValue({data:{id:'real-owner',enabled:true}});
 api.options.mockResolvedValue({data:{...options,accountType:'PERSONAL'}});
 api.keys.mockResolvedValue({data:[{keyId:'real-wallet',ownerId:'real-owner',enabled:true,kind:'MEDIA',accountType:'PERSONAL',firstRechargeOrderId:'first-order'}]});
 const paid=pending({userId:'real-owner',orderId:'paid-order',status:'PAID',fulfilled:true});
 api.get.mockImplementation(async(id:string)=>({data:{...paid,orderId:id}}));api.create.mockResolvedValue({data:paid});
 if(balance===null)api.balance.mockRejectedValue(new Error('unavailable'));
 else api.balance.mockResolvedValue({data:{walletId:'real-wallet',availablePoints4:balance}});
 render(<MemoryRouter><UserAccountProvider><RechargePanel/></UserAccountProvider></MemoryRouter>);
 await screen.findByLabelText('自定义充值金额（元）');await waitFor(()=>expect(screen.getByRole('button',{name:'下一步'})).toBeEnabled());pay();
 await screen.findByRole('heading',{name:'支付成功'});
 await waitFor(()=>expect(screen.getByLabelText('支付后可用余额')).toHaveTextContent(balance??'—'));
 expect(screen.getByText('+ 617.28 积分')).toBeVisible();
 expect(screen.queryByRole('button',{name:/演示支付/})).not.toBeInTheDocument();
});

it('retains enterprise order selection when refreshing a personal account',async()=>{
 api.options.mockResolvedValue({data:{...options,accountType:'PERSONAL',firstRecharge:false}});
 sessionStorage.setItem('xzwl-recharge:real-owner:account',JSON.stringify({requestId:'switch-enterprise',amount4:'2000.00',accountType:'ENTERPRISE',paymentMethod:'ALIPAY'}));
 page();expect(await screen.findByText('企业账户充值')).toBeVisible();
 api.create.mockResolvedValue({data:pending({accountType:'ENTERPRISE',amount4:'2000.0000'})});
 fireEvent.click(screen.getByRole('button',{name:'重试本次订单'}));
 await screen.findByText('等待支付');expect(api.create).toHaveBeenCalledWith({requestId:'switch-enterprise',amount4:'2000.00',accountType:'ENTERPRISE',paymentMethod:'ALIPAY'});
});
it('allows an existing enterprise account to choose personal without a name',async()=>{
 api.options.mockResolvedValue({data:{...options,accountType:'ENTERPRISE',firstRecharge:false}});
 await plans();expect(screen.queryByLabelText('企业名称')).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'个人充值'}));
 fireEvent.click(screen.getByRole('button',{name:'购买 ¥ 200 套餐'}));
 api.create.mockResolvedValue({data:pending({amount4:'200.0000'})});
 fireEvent.click(screen.getByRole('button',{name:'立即支付'}));await screen.findByText('等待支付');
 expect(api.create.mock.calls[0][0]).toMatchObject({accountType:'PERSONAL',amount4:'200.00'});
 expect(api.create.mock.calls[0][0]).not.toHaveProperty('enterpriseName');
});

it.each([true,false])('offers the personal 10000 tier for firstRecharge=%s with exact order points',async(firstRecharge)=>{
 api.options.mockResolvedValue({data:{...options,firstRecharge,accountType:firstRecharge?null:'PERSONAL',rules:options.rules.map(rule=>rule.accountType==='PERSONAL'&&!firstRecharge?{...rule,minimumAmount4:'200.0000',presetAmounts4:rule.presetAmounts4.slice(1)}:rule)}});
 await plans();expect(screen.queryByRole('navigation',{name:'充值导航'})).not.toBeInTheDocument();expect(screen.queryByText(/充值到账后，账户身份将切换为/)).not.toBeInTheDocument();fireEvent.click(screen.getByRole('button',{name:'购买 ¥ 10000 套餐'}));
 expect(screen.queryByRole('navigation',{name:'充值导航'})).not.toBeInTheDocument();
 expect(screen.getByRole('heading',{name:'确认充值'})).toBeVisible();
 expect(screen.getByText('¥ 10,000')).toBeVisible();expect(screen.getByText('到账 123,456 积分')).toBeVisible();
 api.create.mockResolvedValue({data:pending({amount4:'10000.0000',expectedPoints4:'123456.0000'})});
 fireEvent.click(screen.getByRole('button',{name:'立即支付'}));
 expect(await screen.findByRole('img',{name:'收钱吧支付二维码'})).toBeVisible();
 expect(screen.queryByRole('navigation',{name:'充值导航'})).not.toBeInTheDocument();
 expect(api.create.mock.calls[0][0]).toMatchObject({accountType:'PERSONAL',amount4:'10000.00'});
 expect(screen.getByText('¥ 10,000')).toBeVisible();expect(screen.getByText('到账 123,456 积分')).toBeVisible();
});
