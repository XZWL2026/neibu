import { createEffectScope } from './effect-scope.js';
import { createCarousel } from './circular-carousel.js';
import { mountReflections } from './reflective-cards.js';

export function mountCarousel(root, items, onChange, onActivate) {
  const scope = createEffectScope();
  try {
    const carousel = createCarousel(root, items, { onChange, onActivate }, scope);
    mountReflections(root, scope, carousel);
    return {
      select: index => carousel.focusIndex(index),
      step: delta => carousel.stepBy(delta),
      togglePaused: () => carousel.togglePaused(),
      dispose: () => scope.dispose(),
    };
  } catch (error) { scope.dispose(); throw error; }
}
