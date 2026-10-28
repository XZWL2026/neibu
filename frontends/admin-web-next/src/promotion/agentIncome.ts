import {
  promotionIncomeDraftGetApiV1AdminPromotionsAgentIncomeUserIdDraft as readDraft,
  promotionAdjustIncomeDraftPostApiV1AdminPromotionsAgentIncomeUserIdDraft as saveDraft,
  type ResponsePromotionApplicationProductionPromotionOperationsIncomeAdjustmentPage as AdjustmentPage,
  type ResponsePromotionApplicationProductionPromotionOperationsIncomeDraft as Draft,
  promotionDetailGetApiV1AdminPromotionsAgentDirectoryUserId as readDetail,
  promotionAgentIncomeGetApiV1AdminPromotionsAgentIncome as readIncome,
  promotionIncomeConsumptionsGetApiV1AdminPromotionsAgentIncomeUserIdConsumptions as readConsumptions,
  promotionIncomeAdjustmentsGetApiV1AdminPromotionsPayoutsIdAdjustments as readAdjustments,
  promotionPayoutsGetApiV1AdminPromotionsPayouts as readPayouts,
  promotionAccountsGetApiV1AdminPromotionsPayoutAccounts as readAccounts,
  promotionAccountGetApiV1AdminPromotionsPayoutsIdAccount as readAccount,
  promotionCreatePostApiV1AdminPromotionsPayouts as create,
  promotionAdjustIncomePostApiV1AdminPromotionsPayoutsIdAdjustments as adjust,
  promotionDecidePostApiV1AdminPromotionsPayoutsIdDecision as decide,
  promotionCompletePostApiV1AdminPromotionsPayoutsIdComplete as complete,
  promotionSearchGetApiV1AdminPromotionsAgentDirectory as search,
  promotionPromoterGetApiV1PromotionsPromotersUserId as readPromoter,
  userDetailGetApiV1AdminUsersId, iamListGetApiV1AdminAdmins,
  type ResponsePromotionApplicationProductionPromotionOperationsPayout as Payout,
  type ResponsePromotionApplicationProductionPromotionOperationsStatusTotal as StatusTotal,
  type PromotionAgentIncomeGetApiV1AdminPromotionsAgentIncomeParams as IncomeQuery,
} from '../api';
import {enterAdminPortal} from '../adminPortalEntry';
import {units4} from '../portalAmounts';

function required(value:string|null):string {if(!value)throw new Error('Missing income field');return value;}
function amount(value:string|null):string {if(units4(value)===null)throw new Error('Invalid income amount');return value!;}
function optionalAmount(value:string|null){return value===null?null:amount(value);}
function count(value:number){if(!Number.isSafeInteger(value)||value<0)throw new Error('Invalid income count');return value;}
function payout(row:Payout){return {...row,id:required(row.id),promoterUserId:required(row.promoterUserId),status:required(row.status),amount4:amount(row.amount4),baseAmount4:amount(row.baseAmount4),adjustmentAmount4:amount(row.adjustmentAmount4),finalAmount4:amount(row.finalAmount4),adjustmentVersion:count(row.adjustmentVersion)};}
function totals(rows:StatusTotal[]|null){return (rows||[]).map(r=>({status:required(r.status),count:count(r.count),amount4:amount(r.amount4)}));}
export type IncomePayout=ReturnType<typeof payout>;
export type IncomeRow={promoterUserId:string;promoterStatus:string;createdAt:string|null;downlineCount:number|null;consumptionCount:number|null;consumptionAmount4:string|null;commissionAmount4:string|null;availableAmount4:string|null;debtAmount4:string|null;status:string;payout:IncomePayout|null};
export type IncomePage={items:IncomeRow[];page:number;size:number;total:number;totalConsumptionAmount4:string|null;totalCommissionAmount4:string|null;pendingPayoutAmount4:string;statusTotals:ReturnType<typeof totals>};
export async function loadIncome(query:IncomeQuery):Promise<IncomePage>{
  if(query.month==='UNASSIGNED'){
    const params={...query,status:query.status==='ALL'||query.status==='UNSETTLED'?undefined:query.status};
    const data=(await readPayouts(params)).data;
    const summary=params.status?(await readPayouts({...params,status:undefined,page:0,size:1})).data:data;
    const statusTotals=totals(summary.statusTotals);
    const items=query.status==='UNSETTLED'?[]:await Promise.all((data.items||[]).map(async raw=>{
      const p=payout(raw),agent=(await readPromoter(p.promoterUserId)).data;
      return {promoterUserId:p.promoterUserId,promoterStatus:agent.status,createdAt:agent.createdAt,downlineCount:null,consumptionCount:null,consumptionAmount4:null,commissionAmount4:null,availableAmount4:null,debtAmount4:null,status:p.status,payout:p};
    }));
    return {items,page:data.page,size:data.size,total:query.status==='UNSETTLED'?0:count(data.total),totalConsumptionAmount4:null,totalCommissionAmount4:null,pendingPayoutAmount4:statusTotals.find(s=>s.status==='PENDING_PAYOUT')?.amount4||'0.0000',statusTotals};
  }
  const data=(await readIncome(query)).data;
  return {...data,total:count(data.total),totalConsumptionAmount4:optionalAmount(data.totalConsumptionAmount4),totalCommissionAmount4:amount(data.totalCommissionAmount4),pendingPayoutAmount4:amount(data.pendingPayoutAmount4),statusTotals:totals(data.statusTotals),items:(data.items||[]).map(r=>({...r,promoterUserId:required(r.promoterUserId),promoterStatus:required(r.promoterStatus),status:required(r.status),downlineCount:count(r.downlineCount),consumptionCount:count(r.consumptionCount),consumptionAmount4:optionalAmount(r.consumptionAmount4),commissionAmount4:amount(r.commissionAmount4),availableAmount4:amount(r.availableAmount4),debtAmount4:amount(r.debtAmount4),payout:r.payout?payout(r.payout):null}))};
}
export async function historicalRates(id:string,month:string):Promise<(string|null)[]>{
  if(month==='UNASSIGNED')return [];
  const rates=new Set<string|null>();let loaded=0;
  for(let page=0;;page++){
    const data=(await readConsumptions(id,{month,page,size:100})).data,total=count(data.total),items=data.items||[];
    if(!items.length&&loaded<total)throw new Error('Incomplete consumption history');
    for(const row of items){const value=row.cashRate6,n=Number(value);rates.add(value===null||!value.trim()||!Number.isFinite(n)||n<0||n>1?null:String(n));}
    loaded+=items.length;if(loaded>=total)break;
  }
  return [...rates].sort((a,b)=>a===null?1:b===null?-1:Number(a)-Number(b));
}
export async function currentRate(id:string){return (await readDetail(id)).data.level1CashRate6;}
export async function identity(id:string){return (await userDetailGetApiV1AdminUsersId(id)).data;}
export async function consumptions(id:string,month:string,page:number){
  const data=month==='UNASSIGNED'?{items:[],total:0}:(await readConsumptions(id,{month,page,size:10})).data;
  return {...data,total:count(data.total),items:(data.items||[]).map(r=>({...r,allocationId:required(r.allocationId),customerUserId:required(r.customerUserId),commissionAmount4:amount(r.commissionAmount4),paidAmount4:optionalAmount(r.paidAmount4)}))};
}
function adjustmentPage(data:AdjustmentPage){return {...data,total:count(data.total),items:(data.items||[]).map(r=>({...r,commandId:required(r.commandId),operatorId:required(r.operatorId),deltaAmount4:amount(r.deltaAmount4)}))};}
export async function adjustments(id:string,page:number){return adjustmentPage((await readAdjustments(id,{page,size:10})).data);}
function draft(data:Draft){if(!data.adjustments)throw new Error('Missing adjustment history');return {...data,withdrawalId:required(data.withdrawalId),promoterUserId:required(data.promoterUserId),settlementMonth:required(data.settlementMonth),adjustmentAmount4:amount(data.adjustmentAmount4),adjustmentVersion:count(data.adjustmentVersion),adjustments:adjustmentPage(data.adjustments)};}
export async function incomeDraft(id:string,month:string,page=0){return draft((await readDraft(id,{month,page,size:10})).data);}
export async function saveIncomeDraft(id:string,month:string,body:Parameters<typeof saveDraft>[1]){return draft((await saveDraft(id,body,{month})).data);}
export async function payoutHistory(promoterUserId:string,page:number){const data=(await readPayouts({promoterUserId,page,size:10})).data;return {...data,total:count(data.total),items:(data.items||[]).map(payout)};}
export async function accounts(promoterUserId:string,page:number){const data=(await readAccounts({promoterUserId,page,size:10})).data;return {...data,total:count(data.total),items:(data.items||[]).filter(r=>r.status==='ACTIVE').map(r=>({...r,id:required(r.id)}))};}
export async function accountDetails(id:string){return (await readAccount(id)).data;}
export async function createPayout(body:Parameters<typeof create>[0]){return payout((await create(body)).data);}
export async function adjustPayout(id:string,body:Parameters<typeof adjust>[1]){return payout((await adjust(id,body)).data);}
export async function decidePayout(id:string,body:Parameters<typeof decide>[1]){return payout((await decide(id,body)).data);}
export async function completePayout(id:string,body:Parameters<typeof complete>[1]){return payout((await complete(id,body)).data);}
export async function searchAgents(query:string,page:number){const data=(await search({search:query,page,size:20})).data;return {items:(data.items||[]).map(r=>({value:required(r.userId),label:r.nickname||required(r.userId),detail:r.phone||''})),total:count(data.total)};}
export async function credentialFor(userId:string){const rows=(await iamListGetApiV1AdminAdmins()).data;return rows?.find(r=>r.role==='PROMOTER'&&r.boundUserId===userId&&r.enabled)?.id||'';}
export const enterAgent=(credentialId:string)=>enterAdminPortal(credentialId);
