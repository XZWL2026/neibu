/** Display only: retain significant decimal digits and never convert accounting strings to Number. */
export function formatAmount(value: string | number | null | undefined): string {
  return String(value ?? '—').replace(/(\.\d*?[1-9])0+(?=\D|$)|\.0+(?=\D|$)/g, '$1');
}
