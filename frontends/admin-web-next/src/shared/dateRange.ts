export type DateRange = { from?: string; to?: string };

export function shanghaiToday(now = Date.now()): string {
  return new Date(now + 28_800_000).toISOString().slice(0, 10);
}

export function shiftDate(date: string, days: number): string {
  return new Date(Date.parse(date + 'T00:00:00Z') + days * 86_400_000).toISOString().slice(0, 10);
}

export function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const instant = Date.parse(value + 'T00:00:00Z');
  return Number.isFinite(instant) && new Date(instant).toISOString().slice(0, 10) === value;
}

export function validRange(range: DateRange): boolean {
  return (!range.from || validDate(range.from)) && (!range.to || validDate(range.to))
    && (!range.from || !range.to || range.from <= range.to);
}

export function presetRange(days: number): DateRange {
  if (days === 0) return {};
  const to = shanghaiToday();
  return { from: shiftDate(to, 1 - Math.max(1, days)), to };
}
