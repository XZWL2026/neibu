import { expect, it } from 'vitest';
import { formatAmount } from './shared/formatAmount';

it.each([
  ['100.0000', '100'], ['0.1000', '0.1'], ['12.3400', '12.34'],
  ['12.3456', '12.3456'], ['0.0001', '0.0001'], ['0.0000', '0'],
  ['-50.1000', '-50.1'], ['¥ 1,280.0000 积分', '¥ 1,280 积分'],
  ['9007199254740993.1200', '9007199254740993.12'], ['—', '—'],
])('formats %s for display without rounding', (raw, shown) => {
  expect(formatAmount(raw)).toBe(shown);
});
