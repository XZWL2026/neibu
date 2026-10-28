import type { Page } from '@playwright/test';
/** Synthetic responses are confined to browser tests. The shipped app has no mock login/data mode. */
export async function mockGateway(page:Page,role:'admin'|'normal_admin'='admin',initiallyAuthenticated=true) {
  let authenticated=initiallyAuthenticated;
  const calls:string[]=[];
  const user={id:'user-1',nickname:'林舟',phone:'+8613800000001',enabled:true,registeredAt:'2026-09-02T08:30:00Z',source:'NATURAL',accountType:'PERSONAL',keyId:'key-1',keyStatus:'ENABLED',maskedKey:'sk-****demo',availableAmount4:'1632.4500',availablePoints4:'163245.0000'};
  const today=new Date(Date.now()+28800000).toISOString().slice(0,10);
  await page.route('**/api/v1/**',async route=>{
    const request=route.request(),url=new URL(request.url()),path=url.pathname;calls.push(path+url.search);
    const fulfill=(data:unknown,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
    if(path==='/api/v1/auth/session')return fulfill({authenticated,primary:role==='admin',roles:authenticated?[role]:[],user:authenticated?{id:'admin-1',username:'primary.admin'}:null,csrfToken:'test-only-csrf'});
    if(path==='/api/v1/auth/admin/login'){authenticated=true;return fulfill({});}
    if(path==='/api/v1/auth/logout'){authenticated=false;return fulfill({});}
    if(!authenticated)return fulfill({message:'登录已失效'},401);
    if(path==='/api/v1/admin/users/directory')return fulfill({items:Array.from({length:Number(url.searchParams.get('limit')||10)},(_,index)=>({...user,id:'user-'+(index+1),nickname:['林舟','陈澈','沈星','苏禾','陆川'][index%5]+(index>4?' '+(index+1):''),accountType:['PERSONAL','ENTERPRISE','API'][index%3],source:index%3===0?'REFERRAL':'NATURAL',enabled:index%7!==0,availableAmount4:(1632.45+index*139.16).toFixed(4)})),total:1268,summary:{totalUsers:1268,newUsers:86,activeUsers:394,unpaidUsers:637,paidUsers:631}});
    if(path==='/api/v1/admin/users/user-1/directory-detail')return fulfill({user,summary:{totalRechargeAmount4:'3000.0000',netConsumptionAmount4:'1367.5500'},recharges:[],consumptions:[]});
    if(path==='/api/v1/admin/api-users/directory')return fulfill({items:[{user:{...user,id:'api-1',keyId:'api-key-1',accountType:'API'},todayCalls:286,todaySeconds:1820,todayAmount4:'326.8012',lastCallAt:today+'T08:00:00Z'}],total:1,summary:{totalApiUsers:1,previousMonthTotalApiUsers:1,todayCalls:286,yesterdayCalls:260,todaySeconds:1820,yesterdaySeconds:1600,todayAmount4:'326.8012',yesterdayAmount4:'300.0000'}});
    if(path==='/api/v1/admin/users/api-1/directory-detail')return fulfill({user:{...user,id:'api-1',keyId:'api-key-1',accountType:'API'},summary:{totalCalls:500,netConsumptionAmount4:'1367.5500'},recharges:[],consumptions:[]});
    if(path==='/api/v1/admin/payments/recharge-options')return fulfill({userId:url.searchParams.get('userId'),accountType:'API',pointsPerYuan4:'100.0000',rules:[{accountType:'API',minimumAmount4:'5000.0000',presetAmounts4:['5000.0000','10000.0000']}]});
    if(path==='/api/v1/admin/billing/api-users/api-1/media-prices')return fulfill({version:1,defaults:[],overrides:[],prices:[]});
    if(path==='/api/v1/admin/billing/api-users/api-1/media-prices/audit'||path.includes('/wallets/'))return fulfill([]);
    if(path==='/api/v1/admin/promotions/benefit-settings')return fulfill({version:1,effectiveAt:'2026-09-02T08:30:00Z',operator:'primary.admin',reason:'浏览器回归测试配置',global:{invitationPointsRate:'0.010000',defaultLevel1CashRate:'0.080000',defaultLevel2CashRate:'0.000000'},promoters:[{id:'user-1',name:'林舟',maskedPhone:'138****0001',enabled:true,level1CashRate:'0.100000',level2CashRate:null,effectiveAt:'2026-09-02T08:30:00Z'}],customers:[]});
    if(path==='/api/v1/admin/promotions/benefit-settings/history')return fulfill([]);
    if(path.startsWith('/api/v1/admin/promotions/reconciliation'))return fulfill({dryRun:true,executable:false,generatedAt:today+'T08:00:00Z',pendingRewardDeliveries:0,differences:[],debts:[]});
    if(path==='/api/v1/admin/promotions/agent-directory/user-1')return fulfill({agent:{userId:'user-1',nickname:'林舟',phone:user.phone,enabled:true,createdAt:user.registeredAt,source:'ADMIN',invitationCode:'P123',invitedCount:3,paidCount:2,totalRechargeAmount4:'1234.5678',totalCommissionAmount4:'98.7654',pendingCommissionAmount4:'65.4000'},personalPaidCount:1,enterprisePaidCount:0,apiPaidCount:1,paidPercent:'66.66',withdrawnAmount4:'33.3654',level1CashRate6:'0.100000',level2CashRate6:'0.000000'});
    if(path==='/api/v1/admin/promotions/agent-directory')return fulfill({items:[{userId:'user-1',nickname:'林舟',phone:user.phone,status:'ENABLED',invitationCode:'P123',enabled:true,registeredAt:user.registeredAt,createdAt:user.registeredAt,source:'ADMIN',personalPaidCount:1,enterprisePaidCount:0,apiPaidCount:1,invitedCount:3,paidCount:2,totalRechargeAmount4:'1234.5678',totalCommissionAmount4:'98.7654',pendingCommissionAmount4:'65.4000'}],summary:{totalAgents:1,todayNewAgents:0,todayReferredUsers:2,paidDownlineUsers:2,pendingCommissionAmount4:'65.4000',yesterday:{totalAgents:1,newAgents:0,referredUsers:1,paidDownlineUsers:2,pendingCommissionAmount4:'60.0000'}},total:1,page:0,size:10});
    if(path==='/api/v1/admin/promotions/agent-income')return fulfill({items:[],total:0,page:0,size:10,totalConsumptionAmount4:'0.0000',totalCommissionAmount4:'0.0000',pendingPayoutAmount4:'0.0000',statusTotals:[]});
    if(path==='/api/v1/admin/payments/corporate-transfers')return fulfill([]);
    if(path==='/api/v1/admin/billing/daily-finance'){
      const from=url.searchParams.get('from'),to=url.searchParams.get('to');
      const limit=Number(url.searchParams.get('limit')||30);
      const offset=Number(url.searchParams.get('offset')||0);
      const rows=Array.from({length:45},(_,index)=>({date:new Date(Date.parse(today+'T00:00:00Z')-index*86400000).toISOString().slice(0,10),rechargeAmount4:(4200+index%4*860).toFixed(4),consumptionAmount4:(2800+index%5*430).toFixed(4)})).filter(row=>(!from||row.date>=from)&&(!to||row.date<=to));
      return fulfill({items:rows.slice(offset,offset+limit),totalDays:rows.length,offset,limit,from,to,totalRechargeAmount4:'286450.7800',totalConsumptionAmount4:'194632.1200'});
    }
    if(path==='/api/v1/admin/media/native-video-usage'){
      const metric=(tasks:number)=>({tasks,seconds:tasks*5});
      return fulfill({from:url.searchParams.get('from')||today,to:url.searchParams.get('to')||today,total:metric(3470),items:['Seedance 2.5','Seedance 2.0','Seedance 2.0 Fast','Seedance 2.0 Mini'].map((model,index)=>({date:today,model,native480:metric(28+index*5),native720:metric(116+index*15),native1080:metric(57+index*10),enhanced720:metric(0),enhanced1080:metric(16+index),enhanced4k:metric(4+index),total:metric(221+index*31)}))});
    }
    if(path==='/api/v1/admin/payments/orders')return fulfill({items:[],total:0,offset:Number(url.searchParams.get('offset')||0),limit:100});
    if(path==='/api/v1/admin/payments/recharges')return fulfill([]);
    if(path==='/api/v1/settings/runtime')return fulfill({rechargePointsPerYuan4:'100.0000'});
    if(path==='/api/v1/admin/routing/models')return fulfill([]);
    if(path==='/api/v1/admin/routing/providers')return fulfill([]);
    if(path==='/api/v1/admin/billing/media-prices')return fulfill({managed:true,version:1,prices:[]});
    if(path==='/api/v1/admin/admins')return fulfill([{id:'admin-1',username:'primary.admin',role:'ADMIN',enabled:true,primary:true}]);
    if(path.includes('analytics'))return fulfill({total:0,amount4:'0.0000',unknownAmounts:0,models:[],days:[]});
    if(path.includes('/consumptions'))return fulfill({items:[],total:0});
    return fulfill({items:[],total:0,page:0,size:20,summary:{}});
  });
  return {calls,user};
}
