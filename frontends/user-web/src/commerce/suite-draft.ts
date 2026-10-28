import { ResponseMediaInterfacesHttpProductSuiteControllerItemResponseModule as Modules, type ResponseMediaApplicationSuiteProductSuitesRequest as SuiteRequest } from '../api';
import { draftTimestamp } from '../creator/draft-metadata';
import type { ProductReference } from './ProductReferences';
import type { CommerceTool } from './commerce-tools';

export type SuiteDraft = { request: SuiteRequest; files: ProductReference[]; referenceFiles: ProductReference[]; modelSource: string; updatedAt?: number };
const key = (owner: string, tool: CommerceTool) => `xzwl-commerce-suite-draft:${JSON.stringify([owner, tool])}`;
const referencesValid = (files: unknown): files is ProductReference[] => Array.isArray(files) && files.length <= 8
  && files.every(file => file && typeof file.fileId === 'string' && file.fileId.length > 0 && file.fileId.length <= 2000 && typeof file.name === 'string' && file.name.length <= 1000);

export function readSuiteDraft(owner: string | null, tool: CommerceTool): SuiteDraft | null {
  if (!owner) return null;
  try {
    const draft = JSON.parse(localStorage.getItem(key(owner, tool)) ?? 'null') as SuiteDraft | null;
    const request = draft?.request;
    if (!draft || !request || !referencesValid(draft.files) || !referencesValid(draft.referenceFiles)
      || !['upload', 'ai'].includes(draft.modelSource)
      || !['productName', 'sellingPoints', 'language', 'model', 'aspectRatio', 'resolution', 'templateId'].every(field => typeof request[field as keyof SuiteRequest] === 'string' && (request[field as keyof SuiteRequest] as string).length <= 16000)
      || !Number.isInteger(request.templateVersion) || !Array.isArray(request.modules) || request.modules.length > 30
      || !request.modules.every(module => Object.hasOwn(Modules, module))) return null;
    const options = request.options;
    if (options && (typeof options !== 'object'
      || [options.platform, options.replicationMode, options.garmentView].some(value => value != null && typeof value !== 'string')
      || [options.whiteBackground3d, options.whiteBackgroundMannequin].some(value => value != null && typeof value !== 'boolean')
      || options.enhancements != null && (!Array.isArray(options.enhancements) || !options.enhancements.every(value => typeof value === 'string'))
      || options.moduleCounts != null && (!Array.isArray(options.moduleCounts) || !options.moduleCounts.every(value => value && Object.hasOwn(Modules, value.module) && Number.isInteger(value.count) && value.count > 0 && value.count <= 100)))) return null;
    return { ...draft, request: { ...request, fileIds: [...draft.files, ...draft.referenceFiles].map(file => file.fileId) },
      files: draft.files.map(file => ({ ...file, preview: '' })), referenceFiles: draft.referenceFiles.map(file => ({ ...file, preview: '' })) };
  } catch { return null; }
}

export function saveSuiteDraft(owner: string, tool: CommerceTool, draft: SuiteDraft): boolean {
  try {
    const { updatedAt: _updatedAt, ...fields } = draft;
    const content = { ...fields, files: draft.files.map(file => ({ ...file, preview: '' })), referenceFiles: draft.referenceFiles.map(file => ({ ...file, preview: '' })) };
    localStorage.setItem(key(owner, tool), JSON.stringify({ ...content, updatedAt: draftTimestamp(content, readSuiteDraft(owner, tool)) }));
    return true;
  } catch { return false; }
}

export function hasSuiteDraftContent(draft: SuiteDraft): boolean {
  return Boolean(draft.request.productName?.trim() || draft.request.sellingPoints?.trim() || draft.files.length || draft.referenceFiles.length);
}
