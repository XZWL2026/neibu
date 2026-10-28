import { createEffectScope } from './effect-scope.js';
import { mountWaves } from './hero-waves.js';

export function mountHomeEffects(root) {
  const scopes = [mountWaves].map(mount => {
    const scope = createEffectScope();
    try { mount(root, scope); } catch (error) {
      scope.dispose();
      console.warn('Home visual effect unavailable; using static artwork.', error);
    }
    return scope;
  });
  return () => scopes.forEach(scope => scope.dispose());
}
