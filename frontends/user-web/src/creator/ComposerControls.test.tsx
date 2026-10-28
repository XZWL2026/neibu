import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ComposerModeMenu, sortedResolutions } from './ComposerControls';

afterEach(cleanup);
it('opens the current mode, supports keyboard selection and restores focus on Escape', () => {
  const change = vi.fn();
  render(<ComposerModeMenu label="图片生成模式" icon="image" value="auto" onChange={change} options={[{value:'auto',label:'智能图片'},{value:'text',label:'文生图片'}]} />);
  const trigger = screen.getByRole('button', { name: '图片生成模式' });
  fireEvent.click(trigger);
  expect(screen.getByRole('menuitemradio', { name: '智能图片' })).toHaveFocus();
  expect(screen.getByRole('menuitemradio', { name: '智能图片' })).toHaveAttribute('aria-checked','true');
  fireEvent.keyDown(document.activeElement!, { key:'ArrowDown' });
  expect(screen.getByRole('menuitemradio', { name: '文生图片' })).toHaveFocus();
  fireEvent.click(document.activeElement!);
  expect(change).toHaveBeenCalledWith('text');
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  fireEvent.keyDown(trigger, {key:'ArrowDown'});
  fireEvent.keyDown(document.activeElement!, {key:'Escape'});
  expect(trigger).toHaveFocus();
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  fireEvent.click(trigger); fireEvent.pointerDown(document.body);
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
});
it('sorts available resolutions without modifying options or inventing capabilities', () => {
  const values = ['4K','1080p','480p','720p'];
  expect(sortedResolutions(values)).toEqual(['480p','720p','1080p','4K']);
  expect(values).toEqual(['4K','1080p','480p','720p']);
  expect(sortedResolutions(['4K','1K','2K'])).toEqual(['1K','2K','4K']);
});
