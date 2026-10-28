import type { InjectionKey } from 'vue';

export type PromotionUserOption = { value: string; label: string; detail?: string };
export type AttributionHistory = { version: number; previousInviterId?: string|null; previousInviterLabel?: string; directInviterLabel?: string; directInviterId: string; pointBeneficiaryId: string | null; cashPromoterId: string | null; cashTier: string; reason: string | null; operator: string | null; effectiveAt: string };
export type AttributionAdjustment={id:string;userId:string;user:string;previousInviter:string;inviter:string;operator:string;reason:string;time:string};
export type AdminAttributionAdapter = {
  records:(page:number,size:number)=>Promise<{items:AttributionAdjustment[];total:number}>;
  search: (query: string, offset: number) => Promise<{ items: PromotionUserOption[]; total: number }>;
  history: (userId: string, page: number) => Promise<{ items: AttributionHistory[]; total: number; currentVersion: number; currentInviterLabel?: string }>;
  save: (userId: string, inviterId: string, version: number, reason: string) => Promise<void>;
};
export const adminAttributionAdapterKey: InjectionKey<AdminAttributionAdapter> = Symbol('admin-attribution-adapter');

import {
  promotionReferralAdjustmentsGetApiV1AdminPromotionsOperationsReferralAdjustments as readAdjustments,
  userListGetApiV1AdminUsers as readUsers,
  userDetailGetApiV1AdminUsersId as readUser,
  promotionAttributionGetApiV1AdminPromotionsOperationsReferralsUserId as readAttribution,
  promotionRebindPostApiV1AdminPromotionsOperationsReferralsUserId as writeAttribution,
  promotionPayoutsGetApiV1AdminPromotionsPayouts as readPayouts,
  promotionCreatePostApiV1AdminPromotionsPayouts as createPayout,
  promotionDecidePostApiV1AdminPromotionsPayoutsIdDecision as decidePayout,
  promotionCompletePostApiV1AdminPromotionsPayoutsIdComplete as completePayout,
  promotionAccountGetApiV1AdminPromotionsPayoutsIdAccount as readAccount,
  promotionAccountsGetApiV1AdminPromotionsPayoutAccounts as readAccounts,
  promotionListPromotersGetApiV1AdminPromotionsPromoters as readPromoters,
  promotionReportGetApiV1AdminPromotionsWithdrawalReport as readReport,
  promotionAuditGetApiV1AdminPromotionsBenefitAudit as readAudit,
  promotionReconciliationGetApiV1AdminPromotionsReconciliation as readReconciliation,
  promotionPreviewPostApiV1AdminPromotionsReconciliationPreview as readPreview,
  type ResponsePromotionApplicationProductionPromotionOperationsPayout as Payout,
  type ResponsePromotionApplicationProductionPromotionOperationsPayoutPage as PayoutPage,
  type ResponsePromotionApplicationProductionPromotionOperationsReconciliation as Reconciliation
} from '../api';
import type { AdminWithdrawalAdapter, AdminWithdrawalView, AdminWithdrawalStatus } from './localWithdrawalFixtures';
import type { AdminWithdrawalReportAdapter, WithdrawalReportSnapshot, WithdrawalReportFilters } from './localWithdrawalReportFixtures';
import type { AdminBenefitAuditAdapter } from './localBenefitAuditFixtures';
import type { AdminReconciliationAdapter, ReconciledBalances } from './localReconciliationFixtures';

function required<T>(value: T | null | undefined): T { if (value === null || value === undefined) throw new Error('Incomplete promotion response'); return value; }
function decimal(value: string | null) { if (value === null || !/^-?(?:0|[1-9]\d*)\.\d{4}$/.test(value)) throw new Error('Invalid promotion amount'); return value; }
function count(value: number) { if (!Number.isSafeInteger(value) || value < 0) throw new Error('Invalid promotion count'); return value; }
function date(value: string | null) { const parsed = new Date(required(value)); if (!Number.isFinite(parsed.getTime())) throw new Error('Invalid promotion time'); return parsed.toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }); }
const statuses = ['WITHDRAWING', 'PENDING_PAYOUT', 'PAID', 'REJECTED'];
function payoutTypeLabel(value: string | null) { return ({ BANK_CARD: '银行卡', WECHAT: '微信', ALIPAY: '支付宝' } as Record<string, string>)[value ?? ''] ?? '未知收款类型'; }
function status(value: string | null): AdminWithdrawalStatus { if (!value || !statuses.includes(value)) throw new Error('Invalid payout status'); return value as AdminWithdrawalStatus; }
function payout(value: Payout): AdminWithdrawalView {
  return { id: required(value.id), promoter: required(value.promoterUserId), amount: decimal(value.amount4), status: status(value.status),
    payoutAccountSnapshot: [payoutTypeLabel(value.payoutType), value.maskedAccountName, value.maskedAccountReference].filter(Boolean).join(' · '),
    requestedAt: date(value.requestedAt), rejectionReason: value.rejectionReason ?? undefined,
    payoutAccountId: value.payoutAccountId ?? undefined, settlementMonth: value.settlementMonth ?? undefined, transferReference: value.transferReference ?? undefined, paidAt: value.paidAt ? date(value.paidAt) : undefined };
}
function report(value: PayoutPage): WithdrawalReportSnapshot {
  const groups = required(value.statusTotals);
  for (const group of groups) { status(group.status); count(group.count); decimal(group.amount4); }
  const bucket = (key: string) => { const group = groups.find(row => row.status === key); return { amount4: group ? decimal(group.amount4) : '0.0000', count: group ? count(group.count) : 0 }; };
  return { page: count(value.page), size: count(value.size), total: count(value.total),
    summary: { total: { amount4: decimal(value.totalAmount4), count: count(value.total) }, withdrawing: bucket('WITHDRAWING'), pendingPayout: bucket('PENDING_PAYOUT'), paid: bucket('PAID'), rejected: bucket('REJECTED') },
    entries: required(value.items).map(row => ({ id: required(row.id), promoter: required(row.promoterUserId), amount4: decimal(row.amount4), status: status(row.status), requestedDate: date(row.requestedAt).slice(0, 10), settlementMonth: row.settlementMonth ?? undefined, transferReference: row.transferReference ?? undefined, paidAt: row.paidAt ? date(row.paidAt) : undefined })) };
}
function balances(value: Reconciliation): ReconciledBalances {
  if (value.dryRun !== true || value.executable !== false) throw new Error('Invalid reconciliation preview');
  return { dryRun: true, executable: false, generatedAt: date(value.generatedAt), pendingRewardDeliveries: count(value.pendingRewardDeliveries),
    differences: required(value.differences).map(row => ({ userId: required(row.userId), dimension: required(row.dimension), expected4: decimal(row.expected4), actual4: decimal(row.actual4), difference4: decimal(row.difference4) })),
    debts: required(value.debts).map(row => ({ userId: required(row.userId), unit: required(row.unit), amount4: decimal(row.amount4) })) };
}

export function createHttpPromotionAdminAdapters() {
  let revision = 0;
  const pending = new Map<string, unknown>();
  async function checked<T>(call: () => Promise<{ data: T }>) {
    const started = revision;
    const result = await call();
    if (started !== revision) throw new Error('Session changed');
    return result.data;
  }
  async function command<B, T>(key: string, body: () => B, send: (body: B) => Promise<{ data: T }>) {
    const started = revision;
    const request = pending.has(key) ? pending.get(key) as B : body();
    pending.set(key, request);
    try { const value = await checked(() => send(request)); pending.delete(key); return value; }
    catch (error) {
      if (started !== revision) throw new Error('Session changed');
      if (error && typeof error === 'object' && 'status' in error && [400, 409, 422].includes(Number(error.status))) pending.delete(key);
      throw error;
    }
  }
  const attribution: AdminAttributionAdapter = {
    async records(page,size) {
      const started=revision,value=await checked(()=>readAdjustments({page,size})),items=required(value.items);
      const labels=new Map<string,string>();
      await Promise.all([...new Set(items.flatMap(row=>[row.userId,row.previousInviterId,row.inviterId,row.operatorId]).filter((id):id is string=>!!id))].map(async id=>{try{const user=await checked(()=>readUser(id));labels.set(id,user.nickname||id);}catch{labels.set(id,id);}}));
      if(started!==revision)throw new Error('Session changed');
      const label=(id:string|null)=>id?(labels.get(id)||id):'无';
      return {total:count(value.total),items:items.map(row=>({id:required(row.commandId),userId:required(row.userId),user:label(row.userId),previousInviter:label(row.previousInviterId),inviter:label(row.inviterId),operator:row.operatorId?label(row.operatorId):'—',reason:row.reason||'—',time:date(row.occurredAt)}))};
    },
    async search(search, offset) {
      const value = await checked(() => readUsers({ search, offset, limit: 20 }));
      return { total: count(value.total), items: required(value.items).filter(row => row.enabled).map(row => ({ value: required(row.id), label: row.nickname || required(row.id), detail: row.phone ?? undefined })) };
    },
    async history(userId, page) {
      const value = await checked(() => readAttribution(userId));
      const history = required(value.history);
      const labels=new Map<string,string>();
      await Promise.all([...new Set([value.current?.directInviterUserId,...history.slice(page*20,(page+1)*20+1).map(row=>row.binding?.directInviterUserId)].filter((id):id is string=>!!id))].map(async id=>{try{const user=await checked(()=>readUser(id));labels.set(id,user.nickname||id);}catch{labels.set(id,id);}}));
      return { currentInviterLabel:value.current?.directInviterUserId?labels.get(value.current.directInviterUserId):'无',total: history.length, currentVersion: value.current ? count(value.current.version) : 0,
        items: history.slice(page * 20, (page + 1) * 20).map((row,index) => { const binding = required(row.binding),previous=history[page*20+index+1]?.binding?.directInviterUserId; return { previousInviterId:previous,previousInviterLabel:previous?labels.get(previous):undefined,directInviterLabel:labels.get(required(binding.directInviterUserId)),version: count(binding.version), directInviterId: required(binding.directInviterUserId), pointBeneficiaryId: binding.pointBeneficiaryUserId, cashPromoterId: binding.level1PromoterUserId ?? binding.level2PromoterUserId, cashTier: binding.level1PromoterUserId ? 'LEVEL_1' : binding.level2PromoterUserId ? 'LEVEL_2' : 'NONE', reason: row.reason, operator: row.operatorId, effectiveAt: date(binding.effectiveAt) }; }) };
    },
    async save(userId, inviterId, version, reason) {
      await command(JSON.stringify(['rebind', userId, inviterId, version, reason]), () => ({ commandId: crypto.randomUUID(), inviterUserId: inviterId, expectedVersion: version, reason }), body => writeAttribution(userId, body));
    }
  };
  const withdrawals: AdminWithdrawalAdapter = {
    async query(filters) { const value = await checked(() => readPayouts({ status: filters.status || undefined, promoterUserId: filters.promoterUserId || undefined, month: filters.month || undefined, page: filters.page, size: 10 })); return { items: required(value.items).map(payout), page: count(value.page), size: count(value.size), total: count(value.total) }; },
    async promoters(query, page) {
      const items: { value: string; label: string }[] = [];
      const search = query.trim().toLocaleLowerCase();
      // ponytail: scan the promoter directory for exact name/phone search totals; move this join server-side when the directory grows.
      for (let sourcePage = 0, read = 0; ; sourcePage++) {
        const value = await checked(() => readPromoters({ page: sourcePage, size: 20 }));
        const rows = required(value.items);
        const users = await Promise.all(rows.filter(row => row.status === 'ENABLED').map(row => checked(() => readUser(required(row.userId)))));
        for (const user of users) {
          const label = [user.phone, user.nickname].filter(Boolean).join(' · ') || required(user.id);
          if (user.enabled && label.toLocaleLowerCase().includes(search)) items.push({ value: required(user.id), label });
        }
        read += rows.length;
        if (read >= count(value.total)) break;
        if (!rows.length) throw new Error('Incomplete promoter page');
      }
      return { total: items.length, items: items.slice(page * 20, (page + 1) * 20) };
    },
    async accounts(promoterUserId, page) { const value = await checked(() => readAccounts({ promoterUserId, page, size: 20 })); return { total: count(value.total), items: required(value.items).filter(row => row.status === 'ACTIVE').map(row => ({ value: required(row.id), label: [payoutTypeLabel(row.payoutType), row.maskedPayoutAccountName, row.maskedPayoutAccountReference].filter(Boolean).join(' · ') })) }; },
    async account(id) { const value = await checked(() => readAccount(id)); return { accountName: required(value.accountName), accountReference: required(value.accountReference), bankName: value.bankName, qrCodeReference: value.qrCodeReference, snapshotAt: date(value.snapshotAt) }; },
    async create(promoterUserId, settlementMonth, amount4, payoutAccountId, existingId) { return payout(await command(JSON.stringify(['create', promoterUserId, settlementMonth, amount4, payoutAccountId, existingId]), () => ({ commandId: crypto.randomUUID(), withdrawalId: existingId ?? crypto.randomUUID(), promoterUserId, settlementMonth, amount4, payoutAccountId }), createPayout)); },
    async approve(id, operationKey) { return payout(await command(JSON.stringify(['approve', id, operationKey]), () => ({ commandId: crypto.randomUUID(), approved: true, reason: null }), body => decidePayout(id, body))); },
    async reject(id, reason, operationKey) { return payout(await command(JSON.stringify(['reject', id, operationKey, reason]), () => ({ commandId: crypto.randomUUID(), approved: false, reason }), body => decidePayout(id, body))); },
    async markPaid(id, operationKey, proof) { if (!proof?.transferReference || !proof.paidAt) throw new Error('Payment proof is required'); return payout(await command(JSON.stringify(['complete', id, operationKey, proof]), () => ({ commandId: crypto.randomUUID(), transferReference: proof.transferReference, paidAt: proof.paidAt }), body => completePayout(id, body))); }
  };
  const reports: AdminWithdrawalReportAdapter = {
    live: true,
    async load() { return report(await checked(() => readReport({ page: 0, size: 10 }))); },
    async query(filters: WithdrawalReportFilters) { return report(await checked(() => readReport({ status: filters.status === 'ALL' ? undefined : filters.status, promoterUserId: filters.promoterKeyword.trim() || undefined, month: filters.month || undefined, page: filters.page ?? 0, size: 10 }))); }
  };
  const audit: AdminBenefitAuditAdapter = {
    async records(filters) { const value = await checked(() => readAudit({ unit: filters.unit === 'ALL' ? undefined : filters.unit, userId: filters.userId || undefined, reference: filters.reference || undefined, page: filters.page, size: 10 })); return { page: count(value.page), size: count(value.size), total: count(value.total), items: required(value.items).map(row => { if (row.unit !== 'CASH' && row.unit !== 'POINT') throw new Error('Invalid audit unit'); return { ...row, id: required(row.id), unit: row.unit, beneficiaryUserId: required(row.beneficiaryUserId), action: required(row.action), amount4: decimal(row.amount4), occurredAt: date(row.occurredAt) }; }) }; }
  };
  const reconciliation: AdminReconciliationAdapter = {
    async balances() { return balances(await checked(readReconciliation)); },
    async previewBalances(reason) { return balances(await checked(() => readPreview({ reason }))); }
  };
  return { attribution, withdrawals, reports, audit, reconciliation, reset() { revision++; pending.clear(); } };
}
