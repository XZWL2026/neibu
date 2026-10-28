import { ApiError, mediaRegeneratePostApiV1MediaTasksTaskIdRegenerations } from '../api';
import { createProductionMediaPort } from '../canvas/production-api';
import type { VideoSubmission } from './VideoTaskResults';
import { readVideoSubmission, saveVideoSubmission } from './video-submission-recovery';

// Component lifetime must not determine whether an already dispatched request exists.
const flights = new Map<string, Promise<{ taskRef: string }>>();
export class CreationRejection extends Error {
  constructor(readonly status: number) { super('提交被拒绝'); }
}
export function submitCreation(owner: string | null, entry: VideoSubmission, firstAttempt: boolean) {
  const request = entry.request!;
  const key = `${owner}:${request.settings.type}:${request.requestRef}`;
  const existing = flights.get(key);
  if (existing) return existing;
  const commerce = request.requestRef.startsWith('commerce-');
  const saved = readVideoSubmission(owner, request.settings.type, commerce);
  if (saved?.key === entry.key && saved.taskRef) return Promise.resolve({ taskRef: saved.taskRef });
  const operation = entry.replacesTaskId
    ? mediaRegeneratePostApiV1MediaTasksTaskIdRegenerations(entry.replacesTaskId, { headers: { 'Idempotency-Key': request.requestRef } }).then(({ data }) => {
      if (!data.id) throw new Error('未返回任务 ID');
      return { taskRef: data.id };
    }) : createProductionMediaPort().submit(request);
  const flight = operation.then(result => {
    saveVideoSubmission(owner, { ...entry, taskRef: result.taskRef, state: undefined }, request.settings.type);
    return result;
  }).catch(cause => {
    // Preserve the actual flight's admission provenance for every remounted subscriber.
    if (cause instanceof ApiError && ((firstAttempt && [400, 401, 403, 404, 422].includes(cause.status)) || Boolean(entry.replacesTaskId && cause.status === 409))) {
      saveVideoSubmission(owner, null, request.settings.type, commerce);
      throw new CreationRejection(cause.status);
    }
    saveVideoSubmission(owner, { ...entry, state: 'UNCERTAIN' }, request.settings.type);
    throw cause;
  }).finally(() => flights.delete(key));
  flights.set(key, flight);
  return flight;
}
