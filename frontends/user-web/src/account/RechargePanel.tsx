import { randomUUID } from '../../../shared/randomUUID';
import { t, formatMessage, useLanguage, currentLanguage } from '../i18n';
import { formatAmount } from '../../../shared/formatAmount';
import {useEffect, useRef, useState, type FormEvent} from 'react';
import {Link} from 'react-router-dom';
import {QRCodeSVG} from 'qrcode.react';
import {ApiError, refreshSession, billingGetGetApiV1BillingModelPrices, paymentOptionsGetApiV1PaymentsRechargeOptions,
 paymentCreatePostApiV1PaymentsRecharges, paymentGetGetApiV1PaymentsRechargesId,
 paymentHistoryGetApiV1PaymentsRecharges} from '../api';
import {useOptionalUserSession} from '../userSession';
import {TransactionHistory} from './TransactionHistory';
import './recharge.css';

type Options = Awaited<ReturnType<typeof paymentOptionsGetApiV1PaymentsRechargeOptions>>['data'];
type Order = Awaited<ReturnType<typeof paymentGetGetApiV1PaymentsRechargesId>>['data'];
type Identity = 'PERSONAL'|'ENTERPRISE'|'API';
type Method = NonNullable<Order['paymentMethod']>;
const paymentNames:Record<Method,string>={ALIPAY:'支付宝',WECHAT:'微信'};
type Attempt = {requestId:string;amount4:string;accountType:Identity;enterpriseName?:string;orderId?:string;paymentMethod?:Method};
const names = {PERSONAL:'个人',ENTERPRISE:'企业',API:'API'};
const active = (order:Order) => order.status === 'PENDING' || order.status === 'REFUND_PENDING' || order.status === 'PAID' && !order.fulfilled;
export function expectedPoints(amount:string, rate:string) {
 if(!/^\d+(\.\d{1,2})?$/.test(amount)||!/^\d+(\.\d{1,4})?$/.test(rate)) return null;
 const fixed=(value:string,digits:number)=>{const [whole,fraction='']=value.split('.');return BigInt(whole)*10n**BigInt(digits)+BigInt(fraction.padEnd(digits,'0'));};
 const value=fixed(amount,2)*fixed(rate,4)/100n;
 return `${value/10000n}.${String(value%10000n).padStart(4,'0')}`;
}
const yuan=(value:string)=> {const [whole,fraction='']=value.split('.');return `${whole}.${fraction.padEnd(2,'0').slice(0,2)}`;};
const groupedAmount=(value:string|null|undefined)=>{const [whole,fraction]=formatAmount(value).split('.');return whole.replace(/\B(?=(\d{3})+(?!\d))/g,',')+(fraction?`.${fraction}`:'');};
function checkoutUrl(value:string|null|undefined) {try{
 const url=new URL(value||'');
 const web=url.protocol==='https:';
 const wechat=url.protocol==='weixin:'&&url.hostname==='wxpay'&&['/bizpayurl','/bizpayurl/up'].includes(url.pathname)&&!!url.search;
 return (web||wechat)&&!url.username&&!url.password&&!url.hash&&value!.length<=2048?url.href:null;
}catch{return null;}}
function errorText(error:unknown){
 if(error instanceof ApiError){
  if(error.code==='PAYMENT_CHANNEL_AMOUNT_LIMIT')return '金额超过收钱吧支付渠道单笔限额，请调整金额或联系管理员；系统不会自动拆单。';
  if(error.status===409)return '账户身份、首充资格或订单已变化，请刷新后按最新规则重试。';
  if(error.status===400||error.status===422)return '金额或账户信息不符合当前充值规则，请检查最低金额、金额精度。';
  if(error.status===401||error.status===403)return '登录已失效或当前账户没有充值权限。';
 }
 return '支付服务或账户读取暂不可用，尚未确认成功。请刷新或重试本次订单。';
}
function savedAttempt(owner:string,apiOnly:boolean):Attempt|null {try{
 const value=JSON.parse(sessionStorage.getItem(`xzwl-recharge:${owner}:${apiOnly?'api':'account'}`)||'null');
 return value&&typeof value.requestId==='string'&&typeof value.amount4==='string'&&['PERSONAL','ENTERPRISE','API'].includes(value.accountType)
 &&(!value.orderId||typeof value.orderId==='string')&&(value.paymentMethod===undefined||['ALIPAY','WECHAT'].includes(value.paymentMethod))?value:null;
}catch{return null;}}
function RechargeSuccess({amount,onClose}:{amount:string;onClose():void}) {
  useLanguage();
 const dialog=useRef<HTMLDialogElement>(null);
 useEffect(()=>{
  const previous=document.activeElement instanceof HTMLElement?document.activeElement:null;
  const element=dialog.current;
  if(element?.showModal)element.showModal();else element?.setAttribute('open','');
  element?.querySelector('button')?.focus();
  return()=>{element?.close?.();previous?.focus();};
 },[]);
 return <dialog ref={dialog} className="recharge-success" aria-label={t("充值成功")} aria-modal="true"
  onCancel={event=>{event.preventDefault();onClose();}}>
  <h2>{t("充值成功")}</h2><p>{formatMessage("已充值 ¥ {0}", formatAmount(amount))}</p>
  <button type="button" className="btn-primary" onClick={onClose}>{t("确定")}</button>
 </dialog>;
}
type PriceCatalog=Awaited<ReturnType<typeof billingGetGetApiV1BillingModelPrices>>['data'];
type ModelPrice=NonNullable<PriceCatalog['prices']>[number];
// User-approved fixed reference prices from 模型价格首页.png (yuan/second).
const referencePrices:Record<string,string>={
 'seedance-2.0/720p':'1.00','seedance-2.0/1080p':'2.50','seedance-2.0/4k':'5.00',
 'seedance-2.5/720p':'1.55','seedance-2.5/1080p':'3.80'
};
const referencePrice=(row:ModelPrice)=>row.type==='VIDEO'&&row.unit==='SECOND'?referencePrices[`${row.model?.toLowerCase()}/${row.resolution?.toLowerCase()}`]:undefined;
function priceComparison(row:ModelPrice){
 const reference=referencePrice(row);
 if(!reference||!row.yuanPerUnit||!/^\d+(\.\d{1,4})?$/.test(row.yuanPerUnit))return {reference,saving:null};
 const units=(value:string)=>{const [whole,fraction='']=value.split('.');return BigInt(whole)*10000n+BigInt(fraction.padEnd(4,'0'));};
 const original=units(reference),current=units(row.yuanPerUnit);
 if(current>=original)return {reference,saving:null};
 const scaled=(original-current)*1000n;
 const tenths=(scaled+original/2n)/original;
 return {reference,saving:scaled<original?'省 <0.1%':`省 ${formatAmount(`${tenths/10n}.${tenths%10n}`)}%`};
}
const unitPrice=(value:string)=>{const [whole,fraction='']=formatAmount(value).split('.');return `${whole}.${fraction.padEnd(2,'0')}`;};
const resolutionOrder=(a:string,b:string)=>{
 const size=(value:string)=>(parseFloat(value)||Infinity)*(/k$/i.test(value)?1000:1);
 return size(a)-size(b)||a.localeCompare(b);
};
function PriceDetails({catalog,initialType,onClose}:{catalog:PriceCatalog;initialType:Identity;onClose():void}) {
  useLanguage();
 const [priceType,setPriceType]=useState(initialType==='ENTERPRISE'?'ENTERPRISE':'PERSONAL');
 const dialog=useRef<HTMLDialogElement>(null);
 useEffect(()=>{
  const previous=document.activeElement instanceof HTMLElement?document.activeElement:null;
  const element=dialog.current;
  if(element?.showModal)element.showModal();else element?.setAttribute('open','');
  element?.querySelector('button')?.focus();
  return()=>{element?.close?.();previous?.focus();};
 },[]);
 const rows=(catalog.prices||[]).filter(row=>row.accountType===priceType);
 return <dialog ref={dialog} className="model-prices-dialog" aria-label={t("全部模型价格")} aria-modal="true" onCancel={event=>{event.preventDefault();onClose();}}>
  <header><h2>{t("全部模型价格")}</h2><button type="button" className="model-prices-close" aria-label={t("关闭模型价格")} onClick={onClose}>×</button></header>
  <div className="checkout-identities" role="group" aria-label={t("模型价格身份")}>{(['PERSONAL','ENTERPRISE'] as const).map(value=><button type="button" key={value} aria-pressed={priceType===value} onClick={()=>setPriceType(value)}>{formatMessage("{0}价格", t(names[value]))}</button>)}</div>
  {!rows.length&&<p className="model-prices-empty">{t("暂无已启用的模型价格")}</p>}
  {(['VIDEO','IMAGE'] as const).map(kind=>{
   const items=rows.filter(row=>row.type===kind);if(!items.length)return null;
   const models=[...new Set(items.map(row=>row.model))];
   return <section key={kind} className="model-prices-kind" aria-label={t(kind==='VIDEO'?'视频模型价格':'图片模型价格')}><div className="model-prices-kind-title"><h3>{t(kind==='VIDEO'?'视频模型':'图片模型')}</h3><span>{formatMessage("单位：元 / {0}", t(kind==='VIDEO'?'秒':'张'))}</span></div>
    <div className="table-wrap"><table><thead><tr><th scope="col">{t("模型")}</th><th scope="col">{t("分辨率")}</th><th scope="col">{t("官方参考价")}</th><th scope="col">{formatMessage("{0}单价", t(names[priceType as 'PERSONAL'|'ENTERPRISE']))}</th><th scope="col">{t("节省")}</th></tr></thead>
    {models.map(model=>{const prices=items.filter(row=>row.model===model).sort((a,b)=>resolutionOrder(a.resolution||'',b.resolution||''));return <tbody key={model}>{prices.map((row,index)=>{const comparison=priceComparison(row);return <tr key={row.resolution}>
     {index===0&&<th scope="rowgroup" rowSpan={prices.length}>{model}</th>}<td>{row.resolution?.toUpperCase()}</td><td className="model-price-reference">{comparison.reference?<del>¥ {comparison.reference}</del>:'—'}</td><td className="model-price-value">{row.yuanPerUnit===null?'—':`¥ ${unitPrice(row.yuanPerUnit)}`}</td><td>{comparison.saving?<span className="model-price-saving">{t(comparison.saving)}</span>:'—'}</td>
    </tr>;})}</tbody>;})}</table></div>
   </section>;
  })}
  <footer><p>{formatMessage("1 元 = {0} 积分", formatAmount(catalog.pointsPerYuan4))}</p><button type="button" className="btn-primary" onClick={onClose}>{t("返回充值")}<span aria-hidden="true">→</span></button></footer>
 </dialog>;
}
function ModelPrices({accountType}:{accountType:Identity}) {
  useLanguage();
 const [catalog,setCatalog]=useState<PriceCatalog|null>(null);
 const [error,setError]=useState(false);
 const [reload,setReload]=useState(0);
 const [open,setOpen]=useState(false);
 useEffect(()=>{
  const controller=new AbortController();let live=true;
  setError(false);
  void billingGetGetApiV1BillingModelPrices({signal:controller.signal}).then(({data})=>{
   if(live)setCatalog({...data,prices:(data.prices||[]).filter(row=>referencePrice(row))});
  },()=>{if(live)setError(true);});
  const refresh=()=>setReload(value=>value+1);window.addEventListener('focus',refresh);
  return()=>{live=false;controller.abort();window.removeEventListener('focus',refresh);};
 },[reload]);
 const rows=(catalog?.prices||[]).filter(row=>row.accountType===accountType);
 const video=rows.filter(row=>row.type==='VIDEO');
 const preview:ModelPrice[]=video.length?video:rows.filter(row=>row.type==='IMAGE');
 const models=[...new Set(preview.map(row=>row.model))].slice(0,2);
 const resolutions=[...new Set(preview.filter(row=>models.includes(row.model)).map(row=>row.resolution||''))].sort(resolutionOrder);
 return <section className="recharge-model-prices" aria-label={t("模型价格")}>
  <header><h2>{t("模型价格")}</h2><span>{t("充值后使用")}</span></header>
  {error?<p className="model-prices-empty" role="status">{t("价格暂时无法读取。")}<button type="button" className="checkout-back" onClick={()=>setReload(value=>value+1)}>{t("重新加载价格")}</button></p>:!catalog?<p role="status">{t("正在读取价格…")}</p>:!models.length?<p className="model-prices-empty">{t("暂无已启用的模型价格")}</p>:<div className="table-wrap"><table><thead><tr><th scope="col">{t("模型")}</th>{resolutions.map(value=><th key={value} scope="col">{value.toUpperCase()}</th>)}</tr></thead><tbody>{models.map(model=><tr key={model}><th scope="row">{model}</th>{resolutions.map(resolution=>{const row=preview.find(price=>price.model===model&&price.resolution===resolution);return <td key={resolution}>{t(row?.yuanPerUnit!=null?formatMessage("¥ {0} / {1}", unitPrice(row.yuanPerUnit), t(row.unit==='SECOND'?'秒':'张')):'—')}</td>;})}</tr>)}</tbody></table></div>}
  <button type="button" className="btn-secondary" disabled={!catalog||error} onClick={()=>setOpen(true)}>{t("查看全部模型价格")}<span aria-hidden="true">→</span></button>
  {open&&catalog&&<PriceDetails catalog={catalog} initialType={accountType} onClose={()=>setOpen(false)}/>}
 </section>;
}
export function RechargePanel({apiOnly=false}:{apiOnly?:boolean}) {
  useLanguage();
 const account=useOptionalUserSession();
 const [step,setStep]=useState<'overview'|'plans'|'confirm'|'account'|'records'>('overview');
 const [recordTab,setRecordTab]=useState<'all'|'orders'|'consumption'>('orders');
 const flowRoot=useRef<HTMLElement>(null);
 const [owner,setOwner]=useState<string|null>(null),[options,setOptions]=useState<Options|null>(null);
 const [type,setType]=useState<Identity>(apiOnly?'API':'PERSONAL'),[amount,setAmount]=useState('');
 const [order,setOrder]=useState<Order|null>(null),[history,setHistory]=useState<Order[]>([]),[attempt,setAttempt]=useState<Attempt|null>(null);
 const [method,setMethod]=useState<Method>('ALIPAY');
 const [busy,setBusy]=useState(false),[loaded,setLoaded]=useState(false),[error,setError]=useState('');
 const [success,setSuccess]=useState<Order|null>(null);
 const notified=useRef(new Set<string>()),amountInput=useRef<HTMLInputElement>(null),returning=useRef(false);
 const running=useRef(false),alive=useRef(false);
 const scope=useRef<string|null>(null);const pendingAttempt=useRef<{owner:string|null;value:Attempt|null}>({owner:null,value:null});
 function remember(value:Attempt|null,user=owner){if(!user)return;pendingAttempt.current={owner:user,value};try{const key=`xzwl-recharge:${user}:${apiOnly?'api':'account'}`;if(value)sessionStorage.setItem(key,JSON.stringify(value));else sessionStorage.removeItem(key);}catch{/* In-memory attempt remains retryable. */}setAttempt(value);}
 function acceptOrder(value:Order,user=owner){
  setOrder(value);
  if(value.status==='PAID'&&value.fulfilled&&value.orderId&&value.amount4){
   const key=`${user}:${value.orderId}`;
   if(!notified.current.has(key)){notified.current.add(key);setSuccess(value);}
  }
 }
 function returnToAmount(){
  if(busy||order&&active(order)&&order.status!=='PENDING')return;
  if(order)setHistory(items=>[order,...items.filter(item=>item.orderId!==order.orderId)]);
  returning.current=true;remember(null);setOrder(null);setError('');setStep('plans');
 }
 useEffect(()=>{if(apiOnly&&!order&&returning.current){returning.current=false;amountInput.current?.focus();}},[order]);
 async function run(action:()=>Promise<void>){if(running.current)return;running.current=true;setBusy(true);setError('');try{await action();}catch(cause){if(alive.current)setError(errorText(cause));}finally{running.current=false;if(alive.current)setBusy(false);}}
 async function load(){
  const session=await refreshSession();if(!alive.current)return;
  const id=session.authenticated?session.user?.id:null;
  const sameOwner=scope.current===(id||null);
  if(!sameOwner){setStep('overview');setOrder(null);setSuccess(null);setAttempt(null);setOptions(null);setHistory([]);setAmount('');pendingAttempt.current={owner:id||null,value:null};}
  scope.current=id||null;setOwner(id||null);
  if(!id){setOptions(null);setHistory([]);setOrder(null);setAttempt(null);setLoaded(true);return;}
  const [rules,orders]=await Promise.all([paymentOptionsGetApiV1PaymentsRechargeOptions(),paymentHistoryGetApiV1PaymentsRecharges()]);
  if(!alive.current||scope.current!==id)return;
  if(rules.data.userId!==id)throw new Error('Account mismatch');
  setOptions(rules.data);setHistory(orders.data||[]);
  const bound=rules.data.accountType as Identity|null;
  const saved=savedAttempt(id,apiOnly)??(pendingAttempt.current.owner===id?pendingAttempt.current.value:null);pendingAttempt.current={owner:id,value:saved};setAttempt(saved);
  const selected=saved?.accountType||(apiOnly?'API':!sameOwner?bound||'PERSONAL':type);setType(selected);
  const rule=rules.data.rules?.find(item=>item.accountType===selected);
  if(saved){setMethod(saved.paymentMethod||'ALIPAY');setAmount(yuan(saved.amount4));if(saved.orderId){
    const result=(await paymentGetGetApiV1PaymentsRechargesId(saved.orderId)).data;
    if(!alive.current||scope.current!==id)return;acceptOrder(result,id);
    if(!active(result)){remember(null,id);setAmount(yuan(rule?.presetAmounts4?.[0]||''));window.dispatchEvent(new Event('xzwl-account-changed'));}
  }}else if(!amount||!sameOwner){setAmount(yuan(rule?.presetAmounts4?.[0]||''));}
  setLoaded(true);
 }
 async function refreshAccountAndOrders(){await Promise.all([load(),account?.refresh()]);}
 const latestLoad=useRef(load);latestLoad.current=load;
 useEffect(()=>{
  alive.current=true;const refresh=()=>void run(()=>latestLoad.current());refresh();
  window.addEventListener('focus',refresh);window.addEventListener('xzwl-account-changed',refresh);
  return()=>{alive.current=false;scope.current=null;window.removeEventListener('focus',refresh);window.removeEventListener('xzwl-account-changed',refresh);};
 },[]);
 useEffect(()=>{
  if(!order?.orderId||!active(order)||busy||error)return;
  const timer=window.setTimeout(()=>void run(async()=>{
   const result=(await paymentGetGetApiV1PaymentsRechargesId(order.orderId!)).data;if(!alive.current||scope.current!==owner)return;acceptOrder(result);
   if(!active(result)){remember(null);await load();window.dispatchEvent(new Event('xzwl-account-changed'));}
  }),3000);return()=>window.clearTimeout(timer);
 },[order,busy,error]);
 const bound=options?.accountType;
 const incompatible=apiOnly&&!!bound&&bound!=='API';
 const rule=options?.rules?.find(item=>item.accountType===type);
 const points=options?.pointsPerYuan4?expectedPoints(amount,options.pointsPerYuan4):null;
 const valid=!!points&&Number(amount)>=Number(rule?.minimumAmount4);
 function choose(next:Identity){setType(next);setAmount(yuan(options?.rules?.find(item=>item.accountType===next)?.presetAmounts4?.[0]||''));}
 async function submit(event:FormEvent){event.preventDefault();if(!owner||incompatible||(!attempt&&!valid))return;
  await run(async()=>{
   const next=attempt||{requestId:randomUUID(),amount4:amount,accountType:type,paymentMethod:method};remember(next);
   let result:Order;try{const {orderId:ignored,...payload}=next;void ignored;result=(await paymentCreatePostApiV1PaymentsRecharges(payload)).data;}catch(cause){
    if(cause instanceof ApiError&&cause.code==='PAYMENT_CHANNEL_AMOUNT_LIMIT'){remember(null);setOrder(null);setStep('plans');}
    else if(!attempt&&!next.orderId&&cause instanceof ApiError&&[400,403,409,422].includes(cause.status)){remember(null);setStep('plans');}throw cause;
   }
   if(!alive.current)return;if(!result.orderId||result.accountType!==next.accountType)throw new Error('Invalid order');
   acceptOrder(result);remember({...next,orderId:result.orderId});
   if(!active(result)){remember(null);await load();window.dispatchEvent(new Event('xzwl-account-changed'));}
  });
 }
 const url=order?.status==='PENDING'?checkoutUrl(order.checkoutUrl):null;
 const label=(item:Order)=>item.status==='PAID'?(item.fulfilled?'充值成功，积分已到账':'已付款，正在入账'):
 ({PENDING:'等待支付',EXPIRED:'订单已过期',REFUND_PENDING:'异常订单或退款处理中',REFUNDED:'现金已退款',REVIEW_PENDING:'等待人工审核'}[item.status||'']||item.status);
 const stage=success?'success':order&&active(order)?order.status==='PENDING'?(url?'scan':'confirm'):'processing':attempt?'confirm':step;
 useEffect(()=>{if(!apiOnly){if(returning.current){returning.current=false;amountInput.current?.focus();return;}const heading=flowRoot.current?.querySelector('h1');if(heading){heading.tabIndex=-1;heading.focus({preventScroll:true});if(!['confirm','scan','processing','success'].includes(stage))heading.scrollIntoView?.({block:'start'});}}},[stage,apiOnly]);
 const balance=account?.userId===owner&&!account.fixture?account.availablePoints4:null;
 const credit=(item:Order)=>formatAmount(item.expectedPoints4||expectedPoints(yuan(item.amount4||''),item.rateSnapshot4||''));
 const newSelection=()=>{setSuccess(null);setOrder(null);setStep('plans');};
 const returnAccount=()=>{setSuccess(null);setOrder(null);setStep('account');};
 const orderDetails=(item:Order)=><dl className="checkout-details"><div><dt>{t("订单号")}</dt><dd>{item.orderId}</dd></div><div><dt>{t("账户类型")}</dt><dd>{t(names[item.accountType as Identity])}</dd></div><div><dt>{t("支付金额")}</dt><dd>¥ {formatAmount(item.amount4)}</dd></div><div><dt>{t("到账积分")}</dt><dd>{formatMessage("{0} 积分", credit(item))}</dd></div><div><dt>{t("支付方式")}</dt><dd>{t(item.paymentMethod?paymentNames[item.paymentMethod]:'—')}</dd></div><div><dt>{t("创建时间")}</dt><dd>{item.createdAt?new Date(item.createdAt).toLocaleString(currentLanguage(),{hour12:false}):'—'}</dd></div></dl>;
 if(!apiOnly)return <section ref={flowRoot} className={`real-recharge recharge-experience${stage==='overview'||stage==='plans'?' recharge-landing':''}`} aria-label={t("真实账户充值")}>
  {!['overview','plans','confirm','scan'].includes(stage)&&<nav className="recharge-top-actions" aria-label={t("充值导航")}><Link to="/account/profile">{t("账户中心")}</Link><button type="button" disabled={busy||!!attempt||!!order&&active(order)} onClick={()=>{setSuccess(null);setStep('records');}}>{t("订单记录")}</button><button type="button" disabled={busy} onClick={()=>void run(refreshAccountAndOrders)}>{t("刷新账户与订单")}</button></nav>}
  {error&&<p role="alert">{t(error)}</p>}
  {!loaded&&!error&&<p role="status">{t("正在读取账户与充值规则…")}</p>}
 {loaded&&!owner&&<p>{t("请")}<Link to="/user/login">{t("登录")}</Link>{t("后充值。")}</p>}
  {options&&owner&&<>
   {['confirm','scan','processing','success'].includes(stage)&&<ol className="checkout-steps" aria-label={t("支付进度")}>{['确认订单','扫码支付','充值到账'].map((text,index)=>{
    const current=stage==='confirm'?0:stage==='success'?2:1;
    return <li key={text} className={index<current?'is-complete':undefined} aria-current={index===current?'step':undefined}><span aria-hidden="true">{index<current?'✓':index+1}</span>{t(text)}</li>;
   })}</ol>}
   {stage==='account'&&<>
    <header className="checkout-heading"><p className="checkout-kicker">{t("07 · 返回账户")}</p><h1>{t("开始下一次创作")}</h1></header>
    <div className="recharge-balance"><div><span>{t("当前余额")}</span><p><strong>{formatAmount(balance)}</strong>{t(" 积分")}</p></div><button type="button" className="btn-primary" disabled={busy} onClick={newSelection}>{t("充值")}<span aria-hidden="true">↗</span></button></div>
    <div className="recharge-benefits">{[{icon:'▷',title:'视频生成',text:'让创意成为动态画面',to:'/create/video'},{icon:'▧',title:'图片生成',text:'用灵感创造更多可能',to:'/create/image'},{icon:'◇',title:'创作画布',text:'在画布上展开创作',to:'/canvas'}].map(item=><Link key={item.to} to={item.to}><span aria-hidden="true">{item.icon}</span><strong>{t(item.title)}</strong><small>{t(item.text)}</small><b aria-hidden="true">↗</b></Link>)}</div>
   </>}
   {(stage==='overview'||stage==='plans')&&<>
    <header className="checkout-heading checkout-plans-heading">
    <h1>{t("充值中心")}</h1><p className="recharge-subtitle">{t("为创作补充 积分")}</p>
    {bound!=='API'?<div className="checkout-identities" role="group" aria-label={t("充值身份")}>{(['PERSONAL','ENTERPRISE'] as const).map(value=><button type="button" key={value} aria-pressed={type===value} disabled={busy} onClick={()=>choose(value)}>{formatMessage("{0}充值", t(names[value]))}</button>)}</div>:<p className="checkout-account-type">{formatMessage("{0}充值", t(names[bound as Identity]))}</p>}
    <p className="recharge-current-balance"><span>{t("当前余额")}</span><strong>{formatAmount(balance)}</strong>{t(" 积分")}</p></header>
    <div className="checkout-plans">{rule?.presetAmounts4?.map((value,index)=><article key={value} className={`checkout-plan${index===1?' checkout-plan-featured':''}`}>
     {index===1&&<span className="checkout-plan-badge">{t("推荐")}</span>}<h2>{t(['Starter','Creator','Studio','Pro','Max'][index]||'积分')}</h2><p className="checkout-plan-description">{t(['轻松开始创作','更大的创作可能','适合专业创作者','为高强度创作而生','为更多灵感蓄能'][index]||'为创作补充积分')}</p><p className="checkout-plan-price"><small>¥</small> {groupedAmount(value)}</p><p className="checkout-plan-credits"><strong>{groupedAmount(expectedPoints(yuan(value),options.pointsPerYuan4||''))}</strong>{t(" 积分")}</p>
     <button type="button" className={index===1?'btn-primary':'btn-secondary'} aria-label={formatMessage("购买 ¥ {0} 套餐", formatAmount(value))} disabled={busy} onClick={()=>{setAmount(yuan(value));setStep('confirm');}}>{t("立即充值")}<span aria-hidden="true">→</span></button>
    </article>)}</div>
    <ModelPrices accountType={type}/>
    <form className="checkout-custom" onSubmit={event=>{event.preventDefault();if(valid&&!busy)setStep('confirm');}}><label>{t("自定义充值金额（元）")}<input ref={amountInput} type="number" required step="0.01" min={rule?.minimumAmount4||undefined} value={amount} disabled={busy} onChange={event=>setAmount(event.target.value)}/></label><div><small>{formatMessage("最低 ¥ {0}", formatAmount(rule?.minimumAmount4))}</small><p>{formatMessage("{0} 积分", formatAmount(points))}</p></div><button className="btn-secondary" disabled={busy||!valid}>{t("下一步")}</button></form>
   </>}
   {stage==='confirm'&&<div className="checkout-narrow checkout-confirm"><button type="button" className="checkout-close" aria-label={t("关闭支付方式")} disabled={busy} onClick={returnToAmount}><span aria-hidden="true">×</span></button><header className="checkout-heading"><h1>{t("确认充值")}</h1><p><span>{formatMessage("{0}账户充值", t(names[type]))}</span>{t(" · 选择支付方式，完成支付后即可到账")}</p></header>
    <form onSubmit={submit}><fieldset className="checkout-methods"><legend>{t("支付方式")}</legend>{(['ALIPAY','WECHAT'] as const).map(value=><button type="button" key={value} aria-pressed={(attempt?attempt.paymentMethod:method)===value} disabled={busy||!!attempt} onClick={()=>setMethod(value)}><span className={`checkout-payment-icon ${value.toLowerCase()}`} aria-hidden="true">{value==='ALIPAY'?t('支'):<svg viewBox="0 0 32 32" fill="currentColor"><path d="M13 4C6.9 4 2 7.9 2 12.7c0 2.5 1.3 4.7 3.4 6.3l-.8 3.2 3.8-1.8c1.4.5 3 .9 4.6.9 6.1 0 11-3.9 11-8.6S19.1 4 13 4Z"/><path d="M30 21c0-4-4-7.3-9-7.3S12 17 12 21s4 7.3 9 7.3c1.4 0 2.6-.2 3.8-.7l3.1 1.4-.7-2.7A6.8 6.8 0 0 0 30 21Z" stroke="#11ba57"/><g fill="#11ba57"><circle cx="9" cy="10" r="1.3"/><circle cx="17" cy="10" r="1.3"/><circle cx="18" cy="19" r="1"/><circle cx="24" cy="19" r="1"/></g></svg>}</span>{t(paymentNames[value])}<span className="checkout-method-check" aria-hidden="true">{(attempt?attempt.paymentMethod:method)===value?'✓':''}</span></button>)}</fieldset>
     <div className="checkout-total"><div><small>{t("应付金额")}</small><strong>¥ {groupedAmount(amount)}</strong><p className="checkout-points">{formatMessage("到账 {0} 积分", groupedAmount(points))}</p></div><button className="btn-primary" disabled={busy||(!attempt&&!valid)}>{busy?t('处理中…'):attempt?t('重试本次订单'):<>{t("立即支付 ")}<span aria-hidden="true">→</span></>}</button></div>
     {(!attempt||order?.status==='PENDING')&&<button type="button" className="checkout-back" disabled={busy} onClick={returnToAmount}><span aria-hidden="true">←</span>{t(" 返回充值中心")}</button>}
    </form></div>}
   {stage==='scan'&&order&&<div className="checkout-narrow checkout-centered checkout-scan"><header className="checkout-heading"><h1>{t(order.paymentMethod?formatMessage("使用{0}扫码完成支付", t(paymentNames[order.paymentMethod])):'扫码完成支付')}</h1></header>
    <figure className="recharge-qr"><QRCodeSVG value={url!} size={256} marginSize={4} level="M" role="img" aria-label={t("收钱吧支付二维码")}/><figcaption>{t(order.paymentMethod?formatMessage("{0}扫码付款", t(paymentNames[order.paymentMethod])):'扫码付款')}</figcaption></figure><p className="checkout-amount">¥ {groupedAmount(order.amount4)}</p><p className="checkout-points">{formatMessage("到账 {0} 积分", groupedAmount(order.expectedPoints4||expectedPoints(yuan(order.amount4||''),order.rateSnapshot4||'')))}</p><p className="checkout-waiting" role="status">{t("等待支付")}</p><PaymentExpiry expiresAt={order.expiresAt}/><button type="button" className="btn-secondary" disabled={busy} onClick={returnToAmount}><span aria-hidden="true">←</span>{t(" 返回充值中心")}</button>
   </div>}
   {stage==='processing'&&order&&<div className="checkout-narrow checkout-centered"><header className="checkout-heading"><p className="checkout-kicker">{t("05 · 支付处理中")}</p><h1>{t(label(order))}</h1></header><div className="checkout-spinner" aria-hidden="true"/><p role="status">{t(order.status==='PAID'?'正在确认积分到账，请稍候…':label(order))}</p><p className="checkout-muted">{formatMessage("订单：{0}", order.orderId)}</p></div>}
   {stage==='success'&&success&&<div className="checkout-result"><div className="checkout-success-grid"><div className="checkout-success-amount"><span className="checkout-check" aria-hidden="true"><svg viewBox="0 0 40 40"><path d="m9 20 8 8 15-17"/></svg></span><header className="checkout-heading"><p className="checkout-kicker">PAYMENT SUCCESSFUL</p><h1>{t("支付成功")}</h1><p role="status">{t("充值成功，积分已到账")}</p></header><strong>{formatMessage("+ {0} 积分", credit(success))}</strong><p>{formatMessage("已充值 ¥ {0}", formatAmount(success.amount4))}</p></div><section className="checkout-receipt" aria-label={t("订单详情")}><h2>{t("订单详情")}</h2>{orderDetails(success)}</section></div><div className="recharge-balance checkout-result-balance"><div><span>{t("当前可用余额")}</span><p aria-label={t("支付后可用余额")}><strong>{formatAmount(balance)}</strong>{t(" 积分")}</p></div></div><div className="checkout-result-actions"><button type="button" className="btn-secondary" onClick={()=>{setSuccess(null);setOrder(null);setStep('records');}}>{t("查看订单")}</button><button type="button" className="btn-primary" onClick={returnAccount}>{t("返回账户")}</button></div></div>}
   {stage==='records'&&<><header className="checkout-heading"><p className="checkout-kicker">HISTORY</p><h1>{t("订单记录")}</h1><p>{t("查看你的充值和消费记录")}</p></header><div className="history-switch" role="group" aria-label={t("订单记录类型")}><button type="button" aria-pressed={recordTab==='all'} onClick={()=>setRecordTab('all')}>{t("全部")}</button><button type="button" aria-pressed={recordTab==='orders'} onClick={()=>setRecordTab('orders')}>{t("充值记录")}</button><button type="button" aria-pressed={recordTab==='consumption'} onClick={()=>setRecordTab('consumption')}>{t("消费记录")}</button></div>
    {recordTab!=='orders'?<TransactionHistory key={recordTab} initialCategory={recordTab} hideCategories/>:<section aria-label={t("真实充值订单")}>{history.length===0?<p className="checkout-empty">{t("暂无充值订单")}</p>:<div className="table-wrap checkout-orders"><table><thead><tr><th>{t("时间")}</th><th>{t("订单")}</th><th>{t("类型")}</th><th>{t("金额")}</th><th>{t("积分")}</th><th>{t("状态")}</th></tr></thead><tbody>{history.map(item=><tr key={item.orderId}><td>{item.createdAt?new Date(item.createdAt).toLocaleString(currentLanguage(),{hour12:false}):'—'}</td><td>{item.orderId}</td><td>{t(item.source==='MANUAL'?'人工充值':'充值')}</td><td>¥ {formatAmount(item.amount4)}</td><td className="checkout-order-credit">{formatAmount(item.expectedPoints4)}</td><td><span className="status-pill">{t(label(item))}</span></td></tr>)}</tbody></table></div>}</section>}<button type="button" className="checkout-back" onClick={()=>setStep('account')}>{t("返回账户")}</button></>}
   {order&&!active(order)&&order.status!=='PAID'&&stage!=='records'&&<p role="status">{t(label(order))}</p>}
  </>}
 </section>;
 return <section className="real-recharge" aria-label={t(apiOnly?'API 充值':'真实账户充值')}>
  <div className="api-section-title"><h2>{t(apiOnly?'API 账户充值':'选择充值金额')}</h2><button className="btn-secondary" disabled={busy} onClick={()=>void run(refreshAccountAndOrders)}>{t("刷新账户与订单")}</button></div>
  {error&&<p role="alert">{t(error)}</p>}
  {!loaded&&!error&&<p role="status">{t("正在读取账户与充值规则…")}</p>}
  {loaded&&!owner&&<p>{t("请")}<Link to="/user/login">{t("登录")}</Link>{t("后充值。")}</p>}
  {options&&owner&&<>
   <p>{t(bound?formatMessage("当前身份：{0}，后续充值不能更改。", t(names[bound as Identity])):'身份尚未确定，创建订单或扫码不会绑定身份。')}</p>
   {incompatible?<p role="status">{t("当前为")}{t(names[bound as Identity])}{t("账户，不能切换为 API。")}<Link to="/account/recharge">{t("前往账户充值")}</Link></p>:<>
    {!bound&&!apiOnly&&<div className="category-row" role="group" aria-label={t("充值身份")}>{(['PERSONAL','ENTERPRISE'] as const).map(value=><button type="button" key={value} aria-pressed={type===value} className={type===value?'active':''} disabled={busy||!!attempt} onClick={()=>choose(value)}>{t(names[value])}</button>)}</div>}
    {!bound&&<p>{formatMessage("首次充值确认到账后确定为{0}账户，并自动关联本人的平台主 Key。", t(names[type]))}</p>}
    {order&&<div className="api-order" aria-live="polite"><strong>{t(label(order))}</strong><p>{formatMessage("订单：{0}", order.orderId)}</p><p>{formatMessage("支付金额：¥ {0} · 预计到账积分：{1}", formatAmount(order.amount4), formatAmount(order.expectedPoints4||expectedPoints(yuan(order.amount4||''),order.rateSnapshot4||'')))}</p><p>{formatMessage("订单兑换比例：1 元 = {0} 积分", formatAmount(order.rateSnapshot4))}</p>
      {order.status==='PENDING'&&<button type="button" className="btn-secondary" disabled={busy} onClick={returnToAmount}>{t("返回选择金额")}</button>}
      {url&&<figure className="recharge-qr"><QRCodeSVG value={url} size={256} marginSize={4} level="M" role="img" aria-label={t("收钱吧支付二维码")}/><figcaption>{t(order.paymentMethod?formatMessage("{0}扫码付款", t(paymentNames[order.paymentMethod])):'扫码付款')}</figcaption></figure>}
      {order.status==='PENDING'&&<p>{formatMessage("有效期至 {0}。付款后自动查询到账状态，也可手动刷新。", order.expiresAt?new Date(order.expiresAt).toLocaleString(currentLanguage()):'—')}</p>}
      {order.fulfilled&&<Link to="/account/profile#keys">{t("查看已关联的本人 Key 与余额")}</Link>}
    </div>}
    {(!order||!active(order)||order.status==='PENDING'&&!url)&&<form onSubmit={submit}>
     <div className="category-row" role="group" aria-label={t("支付方式")}>{(['ALIPAY','WECHAT'] as const).map(value=><button key={value} type="button" aria-pressed={(attempt?attempt.paymentMethod:method)===value} disabled={busy||!!attempt} onClick={()=>setMethod(value)}>{t(paymentNames[value])}</button>)}</div>
     <div className="recharge-grid">{rule?.presetAmounts4?.map(value=><button key={value} type="button" disabled={busy||!!attempt} aria-pressed={amount===yuan(value)} onClick={()=>setAmount(yuan(value))}>¥ {formatAmount(value)}</button>)}</div>
     <div className="recharge-custom"><label>{t("自定义充值金额（元）")}<input ref={amountInput} type="number" required step="0.01" min={rule?.minimumAmount4||undefined} value={amount} disabled={busy||!!attempt} onChange={event=>setAmount(event.target.value)}/></label>
     </div><p>{formatMessage("本次最低 ¥ {0} · 1 元 = {1} 积分", formatAmount(rule?.minimumAmount4), formatAmount(options.pointsPerYuan4))}</p>
     <p aria-live="polite">{formatMessage("支付金额：¥ {0} · 预计到账积分：{1}", formatAmount(amount||'—'), formatAmount(points))}</p>
     <button className="btn-primary" disabled={busy||(!attempt&&!valid)}>{t(busy?'处理中…':attempt?'重试本次订单':'创建支付订单')}</button>
    </form>}
   </>}
   <section aria-label={t("真实充值订单")}><h3>{t("最近充值订单")}</h3>{history.length===0?<p>{t("暂无充值订单")}</p>:<div className="table-wrap"><table><thead><tr><th>{t("订单")}</th><th>{t("身份")}</th><th>{t("人民币（元）")}</th><th>{t("积分快照")}</th><th>{t("来源")}</th><th>{t("状态")}</th></tr></thead><tbody>{history.map(item=><tr key={item.orderId}><td>{item.orderId}</td><td>{t(names[item.accountType as Identity])}</td><td>{formatAmount(item.amount4)}</td><td>{formatAmount(item.expectedPoints4)}</td><td>{t(item.source==='MANUAL'?'后台人工确认':'收钱吧')}</td><td>{t(label(item))}</td></tr>)}</tbody></table></div>}<Link to="/account/transactions">{t("查看真实入账流水")}</Link></section>
  </>}
  {success&&<RechargeSuccess amount={success.amount4!} onClose={()=>setSuccess(null)}/>}
 </section>;
}

function PaymentExpiry({expiresAt}:{expiresAt:string|null|undefined}) {
  useLanguage();
 const [now,setNow]=useState(Date.now());
 useEffect(()=>{const timer=window.setInterval(()=>setNow(Date.now()),1000);return()=>window.clearInterval(timer);},[]);
 const end=Date.parse(expiresAt||'');
 if(!Number.isFinite(end))return null;
 const seconds=Math.max(0,Math.ceil((end-now)/1000));
 return <p className="checkout-expiry">{t(seconds?formatMessage("请在 {0}:{1} 内完成支付", String(Math.floor(seconds/60)).padStart(2,'0'), String(seconds%60).padStart(2,'0')):'支付时间已结束，正在确认订单状态')}</p>;
}
