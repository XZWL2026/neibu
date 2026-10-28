import { formatAmount } from './shared/formatAmount';

export function units4(value: string | null | undefined): bigint | null {
  if (!value || !/^-?\d+(?:\.\d{1,4})?$/.test(value)) return null;
  const negative = value.startsWith('-');
  const [whole, fraction = ''] = value.replace(/^-/, '').split('.');
  const units = BigInt(whole!) * 10000n + BigInt(fraction.padEnd(4, '0'));
  return negative ? -units : units;
}
export function decimal4(units: bigint): string {
  const magnitude = units < 0n ? -units : units;
  return `${units < 0n ? '-' : ''}${magnitude / 10000n}.${String(magnitude % 10000n).padStart(4, '0')}`;
}
export function money(value: string | null | undefined): string {
  return units4(value) === null ? '—' : `¥ ${formatAmount(value)}`;
}
export function points(value: string | null | undefined): string {
  return units4(value) === null ? '—' : `${formatAmount(value)} 积分`;
}
export function beforePoints(after: string | null, change: string | null): string | null {
  const a = units4(after), c = units4(change);
  return a === null || c === null ? null : decimal4(a - c);
}
export const shown = (value: string | null | undefined) => value || '—';
export const when = (value: string | null | undefined) => value ? value.replace('T', ' ').replace(/Z$/, ' UTC') : '—';
