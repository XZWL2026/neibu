import { formatAmount } from '../../../shared/formatAmount';
import { accountStatusLabels, type UserSession } from '../userSession';

function points4(value: string | null | undefined): bigint | null {
  if (typeof value !== 'string' || !/^\d+(?:\.\d{1,4})?$/.test(value)) return null;
  const [whole, fraction = ''] = value.split('.');
  return BigInt(whole) * 10000n + BigInt(fraction.padEnd(4, '0'));
}

export function generationBalance(account: UserSession | null, quote: string | undefined) {
  // Standalone consumers retain server validation; the production App always supplies
  // the real account provider. Never manufacture a balance when context is absent.
  if (!account) return { affordable: true, notice: null };
  const key = account.currentKey;
  if (account.status !== 'ready' || !account.authenticated || !key?.enabled
    || key.kind !== '媒体 Key' || key.relationship !== 'Owner') {
    return { affordable: false, notice: account.status !== 'ready' ? accountStatusLabels[account.status] : '当前账户没有可用的本人媒体 Key，请检查账户开通与密钥权限。' };
  }
  const available = points4(account.availablePoints4), cost = points4(quote);
  if (available === null) return { affordable: false, notice: '账户余额暂不可用，请刷新账户后重试。' };
  const affordable = cost !== null && available > 0n && available >= cost;
  return { affordable, notice: affordable ? null
    : `可用积分不足：当前 ${formatAmount(account.availablePoints4)} 积分，预计需要 ${formatAmount(quote)} 积分，请先充值。` };
}
