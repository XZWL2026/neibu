import { afterEach, expect, it, vi } from 'vitest';
import { createPageMotion } from './pageMotion';

afterEach(() => vi.unstubAllGlobals());
function surface(reduced = false) {
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: reduced })));
  const animations: Array<{ cancel: ReturnType<typeof vi.fn>; onfinish: (() => void) | null }> = [];
  const element = document.createElement('div');
  element.animate = vi.fn(() => {
    const animation = { cancel: vi.fn(), onfinish: null };
    animations.push(animation);
    return animation as unknown as Animation;
  });
  return { element, animations };
}

it('replaces in-flight navigation and releases it when complete', () => {
  const motion = createPageMotion(), { element, animations } = surface();
  motion.play(element, 1);
  motion.play(element, -1);
  expect(animations[0]!.cancel).toHaveBeenCalledOnce();
  expect(element.animate).toHaveBeenLastCalledWith(
    [{ opacity: .72, transform: 'translateY(-6px)' }, { opacity: 1, transform: 'none' }],
    expect.objectContaining({ duration: 180 })
  );
  animations[0]!.onfinish?.(); // A stale completion must not clear the new animation.
  motion.cancel();
  expect(animations[1]!.cancel).toHaveBeenCalledOnce();
  motion.play(element, 1);
  animations[2]!.onfinish?.();
  motion.cancel();
  expect(animations[2]!.cancel).not.toHaveBeenCalled();
});

it('uses short non-spatial feedback when reduced motion is requested', () => {
  const { element } = surface(true);
  createPageMotion().play(element, 1);
  expect(element.animate).toHaveBeenCalledWith(
    [{ opacity: .92 }, { opacity: 1 }], expect.objectContaining({ duration: 80 })
  );
});

it('leaves content visible when the animation API is unavailable', () => {
  const element = document.createElement('div');
  element.textContent = 'Current page';
  const motion = createPageMotion();
  expect(() => motion.play(element, 1)).not.toThrow();
  expect(element.style.opacity).toBe('');
  expect(element.hidden).toBe(false);
});
