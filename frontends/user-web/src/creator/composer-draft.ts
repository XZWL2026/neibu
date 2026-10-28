import type { CanvasGenerationSettings } from '../canvas/model';
import type { ComposerMaterial } from './ComposerMaterials';
import { draftTimestamp } from './draft-metadata';

export type ComposerDraftScope = 'home' | 'commerce' | 'create';
export type ComposerDraft = {
  updatedAt?: number;
  prompt: string; selection: CanvasGenerationSettings | null;
  mode: 'auto' | 'reference' | 'text' | 'omni' | 'frames' | null;
  materials: (ComposerMaterial | null)[];
  bindings: { token: string; fileId: string }[];
};
const key = (owner: string, scope: ComposerDraftScope, kind: 'IMAGE' | 'VIDEO') => `xzwl-composer-draft:${JSON.stringify([owner, scope, kind])}`;
export const draftSaveError = '草稿保存失败，请允许本站本地存储后重试。';

export function readComposerDraft(owner: string | null, scope: ComposerDraftScope, kind: 'IMAGE' | 'VIDEO'): ComposerDraft | null {
  if (!owner) return null;
  try {
    const draft = JSON.parse(localStorage.getItem(key(owner, scope, kind)) || 'null') as ComposerDraft | null;
    if (!draft || typeof draft.prompt !== 'string' || draft.prompt.length > 16000
      || !(kind === 'IMAGE' ? ['auto', 'reference', 'text'] : [null, 'omni', 'frames', 'text']).includes(draft.mode)
      || !Array.isArray(draft.materials) || draft.materials.length > 50
      || draft.materials.some(file => file !== null && (!file || typeof file.fileId !== 'string' || !file.fileId || typeof file.name !== 'string'
        || ![undefined, 'IMAGE', 'VIDEO', 'AUDIO'].includes(file.kind) || (kind === 'IMAGE' && file.kind && file.kind !== 'IMAGE')))
      || !Array.isArray(draft.bindings) || draft.bindings.some(binding => !binding || typeof binding.token !== 'string' || typeof binding.fileId !== 'string')) return null;
    // A half-edited quantity must not discard the prompt and uploaded references.
    const value = draft.selection;
    const selection = value && value.type === kind && typeof value.model === 'string' && typeof value.aspectRatio === 'string'
      && typeof value.resolution === 'string' && Number.isInteger(value.count) && value.count >= 1
      && Number.isInteger(value.durationSeconds) ? value : null;
    return { ...draft, selection, materials: draft.materials.map(file => file && { ...file, preview: '' }) };
  } catch { return null; }
}
export function saveComposerDraft(owner: string | null, scope: ComposerDraftScope, kind: 'IMAGE' | 'VIDEO', draft: ComposerDraft): boolean {
  if (!owner) return true;
  try {
    const { updatedAt: _updatedAt, ...fields } = draft;
    const content = { ...fields, materials: draft.materials.map(file => file && { ...file, preview: '' }) };
    localStorage.setItem(key(owner, scope, kind), JSON.stringify({ ...content, updatedAt: draftTimestamp(content, readComposerDraft(owner, scope, kind)) }));
    return true;
  } catch { return false; }
}
