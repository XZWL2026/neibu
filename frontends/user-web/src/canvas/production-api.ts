import {
  ApiError,
  canvasSavePutApiV1CanvasesCanvasId,
  canvasAddMediaReferencePostApiV1CanvasesCanvasIdMediaReferences,
  mediaSubmitPostApiV1MediaTasks,
  mediaSubmitPostApiV1MediaDepthTasks,
  mediaOptionsGetApiV1MediaDepthOptions,
  mediaOptionsGetApiV1MediaGenerationOptions,
  mediaQuotePostApiV1MediaQuotes,
  mediaGetGetApiV1MediaTasksTaskId,
  mediaResultsGetApiV1MediaTasksTaskIdResults,
  storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl,
  type ResponseCanvasDomainCanvasContent,
  type ResponseCanvasInterfacesCanvasControllerCanvasResponse as CanvasResponse,
} from '../api';
import type { CanvasDocument, CanvasNode, CanvasAssetReference, CanvasEdge, CanvasGroup } from './model';
import type { MediaTaskPort, CanvasMediaTaskSnapshot, CanvasGenerationOption } from './media-task-port';
import { canonicalizeCanvasDocument } from './persistence';
import { copyGenerationDefaults } from './generation-defaults';
import { isSafeCanvasPreviewRef } from './preview-ref';

export type { CanvasResponse };

export function canvasError(error: unknown): string {
  if (error instanceof ApiError) {
    const messages: Record<number, string> = {
      400: '参数无效，请检查画布内容或生成设置后重试',
      401: '登录已失效，正在跳转登录',
      403: '权限不足或会话校验失败，请确认账号权限后重试',
      404: '画布或文件不存在、已删除或无权访问，请检查 ID',
      409: '版本或请求冲突：编辑内容已保留，请另存为新画布，或明确放弃编辑后重新加载',
      422: '参数无法处理，请修改提示词、模型、分辨率或媒体引用后重试',
      502: '生成服务暂不可用，请稍后重试；当前编辑已保留',
      503: '生成服务暂不可用，请稍后重试；当前编辑已保留',
    };
    return messages[error.status] ?? `服务请求失败（${error.status}），请稍后重试`;
  }
  if (error instanceof TypeError) return '网络连接失败，请检查网络后重试；当前编辑已保留';
  return error instanceof Error ? error.message : '网络请求失败，请重试；当前编辑已保留';
}

export function mediaReference(value: string) {
  if (/^[A-Za-z0-9_-]{1,128}$/.test(value)) return { fileId: value };
  if (isSafeCanvasPreviewRef(value)) return { httpsUrl: value };
  throw new Error('媒体仅支持文件 ID 或 HTTPS 地址，请重新选择');
}

// Canvas's contracted properties map stores editor metadata as strings. Signed previews
// are resolved from Storage on read and never become part of the persisted document.
export function canvasContent(document: CanvasDocument): ResponseCanvasDomainCanvasContent {
  if (document.nodes.length > 2000 || document.edges.length > 5000) throw new Error('画布超出保存容量，请拆分为多个画布');
  return {
    viewport: { ...document.viewport },
    // The server edge DTO accepts only these three fields. Rich references belong
    // to the target's string properties, not to an uncontracted content field.
    edges: document.edges.map((edge, index) => ({ source: edge.source, target: edge.target, id: `edge-${index}` })),
    nodes: document.nodes.map((node) => {
      if (!/^[A-Za-z0-9_-]{1,128}$/.test(node.id)) throw new Error('节点 ID 无效，请新建该节点');
      const properties: Record<string, string> = { x: String(node.position.x), y: String(node.position.y) };
      if (node.kind === 'text') properties.text = node.text;
      else if (node.kind === 'generation') {
        properties.settings = JSON.stringify(node.settings);
        properties.prompt = node.prompt ?? '';
        properties.taskRef = node.taskRef ?? '';
        properties.taskState = node.taskState;
        properties.message = node.message ?? '';
        if (node.promptBindings) properties.promptBindings = JSON.stringify(node.promptBindings);
        if (node.selectedOutputId) properties.selectedOutputId = node.selectedOutputId;
        if (node.runs) properties.runs = JSON.stringify(node.runs);
      } else {
        mediaReference(node.asset.assetRef);
        properties.publicId = node.asset.assetRef;
        if (node.asset.portraitId) properties.portraitId = node.asset.portraitId;
        if (node.asset.reviewStatus !== undefined) properties.reviewStatus = node.asset.reviewStatus;
        if (node.asset.reviewId !== undefined) properties.reviewId = node.asset.reviewId;
        if (node.asset.reviewPending !== undefined) properties.reviewPending = String(node.asset.reviewPending);
        properties.alt = node.asset.alt;
        properties.sourceTaskRef = node.sourceTaskRef ?? '';
        if (node.asset.durationSeconds !== undefined) properties.durationSeconds = String(node.asset.durationSeconds);
        if (node.asset.sizeBytes !== undefined) properties.sizeBytes = String(node.asset.sizeBytes);
      }
      const groupIndex = document.groups?.findIndex(group => group.nodeIds.includes(node.id)) ?? -1;
      if (groupIndex >= 0) {
        const group = document.groups![groupIndex];
        properties.editorGroup = JSON.stringify({ id: group.id, name: group.name, order: groupIndex, index: group.nodeIds.indexOf(node.id) });
      }
      const references = document.edges.flatMap((edge, index) => edge.target === node.id
        ? [{ ...edge, wireId: `edge-${index}` }] : []);
      if (references.length) properties.editorReferences = JSON.stringify(references);
      if (Object.values(properties).some(value => value.length > 65536)) throw new Error('节点内容或生成历史超出保存容量，请拆分画布或复制节点后继续创作');
      return { id: node.id, kind: node.kind, properties };
    }),
  };
}

export async function resolvePreview(reference: string): Promise<string> {
  const ref = mediaReference(reference);
  if (ref.httpsUrl) return ref.httpsUrl;
  const response = await storageCreateAccessUrlGetApiV1FilesFileIdAccessUrl(ref.fileId!);
  if (!isSafeCanvasPreviewRef(response.data.url)) throw new Error('文件预览不可用：Storage 未返回 HTTPS 地址，请重试');
  return response.data.url;
}

export async function readCanvasDocument(record: CanvasResponse): Promise<CanvasDocument> {
  if (record.deleted) throw new Error('画布已删除，请打开其他画布');
  const nodes = await Promise.all(record.content.nodes.map(async (node, index): Promise<CanvasNode> => {
    const p = node.properties;
    const base = { id: node.id, version: 1 as const, position: { x: Number(p.x ?? 80 + index * 40), y: Number(p.y ?? 80) } };
    if (node.kind === 'text') return { ...base, kind: 'text', text: p.text ?? '' };
    if (node.kind === 'generation') return {
      ...base, kind: 'generation', prompt: p.prompt ?? '', settings: p.settings === undefined ? copyGenerationDefaults('IMAGE') : JSON.parse(p.settings), taskRef: p.taskRef || null,
      taskState: (p.taskState === 'submitting' && !p.taskRef && !p.runs ? 'failed' : p.taskState || 'idle') as Extract<CanvasNode, { kind: 'generation' }>['taskState'], message: p.taskState === 'submitting' && !p.taskRef ? '上次提交中断，请保留原参数重试' : p.message || null,
      ...(p.promptBindings !== undefined ? { promptBindings: JSON.parse(p.promptBindings) } : {}),
      ...(p.selectedOutputId !== undefined ? { selectedOutputId: p.selectedOutputId } : {}),
      ...(p.runs !== undefined ? { runs: JSON.parse(p.runs) } : {}),
    };
    if (node.kind !== 'image' && node.kind !== 'video' && node.kind !== 'audio') throw new Error('画布包含不支持的节点，已停止加载以避免丢失数据');
    if (p.reviewPending !== undefined && p.reviewPending !== 'true' && p.reviewPending !== 'false') {
      throw new Error('画布内容无法安全读取，已停止加载以避免覆盖数据');
    }
    const reference = p.publicId ?? p.fileId ?? p.url ?? p.src;
    mediaReference(reference);
    // A temporarily unavailable preview must not hide the server's saved canvas.
    let previewRef = '';
    try { previewRef = await resolvePreview(reference); }
    catch (error) { if (error instanceof ApiError && error.status === 401) throw error; }
    return { ...base, kind: node.kind, sourceTaskRef: p.sourceTaskRef || null,
      ...(Object.keys(p).length === 1 && p.publicId ? { referenceOnly: true } : {}),
      asset: { kind: node.kind, assetRef: reference, ...(p.portraitId ? { portraitId: p.portraitId } : {}), alt: p.alt || (node.kind === 'video' ? '生成视频' : node.kind === 'audio' ? '参考音频' : '生成图片'), previewRef,
        ...(p.reviewStatus !== undefined ? { reviewStatus: p.reviewStatus as CanvasAssetReference["reviewStatus"] } : {}),
        ...(p.reviewId !== undefined ? { reviewId: p.reviewId } : {}),
        ...(p.reviewPending !== undefined ? { reviewPending: p.reviewPending === 'true' } : {}),
        ...(p.durationSeconds !== undefined ? { durationSeconds: Number(p.durationSeconds) } : {}),
        ...(p.sizeBytes !== undefined ? { sizeBytes: Number(p.sizeBytes) } : {}) } };
  }));
  const metadataError = () => new Error('画布编辑器数据无法安全读取，已停止加载以避免覆盖数据');
  const groups = new Map<string, { group: CanvasGroup; order: number; members: { id: string; index: number }[] }>();
  const edgeMetadata = new Map<string, CanvasEdge>();
  for (const node of record.content.nodes) {
    if (node.properties.editorGroup !== undefined) {
      const value = JSON.parse(node.properties.editorGroup);
      if (!value || typeof value.id !== 'string' || typeof value.name !== 'string'
        || !Number.isSafeInteger(value.order) || value.order < 0 || !Number.isSafeInteger(value.index) || value.index < 0) throw metadataError();
      const existing = groups.get(value.id);
      if (existing && (existing.group.name !== value.name || existing.order !== value.order || existing.members.some(member => member.index === value.index))) throw metadataError();
      const item = existing ?? { group: { id: value.id, name: value.name, nodeIds: [] }, order: value.order, members: [] as { id: string; index: number }[] };
      item.members.push({ id: node.id, index: value.index }); groups.set(value.id, item);
    }
    if (node.properties.editorReferences !== undefined) {
      const values = JSON.parse(node.properties.editorReferences);
      if (!Array.isArray(values)) throw metadataError();
      for (const value of values) {
        const wire = record.content.edges.find(edge => edge.id === value?.wireId);
        if (!wire || wire.target !== node.id || value.source !== wire.source || value.target !== wire.target || edgeMetadata.has(value.wireId)) throw metadataError();
        const { wireId: _wireId, ...edge } = value; edgeMetadata.set(value.wireId, edge);
      }
    }
  }
  const orderedGroups = [...groups.values()].sort((a, b) => a.order - b.order);
  if (new Set(orderedGroups.map(item => item.order)).size !== orderedGroups.length) throw metadataError();
  const document = canonicalizeCanvasDocument({ schemaVersion: 1, projectId: record.id,
    updatedAt: record.updatedAt, nodes,
    edges: record.content.edges.map(edge => edgeMetadata.get(edge.id) ?? edge),
    ...(orderedGroups.length ? { groups: orderedGroups.map(item => ({ ...item.group, nodeIds: item.members.sort((a, b) => a.index - b.index).map(member => member.id) })) } : {}),
    viewport: record.content.viewport }, record.id);
  if (!document) throw new Error('画布内容无法安全读取，已停止加载以避免覆盖数据');
  return document;
}

export async function persistCanvas(record: CanvasResponse, document: CanvasDocument, accept: (record: CanvasResponse) => void, name = record.name): Promise<void> {
  const content = canvasContent(document);
  let current = record;
  for (const node of document.nodes) {
    if ((node.kind !== 'image' && node.kind !== 'video') || current.content.nodes.some(saved => saved.id === node.id)) continue;
    current = (await canvasAddMediaReferencePostApiV1CanvasesCanvasIdMediaReferences(current.id, {
      expectedVersion: current.version, nodeId: node.id, kind: node.kind === 'video' ? 'VIDEO' : 'IMAGE', publicId: node.asset.assetRef,
    })).data;
    accept(current);
  }
  current = (await canvasSavePutApiV1CanvasesCanvasId(current.id, { expectedVersion: current.version, content, name })).data;
  accept(current);
}

export function createProductionMediaPort(): MediaTaskPort {
  return {
    async options() {
      const [generation, depth] = await Promise.allSettled([
        mediaOptionsGetApiV1MediaGenerationOptions(), mediaOptionsGetApiV1MediaDepthOptions(),
      ]);
      for (const result of [generation, depth]) {
        if (result.status === 'rejected' && result.reason instanceof ApiError && result.reason.status === 401) throw result.reason;
      }
      if (generation.status === 'rejected' && depth.status === 'rejected') throw generation.reason;
      const options: CanvasGenerationOption[] = generation.status === 'fulfilled'
        ? (generation.value.data.models ?? []).flatMap(option => option.model && option.resolutions?.length && option.aspectRatios?.length
          ? [{ ...option, model: option.model, displayName: option.displayName || option.model,
              resolutions: option.resolutions, aspectRatios: option.aspectRatios }] : []) : [];
      if (depth.status === 'fulfilled' && depth.value.data.enabled && depth.value.data.resolutions?.length) options.push({
        operation: 'DEPTH', type: 'VIDEO', model: 'depth-anything-v2-small', displayName: '深度白模',
        resolutions: depth.value.data.resolutions.filter(value => value === '480p' || value === '720p'),
        aspectRatios: ['source'], maxCount: 1, minDurationSeconds: 0, maxDurationSeconds: 0,
        highResolutionMaxDurationSeconds: depth.value.data.highResolutionMaxDurationSeconds,
      });
      return options;
    },
    async quote(settings) {
      if (settings.operation === 'DEPTH') throw new Error('本地深度处理不使用商业积分试算');
      const { references, operation: _operation, ...parameters } = settings;
      const response = await mediaQuotePostApiV1MediaQuotes({ ...parameters, ...(references ? { references: [...references] } : {}) });
      if (typeof response.data.points !== 'string' || !/^\d+\.\d{4}$/.test(response.data.points)) {
        throw new Error('积分试算暂不可用，请重新选择参数后重试');
      }
      return { points: response.data.points };
    },
    async submit(request) {
      if (request.settings.operation === 'DEPTH') {
        const [sourceFileId] = request.inputAssetRefs;
        const resolution = request.settings.resolution;
        if (request.inputAssetRefs.length !== 1 || !/^[A-Za-z0-9_-]{1,128}$/.test(sourceFileId)
          || (resolution !== '480p' && resolution !== '720p')) throw new Error('请选择一个已上传的视频和有效分辨率');
        const result = await mediaSubmitPostApiV1MediaDepthTasks({ sourceFileId, resolution }, { headers: { 'Idempotency-Key': request.requestRef } });
        if (!result.data.id) throw new Error('任务未返回有效 ID，请使用原请求重试');
        return { taskRef: result.data.id };
      }
      const references = request.references ? request.references.map(reference => {
        if (!/^[A-Za-z0-9_-]{1,128}$/.test(reference.fileId)) throw new Error('参考素材必须使用上传后的文件 ID');
        return { ...reference };
      }) : request.inputAssetRefs.map(mediaReference);
      const { operation: _operation, ...options } = request.settings;
      if (!options.model?.trim() || !options.resolution?.trim()) throw new Error('请填写可用模型与分辨率后重试');
      const count = options.count ?? 1;
      const duration = options.durationSeconds ?? 0;
      if (!Number.isInteger(count) || count < 1 || count > 16 || !Number.isInteger(duration)
        || (options.type === 'VIDEO' ? count !== 1 || duration < 1 || duration > 600 : duration !== 0)) {
        throw new Error('请检查生成数量与视频秒数后重试');
      }
      const result = await mediaSubmitPostApiV1MediaTasks({ ...options, ...(request.referenceMode ? { referenceMode: request.referenceMode } : {}), prompt: request.prompt, references }, {
        headers: { 'Idempotency-Key': request.requestRef },
      });
      if (!result.data.id) throw new Error('任务未返回有效 ID，请使用原请求重试');
      return { taskRef: result.data.id };
    },
    async read({ taskRef }) {
      const task = (await mediaGetGetApiV1MediaTasksTaskId(taskRef)).data;
      if (task.id !== taskRef) throw new Error('任务响应不匹配，请重新同步');
      const base = { taskRef };
      const depth = task.operation === 'DEPTH';
      switch (task.status) {
        case 'ROUTING': return { ...base, state: 'accepted', message: depth ? '等待深度处理' : '生成服务暂不可用，等待路由或供应商就绪' };
        case 'CHARGE_PENDING': return { ...base, state: 'working', message: '正在确认扣费' };
        case 'SUBMIT_PENDING': return { ...base, state: 'working', message: depth ? '等待本地深度处理' : '等待供应商受理' };
        case 'RUNNING': return { ...base, state: 'working', message: depth ? '深度视频处理中' : '生成处理中' };
        case 'ARCHIVING': return { ...base, state: 'working', message: '正在保存生成文件' };
        case 'REFUND_PENDING': return { ...base, state: 'refund_pending', message: '退款处理中，请稍后同步' };
        case 'FAILED': return { ...base, state: 'failed', message: depth ? '深度处理失败，请点击重新生成深度白模' : '生成失败，请检查生成设置后重新生成' };
        case 'TIMED_OUT': return { ...base, state: 'timed_out', message: depth ? '深度处理超时，请点击重新生成深度白模' : '生成超时，已结束，可调整设置后重新生成' };
        case 'SUCCEEDED':
        case 'PARTIAL_SUCCESS': {
          if (task.type !== 'IMAGE' && task.type !== 'VIDEO') throw new Error('生成结果类型不受支持');
          const kind = task.type === 'VIDEO' ? 'video' : 'image';
          const results = (await mediaResultsGetApiV1MediaTasksTaskIdResults(taskRef)).data;
          if (!results.length) throw new Error('结果尚未就绪，请重新同步任务');
          const outputs = await Promise.all(results.map(async (result): Promise<CanvasAssetReference> => {
            if (!result.fileId || !/^[A-Za-z0-9_-]{1,128}$/.test(result.fileId)) throw new Error('生成结果缺少有效文件 ID，请重新同步');
            let previewRef = '';
            try { previewRef = await resolvePreview(result.fileId); }
            catch (error) { if (error instanceof ApiError && error.status === 401) throw error; }
            const duration = depth ? task.references?.[0]?.durationSeconds : undefined;
            return { assetRef: result.fileId, kind, alt: `${depth ? '深度白模视频' : kind === 'video' ? '生成视频' : '生成图片'} ${result.index + 1}`, previewRef,
              ...(typeof duration === 'number' && Number.isFinite(duration) && duration > 0 ? { durationSeconds: duration } : {}) };
          }));
          return { ...base, state: task.status === 'SUCCEEDED' ? 'succeeded' : 'partial_success', outputs,
            message: task.status === 'SUCCEEDED' ? '生成成功' : '部分成功，未完成部分退款流程已结束' } satisfies CanvasMediaTaskSnapshot;
        }
        default: throw new Error('任务状态暂不受支持，请稍后重新同步');
      }
    },
  };
}
