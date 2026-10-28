import { useEffect, useRef, useState, type Dispatch, type ReactNode, type SetStateAction } from 'react';
import { storageUploadReferenceImagePostApiV1FilesReferenceImages, storageUploadReferenceMediaPostApiV1FilesReferenceMedia,
  type ResponseMediaApplicationCatalogGenerationCatalogServiceOption as ModelOption } from '../api';
import { mediaReference, resolvePreview } from '../canvas/production-api';
import { ComposerMaterials, materialReviewIssue, typedMaterialLabel, type ComposerMaterial } from '../creator/ComposerMaterials';
import { readReferenceAudioDuration } from '../creator/reference-audio-duration';
import { uploadErrorMessage } from '../creator/upload-error';
import type { MediaInputCapability } from '../creator/types';
import type { HomeGenerationMode } from './home-entry';
import { formatMessage, t } from '../i18n';

export type HomeMaterials = (ComposerMaterial | null)[];
const imageBytes = 20 * 1024 * 1024;

export function homeMaterialCapability(kind: 'image' | 'video', mode: HomeGenerationMode, model?: ModelOption): MediaInputCapability {
  const omni = kind === 'video' && mode === 'omni' ? model?.omniReference : null;
  return omni ? {
    acceptedKinds: ['image', 'video', 'audio'],
    acceptedMimeTypes: ['image/png', 'image/jpeg', 'video/mp4', 'video/quicktime', 'audio/mpeg', 'audio/wav', 'audio/x-wav', 'audio/wave'],
    acceptedSources: ['upload', 'library'], maxFileBytes: imageBytes, maxItems: omni.maxTotal,
    maxItemsByKind: { image: omni.maxImages, video: omni.maxVideos, audio: omni.maxAudios },
    maxFileBytesByKind: { video: omni.maxVideoBytes, audio: omni.maxAudioBytes }, verified: true,
  } : {
    acceptedKinds: ['image'], acceptedMimeTypes: ['image/png', 'image/jpeg'], acceptedSources: ['upload', 'library'],
    maxFileBytes: imageBytes, maxItems: mode === 'text' || mode === 'omni' ? 0 : model?.maxReferenceImages ?? 0, verified: Boolean(model),
  };
}

export function homeMaterialIssues(kind: 'image' | 'video', mode: HomeGenerationMode, materials: HomeMaterials, model: ModelOption | undefined, reviewModel: string, checkReview = true): string[] {
  const assets = materials.filter((file): file is ComposerMaterial => Boolean(file));
  const count = (kind: string) => assets.filter(file => (file.kind ?? 'IMAGE') === kind).length;
  const duration = (kind: string) => assets.filter(file => file.kind === kind).reduce((sum, file) => sum + (file.durationSeconds ?? 0), 0);
  return materials.map((file, index) => {
    if (!file) return '';
    if (mode === 'text') return '当前模式不使用素材，请切换模式或移除';
    const type = file.kind ?? 'IMAGE';
    if (kind === 'image') {
      if (type !== 'IMAGE') return '图片生成仅支持图片参考';
      if (index >= (model?.maxReferenceImages ?? 0)) return `超出当前模型上限（${model?.maxReferenceImages ?? 0} 张）`;
    } else {
      if (file.portraitId && mode !== 'omni') return '已审核人像仅支持全能参考，请切换模式';
      if (mode === 'frames') {
        if (type !== 'IMAGE') return '首尾帧仅支持图片，请切换模式或移除';
        if (index >= (model?.maxReferenceImages ?? 0)) return `当前模型不支持${index === 0 ? '首帧' : index === 1 ? '尾帧' : '更多素材'}`;
        if (index === 1 && !materials[0]) return '请先添加首帧，尾帧不能单独提交';
      } else if (mode === 'omni') {
        const omni = model?.omniReference;
        if (!omni) return '当前模型不支持全能参考，请切换模型或移除素材';
        if (assets.length > omni.maxTotal) return `参考素材总数最多 ${omni.maxTotal} 份`;
        const maximum = type === 'VIDEO' ? omni.maxVideos : type === 'AUDIO' ? omni.maxAudios : omni.maxImages;
        if (count(type) > maximum) return `当前类型最多 ${maximum} 份素材`;
        if (type !== 'IMAGE' && (!Number.isFinite(file.durationSeconds) || (file.durationSeconds ?? 0) <= 0)) return '素材缺少可信时长，请重新上传';
        if (type === 'VIDEO' && duration(type) > omni.maxVideoDurationSeconds) return `视频总时长不能超过 ${omni.maxVideoDurationSeconds} 秒`;
        if (type === 'AUDIO' && duration(type) > omni.maxAudioDurationSeconds) return `音频总时长不能超过 ${omni.maxAudioDurationSeconds} 秒`;
        if (type === 'VIDEO' && (file.sizeBytes ?? 0) > omni.maxVideoBytes || type === 'AUDIO' && (file.sizeBytes ?? 0) > omni.maxAudioBytes) return '素材超过当前模型大小限制，请更换素材或模型';
        if (!omni.audioOnly && type === 'AUDIO' && !count('IMAGE') && !count('VIDEO')) return '当前模型不支持仅音频参考，请添加图片或视频';
      }
    }
    return checkReview ? materialReviewIssue(file, reviewModel) : '';
  });
}

export function HomeMaterialPicker({ owner, kind, mode, model, reviewModel, materials, onMaterials, disabled, enabled, onBusyChange, onQueueBusyChange, onDialogOpenChange, onFeedbackChange, children }: {
  owner: string | null; kind: 'image' | 'video'; mode: HomeGenerationMode; model?: ModelOption; reviewModel: string;
  materials: HomeMaterials; onMaterials: Dispatch<SetStateAction<HomeMaterials>>; disabled: boolean; enabled: boolean;
  onBusyChange(busy: boolean): void; onQueueBusyChange(busy: boolean): void; onDialogOpenChange(open: boolean): void; onFeedbackChange(hasFeedback: boolean): void; children: ReactNode;
}) {
  const [uploading, setUploading] = useState(false), [error, setError] = useState('');
  const alive = useRef(true), pending = useRef(false);
  const localPreviews = useRef(new Set<string>());
  const context = JSON.stringify([owner, kind, mode, model?.model, enabled]);
  const latest = useRef({ context, materials, model }); latest.current = { context, materials, model };
  useEffect(() => { alive.current = true; return () => { alive.current = false; localPreviews.current.forEach(url => URL.revokeObjectURL(url)); localPreviews.current.clear(); }; }, []);
  useEffect(() => {
    if (!enabled) return;
    const active = new Set(materials.map(file => file?.preview));
    for (const url of localPreviews.current) if (!active.has(url)) { URL.revokeObjectURL(url); localPreviews.current.delete(url); }
  }, [materials, enabled]);
  useEffect(() => { onBusyChange(uploading); return () => onBusyChange(false); }, [uploading, onBusyChange]);
  useEffect(() => { setError(''); }, [owner, kind, mode, model?.model]);
  const capability = homeMaterialCapability(kind, mode, model);
  const issues = homeMaterialIssues(kind, mode, materials, model, reviewModel);
  const count = materials.filter(Boolean).length;
  const reviewing = materials.some(file => file && materialReviewIssue(file, reviewModel) === '审核中');
  const frames = kind === 'video' && mode === 'frames';
  const slots = frames ? Array.from({ length: Math.max(2, materials.length) }, (_, index) => ({
    file: materials[index] ?? null, label: index === 0 ? '首帧' : index === 1 ? '尾帧' : typedMaterialLabel(materials, index),
    available: index < (model?.maxReferenceImages ?? 0), issue: issues[index] || (index === 0 && !materials[0] ? '请先添加首帧' : undefined),
  })) : materials.map((file, index) => ({ file, label: kind === 'image' ? `参考图 ${index + 1}` : typedMaterialLabel(materials, index), available: true, issue: issues[index] }));
  const nextIndex = !enabled ? -1 : frames ? slots.findIndex(slot => !slot.file && slot.available)
    : materials.filter(Boolean).length >= (capability.maxItems ?? 0) ? -1 : materials.includes(null) ? materials.indexOf(null) : materials.length;
  const write = (material: ComposerMaterial, index: number) => {
    const next = [...latest.current.materials]; next[index] = material;
    latest.current = { ...latest.current, materials: next }; onMaterials(next);
  };
  const audioIssue = (duration: number | null | undefined, index: number) => {
    const omni = latest.current.model?.omniReference;
    const others = latest.current.materials.filter((file, position) => position !== index && file?.kind === 'AUDIO');
    const used = others.reduce((sum, file) => sum + (file?.durationSeconds ?? 0), 0);
    if (!omni || !Number.isFinite(duration) || (duration ?? 0) <= 0 || others.some(file => !Number.isFinite(file?.durationSeconds) || (file?.durationSeconds ?? 0) <= 0)) return '素材缺少可信时长，请重新上传';
    return (duration ?? 0) > Math.max(0, omni.maxAudioDurationSeconds - used) ? `音频总时长不能超过 ${omni.maxAudioDurationSeconds} 秒，剩余 ${Math.max(0, omni.maxAudioDurationSeconds - used)} 秒；请更换音频` : '';
  };
  const upload = async (file: File, index: number): Promise<false | ComposerMaterial> => {
    if (disabled || !enabled || pending.current || !owner || !model || index < 0 || index >= (capability.maxItems ?? 0)) return false;
    const type = ['image/png', 'image/jpeg'].includes(file.type) ? 'IMAGE' : ['video/mp4', 'video/quicktime'].includes(file.type) ? 'VIDEO'
      : ['audio/mpeg', 'audio/wav', 'audio/x-wav', 'audio/wave'].includes(file.type) ? 'AUDIO' : null;
    const maxBytes = type === 'VIDEO' ? model.omniReference?.maxVideoBytes ?? 0 : type === 'AUDIO' ? model.omniReference?.maxAudioBytes ?? 0 : imageBytes;
    if (!type || !capability.acceptedMimeTypes.includes(file.type) || !file.size || file.size > maxBytes) {
      setError(mode === 'omni' ? '仅支持当前模型大小限制内的 JPG/PNG、MP4/MOV、MP3/WAV 素材' : '参考图片仅支持 20MB 以内的 PNG 或 JPEG'); return false;
    }
    const maximum = capability.maxItemsByKind?.[type.toLowerCase() as 'image' | 'video' | 'audio'];
    if (maximum !== undefined && latest.current.materials.filter((item, position) => position !== index && item && (item.kind ?? 'IMAGE') === type).length >= maximum) {
      setError(`当前类型最多 ${maximum} 份素材`); return false;
    }
    const isCurrent = () => alive.current && latest.current.context === context;
    pending.current = true; setUploading(true); onBusyChange(true); setError('');
    try {
      if (type === 'AUDIO') {
        let duration: number;
        try { duration = await readReferenceAudioDuration(file); } catch { if (isCurrent()) setError('无法读取音频时长，请重新导出 MP3/WAV 文件后重试'); return false; }
        if (!isCurrent()) return false;
        const issue = audioIssue(duration, index); if (issue) { setError(issue); return false; }
      }
      const { data } = await (mode === 'omni' ? storageUploadReferenceMediaPostApiV1FilesReferenceMedia({ file }) : storageUploadReferenceImagePostApiV1FilesReferenceImages({ file }));
      if (!data.fileId || !mediaReference(data.fileId).fileId) throw new Error('上传未返回有效文件 ID');
      if (!isCurrent()) return false;
      if (type === 'AUDIO') { const issue = audioIssue(data.durationSeconds, index); if (issue) { setError(issue); return false; } }
      let preview = '';
      try { preview = await resolvePreview(data.fileId); } catch { /* Preserve the uploaded identity when preview signing is unavailable. */ }
      if (!isCurrent()) return false;
      if (!preview) {
        try { preview = URL.createObjectURL(file); localPreviews.current.add(preview); } catch { /* Keep the file and its name when local previews are unavailable. */ }
      }
      const material: ComposerMaterial = { fileId: data.fileId, name: file.name, preview, kind: type, durationSeconds: data.durationSeconds,
        sizeBytes: data.sizeBytes ?? file.size, reviewPending: true, reviewStatus: 'SUBMITTING' };
      write(material, index); return material;
    } catch (cause) { if (isCurrent()) setError(uploadErrorMessage(cause, mode === 'omni')); return false; }
    finally { pending.current = false; if (alive.current) { setUploading(false); onBusyChange(false); } }
  };
  return <div className="ym-home-materials video-composer" data-enabled={enabled}>
    <ComposerMaterials inline onFeedbackChange={onFeedbackChange} selectionKey={`${kind}:${mode}:${model?.model}`} onQueueBusyChange={onQueueBusyChange} onDialogOpenChange={onDialogOpenChange}
      review={enabled ? { owner, model: reviewModel, onSelecting: setUploading,
        onReview: (fileId, reviewId, reviewStatus) => onMaterials(current => current.map(file => file?.fileId === fileId ? { ...file, reviewId, reviewStatus, reviewPending: !reviewId } : file)) } : undefined}
      capability={capability} slots={slots} nextIndex={nextIndex} disabled={disabled || !enabled || !model} busy={uploading} error={error}
      hint={enabled && model && capability.maxItems === 0 ? '当前模型不支持素材' : kind === 'image' && mode === 'reference' && !materials.some(Boolean) ? '请添加参考图片' : undefined}
      onUpload={upload} onReuse={(material, index) => {
        if (disabled || !enabled || pending.current || !model || index < 0 || index >= (capability.maxItems ?? 0)) return false;
        const next = [...latest.current.materials]; next[index] = material;
        // Library material metadata is rechecked against this model before reuse.
        const issue = homeMaterialIssues(kind, mode, next, model, reviewModel, false)[index];
        if (issue) { setError(issue); return false; }
        write(material, index); return true;
      }} onRemove={index => onMaterials(current => kind === 'image' ? current.filter((_, position) => position !== index) : current.map((file, position) => position === index ? null : file))}>
      {children}
    </ComposerMaterials>
    {(uploading || count > 0) && <p className="ym-material-status" role="status">{uploading ? t('正在上传素材…') : formatMessage('已添加 {0} 份素材', count)}{!uploading && reviewing ? ` · ${t('审核中')}` : ''}</p>}
  </div>;
}
