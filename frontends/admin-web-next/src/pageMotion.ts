/** Follow the sidebar's order while keeping the surrounding workspace steady. */
export function createPageMotion() {
  let active: Animation | null = null;
  function cancel() {
    active?.cancel();
    active = null;
  }
  function play(element: HTMLElement | null, direction: number) {
    cancel();
    if (!element?.animate) return;
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
    const animation = element.animate(
      reduced
        ? [{ opacity: .92 }, { opacity: 1 }]
        : [{ opacity: .24, transform: `translateY(${direction < 0 ? -14 : 14}px)` }, { opacity: 1, transform: 'none' }],
      { duration: reduced ? 80 : 260, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' }
    );
    active = animation;
    animation.onfinish = () => { if (active === animation) active = null; };
  }
  return { play, cancel };
}
