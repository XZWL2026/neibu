import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { configureGateway } from '../api';
import { RechargePanel } from './RechargePanel';
import { PlatformKeyPanel } from './AccountProfile';
import {UserAccountProvider} from '../userSession';

const json=(value: unknown,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
let paid=false, fulfilled=false, keys=true, accountType='API', providerUnavailable=false;
let fetchMock: ReturnType<typeof vi.fn<(url: string, init?: RequestInit) => Promise<Response>>>;
const order=()=>({orderId:'paid-order',userId:'owner',amount4:'5000.0000',rateSnapshot4:'10.0000',accountType:'API',status:paid?'PAID':'PENDING',fulfilled,checkoutUrl:'https://pay.example/checkout',expiresAt:'2099-01-01T00:00:00Z'});
beforeEach(()=>{
  configureGateway({});sessionStorage.clear();paid=false;fulfilled=false;keys=true;accountType='API';providerUnavailable=false;
  fetchMock=vi.fn(async(url: string,init?: RequestInit)=>{
    if(url.includes('/auth/session'))return json({authenticated:true,csrfToken:'csrf',roles:['user'],user:{id:'owner',phone:'+8613800000000',nickname:'用户'}});
    if(url.endsWith('/users/me'))return json({id:'owner',phone:'+8613800000000',nickname:'用户',enabled:true});
    if(url.endsWith('/recharge-options'))return json({userId:'owner',accountType:keys?accountType:null,firstRecharge:!keys,pointsPerYuan4:'10.0000',rules:[{accountType:'API',minimumAmount4:'5000.0000',presetAmounts4:['5000.0000','10000.0000','50000.0000','100000.0000']}]});
    if(url.endsWith('/recharges')&&init?.method==='GET')return json([]);
    if(url.endsWith('/access/keys'))return json(keys?[{keyId:'key',ownerId:'owner',accountType,kind:accountType==='API'?'API':'MEDIA',enabled:true,firstRechargeOrderId:'paid-order'}]:[]);
    if(url.endsWith('/secret'))return json({secret:'sk-_test-secret'});
    if(url.includes('/wallets/'))return json({walletId:'key',availablePoints4:'1000.0000',version:1});
    if(url.endsWith('/recharges')&&init?.method==='POST')return providerUnavailable?json({code:'PAYMENT_PROVIDER_UNAVAILABLE'},503):json(order());
    if(url.includes('/recharges/'))return json(order());
    throw new Error('Unexpected '+url);
  });vi.stubGlobal('fetch',fetchMock);
});
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
function open(){return render(<MemoryRouter><UserAccountProvider><><RechargePanel apiOnly/><PlatformKeyPanel/></></UserAccountProvider></MemoryRouter>);}
it('does not request or display a secret until payment AND fulfillment are confirmed',async()=>{
  const view=open();await screen.findByText('已付款，等待积分入账');
  expect(screen.queryByRole('button',{name:'显示 Key'})).not.toBeInTheDocument();
  paid=true;fireEvent.click(screen.getByRole('button',{name:'刷新账户'}));await screen.findByText('已付款，等待积分入账');
  expect(fetchMock.mock.calls.some(([url])=>String(url).endsWith('/secret'))).toBe(false);
  fulfilled=true;fireEvent.click(screen.getByRole('button',{name:'刷新账户'}));
  await screen.findByRole('button',{name:'显示 Key'});fireEvent.click(screen.getByRole('button',{name:'显示 Key'}));
  await screen.findByText('sk-_test-secret');expect(sessionStorage.getItem('xzwl-recharge:owner:api')||'').not.toContain('secret');
  view.unmount();open();await screen.findByRole('button',{name:'显示 Key'});expect(screen.queryByText('sk-_test-secret')).not.toBeInTheDocument();
});
it('uses the generated SDK, CSRF, API type, and same request ID after provider failure',async()=>{
  keys=false;providerUnavailable=true;open();await screen.findByRole('button',{name:'创建支付订单'});
  fireEvent.click(screen.getByRole('button',{name:'创建支付订单'}));await screen.findByText(/支付服务或账户读取暂不可用/);
  expect(screen.queryByRole('button',{name:'显示 Key'})).not.toBeInTheDocument();
  providerUnavailable=false;fireEvent.click(screen.getByRole('button',{name:'重试本次订单'}));await screen.findByText('等待支付');
  const calls=fetchMock.mock.calls.filter(([url,init])=>String(url).endsWith('/recharges')&&init?.method==='POST');
  expect(calls).toHaveLength(2);expect(JSON.parse(String(calls[0][1]!.body))).toMatchObject({accountType:'API',amount4:'5000.00'});
  expect(calls[0][1]!.body).toEqual(calls[1][1]!.body);expect(new Headers(calls[0][1]!.headers).get('X-CSRF-TOKEN')).toBe('csrf');
});
it('restores a pending order after remount without creating another payment',async()=>{
  keys=false;const view=open();await screen.findByRole('button',{name:'创建支付订单'});fireEvent.click(screen.getByRole('button',{name:'创建支付订单'}));
  await screen.findByText('等待支付');view.unmount();open();await screen.findByText('等待支付');
  expect(fetchMock.mock.calls.filter(([url,init])=>String(url).endsWith('/recharges')&&init?.method==='POST')).toHaveLength(1);
});
it('shows incompatible account type without offering conversion or API recharge',async()=>{
  accountType='PERSONAL';open();await screen.findByText(/当前为个人账户/);
  expect(screen.queryByRole('button',{name:'创建支付订单'})).not.toBeInTheDocument();
  expect(fetchMock.mock.calls.some(([url])=>String(url).endsWith('/secret'))).toBe(false);
});

it('allows editing after a definitive validation rejection instead of replaying an invalid amount',async()=>{
  keys=false;const base=fetchMock.getMockImplementation()!;
  fetchMock.mockImplementation(async(url: string,init?: RequestInit)=>url.endsWith('/recharges')&&init?.method==='POST'
    ? json({code:'INVALID_AMOUNT'},400) : base(url,init));
  open();await screen.findByRole('button',{name:'创建支付订单'});
  fireEvent.click(screen.getByRole('button',{name:'创建支付订单'}));await screen.findByText(/金额或账户信息不符合当前充值规则/);
  expect(screen.getByLabelText('自定义充值金额（元）')).toBeEnabled();
  expect(sessionStorage.getItem('xzwl-recharge:owner:api')).toBeNull();
  expect(screen.getByRole('button',{name:'创建支付订单'})).toBeEnabled();
});

it('retains wallet and own Key access after a fulfilled first recharge is refunded',async()=>{
  paid=true;fulfilled=true;
  const base=fetchMock.getMockImplementation()!;
  fetchMock.mockImplementation(async(url: string,init?: RequestInit)=>url.includes('/recharges/')
    ? json({...order(),status:'REFUNDED'}) : base(url,init));
  open();await screen.findByRole('button',{name:'显示 Key'});
  expect(screen.getByText('可用积分').parentElement).toHaveTextContent('1,000 积分');
  fireEvent.click(screen.getByRole('button',{name:'显示 Key'}));
  await screen.findByText('sk-_test-secret');
});
