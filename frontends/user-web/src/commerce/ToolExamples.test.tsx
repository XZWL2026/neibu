import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { ToolExamples } from './ToolExamples';
import { commerceTools, type CommerceTool } from './commerce-tools';

afterEach(cleanup);
const samples: [CommerceTool, string, number][] = [
  ['product-suite', 'product-set', 5], ['product-retouch', 'product-retouch', 2],
  ['image-replica', 'hot-seller-replicate', 3], ['model-wear', 'apparel-group', 4],
  ['apparel-set', 'apparel-set', 4], ['detail-page', 'detail-page', 10],
];
it.each(samples)('%s shows three manually selected sets of real example assets', (tool, prefix, count) => {
  render(<ToolExamples tool={tool} />);
  expect(screen.getByRole('heading', { level: 1, name: commerceTools[tool].title })).toBeVisible();
  expect(screen.getByText('效果示例')).toBeVisible();
  for (const index of [1, 2, 3]) {
    const dot = screen.getByRole('button', { name: `切换到第${index}个示例` });
    fireEvent.click(dot);
    expect(dot).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getAllByRole('button', { pressed: true })).toHaveLength(1);
    const images = screen.getAllByRole('img');
    expect(images).toHaveLength(count);
    expect(images.every(image => image.getAttribute('src')?.includes(`${prefix}-${index}-`))).toBe(true);
    expect(new Set(images.map(image => image.getAttribute('src'))).size).toBe(count);
  }
});
it('uses the distinct product, complete long-page and module images for a detail example', () => {
  render(<ToolExamples tool="detail-page" />);
  expect(screen.getByRole('img', { name: '产品图 3' })).toHaveAttribute('src', expect.stringContaining('detail-page-1-1-3.webp'));
  expect(screen.getByRole('img', { name: '详情长图' })).toHaveAttribute('src', expect.stringContaining('detail-page-1-2-1.webp'));
  expect(screen.getByRole('img', { name: '模块图 6' })).toHaveAttribute('src', expect.stringContaining('detail-page-1-3-6.webp'));
});
it('opens the selected real image in the native dialog and restores focus after Escape cancellation and close', () => {
  render(<ToolExamples tool="product-retouch" />);
  const trigger = screen.getByRole('button', { name: '放大精修图' });
  trigger.focus(); fireEvent.click(trigger);
  const dialog = screen.getByRole('dialog', { name: '精修图' });
  expect(dialog.tagName).toBe('DIALOG');
  expect(within(dialog).getByRole('img')).toHaveAttribute('src', screen.getByRole('img', { name: '精修图' }).getAttribute('src'));
  expect(within(dialog).getByRole('button', { name: '关闭示例预览' })).toHaveFocus();
  fireEvent(dialog, new Event('cancel', { bubbles: false, cancelable: true }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(trigger).toHaveFocus();
  fireEvent.click(trigger);
  fireEvent.click(screen.getByRole('button', { name: '关闭示例预览' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(trigger).toHaveFocus();
});
it('resets the slide and closes the old image when switching tools', () => {
  const view = render(<ToolExamples tool="product-retouch" />);
  fireEvent.click(screen.getByRole('button', { name: '切换到第3个示例' }));
  fireEvent.click(screen.getByRole('button', { name: '放大精修图' }));
  view.rerender(<ToolExamples tool="image-replica" />);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '切换到第1个示例' })).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByRole('img', { name: '高度复刻' })).toHaveAttribute('src', expect.stringContaining('hot-seller-replicate-1-2.webp'));
});
