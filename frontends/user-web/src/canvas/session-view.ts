import type { CanvasDocument, CanvasViewport } from './model';

export const canvasContentChanged = (before: CanvasDocument | null, after: CanvasDocument | null) => before !== after
  && (!before || !after || before.nodes !== after.nodes || before.edges !== after.edges || before.groups !== after.groups);

const key = (owner: string, id: string) => `xzwl-canvas-view:${owner}:${id}`;
export function readSessionView(owner: string, id: string, fallback: CanvasViewport): CanvasViewport {
  try {
    const value = JSON.parse(sessionStorage.getItem(key(owner, id)) ?? 'null');
    if (value && Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.zoom) && value.zoom >= .15 && value.zoom <= 2) return { x: value.x, y: value.y, zoom: value.zoom };
  } catch { /* View persistence must not prevent editing. */ }
  return fallback;
}
export function saveSessionView(owner: string, id: string, viewport: CanvasViewport) {
  try { sessionStorage.setItem(key(owner, id), JSON.stringify(viewport)); } catch { /* Session-only view is optional. */ }
}
