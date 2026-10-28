import { t, formatMessage, useLanguage, currentLanguage } from '../i18n';
import { formatAmount } from '../../../shared/formatAmount';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ApiError, refreshSession, accessListGetApiV1AccessKeys, billingBalanceGetApiV1BillingWalletsKeyId,
  billingEntriesGetApiV1BillingWalletsKeyIdLedger, mediaGetGetApiV1MediaTasksTaskId,
  paymentGetGetApiV1PaymentsRechargesId,
  type ResponseBillingApplicationLedgerBillingLedgerStoreEntry as Entry
} from '../api';
import './transaction-history.css';

type Category = 'all' | 'consumption' | 'recharge';
type LedgerRow = Entry & { walletId: string; keyKind: string | null };
type DisplayRow = LedgerRow & { business: string; status: string; money: string; model: string; parameters: string };
const pageSize = 10;
const ledgerLimit = 100;
const categories = { all: '全部记录', consumption: '消耗历史', recharge: '充值历史' };

function points(value: string | null) {
  if (!value) return '—';
  return value.startsWith('-') || /^0(?:\.0+)?$/.test(value) ? value : `+${value}`;
}
function time(value: string | null) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString(currentLanguage(), { hour12: false });
}

async function describe(row: LedgerRow, signal: AbortSignal): Promise<DisplayRow> {
  const refund = row.kind === 'REFUND';
  const result = { ...row, business: refund ? '积分退回' : '积分消耗', status: refund ? '已退回' : '已扣除', money: row.kind === 'CONSUME' ? row.amount4 ?? '—' : '—', model: '—', parameters: '' };
  if (row.kind === 'CONSUME' || refund) {
    if (row.keyKind === 'API') return { ...result, business: refund ? '积分退回' : 'API 调用' };
    if (row.keyKind === 'MEDIA' && row.requestId) {
      // MediaTaskWorker charges using task.id(); do not infer task types from identifier text.
      try {
        const taskId = refund && row.requestId.endsWith('-refund') ? row.requestId.slice(0, -7) : row.requestId;
        const { data: task } = await mediaGetGetApiV1MediaTasksTaskId(taskId, { signal });
        if (!refund && task.type === 'IMAGE') result.business = '生图';
        if (!refund && task.type === 'VIDEO') result.business = '生视频';
        result.model = task.modelName || task.model || '—';
        result.parameters = [task.resolution, task.type === 'VIDEO' && task.durationSeconds > 0 ? `${task.durationSeconds}秒` : null].filter(Boolean).join(' · ');
      } catch (error) {
        // Older ledger operations may have no corresponding media task.
        if (!(error instanceof ApiError && error.status === 404)) throw error;
      }
    }
    return result;
  }
  if (!row.requestId) throw new Error('Missing recharge order identifier');
  const { data: order } = await paymentGetGetApiV1PaymentsRechargesId(row.requestId, { signal });
  if (row.kind === 'CASH_REFUND') {
    // CASH_REFUND is recorded when points are reserved, before the cash refund completes.
    return { ...result, business: '现金退款',
      money: order.status === 'REFUNDED' && order.amount4 ? `-${order.amount4}` : '—',
      status: order.status === 'REFUNDED' ? '原路退款成功'
        : order.status === 'REFUND_PENDING' ? '原路退款处理中' : '退款状态待确认' };
  }
  return { ...result, business: order.source === 'MANUAL' ? '人工充值' : '充值', money: order.amount4 ?? '—', status: '充值到账' };
}

function totalPoints(values: (string | null)[], negate = false) {
  if (values.some(value => !value || !/^-?\d+\.\d{4}$/.test(value))) return '—';
  const total = values.reduce((sum, value) => sum + BigInt(value!.replace('.', '')), 0n) * (negate ? -1n : 1n);
  const absolute = total < 0n ? -total : total;
  return `${total < 0n ? '-' : ''}${absolute / 10000n}.${String(absolute % 10000n).padStart(4, '0')}`;
}
function groupedPoints(value: string | undefined) {
  return formatAmount(value).replace(/^(-?\d+)/, whole => whole.replace(/\B(?=(\d{3})+(?!\d))/g, ','));
}
function HistoryIcon({kind}: {kind: string}) {
  useLanguage();
  return <svg viewBox="0 0 32 32" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {kind === 'balance' ? <><ellipse cx="16" cy="8" rx="11" ry="5"/><path d="M5 8v7c0 7 22 7 22 0V8M5 16v7c0 7 22 7 22 0v-7"/></> : kind === 'spent' ? <><path strokeWidth="4" d="M6 25V19M13 25V13M20 25V8M27 25V3"/></> : kind === 'recharge' ? <path d="M16 27V5M6 15 16 5l10 10"/> : kind === '生视频' ? <><rect x="3" y="7" width="19" height="19" rx="3"/><path d="m22 13 7-5v17l-7-5M10 12l7 5-7 5Z"/></> : kind === '积分退回' ? <><path d="M26 12A11 11 0 0 0 6 8l-3 5h8M6 20a11 11 0 0 0 20 4l3-5h-8"/></> : <><rect x="3" y="4" width="26" height="24" rx="3"/><circle cx="11" cy="12" r="2"/><path d="m4 24 8-7 5 4 5-7 7 9"/></>}
  </svg>;
}

export function TransactionHistory({initialCategory='consumption',hideCategories=false,overview=false}:{initialCategory?:Category;hideCategories?:boolean;overview?:boolean}) {
  useLanguage();
  const [category, setCategory] = useState<Category>(initialCategory);
  const [page, setPage] = useState(1);
  const [period, setPeriod] = useState('all');
  const [summary, setSummary] = useState<{balance:string;consumption:string;recharge:string} | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [entries, setEntries] = useState<LedgerRow[] | null>(null);
  const [display, setDisplay] = useState<DisplayRow[] | null>(null);
  const [failure, setFailure] = useState<'login' | 'forbidden' | 'request' | null>(null);

  useEffect(() => {
    const refresh = () => { setSummary(null); setEntries(null); setDisplay(null); setFailure(null); setPage(1); setAttempt(value => value + 1); };
    window.addEventListener('xzwl-account-changed', refresh);
    return () => window.removeEventListener('xzwl-account-changed', refresh);
  }, []);

  function failed(error: unknown) {
    setSummary(null);
    setEntries(null);
    setDisplay(null);
    setFailure(error instanceof ApiError && error.status === 401 ? 'login'
      : error instanceof ApiError && error.status === 403 ? 'forbidden' : 'request');
  }

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    async function load() {
      try {
        const session = await refreshSession();
        if (signal.aborted) return;
        if (!session.authenticated || !session.user?.id) { setFailure('login'); return; }
        const { data: keys } = await accessListGetApiV1AccessKeys({ signal });
        if (signal.aborted) return;
        const owned = keys.filter(key => key.ownerId === session.user!.id && key.enabled && key.keyId);
        const ledgers = await Promise.all(owned.map(async key => {
          const { data } = await billingEntriesGetApiV1BillingWalletsKeyIdLedger(key.keyId!, { limit: ledgerLimit }, { signal });
          return data.map(row => ({ ...row, walletId: key.keyId!, keyKind: key.kind }));
        }));
        const now = new Date();
        const monthStart = new Date(now.getFullYear(), now.getMonth(), 1).getTime();
        const monthEnd = new Date(now.getFullYear(), now.getMonth() + 1, 1).getTime();
        if (overview) {
          const balances = await Promise.all(owned.map(key => billingBalanceGetApiV1BillingWalletsKeyId(key.keyId!, {signal})));
          const completeMonth = ledgers.every(rows => rows.every(row => Number.isFinite(Date.parse(row.recordedAt ?? '')))
            && (rows.length < ledgerLimit || rows.some(row => Date.parse(row.recordedAt!) < monthStart)));
          const monthly = ledgers.flat().filter(row => Date.parse(row.recordedAt ?? '') >= monthStart && Date.parse(row.recordedAt ?? '') < monthEnd);
          if (!signal.aborted) setSummary({balance: totalPoints(balances.map(item => item.data.availablePoints4)),
            consumption: completeMonth ? totalPoints(monthly.filter(row => row.kind === 'CONSUME').map(row => row.points4), true) : '—',
            recharge: completeMonth ? totalPoints(monthly.filter(row => row.kind === 'RECHARGE').map(row => row.points4)) : '—'});
        }
        const kinds = category === 'all' ? ['CONSUME','REFUND','RECHARGE','CASH_REFUND'] : category === 'consumption' ? ['CONSUME', 'REFUND'] : ['RECHARGE', 'CASH_REFUND'];
        const from = period === 'last-month' ? new Date(now.getFullYear(), now.getMonth() - 1, 1).getTime() : monthStart;
        const to = period === 'last-month' ? monthStart : monthEnd;
        const filtered = ledgers.flat().filter(row => kinds.includes(row.kind ?? '') && (period === 'all' || (Date.parse(row.recordedAt ?? '') >= from && Date.parse(row.recordedAt ?? '') < to)));
        filtered.sort((a, b) => (Date.parse(b.recordedAt ?? '') || 0) - (Date.parse(a.recordedAt ?? '') || 0));
        if (!signal.aborted) setEntries(filtered);
      } catch (error) { if (!signal.aborted) failed(error); }
    }
    void load();
    return () => controller.abort();
  }, [category, attempt, period, overview]);

  useEffect(() => {
    if (!entries) return;
    const controller = new AbortController();
    void Promise.all(entries.slice((page - 1) * pageSize, page * pageSize)
      .map(row => describe(row, controller.signal)))
      .then(rows => { if (!controller.signal.aborted) setDisplay(rows); })
      .catch(error => { if (!controller.signal.aborted) failed(error); });
    return () => controller.abort();
  }, [entries, page]);

  function select(next: Category) {
    if (next === category) return;
    setEntries(null); setDisplay(null); setFailure(null); setPage(1); setCategory(next);
  }
  function retry() {
    setEntries(null); setDisplay(null); setFailure(null); setPage(1); setAttempt(value => value + 1);
  }
  const loading = !failure && display === null;
  const pages = Math.max(1, Math.ceil((entries?.length ?? 0) / pageSize));
  return <section className={`transaction-history${overview ? ' history-overview' : ''}`} aria-label={t("账户历史记录")}>
    {overview && <div className="history-summary">
      {([{kind:'balance',label:'当前积分',value:summary?.balance},{kind:'spent',label:'本月消耗',value:summary?.consumption},{kind:'recharge',label:'本月充值',value:summary?.recharge}]).map(item => <article className={`history-stat history-stat-${item.kind}`} aria-label={t(item.label)} key={item.kind}><span className="history-stat-icon"><HistoryIcon kind={item.kind}/></span><div><span>{t(item.label)}</span><p><strong>{groupedPoints(item.value)}</strong><span>{t("积分")}</span></p></div></article>)}
    </div>}
    <div className="history-toolbar">
    {!hideCategories&&<div className="history-switch" role="group" aria-label={t("记录分类")}>
      {(Object.keys(categories) as Category[]).map(value => <button type="button" key={value}
        aria-pressed={category === value} onClick={() => select(value)}>{t(overview ? {all:"全部记录",consumption:"消费记录",recharge:"充值记录"}[value] : categories[value])}</button>)}
    </div>}
    {overview && <label className="history-period"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 2v6M17 2v6M3 11h18"/></svg><select aria-label={t("时间范围")} value={period} onChange={event => {setDisplay(null);setEntries(null);setFailure(null);setPage(1);setPeriod(event.target.value);}}><option value="all">{t("全部时间")}</option><option value="month">{t("本月")}</option><option value="last-month">{t("上月")}</option></select></label>}
    </div>
    <p className="history-scope">{formatMessage("显示本人可用账户最近的已入账流水（每个账户最多 {0} 条），按时间倒序排列；不含未到账订单及更早记录。", ledgerLimit)}</p>
    {category === 'recharge' && <p className="history-scope">{t("金额单位为元，积分单独列出。现金退款时间为发起时间；负积分表示收回，退款完成后显示负金额。")}</p>}
    <div className="history-records" aria-busy={loading}>
      {loading && <p className="history-state" role="status">{formatMessage("正在加载{0}…", t(categories[category]))}</p>}
      {failure === 'login' && <div className="history-state"><p>{t("请登录后查看真实账户记录。")}</p><Link className="btn-primary" to="/user/login">{t("登录后查看")}</Link></div>}
      {failure && failure !== 'login' && <div className="history-state" role="alert"><p>{t(failure === 'forbidden' ? '无权查看这些账户记录。' : '记录加载失败，请稍后重试。')}</p><button type="button" onClick={retry}>{t("重新加载")}</button></div>}
      {display && !display.length && <p className="history-state" role="status">{t(category === 'all' ? '暂无账户记录' : category === 'consumption' ? '暂无消耗记录' : '暂无充值记录')}</p>}
      {!!display?.length && <>
        <div className="table-wrap"><table aria-label={t(categories[category])}><thead><tr>
          <th scope="col">{t("时间")}</th>
          {category === 'all' ? <><th scope="col">{t("类型")}</th><th scope="col" className="history-number">{t("金额（元）")}</th><th scope="col" className="history-number">{t("积分变动")}</th></> : category === 'consumption' ? <><th scope="col">{t("业务类型")}</th><th scope="col">{t("生成参数")}</th><th scope="col" className="history-number">{t("金额（元）")}</th><th scope="col" className="history-number">{t("积分变动")}</th></>
            : <><th scope="col">{t("订单号")}</th><th scope="col" className="history-number">{t("支付金额（元）")}</th><th scope="col" className="history-number">{t("到账积分（积分）")}</th></>}
          <th scope="col" className="history-number">{t("变动后余额（积分）")}</th><th scope="col">{t("状态")}</th>
        </tr></thead><tbody>{display.map(row => <tr key={`${row.walletId}:${row.kind}:${row.requestId}`}>
          <td className="history-time">{time(row.recordedAt)}</td>
          {category === 'all' ? <><td><span className="history-type">{overview && <HistoryIcon kind={row.kind === 'RECHARGE' ? 'recharge' : row.business}/>}<span>{t(overview ? (row.business === '生图' ? '图片生成' : row.business === '生视频' ? '视频生成' : row.business) : row.business)}</span></span></td><td className="history-number">{formatAmount(row.money)}</td></> : category === 'consumption' ? <><td className="history-business">{t(row.business)}</td><td className="history-generation"><strong>{row.model}</strong>{row.parameters && <small>{row.parameters}</small>}</td><td className="history-number">{formatAmount(row.money)}</td></>
            : <><td className="history-id">{row.requestId ?? '—'}</td><td className="history-number">{formatAmount(row.money)}</td></>}
          <td className={`history-number ${row.points4?.startsWith('-') ? 'history-debit' : row.points4 && !/^0(?:\.0+)?$/.test(row.points4) ? 'history-credit' : ''}`}>{formatAmount(points(row.points4))}</td>
          <td className="history-number history-balance">{formatAmount(row.balanceAfter4)}</td>
          <td><span className="status-pill" data-kind={row.kind}>{t(row.status)}</span></td>
        </tr>)}</tbody></table></div>
        <nav className="history-pagination" aria-label={formatMessage("{0}分页", categories[category])}>
          <span>{formatMessage("共 {0} 条 · 第 {1} / {2} 页", entries?.length, page, pages)}</span>
          <button type="button" disabled={page === 1} onClick={() => { setDisplay(null); setPage(page - 1); }}>{t("上一页")}</button>
          {overview && Array.from({length: pages}, (_, i) => i + 1).filter(value => Math.abs(value - page) <= 2).map(value => <button type="button" key={value} aria-label={formatMessage("第 {0} 页", value)} aria-current={page === value ? 'page' : undefined} onClick={() => {if(value !== page){setDisplay(null);setPage(value);}}}>{value}</button>)}
          <button type="button" disabled={page === pages} onClick={() => { setDisplay(null); setPage(page + 1); }}>{t("下一页")}</button>
        </nav>
      </>}
    </div>
  </section>;
}
