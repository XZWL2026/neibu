import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { CanvasFeatureBackdrop } from './CanvasFeatureBackdrop';

afterEach(cleanup);

it('keeps the canvas action usable when WebGL is unavailable', () => {
  const { container } = render(<CanvasFeatureBackdrop theme="light"><button>开始创作</button></CanvasFeatureBackdrop>);
  expect(container.firstElementChild).toHaveAttribute('data-prism-theme', 'light');
  expect(container.querySelector('.canvas-prism-viewport')).toHaveAttribute('aria-hidden', 'true');
  expect(screen.getByRole('button', { name: '开始创作' })).toBeEnabled();
  expect(container.querySelector('video')).toBeNull();
});

it('updates the prism palette with the theme', () => {
  const { container, rerender } = render(<CanvasFeatureBackdrop theme="light" />);
  rerender(<CanvasFeatureBackdrop theme="dark" />);
  expect(container.firstElementChild).toHaveAttribute('data-prism-theme', 'dark');
});
