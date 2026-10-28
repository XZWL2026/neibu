import {beforeEach,expect,it,vi} from 'vitest';
const api=vi.hoisted(()=>({load:vi.fn(),save:vi.fn(),history:vi.fn(),customers:vi.fn()}));
vi.mock('../api',()=>({
  promotionBenefitSettingsGetApiV1AdminPromotionsBenefitSettings:api.load,
  promotionChangeBenefitSettingsPostApiV1AdminPromotionsBenefitSettings:api.save,
  promotionBenefitSettingsHistoryGetApiV1AdminPromotionsBenefitSettingsHistory:api.history,
  userDirectoryGetApiV1AdminUsersDirectory:api.customers
}));
import {createHttpBenefitSettingsAdapter} from './httpBenefitSettingsAdapter';
const snapshot=(version=3)=>({version,effectiveAt:'2026-09-15T03:00:00Z',operator:'admin',reason:'配置',global:{invitationPointsRate:'0.100000',defaultLevel1CashRate:'0.080000',defaultLevel2CashRate:'0.030000'},promoters:[{id:'real-agent',name:'真实代理',level1CashRate:null,level2CashRate:null}]});
beforeEach(()=>{vi.resetAllMocks();api.load.mockResolvedValue({data:snapshot()});api.save.mockResolvedValue({data:snapshot(4)});api.history.mockResolvedValue({data:[]});});
it('loads real settings and saves decimals with expected version and stable retry command',async()=>{
  const adapter=createHttpBenefitSettingsAdapter();
  expect((await adapter.load()).promoters[0].name).toBe('真实代理');
  api.save.mockRejectedValueOnce(new Error('lost response'));
  await expect(adapter.saveGlobalRate('defaultLevel1CashRate','0.08','配置')).rejects.toThrow('lost response');
  await adapter.saveGlobalRate('defaultLevel1CashRate','0.08','配置');
  expect(api.save.mock.calls[0][0]).toEqual(api.save.mock.calls[1][0]);
  expect(api.save.mock.calls[0][0]).toMatchObject({expectedVersion:3,promoterId:null,field:'defaultLevel1CashRate',rate:'0.08',reason:'配置'});
  await adapter.savePromoterRate('real-agent','level2CashRate',null,'继承');
  expect(api.save.mock.calls[2][0]).toMatchObject({expectedVersion:4,promoterId:'real-agent',rate:null});
});
it('conflicts require a new read and never automatically repeat a mutation',async()=>{
  const adapter=createHttpBenefitSettingsAdapter();await adapter.load();
  api.save.mockRejectedValueOnce(Object.assign(new Error('conflict'),{status:409}));
  await expect(adapter.saveGlobalRate('defaultLevel1CashRate','0.08','配置')).rejects.toThrow('conflict');
  expect(api.save).toHaveBeenCalledTimes(1);
  api.load.mockResolvedValue({data:snapshot(5)});
  await adapter.saveGlobalRate('defaultLevel1CashRate','0.08','配置');
  expect(api.save.mock.calls[1][0].expectedVersion).toBe(5);
  expect(api.save.mock.calls[1][0].commandId).not.toBe(api.save.mock.calls[0][0].commandId);
});
it('discards stale responses after identity reset',async()=>{
  let resolve!:(value:unknown)=>void;
  api.load.mockReturnValueOnce(new Promise(r=>resolve=r));
  const adapter=createHttpBenefitSettingsAdapter();const pending=adapter.load();adapter.reset();
  resolve({data:snapshot()});await expect(pending).rejects.toThrow('Session changed');
  await adapter.saveGlobalRate('defaultLevel1CashRate','0.08','配置');
  expect(api.load).toHaveBeenCalledTimes(2);
});
it('maps audited history and filters saved dates and promoter identifiers',async()=>{
  api.history.mockResolvedValue({data:[{version:4,scope:'PROMOTER_OVERRIDE',promoterId:'real-agent',invitationPointsRate:null,defaultLevel1CashRate:null,defaultLevel2CashRate:null,level1CashRate:'0.120000',level2CashRate:null,effectiveAt:'2026-09-15T03:00:00Z',operator:'admin',reason:'覆盖'}]});
  const result=await createHttpBenefitSettingsAdapter().queryHistory({scope:'PROMOTER_OVERRIDE',promoterKeyword:'real-agent',startDate:'2026-09-15',endDate:'2026-09-15'});
  expect(result.count).toBe(1);expect(result.records[0]).toMatchObject({maskedPromoter:'*** · PROMOTER-real-agent',promoterL1:'0.120000'});
});

it('maps customer rules and sends only that beneficiary field, preserving retry commands',async()=>{
 api.load.mockResolvedValue({data:{...snapshot(),customers:[{id:'customer',name:'客户',maskedPhone:'138****1111',invitationPointsRate:'0.120000',effectiveAt:'2026-09-20T03:00:00Z',enabled:true,accountType:'PERSONAL',cashAgent:false}]}});
 const adapter=createHttpBenefitSettingsAdapter();const loaded=await adapter.load();
 expect(loaded.customers?.[0]).toMatchObject({id:'customer',pointsRate:'0.120000',phone:'138****1111',effectiveAt:'2026-09-20 11:00:00'});
 api.save.mockRejectedValueOnce(new Error('lost reply'));
 await expect(adapter.saveCustomerRate('customer','0','调整收益比例')).rejects.toThrow('lost reply');
 await adapter.saveCustomerRate('customer','0','调整收益比例');
 expect(api.save.mock.calls[0][0]).toEqual(api.save.mock.calls[1][0]);
 expect(api.save.mock.calls[1][0]).toMatchObject({promoterId:'customer',field:'invitationPointsRate',rate:'0',expectedVersion:3});
 await adapter.saveCustomerRate('customer',null,'调整收益比例');
 expect(api.save.mock.calls[2][0]).toMatchObject({promoterId:'customer',field:'invitationPointsRate',rate:null,expectedVersion:4});
});
it('maps customer history and validates before/after rates',async()=>{
 const row={version:4,scope:'CUSTOMER_OVERRIDE',promoterId:'customer',invitationPointsRate:null,defaultLevel1CashRate:null,defaultLevel2CashRate:null,level1CashRate:null,level2CashRate:null,effectiveAt:'2026-09-20T03:00:00Z',operator:'admin',reason:'删除',field:'invitationPointsRate',beforeRate:'0.120000',afterRate:null};
 api.history.mockResolvedValue({data:[row]});const adapter=createHttpBenefitSettingsAdapter();
 expect((await adapter.loadHistory()).records[0]).toMatchObject({scope:'CUSTOMER_OVERRIDE',field:'invitationPointsRate',beforeRate:'0.120000',afterRate:null});
 api.history.mockResolvedValue({data:[{...row,beforeRate:'2'}]});await expect(adapter.loadHistory()).rejects.toThrow();
});
it('searches real enabled ordinary clients using the existing directory and excludes agents',async()=>{
 api.customers.mockResolvedValue({data:{items:[{id:'user',nickname:'真实客户',phone:'13800131111',enabled:true,accountType:'PERSONAL'},{id:'real-agent',nickname:'代理',phone:'13900000000',enabled:true,accountType:'PERSONAL'},{id:'disabled',nickname:'停用',enabled:false,accountType:'PERSONAL'}],total:3}});
 const adapter=createHttpBenefitSettingsAdapter();await adapter.load();
 const result=await adapter.searchCustomers('138',0);
 expect(api.customers).toHaveBeenCalledWith({search:'138',offset:0,limit:20,enabled:true});
 expect(result.items).toEqual([{value:'user',label:'真实客户',detail:'138****1111'}]);
 expect(result.total).toBe(3);
});
