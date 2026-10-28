import { randomUUID } from '../../../shared/randomUUID';
import { t, formatMessage, useLanguage, currentLanguage } from '../i18n';
import './LivePromotion.css';
import {createContext,useCallback,useContext,useEffect,useRef,useState,type ReactNode,type FormEvent} from 'react';
import {Link} from 'react-router-dom';
import {useUserSession,accountTypeLabels,accountStatusLabels} from '../userSession';
import {ApiError,promotionRewardsGetApiV1PromotionsRewards,promotionParticipationGetApiV1PromotionsParticipation,promotionSubmitPostApiV1PromotionsApplications} from '../api';

type Snapshot=Awaited<ReturnType<typeof promotionParticipationGetApiV1PromotionsParticipation>>['data'];
type Application=Parameters<typeof promotionSubmitPostApiV1PromotionsApplications>[0];
const Context=createContext<{data:Snapshot|null;busy:boolean;error:string;page:number;refresh:(page?:number)=>Promise<void>}|null>(null);
export function LivePromotionProvider({children}:{children:ReactNode}){
  useLanguage();
 const {fixture,userId,authenticated,accountType}=useUserSession();
 const [state,setState]=useState<{owner:string|null;data:Snapshot|null;busy:boolean;error:string;page:number}>({owner:null,data:null,busy:false,error:'',page:0});
 const version=useRef(0);
 const refresh=useCallback(async(page=0)=>{
  if(fixture||!authenticated||!userId)return;
  const request=++version.current;setState(old=>({owner:userId,data:old.owner===userId?old.data:null,busy:true,error:'',page}));
  try{const {data}=await promotionParticipationGetApiV1PromotionsParticipation({page,size:20});if(request===version.current)setState({owner:userId,data,busy:false,error:'',page});}
  catch{if(request===version.current)setState(old=>({...old,busy:false,error:'无法读取邀请与申请信息，请重试。'}));}
 },[fixture,userId,authenticated,accountType]);
 useEffect(()=>{void refresh();return()=>{version.current++;};},[refresh]);
 const visible=state.owner===userId&&!fixture&&authenticated?state:{owner:userId,data:null,busy:false,error:'',page:0};
 return <Context.Provider value={{...visible,refresh}}>{children}</Context.Provider>;
}
export function useLivePromotion(){return useContext(Context);}
export function LivePromotionLinks(){
  useLanguage();
 const {authenticated,accountType,status}=useUserSession(),view=useLivePromotion();
 if(!authenticated||status==='disabled')return null;
 return (view?.data?.eligible??!!accountType)?<Link to="/account/referrals">{t("邀请有礼")}</Link>:null;
}
function LoginRequired(){
  useLanguage();return <section className="promotion-page"><h1>{t("请先登录")}</h1><Link to="/user/login">{t("登录")}</Link></section>;}
export function LiveReferralsPage(){
  useLanguage();
 const session=useUserSession(),view=useLivePromotion();const rewards=useLiveRewards(!!view?.data?.eligible);const [copied,setCopied]=useState(false),[copyError,setCopyError]=useState(false);
 useEffect(()=>{setCopied(false);setCopyError(false);},[session.userId]);
 if(!session.authenticated)return <LoginRequired/>;
 const data=view?.data,code=data?.invitationCode;
 const link=code?new URL(`${import.meta.env.BASE_URL}home?ref=${encodeURIComponent(code)}`,window.location.origin).href:'';
 const list=data?.directInvites;
 return <section className="promotion-page referral-page live-promotion" aria-labelledby="referral-title">
 <header className="referral-hero"><div><h1 id="referral-title">{t("邀请有礼")}</h1><p>{t("邀请好友加入 YOMIO，一起创造更精彩的影像。")}</p></div><button className="referral-refresh" aria-label={t("刷新")} title={t("刷新")} disabled={view?.busy} onClick={()=>void view?.refresh(view.page)}>↻</button></header>
 {view?.error&&<p role="alert">{t(view.error)}</p>}
 {!data?<p>{t(view?.busy?'正在读取…':'邀请信息暂不可用')}</p>:!data.eligible?<p>{t("账户类型尚未设置，暂不具备邀请资格。")}</p>:<>
 <article className="invitation-card">
 <div className="invitation-identity"><h2>{t("我的邀请码")}</h2><div className="invitation-code-line"><strong>{code}</strong><span className="invitation-account-type">{t(accountTypeLabels[data.accountType as keyof typeof accountTypeLabels]||data.accountType)}</span></div>
 <div className="invitation-copy-row"><label className="invitation-link-field" htmlFor="real-invitation-link"><span className="sr-only">{t("邀请链接")}</span><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="m10 13 4-4M8 16l-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0m2 1 1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0"/></svg><input id="real-invitation-link" value={link} readOnly/></label><button className="btn-primary" aria-label={t(copyError?'复制失败，重试':copied?'已复制':'复制邀请链接')} disabled={!link} onClick={async()=>{try{await navigator.clipboard.writeText(link);setCopied(true);setCopyError(false);}catch{setCopyError(true);}}}>{t(copyError?'复制失败，重试':copied?'已复制':'复制链接')}</button></div></div>
 <dl className="invitation-stats"><div aria-label={t("已邀请好友")}><dt>{t("已邀请好友")}</dt><dd><strong>{list?.total??0}</strong><span>{t("人")}</span></dd></div><div aria-label={t("累计奖励")}><dt>{t("累计奖励")}</dt><dd><strong>{rewards.snapshot?rewardTotal(rewards.snapshot.earnedPoints4):'—'}</strong><span>{t("积分")}</span></dd></div></dl></article>
 <div className="referral-records-grid"><section className="direct-invites" aria-labelledby="direct-invites-title"><header className="referral-panel-heading"><h2 id="direct-invites-title">{t("邀请记录")}</h2><span>{t("共 ")}<strong>{list?.total??0}</strong>{t(" 人")}</span></header><div className="referral-records-body" role="region" aria-label={t("邀请记录内容")} tabIndex={0}><div className="referral-table-wrap"><table><thead><tr><th>{t("用户")}</th><th>{t("手机号")}</th><th>{t("加入时间")}</th><th>{t("状态")}</th></tr></thead><tbody>{list?.items?.map((item,index)=><tr key={index}><td>{item.nickname}</td><td>{item.maskedPhone}</td><td>{item.joinedAt?new Date(item.joinedAt).toLocaleString(currentLanguage(),{hour12:false}):'—'}</td><td><span className="invitation-joined">{t("已加入")}</span></td></tr>)}{!list?.items?.length&&<tr><td colSpan={4}>{t("暂无直接邀请用户")}</td></tr>}</tbody></table></div>
 {((list?.total??0)>20||(view?.page??0)>0)&&<div className="account-actions"><button className="btn-secondary" disabled={view?.busy||!view?.page} onClick={()=>void view?.refresh(view.page-1)}>{t("上一页")}</button><span>{formatMessage("第 {0} 页", (view?.page??0)+1)}</span><button className="btn-secondary" disabled={view?.busy||((view?.page??0)+1)*20>=(list?.total??0)} onClick={()=>void view?.refresh(view.page+1)}>{t("下一页")}</button></div>}</div></section>
 <LiveRewardRecords {...rewards}/></div>
 </>}
 </section>;
}

export function LiveApplicationPage(){
  useLanguage();
 const {userId,authenticated,status}=useUserSession(),view=useLivePromotion();
 const [reason,setReason]=useState(''),[channel,setChannel]=useState(''),[agreed,setAgreed]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(''),[attempt,setAttempt]=useState<Application|null>(null);
 const lock=useRef(false),owner=useRef(userId);owner.current=userId;
 const storageKey=userId?`xzwl-agent-application:${userId}`:'';
 useEffect(()=>{lock.current=false;setBusy(false);setAttempt(null);setReason('');setChannel('');setAgreed(false);setError('');if(!storageKey)return;try{const saved=JSON.parse(sessionStorage.getItem(storageKey)||'null');if(saved&&typeof saved.commandId==='string'&&typeof saved.applicationId==='string'&&typeof saved.reason==='string'&&typeof saved.plannedChannel==='string'&&saved.agreementsAccepted===true){setAttempt(saved);setReason(saved.reason);setChannel(saved.plannedChannel);setAgreed(true);}}catch{setError('申请草稿读取失败，请重试。');}},[storageKey]);
 useEffect(()=>{if(attempt?.applicationId&&view?.data?.application?.id===attempt.applicationId){try{sessionStorage.removeItem(storageKey);}catch{}setAttempt(null);}},[view?.data?.application?.id,attempt,storageKey]);
 async function submit(event:FormEvent){event.preventDefault();if(lock.current||!userId)return;
  if(!attempt&&(!reason.trim()||!channel.trim()||!agreed)){setError('请填写申请理由、推广渠道并确认协议。');return;}
  const current=userId,body=attempt||{commandId:randomUUID(),applicationId:randomUUID(),reason:reason.trim(),plannedChannel:channel.trim(),agreementsAccepted:true};
  lock.current=true;setBusy(true);setError('');setAttempt(body);try{sessionStorage.setItem(storageKey,JSON.stringify(body));}catch{/* Keep the exact request in memory. */}
  try{await promotionSubmitPostApiV1PromotionsApplications(body);if(owner.current!==current)return;try{sessionStorage.removeItem(storageKey);}catch{}setAttempt(null);await view?.refresh();}
  catch(cause){if(owner.current!==current)return;if(cause instanceof ApiError&&[400,422].includes(cause.status)){try{sessionStorage.removeItem(storageKey);}catch{}setAttempt(null);}setError('申请提交未完成，请重试；已保存的请求不会重复创建。');}
  finally{lock.current=false;if(owner.current===current)setBusy(false);}
 }
 if(!authenticated)return <LoginRequired/>;
 const data=view?.data,application=data?.application;
 return <section className="promotion-page live-promotion"><p className="eyebrow">AGENT APPLICATION</p><h1>{t(data?.promoter?'代理身份':application?.status==='PENDING'?'代理申请审核中':application?.status==='REJECTED'?'重新申请成为代理':'申请成为代理')}</h1>
 <button className="btn-secondary" disabled={busy||view?.busy} onClick={()=>void view?.refresh()}>{t("刷新")}</button>
 {(error||view?.error)&&<p role="alert">{t(error||view?.error)}</p>}
 {!data?<p>{t("正在读取申请状态…")}</p>:data.promoter?<p>{t(data.promoter.status==='ENABLED'?'你已成为代理人。':'代理身份已停用。')}</p>:application?.status==='PENDING'?<p>{formatMessage("提交时间：{0}", application.submittedAt?new Date(application.submittedAt).toLocaleString(currentLanguage()):'—')}</p>:status==='disabled'?<p>{t(accountStatusLabels[status])}</p>:<>
 {application?.status==='REJECTED'&&<p>{formatMessage("拒绝原因：{0}", application.rejectionReason)}</p>}
 <form className="promotion-form" onSubmit={submit}><label htmlFor="agent-reason">{t("申请理由")}</label><textarea id="agent-reason" maxLength={2000} required value={reason} disabled={busy||!!attempt} onChange={e=>setReason(e.target.value)}/><label htmlFor="agent-channel">{t("计划推广渠道")}</label><textarea id="agent-channel" maxLength={256} required value={channel} disabled={busy||!!attempt} onChange={e=>setChannel(e.target.value)}/><label className="promotion-agreement"><input type="checkbox" checked={agreed} disabled={busy||!!attempt} onChange={e=>setAgreed(e.target.checked)}/>{t("确认推广规则与佣金结算协议")}</label><button className="btn-primary" disabled={busy||view?.busy} type="submit">{t(busy?'提交中…':attempt?'重试原申请':'提交推广申请')}</button></form>
 </>}
 </section>;
}

type RewardSnapshot = {
 earnedPoints4: string; postedPoints4: string; reversedPoints4: string; debtPoints4: string; page: number; size: number; total: number;
 items: { eventId: string; action: string; points4: string; status: string; settlementId: string | null; allocationId: string | null; orderId: string | null; creditedPoints4: string | null; recoveredPoints4: string | null; debtPoints4: string | null; occurredAt: string | null }[];
};
function rewardTotal(value:string){return value.replace(/(\.\d{2})00$/, '$1');}
export function LiveRewardRecords({snapshot,busy,error,onPage}:{snapshot:RewardSnapshot|null;busy:boolean;error:string;onPage:(page:number)=>void}) {
  useLanguage();
 return <section className="invitation-rewards" aria-labelledby="live-rewards-title"><header className="referral-panel-heading"><h2 id="live-rewards-title">{t("奖励记录")}</h2><span className="referral-reward-total">{t("累计奖励 ")}<strong>{t(snapshot?formatMessage("{0} 积分", rewardTotal(snapshot.earnedPoints4)):'—')}</strong></span><button className="referral-refresh" aria-label={t("刷新积分记录")} title={t("刷新积分记录")} disabled={busy} onClick={()=>onPage(snapshot?.page??0)}>↻</button></header><div className="referral-records-body" role="region" aria-label={t("奖励记录内容")} tabIndex={0}>
 {error&&<p role="alert">{t(error)}</p>}
 {snapshot?<>{snapshot.items.length>0?<><div className="referral-table-wrap"><table><caption>{t("邀请积分奖励明细")}</caption><thead><tr><th>{t("动作")}</th><th>{t("奖励积分")}</th><th>{t("到账积分")}</th><th>{t("收回积分")}</th><th>{t("待追回")}</th><th>{t("状态")}</th><th>{t("结算 / 订单")}</th><th>{t("时间")}</th></tr></thead><tbody>
 {snapshot.items.map(item=><tr key={item.eventId}><td>{t(item.action==='GRANT'?'奖励发放':item.action==='REVERSE'?'奖励冲回':item.action)}</td><td>{item.points4}</td><td>{item.creditedPoints4??'—'}</td><td>{item.recoveredPoints4??'—'}</td><td>{item.debtPoints4??'—'}</td><td>{t(['POSTED','DELIVERED'].includes(item.status)?'已到账':item.status==='PENDING'?'待到账':item.status)}</td><td>{item.settlementId??'—'} / {item.orderId??'—'}</td><td>{item.occurredAt?new Date(item.occurredAt).toLocaleString(currentLanguage()):'—'}</td></tr>)}
 </tbody></table></div>
</>:<div className="referral-reward-empty"><svg viewBox="0 0 48 48" fill="none" stroke="currentColor" strokeWidth="2.6" aria-hidden="true"><path d="M7 20h34v9H7zM11 29v16h26V29M24 20v25M24 20h-7a6 6 0 1 1 5-9l2 9Zm0 0h7a6 6 0 1 0-5-9l-2 9Z"/></svg><strong>{t("暂无积分奖励记录")}</strong><p>{t("好友产生符合条件的消费后，奖励将在这里显示。")}</p></div>}{(snapshot.page>0||snapshot.total>snapshot.size)&&<div className="account-actions"><button className="btn-secondary" disabled={busy||!snapshot.page} onClick={()=>onPage(snapshot.page-1)}>{t("上一页积分")}</button><span>{formatMessage("共 {0} 条", snapshot.total)}</span><button className="btn-secondary" disabled={busy||(snapshot.page+1)*snapshot.size>=snapshot.total} onClick={()=>onPage(snapshot.page+1)}>{t("下一页积分")}</button></div>}</>:busy?<p>{t("正在读取积分记录…")}</p>:null}
 </div></section>;
}

function useLiveRewards(enabled:boolean) {
 const {userId,authenticated}=useUserSession();
 const [state,setState]=useState<{owner:string|null;snapshot:RewardSnapshot|null;busy:boolean;error:string}>({owner:null,snapshot:null,busy:false,error:''});
 const revision=useRef(0);
 const load=useCallback(async(page=0)=>{
  if(!enabled||!userId||!authenticated)return;
  const started=++revision.current;setState(old=>({owner:userId,snapshot:old.owner===userId?old.snapshot:null,busy:true,error:''}));
  try {
   const {data}=await promotionRewardsGetApiV1PromotionsRewards({page,size:20});
   const amount=(value:string|null)=>{if(value===null||!/^-?(?:0|[1-9]\d*)\.\d{4}$/.test(value))throw new Error('Invalid reward amount');return value;};
   if(!data.items||!Number.isSafeInteger(data.total)||data.total<0||!Number.isSafeInteger(data.page)||data.page<0||!Number.isSafeInteger(data.size)||data.size<=0)throw new Error('Invalid reward page');
   const snapshot:RewardSnapshot={earnedPoints4:amount(data.earnedPoints4),postedPoints4:amount(data.postedPoints4),reversedPoints4:amount(data.reversedPoints4),debtPoints4:amount(data.debtPoints4),page:data.page,size:data.size,total:data.total,items:data.items.map(item=>{
    if(!item.eventId||!item.action||!item.status)throw new Error('Incomplete reward record');
    return {...item,eventId:item.eventId,action:item.action,status:item.status,points4:amount(item.points4),creditedPoints4:item.creditedPoints4===null?null:amount(item.creditedPoints4),recoveredPoints4:item.recoveredPoints4===null?null:amount(item.recoveredPoints4),debtPoints4:item.debtPoints4===null?null:amount(item.debtPoints4)};
   })};
   if(started===revision.current)setState({owner:userId,snapshot,busy:false,error:''});
  }catch{if(started===revision.current)setState(old=>({...old,busy:false,error:'积分奖励记录加载失败，请重试。'}));}
 },[userId,authenticated,enabled]);
 useEffect(()=>{void load();return()=>{revision.current++;};},[load]);
 const visible=state.owner===userId&&authenticated&&enabled?state:{snapshot:null,busy:false,error:''};
 return {...visible,onPage:(page:number)=>void load(page)};
}
