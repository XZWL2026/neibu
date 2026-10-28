import { createEffectScope } from './effect-scope.js';
import { mountPixels } from './pixel-background.js';

export function mountPixelEffect(root) {
  const scope = createEffectScope();
  try { mountPixels(root, scope); } catch (error) {
    scope.dispose();
    throw error;
  }
  return () => scope.dispose();
}
