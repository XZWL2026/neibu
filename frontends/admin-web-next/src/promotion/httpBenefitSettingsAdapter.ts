import {
  promotionBenefitSettingsGetApiV1AdminPromotionsBenefitSettings as readSettings,
  promotionChangeBenefitSettingsPostApiV1AdminPromotionsBenefitSettings as writeSettings,
  promotionBenefitSettingsHistoryGetApiV1AdminPromotionsBenefitSettingsHistory as readHistory,
  userDirectoryGetApiV1AdminUsersDirectory as searchUsers,
  type RequestPromotionInterfacesHttpBenefitSettingsControllerBenefitRateBody as Change,
  type ResponsePromotionApplicationProductionPromotionStoreBenefitSettings as Settings
} from '../api';
import {copyValidBenefitSettingsSnapshot,copyValidBenefitSettingsHistorySnapshot,
  type AdminBenefitSettingsAdapter,type BenefitSettingsHistoryRecord,type Rate6} from './localBenefitSettingsFixtures';

function required(value:string|null) {if(value===null)throw new Error('Invalid benefit settings response');return value;}
function date(value:string|null) {
  const parsed=new Date(required(value));
  if(!Number.isFinite(parsed.getTime()))throw new Error('Invalid benefit settings date');
  return parsed.toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'});
}
function snapshot(value:Settings) {
  if(!Number.isSafeInteger(value.version)||value.version<1||!value.global||!value.promoters)throw new Error('Invalid benefit settings response');
  return copyValidBenefitSettingsSnapshot({version:`BENEFIT-V${value.version}`,effectiveAt:date(value.effectiveAt),
    operator:required(value.operator),reason:required(value.reason),
    global:{invitationPointsRate:required(value.global.invitationPointsRate),defaultLevel1CashRate:required(value.global.defaultLevel1CashRate),defaultLevel2CashRate:required(value.global.defaultLevel2CashRate)},
    promoters:value.promoters.map(p=>({id:required(p.id),name:p.name||required(p.id),phone:p.maskedPhone,effectiveAt:p.effectiveAt?date(p.effectiveAt):null,enabled:p.enabled,level1CashRate:p.level1CashRate,level2CashRate:p.level2CashRate})),
    customers:(value.customers??[]).map(c=>({id:required(c.id),name:c.name||required(c.id),phone:c.maskedPhone,pointsRate:required(c.invitationPointsRate),effectiveAt:c.effectiveAt?date(c.effectiveAt):null,enabled:c.enabled&&["PERSONAL","ENTERPRISE","API"].includes(c.accountType??""),cashAgent:c.cashAgent}))});
}

export function createHttpBenefitSettingsAdapter():AdminBenefitSettingsAdapter & {reset:()=>void} {
  let version:number|undefined,revision=0;
  let cashAgents=new Set<string>();
  const pending=new Map<string,Change>();
  const current=(started:number)=>{if(started!==revision)throw new Error('Session changed');};
  async function load() {
    const started=revision,result=await readSettings();current(started);
    const value=snapshot(result.data);version=result.data.version;cashAgents=new Set(value.promoters.filter(p=>p.enabled!==false).map(p=>p.id));return value;
  }
  async function save(promoterId:string|null,field:string,rate:string|null,reason:string) {
    const started=revision;
    if(version===undefined)await load();
    current(started);
    const key=JSON.stringify([promoterId,field,rate,reason]);
    const command=pending.get(key)??{commandId:crypto.randomUUID(),expectedVersion:version,promoterId,field,rate,reason};
    pending.set(key,command);
    try {
      const result=await writeSettings(command);current(started);
      const value=snapshot(result.data);version=result.data.version;pending.delete(key);return value;
    } catch(error) {
      current(started);
      if(error && typeof error==='object' && 'status' in error && error.status===409) {pending.delete(key);version=undefined;}
      throw error;
    }
  }
  async function loadHistory() {
    const started=revision,result=await readHistory();current(started);
    const records=result.data.map((row):BenefitSettingsHistoryRecord=>{
      if(row.scope!=='GLOBAL' && row.scope!=='PROMOTER_OVERRIDE' && row.scope!=='CUSTOMER_OVERRIDE')throw new Error('Invalid benefit settings history');
      return {version:`BENEFIT-V${row.version}`,scope:row.scope,field:row.field,
        beforeRate:row.beforeRate,afterRate:row.field==null?undefined:row.afterRate,
        maskedPromoter:row.promoterId===null?null:`*** · PROMOTER-${row.promoterId}`,
        point:row.invitationPointsRate as Rate6|null,defaultL1:row.defaultLevel1CashRate as Rate6|null,defaultL2:row.defaultLevel2CashRate as Rate6|null,
        promoterL1:row.level1CashRate as Rate6|null,promoterL2:row.level2CashRate as Rate6|null,
        effectiveAt:date(row.effectiveAt),admin:required(row.operator),reason:required(row.reason)};
    });
    return copyValidBenefitSettingsHistorySnapshot({count:records.length,records});
  }
  return {load,loadHistory,
    reset(){revision++;version=undefined;pending.clear();cashAgents.clear();},
    saveGlobalRate:(field,rate,reason)=>save(null,field,rate,reason),
    savePromoterRate:(id,field,rate,reason)=>save(id,field,rate,reason),
    saveCustomerRate:(id,rate,reason)=>save(id,'invitationPointsRate',rate,reason),
    async searchCustomers(query,offset) {
      const started=revision;
      const {data}=await searchUsers({search:query,offset,limit:20,enabled:true});current(started);
      if(!Array.isArray(data.items)||!Number.isSafeInteger(data.total)||data.total<0)throw new Error('Invalid customer directory');
      return {total:data.total,items:data.items.filter(u=>u.id&&u.enabled&&['PERSONAL','ENTERPRISE','API'].includes(u.accountType??'')&&!cashAgents.has(u.id)).map(u=>({
        value:required(u.id),label:u.nickname||required(u.id),detail:u.phone?u.phone.replace(/^\+86/,'').replace(/^(.{3}).*(.{4})$/,'$1****$2'):''
      }))};
    },
    async queryHistory(filters) {
      const history=await loadHistory();
      const records=history.records.filter(row=>(filters.scope==='ALL'||row.scope===filters.scope)
        &&(!filters.promoterKeyword||row.maskedPromoter?.toLowerCase().includes(filters.promoterKeyword.trim().toLowerCase()))
        &&(!filters.startDate||row.effectiveAt.slice(0,10)>=filters.startDate)
        &&(!filters.endDate||row.effectiveAt.slice(0,10)<=filters.endDate));
      return {count:records.length,records};
    }};
}
