import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { SuiteProjectSelect } from './SuiteProjectSelect';

const projects = [
  { id: 'first', name: '春季套图', date: '2026/10/13 18:42:22' },
  { id: 'second', name: 'Summer collection', date: '2026/10/14 10:20:00' },
  { id: 'third', name: 'Winter collection', date: '' },
];
afterEach(cleanup);
const trigger = () => screen.getByRole('combobox', { name: '选择生成项目' });

it('opens on the selected project and selects a different project with the pointer', () => {
  const change = vi.fn();
  render(<SuiteProjectSelect projects={projects} value="first" onChange={change} />);
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  fireEvent.click(trigger());
  const options = within(screen.getByRole('listbox')).getAllByRole('option');
  expect(options[0]).toHaveAttribute('aria-selected', 'true');
  expect(trigger()).toHaveAttribute('aria-activedescendant', options[0].id);
  fireEvent.click(options[1]);
  expect(change).toHaveBeenCalledExactlyOnceWith('second');
  expect(trigger()).toHaveFocus();
  expect(trigger()).toHaveAttribute('aria-expanded', 'false');
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
});

it('supports keyboard navigation without committing until confirmation', () => {
  const change = vi.fn();
  render(<SuiteProjectSelect projects={projects} value="second" onChange={change} />);
  trigger().focus();
  fireEvent.keyDown(trigger(), { key: 'ArrowDown' });
  const options = screen.getAllByRole('option');
  expect(trigger()).toHaveAttribute('aria-activedescendant', options[1].id);
  fireEvent.keyDown(trigger(), { key: 'End' });
  expect(trigger()).toHaveAttribute('aria-activedescendant', options[2].id);
  fireEvent.keyDown(trigger(), { key: 'Home' });
  fireEvent.keyDown(trigger(), { key: 'ArrowDown' });
  fireEvent.keyDown(trigger(), { key: 'ArrowUp' });
  expect(change).not.toHaveBeenCalled();
  fireEvent.keyDown(trigger(), { key: 'Enter' });
  expect(change).toHaveBeenCalledExactlyOnceWith('first');
  expect(trigger()).toHaveFocus();
});

it('cancels pending selection on Escape, outside click and focus leaving', () => {
  const change = vi.fn();
  render(<><SuiteProjectSelect projects={projects} value="first" onChange={change} /><button>下一个控件</button></>);
  for (const close of [
    () => fireEvent.keyDown(trigger(), { key: 'Escape' }),
    () => fireEvent.pointerDown(document.body),
    () => fireEvent.blur(trigger(), { relatedTarget: screen.getByText('下一个控件') }),
  ]) {
    fireEvent.click(trigger());
    fireEvent.keyDown(trigger(), { key: 'End' });
    close();
    expect(trigger()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  }
  expect(change).not.toHaveBeenCalled();
});

it('supports typeahead and commits the active project on Tab', () => {
  const change = vi.fn();
  render(<SuiteProjectSelect projects={projects} value="first" onChange={change} />);
  fireEvent.keyDown(trigger(), { key: 's' });
  fireEvent.keyDown(trigger(), { key: 'u' });
  expect(trigger()).toHaveAttribute('aria-activedescendant', screen.getAllByRole('option')[1].id);
  fireEvent.keyDown(trigger(), { key: 'Tab' });
  expect(change).toHaveBeenCalledExactlyOnceWith('second');
  expect(trigger()).toHaveAttribute('aria-expanded', 'false');
});

it('does not emit a change when choosing the current project', () => {
  const change = vi.fn();
  render(<SuiteProjectSelect projects={projects} value="first" onChange={change} />);
  fireEvent.keyDown(trigger(), { key: ' ' });
  fireEvent.keyDown(trigger(), { key: ' ' });
  expect(change).not.toHaveBeenCalled();
  expect(trigger()).toHaveAttribute('aria-expanded', 'false');
});

it('closes a stale menu on external selection changes and disables an empty list', () => {
  const change = vi.fn();
  const { rerender } = render(<SuiteProjectSelect projects={projects} value="first" onChange={change} />);
  fireEvent.click(trigger());
  rerender(<SuiteProjectSelect projects={projects} value="second" onChange={change} />);
  expect(trigger()).toHaveTextContent('Summer collection');
  expect(trigger()).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(trigger());
  rerender(<SuiteProjectSelect projects={[]} value="" onChange={change} />);
  expect(trigger()).toBeDisabled();
  expect(trigger()).toHaveTextContent('暂无项目');
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  expect(change).not.toHaveBeenCalled();
});
