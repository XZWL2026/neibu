import { afterEach, expect, it, vi } from 'vitest';
import { createCarousel } from './circular-carousel.js';
import { createEffectScope } from './effect-scope.js';

let scope;
afterEach(() => { scope?.dispose(); document.body.replaceChildren(); vi.unstubAllGlobals(); });

function mount() {
  vi.stubGlobal('matchMedia', () => Object.assign(new EventTarget(), { matches: true }));
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} });
  vi.stubGlobal('IntersectionObserver', class { observe() {} disconnect() {} });
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1));
  vi.stubGlobal('cancelAnimationFrame', vi.fn());
  const root = document.createElement('div');
  root.innerHTML = '<div class="circular-carousel__stage"><div class="circular-carousel__camera"><div class="circular-carousel__ring">'
    + Array.from({ length: 5 }, (_, i) => `<div class="circular-carousel__card" data-cc-index="${i}"><a href="/commerce/tool-${i}"><img class="commerce-art" /></a></div>`).join('') + '</div></div></div>';
  root.getBoundingClientRect = () => ({ width: 1200, height: 500, left: 0, top: 0 });
  document.body.append(root);
  scope = createEffectScope();
  const onActivate = vi.fn(), onChange = vi.fn();
  const carousel = createCarousel(root, Array.from({ length: 5 }, () => ({ label: 'tool' })), { onActivate, onChange }, scope);
  return { root, carousel, onActivate, onChange };
}

it('keeps all real links and activates the clicked destination even on a side card', () => {
  const { root, onActivate } = mount();
  const link = root.querySelector('a');
  const click = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 });
  link.dispatchEvent(click);
  expect(click.defaultPrevented).toBe(true);
  expect(onActivate).toHaveBeenCalledWith(0);
  expect(root.querySelectorAll('a[href^="/commerce/"]')).toHaveLength(5);
});

it('suppresses navigation after dragging and releases listeners on unmount', () => {
  const { root, carousel, onActivate } = mount();
  carousel.state.suppressClick = true;
  const click = new MouseEvent('click', { bubbles: true, cancelable: true });
  root.querySelector('a').dispatchEvent(click);
  expect(click.defaultPrevented).toBe(true);
  expect(onActivate).not.toHaveBeenCalled();
  scope.dispose();
  root.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  expect(onActivate).not.toHaveBeenCalled();
});

it('supports reduced-motion selection, wrapping, keyboard and explicit pause', () => {
  const { root, carousel, onChange } = mount();
  carousel.focusIndex(4);
  expect(onChange).toHaveBeenLastCalledWith(4);
  root.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
  expect(onChange).toHaveBeenLastCalledWith(0);
  expect(carousel.togglePaused()).toBe(true);
  expect(carousel.togglePaused()).toBe(false);
});
