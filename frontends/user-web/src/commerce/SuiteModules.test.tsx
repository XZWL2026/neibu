import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { axe } from 'vitest-axe';
import { SuiteModules } from './SuiteModules';

afterEach(cleanup);
const options = [{ value: 'one', label: '白底图', description: '干净背景' }, { value: 'two', label: '核心卖点', description: '展示商品特点' }];
it('selects modules with native checkboxes and respects the submission lock', async () => {
  const change = vi.fn();
  const view = render(<SuiteModules options={options} selected={['one']} onChange={change} />);
  expect(screen.getByRole('checkbox', { name: /白底图/ })).toBeChecked();
  fireEvent.click(screen.getByRole('checkbox', { name: /核心卖点/ }));
  expect(change).toHaveBeenCalledWith(['one', 'two']);
  expect((await axe(view.container, { rules: { 'color-contrast': { enabled: false } } })).violations).toEqual([]);
  view.rerender(<SuiteModules options={options} selected={['one']} onChange={change} disabled />);
  expect(screen.getByRole('checkbox', { name: /白底图/ })).toBeDisabled();
});
it('keeps required modules selected and bounds quantities by the model', () => {
  const change=vi.fn(), count=vi.fn();
  render(<SuiteModules options={options} selected={['one','two']} onChange={change} required={['one']} counts={{one:1,two:2}} maxCount={2} onCountChange={count} />);
  expect(screen.getByRole('checkbox',{name:/白底图/})).toBeDisabled();
  expect(screen.getByRole('button',{name:'增加核心卖点数量'})).toBeDisabled();
  fireEvent.click(screen.getByRole('button',{name:'减少核心卖点数量'}));
  expect(count).toHaveBeenCalledWith('two',1);
  expect(screen.getByRole('button',{name:'减少白底图数量'})).toBeDisabled();
});
