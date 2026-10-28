import { ResponseMediaInterfacesHttpProductSuiteControllerItemResponseModule as Modules,
  type ResponseMediaApplicationSuiteProductSuitesRequest as SuiteRequest,
  type ResponseMediaInterfacesHttpProductSuiteControllerItemResponseModule as Module } from '../api';

export type PendingSuite = { key: string; request: SuiteRequest; regeneration?: { suiteId: string; module: Module; sourceTaskId: string } };
const storageKey = (owner: string, tool: string) => `xzwl-product-suite-pending:${owner}${tool === 'product-suite' ? '' : `:${tool}`}`;
export function readPendingSuite(owner: string, tool = 'product-suite'): PendingSuite | null {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey(owner, tool)) || 'null') as PendingSuite | null;
    const request = value?.request;
    if (!value || typeof value.key !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(value.key) || !request
      || !['productName', 'sellingPoints', 'language', 'model', 'aspectRatio', 'resolution', 'templateId'].every(key => typeof request[key as keyof SuiteRequest] === 'string')
      || !Number.isInteger(request.templateVersion) || !Array.isArray(request.fileIds) || !request.fileIds.length || !request.fileIds.every(id => typeof id === 'string')
      || !Array.isArray(request.modules) || !request.modules.length || !request.modules.every(module => Object.hasOwn(Modules, module))) return null;
    if (value.regeneration && (typeof value.regeneration.suiteId !== 'string' || typeof value.regeneration.sourceTaskId !== 'string'
      || !Object.hasOwn(Modules, value.regeneration.module))) return null;
    return value;
  } catch { return null; }
}
export function savePendingSuite(owner: string, value: PendingSuite, tool = 'product-suite'): boolean {
  try {
    const current = readPendingSuite(owner, tool);
    if (current && current.key !== value.key) return false;
    localStorage.setItem(storageKey(owner, tool), JSON.stringify(value));
    return true;
  } catch { return false; }
}
export function clearPendingSuite(owner: string, requestKey: string, tool = 'product-suite'): void {
  try {
    if (readPendingSuite(owner, tool)?.key === requestKey) localStorage.removeItem(storageKey(owner, tool));
  } catch { /* Keep the original key available for another idempotent confirmation. */ }
}
