import { ApiError, mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements,
  type ResponseMediaInterfacesHttpMediaTaskControllerTaskResponse as Task, type ResponseMediaInterfacesHttpMediaTaskControllerEnhancementResponse } from '../api';

export type Enhancement = ResponseMediaInterfacesHttpMediaTaskControllerEnhancementResponse;
export type EnhancementRequest = {
  owner: string; taskId: string; outputIndex: number; fileId: string; target: string;
  quoteId: string; key: string; amount: string; points: string; billableSeconds: number;
  taskRef?: string; state: 'SUBMITTING' | 'UNCERTAIN' | 'ACCEPTED';
};
const storageKey = (owner: string, taskId: string, index: number) => `xzwl-enhancement:${owner}:${taskId}:${index}`;
export function readEnhancement(owner: string | null | undefined, taskId: string, index: number, fileId: string): EnhancementRequest | null {
  if (!owner) return null;
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey(owner, taskId, index)) || 'null') as EnhancementRequest | null;
    if (!value || value.owner !== owner || value.taskId !== taskId || value.outputIndex !== index || value.fileId !== fileId
      || typeof value.key !== 'string' || !value.key || typeof value.quoteId !== 'string' || !value.quoteId
      || !['720p', '1080p', '2K', '4K'].includes(value.target) || !Number.isInteger(value.billableSeconds) || value.billableSeconds < 1
      || !/^\d+\.\d{4}$/.test(value.amount) || !/^\d+\.\d{4}$/.test(value.points)
      || !['SUBMITTING', 'UNCERTAIN', 'ACCEPTED'].includes(value.state)
      || (value.taskRef !== undefined && (typeof value.taskRef !== 'string' || !value.taskRef))) return null;
    return { ...value, state: value.taskRef ? 'ACCEPTED' : 'UNCERTAIN' };
  } catch { return null; }
}
export function saveEnhancement(request: EnhancementRequest): boolean {
  try {
    const key = storageKey(request.owner, request.taskId, request.outputIndex);
    const stored = JSON.parse(sessionStorage.getItem(key) || 'null') as EnhancementRequest | null;
    // Another explicitly confirmed attempt owns this slot; a late older flight must not replace it.
    if (stored?.key && stored.key !== request.key) return false;
    const accepted = stored?.taskRef && !request.taskRef
      ? { ...request, taskRef: stored.taskRef, state: 'ACCEPTED' as const } : request;
    sessionStorage.setItem(key, JSON.stringify(accepted)); return true;
  } catch { return false; }
}
export function clearEnhancement(request: EnhancementRequest) {
  try {
    const key = storageKey(request.owner, request.taskId, request.outputIndex);
    const stored = JSON.parse(sessionStorage.getItem(key) || 'null') as EnhancementRequest | null;
    if (stored?.key !== request.key || stored.quoteId !== request.quoteId) return;
    sessionStorage.removeItem(key);
  } catch { /* Server versions remain authoritative. */ }
}
const flights = new Map<string, Promise<Task>>();
export class EnhancementRejection extends Error { constructor(readonly status: number) { super('增强提交未受理'); } }
export function submitEnhancement(request: EnhancementRequest, firstAttempt: boolean): Promise<Task> {
  const flightKey = `${request.owner}:${request.key}`;
  const existing = flights.get(flightKey);
  if (existing) return existing;
  const flight = mediaSubmitEnhancementPostApiV1MediaTasksTaskIdOutputsOutputIndexEnhancements(
    request.taskId, request.outputIndex, { quoteId: request.quoteId }, { headers: { 'Idempotency-Key': request.key } }
  ).then(({ data }) => {
    if (!data?.id || data.operation !== 'ENHANCEMENT') throw new Error('Invalid enhancement response');
    saveEnhancement({ ...request, taskRef: data.id, state: 'ACCEPTED' });
    return data;
  }).catch(cause => {
    if (firstAttempt && cause instanceof ApiError && [400, 401, 403, 404, 409, 422].includes(cause.status)) {
      clearEnhancement(request); throw new EnhancementRejection(cause.status);
    }
    saveEnhancement({ ...request, state: 'UNCERTAIN' }); throw cause;
  }).finally(() => flights.delete(flightKey));
  flights.set(flightKey, flight);
  return flight;
}
export const enhancementActive = (item: Pick<Enhancement, 'status' | 'refundStatus'>) =>
  !['SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'TIMED_OUT'].includes(item.status ?? '') || item.refundStatus === 'PENDING';
